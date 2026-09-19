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

class SqliteChunkIdentityMigrationTest {
    private static final String V3_RESOURCE = "/db/migration/V3__add_file_indexing_profiles.sql";
    private static final String V4_RESOURCE = "/db/migration/V4__add_chunk_identities.sql";
    private static final List<FtsRow> EXPECTED_FTS = List.of(
            new FtsRow(11, "identitybody Café\r\nİstanbul 😀", "Identityheading Nested", "docs/legacy.md"),
            new FtsRow(29, "unchanged second chunk", "", "docs/legacy.md"));

    @TempDir
    Path root;

    @Test
    void createsFreshVersionFourWithEmptyIdentityStorageAndUsableSearch() throws Exception {
        Path database = root.resolve("nested/index.db");
        try (SqliteStorage storage = SqliteStorage.open(database, root)) {
            Connection connection = storage.connection();
            assertVersionFour(connection);
            assertNoIdentities(connection);
            assertEquals(0, scalar(connection, "SELECT count(*) FROM file_indexing_profiles"));
            seed(connection);
            assertNoIdentities(connection);
            assertEquals(EXPECTED_FTS, rows(connection));
            assertConsistent(connection);
        }

        byte[] before = Files.readAllBytes(database);
        try (SqliteStorage storage = SqliteStorage.openReadOnly(database, root)) {
            assertVersionFour(storage.connection());
            assertNoIdentities(storage.connection());
            assertMatches(storage.connection(), "identitybody", 11);
        }
        assertArrayEquals(before, Files.readAllBytes(database));
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3})
    void upgradesHistoricalDataWithoutReadingSourcesOrInventingChunkingProfiles(int version) throws Exception {
        Map<String, List<List<String>>> before;
        List<List<String>> legacyProfiles;
        try (Connection connection = historical(version)) {
            seedHistorical(connection, version);
            before = sourceSnapshot(connection);
            legacyProfiles = version == 3 ? tableRows(connection, "file_indexing_profiles") : List.of();
            if (version >= 2) {
                assertEquals(EXPECTED_FTS, rows(connection));
            }
        }
        assertFalse(Files.exists(root.resolve("docs/legacy.md")));
        assertFalse(Files.exists(root.resolve("empty.txt")));

        Path database = root.resolve("index.db");
        try (SqliteStorage storage = SqliteStorage.open(database, root)) {
            Connection connection = storage.connection();
            assertVersionFour(connection);
            assertEquals(before, sourceSnapshot(connection));
            assertEquals(legacyProfiles, tableRows(connection, "file_indexing_profiles"));
            assertNoIdentities(connection);
            assertEquals(EXPECTED_FTS, rows(connection));
            assertMatches(connection, "headings: identityheading", 11);
            assertMatches(connection, "content: unchanged", 29);
            assertConsistent(connection);
        }

        byte[] migrated = Files.readAllBytes(database);
        try (SqliteStorage storage = SqliteStorage.open(database, root)) {
            assertVersionFour(storage.connection());
            assertEquals(before, sourceSnapshot(storage.connection()));
            assertEquals(legacyProfiles, tableRows(storage.connection(), "file_indexing_profiles"));
            assertNoIdentities(storage.connection());
            assertEquals(EXPECTED_FTS, rows(storage.connection()));
        }
        assertArrayEquals(migrated, Files.readAllBytes(database));
    }

    @Test
    void versionThreeUpgradeSkipsEarlierMigrationsAndPreservesLegacyProfiles() throws Exception {
        try (Connection connection = openVersionThree(root)) {
            seedHistorical(connection, 3);
            List<List<String>> profiles = tableRows(connection, "file_indexing_profiles");

            SqliteSchemaInitializer.load(connection, rootUri())
                    .initialize(
                            "INVALID V1 SQL;",
                            "INVALID V2 SQL;",
                            "INVALID V3 SQL;",
                            resource(V4_RESOURCE),
                            SqliteFtsSupport::verify);

            assertVersionFour(connection);
            assertEquals(profiles, tableRows(connection, "file_indexing_profiles"));
            assertNoIdentities(connection);
            assertEquals(EXPECTED_FTS, rows(connection));
            assertConsistent(connection);
        }
    }

    @Test
    void healthyVersionFourReopenSkipsAllMigrationScriptsWithoutPersistentWrites() throws Exception {
        Path database = root.resolve("index.db");
        try (SqliteStorage storage = SqliteStorage.open(database, root)) {
            seedHistorical(storage.connection(), 3);
        }
        byte[] before = Files.readAllBytes(database);

        try (Connection connection = raw(database)) {
            SqliteSchemaInitializer.load(connection, rootUri())
                    .initialize(
                            "INVALID V1 SQL;",
                            "INVALID V2 SQL;",
                            "INVALID V3 SQL;",
                            "INVALID V4 SQL;",
                            SqliteFtsSupport::verify);

            assertVersionFour(connection);
            assertEquals(EXPECTED_FTS, rows(connection));
            assertEquals(2, scalar(connection, "SELECT count(*) FROM file_indexing_profiles"));
            assertNoIdentities(connection);
        }
        assertArrayEquals(before, Files.readAllBytes(database));

        try (SqliteStorage storage = SqliteStorage.openReadOnly(database, root)) {
            assertMatches(storage.connection(), "identitybody", 11);
            assertNoIdentities(storage.connection());
        }
        assertArrayEquals(before, Files.readAllBytes(database));
    }

    @Test
    void readOnlyVersionThreeRequestsMigrationWithoutChangingAnyBytes() throws Exception {
        Map<String, List<List<String>>> sources;
        List<List<String>> profiles;
        try (Connection connection = openVersionThree(root)) {
            seedHistorical(connection, 3);
            sources = sourceSnapshot(connection);
            profiles = tableRows(connection, "file_indexing_profiles");
        }
        Path database = root.resolve("index.db");
        byte[] before = Files.readAllBytes(database);

        IndexAccessException failure =
                assertThrows(IndexAccessException.class, () -> SqliteStorage.openReadOnly(database, root));

        assertEquals(IndexAccessException.Reason.MIGRATION_REQUIRED, failure.reason());
        assertArrayEquals(before, Files.readAllBytes(database));
        try (Connection connection = raw(database)) {
            assertEquals(3, scalar(connection, "PRAGMA user_version"));
            assertEquals(3, scalar(connection, "SELECT index_format_version FROM index_metadata"));
            assertEquals(sources, sourceSnapshot(connection));
            assertEquals(profiles, tableRows(connection, "file_indexing_profiles"));
            assertEquals(EXPECTED_FTS, rows(connection));
            assertIdentitySchemaAbsent(connection);
        }
    }

    @ParameterizedTest
    @CsvSource({
        "0,partial-schema",
        "1,partial-schema",
        "2,partial-schema",
        "3,partial-schema",
        "0,after-schema",
        "1,after-schema",
        "2,after-schema",
        "3,after-schema",
        "0,altered-schema",
        "1,altered-schema",
        "2,altered-schema",
        "3,altered-schema",
        "0,after-version-update",
        "1,after-version-update",
        "2,after-version-update",
        "3,after-version-update",
        "3,damaged-legacy-profile",
        "3,damaged-fts"
    })
    void failedIdentityMigrationRollsBackEveryStageAndCanBeRetried(int version, String failurePoint) throws Exception {
        if (version > 0) {
            try (Connection connection = historical(version)) {
                seedHistorical(connection, version);
            }
        }
        Path database = root.resolve("index.db");
        try (Connection connection = raw(database)) {
            byte[] before = Files.readAllBytes(database);
            List<List<String>> schemaBefore = schemaSnapshot(connection);
            Map<String, List<List<String>>> sourcesBefore = version > 0 ? sourceSnapshot(connection) : Map.of();
            List<List<String>> profilesBefore =
                    version == 3 ? tableRows(connection, "file_indexing_profiles") : List.of();
            String canonical = resource(V4_RESOURCE);
            String failingMigration =
                    switch (failurePoint) {
                        case "partial-schema" -> "CREATE TABLE file_chunking_profiles(file_id INTEGER); INVALID SQL;";
                        case "after-schema" -> canonical + "\nINSERT INTO missing_target VALUES (1);";
                        case "altered-schema" -> canonical.replace("file_id > 0", "file_id >= 0");
                        case "after-version-update" ->
                            canonical + "\nUPDATE index_metadata SET project_root_uri = 'wrong-owner';";
                        case "damaged-legacy-profile" -> canonical + "\nDROP TRIGGER chunks_indexing_profile_update;";
                        case "damaged-fts" -> canonical + "\nDROP TRIGGER chunks_fts_insert;";
                        default -> throw new AssertionError(failurePoint);
                    };
            assertFalse(canonical.equals(failingMigration));

            assertThrows(
                    SQLException.class,
                    () -> SqliteSchemaInitializer.load(connection, rootUri())
                            .initialize(
                                    resource(V1_RESOURCE),
                                    resource(V2_RESOURCE),
                                    resource(V3_RESOURCE),
                                    failingMigration,
                                    SqliteFtsSupport::verify));

            assertEquals(version, scalar(connection, "PRAGMA user_version"));
            assertEquals(schemaBefore, schemaSnapshot(connection));
            assertEquals(0, scalar(connection, "SELECT count(*) FROM temp.sqlite_schema"));
            assertIdentitySchemaAbsent(connection);
            if (version > 0) {
                assertArrayEquals(before, Files.readAllBytes(database));
                assertEquals(version, scalar(connection, "SELECT index_format_version FROM index_metadata"));
                assertEquals(sourcesBefore, sourceSnapshot(connection));
                if (version >= 2) {
                    assertEquals(EXPECTED_FTS, rows(connection));
                }
                if (version == 3) {
                    assertEquals(profilesBefore, tableRows(connection, "file_indexing_profiles"));
                }
            }

            SqliteSchemaInitializer.load(connection, rootUri()).initialize();
            assertVersionFour(connection);
            assertNoIdentities(connection);
            assertEquals(profilesBefore, tableRows(connection, "file_indexing_profiles"));
            if (version > 0) {
                assertEquals(sourcesBefore, sourceSnapshot(connection));
                assertEquals(EXPECTED_FTS, rows(connection));
            }
            assertConsistent(connection);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"file_indexing_profiles", "chunks_indexing_profile_update", "chunks_fts_insert"})
    void rejectsDamagedHistoricalVersionThreeBeforeCapabilityProbeOrMigration(String object) throws Exception {
        Path database = root.resolve("index.db");
        try (Connection connection = openVersionThree(root)) {
            seedHistorical(connection, 3);
            execute(connection, "DROP " + (object.equals("file_indexing_profiles") ? "TABLE " : "TRIGGER ") + object);
            byte[] before = Files.readAllBytes(database);

            SQLException failure = assertThrows(
                    SQLException.class,
                    () -> SqliteSchemaInitializer.load(connection, rootUri())
                            .initialize(
                                    resource(V1_RESOURCE),
                                    resource(V2_RESOURCE),
                                    resource(V3_RESOURCE),
                                    resource(V4_RESOURCE),
                                    ignored -> {
                                        throw new AssertionError(
                                                "Damaged V3 must be rejected before capability probing");
                                    }));

            assertTrue(failure.getMessage().contains(object));
            assertArrayEquals(before, Files.readAllBytes(database));
            assertEquals(3, scalar(connection, "PRAGMA user_version"));
            assertIdentitySchemaAbsent(connection);
        }

        byte[] before = Files.readAllBytes(database);
        IndexAccessException failure =
                assertThrows(IndexAccessException.class, () -> SqliteStorage.openReadOnly(database, root));
        assertEquals(IndexAccessException.Reason.CORRUPT_INDEX, failure.reason());
        assertArrayEquals(before, Files.readAllBytes(database));
    }

    @Test
    void concurrentVersionThreeOpenersMigrateOnceAndRetainSourceAndSearchData() throws Exception {
        Map<String, List<List<String>>> sources;
        List<List<String>> profiles;
        try (Connection connection = openVersionThree(root)) {
            seedHistorical(connection, 3);
            sources = sourceSnapshot(connection);
            profiles = tableRows(connection, "file_indexing_profiles");
        }
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> openAfter(ready, start, sources, profiles));
            var second = executor.submit(() -> openAfter(ready, start, sources, profiles));
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            assertEquals(EXPECTED_FTS, first.get(15, TimeUnit.SECONDS));
            assertEquals(EXPECTED_FTS, second.get(15, TimeUnit.SECONDS));
        } finally {
            start.countDown();
        }
    }

    private List<FtsRow> openAfter(
            CountDownLatch ready,
            CountDownLatch start,
            Map<String, List<List<String>>> sources,
            List<List<String>> profiles)
            throws Exception {
        ready.countDown();
        assertTrue(start.await(5, TimeUnit.SECONDS));
        try (SqliteStorage storage = SqliteStorage.open(root.resolve("index.db"), root)) {
            Connection connection = storage.connection();
            assertVersionFour(connection);
            assertNoIdentities(connection);
            assertEquals(sources, sourceSnapshot(connection));
            assertEquals(profiles, tableRows(connection, "file_indexing_profiles"));
            return rows(connection);
        }
    }

    private Connection historical(int version) throws Exception {
        return switch (version) {
            case 1 -> openVersionOne(root);
            case 2 -> openVersionTwo(root);
            case 3 -> openVersionThree(root);
            default -> throw new AssertionError(version);
        };
    }

    private String rootUri() throws Exception {
        return root.toRealPath().toUri().toASCIIString();
    }

    private static void seedHistorical(Connection connection, int version) throws SQLException {
        seed(connection);
        if (version >= 3) {
            execute(connection, "INSERT INTO file_indexing_profiles VALUES (4, 'legacy-tokenizer', 128, 24)");
            execute(connection, "INSERT INTO file_indexing_profiles VALUES (8, 'other-tokenizer', 96, 0)");
        }
    }

    private static void seed(Connection connection) throws SQLException {
        insertFile(connection, 4, "docs/legacy.md");
        insertFile(connection, 8, "empty.txt");
        execute(connection, "UPDATE files SET content_hash = ? WHERE id = 8", "b".repeat(64));
        insertChunk(connection, 11, 4, 0, "identitybody Café\r\nİstanbul 😀");
        insertChunk(connection, 29, 4, 1, "unchanged second chunk");
        execute(connection, "UPDATE chunks SET start_line = 5, end_line = 9 WHERE id = 29");
        insertHeading(connection, 11, 1, "Nested");
        insertHeading(connection, 11, 0, "Identityheading");
        execute(connection, "INSERT INTO chunk_attributes VALUES (11, 'language', 'tr')");
        execute(connection, "INSERT INTO chunk_attributes VALUES (29, 'custom', '')");
    }

    private static void assertVersionFour(Connection connection) throws SQLException {
        assertEquals(4, scalar(connection, "PRAGMA user_version"));
        assertEquals(4, scalar(connection, "SELECT index_format_version FROM index_metadata"));
    }

    private static void assertNoIdentities(Connection connection) throws SQLException {
        assertEquals(0, scalar(connection, "SELECT count(*) FROM file_chunking_profiles"));
        assertEquals(0, scalar(connection, "SELECT count(*) FROM chunk_identities"));
    }

    private static void assertIdentitySchemaAbsent(Connection connection) throws SQLException {
        assertEquals(
                0,
                scalar(
                        connection,
                        "SELECT count(*) FROM sqlite_schema WHERE name IN ('file_chunking_profiles',"
                                + " 'chunk_identities')"));
    }

    private static Map<String, List<List<String>>> sourceSnapshot(Connection connection) throws SQLException {
        Map<String, List<List<String>>> snapshot = new LinkedHashMap<>();
        for (String table : List.of("files", "chunks", "chunk_headings", "chunk_attributes")) {
            snapshot.put(table, tableRows(connection, table));
        }
        return Map.copyOf(snapshot);
    }

    private static List<List<String>> schemaSnapshot(Connection connection) throws SQLException {
        return queryRows(connection, "SELECT type, name, tbl_name, coalesce(sql, '') FROM sqlite_schema ORDER BY 1, 2");
    }

    private static List<List<String>> tableRows(Connection connection, String table) throws SQLException {
        return queryRows(connection, "SELECT * FROM " + table + " ORDER BY 1, 2");
    }

    private static List<List<String>> queryRows(Connection connection, String sql) throws SQLException {
        List<List<String>> values = new ArrayList<>();
        try (var statement = connection.createStatement();
                var result = statement.executeQuery(sql)) {
            while (result.next()) {
                List<String> row = new ArrayList<>();
                for (int column = 1; column <= result.getMetaData().getColumnCount(); column++) {
                    row.add(result.getString(column));
                }
                values.add(List.copyOf(row));
            }
        }
        return List.copyOf(values);
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
