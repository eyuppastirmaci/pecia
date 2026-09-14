package dev.eyuppastirmaci.pecia.chunking.source;

import dev.eyuppastirmaci.pecia.chunking.DocumentChunker;
import dev.eyuppastirmaci.pecia.chunking.internal.SourceText;

import dev.eyuppastirmaci.pecia.content.Chunk;
import dev.eyuppastirmaci.pecia.content.ChunkMetadata;
import dev.eyuppastirmaci.pecia.content.Document;
import dev.eyuppastirmaci.pecia.content.LineRange;
import dev.eyuppastirmaci.pecia.tokenization.TokenCounter;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Map;
import java.util.function.IntUnaryOperator;

import static java.util.Objects.requireNonNull;

public final class SourceCodeChunker implements DocumentChunker {

    private final TokenCounter tokenCounter;
    private final int contentBudget;
    private final int overlapTokens;

    public SourceCodeChunker(TokenCounter tokenCounter, int maxTokens) {
        this(tokenCounter, maxTokens, 0);
    }

    public SourceCodeChunker(TokenCounter tokenCounter, int maxTokens, int overlapTokens) {
        this.tokenCounter = requireNonNull(tokenCounter, "tokenCounter");
        var identity = tokenCounter.identity();

        if (maxTokens <= identity.specialTokenCount() || maxTokens > identity.maxInputTokens()) {
            throw new IllegalArgumentException("maxTokens must be greater than " + identity.specialTokenCount()
                    + " and at most " + identity.maxInputTokens() + ", including special tokens");
        }

        contentBudget = maxTokens - identity.specialTokenCount();

        if (overlapTokens < 0 || overlapTokens >= contentBudget) {
            throw new IllegalArgumentException("overlapTokens must be non-negative and less than " + contentBudget);
        }

        this.overlapTokens = overlapTokens;
    }

    /**
     * Splits source text at language-independent boundaries with token-bounded intra-line fallback and best-effort overlap.
     *
     * @param document extracted document whose text, source path, type, and raw-byte content hash remain unchanged
     * @return immutable source-ordered chunks with consecutive zero-based indices, empty heading paths, zero-based UTF-16
     *         startOffset and exclusive endOffset attributes into the extracted text, and one-based inclusive line ranges,
     *         or empty for whitespace-only text
     * @throws NullPointerException if document is null
     * @throws IllegalArgumentException if an indivisible source character cannot fit within the token budget
     */
    @Override
    public List<Chunk> chunk(Document document) {
        String text = document.content();

        if (SourceText.skipWhitespace(text, 0) == text.length()) {
            return List.of();
        }

        SourceCodeBoundaries boundaries = new SourceCodeBoundaries(text);
        SourceText words = new SourceText(text);
        BitSet oversizedWords = new BitSet();
        List<Chunk> chunks = new ArrayList<>();
        int start = 0;
        int coveredEnd = 0;

        while (coveredEnd < text.length()) {
            int end = findEnd(text, boundaries, words, oversizedWords, start, coveredEnd);

            // Discard overlap if it blocks new coverage or would split a line that fits on its own.
            if (end <= coveredEnd && start < coveredEnd) {
                start = coveredEnd;
                end = findEnd(text, boundaries, words, oversizedWords, start, coveredEnd);
            }

            // Keep trailing blank lines with the final non-blank slice after an intra-line split.
            if (end > coveredEnd && end < text.length() && SourceText.skipWhitespace(text, end) == text.length()
                    && !exceedsBudget(text, start, text.length())) {
                end = text.length();
            }

            String content = text.substring(start, end);

            if (end <= coveredEnd || SourceText.skipWhitespace(content, 0) == content.length()) {
                throw new IllegalArgumentException("Token budget cannot fit a source character at offset " + start);
            }

            ChunkMetadata metadata = new ChunkMetadata(List.of(), Map.of(
                    "startOffset", Integer.toString(start),
                    "endOffset", Integer.toString(end)));
            // The exclusive end is not part of the slice, so its last character determines the inclusive final line.
            LineRange location = new LineRange(boundaries.firstLineAfter(start) + 1,
                    boundaries.firstLineAfter(end - 1) + 1);
            chunks.add(new Chunk(document.sourcePath(), document.type(), chunks.size(),
                    content, location, metadata));
            coveredEnd = end;

            if (end < text.length()) {
                start = overlapStart(text, boundaries, words, start, end);
            }
        }

        return List.copyOf(chunks);
    }

