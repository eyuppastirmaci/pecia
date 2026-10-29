package dev.eyuppastirmaci.pecia.storage.sqlite;

import static dev.eyuppastirmaci.pecia.storage.sqlite.SqliteFtsTestSupport.execute;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.eyuppastirmaci.pecia.chunking.ChunkingIdentity;
import dev.eyuppastirmaci.pecia.content.Chunk;
import dev.eyuppastirmaci.pecia.content.ChunkMetadata;
import dev.eyuppastirmaci.pecia.content.ContentHash;
import dev.eyuppastirmaci.pecia.content.Document;
import dev.eyuppastirmaci.pecia.content.DocumentType;
import dev.eyuppastirmaci.pecia.content.LineRange;
import dev.eyuppastirmaci.pecia.semantic.EmbeddingProfile;
import dev.eyuppastirmaci.pecia.storage.model.StoredFile;
import dev.eyuppastirmaci.pecia.storage.model.UnembeddedChunk;
import dev.eyuppastirmaci.pecia.tokenization.TokenizerCompatibility;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Stores chunk vectors with their files' chunks, and keeps them only as long as those chunks exist. */
class SqliteEmbeddingStorageTest {

    private static final TokenizerCompatibility TOKENIZER =
            new TokenizerCompatibility("wordpiece", "a".repeat(64), 100, 512, 2);
    private static final ChunkingIdentity IDENTITY = new ChunkingIdentity("extract-v1", "chunk-v1", TOKENIZER, 256, 32);
    private static final EmbeddingProfile PROFILE = profile("mean");
    private static final EmbeddingProfile OTHER_PROFILE = profile("cls");

    @TempDir
    Path root;

    @Test
    void vectorsAreStoredWithTheirChunksAndProfileAndSurviveReopening() throws Exception {
        Path database = root.resolve("index.db");
        Document document = document("guide.md", "guidebody");
        List<float[]> vectors = List.of(new float[] {1, 0, -0.5f, 0.25f}, new float[] {0, 1, 2, -3});

        try (SqliteStorage storage = SqliteStorage.open(database, root)) {
            storage.replaceFile(document, chunks(document), IDENTITY, PROFILE, vectors);
        }

        byte[] before = Files.readAllBytes(database);

        try (SqliteStorage storage = SqliteStorage.openReadOnly(database, root)) {
            assertVectors(storage.connection(), Map.of("guide.md#0", vectors.get(0), "guide.md#1", vectors.get(1)));
            assertEquals(
                    List.of(List.of(
                            "test/model",
                            "rev",
                            "b".repeat(64),
                            4L,
                            "wordpiece-v2",
                            "c".repeat(64),
                            256L,
                            "mean",
                            "l2",
                            "chunk-content",
                            PROFILE.fingerprint())),
                    rows(storage.connection(), """
                        SELECT model_id, model_revision, model_sha256, dimensions, tokenizer_algorithm,
                               vocabulary_sha256, max_input_tokens, pooling, normalization, input, fingerprint
                        FROM embedding_profiles
                        """));
        }

        assertArrayEquals(before, Files.readAllBytes(database));
    }

    @Test
    void vectorsAreLittleEndianFloat32() {
        byte[] encoded = SqliteEmbeddingRepository.encode(new float[] {1.0f, -2.5f});

        assertArrayEquals(new byte[] {0, 0, (byte) 0x80, 0x3f, 0, 0, 0x20, (byte) 0xc0}, encoded);
        assertArrayEquals(new float[] {1.0f, -2.5f}, SqliteEmbeddingRepository.decode(encoded));
    }

    @Test
    void aProfileIsStoredOnceAndReusedAcrossFilesAndRuns() throws Exception {
        Path database = root.resolve("index.db");

        try (SqliteStorage storage = SqliteStorage.open(database, root)) {
            replace(storage, "first.md", PROFILE);
            replace(storage, "second.md", PROFILE);
        }

        try (SqliteStorage storage = SqliteStorage.open(database, root)) {
            replace(storage, "third.md", PROFILE);
            replace(storage, "fourth.md", OTHER_PROFILE);

            assertEquals(
                    List.of(List.of(PROFILE.fingerprint(), 6L), List.of(OTHER_PROFILE.fingerprint(), 2L)),
                    rows(storage.connection(), """
                        SELECT p.fingerprint, count(e.chunk_id) FROM embedding_profiles p
                        LEFT JOIN chunk_embeddings e ON e.profile_id = p.id
                        GROUP BY p.id ORDER BY p.id
                        """));
        }
    }

