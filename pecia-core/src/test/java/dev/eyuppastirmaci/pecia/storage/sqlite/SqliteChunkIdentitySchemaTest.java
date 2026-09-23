package dev.eyuppastirmaci.pecia.storage.sqlite;

import static dev.eyuppastirmaci.pecia.storage.sqlite.SqliteFtsTestSupport.assertConsistent;
import static dev.eyuppastirmaci.pecia.storage.sqlite.SqliteFtsTestSupport.execute;
import static dev.eyuppastirmaci.pecia.storage.sqlite.SqliteFtsTestSupport.insertChunk;
import static dev.eyuppastirmaci.pecia.storage.sqlite.SqliteFtsTestSupport.insertFile;
import static dev.eyuppastirmaci.pecia.storage.sqlite.SqliteFtsTestSupport.insertHeading;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.sqlite.JDBC;
import org.sqlite.SQLiteConfig;

class SqliteChunkIdentitySchemaTest {
    private static final String STABLE_ID = "a".repeat(64);
    private static final String OTHER_ID = "b".repeat(64);
    private static final String RETAINED_ID = "c".repeat(64);
    private static final String FINGERPRINT = "d".repeat(64);
    private static final String VOCABULARY_SHA256 = "e".repeat(64);

    @TempDir
    Path root;

