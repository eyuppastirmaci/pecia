package dev.eyuppastirmaci.pecia.storage.sqlite;

import static dev.eyuppastirmaci.pecia.storage.sqlite.SqliteFtsTestSupport.V1_RESOURCE;
import static dev.eyuppastirmaci.pecia.storage.sqlite.SqliteFtsTestSupport.V2_RESOURCE;
import static dev.eyuppastirmaci.pecia.storage.sqlite.SqliteFtsTestSupport.assertConsistent;
import static dev.eyuppastirmaci.pecia.storage.sqlite.SqliteFtsTestSupport.assertMatches;
import static dev.eyuppastirmaci.pecia.storage.sqlite.SqliteFtsTestSupport.execute;
import static dev.eyuppastirmaci.pecia.storage.sqlite.SqliteFtsTestSupport.insertChunk;
import static dev.eyuppastirmaci.pecia.storage.sqlite.SqliteFtsTestSupport.insertFile;
import static dev.eyuppastirmaci.pecia.storage.sqlite.SqliteFtsTestSupport.insertHeading;
import static dev.eyuppastirmaci.pecia.storage.sqlite.SqliteFtsTestSupport.openVersionOne;
import static dev.eyuppastirmaci.pecia.storage.sqlite.SqliteFtsTestSupport.openVersionThree;
import static dev.eyuppastirmaci.pecia.storage.sqlite.SqliteFtsTestSupport.openVersionTwo;
import static dev.eyuppastirmaci.pecia.storage.sqlite.SqliteFtsTestSupport.resource;
import static dev.eyuppastirmaci.pecia.storage.sqlite.SqliteFtsTestSupport.rows;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.sqlite.JDBC;

