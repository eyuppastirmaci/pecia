package dev.eyuppastirmaci.pecia.chunking.text;

import dev.eyuppastirmaci.pecia.chunking.internal.SourceText;

import java.util.Arrays;
import java.util.stream.IntStream;

import static dev.eyuppastirmaci.pecia.chunking.internal.SourceText.isLineBreak;
import static dev.eyuppastirmaci.pecia.chunking.internal.SourceText.isWhitespace;
import static dev.eyuppastirmaci.pecia.chunking.internal.SourceText.nextOffset;

final class TextBoundaries {

    private final SourceText source;
    private final int[] paragraphEnds;
    private final int[] sentenceEnds;
    private final int[] lineEnds;

    TextBoundaries(String text) {
        source = new SourceText(text);
        IntStream.Builder paragraphs = IntStream.builder();
        IntStream.Builder sentences = IntStream.builder();
        IntStream.Builder lines = IntStream.builder();

        for (int index = 0; index < source.wordCount(); index++) {
            int end = source.wordEnd(index);
            int wordEnd = end;

            // Separate the word from its preserved whitespace suffix before applying prose-only boundary preferences.
            while (wordEnd > 0 && isWhitespace(text.codePointBefore(wordEnd))) {
                wordEnd -= Character.charCount(text.codePointBefore(wordEnd));
            }

            int offset = wordEnd;
            int lineBreaks = 0;

            while (offset < end) {
                if (isLineBreak(text.charAt(offset))) {
                    lineBreaks++;
                }

                offset = nextOffset(text, offset);
            }

            if (lineBreaks >= 2) {
                paragraphs.add(end);
            }

            if (endsSentence(text, wordEnd)) {
                sentences.add(end);
            }

            if (lineBreaks > 0) {
                lines.add(end);
            }
        }

        paragraphEnds = paragraphs.build().toArray();
        sentenceEnds = sentences.build().toArray();
        lineEnds = lines.build().toArray();
    }

    int wordCount() {
        return source.wordCount();
    }

    int wordEnd(int index) {
        return source.wordEnd(index);
    }

    int firstWordAfter(int offset) {
        return source.firstWordAfter(offset);
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
        return source.lineAt(offset);
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
