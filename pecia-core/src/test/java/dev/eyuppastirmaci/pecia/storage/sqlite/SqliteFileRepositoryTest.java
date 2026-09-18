package dev.eyuppastirmaci.pecia.storage.sqlite;

import dev.eyuppastirmaci.pecia.content.Chunk;
import dev.eyuppastirmaci.pecia.content.ChunkMetadata;
import dev.eyuppastirmaci.pecia.content.LineRange;
import dev.eyuppastirmaci.pecia.content.ContentHash;
import dev.eyuppastirmaci.pecia.content.DocumentType;
import dev.eyuppastirmaci.pecia.storage.model.StoredFile;
import dev.eyuppastirmaci.pecia.storage.sqlite.mapper.StoredFileRowMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;

import static dev.eyuppastirmaci.pecia.storage.sqlite.SqliteFtsTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

class SqliteFileRepositoryTest {
    private static final ContentHash HASH = ContentHash.sha256("\uFEFFİstanbul\r\n".getBytes(StandardCharsets.UTF_8));
    @TempDir
    Path root;

    @Test
    void roundTripsUpdatesAndDeletesAcrossReopening() throws Exception {
        Path database = root.resolve("index.db");
        Path source = Path.of("docs", "İstanbul 'quoted';-- 😀.md");
        StoredFile file;

        try (SqliteStorage storage = SqliteStorage.open(database, root)) {
            file = storage.files().insert(source, DocumentType.MARKDOWN, HASH);
            assertTrue(file.id() > 0);
            assertEquals(source, file.sourcePath());
            assertEquals(HASH, file.contentHash());
            assertEquals(DocumentType.MARKDOWN, file.documentType());
        }

        try (SqliteStorage storage = SqliteStorage.open(database, root)) {
            SqliteFileRepository repository = storage.files();
            assertEquals(file, repository.findByPath(source).orElseThrow());
            StoredFile updated = new StoredFile(file.id(), Path.of("new.txt"), DocumentType.PLAIN_TEXT,
                    ContentHash.sha256(new byte[0]));
            assertTrue(repository.update(updated));
            assertTrue(repository.findByPath(source).isEmpty());
            assertEquals(List.of(updated), repository.findAll());
            assertThrows(UnsupportedOperationException.class, () -> repository.findAll().clear());
            assertTrue(repository.delete(file.id()));
            assertFalse(repository.delete(file.id()));
            assertFalse(repository.update(updated));
            assertTrue(repository.findAll().isEmpty());
        }
    }

