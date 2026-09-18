package dev.eyuppastirmaci.pecia.index;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.eyuppastirmaci.pecia.config.PeciaConfig;
import dev.eyuppastirmaci.pecia.tokenization.TokenizerIdentity;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class IndexingProfileTest {

    private static final String VOCABULARY_HASH = "a".repeat(64);
    private static final TokenizerIdentity TOKENIZER =
            new TokenizerIdentity("test-model", "revision-1", "wordpiece", VOCABULARY_HASH, 100, 256, 2);

    @Test
    void capturesTokenizerCompatibilityAndChunkSettingsWithoutLoadingTheTokenizer() {
        IndexingProfile profile = IndexingProfile.from(config(128, 16), TOKENIZER);

        assertEquals(new IndexingProfile(TOKENIZER.compatibilityKey(), 128, 16), profile);
        assertEquals(profile, IndexingProfile.from(config(128, 16), TOKENIZER));
    }

    @Test
    void chunkLimitsAndOverlapChangesProduceDifferentProfiles() {
        IndexingProfile original = IndexingProfile.from(config(128, 16), TOKENIZER);

        assertNotEquals(original, IndexingProfile.from(config(64, 16), TOKENIZER));
        assertNotEquals(original, IndexingProfile.from(config(128, 8), TOKENIZER));
    }

    @Test
    void everyTokenizerCompatibilityComponentParticipatesInTheProfile() {
        IndexingProfile original = IndexingProfile.from(config(128, 16), TOKENIZER);
        List<TokenizerIdentity> alternatives = List.of(
                new TokenizerIdentity("other-model", "revision-1", "wordpiece", VOCABULARY_HASH, 100, 256, 2),
                new TokenizerIdentity("test-model", "revision-2", "wordpiece", VOCABULARY_HASH, 100, 256, 2),
                new TokenizerIdentity("test-model", "revision-1", "other-algorithm", VOCABULARY_HASH, 100, 256, 2),
                new TokenizerIdentity("test-model", "revision-1", "wordpiece", "b".repeat(64), 100, 256, 2),
                new TokenizerIdentity("test-model", "revision-1", "wordpiece", VOCABULARY_HASH, 101, 256, 2),
                new TokenizerIdentity("test-model", "revision-1", "wordpiece", VOCABULARY_HASH, 100, 512, 2),
                new TokenizerIdentity("test-model", "revision-1", "wordpiece", VOCABULARY_HASH, 100, 256, 3));

        for (TokenizerIdentity alternative : alternatives) {
            assertNotEquals(original, IndexingProfile.from(config(128, 16), alternative));
        }
    }

    @Test
    void discoveryAdmissionAndStorageSettingsDoNotChangeChunkCompatibility() {
        PeciaConfig first = config(128, 16);
        PeciaConfig second =
                new PeciaConfig(List.of("**/*.java"), List.of("generated/**"), 1024, 128, 16, 8, "cache/other.db");

        assertEquals(IndexingProfile.from(first, TOKENIZER), IndexingProfile.from(second, TOKENIZER));
    }

    @ParameterizedTest
    @CsvSource({"1, 0", "2, 0", "257, 0", "128, 126", "128, 127"})
    void rejectsChunkSettingsThatViolateTheTokenizerInputBudget(int maxTokens, int overlapTokens) {
        PeciaConfig config = config(maxTokens, overlapTokens);

        assertThrows(IllegalArgumentException.class, () -> IndexingProfile.from(config, TOKENIZER));
    }

    @ParameterizedTest
    @CsvSource({"3, 0", "128, 125", "256, 253"})
    void acceptsTheContentBudgetBoundariesAfterReservingSpecialTokens(int maxTokens, int overlapTokens) {
        IndexingProfile profile = IndexingProfile.from(config(maxTokens, overlapTokens), TOKENIZER);

        assertEquals(new IndexingProfile(TOKENIZER.compatibilityKey(), maxTokens, overlapTokens), profile);
    }

    @ParameterizedTest
    @CsvSource({"0, 0", "-1, 0", "128, -1", "128, 128", "128, 129"})
    void rejectsInvalidStoredTokenLimits(int maxTokens, int overlapTokens) {
        assertThrows(
                IllegalArgumentException.class,
                () -> new IndexingProfile(TOKENIZER.compatibilityKey(), maxTokens, overlapTokens));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t\n"})
    void requiresAKnownNonBlankTokenizerKey(String key) {
        Class<? extends RuntimeException> exception =
                key == null ? NullPointerException.class : IllegalArgumentException.class;

        assertThrows(exception, () -> new IndexingProfile(key, 128, 16));
    }

    @Test
    void requiresConfigAndTokenizerIdentity() {
        assertThrows(NullPointerException.class, () -> IndexingProfile.from(null, TOKENIZER));
        assertThrows(NullPointerException.class, () -> IndexingProfile.from(config(128, 16), null));
    }

    private static PeciaConfig config(int maxTokens, int overlapTokens) {
        return new PeciaConfig(List.of(), List.of(), 4096, maxTokens, overlapTokens, 1, ".pecia/index.db");
    }
}
