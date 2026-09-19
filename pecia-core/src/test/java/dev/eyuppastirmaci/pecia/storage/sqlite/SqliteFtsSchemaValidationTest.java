package dev.eyuppastirmaci.pecia.storage.sqlite;

import static dev.eyuppastirmaci.pecia.storage.sqlite.SqliteFtsTestSupport.assertConsistent;
import static dev.eyuppastirmaci.pecia.storage.sqlite.SqliteFtsTestSupport.assertMatches;
import static dev.eyuppastirmaci.pecia.storage.sqlite.SqliteFtsTestSupport.execute;
import static dev.eyuppastirmaci.pecia.storage.sqlite.SqliteFtsTestSupport.insertChunk;
import static dev.eyuppastirmaci.pecia.storage.sqlite.SqliteFtsTestSupport.insertFile;
import static dev.eyuppastirmaci.pecia.storage.sqlite.SqliteFtsTestSupport.insertHeading;
import static dev.eyuppastirmaci.pecia.storage.sqlite.SqliteFtsTestSupport.rows;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.sqlite.JDBC;
import org.sqlite.SQLiteConfig;

class SqliteFtsSchemaValidationTest {
    @TempDir
    Path root;

    @ParameterizedTest
    @ValueSource(strings = {"missing", "ordinary", "columns", "column_order", "tokenizer", "detail", "columnsize"})
    void rejectsMissingOrIncompatibleFtsTablesWithoutRepairingOrChangingSourceData(String damage) throws Exception {
        Path database = root.resolve("index.db");
        Fixture fixture = createIndex(database);

        try (Connection connection = raw(database)) {
            execute(connection, "DROP TABLE chunks_fts");
            if (!damage.equals("missing")) {
                String replacement =
                        switch (damage) {
                            case "ordinary" -> "CREATE TABLE chunks_fts(content TEXT, headings TEXT, source_path TEXT)";
                            case "columns" -> fixture.tableDdl().replace("source_path", "other_path");
                            case "column_order" ->
                                fixture.tableDdl().replace("content,\n    headings,", "headings,\n    content,");
                            case "tokenizer" ->
                                fixture.tableDdl()
                                        .replace("unicode61 remove_diacritics 2", "unicode61 remove_diacritics 1");
                            case "detail" -> fixture.tableDdl().replace("detail = full", "detail = none");
                            case "columnsize" -> fixture.tableDdl().replace("columnsize = 1", "columnsize = 0");
                            default -> throw new AssertionError("Unknown fixture: " + damage);
                        };
                assertNotEquals(
                        fixture.tableDdl(), replacement, "The fixture must actually change the table definition");
                execute(connection, replacement);
            }
        }

        assertRejectedWithoutMutation(database, fixture);

        // Repair is explicit test setup. A failed open must never silently recreate this derived index.
        try (Connection connection = raw(database)) {
            execute(connection, "DROP TABLE IF EXISTS chunks_fts");
            execute(connection, fixture.tableDdl());
            for (FtsRow row : fixture.indexed()) {
                execute(
                        connection,
                        "INSERT INTO chunks_fts(rowid, content, headings, source_path) VALUES (?, ?, ?, ?)",
                        row.id(),
                        row.content(),
                        row.headings(),
                        row.sourcePath());
            }
        }
        assertHealthyReopen(database, fixture);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "chunks_fts_insert",
                "chunks_fts_update",
                "chunks_fts_delete",
                "chunk_headings_fts_insert",
                "chunk_headings_fts_update",
                "chunk_headings_fts_delete",
                "files_fts_path_update"
            })
    void rejectsEveryMissingRequiredTriggerWithoutSilentlyRecreatingIt(String trigger) throws Exception {
        Path database = root.resolve("index.db");
        Fixture fixture = createIndex(database);
        String definition;

        try (Connection connection = raw(database)) {
            definition = objectDdl(connection, trigger);
            execute(connection, "DROP TRIGGER " + trigger);
        }

        assertRejectedWithoutMutation(database, fixture);

        try (Connection connection = raw(database)) {
            execute(connection, definition);
        }
        assertHealthyReopen(database, fixture);
    }

    @ParameterizedTest
    @ValueSource(strings = {"body", "source_table"})
    void rejectsARequiredTriggerWhoseNameIsCorrectButDefinitionIsWrong(String damage) throws Exception {
        Path database = root.resolve("index.db");
        Fixture fixture = createIndex(database);
        String definition;

        try (Connection connection = raw(database)) {
            definition = objectDdl(connection, "chunk_headings_fts_update");
            execute(connection, "DROP TRIGGER chunk_headings_fts_update");
            String replacement = damage.equals("body")
                    ? "CREATE TRIGGER chunk_headings_fts_update AFTER UPDATE OF chunk_id, position,"
                            + " heading ON chunk_headings BEGIN SELECT 1; END"
                    : definition.replace("ON chunk_headings", "ON files");
            assertNotEquals(definition, replacement, "The fixture must actually change the trigger definition");
            execute(connection, replacement);
        }

        assertRejectedWithoutMutation(database, fixture);

        try (Connection connection = raw(database)) {
            execute(connection, "DROP TRIGGER chunk_headings_fts_update");
            execute(connection, definition);
        }
        assertHealthyReopen(database, fixture);
    }

    private Fixture createIndex(Path database) throws Exception {
        try (SqliteStorage storage = SqliteStorage.open(database, root)) {
            Connection connection = storage.connection();
            insertFile(connection, 7, "docs/validationpath.md");
            insertChunk(connection, 41, 7, 0, "validationbody İstanbul 😀\r\noriginal");
            insertHeading(connection, 41, 0, "ValidationHeading");
            execute(connection, "INSERT INTO chunk_attributes VALUES (?, ?, ?)", 41, "startOffset", "0");
            assertConsistent(connection);
            return new Fixture(sourceSnapshot(connection), rows(connection), objectDdl(connection, "chunks_fts"));
        }
    }

    private void assertRejectedWithoutMutation(Path database, Fixture fixture) throws Exception {
        byte[] before = Files.readAllBytes(database);

        assertThrows(SQLException.class, () -> SqliteStorage.open(database, root));

        assertArrayEquals(
                before, Files.readAllBytes(database), "Rejected current schema must not be repaired or rewritten");
        try (Connection connection = raw(database)) {
            assertEquals(fixture.source(), sourceSnapshot(connection));
            assertEquals(4, scalar(connection, "PRAGMA user_version"));
            assertEquals(4, scalar(connection, "SELECT index_format_version FROM index_metadata"));
        }
    }

    private void assertHealthyReopen(Path database, Fixture fixture) throws Exception {
        try (SqliteStorage storage = SqliteStorage.open(database, root)) {
            Connection connection = storage.connection();
            assertEquals(fixture.source(), sourceSnapshot(connection));
            assertEquals(fixture.indexed(), rows(connection));
            assertMatches(connection, "content: validationbody", 41);
            assertMatches(connection, "headings: validationheading", 41);
            assertMatches(connection, "source_path: validationpath", 41);
            assertConsistent(connection);
        }
    }

    private static String objectDdl(Connection connection, String name) throws SQLException {
        try (var query = connection.prepareStatement("SELECT sql FROM sqlite_schema WHERE name = ?")) {
            query.setString(1, name);
            try (var row = query.executeQuery()) {
                assertTrue(row.next(), "Missing fixture object: " + name);
                return row.getString(1);
            }
        }
    }

    private static Map<String, List<List<String>>> sourceSnapshot(Connection connection) throws SQLException {
        Map<String, List<List<String>>> snapshot = new LinkedHashMap<>();
        for (String table : List.of("files", "chunks", "chunk_headings", "chunk_attributes", "index_metadata")) {
            List<List<String>> values = new ArrayList<>();
            try (var statement = connection.createStatement();
                    var rows = statement.executeQuery("SELECT * FROM " + table + " ORDER BY 1, 2")) {
                while (rows.next()) {
                    List<String> row = new ArrayList<>();
                    for (int column = 1; column <= rows.getMetaData().getColumnCount(); column++) {
                        row.add(rows.getString(column));
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
                var row = statement.executeQuery(sql)) {
            assertTrue(row.next());
            return row.getInt(1);
        }
    }

    private static Connection raw(Path database) throws SQLException {
        SQLiteConfig config = new SQLiteConfig();
        config.enforceForeignKeys(true);
        return JDBC.createConnection("jdbc:sqlite:" + database.toUri().toASCIIString(), config.toProperties());
    }

    private record Fixture(Map<String, List<List<String>>> source, List<FtsRow> indexed, String tableDdl) {}
}
