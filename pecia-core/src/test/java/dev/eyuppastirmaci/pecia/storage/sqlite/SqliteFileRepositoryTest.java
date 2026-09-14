package dev.eyuppastirmaci.pecia.storage.sqlite;

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
        try (SqliteStorage storage = SqliteStorage.open(root.resolve("index.db"), root)) {
            var repository = storage.files();
            StoredFile first = repository.insert(Path.of("a.md"), DocumentType.MARKDOWN, HASH);
            StoredFile second = repository.insert(Path.of("b.md"), DocumentType.MARKDOWN, HASH);
            assertThrows(SQLException.class, () -> repository.insert(first.sourcePath(), DocumentType.PLAIN_TEXT, HASH));
            assertThrows(SQLException.class, () -> repository.update(
                    new StoredFile(second.id(), first.sourcePath(), DocumentType.PLAIN_TEXT, HASH)));
            assertEquals(List.of(first, second), repository.findAll());
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
                assertTrue(storage.files().delete(file.id()));

                for (String table : List.of("chunks", "chunk_attributes", "chunk_headings")) {
                    try (var rows = statement.executeQuery("SELECT count(*) FROM " + table)) {
                        assertTrue(rows.next());
                        assertEquals(0, rows.getInt(1));
                    }
                }
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
}
