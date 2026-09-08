package dev.eyuppastirmaci.pecia.chunking;

import java.util.Arrays;
import java.util.stream.IntStream;

final class TextBoundaries {

    private final int[] wordEnds;
    private final int[] paragraphEnds;
    private final int[] sentenceEnds;
    private final int[] lineEnds;
    private final int[] lineStarts;

    TextBoundaries(String text) {
        IntStream.Builder words = IntStream.builder();
        IntStream.Builder paragraphs = IntStream.builder();
        IntStream.Builder sentences = IntStream.builder();
        IntStream.Builder lines = IntStream.builder();
        IntStream.Builder starts = IntStream.builder().add(0);
        int offset = 0;

        while (offset < text.length()) {
            int next = nextOffset(text, offset);

            if (isLineBreak(text.charAt(offset))) {
                starts.add(next);
            }

            offset = next;
        }

        offset = skipWhitespace(text, 0);

        while (offset < text.length()) {
            while (offset < text.length() && !isWhitespace(text.codePointAt(offset))) {
                offset = nextOffset(text, offset);
            }

            int wordEnd = offset;
            int lineBreaks = 0;

            while (offset < text.length() && isWhitespace(text.codePointAt(offset))) {
                if (isLineBreak(text.charAt(offset))) {
                    lineBreaks++;
                }

                offset = nextOffset(text, offset);
            }

            words.add(offset);

            if (lineBreaks >= 2) {
                paragraphs.add(offset);
            }

            if (endsSentence(text, wordEnd)) {
                sentences.add(offset);
            }

            if (lineBreaks > 0) {
                lines.add(offset);
            }
        }

        wordEnds = words.build().toArray();
        paragraphEnds = paragraphs.build().toArray();
        sentenceEnds = sentences.build().toArray();
        lineEnds = lines.build().toArray();
        lineStarts = starts.build().toArray();
    }

    int wordCount() {
        return wordEnds.length;
    }

    int wordEnd(int index) {
        return wordEnds[index];
    }

    int firstWordAfter(int offset) {
        int index = Arrays.binarySearch(wordEnds, offset);

        return index >= 0 ? index + 1 : -index - 1;
    }

    /* Prefers the latest complete paragraph, then sentence, then line without sacrificing new source coverage. */
    int preferredEnd(int minimumExclusive, int maximumInclusive) {
        for (int[] candidates : new int[][] {paragraphEnds, sentenceEnds, lineEnds}) {
            int candidate = floor(candidates, maximumInclusive);

            if (candidate > minimumExclusive) {
                return candidate;
            }
        }

        return maximumInclusive;
    }

    int lineAt(int offset) {
        int index = Arrays.binarySearch(lineStarts, offset);

        return index >= 0 ? index + 1 : -index - 1;
    }

    /* Advances by one Unicode code point or one complete CRLF sequence without altering the source. */
    static int nextOffset(String text, int offset) {
        if (text.charAt(offset) == '\r' && offset + 1 < text.length() && text.charAt(offset + 1) == '\n') {
            return offset + 2;
        }

        return offset + Character.charCount(text.codePointAt(offset));
    }

    static int skipWhitespace(String text, int offset) {
        while (offset < text.length() && isWhitespace(text.codePointAt(offset))) {
            offset = nextOffset(text, offset);
        }

        return offset;
    }

    private static boolean isWhitespace(int codePoint) {
        return Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint);
    }

    private static boolean isLineBreak(char character) {
        return character == '\r' || character == '\n';
    }

    /* Recognizes simple sentence-ending punctuation, allowing closing quotes and brackets after it. */
    private static boolean endsSentence(String text, int end) {
        while (end > 0) {
            int codePoint = text.codePointBefore(end);

            if ("\"')]}»”’".indexOf(codePoint) < 0) {
                return codePoint == '.' || codePoint == '!' || codePoint == '?'
                        || codePoint == '。' || codePoint == '！' || codePoint == '？';
            }

            end -= Character.charCount(codePoint);
        }

        return false;
    }

    private static int floor(int[] values, int maximum) {
        int index = Arrays.binarySearch(values, maximum);

        if (index < 0) {
            index = -index - 2;
        }

        return index >= 0 ? values[index] : -1;
    }
}