    @Test
    void preservesExistingRowsOnDuplicateInsertAndUpdate() throws Exception {
        Path database = root.resolve("index.db");
        long firstId;
        long secondId;
        try (SqliteStorage storage = SqliteStorage.open(database, root)) {
            var repository = storage.files();
            StoredFile first = repository.insert(Path.of("a.md"), DocumentType.MARKDOWN, HASH);
            StoredFile second = repository.insert(Path.of("b.md"), DocumentType.MARKDOWN, HASH);
            firstId = storage.chunks().insert(first.id(), chunk(first, 0, "firstbody", "Firstheading")).id();
            secondId = storage.chunks().insert(second.id(), chunk(second, 0, "secondbody", "Secondheading")).id();
            var before = rows(storage.connection());
            assertThrows(SQLException.class, () -> repository.insert(first.sourcePath(), DocumentType.PLAIN_TEXT, HASH));
            assertThrows(SQLException.class, () -> repository.update(
                    new StoredFile(second.id(), first.sourcePath(), DocumentType.PLAIN_TEXT, HASH)));
            assertEquals(List.of(first, second), repository.findAll());
            assertEquals(before, rows(storage.connection()));
            assertMatches(storage.connection(), "source_path: a", firstId);
            assertMatches(storage.connection(), "source_path: b", secondId);
            assertMatches(storage.connection(), "content: firstbody", firstId);
            assertMatches(storage.connection(), "headings: secondheading", secondId);
            assertConsistent(storage.connection());
        }
        try (SqliteStorage storage = SqliteStorage.open(database, root)) {
            assertMatches(storage.connection(), "source_path: a", firstId);
            assertMatches(storage.connection(), "source_path: b", secondId);
            var second = storage.files().findByPath(Path.of("b.md")).orElseThrow();
            assertTrue(storage.files().update(new StoredFile(second.id(), Path.of("retry.md"), second.documentType(), second.contentHash())));
            assertMatches(storage.connection(), "source_path: b");
            assertMatches(storage.connection(), "source_path: retry", secondId);
            assertMatches(storage.connection(), "source_path: a", firstId);
            assertConsistent(storage.connection());
        }
        try (SqliteStorage storage = SqliteStorage.open(database, root)) {
            assertMatches(storage.connection(), "source_path: a", firstId);
            assertMatches(storage.connection(), "source_path: retry", secondId);
            assertMatches(storage.connection(), "source_path: b");
            assertMatches(storage.connection(), "content: firstbody", firstId);
            assertMatches(storage.connection(), "headings: firstheading", firstId);
            assertMatches(storage.connection(), "content: secondbody", secondId);
            assertMatches(storage.connection(), "headings: secondheading", secondId);
            assertConsistent(storage.connection());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void pathUpdatesAndCascadeDeletionShareTransactionsAndPreserveOtherFiles(boolean commitDeletion) throws Exception {
        Path database = root.resolve("index.db");
        StoredFile moved;
        long firstId;
        long secondId;
        long retainedId;
        try (var storage = SqliteStorage.open(database, root)) {
            var file = storage.files().insert(Path.of("oldpath.md"), DocumentType.MARKDOWN, HASH);
            var other = storage.files().insert(Path.of("retained.md"), DocumentType.MARKDOWN, HASH);
            firstId = storage.chunks().insert(file.id(), chunk(file, 0, "firstbody", "Firstheading")).id();
            secondId = storage.chunks().insert(file.id(), chunk(file, 1, "secondbody", "Secondheading")).id();
            retainedId = storage.chunks().insert(other.id(), chunk(other, 0, "retainedbody", "Retainedheading")).id();
            var before = rows(storage.connection());
            // A hash-only repository update must not change any searchable field.
            var hashed = new StoredFile(file.id(), file.sourcePath(), file.documentType(), ContentHash.sha256(new byte[0]));
            assertTrue(storage.files().update(hashed));
            assertEquals(before, rows(storage.connection()));
            assertMatches(storage.connection(), "source_path: oldpath", firstId, secondId);
            assertConsistent(storage.connection());

            moved = new StoredFile(file.id(), Path.of("newpath.md"), file.documentType(), hashed.contentHash());
            var sourceBefore = storage.chunks().findByFileId(file.id());
            storage.connection().setAutoCommit(false);
            assertTrue(storage.files().update(moved));
            assertMatches(storage.connection(), "source_path: oldpath");
            assertMatches(storage.connection(), "source_path: newpath", firstId, secondId);
            assertMatches(storage.connection(), "source_path: retained", retainedId);
            assertConsistent(storage.connection());
            storage.connection().rollback();
            assertEquals(hashed, storage.files().findByPath(hashed.sourcePath()).orElseThrow());
            assertEquals(sourceBefore, storage.chunks().findByFileId(file.id()));
            assertEquals(before, rows(storage.connection()));
            assertMatches(storage.connection(), "source_path: oldpath", firstId, secondId);
            assertMatches(storage.connection(), "newpath");
            storage.connection().setAutoCommit(true);
            assertTrue(storage.files().update(moved));
            assertMatches(storage.connection(), "source_path: newpath", firstId, secondId);
            assertMatches(storage.connection(), "oldpath");
            assertConsistent(storage.connection());
        }
        try (var storage = SqliteStorage.open(database, root)) {
            assertEquals(moved, storage.files().findByPath(moved.sourcePath()).orElseThrow());
            assertMatches(storage.connection(), "source_path: newpath", firstId, secondId);
            assertMatches(storage.connection(), "oldpath");
            var before = rows(storage.connection());
            var sourceBefore = storage.chunks().findByFileId(moved.id());
            storage.connection().setAutoCommit(false);
            assertTrue(storage.files().delete(moved.id()));
            assertTrue(storage.chunks().findByFileId(moved.id()).isEmpty());
            assertMatches(storage.connection(), "firstbody OR secondbody OR firstheading OR secondheading OR newpath OR oldpath");
            assertMatches(storage.connection(), "content: retainedbody", retainedId);
            assertMatches(storage.connection(), "headings: retainedheading", retainedId);
            assertMatches(storage.connection(), "source_path: retained", retainedId);
            assertConsistent(storage.connection());
            if (commitDeletion) {
                storage.connection().commit();
            } else {
                storage.connection().rollback();
                assertEquals(moved, storage.files().findByPath(moved.sourcePath()).orElseThrow());
                assertEquals(sourceBefore, storage.chunks().findByFileId(moved.id()));
                assertEquals(before, rows(storage.connection()));
            }
            storage.connection().setAutoCommit(true);
        }
        try (var storage = SqliteStorage.open(database, root)) {
            assertEquals(!commitDeletion, storage.files().findByPath(moved.sourcePath()).isPresent());
            assertEquals(commitDeletion ? 0 : 2, storage.chunks().findByFileId(moved.id()).size());
            assertMatches(storage.connection(), "source_path: newpath", commitDeletion ? new long[0] : new long[]{firstId, secondId});
            assertMatches(storage.connection(), "content: firstbody", commitDeletion ? new long[0] : new long[]{firstId});
            assertMatches(storage.connection(), "content: secondbody", commitDeletion ? new long[0] : new long[]{secondId});
            assertMatches(storage.connection(), "headings: firstheading", commitDeletion ? new long[0] : new long[]{firstId});
            assertMatches(storage.connection(), "headings: secondheading", commitDeletion ? new long[0] : new long[]{secondId});
            assertMatches(storage.connection(), "oldpath");
            assertMatches(storage.connection(), "content: retainedbody", retainedId);
            assertMatches(storage.connection(), "headings: retainedheading", retainedId);
            assertMatches(storage.connection(), "source_path: retained", retainedId);
            assertConsistent(storage.connection());
        }
    }

    @Test
    void storesCaseAndUnicodeDistinctPathsInBinaryOrder() throws Exception {
        try (SqliteStorage storage = SqliteStorage.open(root.resolve("index.db"), root)) {
            var repository = storage.files();

            for (String name : List.of("a.txt", "é.txt", "A.txt", "é.txt")) {
                repository.insert(Path.of(name), DocumentType.PLAIN_TEXT, HASH);
            }

            assertEquals(List.of("A.txt", "a.txt", "é.txt", "é.txt"),
                    repository.findAll().stream().map(file -> file.sourcePath().toString()).toList());
        }
    }

    @Test
    void doesNotCommitOrCloseTheStorageConnection() throws Exception {
        try (SqliteStorage storage = SqliteStorage.open(root.resolve("index.db"), root)) {
            var connection = storage.connection();
            var repository = storage.files();
            connection.setAutoCommit(false);
            repository.insert(Path.of("rollback.txt"), DocumentType.PLAIN_TEXT, HASH);
            connection.rollback();
            assertTrue(repository.findAll().isEmpty());
            assertFalse(connection.isClosed());
            connection.setAutoCommit(true);
        }
    }

    @Test
    void deletingThroughRepositoryCascadesToChunkMetadata() throws Exception {
        try (SqliteStorage storage = SqliteStorage.open(root.resolve("index.db"), root)) {
            var file = storage.files().insert(Path.of("a.txt"), DocumentType.PLAIN_TEXT, HASH);

            try (var statement = storage.connection().createStatement()) {
                statement.executeUpdate("INSERT INTO chunks VALUES (1, " + file.id() + ", 0, 'text', 1, 1);"
                        + "INSERT INTO chunk_attributes VALUES (1, 'startOffset', '0');"
                        + "INSERT INTO chunk_headings VALUES (1, 0, 'Heading');");
                assertMatches(storage.connection(), "content: text", 1);
                assertMatches(storage.connection(), "headings: heading", 1);
                assertMatches(storage.connection(), "source_path: a", 1);
                assertTrue(storage.files().delete(file.id()));

                for (String table : List.of("chunks", "chunk_attributes", "chunk_headings")) {
                    try (var rows = statement.executeQuery("SELECT count(*) FROM " + table)) {
                        assertTrue(rows.next());
                        assertEquals(0, rows.getInt(1));
                    }
                }
                assertMatches(storage.connection(), "text OR heading OR a");
                assertConsistent(storage.connection());
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"../outside", "a/../b", ""})
    void rejectsInvalidInputBeforeWriting(String path) throws Exception {
        try (SqliteStorage storage = SqliteStorage.open(root.resolve("index.db"), root)) {
            assertThrows(IllegalArgumentException.class,
                    () -> storage.files().insert(Path.of(path), DocumentType.PLAIN_TEXT, HASH));
            assertTrue(storage.files().findAll().isEmpty());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"../outside", "a//b", "a/", "/absolute", "a/../b"})
    void mapperRejectsInvalidStoredPathsWithoutAdvancingCursor(String path) throws Exception {
        try (SqliteStorage storage = SqliteStorage.open(root.resolve("index.db"), root);
             var statement = storage.connection().prepareStatement(
                     "SELECT 1 AS id, ? AS source_path, 'PLAIN_TEXT' AS document_type, ? AS content_hash")) {
            statement.setString(1, path);
            statement.setString(2, HASH.value());

            try (var rows = statement.executeQuery()) {
                assertTrue(rows.next());
                assertThrows(SQLException.class, () -> new StoredFileRowMapper().map(rows));
                assertFalse(rows.isClosed());
                assertEquals(path, rows.getString("source_path"));
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"NULL, 'PLAIN_TEXT', 'hash'", "'a.txt', 'UNKNOWN', 'hash'", "'a.txt', 'PLAIN_TEXT', 'hash'"})
    void mapperRejectsMalformedValues(String values) throws Exception {
        try (SqliteStorage storage = SqliteStorage.open(root.resolve("index.db"), root);
             var statement = storage.connection().createStatement();
             var rows = statement.executeQuery("WITH fixture(source_path, document_type, content_hash) AS (VALUES ("
                     + values + ")) SELECT 1 AS id, * FROM fixture")) {
            assertTrue(rows.next());
            assertThrows(SQLException.class, () -> new StoredFileRowMapper().map(rows));
        }
    }

    @Test
    void retainedRepositoryCannotOperateAfterStorageCloses() throws Exception {
        SqliteStorage storage = SqliteStorage.open(root.resolve("index.db"), root);
        var repository = storage.files();
        storage.close();
        assertThrows(SQLException.class, repository::findAll);
    }

    private static Chunk chunk(StoredFile file, int index, String content, String heading) {
        return new Chunk(file.sourcePath(), file.documentType(), index, content, new LineRange(1, 2),
                new ChunkMetadata(List.of(heading), Map.of("startOffset", "0")));
    }
}
