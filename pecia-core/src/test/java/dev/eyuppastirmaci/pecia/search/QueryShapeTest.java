package dev.eyuppastirmaci.pecia.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Identifier-like text, which hybrid search ranks lexically first, and everything else. */
class QueryShapeTest {

    @ParameterizedTest
    @ValueSource(
            strings = {
                "maxAttempts",
                "getBulletPosition",
                "ThreadSafeLazyLoadedIvoryTower",
                "RetryExponentialBackoff",
                "CallsCount",
                "HeroStat",
                "BUFFER_THRESHOLD",
                "DAYS_UNTIL_EXPIRATION_WHEN_DISCOUNT_ACTIVE",
                "snake_case_name",
                "com.iluwatar.retry",
                "src/main/App.java",
                "std::vector",
                "String#format",
                "node->next",
                "HTTP2",
                "v1beta",
                // Uppercase letters outside ASCII still mark a camelCase word.
                "çalışanŞube",
                "ÇalışanŞube",
                "retry maxAttempts",
                "where is getActiveCalls"
            })
    void textWithCodeLikeWordsInAFewPartsIsAnIdentifier(String query) {
        assertEquals(QueryShape.IDENTIFIER, QueryShape.of(query));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "retry",
                "Retry",
                "RETRY",
                "retry failed operations with exponential backoff",
                "ensure a class has only one instance",
                "how is getActiveCalls used in the bulkhead",
                "where is getActiveCalls defined",
                "bir sınıfın yalnızca tek bir örneği olsun",
                "İstanbul",
                "?! ...",
                "",
                "   ",
                "end of sentence.",
                "a-b",
                // Separate words never join into one: neither camelCase across the space nor a word with a digit.
                "user Settings",
                "page 2"
            })
    void everythingElseIsNaturalLanguage(String query) {
        assertEquals(QueryShape.NATURAL_LANGUAGE, QueryShape.of(query));
    }

    @Test
    void identifiersFollowTheLexicalOrderAndNaturalLanguageWeighsBothSidesEqually() {
        assertEquals(1.0, QueryShape.NATURAL_LANGUAGE.lexicalWeight());
        assertEquals(1.0, QueryShape.NATURAL_LANGUAGE.semanticWeight());
        assertEquals(1.0, QueryShape.IDENTIFIER.lexicalWeight());
        assertEquals(0.002, QueryShape.IDENTIFIER.semanticWeight());
        assertThrows(NullPointerException.class, () -> QueryShape.of(null));
    }
}