    @Test
    void replacingAFileReplacesOrRemovesOnlyItsVectors() throws Exception {
        try (SqliteStorage storage = SqliteStorage.open(root.resolve("index.db"), root)) {
            replace(storage, "kept.md", PROFILE);
            replace(storage, "changed.md", PROFILE);
            Document changed = document("changed.md", "newbody");
            List<float[]> replacement = vectors(2, 9);

            storage.replaceFile(changed, chunks(changed), IDENTITY, PROFILE, replacement);

            assertVectors(
                    storage.connection(),
                    Map.of(
                            "changed.md#0", replacement.get(0),
                            "changed.md#1", replacement.get(1),
                            "kept.md#0", vectors(2, 1).get(0),
                            "kept.md#1", vectors(2, 1).get(1)));

            // A replacement without vectors, as a lexical run writes it, leaves the file without any.
            Document lexical = document("changed.md", "lexicalbody");
            storage.replaceFile(lexical, chunks(lexical), IDENTITY);

            assertEquals(Map.of("kept.md#0", 4, "kept.md#1", 4), vectorKeys(storage.connection()));
        }
    }

    @Test
    void deletingFilesDeletesTheirVectors() throws Exception {
        try (SqliteStorage storage = SqliteStorage.open(root.resolve("index.db"), root)) {
            StoredFile removed = replace(storage, "removed.md", PROFILE);
            replace(storage, "kept.md", PROFILE);

            assertEquals(1, storage.deleteFiles(List.of(removed)));

            assertEquals(Map.of("kept.md#0", 4, "kept.md#1", 4), vectorKeys(storage.connection()));
        }
    }

    @Test
    void anEmptyFileStoresNoVectors() throws Exception {
        try (SqliteStorage storage = SqliteStorage.open(root.resolve("index.db"), root)) {
            Document empty = document("empty.md", "");

            storage.replaceFile(empty, List.of(), IDENTITY, PROFILE, List.of());

            assertEquals(Map.of(), vectorKeys(storage.connection()));
        }
    }

    @Test
    void vectorsThatDoNotMatchTheChunksOrProfileAreRejectedBeforeAnyWrite() throws Exception {
        Path database = root.resolve("index.db");

        try (SqliteStorage storage = SqliteStorage.open(database, root)) {
            replace(storage, "guide.md", PROFILE);
        }

        byte[] before = Files.readAllBytes(database);
        Document document = document("guide.md", "otherbody");
        List<Chunk> chunks = chunks(document);
        List<float[]> nullVector = new ArrayList<>(List.of(new float[4]));
        nullVector.add(null);

        try (SqliteStorage storage = SqliteStorage.open(database, root)) {
            for (List<float[]> invalid : List.of(
                    vectors(1, 1),
                    vectors(3, 1),
                    List.of(new float[4], new float[3]),
                    List.of(new float[4], new float[] {0, Float.NaN, 0, 0}),
                    List.of(new float[] {Float.POSITIVE_INFINITY, 0, 0, 0}, new float[4]))) {
                assertThrows(
                        IllegalArgumentException.class,
                        () -> storage.replaceFile(document, chunks, IDENTITY, PROFILE, invalid));
            }

            assertThrows(
                    NullPointerException.class,
                    () -> storage.replaceFile(document, chunks, IDENTITY, PROFILE, nullVector));
            assertThrows(
                    NullPointerException.class,
                    () -> storage.replaceFile(document, chunks, IDENTITY, null, vectors(2, 1)));
            assertThrows(
                    NullPointerException.class, () -> storage.replaceFile(document, chunks, IDENTITY, PROFILE, null));
        }

        assertArrayEquals(before, Files.readAllBytes(database));
    }

