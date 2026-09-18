package dev.eyuppastirmaci.pecia.storage.sqlite;

import static dev.eyuppastirmaci.pecia.storage.sqlite.SqliteFtsTestSupport.V2_RESOURCE;
import static dev.eyuppastirmaci.pecia.storage.sqlite.SqliteFtsTestSupport.applyFts;
import static dev.eyuppastirmaci.pecia.storage.sqlite.SqliteFtsTestSupport.applyScript;
import static dev.eyuppastirmaci.pecia.storage.sqlite.SqliteFtsTestSupport.assertConsistent;
import static dev.eyuppastirmaci.pecia.storage.sqlite.SqliteFtsTestSupport.assertMatches;
import static dev.eyuppastirmaci.pecia.storage.sqlite.SqliteFtsTestSupport.execute;
import static dev.eyuppastirmaci.pecia.storage.sqlite.SqliteFtsTestSupport.insertChunk;
import static dev.eyuppastirmaci.pecia.storage.sqlite.SqliteFtsTestSupport.insertFile;
import static dev.eyuppastirmaci.pecia.storage.sqlite.SqliteFtsTestSupport.insertHeading;
import static dev.eyuppastirmaci.pecia.storage.sqlite.SqliteFtsTestSupport.openVersionOne;
import static dev.eyuppastirmaci.pecia.storage.sqlite.SqliteFtsTestSupport.resource;
import static dev.eyuppastirmaci.pecia.storage.sqlite.SqliteFtsTestSupport.rows;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.eyuppastirmaci.pecia.storage.sqlite.SqliteFtsTestSupport.FtsRow;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.sqlite.JDBC;

class SqliteFtsSchemaTest {
    @TempDir
    Path root;

