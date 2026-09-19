package dev.eyuppastirmaci.pecia.tokenization;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class TokenizerCompatibilityTest {

    private static final String VOCABULARY_HASH = "a".repeat(64);
    private static final TokenizerIdentity TOKENIZER =
            new TokenizerIdentity("test-model", "revision-1", "wordpiece", VOCABULARY_HASH, 100, 256, 2);

    @Test
    void copiesTokenizerBehaviorWithoutLoadingAModelOrVocabulary() {
        assertEquals(
                new TokenizerCompatibility("wordpiece", VOCABULARY_HASH, 100, 256, 2),
                TokenizerCompatibility.from(TOKENIZER));
    }

    @Test
    void modelNamesAndRepositoryRevisionsDoNotChangeCompatibility() {
        TokenizerCompatibility original = TokenizerCompatibility.from(TOKENIZER);
        TokenizerIdentity renamedModel =
                new TokenizerIdentity("other-model", "revision-1", "wordpiece", VOCABULARY_HASH, 100, 256, 2);
        TokenizerIdentity revisedModel =
                new TokenizerIdentity("test-model", "revision-2", "wordpiece", VOCABULARY_HASH, 100, 256, 2);

        assertEquals(original, TokenizerCompatibility.from(renamedModel));
        assertEquals(original, TokenizerCompatibility.from(revisedModel));
        assertEquals(
                original.hashCode(), TokenizerCompatibility.from(renamedModel).hashCode());
    }

    @Test
    void everyTokenizerBehaviorComponentAffectsCompatibility() {
        TokenizerCompatibility original = TokenizerCompatibility.from(TOKENIZER);
        List<TokenizerCompatibility> alternatives = List.of(
                new TokenizerCompatibility("wordpiece-v2", VOCABULARY_HASH, 100, 256, 2),
                new TokenizerCompatibility("wordpiece", "b".repeat(64), 100, 256, 2),
                new TokenizerCompatibility("wordpiece", VOCABULARY_HASH, 101, 256, 2),
                new TokenizerCompatibility("wordpiece", VOCABULARY_HASH, 100, 512, 2),
                new TokenizerCompatibility("wordpiece", VOCABULARY_HASH, 100, 256, 3));

        for (TokenizerCompatibility alternative : alternatives) {
            assertNotEquals(original, alternative);
        }
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t\n", "\uD800", "\uDC00", "algo\uD800x"})
    void rejectsMissingBlankOrMalformedAlgorithmIdentifiers(String algorithm) {
        Class<? extends RuntimeException> exception =
                algorithm == null ? NullPointerException.class : IllegalArgumentException.class;

        assertThrows(exception, () -> new TokenizerCompatibility(algorithm, VOCABULARY_HASH, 100, 256, 2));
    }

    @Test
    void acceptsCompleteUnicodeCodePointsInAlgorithmIdentifiers() {
        String algorithm = "algoritma-é-𐐀";

        assertEquals(algorithm, new TokenizerCompatibility(algorithm, VOCABULARY_HASH, 100, 256, 2).algorithm());
    }

    @Test
    void requiresAnExactLowercaseSha256VocabularyDigest() {
        assertThrows(NullPointerException.class, () -> new TokenizerCompatibility("wordpiece", null, 100, 256, 2));

        for (String invalid : List.of("", " ", "a".repeat(63), "a".repeat(65), "A".repeat(64), "g".repeat(64))) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> new TokenizerCompatibility("wordpiece", invalid, 100, 256, 2));
        }
    }

    @ParameterizedTest
    @CsvSource({"0, 256, 2", "-1, 256, 2", "100, 0, 0", "100, -1, 0", "100, 256, -1", "100, 256, 256", "100, 256, 257"})
    void rejectsInconsistentVocabularyAndInputLimits(int vocabularySize, int maxInputTokens, int specialTokenCount) {
        assertThrows(
                IllegalArgumentException.class,
                () -> new TokenizerCompatibility(
                        "wordpiece", VOCABULARY_HASH, vocabularySize, maxInputTokens, specialTokenCount));
    }

    @ParameterizedTest
    @CsvSource({"1, 1, 0", "100, 256, 255", "2147483647, 2147483647, 2147483646"})
    void acceptsBoundaryLimits(int vocabularySize, int maxInputTokens, int specialTokenCount) {
        TokenizerCompatibility compatibility = new TokenizerCompatibility(
                "wordpiece", VOCABULARY_HASH, vocabularySize, maxInputTokens, specialTokenCount);

        assertEquals(vocabularySize, compatibility.vocabularySize());
        assertEquals(maxInputTokens, compatibility.maxInputTokens());
        assertEquals(specialTokenCount, compatibility.specialTokenCount());
    }

    @Test
    void requiresTokenizerIdentity() {
        assertThrows(NullPointerException.class, () -> TokenizerCompatibility.from(null));
    }
}
