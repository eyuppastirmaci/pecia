package dev.eyuppastirmaci.pecia.storage.sqlite;

import static dev.eyuppastirmaci.pecia.storage.sqlite.SqliteFtsTestSupport.assertConsistent;
import static dev.eyuppastirmaci.pecia.storage.sqlite.SqliteFtsTestSupport.assertMatches;
import static dev.eyuppastirmaci.pecia.storage.sqlite.SqliteFtsTestSupport.execute;
import static dev.eyuppastirmaci.pecia.storage.sqlite.SqliteFtsTestSupport.rows;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.eyuppastirmaci.pecia.chunking.ChunkIdGenerator;
import dev.eyuppastirmaci.pecia.chunking.ChunkingIdentity;
import dev.eyuppastirmaci.pecia.content.Chunk;
import dev.eyuppastirmaci.pecia.content.ChunkId;
import dev.eyuppastirmaci.pecia.content.ChunkMetadata;
import dev.eyuppastirmaci.pecia.content.ContentHash;
import dev.eyuppastirmaci.pecia.content.Document;
import dev.eyuppastirmaci.pecia.content.DocumentType;
import dev.eyuppastirmaci.pecia.content.LineRange;
import dev.eyuppastirmaci.pecia.content.SourceLocation;
import dev.eyuppastirmaci.pecia.index.IndexingProfile;
import dev.eyuppastirmaci.pecia.storage.model.StoredChunk;
import dev.eyuppastirmaci.pecia.storage.model.StoredFile;
import dev.eyuppastirmaci.pecia.tokenization.TokenizerCompatibility;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

class SqliteChunkIdentityStorageTest {
    private static final Path SOURCE = Path.of("docs", "İstanbul.md");
    private static final TokenizerCompatibility TOKENIZER =
            new TokenizerCompatibility("wordpiece", "a".repeat(64), 100, 512, 2);
    private static final ChunkingIdentity ORIGINAL = new ChunkingIdentity("extract-v1", "chunk-v1", TOKENIZER, 256, 32);
    private static final ChunkingIdentity UPDATED = new ChunkingIdentity("extract-v2", "chunk-v2", TOKENIZER, 128, 8);
    private static final IndexingProfile LEGACY = new IndexingProfile("legacy:tokenizer", 256, 32);
    private static final List<String> SNAPSHOT_TABLES = List.of(
            "files",
            "chunks",
            "chunk_headings",
            "chunk_attributes",
            "file_indexing_profiles",
            "file_chunking_profiles",
            "chunk_identities");

    @TempDir
    Path root;

    @Test
    void completeIdentityAndEveryChunkIdSurviveReopeningAndReadOnlyAccess() throws Exception {
        Path database = root.resolve("index.db");
        Document document = document(SOURCE, "originalbody");
        Document empty = document(Path.of("empty.md"), "");
        long fileId;
        long emptyId;
        long legacyId;
        Snapshot expected;
        try (var storage = SqliteStorage.open(database, root)) {
            fileId = storage.replaceFile(document, chunks(document), ORIGINAL).id();
            emptyId = storage.replaceFile(empty, List.of(), UPDATED).id();
            Document legacy = document(Path.of("legacy.md"), "legacybody");
            legacyId = storage.replaceFile(legacy, chunks(legacy), LEGACY).id();

            assertKnownIdentity(storage, fileId, document, ORIGINAL);
            assertEquals(Optional.of(UPDATED), storage.files().findChunkingIdentity(emptyId));
            assertTrue(storage.chunks().findByFileId(emptyId).isEmpty());
            assertUnknownIdentity(storage, legacyId);
            assertTrue(storage.files().findChunkingIdentity(Long.MAX_VALUE).isEmpty());
            assertTrue(storage.chunks().findByFileId(Long.MAX_VALUE).isEmpty());
            assertTrue(storage.files().findIndexingProfile(fileId).isEmpty());
            expected = snapshot(storage);
        }

        try (var storage = SqliteStorage.open(database, root)) {
            assertKnownIdentity(storage, fileId, document, ORIGINAL);
            assertEquals(expected, snapshot(storage));
        }

        byte[] beforeReadOnly = Files.readAllBytes(database);
        try (var storage = SqliteStorage.openReadOnly(database, root)) {
            assertKnownIdentity(storage, fileId, document, ORIGINAL);
            assertEquals(Optional.of(UPDATED), storage.files().findChunkingIdentity(emptyId));
            assertUnknownIdentity(storage, legacyId);
            assertEquals(expected, snapshot(storage));
        }
        assertArrayEquals(beforeReadOnly, Files.readAllBytes(database));
    }