    /* Preserves fitting lines before falling back inside the next uncovered line without choosing prose boundaries. */
    private int findEnd(String text, SourceCodeBoundaries boundaries, SourceText words, BitSet oversizedWords,
                        int start, int coveredEnd) {
        int firstContent = SourceText.skipWhitespace(text, start);
        int uncoveredLine = boundaries.firstLineAfter(coveredEnd);
        boolean atLineStart = boundaries.lineStart(uncoveredLine) == coveredEnd;

        // A continued oversized line uses token windows directly instead of recounting its entire remaining suffix.
        if (atLineStart) {
            int safeEnd = growEnd(text, start, boundaries.firstLineAfter(start), boundaries.lineCount() - 1,
                    boundaries::lineEnd);

            if (safeEnd > Math.max(firstContent, coveredEnd)) {
                int preferred = boundaries.preferredEnd(Math.max(firstContent, coveredEnd), safeEnd).orElse(safeEnd);

                // A shorter preferred slice may have more tokens than the verified longer slice.
                if (preferred != safeEnd && exceedsBudget(text, start, preferred)) {
                    return safeEnd;
                }

                return preferred;
            }

            if (start < coveredEnd) {
                return start;
            }
        }

        if (firstContent == text.length()) {
            return start;
        }

        int limit = boundaries.lineEnd(boundaries.firstLineAfter(Math.max(firstContent, coveredEnd)));
        int first = words.firstWordAfter(start);
        int last = words.firstWordAfter(limit - 1);

        // Already oversized units use bounded character probes rather than recounting every complete remaining suffix.
        if (oversizedWords.get(first) && words.wordEnd(first) > firstContent) {
            int unitEnd = Math.min(limit, words.wordEnd(first));
            int end = splitOversizedUnit(text, start, start, unitEnd);

            // Recheck the complete suffix before declaring failure because a longer WordPiece input can be cheaper.
            if (end <= Math.max(firstContent, coveredEnd) && !exceedsBudget(text, start, unitEnd)) {
                return unitEnd;
            }

            return end;
        }

        int safeEnd = growEnd(text, start, first, last, index -> Math.min(limit, words.wordEnd(index)));

        if (safeEnd == start) {
            oversizedWords.set(first);
        }

        if (safeEnd > Math.max(firstContent, coveredEnd)) {
            return safeEnd;
        }

        int unitEnd = Math.min(limit, words.wordEnd(words.firstWordAfter(safeEnd)));

        return splitOversizedUnit(text, start, safeEnd, unitEnd);
    }

    /* Grows and refines exactly counted source slices with bounded probes without assuming additive or monotonic token counts. */
    private int growEnd(String text, int start, int first, int last, IntUnaryOperator candidateEnd) {
        int safeEnd = start;
        int probe = first;
        int lastSafeIndex = probe - 1;
        int stride = 1;

        // Growing batches avoid repeatedly counting every prefix of large regions that normalize to zero tokens.
        while (probe <= last) {
            int candidate = candidateEnd.applyAsInt(probe);

            if (exceedsBudget(text, start, candidate)) {
                break;
            }

            safeEnd = candidate;
            lastSafeIndex = probe;

            if (probe == last) {
                break;
            }

            int remaining = last - probe;
            probe += Math.min(stride, remaining);
            stride = (int) Math.min((long) stride * 2, last - first + 1);
        }

        int low = lastSafeIndex + 1;
        int high = probe - 1;

        // Every retained slice is counted directly; non-monotonic token counts may leave some budget unused.
        while (low <= high) {
            int middle = low + (high - low) / 2;
            int candidate = candidateEnd.applyAsInt(middle);

            if (exceedsBudget(text, start, candidate)) {
                high = middle - 1;
            } else {
                safeEnd = candidate;
                low = middle + 1;
            }
        }

        return safeEnd;
    }

    /* Grows verified character windows without repeatedly rescanning prefixes that contain large zero-token regions. */
    private int splitOversizedUnit(String text, int start, int safeEnd, int limit) {
        return Math.max(safeEnd, growEnd(text, start, safeEnd + 1, limit, offset -> safeOffset(text, offset)));
    }

    /* Prefers complete trailing lines, then word suffixes, then safe character suffixes without reusing the previous start. */
    private int overlapStart(String text, SourceCodeBoundaries boundaries, SourceText words, int start, int end) {
        if (overlapTokens == 0) {
            return end;
        }

        int lastLine = boundaries.firstLineAfter(end - 1);

        if (boundaries.lineEnd(lastLine) == end) {
            int selected = findOverlap(text, end, boundaries.firstLineAfter(start) + 1, lastLine,
                    boundaries::lineStart);

            if (selected < end) {
                return selected;
            }
        }

        int selected = findOverlap(text, end, words.firstWordAfter(start), words.firstWordAfter(end - 1) - 1,
                words::wordEnd);

        if (selected < end) {
            return selected;
        }

        return characterOverlapStart(text, start, end);
    }

    /* Selects only explicitly counted suffixes, allowing less than maximal overlap when token counts are non-monotonic. */
    private int findOverlap(String text, int end, int low, int high, IntUnaryOperator candidateStart) {
        int selected = end;

        while (low <= high) {
            int middle = low + (high - low) / 2;
            int candidate = candidateStart.applyAsInt(middle);

            if (tokenCounter.count(text.substring(candidate, end)) <= overlapTokens) {
                selected = candidate;
                high = middle - 1;
            } else {
                low = middle + 1;
            }
        }

        return selected;
    }

    /* Searches safe suffix starts with bounded probes when a partial identifier or long line has no fitting word suffix. */
    private int characterOverlapStart(String text, int start, int end) {
        int low = SourceText.nextOffset(text, start);
        int high = end - 1;
        int selected = end;

        while (low <= high) {
            int middle = low + (high - low) / 2;
            int candidate = safeOffset(text, middle);

            if (candidate < end && tokenCounter.count(text.substring(candidate, end)) <= overlapTokens) {
                selected = candidate;
                high = middle - 1;
            } else if (candidate == end) {
                high = middle - 1;
            } else {
                low = candidate + 1;
            }
        }

        return selected;
    }

    /* Moves an offset past a split surrogate pair or CRLF sequence while keeping all other source positions unchanged. */
    private static int safeOffset(String text, int offset) {
        if (offset > 0 && offset < text.length()
                && (Character.isLowSurrogate(text.charAt(offset)) && Character.isHighSurrogate(text.charAt(offset - 1))
                || text.charAt(offset) == '\n' && text.charAt(offset - 1) == '\r')) {
            return offset + 1;
        }

        return offset;
    }

    private boolean exceedsBudget(String text, int start, int end) {
        return tokenCounter.count(text.substring(start, end)) > contentBudget;
    }
}
