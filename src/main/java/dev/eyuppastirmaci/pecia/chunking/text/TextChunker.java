package dev.eyuppastirmaci.pecia.chunking.text;

import dev.eyuppastirmaci.pecia.chunking.DocumentChunker;
import dev.eyuppastirmaci.pecia.chunking.internal.SourceText;

import dev.eyuppastirmaci.pecia.content.Chunk;
import dev.eyuppastirmaci.pecia.content.ChunkMetadata;
import dev.eyuppastirmaci.pecia.content.Document;
import dev.eyuppastirmaci.pecia.content.LineRange;
import dev.eyuppastirmaci.pecia.tokenization.TokenCounter;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static java.util.Objects.requireNonNull;

public final class TextChunker implements DocumentChunker {

    private final TokenCounter tokenCounter;
    private final int contentBudget;
    private final int overlapTokens;

    public TextChunker(TokenCounter tokenCounter, int maxTokens, int overlapTokens) {
        this.tokenCounter = requireNonNull(tokenCounter, "tokenCounter");
        var identity = requireNonNull(tokenCounter.identity(), "tokenizer identity");

        if (maxTokens <= identity.specialTokenCount() || maxTokens > identity.maxInputTokens()) {
            throw new IllegalArgumentException("maxTokens must be greater than " + identity.specialTokenCount()
                    + " and at most " + identity.maxInputTokens() + ", including special tokens");
        }

        this.contentBudget = maxTokens - identity.specialTokenCount();

        if (overlapTokens < 0 || overlapTokens >= contentBudget) {
            throw new IllegalArgumentException("overlapTokens must be non-negative and less than " + contentBudget);
        }

        this.overlapTokens = overlapTokens;
    }

    /**
     * Splits extracted text into token-bounded original source slices with preferred text boundaries and optional overlap.
     *
     * @param document extracted document whose text is split without normalization
     * @return immutable source-ordered chunks with consecutive indices and UTF-16 offsets, or empty for whitespace-only text
     * @throws NullPointerException if document is null
     * @throws IllegalArgumentException if an indivisible source character cannot fit within the token budget
     */
    @Override
    public List<Chunk> chunk(Document document) {
        requireNonNull(document, "document");
        String text = document.content();

        if (SourceText.skipWhitespace(text, 0) == text.length()) {
            return List.of();
        }

        TextBoundaries boundaries = new TextBoundaries(text);
        List<Chunk> chunks = new ArrayList<>();
        int start = 0;
        int coveredEnd = 0;

        while (coveredEnd < text.length()) {
            int end = findEnd(text, boundaries, start, coveredEnd);

            // Drop overlap when it prevents the next complete source unit from fitting.
            if (end <= coveredEnd) {
                start = coveredEnd;
                end = findEnd(text, boundaries, start, coveredEnd);
            }

            String content = text.substring(start, end);

            if (end <= coveredEnd || SourceText.skipWhitespace(content, 0) == content.length()) {
                throw new IllegalArgumentException("Token budget cannot fit a source character at offset " + start);
            }

            ChunkMetadata metadata = new ChunkMetadata(List.of(), Map.of(
                    "startOffset", Integer.toString(start),
                    "endOffset", Integer.toString(end)));
            LineRange location = new LineRange(boundaries.lineAt(start), boundaries.lineAt(end - 1));
            chunks.add(new Chunk(document.sourcePath(), document.type(), chunks.size(), content, location, metadata));
            coveredEnd = end;

            if (end < text.length()) {
                start = overlapStart(text, boundaries, start, end);
            }
        }

        return List.copyOf(chunks);
    }

    /* Grows through whole words before choosing a preferred boundary whose exact token count is revalidated. */
    private int findEnd(String text, TextBoundaries boundaries, int start, int coveredEnd) {
        int safeEnd = start;
        int first = boundaries.firstWordAfter(start);
        int lastSafeIndex = first - 1;
        int probe = first;
        int stride = 1;

        // Growing batches avoid quadratic rescanning when many source words normalize to zero tokens.
        while (probe < boundaries.wordCount()) {
            int candidate = boundaries.wordEnd(probe);

            if (!fits(text, start, candidate)) {
                break;
            }

            safeEnd = candidate;
            lastSafeIndex = probe;

            if (probe == boundaries.wordCount() - 1) {
                return safeEnd;
            }

            int remaining = boundaries.wordCount() - 1 - probe;
            probe += Math.min(stride, remaining);
            stride = (int) Math.min((long) stride * 2, boundaries.wordCount());
        }

        int low = lastSafeIndex + 1;
        int high = probe - 1;

        // Refine with verified slices; non-monotonic counts may leave spare budget but cannot produce oversized chunks.
        while (low <= high) {
            int middle = low + (high - low) / 2;
            int candidate = boundaries.wordEnd(middle);

            if (fits(text, start, candidate)) {
                safeEnd = candidate;
                low = middle + 1;
            } else {
                high = middle - 1;
            }
        }

        if (safeEnd == start) {
            return splitOversized(text, start, boundaries.wordEnd(first));
        }

        int preferred = boundaries.preferredEnd(Math.max(start, coveredEnd), safeEnd);

        // WordPiece counts can decrease as text grows, so even a shorter preferred slice must be checked.
        if (preferred != safeEnd && fits(text, start, preferred)) {
            return preferred;
        }

        return safeEnd;
    }

    /* Scans safe character boundaries instead of assuming that token counts are monotonic for longer prefixes. */
    private int splitOversized(String text, int start, int limit) {
        int safeEnd = SourceText.skipWhitespace(text, start);
        int offset = safeEnd;

        while (offset < limit) {
            int next = SourceText.nextOffset(text, offset);

            if (!fits(text, start, next)) {
                break;
            }

            safeEnd = next;
            offset = next;

            // Preserve a following whitespace run in one check instead of repeatedly scanning large blank regions.
            int whitespaceEnd = SourceText.skipWhitespace(text, offset);

            if (whitespaceEnd > offset && fits(text, start, whitespaceEnd)) {
                safeEnd = whitespaceEnd;
                offset = whitespaceEnd;
            }
        }

        return safeEnd;
    }

    /* Finds a verified word suffix with bounded probes, allowing less overlap when token counts are non-monotonic. */
    private int overlapStart(String text, TextBoundaries boundaries, int start, int end) {
        if (overlapTokens == 0) {
            return end;
        }

        int low = boundaries.firstWordAfter(start);
        int high = boundaries.firstWordAfter(end - 1) - 1;
        int selected = end;

        while (low <= high) {
            int middle = low + (high - low) / 2;
            int candidate = boundaries.wordEnd(middle);

            // Only an explicitly counted suffix is eligible; finding the mathematically largest overlap is optional.
            if (tokenCounter.count(text.substring(candidate, end)) <= overlapTokens) {
                selected = candidate;
                high = middle - 1;
            } else {
                low = middle + 1;
            }
        }

        return selected;
    }

    private boolean fits(String text, int start, int end) {
        return tokenCounter.count(text.substring(start, end)) <= contentBudget;
    }
}
