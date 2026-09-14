package dev.eyuppastirmaci.pecia.storage.sqlite;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Map;

final class SqliteSchemaInitializer {
    private static final String SCHEMA_RESOURCE = "/db/migration/V1__create_initial_schema.sql";
    private static final int SCHEMA_VERSION = 1;
    private static final int INDEX_FORMAT_VERSION = 1;
    private static final Map<String, String> REQUIRED_COLUMNS = Map.of(
            "files", "id, source_path, document_type, content_hash",
            "chunks", "id, file_id, chunk_index, content, start_line, end_line",
            "chunk_headings", "chunk_id, position, heading",
            "chunk_attributes", "chunk_id, name, value",
            "index_metadata", "singleton, project_root_uri, index_format_version");

    private SqliteSchemaInitializer() { }

    static void initialize(Connection connection, String rootUri) throws IOException, SQLException {
        try (var input = SqliteSchemaInitializer.class.getResourceAsStream(SCHEMA_RESOURCE)) {
            if (input == null) {
                throw new IOException("Missing SQLite schema resource: " + SCHEMA_RESOURCE);
            }

            initialize(connection, rootUri, new String(input.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    /* Serializes version checks and first creation so concurrent openers cannot initialize the same database twice. */
    static void initialize(Connection connection, String rootUri, String schema) throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.execute("BEGIN IMMEDIATE");

            try {
                int version;

                try (var result = statement.executeQuery("PRAGMA user_version")) {
                    result.next();
                    version = result.getInt(1);
                }

                if (version == 0) {
                    requireEmptyDatabase(connection);
                    // Xerial executes the complete SQL script, avoiding an unsafe split on semicolons.
                    statement.executeUpdate(schema);

                    try (var insert = connection.prepareStatement(
                            "INSERT INTO index_metadata(singleton, project_root_uri, index_format_version) VALUES (1, ?, ?)")) {
                        insert.setString(1, rootUri);
                        insert.setInt(2, INDEX_FORMAT_VERSION);
                        insert.executeUpdate();
                    }

                    statement.execute("PRAGMA user_version = " + SCHEMA_VERSION);
                } else if (version != SCHEMA_VERSION) {
                    throw new SQLException("Unsupported SQLite schema version: " + version + "; expected " + SCHEMA_VERSION);
                }

                validateSchema(connection, rootUri);
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

    private static void requireEmptyDatabase(Connection connection) throws SQLException {
        try (var statement = connection.createStatement();
             var result = statement.executeQuery("SELECT name FROM sqlite_schema WHERE name NOT GLOB 'sqlite_*' LIMIT 1")) {
            if (result.next()) {
                throw new SQLException("Cannot initialize a non-empty unversioned database: " + result.getString(1));
            }
        }
    }

    /* Verifies required tables and readable columns before accepting the version and project ownership marker. */
    private static void validateSchema(Connection connection, String rootUri) throws SQLException {
        for (var table : REQUIRED_COLUMNS.entrySet()) {
            try (var query = connection.prepareStatement("SELECT type FROM sqlite_schema WHERE name = ?")) {
                query.setString(1, table.getKey());

                try (var result = query.executeQuery()) {
                    if (!result.next() || !"table".equals(result.getString(1))) {
                        throw new SQLException("Missing SQLite table: " + table.getKey());
                    }
                }
            }

            try (var query = connection.createStatement();
                 var ignored = query.executeQuery("SELECT " + table.getValue() + " FROM " + table.getKey() + " LIMIT 0")) {
                // Preparing the projection checks every required column even when the table is empty.
            }
        }

        try (var statement = connection.createStatement();
             var result = statement.executeQuery("SELECT singleton, project_root_uri, index_format_version FROM index_metadata")) {
            if (!result.next() || result.getInt(1) != 1) {
                throw new SQLException("Missing SQLite index metadata");
            }

            if (!rootUri.equals(result.getString(2))) {
                throw new SQLException("SQLite index belongs to another project root: " + result.getString(2));
            }

            if (result.getInt(3) != INDEX_FORMAT_VERSION) {
                throw new SQLException("Unsupported index format version: " + result.getInt(3));
            }

            if (result.next()) {
                throw new SQLException("SQLite index metadata must contain exactly one row");
            }
        }
    }
}
