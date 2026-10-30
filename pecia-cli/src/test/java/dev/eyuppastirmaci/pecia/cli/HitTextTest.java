package dev.eyuppastirmaci.pecia.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.eyuppastirmaci.pecia.search.MatchRange;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/** Scores read with three significant digits, and matches are painted where the snippet says they are. */
class HitTextTest {

    private static final String ON = "\u001b[1m\u001b[31m";
    private static final String OFF = "\u001b[0m";
    private static final Terminal COLOR = new Terminal(true, true, 80);

    @ParameterizedTest(name = "{0} reads as {1}")
    @CsvSource({
        "-0.8594, -0.859",
        "0.03278688524590164, 0.0328",
        "0.3341, 0.334",
        "-2.0E-6, -0.000002",
        "-1.5714285714285712E-6, -0.00000157",
        "0.5, 0.5",
        "1.0, 1",
        "0.99951, 1",
        "12345.678, 12300",
        "0.0, 0",
        "-0.0, 0"
    })
    void scoresKeepThreeSignificantDigits(double value, String text) {
        assertEquals(text, HitText.score(value));
    }

    @Test
    void withoutColourTheSnippetSplitsAsLinesDo() {
        String snippet = "first line\r\nsecond\rthird\n\nfifth\n";

        assertEquals(
                snippet.lines().toList(), HitText.snippetLines(snippet, List.of(new MatchRange(0, 5)), Terminal.PLAIN));
        assertEquals(List.of("a", "b"), HitText.snippetLines("a\nb", List.of(), COLOR));
    }

    @ParameterizedTest(name = "[{index}]")
    @ValueSource(strings = {"one", "a\nb\n", "a\r\nb", "a\rb\r\n\nc", "\n\nmiddle\n\n", "trailing\r"})
    void colouredLinesAreTheSameLinesOnceTheColourIsRemoved(String snippet) {
        List<MatchRange> everything = List.of(new MatchRange(0, snippet.codePointCount(0, snippet.length())));

        assertEquals(
                snippet.lines().toList(),
                HitText.snippetLines(snippet, everything, COLOR).stream()
                        .map(line -> line.replace(ON, "").replace(OFF, ""))
                        .toList());
    }

    @Test
    void matchesArePaintedOnTheirLinesCountingCodePoints() {
        String snippet = "😀 needle here\r\n\nlast needle";

        assertEquals(
                List.of("😀 " + ON + "needle" + OFF + " here", "", "last " + ON + "needle" + OFF),
                HitText.snippetLines(snippet, List.of(new MatchRange(2, 8), new MatchRange(21, 27)), COLOR));
    }

    @Test
    void aMatchAcrossALineBreakIsPaintedOnBothLines() {
        assertEquals(
                List.of("ab" + ON + "cd" + OFF, ON + "ef" + OFF + "gh"),
                HitText.snippetLines("abcd\nefgh", List.of(new MatchRange(2, 7)), COLOR));
    }

    @Test
    void touchingMatchesAndMatchesAtTheEndsArePainted() {
        assertEquals(
                List.of(ON + "ab" + OFF + "-" + ON + "cdef" + OFF),
                HitText.snippetLines(
                        "ab-cdef", List.of(new MatchRange(0, 2), new MatchRange(3, 4), new MatchRange(4, 7)), COLOR));
    }
}