    @Test
    void replacementKeepsStableIdsAcrossContentChangesAndChangesThemWithTheIdentity() throws Exception {
        try (var storage = SqliteStorage.open(root.resolve("index.db"), root)) {
            Document document = document(SOURCE, "originalbody");
            StoredFile file = storage.replaceFile(document, chunks(document), ORIGINAL);
            List<StoredChunk> initial = storage.chunks().findByFileId(file.id());
            Document other = document(Path.of("other.md"), "retainedbody");
            StoredFile retained = storage.replaceFile(other, chunks(other), UPDATED);
            List<StoredChunk> retainedChunks = storage.chunks().findByFileId(retained.id());
            Document replacement = document(SOURCE, "replacementbody");

            StoredFile saved = storage.replaceFile(replacement, chunks(replacement), ORIGINAL);

            List<StoredChunk> replaced = storage.chunks().findByFileId(file.id());
            assertEquals(file.id(), saved.id());
            assertEquals(replacement.contentHash(), saved.contentHash());
            assertEquals(stableIds(initial), stableIds(replaced));
            assertNotEquals(
                    initial.stream().map(StoredChunk::id).toList(),
                    replaced.stream().map(StoredChunk::id).toList());
            assertKnownIdentity(storage, file.id(), replacement, ORIGINAL);
            assertMatches(storage.connection(), "originalbody");
            assertMatches(
                    storage.connection(),
                    "replacementbody",
                    replaced.get(0).id(),
                    replaced.get(1).id());

            storage.replaceFile(replacement, chunks(replacement), UPDATED);

            assertNotEquals(stableIds(initial), stableIds(storage.chunks().findByFileId(file.id())));
            assertKnownIdentity(storage, file.id(), replacement, UPDATED);
            assertEquals(
                    retained, storage.files().findByPath(other.sourcePath()).orElseThrow());
            assertEquals(retainedChunks, storage.chunks().findByFileId(retained.id()));
            assertKnownIdentity(storage, retained.id(), other, UPDATED);
            assertConsistent(storage.connection());
        }
    }

    @Test
    void emptyReplacementRetainsIdentityWithoutAnyChunkIds() throws Exception {
        try (var storage = SqliteStorage.open(root.resolve("index.db"), root)) {
            Document document = document(SOURCE, "originalbody");
            StoredFile file = storage.replaceFile(document, chunks(document), ORIGINAL);
            Document empty = document(SOURCE, "");

            StoredFile saved = storage.replaceFile(empty, List.of(), UPDATED);

            assertEquals(file.id(), saved.id());
            assertEquals(empty.contentHash(), saved.contentHash());
            assertEquals(Optional.of(UPDATED), storage.files().findChunkingIdentity(file.id()));
            assertTrue(storage.chunks().findByFileId(file.id()).isEmpty());
            assertEquals(0, count(storage.connection(), "chunk_identities"));
            assertMatches(storage.connection(), "originalbody OR heading");
            assertConsistent(storage.connection());
        }
    }

    @ParameterizedTest
    @CsvSource({"false,false", "false,true", "true,false", "true,true"})
    void plainAndLegacyReplacementsClearIdentityIncludingEmptyFiles(boolean legacy, boolean empty) throws Exception {
        try (var storage = SqliteStorage.open(root.resolve("index.db"), root)) {
            Document document = document(SOURCE, empty ? "" : "originalbody");
            List<Chunk> replacement = empty ? List.of() : chunks(document);
            StoredFile file = storage.replaceFile(document, replacement, ORIGINAL);

            StoredFile saved = legacy
                    ? storage.replaceFile(document, replacement, LEGACY)
                    : storage.replaceFile(document, replacement);

            assertEquals(file.id(), saved.id());
            assertUnknownIdentity(storage, file.id());
            assertEquals(
                    legacy ? Optional.of(LEGACY) : Optional.empty(),
                    storage.files().findIndexingProfile(file.id()));
            assertEquals(0, count(storage.connection(), "chunk_identities"));
            assertConsistent(storage.connection());
        }
    }

