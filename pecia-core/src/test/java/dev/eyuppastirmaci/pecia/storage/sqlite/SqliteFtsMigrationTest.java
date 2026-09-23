package dev.eyuppastirmaci.pecia.storage.sqlite;

import static dev.eyuppastirmaci.pecia.storage.sqlite.SqliteFtsTestSupport.assertConsistent;
import static dev.eyuppastirmaci.pecia.storage.sqlite.SqliteFtsTestSupport.assertMatches;
import static dev.eyuppastirmaci.pecia.storage.sqlite.SqliteFtsTestSupport.execute;
import static dev.eyuppastirmaci.pecia.storage.sqlite.SqliteFtsTestSupport.insertChunk;
import static dev.eyuppastirmaci.pecia.storage.sqlite.SqliteFtsTestSupport.insertFile;
import static dev.eyuppastirmaci.pecia.storage.sqlite.SqliteFtsTestSupport.insertHeading;
import static dev.eyuppastirmaci.pecia.storage.sqlite.SqliteFtsTestSupport.openVersionOne;
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
import org.junit.jupiter.params.provider.ValueSource;
import org.sqlite.JDBC;

class SqliteFtsMigrationTest {
    private static final String DOCUMENT_PATH = "docs/pathneedle 'quoted';-- İstanbul.md";
    private static final String SOURCE_PATH = "src/PaymentService.java";
    private static final String EMPTY_PATH = "empty/unused.txt";
    private static final String DOCUMENT_CONTENT = "bodyneedle Café\r\nİstanbul 😀 e\u0301";
    private static final List<FtsRow> EXPECTED_FTS = List.of(
            new FtsRow(11, "Second unchanged chunk.", "", DOCUMENT_PATH),
            new FtsRow(31, DOCUMENT_CONTENT, "headneedle Repeated Repeated", DOCUMENT_PATH),
            new FtsRow(97, "class PaymentService { }", "Payment service", SOURCE_PATH));

    @TempDir
    Path root;

    @Test
    void createsAFreshCurrentVersionDatabaseWithAnImmediatelyUsableIndex() throws Exception {
        Path database = root.resolve("nested/index.db");

        try (SqliteStorage storage = SqliteStorage.open(database, root)) {
            Connection connection = storage.connection();
            assertCurrentVersion(connection);
            assertEquals(List.of(), rows(connection));
            insertFile(connection, 5, "docs/freshpath.md");
            insertChunk(connection, 19, 5, 0, "freshbody");
            insertHeading(connection, 19, 0, "Freshheading");

            assertMatches(connection, "content: freshbody", 19);
            assertMatches(connection, "headings: freshheading", 19);
            assertMatches(connection, "source_path: freshpath", 19);
            assertConsistent(connection);
        }

        try (SqliteStorage storage = SqliteStorage.open(database, root)) {
            assertCurrentVersion(storage.connection());
            assertEquals(
                    List.of(new FtsRow(19, "freshbody", "Freshheading", "docs/freshpath.md")),
                    rows(storage.connection()));
            assertMatches(storage.connection(), "freshbody", 19);
        }
    }

    @Test
    void upgradesAnEmptyVersionOneDatabaseWithoutInventingSourceOrSearchRows() throws Exception {
        Map<String, List<List<String>>> before;
        try (Connection connection = openVersionOne(root)) {
            before = sourceSnapshot(connection);
            assertEquals(1, scalar(connection, "PRAGMA user_version"));
        }

        try (SqliteStorage storage = SqliteStorage.open(root.resolve("index.db"), root)) {
            Connection connection = storage.connection();
            assertCurrentVersion(connection);
            assertEquals(before, sourceSnapshot(connection));
            assertEquals(List.of(), rows(connection));
            assertMatches(connection, "anything");
            assertConsistent(connection);
        }
    }

    @Test
    void upgradesStoredDataWithoutSourceFilesAndPreservesEveryOriginalValue() throws Exception {
        Map<String, List<List<String>>> before;
        try (Connection connection = openVersionOne(root)) {
            populateVersionOne(connection);
            before = sourceSnapshot(connection);
            assertEquals(1, scalar(connection, "PRAGMA user_version"));
            assertEquals(1, scalar(connection, "SELECT index_format_version FROM index_metadata"));
        }
        assertSourceFilesAbsent();

        try (SqliteStorage storage = SqliteStorage.open(root.resolve("index.db"), root)) {
            Connection connection = storage.connection();
            assertCurrentVersion(connection);
            assertEquals(
                    before,
                    sourceSnapshot(connection),
                    "Migration must preserve IDs, hashes, types, text, line ranges, heading order and every"
                            + " attribute");
            assertEquals(EXPECTED_FTS, rows(connection));
            assertMigratedMatches(connection);
            assertConsistent(connection);
            assertEquals(
                    List.of(31L, 11L),
                    storage.chunks().findByFileId(4).stream()
                            .map(chunk -> chunk.id())
                            .toList());
            assertTrue(storage.chunks().findByFileId(42).isEmpty());
        }

        assertSourceFilesAbsent();
        try (SqliteStorage storage = SqliteStorage.open(root.resolve("index.db"), root)) {
            assertEquals(before, sourceSnapshot(storage.connection()));
            assertEquals(EXPECTED_FTS, rows(storage.connection()));
            assertMigratedMatches(storage.connection());
        }
    }

