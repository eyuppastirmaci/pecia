package dev.eyuppastirmaci.pecia.storage.sqlite;

import static dev.eyuppastirmaci.pecia.storage.sqlite.SqliteFtsTestSupport.execute;
import static dev.eyuppastirmaci.pecia.storage.sqlite.SqliteFtsTestSupport.insertChunk;
import static dev.eyuppastirmaci.pecia.storage.sqlite.SqliteFtsTestSupport.insertFile;
import static dev.eyuppastirmaci.pecia.storage.sqlite.SqliteFtsTestSupport.insertHeading;
import static dev.eyuppastirmaci.pecia.storage.sqlite.SqliteFtsTestSupport.openVersionOne;
import static dev.eyuppastirmaci.pecia.storage.sqlite.SqliteFtsTestSupport.openVersionTwo;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.eyuppastirmaci.pecia.content.LineRange;
import dev.eyuppastirmaci.pecia.search.SearchHit;
import dev.eyuppastirmaci.pecia.search.SearchRequest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Properties;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.sqlite.JDBC;

class SqliteReadOnlyStorageTest {

    @TempDir
    Path root;

    @Test
    void searchesAnExistingIndexWithoutChangingItsBytesAndClosesTheOwnedConnection() throws Exception {
        Path database = root.resolve("İndex #?%.db");

        try (SqliteStorage storage = SqliteStorage.open(database, root)) {
            populate(storage.connection());
        }

        byte[] before = Files.readAllBytes(database);
        List<Path> entries = entries();
        Connection owned;

        try (SqliteStorage storage = SqliteStorage.openReadOnly(database, root.resolve("."))) {
            owned = storage.connection();

            assertTrue(owned.isReadOnly());
            assertTrue(owned.getAutoCommit());

            List<SearchHit> hits = storage.lexicalSearch().search(new SearchRequest("JWT_SECRET"));

            assertEquals(1, hits.size());
            assertEquals(Path.of("docs/İstanbul.md"), hits.getFirst().sourcePath());
            assertEquals(new LineRange(1, 2), hits.getFirst().sourceLocation());
            assertEquals(List.of("Authentication"), hits.getFirst().metadata().headingPath());
            assertEquals("JWT_SECRET İstanbul 😀", hits.getFirst().snippet());
            assertTrue(
                    storage.lexicalSearch().search(new SearchRequest("absent")).isEmpty());
            assertEquals(hits, storage.lexicalSearch().search(new SearchRequest("JWT_SECRET")));
            assertEquals(0, scalar(owned, "SELECT total_changes()"));
            assertEquals(0, scalar(owned, "SELECT count(*) FROM sqlite_temp_schema"));
        }

        assertTrue(owned.isClosed());
        assertArrayEquals(before, Files.readAllBytes(database));
        assertEquals(entries, entries());
    }

    @Test
    void nativeReadOnlyModeStillRejectsWritesWhenQueryOnlyIsDisabled() throws Exception {
        Path database = root.resolve("index.db");

        try (SqliteStorage storage = SqliteStorage.open(database, root)) {
            populate(storage.connection());
        }

        byte[] before = Files.readAllBytes(database);

        try (SqliteStorage storage = SqliteStorage.openReadOnly(database, root)) {
            Connection connection = storage.connection();
            execute(connection, "PRAGMA query_only = OFF");

            for (String sql : List.of(
                    "UPDATE chunks SET content = 'changed'",
                    "CREATE TABLE unexpected(value TEXT)",
                    "PRAGMA user_version = 99")) {
                SQLException failure = assertThrows(SQLException.class, () -> execute(connection, sql));

                assertEquals(8, failure.getErrorCode() & 0xff, "Native SQLITE_READONLY must reject: " + sql);
            }

            assertEquals(
                    1,
                    storage.lexicalSearch()
                            .search(new SearchRequest("JWT_SECRET"))
                            .size());
            assertEquals(3, scalar(connection, "PRAGMA user_version"));
        }

        assertArrayEquals(before, Files.readAllBytes(database));
    }

    @ParameterizedTest
    @ValueSource(strings = {"index.db", "missing/parent/index.db"})
    void rejectsAMissingIndexWithoutCreatingItsFileOrParents(String relativePath) throws Exception {
        Path database = root.resolve(relativePath);
        List<Path> before = entries();

        IndexAccessException failure =
                assertThrows(IndexAccessException.class, () -> SqliteStorage.openReadOnly(database, root));

        assertEquals(IndexAccessException.Reason.NOT_FOUND, failure.reason());
        assertEquals(before, entries());
        assertFalse(Files.exists(database));
    }