    @Test
    void aFailedVectorWriteRestoresTheFilesPreviousChunksAndVectors() throws Exception {
        try (SqliteStorage storage = SqliteStorage.open(root.resolve("index.db"), root)) {
            replace(storage, "guide.md", PROFILE);
            Map<String, Integer> vectorsBefore = vectorKeys(storage.connection());
            List<List<Object>> chunksBefore = rows(storage.connection(), "SELECT id, content FROM chunks ORDER BY id");
            execute(storage.connection(), """
                CREATE TEMP TRIGGER fail_vectors BEFORE INSERT ON chunk_embeddings
                BEGIN SELECT RAISE(ABORT, 'injected vector failure'); END
                """);
            Document document = document("guide.md", "newbody");

            SQLException failure = assertThrows(
                    SQLException.class,
                    () -> storage.replaceFile(document, chunks(document), IDENTITY, PROFILE, vectors(2, 5)));

            assertTrue(failure.getMessage().contains("injected vector failure"), failure.getMessage());
            assertEquals(vectorsBefore, vectorKeys(storage.connection()));
            assertEquals(chunksBefore, rows(storage.connection(), "SELECT id, content FROM chunks ORDER BY id"));
        }
    }

    @Test
    void theSchemaEnforcesVectorShapesAndKeepsProfilesAndChunkTextConsistent() throws Exception {
        try (SqliteStorage storage = SqliteStorage.open(root.resolve("index.db"), root)) {
            replace(storage, "guide.md", PROFILE);
            Connection connection = storage.connection();
            long chunk = (Long) rows(connection, "SELECT min(chunk_id) FROM chunk_embeddings")
                    .getFirst()
                    .getFirst();
            long profile = (Long) rows(connection, "SELECT id FROM embedding_profiles")
                    .getFirst()
                    .getFirst();

            assertRejected(
                    connection, "UPDATE chunk_embeddings SET vector = ? WHERE chunk_id = ?", new byte[12], chunk);
            assertRejected(
                    connection, "UPDATE chunk_embeddings SET vector = ? WHERE chunk_id = ?", new byte[18], chunk);
            assertRejected(connection, "UPDATE chunk_embeddings SET vector = ? WHERE chunk_id = ?", "text", chunk);
            assertRejected(connection, "UPDATE chunk_embeddings SET profile_id = ? WHERE chunk_id = ?", 99, chunk);
            assertRejected(connection, "UPDATE embedding_profiles SET dimensions = ? WHERE id = ?", 8, profile);
            assertRejected(connection, "UPDATE embedding_profiles SET pooling = ? WHERE id = ?", "cls", profile);

            // Changing a chunk's text in place drops its vector instead of leaving one for the old text.
            execute(connection, "UPDATE chunks SET content = 'edited' WHERE id = ?", chunk);

            assertEquals(Map.of("guide.md#1", 4), vectorKeys(connection));
        }
    }

    @Test
    void storedChunksWithoutVectorsAreFoundPerFileInPositionOrder() throws Exception {
        Path database = root.resolve("index.db");
        long lexicalId;
        long embeddedId;

        try (SqliteStorage storage = SqliteStorage.open(database, root)) {
            Document lexical = document("lexical.md", "lexicalbody");
            lexicalId = storage.replaceFile(lexical, chunks(lexical), IDENTITY).id();
            embeddedId = replace(storage, "embedded.md", PROFILE).id();
        }

        byte[] before = Files.readAllBytes(database);

        try (SqliteStorage storage = SqliteStorage.openReadOnly(database, root)) {
            List<UnembeddedChunk> unembedded = storage.findUnembeddedChunks(lexicalId);

            assertEquals(List.of("lexicalbody part 0", "lexicalbody part 1"), contents(unembedded));
            assertEquals(List.of(), storage.findUnembeddedChunks(embeddedId));
            assertEquals(List.of(), storage.findUnembeddedChunks(Long.MAX_VALUE));
            assertThrows(IllegalArgumentException.class, () -> storage.findUnembeddedChunks(0));
        }

        assertArrayEquals(before, Files.readAllBytes(database));
    }

    @Test
    void embeddingsForStoredChunksAreStoredUnderTheirProfile() throws Exception {
        try (SqliteStorage storage = SqliteStorage.open(root.resolve("index.db"), root)) {
            Document first = document("first.md", "firstbody");
            Document second = document("second.md", "secondbody");
            long firstId = storage.replaceFile(first, chunks(first), IDENTITY).id();
            long secondId =
                    storage.replaceFile(second, chunks(second), IDENTITY).id();
            List<UnembeddedChunk> firstChunks = storage.findUnembeddedChunks(firstId);

            assertEquals(2, storage.storeEmbeddings(PROFILE, firstChunks, vectors(2, 3)));
            assertEquals(2, storage.storeEmbeddings(PROFILE, storage.findUnembeddedChunks(secondId), vectors(2, 4)));

            assertVectors(
                    storage.connection(),
                    Map.of(
                            "first.md#0", vectors(2, 3).get(0),
                            "first.md#1", vectors(2, 3).get(1),
                            "second.md#0", vectors(2, 4).get(0),
                            "second.md#1", vectors(2, 4).get(1)));
            assertEquals(List.of(), storage.findUnembeddedChunks(firstId));
            assertEquals(
                    1L,
                    rows(storage.connection(), "SELECT count(*) FROM embedding_profiles")
                            .getFirst()
                            .getFirst());
            assertEquals(0, storage.storeEmbeddings(PROFILE, List.of(), List.of()));
        }
    }

