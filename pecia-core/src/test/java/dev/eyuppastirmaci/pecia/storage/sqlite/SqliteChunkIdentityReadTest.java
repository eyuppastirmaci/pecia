package dev.eyuppastirmaci.pecia.storage.sqlite;

import static dev.eyuppastirmaci.pecia.storage.sqlite.SqliteFtsTestSupport.execute;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.eyuppastirmaci.pecia.chunking.ChunkIdGenerator;
import dev.eyuppastirmaci.pecia.chunking.ChunkingIdentity;
import dev.eyuppastirmaci.pecia.content.Chunk;
import dev.eyuppastirmaci.pecia.content.ChunkMetadata;
import dev.eyuppastirmaci.pecia.content.ContentHash;
import dev.eyuppastirmaci.pecia.content.Document;
import dev.eyuppastirmaci.pecia.content.DocumentType;
import dev.eyuppastirmaci.pecia.content.LineRange;
import dev.eyuppastirmaci.pecia.storage.model.StoredFile;
import dev.eyuppastirmaci.pecia.storage.sqlite.mapper.ChunkingIdentityRowMapper;
import dev.eyuppastirmaci.pecia.tokenization.TokenizerCompatibility;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SqliteChunkIdentityReadTest {

    private static final ChunkingIdentity IDENTITY = new ChunkingIdentity(
            "extraction-v1",
            "chunking-v1",
            new TokenizerCompatibility("wordpiece-v1", "a".repeat(64), 30522, 512, 2),
            256,
            32);
    private static final Path SOURCE = Path.of("docs/İstanbul-😀.md");

    @TempDir
    Path root;

    @ParameterizedTest
    @ValueSource(
            strings = {
                "extraction_version = ''",
                "extraction_version = char(9)",
                "extraction_version = X'616263'",
                "chunking_version = char(9)",
                "chunking_version = X'616263'",
                "tokenizer_algorithm = char(9)",
                "tokenizer_algorithm = X'616263'",
                "vocabulary_sha256 = upper(vocabulary_sha256)",
                "vocabulary_sha256 = CAST(vocabulary_sha256 AS BLOB)",
                "vocabulary_size = 0",
                "vocabulary_size = 1.5",
                "vocabulary_size = 4294997818",
                "max_input_tokens = 0",
                "max_input_tokens = 512.5",
                "max_input_tokens = 4294967808",
                "special_token_count = -1",
                "special_token_count = 2.5",
                "special_token_count = 4294967298",
                "special_token_count = max_input_tokens",
                "max_tokens = special_token_count",
                "max_tokens = max_input_tokens + 1",
                "max_tokens = 256.5",
                "max_tokens = 4294967552",
                "overlap_tokens = -1",
                "overlap_tokens = 32.5",
                "overlap_tokens = 4294967328",
                "overlap_tokens = max_tokens - special_token_count",
                "fingerprint = upper(fingerprint)",
                "fingerprint = CAST(fingerprint AS BLOB)",
                "fingerprint = 'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa'"
            })
    void rejectsInvalidStoredProfilesWithoutSqliteCoercion(String assignment) throws Exception {
        try (var storage = SqliteStorage.open(root.resolve("index.db"), root)) {
            StoredFile file = save(storage, SOURCE, IDENTITY);
            execute(storage.connection(), "PRAGMA ignore_check_constraints = ON");
            execute(
                    storage.connection(),
                    "UPDATE file_chunking_profiles SET " + assignment + " WHERE file_id = ?",
                    file.id());

            try (var query =
                    storage.connection().prepareStatement("SELECT * FROM file_chunking_profiles WHERE file_id = ?")) {
                query.setLong(1, file.id());
                try (var row = query.executeQuery()) {
                    assertTrue(row.next());
                    assertThrows(SQLException.class, () -> new ChunkingIdentityRowMapper().map(row));
                }
            }
            assertInvalidReads(storage, file.id());
        }
    }

    @Test
    void roundTripsExactUnicodeAndNulVersionTextThroughReadOnlyStorage() throws Exception {
        ChunkingIdentity identity = new ChunkingIdentity(
                "\0extract-İstanbul-😀",
                "\0chunk-é-e\u0301",
                new TokenizerCompatibility("\0tokenize-日本語", "a".repeat(64), 30522, 512, 2),
                256,
                32);
        Path database = root.resolve("index.db");
        StoredFile file;
        try (var storage = SqliteStorage.open(database, root)) {
            file = save(storage, SOURCE, identity);
        }
        byte[] before = Files.readAllBytes(database);

        try (var storage = SqliteStorage.openReadOnly(database, root)) {
            assertEquals(Optional.of(identity), storage.files().findChunkingIdentity(file.id()));
            assertEquals(
                    Optional.of(new ChunkIdGenerator(identity).generate(SOURCE, 0)),
                    storage.chunks().findByFileId(file.id()).getFirst().stableId());
        }
        assertArrayEquals(before, Files.readAllBytes(database));
    }

    @Test
    void missingAndLegacyFilesHaveUnknownIdentities() throws Exception {
        try (var storage = SqliteStorage.open(root.resolve("index.db"), root)) {
            Document document = document(SOURCE);
            StoredFile file = storage.replaceFile(document, List.of(chunk(document)));

            assertTrue(storage.files().findChunkingIdentity(file.id()).isEmpty());
            assertTrue(storage.chunks()
                    .findByFileId(file.id())
                    .getFirst()
                    .stableId()
                    .isEmpty());
            assertTrue(storage.files().findChunkingIdentity(Long.MAX_VALUE).isEmpty());
            assertTrue(storage.chunks().findByFileId(Long.MAX_VALUE).isEmpty());
        }
    }

    @Test
    void acceptsProfiledFilesWithNoChunks() throws Exception {
        try (var storage = SqliteStorage.open(root.resolve("index.db"), root)) {
            Document empty = new Document(SOURCE, DocumentType.MARKDOWN, "", ContentHash.sha256(new byte[0]));
            StoredFile file = storage.replaceFile(empty, List.of(), IDENTITY);

            assertEquals(Optional.of(IDENTITY), storage.files().findChunkingIdentity(file.id()));
            assertTrue(storage.chunks().findByFileId(file.id()).isEmpty());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"DELETE", "UPDATE"})
    void rejectsIncompleteIdentityCoverage(String operation) throws Exception {
        try (var storage = SqliteStorage.open(root.resolve("index.db"), root)) {
            StoredFile file = save(storage, SOURCE, IDENTITY);
            if (operation.equals("DELETE")) {
                execute(storage.connection(), "DELETE FROM chunk_identities WHERE file_id = ?", file.id());
            } else {
                // Even a no-op profile update invalidates its identities.
                execute(
                        storage.connection(),
                        "UPDATE file_chunking_profiles SET fingerprint = fingerprint WHERE file_id = ?",
                        file.id());
            }

            assertInvalidReads(storage, file.id());
        }
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "stable_id = ''",
                "stable_id = upper(stable_id)",
                "stable_id = CAST(stable_id AS BLOB)",
                "stable_id = 'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa'"
            })
    void rejectsMalformedOrIncorrectStableIds(String assignment) throws Exception {
        try (var storage = SqliteStorage.open(root.resolve("index.db"), root)) {
            StoredFile file = save(storage, SOURCE, IDENTITY);
            execute(storage.connection(), "PRAGMA ignore_check_constraints = ON");
            execute(
                    storage.connection(),
                    "UPDATE chunk_identities SET " + assignment + " WHERE file_id = ?",
                    file.id());

            assertInvalidReads(storage, file.id());
        }
    }

    @Test
    void rejectsIdentityRowsOwnedByAnotherFileFromBothFiles() throws Exception {
        try (var storage = SqliteStorage.open(root.resolve("index.db"), root)) {
            StoredFile file = save(storage, SOURCE, IDENTITY);
            StoredFile other = save(storage, Path.of("other.md"), IDENTITY);
            execute(storage.connection(), "PRAGMA foreign_keys = OFF");
            execute(
                    storage.connection(),
                    "UPDATE chunk_identities SET file_id = ? WHERE file_id = ?",
                    other.id(),
                    file.id());

            assertInvalidReads(storage, file.id());
            assertInvalidReads(storage, other.id());
        }
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "DELETE FROM file_chunking_profiles WHERE file_id = ?",
                "DELETE FROM files WHERE id = ?",
                "UPDATE chunk_identities SET chunk_id = 999 WHERE file_id = ?",
                "UPDATE chunk_identities SET file_id = 999 WHERE file_id = ?"
            })
    void rejectsOrphanProfilesAndIdentities(String damage) throws Exception {
        try (var storage = SqliteStorage.open(root.resolve("index.db"), root)) {
            StoredFile file = save(storage, SOURCE, IDENTITY);
            execute(storage.connection(), "PRAGMA foreign_keys = OFF");
            execute(storage.connection(), damage, file.id());

            assertInvalidReads(storage, file.id());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"0.5", "4294967296", "2", "'invalid'"})
    void rejectsInvalidStoredChunkIndicesInsteadOfCoercingThem(String index) throws Exception {
        try (var storage = SqliteStorage.open(root.resolve("index.db"), root)) {
            StoredFile file = save(storage, SOURCE, IDENTITY);
            long chunkId = storage.chunks().findByFileId(file.id()).getFirst().id();
            execute(storage.connection(), "UPDATE chunks SET chunk_index = " + index + " WHERE id = ?", chunkId);
            // The ordinary update invalidated provenance. Restore it as an external corrupt writer could.
            insertProfile(storage.connection(), file.id(), IDENTITY);
            execute(
                    storage.connection(),
                    "INSERT INTO chunk_identities VALUES (?, ?, ?)",
                    chunkId,
                    file.id(),
                    new ChunkIdGenerator(IDENTITY).generate(SOURCE, 0).value());

            assertInvalidReads(storage, file.id());
        }
    }

    @Test
    void rejectsProfileFingerprintThatWasNotUpdatedWithItsFields() throws Exception {
        try (var storage = SqliteStorage.open(root.resolve("index.db"), root)) {
            StoredFile file = save(storage, SOURCE, IDENTITY);
            execute(
                    storage.connection(),
                    "UPDATE file_chunking_profiles SET extraction_version = 'changed-v2' WHERE file_id = ?",
                    file.id());

            SQLException failure =
                    assertThrows(SQLException.class, () -> storage.files().findChunkingIdentity(file.id()));
            assertTrue(failure.getMessage().contains("fingerprint"));
        }
    }

    @Test
    void callerOwnedReadSnapshotKeepsIdentityAndContentTogetherAcrossReplacement() throws Exception {
        Path database = root.resolve("index.db");
        ChunkingIdentity updated =
                new ChunkingIdentity(IDENTITY.extractionVersion(), "chunking-v2", IDENTITY.tokenizer(), 256, 32);
        try (var writer = SqliteStorage.open(database, root)) {
            try (var statement = writer.connection().createStatement();
                    var row = statement.executeQuery("PRAGMA journal_mode = WAL")) {
                assertTrue(row.next());
                assertEquals("wal", row.getString(1));
            }
            StoredFile file = save(writer, SOURCE, IDENTITY);

            try (var reader = SqliteStorage.openReadOnly(database, root)) {
                reader.connection().setAutoCommit(false);
                var oldChunks = reader.chunks().findByFileId(file.id());
                Document replacement = new Document(
                        SOURCE,
                        DocumentType.MARKDOWN,
                        "replacementbody",
                        ContentHash.sha256("replacementbody".getBytes(StandardCharsets.UTF_8)));

                writer.replaceFile(replacement, List.of(chunk(replacement)), updated);

                assertEquals(Optional.of(IDENTITY), reader.files().findChunkingIdentity(file.id()));
                assertEquals(oldChunks, reader.chunks().findByFileId(file.id()));
                assertFalse(reader.connection().getAutoCommit());
                reader.connection().rollback();
                reader.connection().setAutoCommit(true);

                assertEquals(Optional.of(updated), reader.files().findChunkingIdentity(file.id()));
                var newChunk = reader.chunks().findByFileId(file.id()).getFirst();
                assertEquals(replacement.content(), newChunk.chunk().content());
                assertEquals(Optional.of(new ChunkIdGenerator(updated).generate(SOURCE, 0)), newChunk.stableId());
                assertTrue(reader.connection().getAutoCommit());
            }
        }
    }

    @ParameterizedTest
    @ValueSource(longs = {Long.MIN_VALUE, -1, 0})
    void validatesLookupIdsBeforeAccessingClosedStorage(long fileId) throws Exception {
        SqliteFileRepository files;
        try (var storage = SqliteStorage.open(root.resolve("index.db"), root)) {
            files = storage.files();
        }

        assertThrows(IllegalArgumentException.class, () -> files.findChunkingIdentity(fileId));
    }

    private static void assertInvalidReads(SqliteStorage storage, long fileId) {
        assertThrows(SQLException.class, () -> storage.files().findChunkingIdentity(fileId));
        assertThrows(SQLException.class, () -> storage.chunks().findByFileId(fileId));
    }

    private static StoredFile save(SqliteStorage storage, Path path, ChunkingIdentity identity) throws SQLException {
        Document document = document(path);
        return storage.replaceFile(document, List.of(chunk(document)), identity);
    }

    private static Document document(Path path) {
        String content = "documentbody";
        return new Document(
                path, DocumentType.MARKDOWN, content, ContentHash.sha256(content.getBytes(StandardCharsets.UTF_8)));
    }

    private static Chunk chunk(Document document) {
        return new Chunk(
                document.sourcePath(),
                document.type(),
                0,
                document.content(),
                new LineRange(1, 1),
                ChunkMetadata.empty());
    }

    private static void insertProfile(Connection connection, long fileId, ChunkingIdentity identity)
            throws SQLException {
        TokenizerCompatibility tokenizer = identity.tokenizer();
        execute(
                connection,
                """
                INSERT INTO file_chunking_profiles (
                    file_id, extraction_version, chunking_version, tokenizer_algorithm, vocabulary_sha256,
                    vocabulary_size, max_input_tokens, special_token_count, max_tokens, overlap_tokens, fingerprint
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                fileId,
                identity.extractionVersion(),
                identity.chunkingVersion(),
                tokenizer.algorithm(),
                tokenizer.vocabularySha256(),
                tokenizer.vocabularySize(),
                tokenizer.maxInputTokens(),
                tokenizer.specialTokenCount(),
                identity.maxTokens(),
                identity.overlapTokens(),
                identity.fingerprint());
    }
}
