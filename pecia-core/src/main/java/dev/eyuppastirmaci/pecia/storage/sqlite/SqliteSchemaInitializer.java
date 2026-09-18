package dev.eyuppastirmaci.pecia.storage.sqlite;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;

final class SqliteSchemaInitializer {

    private static final String SCHEMA_RESOURCE = "/db/migration/V1__create_initial_schema.sql";
    private static final int SCHEMA_VERSION = 2;
    private static final int INDEX_FORMAT_VERSION = 2;
    private static final Map<String, String> REQUIRED_COLUMNS = Map.of(
            "files", "id, source_path, document_type, content_hash",
            "chunks", "id, file_id, chunk_index, content, start_line, end_line",
            "chunk_headings", "chunk_id, position, heading",
            "chunk_attributes", "chunk_id, name, value",
            "index_metadata", "singleton, project_root_uri, index_format_version");

    private SqliteSchemaInitializer() { }

    static void validateReadOnly(Connection connection, String rootUri) throws IOException, SQLException {
        SqliteFtsSchema fts = SqliteFtsSchema.load();

        // A deferred read transaction keeps all validation reads in one snapshot without a write lock.
        try (Statement statement = connection.createStatement()) {
            statement.execute("BEGIN");

            try {
                int version;

                try (ResultSet result = statement.executeQuery("PRAGMA user_version")) {
                    result.next();
                    version = result.getInt(1);
                }

                if (version != 1 && version != SCHEMA_VERSION) {
                    throw new IndexAccessException(IndexAccessException.Reason.INCOMPATIBLE,
                            "Unsupported SQLite schema version: " + version);
                }

                validateSchema(connection, rootUri, version == 1 ? 1 : INDEX_FORMAT_VERSION);

                if (version == 1) {
                    throw new IndexAccessException(IndexAccessException.Reason.MIGRATION_REQUIRED,
                            "Index upgrade required; run index before querying");
                }

                fts.validate(connection);
                statement.execute("COMMIT");
            } catch (SQLException | RuntimeException | Error failure) {
                try {
                    statement.execute("ROLLBACK");
                } catch (SQLException rollbackFailure) {
                    failure.addSuppressed(rollbackFailure);
                }

                if (failure instanceof SQLException sql && !(sql instanceof IndexAccessException)
                        && isInvalidSchema(sql)) {

                    throw new IndexAccessException(IndexAccessException.Reason.CORRUPT_INDEX,
                            "Could not validate existing index", sql);
                }

                throw failure;
            }
        }
    }

    private static boolean isInvalidSchema(SQLException failure) {
        // Generic validation errors and SQLite ERROR/CORRUPT/NOTADB indicate invalid stored structure.
        // Busy, locked, I/O and other access errors must retain their original classification.
        int code = failure.getErrorCode() & 0xff;

        return code == 0 || code == 1 || code == 11 || code == 26;
    }

    static void initialize(Connection connection, String rootUri) throws IOException, SQLException {
        try (var input = SqliteSchemaInitializer.class.getResourceAsStream(SCHEMA_RESOURCE)) {
            if (input == null) {
                throw new IOException("Missing SQLite schema resource: " + SCHEMA_RESOURCE);
            }

            initialize(connection, rootUri, new String(input.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    static void initialize(Connection connection, String rootUri, String schema) throws IOException, SQLException {
        SqliteFtsSchema fts = SqliteFtsSchema.load();
        initialize(connection, rootUri, schema, fts.script(), fts, SqliteFtsSupport::verify);
    }

    /* Failure-injection seam; expected definitions always come from the canonical bundled resource. */
    static void initialize(Connection connection, String rootUri, String schema, String migration, FtsCheck ftsCheck)
            throws IOException, SQLException {
        initialize(connection, rootUri, schema, migration, SqliteFtsSchema.load(), ftsCheck);
    }

    /* Serializes validation, creation and upgrade so concurrent openers cannot migrate the same index twice. */
    private static void initialize(Connection connection, String rootUri, String schema, String migration,
                                   SqliteFtsSchema fts, FtsCheck ftsCheck) throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.execute("BEGIN IMMEDIATE");

            try {
                int version;

                try (var result = statement.executeQuery("PRAGMA user_version")) {
                    result.next();
                    version = result.getInt(1);
                }

                if (version != 0 && version != 1 && version != SCHEMA_VERSION) {
                    throw new SQLException("Unsupported SQLite schema version: " + version + "; expected 1 or " + SCHEMA_VERSION);
                }

                if (version == 0) {
                    requireEmptyDatabase(connection);
                } else {
                    // Validate ownership and the old format before even attempting migration or writing schema objects.
                    validateSchema(connection, rootUri, version == 1 ? 1 : INDEX_FORMAT_VERSION);
                }

                if (version == 1) {
                    requireValidRelationships(connection);
                }
                ftsCheck.verify(connection);

                if (version == 0) {
                    // Xerial executes the complete SQL script, avoiding an unsafe split on semicolons.
                    statement.executeUpdate(schema);

                    try (var insert = connection.prepareStatement(
                            "INSERT INTO index_metadata(singleton, project_root_uri, index_format_version) VALUES (1, ?, ?)")) {
                        insert.setString(1, rootUri);
                        insert.setInt(2, 1);
                        insert.executeUpdate();
                    }

                    validateSchema(connection, rootUri, 1);
                }

                if (version < SCHEMA_VERSION) {
                    statement.executeUpdate(migration);
                    fts.validate(connection);
                    statement.executeUpdate("UPDATE index_metadata SET index_format_version = " + INDEX_FORMAT_VERSION
                            + " WHERE singleton = 1");
                    statement.execute("PRAGMA user_version = " + SCHEMA_VERSION);
                } else {
                    fts.validate(connection);
                }

                validateSchema(connection, rootUri, INDEX_FORMAT_VERSION);
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
    private static void validateSchema(Connection connection, String rootUri, int expectedFormat) throws SQLException {
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
                throw new IndexAccessException(IndexAccessException.Reason.WRONG_PROJECT,
                        "SQLite index belongs to another project root: " + result.getString(2));
            }

            if (result.getInt(3) != expectedFormat) {
                throw new IndexAccessException(IndexAccessException.Reason.INCOMPATIBLE,
                        "Unsupported index format version: " + result.getInt(3));
            }

            if (result.next()) {
                throw new SQLException("SQLite index metadata must contain exactly one row");
            }
        }
    }

    private static void requireValidRelationships(Connection connection) throws SQLException {
        // Only before backfill: an orphan chunk must not silently disappear from the JOIN-based search copy.
        try (var statement = connection.createStatement(); var result = statement.executeQuery("PRAGMA foreign_key_check")) {
            if (result.next()) {
                throw new SQLException("Invalid SQLite V1 foreign-key relationship in table: " + result.getString(1));
            }
        }
    }

    @FunctionalInterface
    interface FtsCheck {
        void verify(Connection connection) throws SQLException;
    }
}
