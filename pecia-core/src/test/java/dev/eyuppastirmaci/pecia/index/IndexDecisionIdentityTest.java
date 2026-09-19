package dev.eyuppastirmaci.pecia.index;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.eyuppastirmaci.pecia.chunking.ChunkingIdentity;
import dev.eyuppastirmaci.pecia.config.PeciaConfig;
import dev.eyuppastirmaci.pecia.content.ContentHash;
import dev.eyuppastirmaci.pecia.content.DocumentType;
import dev.eyuppastirmaci.pecia.content.ExtractionRequest;
import dev.eyuppastirmaci.pecia.storage.model.StoredFile;
import dev.eyuppastirmaci.pecia.tokenization.TokenizerCompatibility;
import dev.eyuppastirmaci.pecia.tokenization.TokenizerIdentity;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

class IndexDecisionIdentityTest {

    private static final Path SOURCE_PATH = Path.of("src/Auth.java");
    private static final ExtractionRequest REQUEST =
            new ExtractionRequest(SOURCE_PATH.toAbsolutePath().normalize(), SOURCE_PATH, DocumentType.SOURCE_CODE);
    private static final ContentHash HASH = hash("class Auth {}\n");
    private static final TokenizerCompatibility TOKENIZER =
            new TokenizerCompatibility("wordpiece-v1", "a".repeat(64), 30_000, 512, 2);
    private static final ChunkingIdentity IDENTITY =
            new ChunkingIdentity("extraction-v1", "chunking-v1", TOKENIZER, 128, 16);
    private static final StoredFile STORED = new StoredFile(1, SOURCE_PATH, DocumentType.SOURCE_CODE, HASH);

    @Test
    void identifiesANewPathWithoutStoredState() {
        assertEquals(IndexDecision.NEW, IndexDecision.evaluate(REQUEST, HASH, IDENTITY));
    }

    @Test
    void matchingContentTypeAndFullIdentityAreUnchanged() {
        ChunkingIdentity equivalentIdentity = new ChunkingIdentity("extraction-v1", "chunking-v1", TOKENIZER, 128, 16);

        assertEquals(
                IndexDecision.UNCHANGED,
                IndexDecision.evaluate(REQUEST, new ContentHash(HASH.value()), equivalentIdentity, STORED, IDENTITY));
    }

    @Test
    void unknownFullIdentityRequiresReprocessingEvenWhenContentAndTypeMatch() {
        assertEquals(IndexDecision.CHANGED, IndexDecision.evaluate(REQUEST, HASH, IDENTITY, STORED));
    }

    @ParameterizedTest(name = "{0} requires reprocessing")
    @MethodSource("changedIdentities")
    void everyBehaviorFieldChangeRequiresReprocessing(String field, ChunkingIdentity changedIdentity) {
        assertEquals(
                IndexDecision.CHANGED, IndexDecision.evaluate(REQUEST, HASH, changedIdentity, STORED, IDENTITY), field);
    }

    @ParameterizedTest
    @CsvSource({"other-model, revision-v1", "model, revision-v2", "other-model, revision-v2"})
    void modelNamesAndRevisionsAloneDoNotRequireReprocessing(String modelId, String revision) {
        ChunkingIdentity storedIdentity = ChunkingIdentity.from(
                PeciaConfig.defaults(),
                new TokenizerIdentity("model", "revision-v1", "wordpiece-v1", "a".repeat(64), 30_000, 512, 2));
        ChunkingIdentity candidateIdentity = ChunkingIdentity.from(
                PeciaConfig.defaults(),
                new TokenizerIdentity(modelId, revision, "wordpiece-v1", "a".repeat(64), 30_000, 512, 2));

        assertEquals(
                IndexDecision.UNCHANGED,
                IndexDecision.evaluate(REQUEST, HASH, candidateIdentity, STORED, storedIdentity));
    }

    @ParameterizedTest
    @ValueSource(strings = {"class Auth { String JWT_SECRET; }\n", "class Auth {}\r\n", "\uFEFFclass Auth {}\n", ""})
    void rawContentChangesRequireReprocessing(String changedContent) {
        assertEquals(
                IndexDecision.CHANGED,
                IndexDecision.evaluate(REQUEST, hash(changedContent), IDENTITY, STORED, IDENTITY));
    }

    @Test
    void documentTypeChangesRequireReprocessing() {
        ExtractionRequest changedType = new ExtractionRequest(REQUEST.file(), SOURCE_PATH, DocumentType.PLAIN_TEXT);

        assertEquals(IndexDecision.CHANGED, IndexDecision.evaluate(changedType, HASH, IDENTITY, STORED, IDENTITY));
    }