    @Test
    void chunksChangedDeletedOrEmbeddedMeanwhileAreSkipped() throws Exception {
        try (SqliteStorage storage = SqliteStorage.open(root.resolve("index.db"), root)) {
            List<UnembeddedChunk> pending = new ArrayList<>();
            List<StoredFile> files = new ArrayList<>();

            for (String path : List.of("changed.md", "deleted.md", "embedded.md", "kept.md")) {
                Document document = document(path, path.replace(".md", "body"));
                StoredFile file = storage.replaceFile(document, chunks(document), IDENTITY);
                files.add(file);
                pending.addAll(storage.findUnembeddedChunks(file.id()));
            }

            // While the vectors are being made: one chunk's text changes, a file is deleted, and another run embeds
            // a chunk with different values.
            execute(
                    storage.connection(),
                    "UPDATE chunks SET content = 'edited' WHERE id = ?",
                    pending.get(0).id());
            assertEquals(1, storage.deleteFiles(List.of(files.get(1))));
            assertEquals(1, storage.storeEmbeddings(PROFILE, List.of(pending.get(4)), vectors(1, 7)));

            int stored = storage.storeEmbeddings(PROFILE, pending, vectors(pending.size(), 1));

            assertEquals(4, stored);
            assertVectors(
                    storage.connection(),
                    Map.of(
                            "changed.md#1", vectors(8, 1).get(1),
                            "embedded.md#0", vectors(1, 7).get(0),
                            "embedded.md#1", vectors(8, 1).get(5),
                            "kept.md#0", vectors(8, 1).get(6),
                            "kept.md#1", vectors(8, 1).get(7)));
        }
    }

    @Test
    void embeddingsThatDoNotMatchTheChunksOrProfileAreRejectedBeforeAnyWrite() throws Exception {
        Path database = root.resolve("index.db");
        List<UnembeddedChunk> pending;

        try (SqliteStorage storage = SqliteStorage.open(database, root)) {
            Document document = document("guide.md", "guidebody");
            pending = storage.findUnembeddedChunks(
                    storage.replaceFile(document, chunks(document), IDENTITY).id());
        }

        byte[] before = Files.readAllBytes(database);
        List<float[]> nullVector = new ArrayList<>(List.of(new float[4]));
        nullVector.add(null);

        try (SqliteStorage storage = SqliteStorage.open(database, root)) {
            for (List<float[]> invalid : List.of(
                    vectors(1, 1),
                    vectors(3, 1),
                    List.of(new float[4], new float[5]),
                    List.of(new float[4], new float[] {0, Float.NaN, 0, 0}))) {
                assertThrows(IllegalArgumentException.class, () -> storage.storeEmbeddings(PROFILE, pending, invalid));
            }

            assertThrows(NullPointerException.class, () -> storage.storeEmbeddings(PROFILE, pending, nullVector));
            assertThrows(NullPointerException.class, () -> storage.storeEmbeddings(null, pending, vectors(2, 1)));
            assertThrows(NullPointerException.class, () -> storage.storeEmbeddings(PROFILE, null, vectors(2, 1)));
        }

        assertArrayEquals(before, Files.readAllBytes(database));
    }

    @Test
    void aFailedPageStoresNoneOfItsEmbeddings() throws Exception {
        try (SqliteStorage storage = SqliteStorage.open(root.resolve("index.db"), root)) {
            Document document = document("guide.md", "guidebody");
            List<UnembeddedChunk> pending = storage.findUnembeddedChunks(
                    storage.replaceFile(document, chunks(document), IDENTITY).id());
            execute(storage.connection(), """
                CREATE TEMP TRIGGER fail_second_vector BEFORE INSERT ON chunk_embeddings
                WHEN (SELECT count(*) FROM chunk_embeddings) = 1
                BEGIN SELECT RAISE(ABORT, 'injected vector failure'); END
                """);

            assertThrows(SQLException.class, () -> storage.storeEmbeddings(PROFILE, pending, vectors(2, 1)));

            assertEquals(Map.of(), vectorKeys(storage.connection()));
            assertEquals(pending, storage.findUnembeddedChunks(fileId(storage)));
        }
    }

