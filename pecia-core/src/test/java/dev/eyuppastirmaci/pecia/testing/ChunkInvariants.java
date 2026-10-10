package dev.eyuppastirmaci.pecia.testing;

import dev.eyuppastirmaci.pecia.content.Chunk;
import dev.eyuppastirmaci.pecia.content.Document;
import dev.eyuppastirmaci.pecia.content.DocumentType;
import dev.eyuppastirmaci.pecia.content.LineRange;
import dev.eyuppastirmaci.pecia.tokenization.TokenCounter;
import java.util.List;

/**
 * The coverage invariant shared by every chunking strategy, as stated by the strategies' example tests. Chunks cover
 * the source from start to end in order; removing each chunk's overlap with its predecessor reconstructs the source
 * exactly; every chunk respects the token limit and overlap budget; and boundaries never split a surrogate pair or a
 * CRLF. A source made only of whitespace produces no chunks.
 *
 * <p>Whitespace follows the chunkers' definition: {@link Character#isWhitespace} or {@link Character#isSpaceChar}, so
 * no-break and ideographic spaces count as whitespace.
 */
public final class ChunkInvariants {

    private ChunkInvariants() {}

    /**
     * Checks the invariant and returns a description of the first violation, or null when the chunks satisfy it.
     * Returning a message rather than throwing lets properties report the chunk index and offsets.
     */
    public static String violation(
            Document document, List<Chunk> chunks, TokenCounter counter, int maxTokens, int overlapTokens) {
        String text = document.content();

        if (onlyWhitespace(text)) {
            return chunks.isEmpty() ? null : "A whitespace-only source produced " + chunks.size() + " chunks";
        }

        int coveredEnd = 0;
        int previousStart = -1;
        StringBuilder reconstructed = new StringBuilder();

        for (int index = 0; index < chunks.size(); index++) {
            Chunk chunk = chunks.get(index);
            int start = Integer.parseInt(chunk.metadata().attributes().get("startOffset"));
            int end = Integer.parseInt(chunk.metadata().attributes().get("endOffset"));
            String where = "chunk " + index + " [" + start + ", " + end + ")";

            if (chunk.index() != index) {
                return where + " has index " + chunk.index();
            }
            if (!chunk.sourcePath().equals(document.sourcePath()) || chunk.documentType() != document.type()) {
                return where + " does not keep the document's path and type";
            }
            if (start <= previousStart || start > coveredEnd || end <= coveredEnd || end > text.length()) {
                return where + " breaks ordered coverage after offset " + coveredEnd;
            }
            if (overlapTokens == 0 && start != coveredEnd) {
                return where + " overlaps although the overlap budget is zero";
            }
            if (!text.substring(start, end).equals(chunk.content())) {
                return where + " differs from its source slice";
            }
            if (onlyWhitespace(chunk.content())) {
                return where + " contains only whitespace";
            }
            if (counter.countModelInput(chunk.content()) > maxTokens) {
                return where + " exceeds " + maxTokens + " model input tokens";
            }
            if (counter.count(text.substring(start, coveredEnd)) > overlapTokens) {
                return where + " overlaps by more than " + overlapTokens + " tokens";
            }
            if (!new LineRange(lineAt(text, start), lineAt(text, end - 1)).equals(chunk.sourceLocation())) {
                return where + " has line range " + chunk.sourceLocation();
            }
            if (splitsPair(text, start) || splitsPair(text, end)) {
                return where + " splits a surrogate pair or a CRLF";
            }
            if (document.type() != DocumentType.MARKDOWN
                    && !chunk.metadata().headingPath().isEmpty()) {
                return where + " has a heading path outside Markdown";
            }
            if (chunk.metadata().headingPath().stream().anyMatch(String::isBlank)) {
                return where + " has a blank heading";
            }

            reconstructed.append(text, coveredEnd, end);
            coveredEnd = end;
            previousStart = start;
        }

        if (coveredEnd != text.length()) {
            return "Chunks end at " + coveredEnd + " of " + text.length();
        }

        return reconstructed.toString().equals(text) ? null : "Removing overlap does not reconstruct the source";
    }

    static boolean onlyWhitespace(String text) {
        return text.codePoints().allMatch(point -> Character.isWhitespace(point) || Character.isSpaceChar(point));
    }

    /* Counts LF, CRLF, and lone CR as line breaks; the line of an offset is one plus the breaks before it. */
    private static int lineAt(String text, int offset) {
        int line = 1;

        for (int index = 0; index < offset; index++) {
            char current = text.charAt(index);

            if (current == '\n' || current == '\r' && (index + 1 == text.length() || text.charAt(index + 1) != '\n')) {
                line++;
            }
        }

        return line;
    }

    private static boolean splitsPair(String text, int offset) {
        if (offset <= 0 || offset >= text.length()) {
            return false;
        }

        char before = text.charAt(offset - 1);
        char after = text.charAt(offset);
        return Character.isHighSurrogate(before) && Character.isLowSurrogate(after) || before == '\r' && after == '\n';
    }
}
