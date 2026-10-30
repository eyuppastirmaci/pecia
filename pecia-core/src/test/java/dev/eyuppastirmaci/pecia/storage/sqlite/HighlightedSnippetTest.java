package dev.eyuppastirmaci.pecia.storage.sqlite;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import dev.eyuppastirmaci.pecia.search.MatchRange;
import dev.eyuppastirmaci.pecia.search.SearchHit;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Match ranges come from the markers FTS5 puts in a snippet, and are never wrong when the text holds markers. */
class HighlightedSnippetTest {

    private static final String OPEN = "\uFDD0";
    private static final String CLOSE = "\uFDD1";

    @Test
    void markersBecomeRangesInCodePointsAndLeaveThePlainText() {
        HighlightedSnippet snippet =
                HighlightedSnippet.read("😀 pay 😀😀 pay later", "😀 " + mark("pay") + " 😀😀 " + mark("pay later"));

        assertEquals("😀 pay 😀😀 pay later", snippet.text());
        assertEquals(List.of(new MatchRange(2, 5), new MatchRange(9, 18)), snippet.matches());
    }

    @Test
    void emptyAndTouchingMarksAreReadAsTheyAre() {
        HighlightedSnippet snippet = HighlightedSnippet.read("ab", OPEN + CLOSE + mark("a") + mark("b"));

        assertEquals(List.of(new MatchRange(0, 1), new MatchRange(1, 2)), snippet.matches());
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(
            strings = {
                "\uFDD0a\uFDD0 b\uFDD1",
                "a \uFDD1b",
                "\uFDD0a\uFDD1 \uFDD0b",
            })
    void unbalancedMarkersGiveNoRanges(String marked) {
        HighlightedSnippet snippet = HighlightedSnippet.read("a b", marked);

        assertEquals("a b", snippet.text());
        assertEquals(List.of(), snippet.matches());
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"a \uFDD0 needle", "\uFDD0a\uFDD1 needle"})
    void textThatHoldsMarkerCharactersGivesNoRanges(String plain) {
        // FTS5 marks "needle", but the text's own markers make the marks ambiguous, even when they pair up.
        String marked = plain.replace("needle", mark("needle"));

        HighlightedSnippet snippet = HighlightedSnippet.read(plain, marked);

        assertEquals(plain, snippet.text());
        assertEquals(List.of(), snippet.matches());
    }

    @Test
    void aShortSnippetIsKeptWhole() {
        HighlightedSnippet snippet = new HighlightedSnippet("x".repeat(SearchHit.MAX_SNIPPET_CODE_POINTS), List.of());

        assertSame(snippet, snippet.bounded());
    }

    @Test
    void cuttingDropsRangesPastTheCutAndEndsTheOneAcrossIt() {
        String text = "😀".repeat(450);
        HighlightedSnippet snippet = new HighlightedSnippet(
                text, List.of(new MatchRange(10, 20), new MatchRange(395, 405), new MatchRange(399, 401)));

        HighlightedSnippet bounded = snippet.bounded();

        assertEquals("😀".repeat(399) + "…", bounded.text());
        assertEquals(List.of(new MatchRange(10, 20), new MatchRange(395, 399)), bounded.matches());
    }

    private static String mark(String text) {
        return OPEN + text + CLOSE;
    }
}