    @Test
    void rejectsNullInputsBeforeCreatingAnyFilesystemEntries() throws Exception {
        Path database = root.resolve("missing/index.db");
        List<Path> before = entries();

        assertThrows(NullPointerException.class, () -> SqliteStorage.openReadOnly(null, root));
        assertThrows(NullPointerException.class, () -> SqliteStorage.openReadOnly(database, null));

        assertEquals(before, entries());
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2})
    void reportsMigrationForAValidHistoricalIndexWithoutUpgradingIt(int version) throws Exception {
        Path database = root.resolve("index.db");

        try (Connection connection = version == 1 ? openVersionOne(root) : openVersionTwo(root)) {
            populate(connection);
        }

        byte[] before = Files.readAllBytes(database);

        IndexAccessException failure =
                assertThrows(IndexAccessException.class, () -> SqliteStorage.openReadOnly(database, root));

        assertEquals(IndexAccessException.Reason.MIGRATION_REQUIRED, failure.reason());
        assertTrue(failure.getMessage().contains("index"));
        assertArrayEquals(before, Files.readAllBytes(database));

        try (Connection connection = raw(database)) {
            assertEquals(version, scalar(connection, "PRAGMA user_version"));
            assertEquals(version, scalar(connection, "SELECT index_format_version FROM index_metadata"));
            assertEquals(
                    version - 1, scalar(connection, "SELECT count(*) FROM sqlite_schema WHERE name = 'chunks_fts'"));
            assertEquals(
                    0, scalar(connection, "SELECT count(*) FROM sqlite_schema WHERE name = 'file_indexing_profiles'"));
            assertEquals(1, scalar(connection, "SELECT count(*) FROM chunks"));
            assertExclusiveAccess(connection);
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 4, 99})
    void rejectsUnsupportedSchemaVersionsWithoutAdoptingOrChangingThem(int version) throws Exception {
        Path database = root.resolve("index.db");

        try (Connection connection = raw(database)) {
            execute(connection, "CREATE TABLE sentinel(value TEXT)");
            execute(connection, "PRAGMA user_version = " + version);
        }

        assertRejectedWithoutModification(database, root, IndexAccessException.Reason.INCOMPATIBLE);
    }

    @Test
    void rejectsAnUnsupportedIndexFormatWithoutChangingIt() throws Exception {
        Path database = root.resolve("index.db");

        try (SqliteStorage storage = SqliteStorage.open(database, root)) {
            execute(storage.connection(), "UPDATE index_metadata SET index_format_version = 99");
        }

        assertRejectedWithoutModification(database, root, IndexAccessException.Reason.INCOMPATIBLE);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void rejectsAnotherProjectsIndexBeforeMigrationOrSearch(boolean versionOne) throws Exception {
        Path database = root.resolve("index.db");

        if (versionOne) {
            try (Connection connection = openVersionOne(root)) {
                populate(connection);
            }
        } else {
            try (SqliteStorage storage = SqliteStorage.open(database, root)) {
                populate(storage.connection());
            }
        }

        Path otherProject = Files.createDirectory(root.resolve("other"));

        assertRejectedWithoutModification(database, otherProject, IndexAccessException.Reason.WRONG_PROJECT);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "DROP TABLE chunk_attributes",
                "ALTER TABLE chunks RENAME COLUMN content TO wrong",
                "DELETE FROM index_metadata",
                "DROP TRIGGER chunks_fts_insert",
                "DROP TABLE chunks_fts"
            })
    void rejectsDamagedSchemaAndFtsStructuresWithoutRepairingThem(String damage) throws Exception {
        Path database = root.resolve("index.db");

        try (SqliteStorage storage = SqliteStorage.open(database, root)) {
            execute(storage.connection(), damage);
        }

        assertRejectedWithoutModification(database, root, IndexAccessException.Reason.CORRUPT_INDEX);
    }

    @Test
    void reportsNonDatabaseBytesAsCorruptionWithoutChangingTheFile() throws Exception {
        Path database = Files.writeString(root.resolve("index.db"), "this is not a SQLite database");
        byte[] before = Files.readAllBytes(database);

        IndexAccessException failure =
                assertThrows(IndexAccessException.class, () -> SqliteStorage.openReadOnly(database, root));

        assertEquals(IndexAccessException.Reason.CORRUPT_INDEX, failure.reason());
        assertInstanceOf(SQLException.class, failure.getCause());
        assertArrayEquals(before, Files.readAllBytes(database));
    }

    @Test
    void readsCommittedWalContentWithoutCheckpointingOrIgnoringIt() throws Exception {
        Path database = root.resolve("index.db");

        try (SqliteStorage ignored = SqliteStorage.open(database, root)) {}

        try (Connection writer = raw(database)) {
            try (Statement statement = writer.createStatement();
                    ResultSet result = statement.executeQuery("PRAGMA journal_mode = WAL")) {
                assertTrue(result.next());
                assertEquals("wal", result.getString(1));
            }

            populate(writer);
            Path wal = Path.of(database + "-wal");

            assertTrue(Files.size(wal) > 0);

            byte[] databaseBefore = Files.readAllBytes(database);
            byte[] walBefore = Files.readAllBytes(wal);

            try (SqliteStorage storage = SqliteStorage.openReadOnly(database, root)) {
                assertEquals(
                        1,
                        storage.lexicalSearch()
                                .search(new SearchRequest("JWT_SECRET"))
                                .size());
            }

            assertArrayEquals(databaseBefore, Files.readAllBytes(database));
            assertArrayEquals(walBefore, Files.readAllBytes(wal));
        }
    }

    @Test
    @Timeout(10)
    void preservesBusyFailuresDuringValidationAndAllowsRetryAfterTheWriterUnlocks() throws Exception {
        Path database = root.resolve("index.db");

        try (SqliteStorage storage = SqliteStorage.open(database, root)) {
            populate(storage.connection());
        }

        try (SqliteStorage reader = SqliteStorage.openReadOnly(database, root);
                Connection writer = raw(database)) {
            Connection connection = reader.connection();

            try (Statement statement = connection.createStatement()) {
                statement.execute("PRAGMA busy_timeout = 0");
            }

            execute(writer, "BEGIN EXCLUSIVE");

            try {
                SQLException failure = assertThrows(
                        SQLException.class,
                        () -> SqliteSchemaInitializer.load(
                                        connection, root.toRealPath().toUri().toASCIIString())
                                .validateReadOnly());

                assertEquals(5, failure.getErrorCode() & 0xff, "The writer lock must remain a SQLITE_BUSY failure");
                assertFalse(failure instanceof IndexAccessException, "A locked database is not a corrupt index");
            } finally {
                execute(writer, "ROLLBACK");
            }

            SqliteSchemaInitializer.load(connection, root.toRealPath().toUri().toASCIIString())
                    .validateReadOnly();

            assertEquals(
                    1,
                    reader.lexicalSearch()
                            .search(new SearchRequest("JWT_SECRET"))
                            .size());
        }
    }

    private void assertRejectedWithoutModification(Path database, Path projectRoot, IndexAccessException.Reason reason)
            throws Exception {
        byte[] before = Files.readAllBytes(database);
        List<Path> entries = entries();
        IndexAccessException failure =
                assertThrows(IndexAccessException.class, () -> SqliteStorage.openReadOnly(database, projectRoot));

        assertEquals(reason, failure.reason());
        assertArrayEquals(before, Files.readAllBytes(database));
        assertEquals(entries, entries());

        try (Connection connection = raw(database)) {
            assertExclusiveAccess(connection);
        }
    }

    private static void assertExclusiveAccess(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA busy_timeout = 0");
        }

        execute(connection, "BEGIN EXCLUSIVE");
        execute(connection, "ROLLBACK");
    }

    private List<Path> entries() throws Exception {
        try (Stream<Path> paths = Files.walk(root)) {
            return paths.sorted().toList();
        }
    }

    private static void populate(Connection connection) throws SQLException {
        insertFile(connection, 1, "docs/İstanbul.md");
        insertChunk(connection, 1, 1, 0, "JWT_SECRET İstanbul 😀");
        insertHeading(connection, 1, 0, "Authentication");
    }

    private static int scalar(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery(sql)) {
            assertTrue(result.next());

            return result.getInt(1);
        }
    }

    private static Connection raw(Path database) throws SQLException {
        return JDBC.createConnection("jdbc:sqlite:" + database.toUri().toASCIIString(), new Properties());
    }
}