    @Test
    void fullIdentityReplacementUpgradesLegacyDataAndClearsTheLegacyProfile() throws Exception {
        try (var storage = SqliteStorage.open(root.resolve("index.db"), root)) {
            Document document = document(SOURCE, "originalbody");
            StoredFile file = storage.replaceFile(document, chunks(document), LEGACY);
            assertUnknownIdentity(storage, file.id());

            assertEquals(
                    file.id(),
                    storage.replaceFile(document, chunks(document), ORIGINAL).id());

            assertKnownIdentity(storage, file.id(), document, ORIGINAL);
            assertTrue(storage.files().findIndexingProfile(file.id()).isEmpty());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"file", "insertChunk", "deleteChunks"})
    void repositoryWritesClearOnlyTheAffectedFilesIdentity(String operation) throws Exception {
        try (var storage = SqliteStorage.open(root.resolve("index.db"), root)) {
            Document document = document(SOURCE, "originalbody");
            StoredFile file = storage.replaceFile(document, chunks(document), ORIGINAL);
            Document other = document(Path.of("other.md"), "retainedbody");
            StoredFile retained = storage.replaceFile(other, chunks(other), UPDATED);
            List<StoredChunk> retainedChunks = storage.chunks().findByFileId(retained.id());

            switch (operation) {
                case "file" -> assertTrue(storage.files().update(file));
                case "insertChunk" -> {
                    StoredChunk inserted = storage.chunks().insert(file.id(), chunk(document, 2));
                    assertTrue(inserted.stableId().isEmpty());
                }
                case "deleteChunks" -> assertEquals(2, storage.chunks().deleteByFileId(file.id()));
                default -> throw new AssertionError(operation);
            }

            assertUnknownIdentity(storage, file.id());
            assertKnownIdentity(storage, retained.id(), other, UPDATED);
            assertEquals(retainedChunks, storage.chunks().findByFileId(retained.id()));
            assertEquals(2, count(storage.connection(), "chunk_identities"));
            assertConsistent(storage.connection());
        }
    }

