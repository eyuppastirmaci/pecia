package dev.eyuppastirmaci.pecia.chunking.internal;

import java.util.Arrays;
import java.util.stream.IntStream;

/** Indexes whitespace-delimited words and physical lines in unchanged source text. */
public final class SourceText {

    private final int[] wordEnds;
    private final int[] lineStarts;

    /** Builds source boundaries while treating CRLF pairs as single line terminators. */
    public SourceText(String text) {
        IntStream.Builder starts = IntStream.builder().add(0);
        int offset = 0;

        while (offset < text.length()) {
            int next = nextOffset(text, offset);

            if (isLineBreak(text.charAt(offset))) {
                starts.add(next);
            }

            offset = next;
        }

        IntStream.Builder words = IntStream.builder();
        offset = skipWhitespace(text, 0);

        while (offset < text.length()) {
            while (offset < text.length() && !isWhitespace(text.codePointAt(offset))) {
                offset = nextOffset(text, offset);
            }

            offset = skipWhitespace(text, offset);
            words.add(offset);
        }

        wordEnds = words.build().toArray();
        lineStarts = starts.build().toArray();
    }

    /**
     * Returns the number of whitespace-delimited source words.
     *
     * @return the word count, or zero for empty or whitespace-only text
     */
    public int wordCount() {
        return wordEnds.length;
    }

    /**
     * Returns a word's exclusive source end including its following whitespace.
     *
     * @param index zero-based word index
     * @return the zero-based UTF-16 end offset
     * @throws IndexOutOfBoundsException if index does not identify a word
     */
    public int wordEnd(int index) {
        return wordEnds[index];
    }

    /**
     * Finds the first indexed word ending strictly after a source offset.
     *
     * @param offset zero-based UTF-16 source offset
     * @return the zero-based word index, or the word count when no later end exists
     */
    public int firstWordAfter(int offset) {
        int index = Arrays.binarySearch(wordEnds, offset);

        return index >= 0 ? index + 1 : -index - 1;
    }

    /**
     * Resolves a source position to its physical line with terminators belonging to the line they
     * terminate.
     *
     * @param offset zero-based UTF-16 source offset, including EOF when an insertion position is
     *     needed
     * @return the one-based line number, including the empty line after a final terminator at EOF
     */
    public int lineAt(int offset) {
        int index = Arrays.binarySearch(lineStarts, offset);

        return index >= 0 ? index + 1 : -index - 1;
    }

    /**
     * Advances past one Unicode code point or one complete CRLF sequence without altering the source.
     *
     * @param text original source text
     * @param offset zero-based UTF-16 offset at the start of a source unit
     * @return the exclusive offset after that unit
     * @throws NullPointerException if text is null
     * @throws IndexOutOfBoundsException if offset is outside the text
     */
    public static int nextOffset(String text, int offset) {
        if (text.charAt(offset) == '\r' && offset + 1 < text.length() && text.charAt(offset + 1) == '\n') {
            return offset + 2;
        }

        return offset + Character.charCount(text.codePointAt(offset));
    }

    /**
     * Advances past Unicode whitespace while preserving complete CRLF sequences.
     *
     * @param text original source text
     * @param offset zero-based UTF-16 source offset at which scanning begins
     * @return the next non-whitespace offset, or the end of the text
     * @throws NullPointerException if text is null
     * @throws IndexOutOfBoundsException if offset is negative
     */
    public static int skipWhitespace(String text, int offset) {
        while (offset < text.length() && isWhitespace(text.codePointAt(offset))) {
            offset = nextOffset(text, offset);
        }

        return offset;
    }

    /**
     * Recognizes Unicode whitespace and space characters used by source slicing.
     *
     * @param codePoint Unicode code point to classify
     * @return true when the code point is whitespace or a space character
     */
    public static boolean isWhitespace(int codePoint) {
        return Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint);
    }

    /**
     * Recognizes the CR and LF characters used as physical line terminators.
     *
     * @param character source character to classify
     * @return true for CR or LF
     */
    public static boolean isLineBreak(char character) {
        return character == '\r' || character == '\n';
    }
}
