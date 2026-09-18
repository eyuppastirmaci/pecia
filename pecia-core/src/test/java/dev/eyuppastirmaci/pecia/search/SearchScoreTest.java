package dev.eyuppastirmaci.pecia.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SearchScoreTest {
    @Test
    void preservesRawSqliteBm25ScoreAndItsOrderingDirection() {
        SearchScore score = new SearchScore(-0.00000125, SearchScore.Kind.SQLITE_BM25);

        assertEquals(-0.00000125, score.value());
        assertEquals(SearchScore.Kind.SQLITE_BM25, score.kind());
        assertTrue(score.kind().lowerIsBetter());
    }

    @ParameterizedTest
    @ValueSource(doubles = {-Double.MAX_VALUE, -12.5, -0.0, 0.0, 12.5, Double.MAX_VALUE})
    void acceptsFiniteValuesWithoutProbabilityBounds(double value) {
        assertEquals(value, new SearchScore(value, SearchScore.Kind.SQLITE_BM25).value());
    }

    @ParameterizedTest
    @ValueSource(doubles = {Double.NaN, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY})
    void rejectsNonFiniteValues(double value) {
        assertThrows(IllegalArgumentException.class, () -> new SearchScore(value, SearchScore.Kind.SQLITE_BM25));
    }

    @Test
    void requiresAnExplicitScoreKind() {
        assertThrows(NullPointerException.class, () -> new SearchScore(-1.0, null));
    }
}
