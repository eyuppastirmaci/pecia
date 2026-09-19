package dev.eyuppastirmaci.pecia.storage.sqlite;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;
import java.util.Objects;

/** Validates and migrates one project's index using a borrowed connection. */
final class SqliteSchemaInitializer {

    private static final String SCHEMA_RESOURCE = "/db/migration/V1__create_initial_schema.sql";
    private static final int SCHEMA_VERSION = 4;
    private static final int INDEX_FORMAT_VERSION = 4;
    private static final Map<String, String> REQUIRED_COLUMNS = Map.of(
            "files", "id, source_path, document_type, content_hash",
            "chunks", "id, file_id, chunk_index, content, start_line, end_line",
            "chunk_headings", "chunk_id, position, heading",
            "chunk_attributes", "chunk_id, name, value",
            "index_metadata", "singleton, project_root_uri, index_format_version");

    private final Connection connection;
    private final String rootUri;
    private final SqliteFtsSchema fts;
    private final SqliteIndexingProfileSchema profiles;
    private final SqliteChunkIdentitySchema identities;

    private SqliteSchemaInitializer(
            Connection connection,
            String rootUri,
            SqliteFtsSchema fts,
            SqliteIndexingProfileSchema profiles,
            SqliteChunkIdentitySchema identities) {
        this.connection = Objects.requireNonNull(connection, "connection");
        this.rootUri = Objects.requireNonNull(rootUri, "rootUri");
        this.fts = Objects.requireNonNull(fts, "fts");
        this.profiles = Objects.requireNonNull(profiles, "profiles");
        this.identities = Objects.requireNonNull(identities, "identities");
    }

    static SqliteSchemaInitializer load(Connection connection, String rootUri) throws IOException {
        return new SqliteSchemaInitializer(
                connection,
                rootUri,
                SqliteFtsSchema.load(),
                SqliteIndexingProfileSchema.load(),
                SqliteChunkIdentitySchema.load());
    }

