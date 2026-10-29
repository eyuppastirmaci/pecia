package dev.eyuppastirmaci.pecia.storage.sqlite;

import static dev.eyuppastirmaci.pecia.storage.sqlite.SqliteFtsTestSupport.V1_RESOURCE;
import static dev.eyuppastirmaci.pecia.storage.sqlite.SqliteFtsTestSupport.V2_RESOURCE;
import static dev.eyuppastirmaci.pecia.storage.sqlite.SqliteFtsTestSupport.V3_RESOURCE;
import static dev.eyuppastirmaci.pecia.storage.sqlite.SqliteFtsTestSupport.V4_RESOURCE;
import static dev.eyuppastirmaci.pecia.storage.sqlite.SqliteFtsTestSupport.V5_RESOURCE;
import static dev.eyuppastirmaci.pecia.storage.sqlite.SqliteFtsTestSupport.execute;
import static dev.eyuppastirmaci.pecia.storage.sqlite.SqliteFtsTestSupport.insertChunk;
import static dev.eyuppastirmaci.pecia.storage.sqlite.SqliteFtsTestSupport.insertFile;
import static dev.eyuppastirmaci.pecia.storage.sqlite.SqliteFtsTestSupport.openVersionFour;
import static dev.eyuppastirmaci.pecia.storage.sqlite.SqliteFtsTestSupport.resource;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.sqlite.JDBC;

/** Adds the embedding tables to version-four indexes, atomically, and validates them on every open. */
class SqliteEmbeddingMigrationTest {

    private static final List<String> EMBEDDING_OBJECTS = List.of(
            "chunk_embeddings",
            "chunk_embeddings_dimensions_insert",
            "chunk_embeddings_dimensions_update",
            "chunk_embeddings_profile",
            "chunks_embedding_update",
            "embedding_profiles",
            "embedding_profiles_update");

    @TempDir
    Path root;

    @Test
    void aFreshIndexHasEmptyEmbeddingTables() throws Exception {
        try (SqliteStorage storage = SqliteStorage.open(root.resolve("index.db"), root)) {
            assertCurrentVersion(storage.connection());
            assertEquals(EMBEDDING_OBJECTS, embeddingObjects(storage.connection()));
            assertNoEmbeddings(storage.connection());
        }
    }

    @Test
    void aVersionFourIndexGainsEmptyEmbeddingTablesAndKeepsItsRows() throws Exception {
        List<List<String>> before;

        try (Connection connection = openVersionFour(root)) {
            seed(connection);
            before = sources(connection);
        }

        try (SqliteStorage storage = SqliteStorage.open(root.resolve("index.db"), root)) {
            assertCurrentVersion(storage.connection());
            assertEquals(EMBEDDING_OBJECTS, embeddingObjects(storage.connection()));
            assertNoEmbeddings(storage.connection());
            assertEquals(before, sources(storage.connection()));
        }
    }

    @Test
    void aReadOnlyOpenOfAVersionFourIndexRequiresMigrationWithoutChangingIt() throws Exception {
        try (Connection connection = openVersionFour(root)) {
            seed(connection);
        }

        Path database = root.resolve("index.db");
        byte[] before = Files.readAllBytes(database);

        IndexAccessException failure =
                assertThrows(IndexAccessException.class, () -> SqliteStorage.openReadOnly(database, root));

        assertEquals(IndexAccessException.Reason.MIGRATION_REQUIRED, failure.reason());
        assertArrayEquals(before, Files.readAllBytes(database));
    }

    @ParameterizedTest
    @ValueSource(strings = {"partial-schema", "after-schema", "altered-schema", "missing-trigger"})
    void aFailedEmbeddingMigrationRollsBackAndCanBeRetried(String failurePoint) throws Exception {
        try (Connection connection = openVersionFour(root)) {
            seed(connection);
        }

        Path database = root.resolve("index.db");
        String canonical = resource(V5_RESOURCE);
        String failing =
                switch (failurePoint) {
                    case "partial-schema" -> "CREATE TABLE embedding_profiles(id INTEGER); INVALID SQL;";
                    case "after-schema" -> canonical + "\nINSERT INTO missing_target VALUES (1);";
                    case "altered-schema" -> canonical.replace("dimensions <= 65536", "dimensions <= 65537");
                    case "missing-trigger" -> canonical + "\nDROP TRIGGER chunks_embedding_update;";
                    default -> throw new AssertionError(failurePoint);
                };
        assertFalse(canonical.equals(failing));

        try (Connection connection = raw(database)) {
            byte[] before = Files.readAllBytes(database);

            assertThrows(
                    SQLException.class,
                    () -> SqliteSchemaInitializer.load(connection, rootUri())
                            .initialize(
                                    resource(V1_RESOURCE),
                                    resource(V2_RESOURCE),
                                    resource(V3_RESOURCE),
                                    resource(V4_RESOURCE),
                                    failing,
                                    SqliteFtsSupport::verify));

            assertEquals(4, scalar(connection, "PRAGMA user_version"));
            assertEquals(List.of(), embeddingObjects(connection));
            assertArrayEquals(before, Files.readAllBytes(database));

            SqliteSchemaInitializer.load(connection, rootUri()).initialize();

            assertCurrentVersion(connection);
            assertEquals(EMBEDDING_OBJECTS, embeddingObjects(connection));
        }
    }