class SqliteFtsMigrationFailureTest {
    @TempDir
    Path root;

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3, 4})
    void missingFtsRuntimeDoesNotChangeAnySupportedVersionAndAllowsRetry(int version) throws Exception {
        Path database = root.resolve("index.db");
        if (version == 1) {
            try (Connection connection = openVersionOne(root)) {
                seed(connection);
            }
        } else if (version == 2) {
            try (Connection connection = openVersionTwo(root)) {
                seed(connection);
            }
        } else if (version == 3) {
            try (Connection connection = openVersionThree(root)) {
                seed(connection);
            }
        } else if (version == 4) {
            try (SqliteStorage storage = SqliteStorage.open(database, root)) {
                seed(storage.connection());
            }
        }

        try (Connection connection = raw(database)) {
            byte[] before = Files.readAllBytes(database);
            SQLException cause = new SQLException("no such module: fts5", "missing", 1);
            SQLException failure = assertThrows(
                    SQLException.class,
                    () -> SqliteSchemaInitializer.load(connection, rootUri())
                            .initialize(
                                    resource(V1_RESOURCE),
                                    resource(V2_RESOURCE),
                                    owned -> SqliteFtsSupport.verify(owned, (probeConnection, table) -> {
                                        throw cause;
                                    })));

            assertTrue(failure.getMessage().contains("FTS5 is unavailable"));
            assertSame(cause, failure.getCause());
            assertEquals(0, failure.getSuppressed().length);
            assertEquals(version, scalar(connection, "PRAGMA user_version"));
            assertEquals(0, scalar(connection, "SELECT count(*) FROM temp.sqlite_schema"));
            assertFalse(connection.isClosed(), "The initializer does not own this test connection");
            if (version > 0) {
                assertArrayEquals(before, Files.readAllBytes(database));
                assertEquals(version, scalar(connection, "SELECT index_format_version FROM index_metadata"));
                assertEquals(2, scalar(connection, "SELECT count(*) FROM chunks"));
            } else {
                assertEquals(0, scalar(connection, "SELECT count(*) FROM sqlite_schema"));
            }

            SqliteSchemaInitializer.load(connection, rootUri()).initialize();
            assertEquals(4, scalar(connection, "PRAGMA user_version"));
            assertEquals(4, scalar(connection, "SELECT index_format_version FROM index_metadata"));
            if (version > 0) {
                assertMatches(connection, "originalfirst", 11);
                assertMatches(connection, "originalsecond", 12);
            }
            assertConsistent(connection);
        }
    }

    @ParameterizedTest
    @ValueSource(
            strings = {"partial-schema", "mid-backfill", "after-backfill", "wrong-trigger", "after-version-update"})
    void failedUpgradeRestoresTheExactVersionOneDatabaseAndCanBeRetried(String failurePoint) throws Exception {
        try (Connection connection = openVersionOne(root)) {
            seed(connection);
            byte[] before = Files.readAllBytes(root.resolve("index.db"));
            String canonical = resource(V2_RESOURCE);
            String failingMigration =
                    switch (failurePoint) {
                        case "partial-schema" -> "CREATE TABLE partially_created(value TEXT); INVALID SQL;";
                        case "mid-backfill" ->
                            canonical.replace(
                                    "SELECT c.id, c.content,",
                                    "SELECT c.id, CASE WHEN c.id = 12 THEN abs(-9223372036854775808) ELSE c.content"
                                            + " END,");
                        case "after-backfill" -> canonical + "\nINSERT INTO missing_target VALUES (1);";
                        case "wrong-trigger" ->
                            canonical.replace("DELETE FROM chunks_fts WHERE rowid = OLD.id;", "SELECT 1;");
                        // Both version markers will be advanced before final ownership validation rejects this
                        // change.
                        case "after-version-update" ->
                            canonical + "\nUPDATE index_metadata SET project_root_uri = 'wrong-owner';";
                        default -> throw new AssertionError(failurePoint);
                    };

            SQLException failure = assertThrows(
                    SQLException.class,
                    () -> SqliteSchemaInitializer.load(connection, rootUri())
                            .initialize(resource(V1_RESOURCE), failingMigration, SqliteFtsSupport::verify));

            assertFalse(failure.getMessage().contains("FTS5 is unavailable"));
            assertEquals(0, failure.getSuppressed().length);
            assertArrayEquals(before, Files.readAllBytes(root.resolve("index.db")));
            assertEquals(1, scalar(connection, "PRAGMA user_version"));
            assertEquals(1, scalar(connection, "SELECT index_format_version FROM index_metadata"));
            assertEquals(0, scalar(connection, "SELECT count(*) FROM sqlite_schema WHERE name = 'chunks_fts'"));
            assertEquals(0, scalar(connection, "SELECT count(*) FROM temp.sqlite_schema"));

            SqliteSchemaInitializer.load(connection, rootUri()).initialize();
            assertEquals(4, scalar(connection, "PRAGMA user_version"));
            assertMatches(connection, "content: originalfirst", 11);
            assertMatches(connection, "content: originalsecond", 12);
            assertMatches(connection, "headings: Originalheading", 11);
            assertConsistent(connection);
        }
    }

    @Test
    void failingFreshInitializationLeavesNoSchemaAndAllowsNormalOpen() throws Exception {
        Path database = root.resolve("index.db");
        try (Connection connection = raw(database)) {
            assertThrows(
                    SQLException.class,
                    () -> SqliteSchemaInitializer.load(connection, rootUri())
                            .initialize(
                                    resource(V1_RESOURCE),
                                    resource(V2_RESOURCE) + "\nINVALID SQL;",
                                    SqliteFtsSupport::verify));
            assertEquals(0, scalar(connection, "PRAGMA user_version"));
            assertEquals(0, scalar(connection, "SELECT count(*) FROM sqlite_schema"));
            assertEquals(0, scalar(connection, "SELECT count(*) FROM temp.sqlite_schema"));
        }

        try (SqliteStorage storage = SqliteStorage.open(database, root)) {
            assertEquals(4, scalar(storage.connection(), "PRAGMA user_version"));
            assertConsistent(storage.connection());
        }
    }

    @Test
    void acceptsHistoricalCrLfDeclarationsWhenOpeningWithAnLfResource() throws Exception {
        try (Connection connection = openVersionOne(root)) {
            seed(connection);
            SqliteSchemaInitializer.load(connection, rootUri())
                    .initialize(
                            resource(V1_RESOURCE),
                            resource(V2_RESOURCE).replace("\n", "\r\n"),
                            SqliteFtsSupport::verify);
            assertMatches(connection, "originalfirst", 11);
        }
        try (SqliteStorage storage = SqliteStorage.open(root.resolve("index.db"), root)) {
            assertMatches(storage.connection(), "originalsecond", 12);
            assertEquals(4, scalar(storage.connection(), "PRAGMA user_version"));
        }
    }

    @Test
    void publicOpenRollsBackWhenAdvancingTheFormatMarkerFailsAndReleasesItsConnection() throws Exception {
        Path database = root.resolve("index.db");
        try (Connection connection = openVersionOne(root)) {
            seed(connection);
            execute(connection, """
                CREATE TRIGGER fail_format_update BEFORE UPDATE OF index_format_version ON index_metadata
                BEGIN SELECT RAISE(ABORT, 'injected format update failure'); END;
                """);
        }
        byte[] before = Files.readAllBytes(database);

        SQLException failure = assertThrows(SQLException.class, () -> SqliteStorage.open(database, root));
        assertTrue(failure.getMessage().contains("injected format update failure"));
        assertArrayEquals(before, Files.readAllBytes(database));

        try (Connection connection = raw(database)) {
            execute(connection, "BEGIN EXCLUSIVE");
            assertEquals(1, scalar(connection, "PRAGMA user_version"));
            assertEquals(1, scalar(connection, "SELECT index_format_version FROM index_metadata"));
            assertEquals(0, scalar(connection, "SELECT count(*) FROM sqlite_schema WHERE name = 'chunks_fts'"));
            execute(connection, "DROP TRIGGER fail_format_update");
            execute(connection, "COMMIT");
        }
        try (SqliteStorage storage = SqliteStorage.open(database, root)) {
            assertMatches(storage.connection(), "originalfirst", 11);
            assertConsistent(storage.connection());
        }
    }

    @Test
    void rejectsAnOrphanedVersionOneChunkBeforeBackfillCanSilentlyOmitIt() throws Exception {
        Path database = root.resolve("index.db");
        try (Connection connection = openVersionOne(root)) {
            execute(connection, "PRAGMA foreign_keys = OFF");
            insertChunk(connection, 11, 99, 0, "orphan");
        }
        byte[] before = Files.readAllBytes(database);
        SQLException failure = assertThrows(SQLException.class, () -> SqliteStorage.open(database, root));
        assertTrue(failure.getMessage().contains("foreign-key relationship"));
        assertArrayEquals(before, Files.readAllBytes(database));
    }

    private void seed(Connection connection) throws SQLException {
        insertFile(connection, 1, "docs/original.md");
        insertChunk(connection, 11, 1, 0, "originalfirst");
        insertChunk(connection, 12, 1, 1, "originalsecond");
        insertHeading(connection, 11, 0, "Originalheading");
        execute(connection, "INSERT INTO chunk_attributes VALUES (11, 'startOffset', '0')");
    }

    private String rootUri() throws Exception {
        return root.toRealPath().toUri().toASCIIString();
    }

    private static Connection raw(Path database) throws SQLException {
        return JDBC.createConnection("jdbc:sqlite:" + database.toUri().toASCIIString(), new Properties());
    }

    private static int scalar(Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement();
                var rows = statement.executeQuery(sql)) {
            assertTrue(rows.next());
            return rows.getInt(1);
        }
    }
}
