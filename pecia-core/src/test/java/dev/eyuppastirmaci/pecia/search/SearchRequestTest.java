package dev.eyuppastirmaci.pecia.search;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SearchRequestTest {
    @Test
    void defaultsToTenHitsAndPreservesLiteralQueryText() {
        String query = " \u00a0İSTANBUL JWT_SECRET \"PaymentService\"* OR foo\n";
        SearchRequest request = new SearchRequest(query);

        assertEquals(query, request.query());
        assertEquals(10, request.limit());
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 10, 100})
    void acceptsSupportedLimits(int limit) {
        assertEquals(limit, new SearchRequest("payment", limit).limit());
    }

    @ParameterizedTest
    @ValueSource(ints = {Integer.MIN_VALUE, -1, 0, 101, Integer.MAX_VALUE})
    void rejectsUnsupportedLimits(int limit) {
        assertThrows(IllegalArgumentException.class, () -> new SearchRequest("payment", limit));
    }

    @Test
    void rejectsNullThroughBothConstructors() {
        assertThrows(NullPointerException.class, () -> new SearchRequest(null));
        assertThrows(NullPointerException.class, () -> new SearchRequest(null, 10));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " \t\r\n", "\u00a0", "\u2007", "\u202f", "\u0085", "\u2003\u2028\u2029\u3000"})
    void rejectsBlankIncludingNonBreakingAndUnicodeSpaces(String query) {
        assertThrows(IllegalArgumentException.class, () -> new SearchRequest(query));
    }

    @Test
    void rejectsEmbeddedNulInsteadOfTruncatingText() {
        assertThrows(IllegalArgumentException.class, () -> new SearchRequest("JWT\0_SECRET"));
        assertThrows(IllegalArgumentException.class, () -> new SearchRequest("\0"));
    }

    @Test
    void enforcesTheCodePointLimitWithoutTruncation() {
        String boundary = "a".repeat(4096);

        assertEquals(boundary, new SearchRequest(boundary).query());
        assertThrows(IllegalArgumentException.class, () -> new SearchRequest(boundary + "a"));
    }

    @Test
    void countsSupplementaryCharactersAsOneCodePoint() {
        String boundary = "\ud83d\ude80".repeat(4096);

        assertEquals(8192, boundary.length());
        assertEquals(boundary, new SearchRequest(boundary).query());
        assertThrows(IllegalArgumentException.class, () -> new SearchRequest(boundary + "\ud83d\ude80"));
    }

    @Test
    void leavesPunctuationInterpretationToTheCompiler() {
        assertEquals("!!! () \"\"", new SearchRequest("!!! () \"\"").query());
    }
}
