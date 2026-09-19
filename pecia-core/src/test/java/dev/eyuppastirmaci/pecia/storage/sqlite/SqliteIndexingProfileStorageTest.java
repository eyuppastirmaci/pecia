package dev.eyuppastirmaci.pecia.storage.sqlite;

import static dev.eyuppastirmaci.pecia.storage.sqlite.SqliteFtsTestSupport.assertConsistent;
import static dev.eyuppastirmaci.pecia.storage.sqlite.SqliteFtsTestSupport.assertMatches;
import static dev.eyuppastirmaci.pecia.storage.sqlite.SqliteFtsTestSupport.execute;
import static dev.eyuppastirmaci.pecia.storage.sqlite.SqliteFtsTestSupport.rows;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.eyuppastirmaci.pecia.content.Chunk;
import dev.eyuppastirmaci.pecia.content.ChunkMetadata;
import dev.eyuppastirmaci.pecia.content.ContentHash;
import dev.eyuppastirmaci.pecia.content.Document;
import dev.eyuppastirmaci.pecia.content.DocumentType;
import dev.eyuppastirmaci.pecia.content.LineRange;
import dev.eyuppastirmaci.pecia.index.IndexingProfile;
import dev.eyuppastirmaci.pecia.storage.model.StoredChunk;
import dev.eyuppastirmaci.pecia.storage.model.StoredFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SqliteIndexingProfileStorageTest {
    private static final Path SOURCE = Path.of("docs", "İstanbul.md");
    private static final IndexingProfile ORIGINAL = new IndexingProfile("tokenizer:original", 256, 32);
    private static final IndexingProfile UPDATED = new IndexingProfile("tokenizer:updated", 128, 8);

    @TempDir
    Path root;

    @Test
    void profilesSurviveReopeningAndReadOnlyAccessIncludingEmptyFiles() throws Exception {
        Path database = root.resolve("index.db");
        Document document = document(SOURCE, "originalbody");
        Document empty = document(Path.of("empty.md"), "");
        long fileId;
        long emptyId;
        long legacyId;
        try (var storage = SqliteStorage.open(database, root)) {
            fileId = storage.replaceFile(document, List.of(chunk(document, 0)), ORIGINAL)
                    .id();
            emptyId = storage.replaceFile(empty, List.of(), UPDATED).id();
            legacyId = storage.files()
                    .insert(Path.of("legacy.md"), document.type(), document.contentHash())
                    .id();
            assertEquals(Optional.of(ORIGINAL), storage.files().findIndexingProfile(fileId));
            assertEquals(Optional.of(UPDATED), storage.files().findIndexingProfile(emptyId));
            assertTrue(storage.files().findIndexingProfile(legacyId).isEmpty());
            assertTrue(storage.files().findIndexingProfile(Long.MAX_VALUE).isEmpty());
        }

        try (var storage = SqliteStorage.open(database, root)) {
            assertEquals(Optional.of(ORIGINAL), storage.files().findIndexingProfile(fileId));
            assertEquals(Optional.of(UPDATED), storage.files().findIndexingProfile(emptyId));
            assertTrue(storage.chunks().findByFileId(emptyId).isEmpty());
        }

        try (var storage = SqliteStorage.openReadOnly(database, root)) {
            assertEquals(Optional.of(ORIGINAL), storage.files().findIndexingProfile(fileId));
            assertEquals(Optional.of(UPDATED), storage.files().findIndexingProfile(emptyId));
            assertTrue(storage.files().findIndexingProfile(legacyId).isEmpty());
            assertTrue(storage.files().findIndexingProfile(Long.MAX_VALUE).isEmpty());
            assertEquals(
                    List.of(chunk(document, 0)),
                    storage.chunks().findByFileId(fileId).stream()
                            .map(StoredChunk::chunk)
                            .toList());
        }
    }

    @Test
    void replacementPreservesFileIdAndChangesOnlyThatFilesProfile() throws Exception {
        try (var storage = SqliteStorage.open(root.resolve("index.db"), root)) {
            Document document = document(SOURCE, "originalbody");
            Document other = document(Path.of("other.md"), "retainedbody");
            var file = storage.replaceFile(document, List.of(chunk(document, 0)), ORIGINAL);
            var retained = storage.replaceFile(other, List.of(chunk(other, 0)), ORIGINAL);
            var retainedChunks = storage.chunks().findByFileId(retained.id());
            Document replacement = document(SOURCE, "replacementbody");

            var saved =
                    storage.replaceFile(replacement, List.of(chunk(replacement, 0), chunk(replacement, 1)), UPDATED);

            assertEquals(file.id(), saved.id());
            assertEquals(replacement.contentHash(), saved.contentHash());
            assertEquals(Optional.of(UPDATED), storage.files().findIndexingProfile(file.id()));
            assertEquals(Optional.of(ORIGINAL), storage.files().findIndexingProfile(retained.id()));
            assertEquals(
                    retained, storage.files().findByPath(other.sourcePath()).orElseThrow());
            assertEquals(retainedChunks, storage.chunks().findByFileId(retained.id()));
            assertMatches(storage.connection(), "originalbody");
            assertMatches(
                    storage.connection(),
                    "retainedbody",
                    retainedChunks.getFirst().id());
            assertConsistent(storage.connection());

            assertEquals(
                    file.id(),
                    storage.replaceFile(document(SOURCE, ""), List.of(), ORIGINAL)
                            .id());
            assertEquals(Optional.of(ORIGINAL), storage.files().findIndexingProfile(file.id()));
            assertTrue(storage.chunks().findByFileId(file.id()).isEmpty());
            assertMatches(storage.connection(), "replacementbody");
        }
    }

    @Test
    void legacyReplacementClearsProfileEvenForAnEmptyFile() throws Exception {
        try (var storage = SqliteStorage.open(root.resolve("index.db"), root)) {
            Document document = document(SOURCE, "originalbody");
            var file = storage.replaceFile(document, List.of(chunk(document, 0)), ORIGINAL);
            assertEquals(
                    file.id(),
                    storage.replaceFile(document, List.of(chunk(document, 0))).id());
            assertTrue(storage.files().findIndexingProfile(file.id()).isEmpty());

            Document empty = document(SOURCE, "");
            storage.replaceFile(empty, List.of(), UPDATED);
            assertEquals(Optional.of(UPDATED), storage.files().findIndexingProfile(file.id()));
            storage.replaceFile(empty, List.of());
            assertTrue(storage.files().findIndexingProfile(file.id()).isEmpty());
            assertConsistent(storage.connection());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"file", "insertChunk", "deleteChunks"})
    void repositoryWritesInvalidateOnlyTheAffectedFilesProfile(String operation) throws Exception {
        try (var storage = SqliteStorage.open(root.resolve("index.db"), root)) {
            Document document = document(SOURCE, "originalbody");
            Document other = document(Path.of("other.md"), "retainedbody");
            var file = storage.replaceFile(document, List.of(chunk(document, 0)), ORIGINAL);
            var retained = storage.replaceFile(other, List.of(chunk(other, 0)), UPDATED);

            switch (operation) {
                case "file" -> assertTrue(storage.files().update(file));
                case "insertChunk" -> storage.chunks().insert(file.id(), chunk(document, 1));
                case "deleteChunks" -> assertEquals(1, storage.chunks().deleteByFileId(file.id()));
                default -> throw new AssertionError(operation);
            }

            assertTrue(storage.files().findIndexingProfile(file.id()).isEmpty());
            assertEquals(Optional.of(UPDATED), storage.files().findIndexingProfile(retained.id()));
            assertConsistent(storage.connection());
        }
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "UPDATE chunks SET content = 'modifiedbody' WHERE id = ?",
                "INSERT INTO chunk_headings VALUES (?, 1, 'Addedheading')",
                "UPDATE chunk_headings SET heading = 'Changedheading' WHERE chunk_id = ?",
                "DELETE FROM chunk_headings WHERE chunk_id = ?",
                "INSERT INTO chunk_attributes VALUES (?, 'extra', 'value')",
                "UPDATE chunk_attributes SET value = 'changed' WHERE chunk_id = ?",
                "DELETE FROM chunk_attributes WHERE chunk_id = ?"
            })
    void directChunkAndMetadataChangesInvalidateOnlyTheirFilesProfile(String sql) throws Exception {
        try (var storage = SqliteStorage.open(root.resolve("index.db"), root)) {
            Document document = document(SOURCE, "originalbody");
            Document other = document(Path.of("other.md"), "retainedbody");
            var file = storage.replaceFile(document, List.of(chunk(document, 0)), ORIGINAL);
            var retained = storage.replaceFile(other, List.of(chunk(other, 0)), UPDATED);
            long chunkId = storage.chunks().findByFileId(file.id()).getFirst().id();

            execute(storage.connection(), sql, chunkId);

            assertTrue(storage.files().findIndexingProfile(file.id()).isEmpty());
            assertEquals(Optional.of(UPDATED), storage.files().findIndexingProfile(retained.id()));
            assertConsistent(storage.connection());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"chunks", "chunk_headings", "chunk_attributes"})
    void movingContentBetweenFilesInvalidatesBothProfiles(String table) throws Exception {
        try (var storage = SqliteStorage.open(root.resolve("index.db"), root)) {
            Document document = document(SOURCE, "originalbody");
            Document other = document(Path.of("other.md"), "retainedbody");
            var file = storage.replaceFile(document, List.of(chunk(document, 0)), ORIGINAL);
            var retained = storage.replaceFile(other, List.of(chunk(other, 0)), UPDATED);
            long sourceChunk =
                    storage.chunks().findByFileId(file.id()).getFirst().id();
            long targetChunk =
                    storage.chunks().findByFileId(retained.id()).getFirst().id();

            switch (table) {
                case "chunks" ->
                    execute(
                            storage.connection(),
                            "UPDATE chunks SET file_id = ?, chunk_index = 1 WHERE id = ?",
                            retained.id(),
                            sourceChunk);
                case "chunk_headings" ->
                    execute(
                            storage.connection(),
                            "UPDATE chunk_headings SET chunk_id = ?, position = 1 WHERE chunk_id = ?",
                            targetChunk,
                            sourceChunk);
                case "chunk_attributes" ->
                    execute(
                            storage.connection(),
                            "UPDATE chunk_attributes SET chunk_id = ?, name = 'moved' WHERE chunk_id = ?",
                            targetChunk,
                            sourceChunk);
                default -> throw new AssertionError(table);
            }

            assertTrue(storage.files().findIndexingProfile(file.id()).isEmpty());
            assertTrue(storage.files().findIndexingProfile(retained.id()).isEmpty());
            assertConsistent(storage.connection());
        }
    }

    @Test
    void fileDeletionCascadesToItsProfileAndLeavesOtherProfiles() throws Exception {
        try (var storage = SqliteStorage.open(root.resolve("index.db"), root)) {
            Document document = document(SOURCE, "originalbody");
            Document other = document(Path.of("other.md"), "retainedbody");
            var file = storage.replaceFile(document, List.of(chunk(document, 0)), ORIGINAL);
            var retained = storage.replaceFile(other, List.of(chunk(other, 0)), UPDATED);

            assertTrue(storage.files().delete(file.id()));

            assertTrue(storage.files().findIndexingProfile(file.id()).isEmpty());
            assertEquals(Optional.of(UPDATED), storage.files().findIndexingProfile(retained.id()));
            try (var statement = storage.connection().createStatement();
                    var result = statement.executeQuery("SELECT file_id FROM file_indexing_profiles")) {
                assertTrue(result.next());
                assertEquals(retained.id(), result.getLong(1));
                assertFalse(result.next());
            }
            assertConsistent(storage.connection());
        }
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "BEFORE INSERT ON file_indexing_profiles",
                "BEFORE INSERT ON chunks WHEN NEW.chunk_index = 1",
                "BEFORE INSERT ON chunk_attributes"
            })
    void failedReplacementRestoresTheExactManifestChunksSearchAndProfile(String failurePoint) throws Exception {
        Path database = root.resolve("index.db");
        Snapshot before;
        try (var storage = SqliteStorage.open(database, root)) {
            Document document = document(SOURCE, "originalbody");
            storage.replaceFile(document, List.of(chunk(document, 0)), ORIGINAL);
            before = snapshot(storage, SOURCE);
            injectFailure(storage.connection(), failurePoint);
            Document replacement = document(SOURCE, "replacementbody");

            assertThrows(
                    SQLException.class,
                    () -> storage.replaceFile(
                            replacement, List.of(chunk(replacement, 0), chunk(replacement, 1)), UPDATED));

            assertSnapshot(storage, before);
            assertMatches(
                    storage.connection(),
                    "originalbody",
                    before.chunks().getFirst().id());
            assertMatches(storage.connection(), "replacementbody");
            execute(storage.connection(), "DROP TRIGGER injected_profile_failure");
        }
        try (var storage = SqliteStorage.open(database, root)) {
            assertSnapshot(storage, before);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"BEFORE INSERT ON file_indexing_profiles", "BEFORE INSERT ON chunk_attributes"})
    void failedFirstInsertionLeavesNoManifestChunksMetadataSearchOrProfile(String failurePoint) throws Exception {
        try (var storage = SqliteStorage.open(root.resolve("index.db"), root)) {
            Document document = document(SOURCE, "originalbody");
            injectFailure(storage.connection(), failurePoint);

            assertThrows(
                    SQLException.class, () -> storage.replaceFile(document, List.of(chunk(document, 0)), ORIGINAL));

            for (String table : List.of(
                    "files", "chunks", "chunk_headings", "chunk_attributes", "chunks_fts", "file_indexing_profiles")) {
                try (var statement = storage.connection().createStatement();
                        var result = statement.executeQuery("SELECT count(*) FROM " + table)) {
                    assertTrue(result.next());
                    assertEquals(0, result.getInt(1), table);
                }
            }
            assertMatches(storage.connection(), "originalbody OR heading");
            assertConsistent(storage.connection());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void callerTransactionControlsWhetherReplacementAndProfileBecomeVisible(boolean commit) throws Exception {
        Path database = root.resolve("index.db");
        try (var storage = SqliteStorage.open(database, root);
                var reader = SqliteStorage.openReadOnly(database, root)) {
            Document document = document(SOURCE, "originalbody");
            Document replacement = document(SOURCE, "replacementbody");
            var file = storage.replaceFile(document, List.of(chunk(document, 0)), ORIGINAL);
            var before = snapshot(storage, SOURCE);
            storage.connection().setAutoCommit(false);

            storage.replaceFile(replacement, List.of(chunk(replacement, 0)), UPDATED);

            assertFalse(storage.connection().getAutoCommit());
            assertEquals(Optional.of(UPDATED), storage.files().findIndexingProfile(file.id()));
            assertEquals(before, snapshot(reader, SOURCE));
            if (commit) {
                storage.connection().commit();
            } else {
                storage.connection().rollback();
            }
            storage.connection().setAutoCommit(true);
            assertEquals(
                    Optional.of(commit ? UPDATED : ORIGINAL), reader.files().findIndexingProfile(file.id()));
            if (commit) {
                assertEquals(
                        replacement.contentHash(),
                        reader.files().findByPath(SOURCE).orElseThrow().contentHash());
                long chunkId =
                        reader.chunks().findByFileId(file.id()).getFirst().id();
                assertMatches(reader.connection(), "replacementbody", chunkId);
                assertMatches(reader.connection(), "originalbody");
            } else {
                assertSnapshot(storage, before);
                assertEquals(before, snapshot(reader, SOURCE));
            }
        }
    }

    @Test
    void profileWriteFailurePreservesEarlierWritesInTheOuterTransaction() throws Exception {
        Path database = root.resolve("index.db");
        Snapshot original;
        StoredFile retained;
        try (var storage = SqliteStorage.open(database, root)) {
            Document document = document(SOURCE, "originalbody");
            storage.replaceFile(document, List.of(chunk(document, 0)), ORIGINAL);
            injectFailure(
                    storage.connection(),
                    "BEFORE INSERT ON file_indexing_profiles WHEN NEW.tokenizer_key = 'tokenizer:updated'");
            storage.connection().setAutoCommit(false);
            Document other = document(Path.of("other.md"), "retainedbody");
            retained = storage.replaceFile(other, List.of(chunk(other, 0)), ORIGINAL);
            original = snapshot(storage, SOURCE);
            Document replacement = document(SOURCE, "replacementbody");

            assertThrows(
                    SQLException.class,
                    () -> storage.replaceFile(replacement, List.of(chunk(replacement, 0)), UPDATED));

            assertFalse(storage.connection().getAutoCommit());
            assertSnapshot(storage, original);
            assertEquals(
                    retained, storage.files().findByPath(other.sourcePath()).orElseThrow());
            assertEquals(Optional.of(ORIGINAL), storage.files().findIndexingProfile(retained.id()));
            execute(storage.connection(), "DROP TRIGGER injected_profile_failure");
            storage.connection().commit();
            storage.connection().setAutoCommit(true);
        }
        try (var storage = SqliteStorage.open(database, root)) {
            assertSnapshot(storage, original);
            assertEquals(
                    retained, storage.files().findByPath(retained.sourcePath()).orElseThrow());
            assertEquals(Optional.of(ORIGINAL), storage.files().findIndexingProfile(retained.id()));
            assertMatches(
                    storage.connection(),
                    "retainedbody",
                    storage.chunks().findByFileId(retained.id()).getFirst().id());
        }
    }

    @Test
    void validatesProfileAndEveryChunkBeforeAccessingStorage() throws Exception {
        SqliteStorage closed;
        Document document = document(SOURCE, "originalbody");
        Document replacement = document(SOURCE, "replacementbody");
        List<Chunk> valid = List.of(chunk(replacement, 0));
        try (var storage = SqliteStorage.open(root.resolve("index.db"), root)) {
            closed = storage;
            storage.replaceFile(document, List.of(chunk(document, 0)), ORIGINAL);
            var before = snapshot(storage, SOURCE);

            assertThrows(
                    NullPointerException.class, () -> storage.replaceFile(replacement, valid, (IndexingProfile) null));
            assertThrows(NullPointerException.class, () -> storage.replaceFile(null, valid, UPDATED));
            assertThrows(NullPointerException.class, () -> storage.replaceFile(replacement, null, UPDATED));
            assertThrows(
                    NullPointerException.class,
                    () -> storage.replaceFile(replacement, Arrays.asList(chunk(replacement, 0), null), UPDATED));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> storage.replaceFile(
                            replacement, List.of(chunk(replacement, 0), chunk(replacement, 0)), UPDATED));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> storage.replaceFile(
                            replacement, List.of(chunk(document(Path.of("wrong.md"), "wrongbody"), 0)), UPDATED));
            assertSnapshot(storage, before);
        }

        assertThrows(NullPointerException.class, () -> closed.replaceFile(replacement, valid, (IndexingProfile) null));
        assertThrows(
                IllegalArgumentException.class,
                () -> closed.replaceFile(replacement, List.of(chunk(replacement, 1)), UPDATED));
    }

    @ParameterizedTest
    @ValueSource(longs = {Long.MIN_VALUE, -1, 0})
    void invalidProfileLookupIdsFailBeforeAccessingTheConnection(long fileId) throws Exception {
        SqliteFileRepository repository;
        try (var storage = SqliteStorage.open(root.resolve("index.db"), root)) {
            repository = storage.files();
            assertThrows(IllegalArgumentException.class, () -> repository.findIndexingProfile(fileId));
        }
        assertThrows(IllegalArgumentException.class, () -> repository.findIndexingProfile(fileId));
        assertThrows(SQLException.class, () -> repository.findIndexingProfile(1));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "tokenizer_key = ''",
                "tokenizer_key = '   '",
                "tokenizer_key = char(9)",
                "tokenizer_key = X'616263'",
                "max_tokens = 0",
                "max_tokens = 128.5",
                "overlap_tokens = 8.5",
                "overlap_tokens = 'invalid'",
                "overlap_tokens = -1",
                "overlap_tokens = max_tokens",
                "max_tokens = 4294967552",
                "overlap_tokens = 4294967328"
            })
    void invalidPersistedProfilesProduceSqlExceptions(String assignment) throws Exception {
        try (var storage = SqliteStorage.open(root.resolve("index.db"), root)) {
            Document document = document(SOURCE, "originalbody");
            var file = storage.replaceFile(document, List.of(chunk(document, 0)), ORIGINAL);
            execute(storage.connection(), "PRAGMA ignore_check_constraints = ON");
            execute(
                    storage.connection(),
                    "UPDATE file_indexing_profiles SET " + assignment + " WHERE file_id = ?",
                    file.id());

            assertThrows(SQLException.class, () -> storage.files().findIndexingProfile(file.id()));
        }
    }

    private static Snapshot snapshot(SqliteStorage storage, Path path) throws SQLException {
        var file = storage.files().findByPath(path).orElseThrow();
        return new Snapshot(
                file,
                storage.chunks().findByFileId(file.id()),
                rows(storage.connection()),
                storage.files().findIndexingProfile(file.id()).orElseThrow());
    }

    private static void assertSnapshot(SqliteStorage storage, Snapshot expected) throws SQLException {
        assertEquals(expected, snapshot(storage, expected.file().sourcePath()));
        assertConsistent(storage.connection());
    }

    private static void injectFailure(Connection connection, String failurePoint) throws SQLException {
        execute(
                connection,
                "CREATE TRIGGER injected_profile_failure " + failurePoint
                        + " BEGIN SELECT RAISE(ABORT, 'injected profile storage failure'); END;");
    }

    private static Document document(Path path, String content) {
        return new Document(
                path, DocumentType.MARKDOWN, content, ContentHash.sha256(content.getBytes(StandardCharsets.UTF_8)));
    }

    private static Chunk chunk(Document document, int index) {
        return new Chunk(
                document.sourcePath(),
                document.type(),
                index,
                document.content(),
                new LineRange(1, 2),
                new ChunkMetadata(List.of("Heading"), Map.of("startOffset", "0")));
    }

    private record Snapshot(
            StoredFile file,
            List<StoredChunk> chunks,
            List<SqliteFtsTestSupport.FtsRow> searchRows,
            IndexingProfile profile) {}
}