    @ParameterizedTest
    @ValueSource(
            strings = {
                "file_id = 0",
                "file_id = 99",
                "extraction_version = NULL",
                "extraction_version = ''",
                "extraction_version = '   '",
                "extraction_version = X'616263'",
                "chunking_version = NULL",
                "chunking_version = ''",
                "chunking_version = '   '",
                "chunking_version = X'616263'",
                "tokenizer_algorithm = NULL",
                "tokenizer_algorithm = ''",
                "tokenizer_algorithm = '   '",
                "tokenizer_algorithm = X'616263'",
                "vocabulary_sha256 = NULL",
                "vocabulary_sha256 = ''",
                "vocabulary_sha256 = upper(vocabulary_sha256)",
                "vocabulary_sha256 = substr(vocabulary_sha256, 2)",
                "vocabulary_sha256 = vocabulary_sha256 || 'a'",
                "vocabulary_sha256 = vocabulary_sha256 || char(0)",
                "vocabulary_sha256 = substr(vocabulary_sha256, 1, 63) || char(0)",
                "vocabulary_sha256 = replace(vocabulary_sha256, 'e', 'g')",
                "vocabulary_sha256 = CAST(vocabulary_sha256 AS BLOB)",
                "vocabulary_size = NULL",
                "vocabulary_size = 0",
                "vocabulary_size = -1",
                "vocabulary_size = 1.5",
                "vocabulary_size = 2147483648",
                "max_input_tokens = NULL",
                "max_input_tokens = 0",
                "max_input_tokens = -1",
                "max_input_tokens = 512.5",
                "max_input_tokens = 2147483648",
                "special_token_count = NULL",
                "special_token_count = -1",
                "special_token_count = 1.5",
                "special_token_count = max_input_tokens",
                "max_tokens = NULL",
                "max_tokens = 0",
                "max_tokens = special_token_count",
                "max_tokens = max_input_tokens + 1",
                "max_tokens = 256.5",
                "max_tokens = 2147483648",
                "overlap_tokens = NULL",
                "overlap_tokens = -1",
                "overlap_tokens = 0.5",
                "overlap_tokens = max_tokens - special_token_count",
                "fingerprint = NULL",
                "fingerprint = ''",
                "fingerprint = upper(fingerprint)",
                "fingerprint = substr(fingerprint, 2)",
                "fingerprint = fingerprint || 'a'",
                "fingerprint = fingerprint || char(0)",
                "fingerprint = substr(fingerprint, 1, 63) || char(0)",
                "fingerprint = replace(fingerprint, 'd', 'g')",
                "fingerprint = CAST(fingerprint AS BLOB)"
            })
    void rejectsInvalidCompatibilityFieldsWithoutLosingExistingIdentity(String assignment) throws Exception {
        try (SqliteStorage storage = populatedIndex()) {
            Connection connection = storage.connection();

            assertThrows(
                    SQLException.class,
                    () -> execute(
                            connection, "UPDATE file_chunking_profiles SET " + assignment + " WHERE file_id = 7"));

            assertEquals(List.of(7L, 8L, 9L), ids(connection, "file_chunking_profiles", "file_id"));
            assertEquals(List.of(41L, 42L, 43L), ids(connection, "chunk_identities", "chunk_id"));
            assertEquals(STABLE_ID, stableId(connection, 41));
        }
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "vocabulary_size = 1, max_input_tokens = 1, special_token_count = 0, max_tokens = 1, overlap_tokens ="
                        + " 0",
                "special_token_count = 255, max_tokens = 256, overlap_tokens = 0",
                "max_tokens = 512, overlap_tokens = 509",
                "vocabulary_size = 2147483647, max_input_tokens = 2147483647, max_tokens = 2147483647,"
                        + " overlap_tokens = 2147483644"
            })
    void acceptsBoundaryCompatibilityBudgets(String assignments) throws Exception {
        try (SqliteStorage storage = populatedIndex()) {
            Connection connection = storage.connection();

            execute(connection, "UPDATE file_chunking_profiles SET " + assignments + " WHERE file_id = 7");

            assertEquals(List.of(7L, 8L, 9L), ids(connection, "file_chunking_profiles", "file_id"));
            assertEquals(List.of(42L, 43L), ids(connection, "chunk_identities", "chunk_id"));
        }
    }

    @Test
    void preservesVersionAndAlgorithmTextIncludingUnicodeAndLeadingNul() throws Exception {
        String extraction = "\0extract-İstanbul-😀";
        String chunking = "\0chunk-é-e\u0301";
        String algorithm = "\0tokenize-日本語";
        try (SqliteStorage storage = populatedIndex()) {
            Connection connection = storage.connection();
            execute(connection, """
                UPDATE file_chunking_profiles
                SET extraction_version = ?, chunking_version = ?, tokenizer_algorithm = ? WHERE file_id = 7
                """, extraction, chunking, algorithm);
        }

        try (SqliteStorage storage = SqliteStorage.openReadOnly(root.resolve("index.db"), root);
                var statement = storage.connection().createStatement();
                var row = statement.executeQuery("""
                    SELECT extraction_version, chunking_version, tokenizer_algorithm
                    FROM file_chunking_profiles WHERE file_id = 7
                    """)) {
            assertTrue(row.next());
            assertEquals(extraction, row.getString(1));
            assertEquals(chunking, row.getString(2));
            assertEquals(algorithm, row.getString(3));
            assertFalse(row.next());
        }
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "NULL",
                "''",
                "upper(stable_id)",
                "substr(stable_id, 2)",
                "stable_id || 'a'",
                "stable_id || char(0)",
                "substr(stable_id, 1, 63) || char(0)",
                "replace(stable_id, 'a', 'g')",
                "CAST(stable_id AS BLOB)"
            })
    void rejectsNoncanonicalStableIds(String value) throws Exception {
        try (SqliteStorage storage = populatedIndex()) {
            Connection connection = storage.connection();

            assertThrows(
                    SQLException.class,
                    () -> execute(
                            connection, "UPDATE chunk_identities SET stable_id = " + value + " WHERE chunk_id = 41"));

            assertEquals(STABLE_ID, stableId(connection, 41));
        }
    }

    @Test
    void rejectsDuplicateStableIdsAndChunkPositionsWithoutChangingExistingRows() throws Exception {
        try (SqliteStorage storage = populatedIndex()) {
            Connection connection = storage.connection();

            assertThrows(
                    SQLException.class,
                    () -> execute(
                            connection, "UPDATE chunk_identities SET stable_id = ? WHERE chunk_id = 42", STABLE_ID));
            assertThrows(SQLException.class, () -> insertIdentity(connection, 41, 7, "f".repeat(64)));
            assertThrows(SQLException.class, () -> insertChunk(connection, 44, 7, 0, "duplicate position"));

            assertEquals(STABLE_ID, stableId(connection, 41));
            assertEquals(OTHER_ID, stableId(connection, 42));
            assertEquals(List.of(41L, 42L, 43L), ids(connection, "chunks", "id"));
            assertEquals(List.of(7L, 8L, 9L), ids(connection, "file_chunking_profiles", "file_id"));
        }
    }

    @Test
    void identitiesRequireAnExistingChunkOwnedByTheirFile() throws Exception {
        try (SqliteStorage storage = populatedIndex()) {
            Connection connection = storage.connection();
            execute(connection, "DELETE FROM chunk_identities WHERE chunk_id = 41");

            assertThrows(SQLException.class, () -> insertIdentity(connection, 41, 8, STABLE_ID));
            assertThrows(SQLException.class, () -> insertIdentity(connection, 999, 7, STABLE_ID));
            assertThrows(SQLException.class, () -> insertIdentity(connection, 41, 999, STABLE_ID));

            assertEquals(List.of(42L, 43L), ids(connection, "chunk_identities", "chunk_id"));
            insertIdentity(connection, 41, 7, STABLE_ID);
            assertEquals(STABLE_ID, stableId(connection, 41));
        }
    }

    @Test
    void missingCompatibilityFailsAutocommitAndDeferredCommitWithoutRemovingSourceData() throws Exception {
        try (SqliteStorage storage = populatedIndex()) {
            Connection connection = storage.connection();
            execute(connection, "DELETE FROM file_chunking_profiles WHERE file_id = 7");
            assertThrows(SQLException.class, () -> insertIdentity(connection, 41, 7, STABLE_ID));
            assertEquals(List.of(42L, 43L), ids(connection, "chunk_identities", "chunk_id"));

            connection.setAutoCommit(false);
            try {
                insertIdentity(connection, 41, 7, STABLE_ID);
                assertEquals(STABLE_ID, stableId(connection, 41));
                assertThrows(SQLException.class, connection::commit);
                connection.rollback();
            } finally {
                connection.setAutoCommit(true);
            }

            assertEquals(List.of(42L, 43L), ids(connection, "chunk_identities", "chunk_id"));
            assertEquals(List.of(41L, 42L, 43L), ids(connection, "chunks", "id"));
            assertConsistent(connection);
        }
    }

    @Test
    void permitsStampingIdentitiesAfterAllContentAndWritingCompatibilityLast() throws Exception {
        Path database = root.resolve("index.db");
        try (SqliteStorage storage = SqliteStorage.open(database, root)) {
            Connection connection = storage.connection();
            connection.setAutoCommit(false);
            try {
                insertFile(connection, 7, "docs/İstanbul.md");
                insertChunk(connection, 41, 7, 0, "first chunk");
                insertChunk(connection, 42, 7, 1, "second chunk");
                insertHeading(connection, 41, 0, "Heading");
                execute(connection, "INSERT INTO chunk_attributes VALUES (42, 'startOffset', '12')");
                insertIdentity(connection, 41, 7, STABLE_ID);
                insertIdentity(connection, 42, 7, OTHER_ID);
                assertTrue(ids(connection, "file_chunking_profiles", "file_id").isEmpty());

                insertProfile(connection, 7);
                connection.commit();
            } finally {
                connection.setAutoCommit(true);
            }
            assertConsistent(connection);
        }

        try (SqliteStorage storage = SqliteStorage.openReadOnly(database, root)) {
            assertEquals(List.of(7L), ids(storage.connection(), "file_chunking_profiles", "file_id"));
            assertEquals(STABLE_ID, stableId(storage.connection(), 41));
            assertEquals(OTHER_ID, stableId(storage.connection(), 42));
        }
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "UPDATE files SET source_path = 'renamed.md' WHERE id = 7",
                "UPDATE files SET content_hash = replace(content_hash, 'a', 'b') WHERE id = 7",
                "UPDATE files SET document_type = 'PLAIN_TEXT' WHERE id = 7",
                "INSERT INTO chunks VALUES (44, 7, 1, 'added chunk', 1, 1)",
                "UPDATE chunks SET content = 'modified body' WHERE id = 41",
                "UPDATE chunks SET chunk_index = 1 WHERE id = 41",
                "DELETE FROM chunks WHERE id = 41",
                "INSERT INTO chunk_headings VALUES (41, 1, 'Added heading')",
                "UPDATE chunk_headings SET heading = 'Changed heading' WHERE chunk_id = 41",
                "DELETE FROM chunk_headings WHERE chunk_id = 41",
                "INSERT INTO chunk_attributes VALUES (41, 'extra', 'value')",
                "UPDATE chunk_attributes SET value = 'changed' WHERE chunk_id = 41",
                "DELETE FROM chunk_attributes WHERE chunk_id = 41"
            })
    void contentWritesInvalidateTheirFullCompatibilityAndStableIdsOnly(String sql) throws Exception {
        try (SqliteStorage storage = populatedIndex()) {
            Connection connection = storage.connection();

            execute(connection, sql);

            assertEquals(List.of(8L, 9L), ids(connection, "file_chunking_profiles", "file_id"));
            assertEquals(List.of(42L, 43L), ids(connection, "chunk_identities", "chunk_id"));
            assertEquals(OTHER_ID, stableId(connection, 42));
            assertEquals(RETAINED_ID, stableId(connection, 43));
            assertConsistent(connection);
        }
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "UPDATE chunks SET file_id = 8, chunk_index = 1 WHERE id = 41",
                "UPDATE chunk_headings SET chunk_id = 42, position = 1 WHERE chunk_id = 41",
                "UPDATE chunk_attributes SET chunk_id = 42, name = 'moved' WHERE chunk_id = 41"
            })
    void movingChunksOrMetadataInvalidatesBothOwnersAndPreservesOtherFiles(String sql) throws Exception {
        try (SqliteStorage storage = populatedIndex()) {
            Connection connection = storage.connection();

            execute(connection, sql);

            assertEquals(List.of(9L), ids(connection, "file_chunking_profiles", "file_id"));
            assertEquals(List.of(43L), ids(connection, "chunk_identities", "chunk_id"));
            assertEquals(RETAINED_ID, stableId(connection, 43));
            assertConsistent(connection);
        }
    }

    @Test
    void updatingCompatibilityClearsOnlyItsStableIds() throws Exception {
        try (SqliteStorage storage = populatedIndex()) {
            Connection connection = storage.connection();

            execute(connection, "UPDATE file_chunking_profiles SET chunking_version = 'changed-v2' WHERE file_id = 7");

            assertEquals(List.of(7L, 8L, 9L), ids(connection, "file_chunking_profiles", "file_id"));
            assertEquals(List.of(42L, 43L), ids(connection, "chunk_identities", "chunk_id"));
            assertEquals(List.of(41L, 42L, 43L), ids(connection, "chunks", "id"));
            assertConsistent(connection);
        }
    }

    @Test
    void deletingAFileCascadesToItsCompatibilityAndStableIds() throws Exception {
        try (SqliteStorage storage = populatedIndex()) {
            Connection connection = storage.connection();

            execute(connection, "DELETE FROM files WHERE id = 7");

            assertEquals(List.of(8L, 9L), ids(connection, "file_chunking_profiles", "file_id"));
            assertEquals(List.of(42L, 43L), ids(connection, "chunk_identities", "chunk_id"));
            assertEquals(List.of(42L, 43L), ids(connection, "chunks", "id"));
            assertConsistent(connection);
        }
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "file_chunking_profiles",
                "chunk_identities",
                "chunks_identity_owner",
                "chunk_identities_file",
                "files_chunking_profile_update",
                "chunks_chunking_profile_insert",
                "chunks_chunking_profile_update",
                "chunks_chunking_profile_delete",
                "chunk_headings_chunking_profile_insert",
                "chunk_headings_chunking_profile_update",
                "chunk_headings_chunking_profile_delete",
                "chunk_attributes_chunking_profile_insert",
                "chunk_attributes_chunking_profile_update",
                "chunk_attributes_chunking_profile_delete",
                "file_chunking_profiles_update"
            })
    void rejectsEveryMissingV4ObjectForWritersAndReadersWithoutRepair(String name) throws Exception {
        Path database = root.resolve("index.db");
        try (SqliteStorage storage = populatedIndex()) {
            assertEquals(STABLE_ID, stableId(storage.connection(), 41));
        }
        try (Connection connection = raw(database)) {
            String type = objectType(connection, name);
            execute(connection, "DROP " + type + " " + name);
        }

        assertRejectedWithoutMutation(database);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "compatibility_constraint",
                "stable_id_constraint",
                "profile_deferral",
                "owner_foreign_key",
                "owner_index",
                "identity_file_index",
                "trigger_body"
            })
    void rejectsAlteredV4DefinitionsForWritersAndReadersWithoutRepair(String damage) throws Exception {
        Path database = root.resolve("index.db");
        try (SqliteStorage storage = populatedIndex()) {
            assertEquals(STABLE_ID, stableId(storage.connection(), 41));
        }
        try (Connection connection = raw(database)) {
            String name =
                    switch (damage) {
                        case "compatibility_constraint" -> "file_chunking_profiles";
                        case "stable_id_constraint", "profile_deferral", "owner_foreign_key" -> "chunk_identities";
                        case "owner_index" -> "chunks_identity_owner";
                        case "identity_file_index" -> "chunk_identities_file";
                        case "trigger_body" -> "chunks_chunking_profile_insert";
                        default -> throw new AssertionError(damage);
                    };
            String original = objectDdl(connection, name);
            String altered =
                    switch (damage) {
                        case "compatibility_constraint" ->
                            original.replace("max_tokens <= max_input_tokens", "max_tokens > 0");
                        case "stable_id_constraint" ->
                            original.replace(
                                    "length(CAST(stable_id AS BLOB)) = 64", "length(CAST(stable_id AS BLOB)) > 0");
                        case "profile_deferral" ->
                            original.replace("DEFERRABLE INITIALLY DEFERRED", "NOT DEFERRABLE INITIALLY IMMEDIATE");
                        case "owner_foreign_key" ->
                            original.replace(
                                    "FOREIGN KEY (chunk_id, file_id) REFERENCES chunks(id, file_id)",
                                    "FOREIGN KEY (chunk_id) REFERENCES chunks(id)");
                        case "owner_index" -> original.replace("UNIQUE INDEX", "INDEX");
                        case "identity_file_index" -> original.replace("(file_id)", "(chunk_id)");
                        case "trigger_body" -> "CREATE TRIGGER " + name + " AFTER INSERT ON chunks BEGIN SELECT 1; END";
                        default -> throw new AssertionError(damage);
                    };
            assertNotEquals(original, altered, "The fixture must actually change a schema definition");
            if (objectType(connection, name).equals("table")) {
                // Preserve source rows while simulating an externally altered table definition.
                execute(connection, "PRAGMA writable_schema = ON");
                execute(connection, "UPDATE sqlite_schema SET sql = ? WHERE name = ?", altered, name);
                execute(connection, "PRAGMA writable_schema = OFF");
            } else {
                execute(connection, "DROP " + objectType(connection, name) + " " + name);
                execute(connection, altered);
            }
        }

        assertRejectedWithoutMutation(database);
    }

    private SqliteStorage populatedIndex() throws Exception {
        SqliteStorage storage = SqliteStorage.open(root.resolve("index.db"), root);
        try {
            Connection connection = storage.connection();
            for (int offset = 0; offset < 3; offset++) {
                int fileId = 7 + offset;
                int chunkId = 41 + offset;
                insertFile(connection, fileId, "docs/İstanbul-" + offset + ".md");
                insertChunk(connection, chunkId, fileId, 0, "body-" + offset);
                insertHeading(connection, chunkId, 0, "Heading-" + offset);
                execute(connection, "INSERT INTO chunk_attributes VALUES (?, 'startOffset', '0')", chunkId);
                insertProfile(connection, fileId);
                insertIdentity(
                        connection,
                        chunkId,
                        fileId,
                        List.of(STABLE_ID, OTHER_ID, RETAINED_ID).get(offset));
            }
            return storage;
        } catch (Exception | Error failure) {
            storage.close();
            throw failure;
        }
    }

    private void assertRejectedWithoutMutation(Path database) throws Exception {
        byte[] before = Files.readAllBytes(database);
        for (boolean readOnly : List.of(false, true)) {
            SQLException failure = assertThrows(
                    SQLException.class,
                    () -> (readOnly ? SqliteStorage.openReadOnly(database, root) : SqliteStorage.open(database, root))
                            .close(),
                    "Damaged schema was accepted");
            if (readOnly) {
                assertEquals(
                        IndexAccessException.Reason.CORRUPT_INDEX,
                        assertInstanceOf(IndexAccessException.class, failure).reason());
            }
            assertArrayEquals(before, Files.readAllBytes(database), "A rejected open must not alter the database");
        }
    }

    private static void insertProfile(Connection connection, long fileId) throws SQLException {
        execute(connection, """
            INSERT INTO file_chunking_profiles (
                file_id, extraction_version, chunking_version, tokenizer_algorithm, vocabulary_sha256,
                vocabulary_size, max_input_tokens, special_token_count, max_tokens, overlap_tokens, fingerprint
            ) VALUES (?, 'extraction-v1', 'chunking-v1', 'wordpiece-v1', ?, 30522, 512, 2, 256, 32, ?)
            """, fileId, VOCABULARY_SHA256, FINGERPRINT);
    }

    private static void insertIdentity(Connection connection, long chunkId, long fileId, String stableId)
            throws SQLException {
        execute(
                connection,
                "INSERT INTO chunk_identities (chunk_id, file_id, stable_id) VALUES (?, ?, ?)",
                chunkId,
                fileId,
                stableId);
    }

    private static List<Long> ids(Connection connection, String table, String column) throws SQLException {
        List<Long> values = new ArrayList<>();
        try (var statement = connection.createStatement();
                var rows = statement.executeQuery("SELECT " + column + " FROM " + table + " ORDER BY " + column)) {
            while (rows.next()) {
                values.add(rows.getLong(1));
            }
        }
        return List.copyOf(values);
    }

    private static String stableId(Connection connection, long chunkId) throws SQLException {
        try (var statement = connection.prepareStatement("SELECT stable_id FROM chunk_identities WHERE chunk_id = ?")) {
            statement.setLong(1, chunkId);
            try (var result = statement.executeQuery()) {
                assertTrue(result.next());
                String id = result.getString(1);
                assertFalse(result.next());
                return id;
            }
        }
    }

    private static String objectType(Connection connection, String name) throws SQLException {
        return schemaField(connection, name, "type");
    }

    private static String objectDdl(Connection connection, String name) throws SQLException {
        return schemaField(connection, name, "sql");
    }

    private static String schemaField(Connection connection, String name, String field) throws SQLException {
        try (var statement = connection.prepareStatement("SELECT " + field + " FROM sqlite_schema WHERE name = ?")) {
            statement.setString(1, name);
            try (var result = statement.executeQuery()) {
                assertTrue(result.next(), "Missing fixture object: " + name);
                return result.getString(1);
            }
        }
    }

    private static Connection raw(Path database) throws SQLException {
        SQLiteConfig config = new SQLiteConfig();
        config.enforceForeignKeys(true);
        return JDBC.createConnection("jdbc:sqlite:" + database.toUri().toASCIIString(), config.toProperties());
    }
}
