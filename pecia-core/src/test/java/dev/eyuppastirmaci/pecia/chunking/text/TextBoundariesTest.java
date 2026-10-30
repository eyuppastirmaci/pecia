package dev.eyuppastirmaci.pecia.chunking.text;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** Plain-text slices prefer paragraph ends, then sentence ends, then line ends. */
class TextBoundariesTest {

    @Test
    void twoLineBreaksEndAParagraphWhichWinsOverALaterSentence() {
        String text = "first part\n\nsecond. more\nlast";

        assertEquals(text.indexOf("second"), boundaries(text).preferredEnd(0, text.indexOf("last")));
    }

    @Test
    void aWordOfOnlyClosingQuotesAndBracketsEndsNoSentence() {
        String text = "\") word\nlast";

        // Nothing ends a sentence, so the line end is used.
        assertEquals(text.indexOf("last"), boundaries(text).preferredEnd(0, text.indexOf("last")));
    }

    @Test
    void aBoundaryExactlyAtTheEndOfTheWindowIsUsed() {
        String text = "a b.\nc\n\nd";

        // The only paragraph end is the window's end itself; a sentence end lies earlier.
        assertEquals(text.indexOf("d"), boundaries(text).preferredEnd(0, text.indexOf("d")));
    }

    private static TextBoundaries boundaries(String text) {
        return new TextBoundaries(text);
    }
}
