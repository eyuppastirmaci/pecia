package dev.eyuppastirmaci.pecia.chunking.source;

import dev.eyuppastirmaci.pecia.chunking.internal.SourceText;
import java.util.Arrays;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.stream.IntStream;

final class SourceCodeBoundaries {

    private static final int TAB_WIDTH = 4;

    private final int textLength;
    private final int[] lineEnds;
    private final int[] blankLineEnds;
    private final int[] dedentStarts;

    SourceCodeBoundaries(String text) {
        textLength = text.length();
        IntStream.Builder lines = IntStream.builder();
        IntStream.Builder blanks = IntStream.builder();
        IntStream.Builder dedents = IntStream.builder();
        int offset = 0;
        long previousIndent = -1;
        boolean pendingBlank = false;

        while (offset < textLength) {
            int start = offset;
            long indent = 0;

            // Tab stops are only a comparison heuristic; indentation characters remain untouched in the
            // source.
            while (offset < textLength && (text.charAt(offset) == ' ' || text.charAt(offset) == '\t')) {
                indent += text.charAt(offset) == '\t' ? TAB_WIDTH - indent % TAB_WIDTH : 1;
                offset++;
            }

            boolean blank = true;

            while (offset < textLength && text.charAt(offset) != '\r' && text.charAt(offset) != '\n') {
                int codePoint = text.codePointAt(offset);

                if (!Character.isWhitespace(codePoint) && !Character.isSpaceChar(codePoint)) {
                    blank = false;
                }

                offset += Character.charCount(codePoint);
            }

            if (offset < textLength) {
                offset = SourceText.nextOffset(text, offset);
            }

            lines.add(offset);

            if (blank) {
                pendingBlank = previousIndent >= 0;
            } else {
                if (pendingBlank) {
                    blanks.add(start);
                }

                if (previousIndent >= 0 && indent < previousIndent) {
                    dedents.add(start);
                }

                previousIndent = indent;
                pendingBlank = false;
            }
        }

        if (pendingBlank) {
            blanks.add(textLength);
        }

        lineEnds = lines.build().toArray();
        blankLineEnds = blanks.build().toArray();
        dedentStarts = dedents.build().toArray();
    }

    int lineCount() {
        return lineEnds.length;
    }

    int lineStart(int index) {
        Objects.checkIndex(index, lineEnds.length);

        return index == 0 ? 0 : lineEnds[index - 1];
    }

    int lineEnd(int index) {
        return lineEnds[index];
    }

    /**
     * Finds the first physical line ending after a UTF-16 offset, returning the line count at end of
     * input.
     */
    int firstLineAfter(int offset) {
        if (offset < 0 || offset > textLength) {
            throw new IllegalArgumentException("Offset must be within the source text");
        }

        int index = Arrays.binarySearch(lineEnds, offset);

        return index >= 0 ? index + 1 : -index - 1;
    }

    /**
     * Prefers EOF when it fits, otherwise the latest blank-run end, dedent start, or line end within
     * an exclusive/inclusive window.
     */
    OptionalInt preferredEnd(int minimumExclusive, int maximumInclusive) {
        if (minimumExclusive < 0 || maximumInclusive < minimumExclusive || maximumInclusive > textLength) {
            throw new IllegalArgumentException("Boundary window must be ordered and within the source text");
        }

        if (minimumExclusive == maximumInclusive) {
            return OptionalInt.empty();
        }

        if (maximumInclusive == textLength) {
            return OptionalInt.of(textLength);
        }

        for (int[] candidates : new int[][] {blankLineEnds, dedentStarts, lineEnds}) {
            int index = Arrays.binarySearch(candidates, maximumInclusive);

            if (index < 0) {
                index = -index - 2;
            }

            if (index >= 0 && candidates[index] > minimumExclusive) {
                return OptionalInt.of(candidates[index]);
            }
        }

        return OptionalInt.empty();
    }
}
