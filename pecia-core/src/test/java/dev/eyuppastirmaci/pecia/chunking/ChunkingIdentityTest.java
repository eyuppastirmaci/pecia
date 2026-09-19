package dev.eyuppastirmaci.pecia.chunking;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.eyuppastirmaci.pecia.config.PeciaConfig;
import dev.eyuppastirmaci.pecia.tokenization.TokenizerCompatibility;
import dev.eyuppastirmaci.pecia.tokenization.TokenizerIdentity;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class ChunkingIdentityTest {

    private static final String EXTRACTION_VERSION = "pecia-utf8-extraction-v1";
    private static final String CHUNKING_VERSION = "pecia-built-in-chunking-v1";
    private static final String VOCABULARY_HASH = "a".repeat(64);
    private static final TokenizerIdentity TOKENIZER =
            new TokenizerIdentity("test-model", "revision-1", "wordpiece", VOCABULARY_HASH, 100, 256, 2);
    private static final TokenizerCompatibility COMPATIBILITY = TokenizerCompatibility.from(TOKENIZER);

    @Test
    void createsTheBuiltInIdentityFromMetadataWithoutLoadingTheTokenizer() {
        ChunkingIdentity identity = ChunkingIdentity.from(config(128, 16), TOKENIZER);

        assertEquals(new ChunkingIdentity(EXTRACTION_VERSION, CHUNKING_VERSION, COMPATIBILITY, 128, 16), identity);
        assertEquals(identity, ChunkingIdentity.from(config(128, 16), TOKENIZER));
    }

    @Test
    void fingerprintHasAStableVersionedCanonicalEncoding() {
        ChunkingIdentity identity = ChunkingIdentity.from(config(128, 16), TOKENIZER);

        // Independently generated with Python hashlib/struct: UTF-8 fields with big-endian int32
        // byte lengths (format tag, extraction, chunking, algorithm, digest), then five int32 limits.
        assertEquals("f4e01103f94180c4c5c95a0eab680c1445ad90e4314ec383c17a9a2d349fdb54", identity.fingerprint());
        assertEquals(identity.fingerprint(), identity.fingerprint());
        assertTrue(identity.fingerprint().matches("[0-9a-f]{64}"));
    }

    @Test
    void fingerprintUsesUtf8ByteLengthsForUnicodeIdentifiers() {
        ChunkingIdentity identity = new ChunkingIdentity(
                "çıkarma-東京-🧪",
                "bölümleme-Δ",
                new TokenizerCompatibility("algoritma-é-𐐀", "b".repeat(64), 321, 512, 3),
                255,
                25);

        // This second independent vector distinguishes UTF-8 byte lengths from Java char counts.
        assertEquals("41a1d9915b9df3d684463556eff0bd3952dfeae44791f017f5ad1b7ec126fd00", identity.fingerprint());
    }

    @Test
    void everyProcessingVersionTokenizerPropertyAndTokenSettingChangesTheFingerprint() {
        ChunkingIdentity original = ChunkingIdentity.from(config(128, 16), TOKENIZER);
        List<ChunkingIdentity> alternatives = List.of(
                new ChunkingIdentity("extraction-v2", CHUNKING_VERSION, COMPATIBILITY, 128, 16),
                new ChunkingIdentity(EXTRACTION_VERSION, "chunking-v2", COMPATIBILITY, 128, 16),
                identity(new TokenizerCompatibility("wordpiece-v2", VOCABULARY_HASH, 100, 256, 2)),
                identity(new TokenizerCompatibility("wordpiece", "b".repeat(64), 100, 256, 2)),
                identity(new TokenizerCompatibility("wordpiece", VOCABULARY_HASH, 101, 256, 2)),
                identity(new TokenizerCompatibility("wordpiece", VOCABULARY_HASH, 100, 512, 2)),
                identity(new TokenizerCompatibility("wordpiece", VOCABULARY_HASH, 100, 256, 3)),
                new ChunkingIdentity(EXTRACTION_VERSION, CHUNKING_VERSION, COMPATIBILITY, 64, 16),
                new ChunkingIdentity(EXTRACTION_VERSION, CHUNKING_VERSION, COMPATIBILITY, 128, 8));

        for (ChunkingIdentity alternative : alternatives) {
            assertNotEquals(original, alternative);
            assertNotEquals(original.fingerprint(), alternative.fingerprint());
        }
    }

    @Test
    void modelOnlyChangesPreserveIdentityAndFingerprint() {
        ChunkingIdentity original = ChunkingIdentity.from(config(128, 16), TOKENIZER);
        List<TokenizerIdentity> alternatives = List.of(
                new TokenizerIdentity("other-model", "revision-1", "wordpiece", VOCABULARY_HASH, 100, 256, 2),
                new TokenizerIdentity("test-model", "revision-2", "wordpiece", VOCABULARY_HASH, 100, 256, 2));

        for (TokenizerIdentity tokenizer : alternatives) {
            ChunkingIdentity alternative = ChunkingIdentity.from(config(128, 16), tokenizer);

            assertEquals(original, alternative);
            assertEquals(original.hashCode(), alternative.hashCode());
            assertEquals(original.fingerprint(), alternative.fingerprint());
        }
    }

    @Test
    void discoveryAdmissionConcurrencyAndStorageSettingsDoNotChangeIdentity() {
        ChunkingIdentity original = ChunkingIdentity.from(config(128, 16), TOKENIZER);
        PeciaConfig otherSettings =
                new PeciaConfig(List.of("**/*.java"), List.of("generated/**"), 1024, 128, 16, 8, "cache/other.db");
        ChunkingIdentity alternative = ChunkingIdentity.from(otherSettings, TOKENIZER);

        assertEquals(original, alternative);
        assertEquals(original.fingerprint(), alternative.fingerprint());
    }

    @Test
    void delimitersInsideVersionIdentifiersCannotCreateAmbiguousFingerprints() {
        ChunkingIdentity first = new ChunkingIdentity("extract:a", "chunk", COMPATIBILITY, 128, 16);
        ChunkingIdentity second = new ChunkingIdentity("extract", "a:chunk", COMPATIBILITY, 128, 16);

        assertNotEquals(first.fingerprint(), second.fingerprint());
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t\n", "\uD800", "\uDC00", "version\uD800x"})
    void rejectsMissingBlankOrMalformedProcessingVersions(String version) {
        Class<? extends RuntimeException> exception =
                version == null ? NullPointerException.class : IllegalArgumentException.class;

        assertThrows(exception, () -> new ChunkingIdentity(version, CHUNKING_VERSION, COMPATIBILITY, 128, 16));
        assertThrows(exception, () -> new ChunkingIdentity(EXTRACTION_VERSION, version, COMPATIBILITY, 128, 16));
    }

    @ParameterizedTest
    @CsvSource({"0, 0", "-1, 0", "1, 0", "2, 0", "257, 0", "128, -1", "128, 126", "128, 127", "128, 128"})
    void rejectsInvalidOrIncompatibleChunkBudgets(int maxTokens, int overlapTokens) {
        assertThrows(
                IllegalArgumentException.class,
                () -> new ChunkingIdentity(
                        EXTRACTION_VERSION, CHUNKING_VERSION, COMPATIBILITY, maxTokens, overlapTokens));
    }

    @ParameterizedTest
    @CsvSource({"1, 0", "2, 0", "257, 0", "128, 126", "128, 127"})
    void configFactoryEnforcesTheTokenizerContentBudget(int maxTokens, int overlapTokens) {
        PeciaConfig config = config(maxTokens, overlapTokens);

        assertThrows(IllegalArgumentException.class, () -> ChunkingIdentity.from(config, TOKENIZER));
    }

    @ParameterizedTest
    @CsvSource({"3, 0", "128, 125", "256, 253"})
    void acceptsContentBudgetBoundariesAfterReservingSpecialTokens(int maxTokens, int overlapTokens) {
        ChunkingIdentity identity = ChunkingIdentity.from(config(maxTokens, overlapTokens), TOKENIZER);

        assertEquals(maxTokens, identity.maxTokens());
        assertEquals(overlapTokens, identity.overlapTokens());
    }

    @Test
    void validatesExtremeTokenBudgetsWithoutIntegerOverflow() {
        TokenizerCompatibility fullContent =
                new TokenizerCompatibility("wordpiece", VOCABULARY_HASH, 100, Integer.MAX_VALUE, 0);
        TokenizerCompatibility singleContentToken =
                new TokenizerCompatibility("wordpiece", VOCABULARY_HASH, 100, Integer.MAX_VALUE, Integer.MAX_VALUE - 1);
        ChunkingIdentity largest = new ChunkingIdentity(
                EXTRACTION_VERSION, CHUNKING_VERSION, fullContent, Integer.MAX_VALUE, Integer.MAX_VALUE - 1);
        ChunkingIdentity smallestContent =
                new ChunkingIdentity(EXTRACTION_VERSION, CHUNKING_VERSION, singleContentToken, Integer.MAX_VALUE, 0);

        assertEquals(Integer.MAX_VALUE - 1, largest.overlapTokens());
        assertEquals(0, smallestContent.overlapTokens());
        assertThrows(
                IllegalArgumentException.class,
                () -> new ChunkingIdentity(
                        EXTRACTION_VERSION, CHUNKING_VERSION, singleContentToken, Integer.MAX_VALUE, 1));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ChunkingIdentity(
                        EXTRACTION_VERSION, CHUNKING_VERSION, singleContentToken, Integer.MAX_VALUE - 1, 0));
    }

    @Test
    void requiresTokenizerMetadataAndConfiguration() {
        assertThrows(
                NullPointerException.class,
                () -> new ChunkingIdentity(EXTRACTION_VERSION, CHUNKING_VERSION, null, 128, 16));
        assertThrows(NullPointerException.class, () -> ChunkingIdentity.from(null, TOKENIZER));
        assertThrows(NullPointerException.class, () -> ChunkingIdentity.from(config(128, 16), null));
    }

    private static ChunkingIdentity identity(TokenizerCompatibility tokenizer) {
        return new ChunkingIdentity(EXTRACTION_VERSION, CHUNKING_VERSION, tokenizer, 128, 16);
    }

    private static PeciaConfig config(int maxTokens, int overlapTokens) {
        return new PeciaConfig(List.of(), List.of(), 4096, maxTokens, overlapTokens, 1, ".pecia/index.db");
    }
}