    @Test
    void repeatedCurrentVersionOpeningsDoNotRewriteOrDuplicateTheExistingIndex() throws Exception {
        Path database = root.resolve("index.db");
        Map<String, List<List<String>>> before;
        try (Connection connection = openVersionOne(root)) {
            populateVersionOne(connection);
            before = sourceSnapshot(connection);
        }
        try (SqliteStorage storage = SqliteStorage.open(database, root)) {
            assertCurrentVersion(storage.connection());
            assertEquals(EXPECTED_FTS, rows(storage.connection()));
        }
        byte[] migratedDatabase = Files.readAllBytes(database);

        for (int attempt = 0; attempt < 3; attempt++) {
            try (SqliteStorage storage = SqliteStorage.open(database, root)) {
                Connection connection = storage.connection();
                assertCurrentVersion(connection);
                assertEquals(before, sourceSnapshot(connection));
                assertEquals(EXPECTED_FTS, rows(connection));
                assertMigratedMatches(connection);
            }

            // A capability probe may write TEMP objects; reopening must not rebuild or write the main
            // database.
            assertArrayEquals(migratedDatabase, Files.readAllBytes(database), "Read-only reopen attempt " + attempt);
        }
    }

    @Test
    void concurrentOpenersUpgradeTheSameVersionOneDatabaseOnce() throws Exception {
        Map<String, List<List<String>>> before;
        try (Connection connection = openVersionOne(root)) {
            populateVersionOne(connection);
            before = sourceSnapshot(connection);
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

        try (SqliteStorage storage = SqliteStorage.open(root.resolve("index.db"), root)) {
            assertCurrentVersion(storage.connection());
            assertEquals(before, sourceSnapshot(storage.connection()));
            assertEquals(EXPECTED_FTS, rows(storage.connection()));
            assertMigratedMatches(storage.connection());
            assertConsistent(storage.connection());
        }
    }

    @Test
    void refusesAnotherProjectsVersionOneDatabaseBeforeMigrationAndAllowsTheOwnerToRetry() throws Exception {
        Path database = root.resolve("index.db");
        Map<String, List<List<String>>> before;
        try (Connection connection = openVersionOne(root)) {
            populateVersionOne(connection);
            before = sourceSnapshot(connection);
        }
        Path anotherProject = Files.createDirectory(root.resolve("another-project"));
        byte[] originalDatabase = Files.readAllBytes(database);

        SQLException failure = assertThrows(
                SQLException.class,
                () -> SqliteStorage.open(database, anotherProject).close());
        assertTrue(failure.getMessage().contains("another project root"));
        assertArrayEquals(originalDatabase, Files.readAllBytes(database));
        try (Connection connection = raw(database)) {
            assertEquals(1, scalar(connection, "PRAGMA user_version"));
            assertEquals(1, scalar(connection, "SELECT index_format_version FROM index_metadata"));
            assertEquals(0, scalar(connection, "SELECT count(*) FROM sqlite_schema WHERE name = 'chunks_fts'"));
            assertEquals(before, sourceSnapshot(connection));
        }

        try (SqliteStorage storage = SqliteStorage.open(database, root)) {
            assertCurrentVersion(storage.connection());
            assertEquals(before, sourceSnapshot(storage.connection()));
            assertEquals(EXPECTED_FTS, rows(storage.connection()));
            assertMigratedMatches(storage.connection());
        }
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "DROP TABLE chunk_attributes",
                "ALTER TABLE chunks RENAME COLUMN content TO wrong",
                "DELETE FROM index_metadata",
                "UPDATE index_metadata SET index_format_version = 2",
                "UPDATE index_metadata SET index_format_version = 99"
            })
    void refusesMalformedOrFormatIncompatibleVersionOneWithoutChangingIt(String damage) throws Exception {
        Path database = root.resolve("index.db");
        try (Connection connection = openVersionOne(root)) {
            populateVersionOne(connection);
            execute(connection, damage);
        }
        byte[] damagedDatabase = Files.readAllBytes(database);

        assertThrows(
                SQLException.class, () -> SqliteStorage.open(database, root).close());

        assertArrayEquals(damagedDatabase, Files.readAllBytes(database));
        try (Connection connection = raw(database)) {
            assertEquals(1, scalar(connection, "PRAGMA user_version"));
            assertEquals(0, scalar(connection, "SELECT count(*) FROM sqlite_schema WHERE name = 'chunks_fts'"));
        }
    }

    private List<FtsRow> openAfter(CountDownLatch ready, CountDownLatch start) throws Exception {
        ready.countDown();
        assertTrue(start.await(5, TimeUnit.SECONDS));
        try (SqliteStorage storage = SqliteStorage.open(root.resolve("index.db"), root)) {
            assertCurrentVersion(storage.connection());
            assertMigratedMatches(storage.connection());
            return rows(storage.connection());
        }
    }

