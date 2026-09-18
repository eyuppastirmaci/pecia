package dev.eyuppastirmaci.pecia.storage.sqlite;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.sqlite.JDBC;

class SqliteFtsSupportTest {
    @TempDir
    Path root;

    @Test
    void verifiesTheRealRuntimeRepeatedlyWithoutChangingCurrentStorageOrCallerTempTables() throws Exception {
        Path database = root.resolve("index.db");

        try (SqliteStorage storage = SqliteStorage.open(database, root)) {
            Connection connection = storage.connection();
            execute(
                    connection,
                    "INSERT INTO files VALUES (1, 'docs/original.md', 'MARKDOWN', '" + "a".repeat(64) + "')");
            execute(connection, "INSERT INTO chunks VALUES (1, 1, 0, 'Original İstanbul content', 1, 2)");
            execute(connection, "INSERT INTO chunk_headings VALUES (1, 0, 'Original heading')");
            execute(connection, "INSERT INTO chunk_attributes VALUES (1, 'startOffset', '0')");
            execute(connection, "CREATE TEMP TABLE caller_temp(value TEXT)");
            execute(connection, "INSERT INTO caller_temp VALUES ('retained')");
            var files = storage.files().findAll();
            var chunks = storage.chunks().findByFileId(1);
            var indexed = SqliteFtsTestSupport.rows(connection);
            var mainSchema = schema(connection, "main");
            var tempSchema = schema(connection, "temp");

            for (int attempt = 0; attempt < 2; attempt++) {
                SqliteFtsSupport.verify(connection);
                assertFalse(connection.isClosed());
                assertTrue(connection.getAutoCommit());
                assertEquals(mainSchema, schema(connection, "main"));
                assertEquals(tempSchema, schema(connection, "temp"));
                assertEquals(files, storage.files().findAll());
                assertEquals(chunks, storage.chunks().findByFileId(1));
                assertEquals(indexed, SqliteFtsTestSupport.rows(connection));
                SqliteFtsTestSupport.assertMatches(connection, "content: original", 1);
                assertEquals(2, scalar(connection, "PRAGMA user_version"));
                assertEquals(2, scalar(connection, "SELECT index_format_version FROM index_metadata"));
                assertEquals(1, scalar(connection, "SELECT count(*) FROM caller_temp WHERE value = 'retained'"));
            }
        }

        try (SqliteStorage storage = SqliteStorage.open(database, root)) {
            assertEquals(2, scalar(storage.connection(), "PRAGMA user_version"));
            assertEquals(1, storage.chunks().findByFileId(1).size());
            List<String> mainSchema = schema(storage.connection(), "main");
            for (String table :
                    List.of("files", "chunks", "chunk_headings", "chunk_attributes", "index_metadata", "chunks_fts")) {
                assertTrue(mainSchema.stream().anyMatch(value -> value.startsWith("table|" + table + "|")), table);
            }
            SqliteFtsTestSupport.assertMatches(storage.connection(), "headings: heading", 1);
        }
    }

    @ParameterizedTest
    @CsvSource({"jdbc, false", "jdbc, true", "sql, false", "sql, true"})
    void preservesOuterTransactionsAndSavepointsOnSuccessAndFailure(String mode, boolean fail) throws Exception {
        Path database = root.resolve("transaction.db");

        try (Connection connection = raw(database);
                Connection observer = raw(database)) {
            execute(connection, "CREATE TABLE caller_data(value TEXT)");

            if (mode.equals("jdbc")) {
                connection.setAutoCommit(false);
            } else {
                execute(connection, "BEGIN IMMEDIATE");
            }

            boolean autoCommit = connection.getAutoCommit();
            execute(connection, "INSERT INTO caller_data VALUES ('before')");
            execute(connection, "SAVEPOINT caller_boundary");
            execute(connection, "INSERT INTO caller_data VALUES ('after')");

            if (fail) {
                SQLException original = new SQLException("injected probe write failure", "test", 7);
                SQLException failure = assertThrows(
                        SQLException.class,
                        () -> SqliteFtsSupport.verify(connection, (owned, table) -> {
                            createAndWriteTempFts(owned, table);
                            throw original;
                        }));
                assertSame(original, failure);
                assertEquals(0, failure.getSuppressed().length);
            } else {
                SqliteFtsSupport.verify(connection);
            }

            assertFalse(connection.isClosed());
            assertEquals(autoCommit, connection.getAutoCommit());
            assertEquals(List.of(), schema(connection, "temp"));
            assertEquals(2, scalar(connection, "SELECT count(*) FROM caller_data"));
            assertEquals(
                    0, scalar(observer, "SELECT count(*) FROM caller_data"), "The probe must not commit caller writes");
            execute(connection, "ROLLBACK TO caller_boundary");
            execute(connection, "RELEASE caller_boundary");
            assertEquals(1, scalar(connection, "SELECT count(*) FROM caller_data"));

            if (mode.equals("jdbc")) {
                connection.rollback();
            } else {
                execute(connection, "ROLLBACK");
            }

            assertEquals(0, scalar(connection, "SELECT count(*) FROM caller_data"));
        }
    }