    @Test
    void createsTheSearchColumnsAndPinnedTokenizerOnAnEmptyVersionOneDatabase() throws Exception {
        try (Connection connection = openVersionOne(root)) {
            applyFts(connection);
            assertEquals(List.of(), rows(connection));
            assertEquals(List.of("content", "headings", "source_path"), columnNames(connection));

            try (var statement = connection.createStatement();
                    var result = statement.executeQuery("SELECT sql FROM sqlite_schema WHERE name = 'chunks_fts'")) {
                assertTrue(result.next());
                String ddl = result.getString(1);
                assertTrue(ddl.contains("USING fts5("));
                assertTrue(ddl.contains("tokenize = '" + SqliteFtsSupport.TOKENIZER + "'"));
                assertTrue(ddl.contains("detail = full"));
                assertTrue(ddl.contains("columnsize = 1"));
            }

            insertFile(connection, 1, "docs/tokenizer.md");
            insertChunk(connection, 17, 1, 0, "bộ payment validation");
            assertMatches(connection, "content: bo", 17);
            assertMatches(connection, "content: \"payment validation\"", 17);
            assertMatches(connection, "content: \"validation payment\"");
            assertEquals(List.of(new FtsRow(17, "bộ payment validation", "", "docs/tokenizer.md")), rows(connection));
            assertConsistent(connection);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void backfillsAllFieldsWithoutChangingSourceRecordsAndPreservesThemOnReopen(boolean reverseUnordered)
            throws Exception {
        String path = "docs/pathonly 'quoted';-- İstanbul.md";
        String content = "bodyonly café\r\nİstanbul 😀 e\u0301";
        List<FtsRow> expected = List.of(
                new FtsRow(11, "secondbody", "", path),
                new FtsRow(31, content, "headonly Repeated Repeated", path),
                new FtsRow(97, "untouchedbody", "Other", "other/untouched.md"));
        Map<String, List<List<String>>> source;

        try (Connection connection = openVersionOne(root)) {
            insertFile(connection, 4, path);
            insertFile(connection, 8, "other/untouched.md");
            insertFile(connection, 9, "docs/emptyfile.md");
            insertChunk(connection, 31, 4, 0, content);
            insertChunk(connection, 11, 4, 1, "secondbody");
            insertChunk(connection, 97, 8, 0, "untouchedbody");
            insertHeading(connection, 31, 2, "Repeated");
            insertHeading(connection, 31, 0, "headonly");
            insertHeading(connection, 31, 1, "Repeated");
            insertHeading(connection, 97, 0, "Other");
            execute(connection, "INSERT INTO chunk_attributes VALUES (?, ?, ?)", 31, "custom", "hiddenneedle");
            execute(connection, "UPDATE chunks SET start_line = 5, end_line = 6 WHERE id = 31");
            source = sourceSnapshot(connection);
            execute(connection, "PRAGMA reverse_unordered_selects = " + (reverseUnordered ? "ON" : "OFF"));

            applyFts(connection);

            assertEquals(source, sourceSnapshot(connection));
            assertEquals(expected, rows(connection));
            assertBackfilledMatches(connection);
            assertConsistent(connection);
            // Script activation/version management belongs to stage 13.3, not this SQL resource.
            assertEquals(1, scalar(connection, "PRAGMA user_version"));
            assertEquals(1, scalar(connection, "SELECT index_format_version FROM index_metadata"));
        }

        // Read the script-only fixture directly: the SQL resource deliberately leaves version markers
        // with its caller.
        try (Connection connection = JDBC.createConnection(
                "jdbc:sqlite:" + root.resolve("index.db").toUri().toASCIIString(), new Properties())) {
            assertEquals(source, sourceSnapshot(connection));
            assertEquals(expected, rows(connection));
            assertBackfilledMatches(connection);
            assertConsistent(connection);
        }
    }

    @Test
    void backfillAndLiveTriggersProduceIdenticalRowsIncludingRepeatedOrderedHeadings() throws Exception {
        try (Connection connection = openVersionOne(root)) {
            insertFile(connection, 1, "docs/identity.md");
            insertChunk(connection, 23, 1, 0, "identitybody");
            insertHeading(connection, 23, 2, "Repeated");
            insertHeading(connection, 23, 0, "Top");
            insertHeading(connection, 23, 1, "Repeated");
            applyFts(connection);
            var backfilled = rows(connection);
            assertEquals(
                    List.of(new FtsRow(23, "identitybody", "Top Repeated Repeated", "docs/identity.md")), backfilled);

            execute(connection, "DELETE FROM chunks WHERE id = 23");
            assertMatches(connection, "identitybody");
            assertEquals(List.of(), rows(connection));
            insertChunk(connection, 23, 1, 0, "identitybody");
            insertHeading(connection, 23, 1, "Repeated");
            insertHeading(connection, 23, 2, "Repeated");
            insertHeading(connection, 23, 0, "Top");

            assertEquals(backfilled, rows(connection));
            assertMatches(connection, "headings: \"Top Repeated Repeated\"", 23);
            assertConsistent(connection);
        }
    }

    @Test
    void wholeScriptCanBeRolledBackAfterBackfillAndThenRetried() throws Exception {
        try (Connection connection = openVersionOne(root)) {
            insertFile(connection, 1, "docs/retry.md");
            insertChunk(connection, 9, 1, 0, "retrybody");
            insertHeading(connection, 9, 0, "RetryHeading");
            var source = sourceSnapshot(connection);
            var schema = schemaSnapshot(connection);

            assertThrows(
                    SQLException.class,
                    () -> applyScript(
                            connection, resource(V2_RESOURCE) + "\nINSERT INTO missing_backfill_target VALUES (1);"));

            assertEquals(source, sourceSnapshot(connection));
            assertEquals(
                    schema,
                    schemaSnapshot(connection),
                    "Rollback must remove the virtual table, shadow tables and triggers");
            assertEquals(1, scalar(connection, "PRAGMA user_version"));
            applyFts(connection);
            assertMatches(connection, "retrybody", 9);
            assertMatches(connection, "headings: RetryHeading", 9);
            execute(connection, "UPDATE chunks SET content = ? WHERE id = 9", "recoveredbody");
            assertMatches(connection, "retrybody");
            assertMatches(connection, "recoveredbody", 9);
            assertConsistent(connection);
        }
    }

    @Test
    void refusesDuplicateInstallationWithoutDuplicatingOrAlteringSearchRows() throws Exception {
        try (Connection connection = openVersionOne(root)) {
            insertFile(connection, 1, "docs/once.md");
            insertChunk(connection, 5, 1, 0, "oncetoken");
            applyFts(connection);
            var source = sourceSnapshot(connection);
            var indexed = rows(connection);
            var schema = schemaSnapshot(connection);

            assertThrows(SQLException.class, () -> applyFts(connection));

            assertEquals(source, sourceSnapshot(connection));
            assertEquals(indexed, rows(connection));
            assertEquals(schema, schemaSnapshot(connection));
            assertMatches(connection, "oncetoken", 5);
            assertConsistent(connection);
        }
    }

    private static void assertBackfilledMatches(Connection connection) throws SQLException {
        assertMatches(connection, "content: bodyonly", 31);
        assertMatches(connection, "headings: headonly", 31);
        assertMatches(connection, "headings: \"headonly Repeated Repeated\"", 31);
        assertMatches(connection, "source_path: pathonly", 11, 31);
        assertMatches(connection, "content: pathonly");
        assertMatches(connection, "headings: bodyonly");
        assertMatches(connection, "hiddenneedle");
        assertMatches(connection, "emptyfile");
        assertMatches(connection, "untouchedbody", 97);
    }

    private static List<String> columnNames(Connection connection) throws SQLException {
        List<String> result = new ArrayList<>();
        try (var statement = connection.createStatement();
                var rows = statement.executeQuery("PRAGMA table_info(chunks_fts)")) {
            while (rows.next()) {
                result.add(rows.getString("name"));
            }
        }
        return result;
    }

    private static Map<String, List<List<String>>> sourceSnapshot(Connection connection) throws SQLException {
        Map<String, List<List<String>>> result = new LinkedHashMap<>();
        for (String table : List.of("files", "chunks", "chunk_headings", "chunk_attributes", "index_metadata")) {
            result.put(table, queryRows(connection, "SELECT * FROM " + table + " ORDER BY 1, 2"));
        }
        return result;
    }

    private static List<List<String>> schemaSnapshot(Connection connection) throws SQLException {
        return queryRows(connection, "SELECT type, name, coalesce(sql, '') FROM sqlite_schema ORDER BY name");
    }

    private static List<List<String>> queryRows(Connection connection, String sql) throws SQLException {
        List<List<String>> result = new ArrayList<>();
        try (var statement = connection.createStatement();
                var rows = statement.executeQuery(sql)) {
            while (rows.next()) {
                List<String> values = new ArrayList<>();
                for (int column = 1; column <= rows.getMetaData().getColumnCount(); column++) {
                    values.add(rows.getString(column));
                }
                result.add(List.copyOf(values));
            }
        }
        return result;
    }

    private static int scalar(Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement();
                var rows = statement.executeQuery(sql)) {
            assertTrue(rows.next());
            return rows.getInt(1);
        }
    }
}
