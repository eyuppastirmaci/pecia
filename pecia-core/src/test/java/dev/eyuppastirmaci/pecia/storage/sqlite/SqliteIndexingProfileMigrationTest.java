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
import static dev.eyuppastirmaci.pecia.storage.sqlite.SqliteFtsTestSupport.openVersionTwo;
import static dev.eyuppastirmaci.pecia.storage.sqlite.SqliteFtsTestSupport.resource;
import static dev.eyuppastirmaci.pecia.storage.sqlite.SqliteFtsTestSupport.rows;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.eyuppastirmaci.pecia.storage.sqlite.SqliteFtsTestSupport.FtsRow;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.sqlite.JDBC;

class SqliteIndexingProfileMigrationTest {
    private static final String V3_RESOURCE = "/db/migration/V3__add_file_indexing_profiles.sql";
    private static final List<FtsRow> EXPECTED_FTS = List.of(
            new FtsRow(11, "profilebody Café\r\nİstanbul 😀", "Profileheading Nested", "docs/legacy.md"),
            new FtsRow(29, "unchanged second chunk", "", "docs/legacy.md"));

    @TempDir
    Path root;

    @Test
    void createsFreshVersionThreeWithEmptyProfileStorageAndUsableSearch() throws Exception {
        Path database = root.resolve("nested/index.db");
        try (SqliteStorage storage = SqliteStorage.open(database, root)) {
            Connection connection = storage.connection();
            assertVersionThree(connection);
            assertEquals(0, scalar(connection, "SELECT count(*) FROM file_indexing_profiles"));
            seed(connection);
            assertEquals(EXPECTED_FTS, rows(connection));
            assertTrue(storage.files().findIndexingProfile(4).isEmpty());
            assertConsistent(connection);
        }

        byte[] before = Files.readAllBytes(database);
        try (SqliteStorage storage = SqliteStorage.openReadOnly(database, root)) {
            assertVersionThree(storage.connection());
            assertMatches(storage.connection(), "profilebody", 11);
        }
        assertArrayEquals(before, Files.readAllBytes(database));
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2})
    void upgradesLegacyDataWithoutInventingProfilesOrChangingSourceAndSearchRows(int version) throws Exception {
        Map<String, List<List<String>>> before;
        try (Connection connection = historical(version)) {
            seed(connection);
            before = sourceSnapshot(connection);
            if (version == 2) {
                assertEquals(EXPECTED_FTS, rows(connection));
            }
        }
        assertFalse(Files.exists(root.resolve("docs/legacy.md")));

        Path database = root.resolve("index.db");
        try (SqliteStorage storage = SqliteStorage.open(database, root)) {
            Connection connection = storage.connection();
            assertVersionThree(connection);
            assertEquals(before, sourceSnapshot(connection));
            assertEquals(EXPECTED_FTS, rows(connection));
            assertEquals(0, scalar(connection, "SELECT count(*) FROM file_indexing_profiles"));
            assertTrue(storage.files().findIndexingProfile(4).isEmpty());
            assertTrue(storage.files().findIndexingProfile(8).isEmpty());
            assertMatches(connection, "headings: profileheading", 11);
            assertMatches(connection, "content: unchanged", 29);
            assertConsistent(connection);
        }

        byte[] migrated = Files.readAllBytes(database);
        try (SqliteStorage storage = SqliteStorage.open(database, root)) {
            assertVersionThree(storage.connection());
            assertEquals(before, sourceSnapshot(storage.connection()));
            assertEquals(EXPECTED_FTS, rows(storage.connection()));
        }
        assertArrayEquals(migrated, Files.readAllBytes(database));
    }

    @Test
    void versionTwoUpgradeDoesNotExecuteTheVersionTwoMigrationAgain() throws Exception {
        try (Connection connection = openVersionTwo(root)) {
            seed(connection);
            SqliteSchemaInitializer.load(connection, rootUri())
                    .initialize(resource(V1_RESOURCE), "INVALID SQL;", resource(V3_RESOURCE), SqliteFtsSupport::verify);

            assertVersionThree(connection);
            assertEquals(EXPECTED_FTS, rows(connection));
            assertConsistent(connection);
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2})
    void readOnlyLegacyOpenRequestsMigrationWithoutChangingAnyBytes(int version) throws Exception {
        try (Connection connection = historical(version)) {
            seed(connection);
        }
        Path database = root.resolve("index.db");
        byte[] before = Files.readAllBytes(database);

        IndexAccessException failure =
                assertThrows(IndexAccessException.class, () -> SqliteStorage.openReadOnly(database, root));

        assertEquals(IndexAccessException.Reason.MIGRATION_REQUIRED, failure.reason());
        assertArrayEquals(before, Files.readAllBytes(database));
        try (Connection connection = raw(database)) {
            assertEquals(version, scalar(connection, "PRAGMA user_version"));
            assertEquals(version, scalar(connection, "SELECT index_format_version FROM index_metadata"));
            assertEquals(
                    0, scalar(connection, "SELECT count(*) FROM sqlite_schema WHERE name = 'file_indexing_profiles'"));
        }
    }

    @ParameterizedTest
    @CsvSource({
        "0,partial-schema",
        "1,partial-schema",
        "2,partial-schema",
        "1,after-schema",
        "2,after-schema",
        "1,wrong-trigger",
        "2,wrong-trigger",
        "1,after-version-update",
        "2,after-version-update"
    })
    void failedProfileMigrationRollsBackEveryStageAndCanBeRetried(int version, String failurePoint) throws Exception {
        if (version > 0) {
            try (Connection connection = historical(version)) {
                seed(connection);
            }
        }
        Path database = root.resolve("index.db");
        try (Connection connection = raw(database)) {
            byte[] before = Files.readAllBytes(database);
            String canonical = resource(V3_RESOURCE);
            String failingMigration =
                    switch (failurePoint) {
                        case "partial-schema" -> "CREATE TABLE file_indexing_profiles(file_id INTEGER); INVALID SQL;";
                        case "after-schema" -> canonical + "\nINSERT INTO missing_target VALUES (1);";
                        case "wrong-trigger" -> canonical.replace("WHERE file_id = NEW.file_id", "WHERE file_id = -1");
                        case "after-version-update" ->
                            canonical + "\nUPDATE index_metadata SET project_root_uri = 'wrong-owner';";
                        default -> throw new AssertionError(failurePoint);
                    };

            assertThrows(
                    SQLException.class,
                    () -> SqliteSchemaInitializer.load(connection, rootUri())
                            .initialize(
                                    resource(V1_RESOURCE),
                                    resource(V2_RESOURCE),
                                    failingMigration,
                                    SqliteFtsSupport::verify));

            assertEquals(version, scalar(connection, "PRAGMA user_version"));
            assertEquals(
                    0, scalar(connection, "SELECT count(*) FROM sqlite_schema WHERE name LIKE '%indexing_profile%'"));
            assertEquals(0, scalar(connection, "SELECT count(*) FROM temp.sqlite_schema"));
            if (version == 0) {
                assertEquals(0, scalar(connection, "SELECT count(*) FROM sqlite_schema"));
            } else {
                assertArrayEquals(before, Files.readAllBytes(database));
                assertEquals(version, scalar(connection, "SELECT index_format_version FROM index_metadata"));
                assertEquals(2, scalar(connection, "SELECT count(*) FROM chunks"));
                assertEquals(
                        version == 2 ? 1 : 0,
                        scalar(connection, "SELECT count(*) FROM sqlite_schema WHERE name = 'chunks_fts'"));
            }

            SqliteSchemaInitializer.load(connection, rootUri()).initialize();
            assertVersionThree(connection);
            assertEquals(0, scalar(connection, "SELECT count(*) FROM file_indexing_profiles"));
            if (version > 0) {
                assertEquals(EXPECTED_FTS, rows(connection));
            }
            assertConsistent(connection);
        }
    }

    @Test
    void validatesHistoricalVersionTwoSearchObjectsBeforeAnyCapabilityProbeOrMigration() throws Exception {
        try (Connection connection = openVersionTwo(root)) {
            seed(connection);
            execute(connection, "DROP TRIGGER chunks_fts_insert");
            byte[] before = Files.readAllBytes(root.resolve("index.db"));

            SQLException failure = assertThrows(
                    SQLException.class,
                    () -> SqliteSchemaInitializer.load(connection, rootUri())
                            .initialize(
                                    resource(V1_RESOURCE), resource(V2_RESOURCE), resource(V3_RESOURCE), ignored -> {
                                        throw new AssertionError(
                                                "Damaged V2 must be rejected before the capability probe");
                                    }));

            assertTrue(failure.getMessage().contains("chunks_fts_insert"));
            assertArrayEquals(before, Files.readAllBytes(root.resolve("index.db")));
            assertEquals(2, scalar(connection, "PRAGMA user_version"));
        }

        assertReadOnlyRejectedWithoutMutation(
                root.resolve("index.db"), root, IndexAccessException.Reason.CORRUPT_INDEX);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "file_indexing_profiles",
                "files_indexing_profile_update",
                "chunks_indexing_profile_insert",
                "chunks_indexing_profile_update",
                "chunks_indexing_profile_delete",
                "chunk_headings_indexing_profile_insert",
                "chunk_headings_indexing_profile_update",
                "chunk_headings_indexing_profile_delete",
                "chunk_attributes_indexing_profile_insert",
                "chunk_attributes_indexing_profile_update",
                "chunk_attributes_indexing_profile_delete"
            })
    void rejectsEveryMissingProfileSchemaObjectWithoutRepairingVersionThree(String object) throws Exception {
        Path database = root.resolve("index.db");
        try (SqliteStorage storage = SqliteStorage.open(database, root)) {
            seed(storage.connection());
            String type = object.equals("file_indexing_profiles") ? "TABLE" : "TRIGGER";
            execute(storage.connection(), "DROP " + type + " " + object);
        }
        assertBothModesRejectWithoutMutation(database, IndexAccessException.Reason.CORRUPT_INDEX);
    }

    @ParameterizedTest
    @ValueSource(strings = {"table", "trigger"})
    void rejectsAlteredProfileConstraintsAndTriggerBodies(String object) throws Exception {
        Path database = root.resolve("index.db");
        try (SqliteStorage storage = SqliteStorage.open(database, root)) {
            Connection connection = storage.connection();
            seed(connection);
            if (object.equals("table")) {
                execute(connection, "DROP TABLE file_indexing_profiles");
                execute(
                        connection,
                        "CREATE TABLE file_indexing_profiles(file_id INTEGER PRIMARY KEY,"
                                + " tokenizer_key TEXT, max_tokens INTEGER, overlap_tokens INTEGER)");
            } else {
                execute(connection, "DROP TRIGGER chunks_indexing_profile_update");
                execute(
                        connection,
                        "CREATE TRIGGER chunks_indexing_profile_update AFTER UPDATE ON chunks"
                                + " BEGIN SELECT 1; END;");
            }
        }
        assertBothModesRejectWithoutMutation(database, IndexAccessException.Reason.CORRUPT_INDEX);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3})
    void rejectsWrongOwnersAndUnknownFormatsWithoutMutation(int version) throws Exception {
        Path database = root.resolve("index.db");
        if (version < 3) {
            try (Connection connection = historical(version)) {
                seed(connection);
            }
        } else {
            try (SqliteStorage storage = SqliteStorage.open(database, root)) {
                seed(storage.connection());
            }
        }
        Path otherRoot = Files.createDirectory(root.resolve("other"));
        byte[] before = Files.readAllBytes(database);
        IndexAccessException wrongOwner =
                assertThrows(IndexAccessException.class, () -> SqliteStorage.open(database, otherRoot));
        assertEquals(IndexAccessException.Reason.WRONG_PROJECT, wrongOwner.reason());
        assertArrayEquals(before, Files.readAllBytes(database));
        assertReadOnlyRejectedWithoutMutation(database, otherRoot, IndexAccessException.Reason.WRONG_PROJECT);

        try (Connection connection = raw(database)) {
            execute(connection, "UPDATE index_metadata SET index_format_version = 99");
        }
        assertBothModesRejectWithoutMutation(database, IndexAccessException.Reason.INCOMPATIBLE);
    }

    @Test
    void rejectsFutureSchemaVersionWithoutMutation() throws Exception {
        Path database = root.resolve("index.db");
        try (SqliteStorage storage = SqliteStorage.open(database, root)) {
            seed(storage.connection());
            execute(storage.connection(), "PRAGMA user_version = 4");
        }
        assertBothModesRejectWithoutMutation(database, IndexAccessException.Reason.INCOMPATIBLE);
    }

    @ParameterizedTest
    @CsvSource({"1,1.5", "2,2.5", "3,3.5", "1,4294967297", "2,4294967298", "3,4294967299"})
    void rejectsFormatValuesThatWouldTruncateToAKnownVersion(int version, String invalidFormat) throws Exception {
        Path database = root.resolve("index.db");

        if (version < 3) {
            try (Connection connection = historical(version)) {
                seed(connection);
            }
        } else {
            try (SqliteStorage storage = SqliteStorage.open(database, root)) {
                seed(storage.connection());
            }
        }

        try (Connection connection = raw(database)) {
            execute(connection, "UPDATE index_metadata SET index_format_version = " + invalidFormat);
        }

        assertBothModesRejectWithoutMutation(database, IndexAccessException.Reason.INCOMPATIBLE);
    }

    @Test
    void concurrentVersionTwoOpenersMigrateOnlyOnceAndRetainTheSearchIndex() throws Exception {
        try (Connection connection = openVersionTwo(root)) {
            seed(connection);
        }
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> openAfter(ready, start));
            var second = executor.submit(() -> openAfter(ready, start));
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            assertEquals(EXPECTED_FTS, first.get(15, TimeUnit.SECONDS));
            assertEquals(EXPECTED_FTS, second.get(15, TimeUnit.SECONDS));
        } finally {
            start.countDown();
        }
    }

    private List<FtsRow> openAfter(CountDownLatch ready, CountDownLatch start) throws Exception {
        ready.countDown();
        assertTrue(start.await(5, TimeUnit.SECONDS));
        try (SqliteStorage storage = SqliteStorage.open(root.resolve("index.db"), root)) {
            assertVersionThree(storage.connection());
            assertEquals(0, scalar(storage.connection(), "SELECT count(*) FROM file_indexing_profiles"));
            return rows(storage.connection());
        }
    }

    private void assertBothModesRejectWithoutMutation(Path database, IndexAccessException.Reason reason)
            throws Exception {
        byte[] before = Files.readAllBytes(database);
        assertThrows(SQLException.class, () -> SqliteStorage.open(database, root));
        assertArrayEquals(before, Files.readAllBytes(database));
        assertReadOnlyRejectedWithoutMutation(database, root, reason);
    }

    private static void assertReadOnlyRejectedWithoutMutation(
            Path database, Path projectRoot, IndexAccessException.Reason reason) throws Exception {
        byte[] before = Files.readAllBytes(database);
        IndexAccessException failure =
                assertThrows(IndexAccessException.class, () -> SqliteStorage.openReadOnly(database, projectRoot));
        assertEquals(reason, failure.reason());
        assertArrayEquals(before, Files.readAllBytes(database));
    }

    private Connection historical(int version) throws Exception {
        return version == 1 ? openVersionOne(root) : openVersionTwo(root);
    }

    private String rootUri() throws Exception {
        return root.toRealPath().toUri().toASCIIString();
    }

    private static void seed(Connection connection) throws SQLException {
        insertFile(connection, 4, "docs/legacy.md");
        insertFile(connection, 8, "empty.txt");
        insertChunk(connection, 11, 4, 0, "profilebody Café\r\nİstanbul 😀");
        insertChunk(connection, 29, 4, 1, "unchanged second chunk");
        insertHeading(connection, 11, 1, "Nested");
        insertHeading(connection, 11, 0, "Profileheading");
        execute(connection, "INSERT INTO chunk_attributes VALUES (11, 'language', 'tr')");
        execute(connection, "INSERT INTO chunk_attributes VALUES (29, 'custom', 'untouched')");
    }

    private static void assertVersionThree(Connection connection) throws SQLException {
        assertEquals(3, scalar(connection, "PRAGMA user_version"));
        assertEquals(3, scalar(connection, "SELECT index_format_version FROM index_metadata"));
    }

    private static Map<String, List<List<String>>> sourceSnapshot(Connection connection) throws SQLException {
        Map<String, List<List<String>>> snapshot = new LinkedHashMap<>();
        for (String table : List.of("files", "chunks", "chunk_headings", "chunk_attributes")) {
            List<List<String>> values = new ArrayList<>();
            try (var statement = connection.createStatement();
                    var result = statement.executeQuery("SELECT * FROM " + table + " ORDER BY 1, 2")) {
                while (result.next()) {
                    List<String> row = new ArrayList<>();
                    for (int column = 1; column <= result.getMetaData().getColumnCount(); column++) {
                        row.add(result.getString(column));
                    }
                    values.add(List.copyOf(row));
                }
            }
            snapshot.put(table, List.copyOf(values));
        }
        return Map.copyOf(snapshot);
    }

    private static int scalar(Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement();
                var result = statement.executeQuery(sql)) {
            assertTrue(result.next());
            return result.getInt(1);
        }
    }

    private static Connection raw(Path database) throws SQLException {
        return JDBC.createConnection("jdbc:sqlite:" + database.toUri().toASCIIString(), new Properties());
    }
}