    @Test
    void aHealthyCurrentIndexSkipsTheEmbeddingMigration() throws Exception {
        Path database = root.resolve("index.db");
        SqliteStorage.open(database, root).close();
        byte[] before = Files.readAllBytes(database);

        try (Connection connection = raw(database)) {
            SqliteSchemaInitializer.load(connection, rootUri())
                    .initialize(
                            "INVALID V1 SQL;",
                            "INVALID V2 SQL;",
                            "INVALID V3 SQL;",
                            "INVALID V4 SQL;",
                            "INVALID V5 SQL;",
                            SqliteFtsSupport::verify);

            assertCurrentVersion(connection);
        }

        assertArrayEquals(before, Files.readAllBytes(database));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "DROP TRIGGER chunks_embedding_update",
                "DROP TRIGGER embedding_profiles_update",
                "DROP INDEX chunk_embeddings_profile",
                "DROP TABLE chunk_embeddings"
            })
    void aCurrentIndexWithDamagedEmbeddingSchemaIsRejectedWithoutChanges(String damage) throws Exception {
        Path database = root.resolve("index.db");
        SqliteStorage.open(database, root).close();

        try (Connection connection = raw(database)) {
            execute(connection, damage);
        }

        byte[] before = Files.readAllBytes(database);

        for (boolean readOnly : new boolean[] {false, true}) {
            IndexAccessException failure = assertThrows(
                    IndexAccessException.class,
                    () -> (readOnly ? SqliteStorage.openReadOnly(database, root) : SqliteStorage.open(database, root))
                            .close());

            assertEquals(IndexAccessException.Reason.CORRUPT_INDEX, failure.reason(), damage);
            assertTrue(failure.getMessage().contains("embedding"), failure.getMessage());
        }

        assertArrayEquals(before, Files.readAllBytes(database));
    }

    private String rootUri() throws Exception {
        return root.toRealPath().toUri().toASCIIString();
    }

    private static void seed(Connection connection) throws SQLException {
        insertFile(connection, 4, "docs/guide.md");
        insertChunk(connection, 11, 4, 0, "embeddingbody");
        insertChunk(connection, 12, 4, 1, "second chunk");
    }

    private static void assertCurrentVersion(Connection connection) throws SQLException {
        assertEquals(5, scalar(connection, "PRAGMA user_version"));
        assertEquals(5, scalar(connection, "SELECT index_format_version FROM index_metadata"));
    }

    private static void assertNoEmbeddings(Connection connection) throws SQLException {
        assertEquals(0, scalar(connection, "SELECT count(*) FROM embedding_profiles"));
        assertEquals(0, scalar(connection, "SELECT count(*) FROM chunk_embeddings"));
    }

    private static List<String> embeddingObjects(Connection connection) throws SQLException {
        List<String> names = new ArrayList<>();

        for (List<String> row : queryRows(connection, """
                SELECT name FROM sqlite_schema
                WHERE name IN ('embedding_profiles', 'chunk_embeddings', 'chunk_embeddings_profile',
                    'chunk_embeddings_dimensions_insert', 'chunk_embeddings_dimensions_update',
                    'chunks_embedding_update', 'embedding_profiles_update')
                ORDER BY name
                """)) {
            names.add(row.getFirst());
        }

        return names;
    }

    private static List<List<String>> sources(Connection connection) throws SQLException {
        return queryRows(connection, """
            SELECT 'file', id, source_path, content_hash FROM files
            UNION ALL SELECT 'chunk', id, file_id, content FROM chunks
            ORDER BY 1, 2
            """);
    }

    private static List<List<String>> queryRows(Connection connection, String sql) throws SQLException {
        List<List<String>> rows = new ArrayList<>();

        try (var statement = connection.createStatement();
                var result = statement.executeQuery(sql)) {
            int columns = result.getMetaData().getColumnCount();

            while (result.next()) {
                List<String> row = new ArrayList<>();

                for (int column = 1; column <= columns; column++) {
                    row.add(result.getString(column));
                }

                rows.add(List.copyOf(row));
            }
        }

        return List.copyOf(rows);
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
