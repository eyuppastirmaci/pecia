package dev.eyuppastirmaci.pecia.storage.sqlite;

import static dev.eyuppastirmaci.pecia.storage.sqlite.SqliteFtsTestSupport.execute;
import static dev.eyuppastirmaci.pecia.storage.sqlite.SqliteFtsTestSupport.rows;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.eyuppastirmaci.pecia.chunking.ChunkingIdentity;
import dev.eyuppastirmaci.pecia.config.PeciaConfig;
import dev.eyuppastirmaci.pecia.content.Chunk;
import dev.eyuppastirmaci.pecia.content.ChunkMetadata;
import dev.eyuppastirmaci.pecia.content.ContentHash;
import dev.eyuppastirmaci.pecia.content.Document;
import dev.eyuppastirmaci.pecia.content.DocumentType;
import dev.eyuppastirmaci.pecia.content.ExtractionRequest;
import dev.eyuppastirmaci.pecia.content.LineRange;
import dev.eyuppastirmaci.pecia.index.IndexingProfile;
import dev.eyuppastirmaci.pecia.storage.model.StoredFile;
import dev.eyuppastirmaci.pecia.tokenization.TokenizerIdentity;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SqliteIncrementalIdentityTest {
    private static final Path SOURCE = Path.of("notes.txt");
    private static final TokenizerIdentity TOKENIZER =
            new TokenizerIdentity("model", "revision", "wordpiece", "a".repeat(64), 100, 512, 2);
    private static final ChunkingIdentity IDENTITY = ChunkingIdentity.from(PeciaConfig.defaults(), TOKENIZER);
    private static final ChunkingIdentity UPDATED = new ChunkingIdentity(
            IDENTITY.extractionVersion(), "chunking-v2", IDENTITY.tokenizer(), IDENTITY.maxTokens(), 8);
    private static final IndexingProfile LEGACY = IndexingProfile.from(PeciaConfig.defaults(), TOKENIZER);

    @TempDir
    Path root;

    @ParameterizedTest
    @ValueSource(strings = {"needle İstanbul 😀", ""})
    void matchingFullIdentityIsReadOnlyAndPreservesChunksIdsAndSearchRows(String text) throws Exception {
        Path database = root.resolve("index.db");
        Document document = document(text);
        StoredFile file;
        try (var storage = SqliteStorage.open(database, root)) {
            file = storage.replaceFile(document, chunks(document), IDENTITY);
        }
        byte[] before = Files.readAllBytes(database);

        try (var storage = SqliteStorage.openReadOnly(database, root)) {
            var chunks = storage.chunks().findByFileId(file.id());
            var search = rows(storage.connection());

            assertEquals(Optional.of(file), storage.findUnchangedFile(request(), document.contentHash(), IDENTITY));

            assertEquals(chunks, storage.chunks().findByFileId(file.id()));
            assertEquals(search, rows(storage.connection()));
            assertTrue(storage.connection().getAutoCommit());
        }
        assertArrayEquals(before, Files.readAllBytes(database));
    }

    @Test
    void contentTypeAndFullIdentityMustAllMatch() throws Exception {
        Document document = document("needle");
        try (var storage = SqliteStorage.open(root.resolve("index.db"), root)) {
            StoredFile file = storage.replaceFile(document, chunks(document), IDENTITY);
            var originalChunks = storage.chunks().findByFileId(file.id());
            ExtractionRequest changedType = new ExtractionRequest(root.resolve(SOURCE), SOURCE, DocumentType.MARKDOWN);

            assertTrue(storage.findUnchangedFile(request(), document("changed").contentHash(), IDENTITY)
                    .isEmpty());
            assertTrue(storage.findUnchangedFile(changedType, document.contentHash(), IDENTITY)
                    .isEmpty());
            assertTrue(storage.findUnchangedFile(request(), document.contentHash(), UPDATED)
                    .isEmpty());

            assertEquals(file, storage.files().findByPath(SOURCE).orElseThrow());
            assertEquals(originalChunks, storage.chunks().findByFileId(file.id()));
            assertEquals(Optional.of(IDENTITY), storage.files().findChunkingIdentity(file.id()));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "unprofiled", "legacy", "invalidated"})
    void missingFullIdentityNeverQualifiesForSkipping(String state) throws Exception {
        Document document = document("needle");
        try (var storage = SqliteStorage.open(root.resolve("index.db"), root)) {
            switch (state) {
                case "missing" -> {}
                case "unprofiled" -> storage.replaceFile(document, chunks(document));
                case "legacy" -> {
                    StoredFile file = storage.replaceFile(document, chunks(document), LEGACY);
                    assertEquals(
                            Optional.of(file), storage.findUnchangedFile(request(), document.contentHash(), LEGACY));
                }
                case "invalidated" -> {
                    StoredFile file = storage.replaceFile(document, chunks(document), IDENTITY);
                    storage.chunks().deleteByFileId(file.id());
                }
                default -> throw new AssertionError(state);
            }
            var manifest = storage.files().findAll();

            assertTrue(storage.findUnchangedFile(request(), document.contentHash(), IDENTITY)
                    .isEmpty());

            assertEquals(manifest, storage.files().findAll());
            for (StoredFile file : manifest) {
                assertTrue(storage.files().findChunkingIdentity(file.id()).isEmpty());
            }
        }
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "DELETE FROM chunk_identities",
                "UPDATE chunk_identities SET stable_id ="
                        + " 'bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb'",
                "UPDATE file_chunking_profiles SET fingerprint ="
                        + " 'bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb'"
            })
    void corruptIdentityStateCannotBeReportedAsUnchanged(String corruption) throws Exception {
        Document document = document("needle");
        try (var storage = SqliteStorage.open(root.resolve("index.db"), root)) {
            StoredFile file = storage.replaceFile(document, chunks(document), IDENTITY);
            execute(storage.connection(), corruption);
            var search = rows(storage.connection());

            assertThrows(
                    SQLException.class, () -> storage.findUnchangedFile(request(), document.contentHash(), IDENTITY));

            assertEquals(file, storage.files().findByPath(SOURCE).orElseThrow());
            assertEquals(search, rows(storage.connection()));
            assertTrue(storage.connection().getAutoCommit());
        }
    }

    @Test
    void lookupUsesTheCallerSnapshotAndDoesNotCommitIt() throws Exception {
        Path database = root.resolve("index.db");
        Document original = document("original");
        Document replacement = document("replacement");
        try (var writer = SqliteStorage.open(database, root)) {
            try (var statement = writer.connection().createStatement();
                    var row = statement.executeQuery("PRAGMA journal_mode = WAL")) {
                assertTrue(row.next());
                assertEquals("wal", row.getString(1));
            }
            StoredFile oldFile = writer.replaceFile(original, chunks(original), IDENTITY);

            try (var reader = SqliteStorage.openReadOnly(database, root)) {
                reader.connection().setAutoCommit(false);
                assertEquals(
                        Optional.of(oldFile), reader.findUnchangedFile(request(), original.contentHash(), IDENTITY));

                StoredFile newFile = writer.replaceFile(replacement, chunks(replacement), UPDATED);

                assertEquals(
                        Optional.of(oldFile), reader.findUnchangedFile(request(), original.contentHash(), IDENTITY));
                assertTrue(reader.findUnchangedFile(request(), replacement.contentHash(), UPDATED)
                        .isEmpty());
                assertFalse(reader.connection().getAutoCommit());
                reader.connection().rollback();
                reader.connection().setAutoCommit(true);

                assertTrue(reader.findUnchangedFile(request(), original.contentHash(), IDENTITY)
                        .isEmpty());
                assertEquals(
                        Optional.of(newFile), reader.findUnchangedFile(request(), replacement.contentHash(), UPDATED));
            }
        }
    }

    @Test
    void requiredInputsAreValidatedBeforeAccessingStorage() throws Exception {
        SqliteStorage closed;
        try (var storage = SqliteStorage.open(root.resolve("index.db"), root)) {
            closed = storage;
            assertInvalidInputs(storage);
            assertTrue(storage.files().findAll().isEmpty());
        }
        assertInvalidInputs(closed);
        assertThrows(
                SQLException.class,
                () -> closed.findUnchangedFile(request(), document("needle").contentHash(), IDENTITY));
    }

    private void assertInvalidInputs(SqliteStorage storage) {
        ContentHash hash = document("needle").contentHash();
        assertThrows(NullPointerException.class, () -> storage.findUnchangedFile(null, hash, IDENTITY));
        assertThrows(NullPointerException.class, () -> storage.findUnchangedFile(request(), null, IDENTITY));
        assertThrows(
                NullPointerException.class, () -> storage.findUnchangedFile(request(), hash, (ChunkingIdentity) null));
    }

    private ExtractionRequest request() {
        return new ExtractionRequest(root.resolve(SOURCE), SOURCE, DocumentType.PLAIN_TEXT);
    }

    private static Document document(String text) {
        return new Document(
                SOURCE, DocumentType.PLAIN_TEXT, text, ContentHash.sha256(text.getBytes(StandardCharsets.UTF_8)));
    }

    private static List<Chunk> chunks(Document document) {
        return document.content().isEmpty()
                ? List.of()
                : List.of(new Chunk(
                        SOURCE, document.type(), 0, document.content(), new LineRange(1, 1), ChunkMetadata.empty()));
    }
}
