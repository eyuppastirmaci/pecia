package dev.eyuppastirmaci.pecia.storage.sqlite;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.sqlite.JDBC;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class SqliteStorageTest {
    @TempDir
    Path root;

    @Test
    void initializesReopensAndClosesAFileDatabaseWithoutLosingData() throws Exception {
        Path database = root.resolve(".pecia/İndex #%.db");
        Connection owned;

        try (SqliteStorage storage = SqliteStorage.open(database, root)) {
            owned = storage.connection();
            assertEquals(1, scalar(owned, "PRAGMA user_version"));
            assertEquals(1, scalar(owned, "PRAGMA foreign_keys"));
            assertEquals(5, scalar(owned, "SELECT count(*) FROM sqlite_schema WHERE type = 'table' AND name NOT GLOB 'sqlite_*'"));
            insertFile(owned);
        }

        assertTrue(owned.isClosed());
        assertTrue(Files.size(database) > 0);

        try (SqliteStorage storage = SqliteStorage.open(database, root.resolve("."))) {
            assertEquals(1, scalar(storage.connection(), "SELECT count(*) FROM files"));
            assertEquals(1, scalar(storage.connection(), "PRAGMA foreign_keys"));
        }
    }

    @Test
    void enforcesRelationshipsUniquenessAndCascadingDeletion() throws Exception {
        try (SqliteStorage storage = SqliteStorage.open(root.resolve("index.db"), root)) {
            Connection connection = storage.connection();
            insertFile(connection);
            assertThrows(SQLException.class, () -> insertFile(connection));
            execute(connection, "INSERT INTO chunks VALUES (1, 1, 0, 'İstanbul 😀', 1, 2)");
            execute(connection, "INSERT INTO chunk_headings VALUES (1, 0, 'Başlık')");
            execute(connection, "INSERT INTO chunk_attributes VALUES (1, 'startOffset', '0')");
            assertThrows(SQLException.class, () -> execute(connection, "INSERT INTO chunks VALUES (2, 99, 0, 'orphan', 1, 1)"));
            assertThrows(SQLException.class, () -> execute(connection, "INSERT INTO chunks VALUES (2, 1, 0, 'duplicate', 1, 1)"));
            assertThrows(SQLException.class, () -> execute(connection, "INSERT INTO chunks VALUES (2, 1, 1, 'bad range', 2, 1)"));
            execute(connection, "DELETE FROM files WHERE id = 1");

            for (String table : new String[]{"chunks", "chunk_headings", "chunk_attributes"}) {
                assertEquals(0, scalar(connection, "SELECT count(*) FROM " + table));
            }
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 2, 100})
    void rejectsUnsupportedSchemaVersionsWithoutChangingTheDatabase(int version) throws Exception {
        Path database = root.resolve("index.db");

        try (Connection connection = raw(database)) {
            execute(connection, "CREATE TABLE sentinel(value TEXT)");
            execute(connection, "PRAGMA user_version = " + version);
        }

        byte[] before = Files.readAllBytes(database);
        SQLException failure = assertThrows(SQLException.class, () -> SqliteStorage.open(database, root));
        assertTrue(failure.getMessage().contains("schema version"));
        assertArrayEquals(before, Files.readAllBytes(database));
    }

    @Test
    void refusesToAdoptAnUnversionedExistingDatabase() throws Exception {
        Path database = root.resolve("index.db");

        try (Connection connection = raw(database)) {
            execute(connection, "CREATE TABLE personal_data(value TEXT)");
        }

        byte[] before = Files.readAllBytes(database);
        assertThrows(SQLException.class, () -> SqliteStorage.open(database, root));
        assertArrayEquals(before, Files.readAllBytes(database));
    }

    @Test
    void rejectsAnotherProjectAndReleasesTheFailedConnection() throws Exception {
        Path database = root.resolve("index.db");

        try (SqliteStorage ignored = SqliteStorage.open(database, root)) {
        }

        Path other = Files.createDirectory(root.resolve("other"));
        byte[] before = Files.readAllBytes(database);
        SQLException failure = assertThrows(SQLException.class, () -> SqliteStorage.open(database, other));
        assertTrue(failure.getMessage().contains("another project root"));
        assertArrayEquals(before, Files.readAllBytes(database));

        try (SqliteStorage storage = SqliteStorage.open(database, root)) {
            insertFile(storage.connection());
        }

        Files.delete(database);
    }

    @ParameterizedTest
    @ValueSource(strings = {"DROP TABLE chunk_attributes", "ALTER TABLE chunks RENAME COLUMN content TO wrong",
            "DELETE FROM index_metadata", "UPDATE index_metadata SET index_format_version = 2"})
    void rejectsIncompleteOrIncompatibleVersionOneDatabases(String damage) throws Exception {
        Path database = root.resolve("index.db");

        try (SqliteStorage storage = SqliteStorage.open(database, root)) {
            execute(storage.connection(), damage);
        }

        byte[] before = Files.readAllBytes(database);
        assertThrows(SQLException.class, () -> SqliteStorage.open(database, root));
        assertArrayEquals(before, Files.readAllBytes(database));
    }

    @Test
    void rollsBackPartiallyExecutedSchemaAndAllowsRetry() throws Exception {
        Path database = root.resolve("index.db");

        try (Connection connection = raw(database)) {
            assertThrows(SQLException.class, () -> SqliteSchemaInitializer.initialize(connection,
                    root.toRealPath().toUri().toASCIIString(),
                    "CREATE TABLE partial(value TEXT); PRAGMA user_version = 1; INVALID SQL;"));
            assertEquals(0, scalar(connection, "PRAGMA user_version"));
            assertEquals(0, scalar(connection, "SELECT count(*) FROM sqlite_schema"));
        }

        try (SqliteStorage storage = SqliteStorage.open(database, root)) {
            assertEquals(1, scalar(storage.connection(), "PRAGMA user_version"));
        }
    }

    @Test
    void rollsBackWhenMetadataInsertFailsAfterSchemaCreation() throws Exception {
        Path database = root.resolve("index.db");

        try (Connection connection = raw(database)) {
            assertThrows(SQLException.class, () -> SqliteSchemaInitializer.initialize(connection, "root",
                    "CREATE TABLE index_metadata(singleton INTEGER CHECK(singleton = 2), project_root_uri TEXT, index_format_version INTEGER);"));
            assertEquals(0, scalar(connection, "SELECT count(*) FROM sqlite_schema"));
            assertEquals(0, scalar(connection, "PRAGMA user_version"));
        }
    }

    @Test
    void concurrentOpenersShareOneInitialization() throws Exception {
        Path database = root.resolve("index.db");
        CountDownLatch start = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> openAfter(start, database));
            var second = executor.submit(() -> openAfter(start, database));
            start.countDown();
            assertEquals(1, first.get(15, TimeUnit.SECONDS));
            assertEquals(1, second.get(15, TimeUnit.SECONDS));
        }
    }

    @Test
    void rejectsInvalidProjectRootBeforeCreatingDatabaseDirectories() throws Exception {
        Path database = root.resolve("missing/index.db");
        Path file = Files.writeString(root.resolve("file.txt"), "text");
        assertThrows(java.io.IOException.class, () -> SqliteStorage.open(database, file));
        assertFalse(Files.exists(database.getParent()));
    }

    private int openAfter(CountDownLatch start, Path database) throws Exception {
        assertTrue(start.await(5, TimeUnit.SECONDS));

        try (SqliteStorage storage = SqliteStorage.open(database, root)) {
            return scalar(storage.connection(), "SELECT count(*) FROM index_metadata");
        }
    }

    private static Connection raw(Path database) throws SQLException {
        return JDBC.createConnection("jdbc:sqlite:" + database.toUri().toASCIIString(), new Properties());
    }

    private static void insertFile(Connection connection) throws SQLException {
        execute(connection, "INSERT INTO files VALUES (1, 'docs/İstanbul.md', 'MARKDOWN', '" + "a".repeat(64) + "')");
    }

    private static void execute(Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.executeUpdate(sql);
        }
    }

    private static int scalar(Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement(); var result = statement.executeQuery(sql)) {
            assertTrue(result.next());

            return result.getInt(1);
        }
    }
}