    @ParameterizedTest
    @ValueSource(
            strings = {"no such module: fts5", "[SQLITE_ERROR] SQL error or missing database (no such module: fts5)"})
    void reportsMissingFts5WithTheOriginalCauseAndAllowsRetry(String message) throws Exception {
        try (Connection connection = raw(root.resolve("missing.db"))) {
            SQLException original = new SQLException(message, "runtime", 1);
            SQLException failure = assertThrows(
                    SQLException.class,
                    () -> SqliteFtsSupport.verify(connection, (owned, table) -> {
                        throw original;
                    }));

            assertTrue(failure.getMessage().contains("FTS5 is unavailable"));
            assertTrue(failure.getMessage().contains("SQLite JDBC runtime"));
            assertSame(original, failure.getCause());
            assertEquals(original.getSQLState(), failure.getSQLState());
            assertEquals(original.getErrorCode(), failure.getErrorCode());
            assertEquals(0, failure.getSuppressed().length);
            assertEquals(List.of(), schema(connection, "temp"));
            assertFalse(connection.isClosed());
            SqliteFtsSupport.verify(connection);
            assertEquals(List.of(), schema(connection, "temp"));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void permitsSubsequentMainSchemaCreationInTheSameTransactionAfterSuccessOrFailure(boolean failProbe)
            throws Exception {
        try (Connection connection = raw(root.resolve("schema-after-probe.db"))) {
            execute(connection, "BEGIN IMMEDIATE");
            if (failProbe) {
                SQLException original = new SQLException("injected failure after temp virtual table creation");
                assertSame(
                        original,
                        assertThrows(
                                SQLException.class,
                                () -> SqliteFtsSupport.verify(connection, (owned, table) -> {
                                    createAndWriteTempFts(owned, table);
                                    throw original;
                                })));
            } else {
                SqliteFtsSupport.verify(connection);
            }

            execute(
                    connection,
                    "CREATE TABLE later_source(value TEXT); CREATE VIRTUAL TABLE later_fts USING" + " fts5(content);");
            execute(connection, "INSERT INTO later_source VALUES ('later'); INSERT INTO later_fts VALUES ('later');");
            assertEquals(1, scalar(connection, "SELECT count(*) FROM later_fts WHERE later_fts MATCH 'later'"));
            assertEquals(List.of(), schema(connection, "temp"));
            execute(connection, "ROLLBACK");
            assertEquals(
                    List.of(), schema(connection, "main"), "The probe must leave the outer transaction rollbackable");
        }
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(
            strings = {
                "database is locked",
                "no such module: fts50",
                "no such module: another",
                "[SQLITE_ERROR] SQL error or missing database (no such module: fts50)",
                "no such tokenizer: unicode61",
                "fts5: syntax error near MATCH"
            })
    void preservesUnrelatedSqlFailuresAndCleansPartiallyCreatedTempObjects(String message) throws Exception {
        try (Connection connection = raw(root.resolve("failure.db"))) {
            SQLException original = new SQLException(message, "other", 5);
            SQLException failure = assertThrows(
                    SQLException.class,
                    () -> SqliteFtsSupport.verify(connection, (owned, table) -> {
                        createAndWriteTempFts(owned, table);
                        throw original;
                    }));

            assertSame(original, failure);
            assertEquals(0, failure.getSuppressed().length);
            assertEquals(List.of(), schema(connection, "main"));
            assertEquals(List.of(), schema(connection, "temp"));
            assertEquals(0, scalar(connection, "PRAGMA user_version"));
            SqliteFtsSupport.verify(connection);
        }
    }

    @Test
    void cleansTempObjectsAfterAnUncheckedFailure() throws Exception {
        try (Connection connection = raw(root.resolve("unchecked.db"))) {
            RuntimeException original = new IllegalStateException("injected unchecked failure");
            RuntimeException failure = assertThrows(
                    IllegalStateException.class,
                    () -> SqliteFtsSupport.verify(connection, (owned, table) -> {
                        createAndWriteTempFts(owned, table);
                        throw original;
                    }));

            assertSame(original, failure);
            assertEquals(List.of(), schema(connection, "temp"));
            SqliteFtsSupport.verify(connection);
        }
    }

    @Test
    void retainsThePrimaryFailureWhenSqliteHasAlreadyRolledBackTheTransaction() throws Exception {
        try (Connection connection = raw(root.resolve("rollback.db"))) {
            execute(connection, "CREATE TABLE unique_values(value TEXT UNIQUE)");
            execute(connection, "INSERT INTO unique_values VALUES ('duplicate')");
            SQLException failure = assertThrows(
                    SQLException.class,
                    () -> SqliteFtsSupport.verify(connection, (owned, table) -> {
                        createAndWriteTempFts(owned, table);
                        // ROLLBACK conflict handling removes our savepoint before the helper can
                        // clean it up.
                        execute(owned, "INSERT OR ROLLBACK INTO unique_values VALUES ('duplicate')");
                    }));

            assertTrue(failure.getMessage().contains("UNIQUE constraint failed"));
            assertEquals(1, failure.getSuppressed().length);
            assertTrue(failure.getSuppressed()[0].getMessage().contains("no such savepoint"));
            assertEquals(List.of(), schema(connection, "temp"));
            assertEquals(1, scalar(connection, "SELECT count(*) FROM unique_values"));
            SqliteFtsSupport.verify(connection);
        }
    }

    @Test
    void doesNotAttemptCleanupWhenTheConnectionIsAlreadyClosed() throws Exception {
        Connection connection = raw(root.resolve("closed.db"));
        connection.close();
        SQLException failure = assertThrows(
                SQLException.class,
                () -> SqliteFtsSupport.verify(
                        connection, (owned, table) -> fail("The probe must not run without a savepoint")));

        assertEquals(0, failure.getSuppressed().length);
        assertFalse(failure.getMessage().contains("FTS5 is unavailable"));
    }

    private static void createAndWriteTempFts(Connection connection, String table) throws SQLException {
        execute(
                connection,
                "CREATE VIRTUAL TABLE temp."
                        + table
                        + " USING fts5(content, tokenize = '"
                        + SqliteFtsSupport.TOKENIZER
                        + "')");
        execute(connection, "INSERT INTO temp." + table + " VALUES ('partially written')");
    }

    private static List<String> schema(Connection connection, String schema) throws SQLException {
        List<String> result = new ArrayList<>();

        try (var statement = connection.createStatement();
                var rows = statement.executeQuery(
                        "SELECT type, name, sql FROM " + schema + ".sqlite_schema ORDER BY name")) {
            while (rows.next()) {
                result.add(rows.getString(1) + "|" + rows.getString(2) + "|" + rows.getString(3));
            }
        }

        return List.copyOf(result);
    }

    private static Connection raw(Path database) throws SQLException {
        return JDBC.createConnection("jdbc:sqlite:" + database.toUri().toASCIIString(), new Properties());
    }

    private static void execute(Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.executeUpdate(sql);
        }
    }

    private static int scalar(Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement();
                var rows = statement.executeQuery(sql)) {
            assertTrue(rows.next());
            return rows.getInt(1);
        }
    }
}
