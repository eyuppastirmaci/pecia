package dev.eyuppastirmaci.pecia.storage.sqlite;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.sqlite.JDBC;
import org.sqlite.SQLiteConfig;

/**
 * Builds historical fixtures directly from production SQL, independently of the current storage
 * initializer.
 */
final class SqliteFtsTestSupport {
    static final String V1_RESOURCE = "/db/migration/V1__create_initial_schema.sql";
    static final String V2_RESOURCE = "/db/migration/V2__add_chunk_fts.sql";

    private SqliteFtsTestSupport() {}

    static Connection openVersionOne(Path root) throws IOException, SQLException {
        SQLiteConfig config = new SQLiteConfig();
        config.enforceForeignKeys(true);
        Connection connection = JDBC.createConnection(
                "jdbc:sqlite:" + root.resolve("index.db").toUri().toASCIIString(), config.toProperties());

        try {
            applyScript(connection, resource(V1_RESOURCE));
            execute(
                    connection,
                    "INSERT INTO index_metadata VALUES (1, ?, 1)",
                    root.toRealPath().toUri().toASCIIString());
            execute(connection, "PRAGMA user_version = 1");
            return connection;
        } catch (IOException | SQLException | RuntimeException | Error failure) {
            try {
                connection.close();
            } catch (SQLException closeFailure) {
                failure.addSuppressed(closeFailure);
            }
            throw failure;
        }
    }

    static void applyFts(Connection connection) throws IOException, SQLException {
        applyScript(connection, resource(V2_RESOURCE));
    }

    static void applyScript(Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.execute("BEGIN IMMEDIATE");
            try {
                // Execute the whole resource so semicolons inside trigger bodies remain intact.
                statement.executeUpdate(sql);
                statement.execute("COMMIT");
            } catch (SQLException | RuntimeException | Error failure) {
                try {
                    statement.execute("ROLLBACK");
                } catch (SQLException rollbackFailure) {
                    failure.addSuppressed(rollbackFailure);
                }
                throw failure;
            }
        }
    }

    static String resource(String name) throws IOException {
        try (var stream = SqliteFtsTestSupport.class.getResourceAsStream(name)) {
            assertNotNull(stream, "Missing production SQL resource: " + name);
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    static void execute(Connection connection, String sql, Object... parameters) throws SQLException {
        try (var statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < parameters.length; index++) {
                statement.setObject(index + 1, parameters[index]);
            }
            statement.executeUpdate();
        }
    }

    static void insertFile(Connection connection, long id, String path) throws SQLException {
        execute(connection, "INSERT INTO files VALUES (?, ?, 'MARKDOWN', ?)", id, path, "a".repeat(64));
    }

    static void insertChunk(Connection connection, long id, long fileId, int index, String content)
            throws SQLException {
        execute(connection, "INSERT INTO chunks VALUES (?, ?, ?, ?, 1, 2)", id, fileId, index, content);
    }

    static void insertHeading(Connection connection, long chunkId, int position, String heading) throws SQLException {
        execute(connection, "INSERT INTO chunk_headings VALUES (?, ?, ?)", chunkId, position, heading);
    }

    static void assertMatches(Connection connection, String expression, long... expectedIds) throws SQLException {
        List<Long> ids = new ArrayList<>();
        try (var statement =
                connection.prepareStatement("SELECT rowid FROM chunks_fts WHERE chunks_fts MATCH ? ORDER BY rowid")) {
            statement.setString(1, expression);
            try (var result = statement.executeQuery()) {
                while (result.next()) {
                    ids.add(result.getLong(1));
                }
            }
        }
        assertEquals(Arrays.stream(expectedIds).boxed().toList(), ids, "FTS MATCH expression: " + expression);
    }

    static List<FtsRow> rows(Connection connection) throws SQLException {
        List<FtsRow> rows = new ArrayList<>();
        try (var statement = connection.createStatement();
                var result = statement.executeQuery(
                        "SELECT rowid, content, headings, source_path FROM chunks_fts ORDER BY rowid")) {
            while (result.next()) {
                rows.add(new FtsRow(result.getLong(1), result.getString(2), result.getString(3), result.getString(4)));
            }
        }
        return List.copyOf(rows);
    }

    static void assertConsistent(Connection connection) throws SQLException {
        List<FtsRow> expected = new ArrayList<>();
        try (var statement = connection.createStatement();
                var result = statement.executeQuery("""
                    SELECT c.id, c.content, f.source_path FROM chunks c
                    JOIN files f ON f.id = c.file_id ORDER BY c.id
                    """);
                var headingQuery = connection.prepareStatement(
                        "SELECT heading FROM chunk_headings WHERE chunk_id = ? ORDER BY position")) {
            while (result.next()) {
                long id = result.getLong(1);
                List<String> headings = new ArrayList<>();
                headingQuery.setLong(1, id);
                try (var headingRows = headingQuery.executeQuery()) {
                    while (headingRows.next()) {
                        headings.add(headingRows.getString(1));
                    }
                }
                // Independent Java construction avoids repeating the production SQL aggregation.
                expected.add(new FtsRow(id, result.getString(2), String.join(" ", headings), result.getString(3)));
            }
        }
        assertEquals(expected, rows(connection));
        execute(connection, "INSERT INTO chunks_fts(chunks_fts) VALUES ('integrity-check')");
    }

    record FtsRow(long id, String content, String headings, String sourcePath) {}
}