    private void assertSourceFilesAbsent() {
        for (String path : List.of(DOCUMENT_PATH, SOURCE_PATH, EMPTY_PATH)) {
            assertFalse(
                    Files.exists(root.resolve(path)), "The migration fixture deliberately has no source file: " + path);
        }
    }

    private static void populateVersionOne(Connection connection) throws SQLException {
        insertFile(connection, 4, DOCUMENT_PATH);
        insertFile(connection, 9, SOURCE_PATH);
        insertFile(connection, 42, EMPTY_PATH);
        execute(connection, "UPDATE files SET content_hash = ? WHERE id = 4", "b".repeat(64));
        execute(
                connection,
                "UPDATE files SET document_type = 'SOURCE_CODE', content_hash = ? WHERE id = 9",
                "c".repeat(64));
        execute(
                connection,
                "UPDATE files SET document_type = 'PLAIN_TEXT', content_hash = ? WHERE id = 42",
                "d".repeat(64));
        insertChunk(connection, 31, 4, 0, DOCUMENT_CONTENT);
        insertChunk(connection, 11, 4, 1, "Second unchanged chunk.");
        insertChunk(connection, 97, 9, 0, "class PaymentService { }");
        execute(connection, "UPDATE chunks SET start_line = 10, end_line = 12 WHERE id = 31");
        execute(connection, "UPDATE chunks SET start_line = 15, end_line = 15 WHERE id = 11");
        execute(connection, "UPDATE chunks SET start_line = 3, end_line = 3 WHERE id = 97");
        insertHeading(connection, 31, 2, "Repeated");
        insertHeading(connection, 31, 0, "headneedle");
        insertHeading(connection, 31, 1, "Repeated");
        insertHeading(connection, 97, 0, "Payment service");
        execute(connection, "INSERT INTO chunk_attributes VALUES (?, ?, ?)", 31, "startOffset", "12");
        execute(
                connection,
                "INSERT INTO chunk_attributes VALUES (?, ?, ?)",
                31,
                "endOffset",
                Integer.toString(12 + DOCUMENT_CONTENT.length()));
        execute(connection, "INSERT INTO chunk_attributes VALUES (?, ?, ?)", 31, "custom", "hiddenneedle ' \n\r\t😀");
        execute(connection, "INSERT INTO chunk_attributes VALUES (?, ?, ?)", 31, "empty", "");
        execute(connection, "INSERT INTO chunk_attributes VALUES (?, ?, ?)", 97, "language", "java");
    }

    private static void assertMigratedMatches(Connection connection) throws SQLException {
        assertMatches(connection, "content: bodyneedle", 31);
        assertMatches(connection, "content: cafe", 31);
        assertMatches(connection, "headings: headneedle", 31);
        assertMatches(connection, "headings: \"headneedle repeated repeated\"", 31);
        assertMatches(connection, "source_path: pathneedle", 11, 31);
        assertMatches(connection, "source_path: paymentservice", 97);
        assertMatches(connection, "content: pathneedle");
        assertMatches(connection, "hiddenneedle");
        assertMatches(connection, "unused");
    }

    private static void assertCurrentVersion(Connection connection) throws SQLException {
        assertEquals(4, scalar(connection, "PRAGMA user_version"));
        assertEquals(4, scalar(connection, "SELECT index_format_version FROM index_metadata"));
        assertEquals(1, scalar(connection, "PRAGMA foreign_keys"));
    }

    private static Map<String, List<List<String>>> sourceSnapshot(Connection connection) throws SQLException {
        Map<String, List<List<String>>> snapshot = new LinkedHashMap<>();
        for (String table : List.of("files", "chunks", "chunk_headings", "chunk_attributes")) {
            snapshot.put(table, queryRows(connection, "SELECT * FROM " + table + " ORDER BY 1, 2"));
        }
        // Only the index format marker changes during migration; ownership must remain identical.
        snapshot.put("index_owner", queryRows(connection, "SELECT singleton, project_root_uri FROM index_metadata"));
        return snapshot;
    }

    private static List<List<String>> queryRows(Connection connection, String sql) throws SQLException {
        List<List<String>> result = new ArrayList<>();
        try (var statement = connection.createStatement();
                var rows = statement.executeQuery(sql)) {
            int columns = rows.getMetaData().getColumnCount();
            while (rows.next()) {
                List<String> values = new ArrayList<>();
                for (int column = 1; column <= columns; column++) {
                    values.add(rows.getString(column));
                }
                result.add(List.copyOf(values));
            }
        }
        return List.copyOf(result);
    }

    private static int scalar(Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement();
                var rows = statement.executeQuery(sql)) {
            assertTrue(rows.next());
            return rows.getInt(1);
        }
    }

    private static Connection raw(Path database) throws SQLException {
        return JDBC.createConnection("jdbc:sqlite:" + database.toUri().toASCIIString(), new Properties());
    }
}