    @Test
    void deletingChunksOfAnEmptyFileStillInvalidatesItsKnownIdentity() throws Exception {
        try (var storage = SqliteStorage.open(root.resolve("index.db"), root)) {
            StoredFile file = storage.replaceFile(document(SOURCE, ""), List.of(), ORIGINAL);
            Document other = document(Path.of("other.md"), "retainedbody");
            StoredFile retained = storage.replaceFile(other, chunks(other), UPDATED);

            assertEquals(0, storage.chunks().deleteByFileId(file.id()));

            assertUnknownIdentity(storage, file.id());
            assertKnownIdentity(storage, retained.id(), other, UPDATED);
        }
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "UPDATE chunks SET content = 'modifiedbody' WHERE id = ?",
                "UPDATE chunk_headings SET heading = 'Changedheading' WHERE chunk_id = ?",
                "UPDATE chunk_attributes SET value = 'changed' WHERE chunk_id = ?"
            })
    void directContentAndMetadataChangesMakeAllAffectedChunkIdsUnknown(String sql) throws Exception {
        try (var storage = SqliteStorage.open(root.resolve("index.db"), root)) {
            Document document = document(SOURCE, "originalbody");
            StoredFile file = storage.replaceFile(document, chunks(document), ORIGINAL);
            Document other = document(Path.of("other.md"), "retainedbody");
            StoredFile retained = storage.replaceFile(other, chunks(other), UPDATED);
            long firstChunk =
                    storage.chunks().findByFileId(file.id()).getFirst().id();

            execute(storage.connection(), sql, firstChunk);

            assertUnknownIdentity(storage, file.id());
            assertKnownIdentity(storage, retained.id(), other, UPDATED);
            assertEquals(2, count(storage.connection(), "chunk_identities"));
            assertConsistent(storage.connection());
        }
    }

    @Test
    void deletingAFileCascadesItsIdentityWithoutChangingOtherFiles() throws Exception {
        try (var storage = SqliteStorage.open(root.resolve("index.db"), root)) {
            Document document = document(SOURCE, "originalbody");
            StoredFile file = storage.replaceFile(document, chunks(document), ORIGINAL);
            Document other = document(Path.of("other.md"), "retainedbody");
            StoredFile retained = storage.replaceFile(other, chunks(other), UPDATED);

            assertTrue(storage.files().delete(file.id()));

            assertTrue(storage.files().findByPath(SOURCE).isEmpty());
            assertUnknownIdentity(storage, file.id());
            assertTrue(storage.chunks().findByFileId(file.id()).isEmpty());
            assertKnownIdentity(storage, retained.id(), other, UPDATED);
            assertEquals(1, count(storage.connection(), "file_chunking_profiles"));
            assertEquals(2, count(storage.connection(), "chunk_identities"));
            assertConsistent(storage.connection());
        }
    }

    @ParameterizedTest
    @MethodSource("replacementFailures")
    void failedReplacementRestoresManifestChunksMetadataSearchAndBothProfileFormats(String failurePoint, String action)
            throws Exception {
        Path database = root.resolve("index.db");
        Snapshot before;
        try (var storage = SqliteStorage.open(database, root)) {
            Document original = document(SOURCE, "originalbody");
            storage.replaceFile(original, chunks(original), ORIGINAL);
            Document legacy = document(Path.of("legacy.md"), "legacybody");
            storage.replaceFile(legacy, chunks(legacy), LEGACY);
            before = snapshot(storage);
            injectFailure(storage.connection(), failurePoint, action);
            Document replacement = document(SOURCE, "replacementbody");

            assertThrows(SQLException.class, () -> storage.replaceFile(replacement, chunks(replacement), UPDATED));

            assertEquals(before, snapshot(storage));
            assertMatches(storage.connection(), "replacementbody");
            assertConsistent(storage.connection());
            execute(storage.connection(), "DROP TRIGGER injected_identity_failure");
        }
        try (var storage = SqliteStorage.open(database, root)) {
            assertEquals(before, snapshot(storage));
        }
    }

    @ParameterizedTest
    @MethodSource("replacementFailures")
    void failedFirstInsertionLeavesNoManifestChunksMetadataSearchOrIdentity(String failurePoint, String action)
            throws Exception {
        try (var storage = SqliteStorage.open(root.resolve("index.db"), root)) {
            Snapshot before = snapshot(storage);
            injectFailure(storage.connection(), failurePoint, action);
            Document document = document(SOURCE, "originalbody");

            assertThrows(SQLException.class, () -> storage.replaceFile(document, chunks(document), ORIGINAL));

            assertEquals(before, snapshot(storage));
            assertMatches(storage.connection(), "originalbody OR heading");
            assertConsistent(storage.connection());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void callerTransactionControlsVisibilityAndCommitOfIdentityAndChunks(boolean commit) throws Exception {
        Path database = root.resolve("index.db");
        try (var storage = SqliteStorage.open(database, root);
                var reader = SqliteStorage.openReadOnly(database, root)) {
            Document document = document(SOURCE, "originalbody");
            StoredFile file = storage.replaceFile(document, chunks(document), ORIGINAL);
            Snapshot before = snapshot(storage);
            storage.connection().setAutoCommit(false);
            Document replacement = document(SOURCE, "replacementbody");

            storage.replaceFile(replacement, chunks(replacement), UPDATED);

            assertFalse(storage.connection().getAutoCommit());
            assertKnownIdentity(storage, file.id(), replacement, UPDATED);
            Snapshot replaced = snapshot(storage);
            assertEquals(before, snapshot(reader));
            if (commit) {
                storage.connection().commit();
            } else {
                storage.connection().rollback();
            }
            storage.connection().setAutoCommit(true);
            assertEquals(commit ? replaced : before, snapshot(reader));
            assertConsistent(storage.connection());
        }
    }

    @ParameterizedTest
    @CsvSource({
        "chunk_identities,ABORT",
        "chunk_identities,IGNORE",
        "file_chunking_profiles,ABORT",
        "file_chunking_profiles,IGNORE"
    })
    void failedIdentityWritePreservesEarlierWritesInsideTheCallerTransaction(String table, String action)
            throws Exception {
        Path database = root.resolve("index.db");
        Snapshot expected;
        try (var storage = SqliteStorage.open(database, root)) {
            Document document = document(SOURCE, "originalbody");
            storage.replaceFile(document, chunks(document), ORIGINAL);
            storage.connection().setAutoCommit(false);
            Document earlier = document(Path.of("earlier.md"), "earlierbody");
            storage.replaceFile(earlier, chunks(earlier), ORIGINAL);
            expected = snapshot(storage);
            injectFailure(storage.connection(), "BEFORE INSERT ON " + table, action);
            Document replacement = document(SOURCE, "replacementbody");

            assertThrows(SQLException.class, () -> storage.replaceFile(replacement, chunks(replacement), UPDATED));

            assertFalse(storage.connection().getAutoCommit());
            assertEquals(expected, snapshot(storage));
            assertConsistent(storage.connection());
            execute(storage.connection(), "DROP TRIGGER injected_identity_failure");
            storage.connection().commit();
            storage.connection().setAutoCommit(true);
        }
        try (var storage = SqliteStorage.open(database, root)) {
            assertEquals(expected, snapshot(storage));
            assertMatches(storage.connection(), "replacementbody");
        }
    }

    @Test
    void validatesIdentityAndTheWholeChunkListBeforeAccessingStorage() throws Exception {
        SqliteStorage closed;
        Document document = document(SOURCE, "originalbody");
        try (var storage = SqliteStorage.open(root.resolve("index.db"), root)) {
            closed = storage;
            storage.replaceFile(document, chunks(document), ORIGINAL);
            Snapshot before = snapshot(storage);

            assertInvalidReplacementInputs(storage, document);

            assertEquals(before, snapshot(storage));
        }
        assertInvalidReplacementInputs(closed, document);
    }

    @ParameterizedTest
    @ValueSource(longs = {Long.MIN_VALUE, -1, 0})
    void invalidIdentityLookupIdsFailBeforeAccessingTheConnection(long fileId) throws Exception {
        SqliteFileRepository repository;
        try (var storage = SqliteStorage.open(root.resolve("index.db"), root)) {
            repository = storage.files();
            assertThrows(IllegalArgumentException.class, () -> repository.findChunkingIdentity(fileId));
        }
        assertThrows(IllegalArgumentException.class, () -> repository.findChunkingIdentity(fileId));
        assertThrows(SQLException.class, () -> repository.findChunkingIdentity(1));
    }

    private static Stream<Arguments> replacementFailures() {
        return Stream.of(
                Arguments.of("BEFORE INSERT ON chunks WHEN NEW.chunk_index = 1", "ABORT"),
                Arguments.of("BEFORE INSERT ON chunk_headings", "ABORT"),
                Arguments.of("BEFORE INSERT ON chunk_attributes", "ABORT"),
                Arguments.of(
                        "BEFORE INSERT ON chunk_identities WHEN (SELECT chunk_index FROM chunks"
                                + " WHERE id = NEW.chunk_id) = 1",
                        "ABORT"),
                Arguments.of("BEFORE INSERT ON file_chunking_profiles", "ABORT"),
                Arguments.of(
                        "BEFORE INSERT ON chunk_identities WHEN (SELECT chunk_index FROM chunks"
                                + " WHERE id = NEW.chunk_id) = 1",
                        "IGNORE"),
                Arguments.of("BEFORE INSERT ON file_chunking_profiles", "IGNORE"));
    }

    private static void assertInvalidReplacementInputs(SqliteStorage storage, Document document) {
        List<Chunk> valid = chunks(document);
        assertThrows(NullPointerException.class, () -> storage.replaceFile(document, valid, (ChunkingIdentity) null));
        assertThrows(NullPointerException.class, () -> storage.replaceFile(null, valid, ORIGINAL));
        assertThrows(NullPointerException.class, () -> storage.replaceFile(document, null, ORIGINAL));
        assertThrows(
                NullPointerException.class,
                () -> storage.replaceFile(document, Arrays.asList(chunk(document, 0), null), ORIGINAL));
        for (List<Chunk> invalid : List.of(
                List.of(chunk(document, 0), chunk(document, 0)),
                List.of(chunk(document, 1), chunk(document, 0)),
                List.of(chunk(document, 0), chunk(document, 2)),
                List.of(chunk(document, 0), chunk(document(Path.of("wrong.md"), "wrongbody"), 1)),
                List.of(new Chunk(
                        document.sourcePath(),
                        DocumentType.PLAIN_TEXT,
                        0,
                        document.content(),
                        new LineRange(1, 1),
                        ChunkMetadata.empty())),
                List.of(new Chunk(
                        document.sourcePath(),
                        document.type(),
                        0,
                        document.content(),
                        new SourceLocation() {},
                        ChunkMetadata.empty())))) {
            assertThrows(IllegalArgumentException.class, () -> storage.replaceFile(document, invalid, ORIGINAL));
        }
    }

    private static void assertKnownIdentity(
            SqliteStorage storage, long fileId, Document document, ChunkingIdentity identity) throws SQLException {
        assertEquals(Optional.of(identity), storage.files().findChunkingIdentity(fileId));
        List<StoredChunk> stored = storage.chunks().findByFileId(fileId);
        assertEquals(chunks(document), stored.stream().map(StoredChunk::chunk).toList());
        ChunkIdGenerator generator = new ChunkIdGenerator(identity);
        assertEquals(chunks(document).stream().map(generator::generate).toList(), stableIds(stored));
        assertNotEquals(stored.get(0).stableId(), stored.get(1).stableId());
    }

    private static void assertUnknownIdentity(SqliteStorage storage, long fileId) throws SQLException {
        assertTrue(storage.files().findChunkingIdentity(fileId).isEmpty());
        assertTrue(storage.chunks().findByFileId(fileId).stream()
                .allMatch(chunk -> chunk.stableId().isEmpty()));
    }

    private static List<ChunkId> stableIds(List<StoredChunk> chunks) {
        return chunks.stream().map(chunk -> chunk.stableId().orElseThrow()).toList();
    }

    private static Snapshot snapshot(SqliteStorage storage) throws SQLException {
        Map<String, List<List<Object>>> tables = new LinkedHashMap<>();
        for (String table : SNAPSHOT_TABLES) {
            List<List<Object>> values = new ArrayList<>();
            try (var statement = storage.connection().createStatement();
                    var result = statement.executeQuery("SELECT * FROM " + table + " ORDER BY 1, 2")) {
                int columns = result.getMetaData().getColumnCount();
                while (result.next()) {
                    List<Object> row = new ArrayList<>();
                    for (int column = 1; column <= columns; column++) {
                        row.add(result.getObject(column));
                    }
                    values.add(List.copyOf(row));
                }
            }
            tables.put(table, List.copyOf(values));
        }
        return new Snapshot(Map.copyOf(tables), rows(storage.connection()));
    }

    private static long count(Connection connection, String table) throws SQLException {
        try (var statement = connection.createStatement();
                var result = statement.executeQuery("SELECT count(*) FROM " + table)) {
            assertTrue(result.next());
            return result.getLong(1);
        }
    }

    private static void injectFailure(Connection connection, String failurePoint, String action) throws SQLException {
        String raise =
                action.equals("IGNORE") ? "RAISE(IGNORE)" : "RAISE(ABORT, 'injected chunk identity storage failure')";
        execute(
                connection,
                "CREATE TRIGGER injected_identity_failure " + failurePoint + " BEGIN SELECT " + raise + "; END;");
    }

    private static Document document(Path path, String content) {
        return new Document(
                path, DocumentType.MARKDOWN, content, ContentHash.sha256(content.getBytes(StandardCharsets.UTF_8)));
    }

    private static List<Chunk> chunks(Document document) {
        return List.of(chunk(document, 0), chunk(document, 1));
    }

    private static Chunk chunk(Document document, int index) {
        return new Chunk(
                document.sourcePath(),
                document.type(),
                index,
                document.content(),
                new LineRange(index * 2 + 1, index * 2 + 2),
                new ChunkMetadata(List.of("Heading", "Section " + index), Map.of("startOffset", "" + index)));
    }

    private record Snapshot(Map<String, List<List<Object>>> tables, List<SqliteFtsTestSupport.FtsRow> searchRows) {}
}