    void validateReadOnly() throws SQLException {
        // A deferred read transaction keeps all validation reads in one snapshot without a write lock.
        try (Statement statement = connection.createStatement()) {
            statement.execute("BEGIN");

            try {
                int version;

                try (ResultSet result = statement.executeQuery("PRAGMA user_version")) {
                    result.next();
                    version = result.getInt(1);
                }

                if (version < 1 || version > SCHEMA_VERSION) {
                    throw new IndexAccessException(
                            IndexAccessException.Reason.INCOMPATIBLE, "Unsupported SQLite schema version: " + version);
                }

                validateStoredSchema(version);

                if (version < SCHEMA_VERSION) {
                    throw new IndexAccessException(
                            IndexAccessException.Reason.MIGRATION_REQUIRED,
                            "Index upgrade required; run index before querying");
                }

                statement.execute("COMMIT");
            } catch (SQLException | RuntimeException | Error failure) {
                try {
                    statement.execute("ROLLBACK");
                } catch (SQLException rollbackFailure) {
                    failure.addSuppressed(rollbackFailure);
                }

                if (failure instanceof SQLException sql
                        && !(sql instanceof IndexAccessException)
                        && isInvalidSchema(sql)) {
                    throw new IndexAccessException(
                            IndexAccessException.Reason.CORRUPT_INDEX, "Could not validate existing index", sql);
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

    void initialize() throws IOException, SQLException {
        try (var input = SqliteSchemaInitializer.class.getResourceAsStream(SCHEMA_RESOURCE)) {
            if (input == null) {
                throw new IOException("Missing SQLite schema resource: " + SCHEMA_RESOURCE);
            }

            initialize(new String(input.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    void initialize(String schema) throws SQLException {
        initialize(schema, fts.script(), profiles.script(), SqliteFtsSupport::verify);
    }

    // Failure-injection seam; expected definitions come from the canonical bundled resource.
    void initialize(String schema, String ftsMigration, FtsCheck ftsCheck) throws SQLException {
        initialize(schema, ftsMigration, profiles.script(), ftsCheck);
    }

    void initialize(String schema, String ftsMigration, String profileMigration, FtsCheck ftsCheck)
            throws SQLException {
        initialize(schema, ftsMigration, profileMigration, identities.script(), ftsCheck);
    }

    /** Serializes validation, creation and upgrade to prevent concurrent duplicate migrations. */
    void initialize(
            String schema, String ftsMigration, String profileMigration, String identityMigration, FtsCheck ftsCheck)
            throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.execute("BEGIN IMMEDIATE");

            try {
                int version;

                try (var result = statement.executeQuery("PRAGMA user_version")) {
                    result.next();
                    version = result.getInt(1);
                }

                if (version < 0 || version > SCHEMA_VERSION) {
                    throw new SQLException(
                            "Unsupported SQLite schema version: " + version + "; expected 1 through " + SCHEMA_VERSION);
                }

                if (version == 0) {
                    requireEmptyDatabase();
                } else {
                    // Reject foreign or invalid indexes before the capability probe or any migration.
                    validateStoredSchema(version);
                }

                ftsCheck.verify(connection);

                if (version == 0) {
                    createInitialSchema(statement, schema);
                }

                applyMigrations(statement, version, ftsMigration, profileMigration, identityMigration);
                validateStoredSchema(SCHEMA_VERSION);
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

    private void validateStoredSchema(int version) throws SQLException {
        validateSchema(version);

        if (version == 1) {
            requireValidRelationships();
        } else {
            fts.validate(connection);
        }

        if (version >= 3) {
            profiles.validate(connection);
        }

        if (version >= 4) {
            identities.validate(connection);
        }
    }

    private void createInitialSchema(Statement statement, String schema) throws SQLException {
        // Xerial executes the complete SQL script, avoiding an unsafe split on semicolons.
        statement.executeUpdate(schema);

        try (var insert = connection.prepareStatement(
                "INSERT INTO index_metadata(singleton, project_root_uri, index_format_version) VALUES (1, ?, ?)")) {
            insert.setString(1, rootUri);
            insert.setInt(2, 1);
            insert.executeUpdate();
        }

        validateSchema(1);
    }

    private void applyMigrations(
            Statement statement, int version, String ftsMigration, String profileMigration, String identityMigration)
            throws SQLException {
        if (version < 2) {
            statement.executeUpdate(ftsMigration);
            fts.validate(connection);
        }

        if (version < 3) {
            statement.executeUpdate(profileMigration);
            profiles.validate(connection);
        }

        if (version < 4) {
            statement.executeUpdate(identityMigration);
            identities.validate(connection);
        }

        if (version < SCHEMA_VERSION) {
            statement.executeUpdate("UPDATE index_metadata SET index_format_version = "
                    + INDEX_FORMAT_VERSION
                    + " WHERE singleton = 1");
            statement.execute("PRAGMA user_version = " + SCHEMA_VERSION);
        }
    }

    private void requireEmptyDatabase() throws SQLException {
        try (var statement = connection.createStatement();
                var result = statement.executeQuery(
                        "SELECT name FROM sqlite_schema WHERE name NOT GLOB 'sqlite_*' LIMIT 1")) {
            if (result.next()) {
                throw new SQLException("Cannot initialize a non-empty unversioned database: " + result.getString(1));
            }
        }
    }

    private void validateSchema(int expectedFormat) throws SQLException {
        validateTables();
        validateIndexMetadata(expectedFormat);
    }

    private void validateTables() throws SQLException {
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
                    var ignored =
                            query.executeQuery("SELECT " + table.getValue() + " FROM " + table.getKey() + " LIMIT 0")) {
                // Preparing the projection checks every required column even when the table is empty.
            }
        }
    }

    private void validateIndexMetadata(int expectedFormat) throws SQLException {
        try (var statement = connection.createStatement();
                var result = statement.executeQuery(
                        "SELECT singleton, project_root_uri, index_format_version FROM index_metadata")) {
            if (!result.next() || result.getInt(1) != 1) {
                throw new SQLException("Missing SQLite index metadata");
            }

            if (!rootUri.equals(result.getString(2))) {
                throw new IndexAccessException(
                        IndexAccessException.Reason.WRONG_PROJECT,
                        "SQLite index belongs to another project root: " + result.getString(2));
            }

            Object storedFormat = result.getObject(3);

            if (!(storedFormat instanceof Integer format) || format != expectedFormat) {
                throw new IndexAccessException(
                        IndexAccessException.Reason.INCOMPATIBLE, "Unsupported index format version: " + storedFormat);
            }

            if (result.next()) {
                throw new SQLException("SQLite index metadata must contain exactly one row");
            }
        }
    }

    private void requireValidRelationships() throws SQLException {
        // Only before backfill: an orphan chunk must not silently disappear from the JOIN-based search
        // copy.
        try (var statement = connection.createStatement();
                var result = statement.executeQuery("PRAGMA foreign_key_check")) {
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