    @Test
    void movingTheProjectRootDoesNotChangeTheSourceIdentity() {
        Path relocatedFile =
                Path.of("relocated-project").toAbsolutePath().normalize().resolve(SOURCE_PATH);
        ExtractionRequest relocated = new ExtractionRequest(relocatedFile, SOURCE_PATH, DocumentType.SOURCE_CODE);

        assertEquals(IndexDecision.UNCHANGED, IndexDecision.evaluate(relocated, HASH, IDENTITY, STORED, IDENTITY));
    }

    @Test
    void rejectsStoredStateFromAnotherSourcePath() {
        StoredFile otherFile = new StoredFile(2, Path.of("src/Other.java"), DocumentType.SOURCE_CODE, HASH);

        assertThrows(IllegalArgumentException.class, () -> IndexDecision.evaluate(REQUEST, HASH, IDENTITY, otherFile));
        assertThrows(
                IllegalArgumentException.class,
                () -> IndexDecision.evaluate(REQUEST, HASH, IDENTITY, otherFile, IDENTITY));
    }

    @Test
    void validatesMandatoryInputsWhenThereIsNoStoredFile() {
        assertThrows(NullPointerException.class, () -> IndexDecision.evaluate(null, HASH, IDENTITY));
        assertThrows(NullPointerException.class, () -> IndexDecision.evaluate(REQUEST, null, IDENTITY));
        assertThrows(NullPointerException.class, () -> IndexDecision.evaluate(REQUEST, HASH, (ChunkingIdentity) null));
    }

    @Test
    void validatesMandatoryInputsWhenTheFullIdentityIsUnknown() {
        assertThrows(NullPointerException.class, () -> IndexDecision.evaluate(null, HASH, IDENTITY, STORED));
        assertThrows(NullPointerException.class, () -> IndexDecision.evaluate(REQUEST, null, IDENTITY, STORED));
        assertThrows(
                NullPointerException.class,
                () -> IndexDecision.evaluate(REQUEST, HASH, (ChunkingIdentity) null, STORED));
        assertThrows(NullPointerException.class, () -> IndexDecision.evaluate(REQUEST, HASH, IDENTITY, null));
    }

    @Test
    void validatesMandatoryInputsWhenTheFullIdentityIsKnown() {
        assertThrows(NullPointerException.class, () -> IndexDecision.evaluate(null, HASH, IDENTITY, STORED, IDENTITY));
        assertThrows(
                NullPointerException.class, () -> IndexDecision.evaluate(REQUEST, null, IDENTITY, STORED, IDENTITY));
        assertThrows(NullPointerException.class, () -> IndexDecision.evaluate(REQUEST, HASH, null, STORED, IDENTITY));
        assertThrows(NullPointerException.class, () -> IndexDecision.evaluate(REQUEST, HASH, IDENTITY, null, IDENTITY));
        assertThrows(NullPointerException.class, () -> IndexDecision.evaluate(REQUEST, HASH, IDENTITY, STORED, null));
    }

    @Test
    void changedContentCannotMaskAnInvalidStoredIdentity() {
        assertThrows(
                NullPointerException.class,
                () -> IndexDecision.evaluate(REQUEST, hash("changed"), IDENTITY, STORED, null));
    }

    private static Stream<Arguments> changedIdentities() {
        return Stream.of(
                Arguments.of(
                        "extraction version", new ChunkingIdentity("extraction-v2", "chunking-v1", TOKENIZER, 128, 16)),
                Arguments.of(
                        "chunking version", new ChunkingIdentity("extraction-v1", "chunking-v2", TOKENIZER, 128, 16)),
                Arguments.of(
                        "tokenizer algorithm",
                        identity(new TokenizerCompatibility("wordpiece-v2", "a".repeat(64), 30_000, 512, 2))),
                Arguments.of(
                        "vocabulary digest",
                        identity(new TokenizerCompatibility("wordpiece-v1", "b".repeat(64), 30_000, 512, 2))),
                Arguments.of(
                        "vocabulary size",
                        identity(new TokenizerCompatibility("wordpiece-v1", "a".repeat(64), 30_001, 512, 2))),
                Arguments.of(
                        "maximum input tokens",
                        identity(new TokenizerCompatibility("wordpiece-v1", "a".repeat(64), 30_000, 256, 2))),
                Arguments.of(
                        "special-token count",
                        identity(new TokenizerCompatibility("wordpiece-v1", "a".repeat(64), 30_000, 512, 3))),
                Arguments.of(
                        "maximum chunk tokens",
                        new ChunkingIdentity("extraction-v1", "chunking-v1", TOKENIZER, 64, 16)),
                Arguments.of(
                        "overlap tokens", new ChunkingIdentity("extraction-v1", "chunking-v1", TOKENIZER, 128, 8)));
    }

    private static ChunkingIdentity identity(TokenizerCompatibility tokenizer) {
        return new ChunkingIdentity("extraction-v1", "chunking-v1", tokenizer, 128, 16);
    }

    private static ContentHash hash(String content) {
        return ContentHash.sha256(content.getBytes(StandardCharsets.UTF_8));
    }
}