    private static long fileId(SqliteStorage storage) throws SQLException {
        return storage.files().findAll().getFirst().id();
    }

    private static List<String> contents(List<UnembeddedChunk> chunks) {
        return chunks.stream().map(UnembeddedChunk::content).toList();
    }

    private static void assertRejected(Connection connection, String sql, Object... parameters) {
        assertThrows(SQLException.class, () -> execute(connection, sql, parameters), sql);
    }

    private static StoredFile replace(SqliteStorage storage, String path, EmbeddingProfile profile)
            throws SQLException {
        Document document = document(path, path.replace(".md", "body"));

        return storage.replaceFile(document, chunks(document), IDENTITY, profile, vectors(2, 1));
    }

    /** Returns {@code count} vectors of the test profile's four dimensions, distinct for each seed. */
    private static List<float[]> vectors(int count, int seed) {
        List<float[]> vectors = new ArrayList<>();

        for (int index = 0; index < count; index++) {
            vectors.add(new float[] {seed, index, seed * 0.5f, -index});
        }

        return vectors;
    }

    private static void assertVectors(Connection connection, Map<String, float[]> expected) throws SQLException {
        Map<String, float[]> actual = new LinkedHashMap<>();

        for (List<Object> row : rows(connection, """
                SELECT f.source_path || '#' || c.chunk_index, e.vector FROM chunk_embeddings e
                JOIN chunks c ON c.id = e.chunk_id JOIN files f ON f.id = c.file_id
                """)) {
            actual.put((String) row.get(0), SqliteEmbeddingRepository.decode((byte[]) row.get(1)));
        }

        assertEquals(expected.keySet(), actual.keySet());
        expected.forEach((key, vector) -> assertArrayEquals(vector, actual.get(key), key));
    }

    /** Maps each stored vector's chunk, as {@code path#index}, to its number of dimensions. */
    private static Map<String, Integer> vectorKeys(Connection connection) throws SQLException {
        Map<String, Integer> keys = new LinkedHashMap<>();

        for (List<Object> row : rows(connection, """
                SELECT f.source_path || '#' || c.chunk_index, length(e.vector) / 4 FROM chunk_embeddings e
                JOIN chunks c ON c.id = e.chunk_id JOIN files f ON f.id = c.file_id
                ORDER BY f.source_path, c.chunk_index
                """)) {
            keys.put((String) row.get(0), ((Number) row.get(1)).intValue());
        }

        return keys;
    }

    private static List<List<Object>> rows(Connection connection, String sql) throws SQLException {
        List<List<Object>> rows = new ArrayList<>();

        try (var statement = connection.createStatement();
                var result = statement.executeQuery(sql)) {
            int columns = result.getMetaData().getColumnCount();

            while (result.next()) {
                List<Object> row = new ArrayList<>();

                for (int column = 1; column <= columns; column++) {
                    Object value = result.getObject(column);
                    row.add(value instanceof Integer number ? Long.valueOf(number) : value);
                }

                rows.add(row);
            }
        }

        return rows;
    }

    private static EmbeddingProfile profile(String pooling) {
        return new EmbeddingProfile(
                "test/model",
                "rev",
                "b".repeat(64),
                4,
                "wordpiece-v2",
                "c".repeat(64),
                256,
                pooling,
                "l2",
                "chunk-content");
    }

    private static Document document(String path, String content) {
        return new Document(
                Path.of(path),
                DocumentType.MARKDOWN,
                content,
                ContentHash.sha256(content.getBytes(StandardCharsets.UTF_8)));
    }

    private static List<Chunk> chunks(Document document) {
        if (document.content().isEmpty()) {
            return List.of();
        }

        return List.of(chunk(document, 0), chunk(document, 1));
    }

    private static Chunk chunk(Document document, int index) {
        return new Chunk(
                document.sourcePath(),
                document.type(),
                index,
                document.content() + " part " + index,
                new LineRange(index + 1, index + 1),
                new ChunkMetadata(List.of(), Map.of()));
    }
}
