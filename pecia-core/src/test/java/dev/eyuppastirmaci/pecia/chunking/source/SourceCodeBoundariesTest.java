package dev.eyuppastirmaci.pecia.chunking.source;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SourceCodeBoundariesTest {

    @Test
    void prefersBlankRunsThenDedentsThenTheLatestLineEnd() {
        String text = "root\n\nblock\n    nested\nend\nlast";
        SourceCodeBoundaries boundaries = new SourceCodeBoundaries(text);
        int blank = text.indexOf("block");
        int dedent = text.indexOf("end");
        int line = text.indexOf("last");

        assertEquals(OptionalInt.of(blank), boundaries.preferredEnd(0, line));
        assertEquals(OptionalInt.of(dedent), boundaries.preferredEnd(blank, line));
        assertEquals(OptionalInt.of(line), boundaries.preferredEnd(dedent, line));
    }

    @Test
    void choosesTheLatestBoundaryWithinEachPriority() {
        String text = "first\n\nsecond\n\nthird\n    deep\nend\nlast";
        SourceCodeBoundaries boundaries = new SourceCodeBoundaries(text);

        assertEquals(OptionalInt.of(text.indexOf("third")), boundaries.preferredEnd(0, text.indexOf("last")));
    }

    @Test
    void prefersEndOfInputWhenTheWholeRemainingSliceFits() {
        String text = "first\n\nsecond\n    deep\nlast";
        SourceCodeBoundaries boundaries = new SourceCodeBoundaries(text);

        assertEquals(OptionalInt.of(text.length()), boundaries.preferredEnd(0, text.length()));
        assertEquals(OptionalInt.of(text.length()), boundaries.preferredEnd(text.indexOf("second"), text.length()));
    }

    @Test
    void keepsBlankRunsWithThePrecedingTextAndIndentationWithTheFollowingLine() {
        String text = "root\n \t\n\n    next\n    tail";
        SourceCodeBoundaries boundaries = new SourceCodeBoundaries(text);
        int next = text.indexOf("    next");
        int end = boundaries.preferredEnd(0, text.indexOf("    tail")).orElseThrow();

        assertEquals(next, end);
        assertEquals("root\n \t\n\n", text.substring(0, end));
        assertTrue(text.substring(end).startsWith("    next"));
    }

    @Test
    void ignoresLeadingBlankLinesAsPreferredParagraphBoundaries() {
        String text = "\n \t\nfirst\nsecond\nlast";
        SourceCodeBoundaries boundaries = new SourceCodeBoundaries(text);

        assertEquals(OptionalInt.of(text.indexOf("last")), boundaries.preferredEnd(0, text.indexOf("last")));
    }

    @Test
    void measuresTabsUsingFourColumnStopsWithoutRewritingIndentation() {
        String text = "    first\n\tsecond\n  third\n  fourth\nlast";
        SourceCodeBoundaries boundaries = new SourceCodeBoundaries(text);

        assertEquals(OptionalInt.of(text.indexOf("  third")), boundaries.preferredEnd(0, text.indexOf("last") - 1));
        assertEquals("\tsecond\n", text.substring(boundaries.lineStart(1), boundaries.lineEnd(1)));

        String mixed = " \tfirst\n    second\n    third\nlast";
        SourceCodeBoundaries mixedBoundaries = new SourceCodeBoundaries(mixed);
        assertEquals(
                OptionalInt.of(mixed.indexOf("    third")), mixedBoundaries.preferredEnd(0, mixed.indexOf("last") - 1));
    }

    @Test
    void comparesIndentationWithThePreviousNonBlankLine() {
        String text = "    first\n \t\n  second\n  third\nlast";
        SourceCodeBoundaries boundaries = new SourceCodeBoundaries(text);
        int second = text.indexOf("  second");

        assertEquals(OptionalInt.of(second), boundaries.preferredEnd(0, text.indexOf("last") - 1));
        assertEquals(
                OptionalInt.of(text.indexOf("  third")), boundaries.preferredEnd(second, text.indexOf("last") - 1));
    }

    @Test
    void doesNotTreatSentencesBracesOrMarkdownMarkersAsSpecialBoundaries() {
        String text = "const x = 'Hello! World?'; # heading ``` { } <Component />";
        SourceCodeBoundaries boundaries = new SourceCodeBoundaries(text);

        assertEquals(OptionalInt.empty(), boundaries.preferredEnd(0, text.length() - 1));
        assertEquals(OptionalInt.of(text.length()), boundaries.preferredEnd(0, text.length()));
    }

    @Test
    void reportsNoLineBoundaryWhenALineNeedsTokenFallback() {
        String text = "a".repeat(1000) + "\nnext";
        SourceCodeBoundaries boundaries = new SourceCodeBoundaries(text);

        assertEquals(OptionalInt.empty(), boundaries.preferredEnd(0, 500));
        assertEquals(OptionalInt.of(1001), boundaries.preferredEnd(0, 1001));
        assertEquals(OptionalInt.empty(), boundaries.preferredEnd(1001, 1002));
    }

    @ParameterizedTest
    @ValueSource(strings = {"\n", "\r\n", "\r"})
    void preservesOriginalPhysicalLinesAndUtf16Offsets(String newline) {
        List<String> lines = List.of("😀 root" + newline, " \t" + newline, "\tİçerik" + newline, "last");
        String text = String.join("", lines);
        SourceCodeBoundaries boundaries = new SourceCodeBoundaries(text);
        int offset = 0;

        assertEquals(lines.size(), boundaries.lineCount());

        for (int index = 0; index < lines.size(); index++) {
            assertEquals(offset, boundaries.lineStart(index));
            offset += lines.get(index).length();
            assertEquals(offset, boundaries.lineEnd(index));
            assertEquals(lines.get(index), text.substring(boundaries.lineStart(index), boundaries.lineEnd(index)));
        }

        assertEquals(text.length(), offset);
    }

    @Test
    void neverChoosesAnOffsetInsideCrLfOrASurrogatePair() {
        String text = "😀\r\nnext";
        SourceCodeBoundaries boundaries = new SourceCodeBoundaries(text);

        assertEquals(OptionalInt.empty(), boundaries.preferredEnd(0, 1));
        assertEquals(OptionalInt.empty(), boundaries.preferredEnd(0, 3));
        assertEquals(OptionalInt.of(4), boundaries.preferredEnd(0, 4));
    }

    @Test
    void recognizesUnicodeWhitespaceOnlyLinesWithoutTreatingUnicodeSeparatorsAsNewlines() {
        String text = "first\n\u00a0\u2003\f\nnext\nlast";
        SourceCodeBoundaries boundaries = new SourceCodeBoundaries(text);

        assertEquals(OptionalInt.of(text.indexOf("next")), boundaries.preferredEnd(0, text.indexOf("last")));
        assertEquals(1, new SourceCodeBoundaries("first\u2028last").lineCount());
    }

    @Test
    void indexesLineEndsWithoutCreatingAnExtraEmptyLineAtEof() {
        SourceCodeBoundaries empty = new SourceCodeBoundaries("");
        SourceCodeBoundaries terminated = new SourceCodeBoundaries("a\n\n");

        assertEquals(0, empty.lineCount());
        assertEquals(OptionalInt.empty(), empty.preferredEnd(0, 0));
        assertEquals(0, empty.firstLineAfter(0));
        assertEquals(2, terminated.lineCount());
        assertEquals(0, terminated.firstLineAfter(0));
        assertEquals(0, terminated.firstLineAfter(1));
        assertEquals(1, terminated.firstLineAfter(2));
        assertEquals(2, terminated.firstLineAfter(3));
        assertEquals(1, new SourceCodeBoundaries(" \t").lineCount());
    }

    @Test
    void rejectsInvalidWindowsOffsetsAndLineIndices() {
        SourceCodeBoundaries boundaries = new SourceCodeBoundaries("abc\ndef");

        assertThrows(NullPointerException.class, () -> new SourceCodeBoundaries(null));
        assertThrows(IllegalArgumentException.class, () -> boundaries.preferredEnd(-1, 3));
        assertThrows(IllegalArgumentException.class, () -> boundaries.preferredEnd(4, 3));
        assertThrows(IllegalArgumentException.class, () -> boundaries.preferredEnd(0, 8));
        assertThrows(IllegalArgumentException.class, () -> boundaries.firstLineAfter(-1));
        assertThrows(IllegalArgumentException.class, () -> boundaries.firstLineAfter(8));
        assertThrows(IndexOutOfBoundsException.class, () -> boundaries.lineStart(-1));
        assertThrows(IndexOutOfBoundsException.class, () -> boundaries.lineStart(2));
        assertThrows(IndexOutOfBoundsException.class, () -> boundaries.lineEnd(2));
        assertEquals(OptionalInt.empty(), boundaries.preferredEnd(4, 4));
    }

    @Test
    void remainsDeterministicAndReturnsOnlySafeBoundariesWithinTheRequestedWindow() {
        String text = "\nroot\r\n \t\r\n    😀 deep\n\tpeer\r  end\nlast";
        SourceCodeBoundaries first = new SourceCodeBoundaries(text);
        SourceCodeBoundaries second = new SourceCodeBoundaries(text);

        for (int minimum = 0; minimum <= text.length(); minimum++) {
            for (int maximum = minimum; maximum <= text.length(); maximum++) {
                OptionalInt result = first.preferredEnd(minimum, maximum);
                assertEquals(result, second.preferredEnd(minimum, maximum));

                if (result.isPresent()) {
                    int end = result.getAsInt();
                    assertTrue(end > minimum && end <= maximum);

                    if (end < text.length()) {
                        assertFalse(Character.isHighSurrogate(text.charAt(end - 1))
                                && Character.isLowSurrogate(text.charAt(end)));
                        assertFalse(text.charAt(end - 1) == '\r' && text.charAt(end) == '\n');
                    }
                }
            }
        }
    }
}
