package dev.eyuppastirmaci.pecia.storage.sqlite;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

/** Checks the connection's actual FTS5 runtime without creating a persistent index. */
final class SqliteFtsSupport {
    static final String TOKENIZER = "unicode61 remove_diacritics 2";

    private SqliteFtsSupport() {}

    static void verify(Connection connection) throws SQLException {
        verify(connection, SqliteFtsSupport::probe);
    }

    // Allows deterministic runtime failures without substituting a different SQLite binary.
    static void verify(Connection connection, Probe probe) throws SQLException {
        // These identifiers contain only a fixed prefix and generated hexadecimal characters.
        String name = "pecia_fts_probe_" + UUID.randomUUID().toString().replace("-", "");

        try (var statement = connection.createStatement()) {
            statement.execute("SAVEPOINT " + name);

            try {
                probe.run(connection, name);
            } catch (SQLException failure) {
                SQLException reported = classify(failure);
                cleanAfterFailure(statement, name, reported);
                throw reported;
            } catch (RuntimeException | Error failure) {
                cleanAfterFailure(statement, name, failure);
                throw failure;
            }

            clean(statement, name);
        }
    }

    private static void probe(Connection connection, String table) throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.execute("CREATE VIRTUAL TABLE temp."
                    + table
                    + " USING fts5(content, tokenize = '"
                    + TOKENIZER
                    + "', detail = full, columnsize = 1)");
        }

        String content = "pecia fts capability";

        try (var insert = connection.prepareStatement("INSERT INTO temp." + table + "(rowid, content) VALUES (1, ?)")) {
            insert.setString(1, content);
            insert.executeUpdate();
        }

        try (var query = connection.prepareStatement(
                "SELECT rowid, content FROM temp." + table + " WHERE " + table + " MATCH ?")) {
            query.setString(1, "capability");

            try (var result = query.executeQuery()) {
                if (!result.next() || result.getLong(1) != 1 || !content.equals(result.getString(2)) || result.next()) {
                    throw new SQLException("SQLite FTS5 runtime verification returned unexpected MATCH results");
                }
            }
        }
    }

    private static SQLException classify(SQLException failure) {
        String message = failure.getMessage();

        if (message != null && (message.equals("no such module: fts5") || message.endsWith("(no such module: fts5)"))) {
            return new SQLException(
                    "SQLite FTS5 is unavailable; use a SQLite JDBC runtime built with FTS5 support",
                    failure.getSQLState(),
                    failure.getErrorCode(),
                    failure);
        }

        return failure;
    }

    private static void cleanAfterFailure(Statement statement, String name, Throwable failure) {
        try {
            clean(statement, name);
        } catch (SQLException cleanupFailure) {
            failure.addSuppressed(cleanupFailure);
        }
    }

    private static void clean(Statement statement, String name) throws SQLException {
        SQLException failure = null;
        try {
            // Disconnect the virtual table first: rollback alone makes this runtime reject subsequent
            // main-schema DDL in the same outer transaction, even though the TEMP schema looks empty.
            statement.execute("DROP TABLE IF EXISTS temp." + name);
        } catch (SQLException dropFailure) {
            failure = dropFailure;
        }

        try {
            // Still roll back if DROP failed; releasing never commits a caller's outer transaction.
            statement.execute("ROLLBACK TO " + name);
            statement.execute("RELEASE " + name);
        } catch (SQLException rollbackFailure) {
            if (failure == null) {
                throw rollbackFailure;
            }
            failure.addSuppressed(rollbackFailure);
        }
        if (failure != null) {
            throw failure;
        }
    }

    @FunctionalInterface
    interface Probe {
        void run(Connection connection, String table) throws SQLException;
    }
}
