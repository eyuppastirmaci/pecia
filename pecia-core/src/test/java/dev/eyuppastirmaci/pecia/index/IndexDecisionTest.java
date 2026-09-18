package dev.eyuppastirmaci.pecia.index;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.eyuppastirmaci.pecia.content.ContentHash;
import dev.eyuppastirmaci.pecia.content.DocumentType;
import dev.eyuppastirmaci.pecia.content.ExtractionRequest;
import dev.eyuppastirmaci.pecia.storage.model.StoredFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class IndexDecisionTest {

    private static final Path SOURCE_PATH = Path.of("src/Auth.java");
    private static final ExtractionRequest REQUEST =
            new ExtractionRequest(SOURCE_PATH.toAbsolutePath().normalize(), SOURCE_PATH, DocumentType.SOURCE_CODE);
    private static final ContentHash HASH = hash("class Auth {}\n");
    private static final IndexingProfile PROFILE = new IndexingProfile("test-tokenizer-v1", 128, 16);
    private static final StoredFile STORED = new StoredFile(1, SOURCE_PATH, DocumentType.SOURCE_CODE, HASH);

    @Test
    void identifiesANewPathWithoutStoredState() {
        assertEquals(IndexDecision.NEW, IndexDecision.evaluate(REQUEST, HASH, PROFILE));
    }

    @Test
    void skipsOnlyWhenContentTypeAndKnownProfileAllMatch() {
        IndexingProfile equivalentProfile = new IndexingProfile(PROFILE.tokenizerKey(), 128, 16);
        ContentHash equivalentHash = new ContentHash(HASH.value());

        assertEquals(
                IndexDecision.UNCHANGED,
                IndexDecision.evaluate(REQUEST, equivalentHash, equivalentProfile, STORED, PROFILE));
    }

    @Test
    void reprocessesLegacyRecordsWithAnUnknownProfileEvenIfContentMatches() {
        assertEquals(IndexDecision.CHANGED, IndexDecision.evaluate(REQUEST, HASH, PROFILE, STORED));
    }

    @ParameterizedTest
    @ValueSource(strings = {"class Auth { String JWT_SECRET; }\n", "class Auth {}\r\n", "\uFEFFclass Auth {}\n", ""})
    void contentByteChangesRequireReprocessing(String changedContent) {
        assertEquals(
                IndexDecision.CHANGED, IndexDecision.evaluate(REQUEST, hash(changedContent), PROFILE, STORED, PROFILE));
    }

    @Test
    void documentTypeChangesRequireReprocessingEvenIfContentMatches() {
        ExtractionRequest changedType = new ExtractionRequest(REQUEST.file(), SOURCE_PATH, DocumentType.PLAIN_TEXT);

        assertEquals(IndexDecision.CHANGED, IndexDecision.evaluate(changedType, HASH, PROFILE, STORED, PROFILE));
    }

    @Test
    void tokenizerAndChunkSettingChangesRequireReprocessing() {
        List<IndexingProfile> alternatives = List.of(
                new IndexingProfile("test-tokenizer-v2", 128, 16),
                new IndexingProfile(PROFILE.tokenizerKey(), 64, 16),
                new IndexingProfile(PROFILE.tokenizerKey(), 128, 8));

        for (IndexingProfile alternative : alternatives) {
            assertEquals(IndexDecision.CHANGED, IndexDecision.evaluate(REQUEST, HASH, alternative, STORED, PROFILE));
        }
    }

    @Test
    void rejectsStateLookedUpForAnotherPath() {
        StoredFile otherFile = new StoredFile(2, Path.of("src/Other.java"), DocumentType.SOURCE_CODE, HASH);

        assertThrows(IllegalArgumentException.class, () -> IndexDecision.evaluate(REQUEST, HASH, PROFILE, otherFile));
        assertThrows(
                IllegalArgumentException.class,
                () -> IndexDecision.evaluate(REQUEST, HASH, PROFILE, otherFile, PROFILE));
    }

    @Test
    void requiresAStoredFileWhenSupplyingAStoredProfile() {
        assertThrows(NullPointerException.class, () -> IndexDecision.evaluate(REQUEST, HASH, PROFILE, null, PROFILE));
    }

    @Test
    void validatesMandatoryInputsEvenWhenThereIsNoStoredFile() {
        assertThrows(NullPointerException.class, () -> IndexDecision.evaluate(null, HASH, PROFILE));
        assertThrows(NullPointerException.class, () -> IndexDecision.evaluate(REQUEST, null, PROFILE));
        assertThrows(NullPointerException.class, () -> IndexDecision.evaluate(REQUEST, HASH, null));
    }

    @Test
    void validatesMandatoryInputsForLegacyStoredFiles() {
        assertThrows(NullPointerException.class, () -> IndexDecision.evaluate(null, HASH, PROFILE, STORED));
        assertThrows(NullPointerException.class, () -> IndexDecision.evaluate(REQUEST, null, PROFILE, STORED));
        assertThrows(NullPointerException.class, () -> IndexDecision.evaluate(REQUEST, HASH, null, STORED));
        assertThrows(NullPointerException.class, () -> IndexDecision.evaluate(REQUEST, HASH, PROFILE, null));
    }

    @Test
    void validatesMandatoryInputsWhenTheStoredProfileIsKnown() {
        assertThrows(NullPointerException.class, () -> IndexDecision.evaluate(null, HASH, PROFILE, STORED, PROFILE));
        assertThrows(NullPointerException.class, () -> IndexDecision.evaluate(REQUEST, null, PROFILE, STORED, PROFILE));
        assertThrows(NullPointerException.class, () -> IndexDecision.evaluate(REQUEST, HASH, null, STORED, PROFILE));
        assertThrows(NullPointerException.class, () -> IndexDecision.evaluate(REQUEST, HASH, PROFILE, STORED, null));
    }

    @Test
    void doesNotTreatANullStoredProfileAsAnUnknownLegacyProfile() {
        ContentHash changedHash = hash("changed content");

        assertThrows(
                NullPointerException.class, () -> IndexDecision.evaluate(REQUEST, changedHash, PROFILE, STORED, null));
    }

    private static ContentHash hash(String content) {
        return ContentHash.sha256(content.getBytes(StandardCharsets.UTF_8));
    }
}
