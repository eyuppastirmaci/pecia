package dev.eyuppastirmaci.pecia.chunking.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SourceTextTest {

    @Test
    void indexesWordEndsWithOriginalWhitespaceAndUtf16Offsets() {
        SourceText source = new SourceText(" \t😀\r\n  one\u00a0two\rthree\n");

        assertEquals(4, source.wordCount());
        assertEquals(8, source.wordEnd(0));
        assertEquals(12, source.wordEnd(1));
        assertEquals(16, source.wordEnd(2));
        assertEquals(22, source.wordEnd(3));
        assertEquals(0, source.firstWordAfter(0));
        assertEquals(0, source.firstWordAfter(7));
        assertEquals(1, source.firstWordAfter(8));
        assertEquals(4, source.firstWordAfter(22));
    }

    @ParameterizedTest
    @ValueSource(strings = {"\n", "\r\n", "\r"})
    void mapsPhysicalCharactersAndEofInsertionPositionsToLines(String newline) {
        SourceText source = new SourceText("😀" + newline + "x" + newline);
        int secondStart = 2 + newline.length();

        assertEquals(1, source.lineAt(0));
        assertEquals(1, source.lineAt(secondStart - 1));
        assertEquals(2, source.lineAt(secondStart));
        assertEquals(2, source.lineAt(secondStart + newline.length()));
        assertEquals(3, source.lineAt(secondStart + 1 + newline.length()));
    }

    @Test
    void advancesPastWholeUnicodeAndCrLfUnits() {
        String text = "😀\r\nx";

        assertEquals(2, SourceText.nextOffset(text, 0));
        assertEquals(4, SourceText.nextOffset(text, 2));
        assertEquals(5, SourceText.nextOffset(text, 4));
        assertEquals(5, SourceText.skipWhitespace("\u00a0\u2003\r\n\tx", 0));
        assertEquals(0, SourceText.skipWhitespace("", 0));
    }

    @Test
    void keepsWhitespaceClassificationSeparateFromPhysicalLineBreaks() {
        assertTrue(SourceText.isWhitespace('\u00a0'));
        assertTrue(SourceText.isWhitespace('\u2003'));
        assertTrue(SourceText.isWhitespace('\u2028'));
        assertFalse(SourceText.isWhitespace('\u200b'));
        assertFalse(SourceText.isWhitespace('x'));
        assertTrue(SourceText.isLineBreak('\r'));
        assertTrue(SourceText.isLineBreak('\n'));
        assertFalse(SourceText.isLineBreak('\u2028'));
        assertEquals(1, new SourceText("a\u2028b").lineAt(2));
    }

    @Test
    void hasNoWordEntriesForBlankTextButRetainsItsLinePositions() {
        SourceText source = new SourceText(" \t\r\n\n");

        assertEquals(0, source.wordCount());
        assertEquals(0, source.firstWordAfter(0));
        assertEquals(3, source.lineAt(5));
        assertEquals(1, new SourceText("").lineAt(0));
    }

    @Test
    void retainsNullAndInvalidIndexFailures() {
        assertThrows(NullPointerException.class, () -> new SourceText(null));
        assertThrows(NullPointerException.class, () -> SourceText.nextOffset(null, 0));
        assertThrows(NullPointerException.class, () -> SourceText.skipWhitespace(null, 0));
        assertThrows(IndexOutOfBoundsException.class, () -> SourceText.nextOffset("x", 1));
        assertThrows(IndexOutOfBoundsException.class, () -> SourceText.skipWhitespace("x", -1));
        assertThrows(IndexOutOfBoundsException.class, () -> new SourceText("x").wordEnd(1));
    }
}
