package dev.eyuppastirmaci.pecia.index;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.eyuppastirmaci.pecia.config.PeciaConfigLoader;
import dev.eyuppastirmaci.pecia.config.PeciaConfigParser;
import dev.eyuppastirmaci.pecia.semantic.Embedder;
import dev.eyuppastirmaci.pecia.semantic.EmbeddingException;
import dev.eyuppastirmaci.pecia.semantic.EmbeddingProfile;
import dev.eyuppastirmaci.pecia.semantic.ModelOutcome;
import dev.eyuppastirmaci.pecia.storage.sqlite.SqliteStorage;
import dev.eyuppastirmaci.pecia.testing.IndexDump;
import dev.eyuppastirmaci.pecia.testing.LexicalTestCorpus;
import dev.eyuppastirmaci.pecia.testing.TinyModel;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Semantic indexing runs with the tiny model: what they embed, when they skip, and what a failure leaves behind. */
class SemanticIndexingTest {

    private static final ModelOutcome TINY = TinyModel.cached();

    @TempDir
    Path sandbox;

    private final PeciaConfigLoader loader = new PeciaConfigLoader(new PeciaConfigParser());
    private final List<String> events = new ArrayList<>();
    private final List<Integer> batchSizes = new ArrayList<>();
    private int failOnCall = -1;
    private int embedCalls;
    private Path root;

    @BeforeEach
    void prepareProject() throws Exception {
        root = Files.createDirectories(sandbox.resolve("project")).toRealPath();
        LexicalTestCorpus.write(root);
    }

    @Test
    void everyNewChunkStoresTheEmbeddingOfItsContentUnderTheMiniLmProfile() throws Exception {
        IndexResult result = service(TINY).index(root, IndexOptions.semantic(true));

        assertEquals(IndexResult.Status.COMPLETE, result.status());
        assertSame(TINY, result.semanticModel());
        assertEquals(result.writtenChunks(), result.embeddedChunks());
        assertEquals(List.of("open", "close"), events);

        Map<String, String> contents = query(result, "SELECT c.id, c.content FROM chunks c");
        Map<String, byte[]> vectors = vectors(result);
        assertEquals(contents.keySet(), vectors.keySet());
        assertEquals(
                List.of(EmbeddingProfile.minilm().fingerprint()),
                List.copyOf(query(result, "SELECT fingerprint, fingerprint FROM embedding_profiles")
                        .keySet()));

        try (Embedder embedder = Embedder.open(TINY)) {
            for (var chunk : contents.entrySet()) {
                float[] expected = embedder.embed(List.of(chunk.getValue()))[0];

                assertArrayEquals(expected, decode(vectors.get(chunk.getKey())), 1e-6f, chunk.getValue());
            }
        }
    }

    @Test
    void unchangedFilesAreNotEmbeddedAgain() throws Exception {
        IndexResult first = service(TINY).index(root, IndexOptions.semantic(true));
        IndexDump before = dump(first);
        int callsAfterFirstRun = embedCalls;
        events.clear();

        IndexResult second = service(TINY).index(root, IndexOptions.semantic(true));

        assertEquals(0, second.embeddedChunks());
        assertEquals(callsAfterFirstRun, embedCalls);
        assertEquals(before, dump(second));
        assertEquals(List.of("open", "close"), events);
    }

    @Test
    void aChangedFileReplacesItsEmbeddingsAndADeletedFileLosesThem() throws Exception {
        IndexResult first = service(TINY).index(root, IndexOptions.semantic(true));
        Map<String, String> before = embeddingsByChunk(dump(first));
        Files.writeString(root.resolve(LexicalTestCorpus.NOTES), "Replaced notes about refunds.\n");
        Files.delete(root.resolve(LexicalTestCorpus.CONFIG));

        IndexResult second = service(TINY).index(root, IndexOptions.semantic(true));
        Map<String, String> after = embeddingsByChunk(dump(second));

        assertEquals(1, second.deletedFiles());
        assertEquals(second.writtenChunks(), second.embeddedChunks());
        assertTrue(after.keySet().stream().noneMatch(key -> key.startsWith("config.yaml#")), after.toString());
        assertNotEquals(before.get("docs/notes.txt#0"), after.get("docs/notes.txt#0"));

        Map<String, String> untouched = new LinkedHashMap<>(before);
        untouched.keySet().removeIf(key -> key.startsWith("config.yaml#") || key.startsWith("docs/notes.txt#"));
        after.keySet().removeIf(key -> key.startsWith("docs/notes.txt#"));
        assertEquals(untouched, after);
    }

    @Test
    void separateRunsOverTheSameFilesStoreIdenticalEmbeddings() throws Exception {
        Path other = Files.createDirectories(sandbox.resolve("other")).toRealPath();
        LexicalTestCorpus.writeReversed(other);

        IndexDump first = dump(service(TINY).index(root, IndexOptions.semantic(true)));
        IndexDump second = dump(service(TINY).index(other, IndexOptions.semantic(true)));

        assertFalse(first.embeddings().isEmpty());
        assertEquals(first, second);
    }

    @Test
    void largeFilesAreEmbeddedInBatchesOfThirtyTwo() throws Exception {
        Path project = Files.createDirectories(sandbox.resolve("batches")).toRealPath();
        // Six tokens leave four for content, so each repetition of the four words becomes one chunk.
        Files.writeString(project.resolve(".pecia.toml"), "[chunk]\nmax_tokens = 6\noverlap_tokens = 0\n");
        Files.writeString(project.resolve("words.txt"), "alpha beta gamma delta ".repeat(70) + "\n");

        IndexResult result = service(TINY).index(project, IndexOptions.semantic(true));

        assertEquals(70, result.embeddedChunks());
        assertEquals(List.of(32, 32, 6), batchSizes);
    }

    @Test
    void anEmbedderThatCannotOpenLeavesNoIndex() throws Exception {
        ModelOutcome broken = TinyModel.cached("missing-input");

        IndexException failure =
                assertThrows(IndexException.class, () -> service(broken).index(root, IndexOptions.semantic(true)));

        assertEquals(IndexResult.Status.EMBEDDER_UNAVAILABLE, failure.result().status());
        assertSame(broken, failure.result().semanticModel());
        assertEquals(EmbeddingException.Reason.INVALID_MODEL, ((EmbeddingException) failure.getCause()).reason());
        assertEquals(List.of(), events);
        assertFalse(Files.exists(root.resolve(".pecia")));
    }

    @Test
    void anEmbedderThatCannotOpenLeavesAnExistingIndexUntouched() throws Exception {
        IndexResult lexical = service(TINY).index(root);
        IndexDump before = dump(lexical);
        Files.writeString(root.resolve(LexicalTestCorpus.NOTES), "changed before the semantic run\n");

        assertThrows(
                IndexException.class,
                () -> service(TinyModel.cached("missing-input")).index(root, IndexOptions.semantic(true)));

        assertEquals(before, dump(lexical));
    }

    @Test
    void anEmbeddingFailureKeepsEarlierFilesAndLeavesTheFailingFileAsItWas() throws Exception {
        IndexResult lexical = service(TINY).index(root);
        IndexDump before = dump(lexical);
        for (Path file : List.of(LexicalTestCorpus.CONFIG, LexicalTestCorpus.NOTES, LexicalTestCorpus.HANDBOOK)) {
            Files.writeString(root.resolve(file), Files.readString(root.resolve(file)) + "\nchanged\n");
        }
        Files.delete(root.resolve(LexicalTestCorpus.EMPTY));
        // Candidates run in path order, so config.yaml is embedded first and the next changed file fails.
        failOnCall = 2;

        IndexException failure =
                assertThrows(IndexException.class, () -> service(TINY).index(root, IndexOptions.semantic(true)));
        IndexDump after = dump(lexical);

        assertEquals(IndexResult.Status.EMBEDDING_FAILED, failure.result().status());
        assertInstanceOf(EmbeddingException.class, failure.getCause());
        assertEquals(1, failure.result().indexedFiles());
        assertEquals(failure.result().writtenChunks(), failure.result().embeddedChunks());
        assertEquals(0, failure.result().deletedFiles());
        assertEquals(List.of("open", "close"), events);

        // Only config.yaml changed; the failing file and the deleted file keep their previous records.
        assertTrue(embeddingsByChunk(after).keySet().stream().allMatch(key -> key.startsWith("config.yaml#")));
        assertEquals(withoutFile(before, "config.yaml"), withoutFile(after.withoutEmbeddings(), "config.yaml"));
    }

    @Test
    void anOutputTheEmbedderRejectsFailsTheRunBeforeTheFileIsWritten() throws Exception {
        IndexException failure = assertThrows(
                IndexException.class,
                () -> service(TinyModel.cached("non-finite")).index(root, IndexOptions.semantic(true)));

        assertEquals(IndexResult.Status.EMBEDDING_FAILED, failure.result().status());
        assertEquals(EmbeddingException.Reason.INVALID_OUTPUT, ((EmbeddingException) failure.getCause()).reason());
        assertEquals(0, failure.result().indexedFiles());

        try (SqliteStorage storage = SqliteStorage.openReadOnly(root.resolve(".pecia/index.db"), root)) {
            assertTrue(storage.files().findAll().isEmpty());
        }
    }

    @Test
    void lexicalRunsAndPreviewsNeverOpenAnEmbedder() throws Exception {
        IndexService service = new IndexService(loader, context -> openStorage(context), listener -> TINY, model -> {
            throw new AssertionError("No embedder may open");
        });

        IndexResult result = service.index(root);
        service.preview(root, IndexOptions.semantic(true));

        assertEquals(0, result.embeddedChunks());
        assertEquals(List.of(), dump(result).embeddings());
    }

    private IndexService service(ModelOutcome model) {
        return new IndexService(loader, SemanticIndexingTest::openStorage, listener -> model, this::open);
    }

    private static SqliteStorage openStorage(dev.eyuppastirmaci.pecia.project.ProjectContext context)
            throws java.io.IOException, SQLException {
        return SqliteStorage.open(context.databasePath(), context.projectRoot());
    }

    /** Opens the real embedder, recording its lifecycle and batches and failing on a chosen call. */
    private TextEmbedder open(ModelOutcome model) throws EmbeddingException {
        TextEmbedder embedder = TextEmbedder.open(model);
        events.add("open");

        return new TextEmbedder() {
            @Override
            public float[][] embed(List<String> texts) throws EmbeddingException {
                embedCalls++;
                batchSizes.add(texts.size());

                if (embedCalls == failOnCall) {
                    throw new EmbeddingException(EmbeddingException.Reason.INFERENCE_FAILED, "injected", null);
                }

                return embedder.embed(texts);
            }

            @Override
            public void close() {
                events.add("close");
                embedder.close();
            }
        };
    }

    private static IndexDump dump(IndexResult result) throws Exception {
        return IndexDump.read(result.context().databasePath(), result.context().projectRoot(), List.of());
    }

    private static Map<String, String> embeddingsByChunk(IndexDump dump) {
        Map<String, String> embeddings = new LinkedHashMap<>();
        dump.embeddings()
                .forEach(entry -> embeddings.put(entry.sourcePath() + "#" + entry.chunkIndex(), entry.vectorSha256()));
        return embeddings;
    }

    private static IndexDump withoutFile(IndexDump dump, String path) {
        return new IndexDump(
                dump.formatVersion(),
                dump.files().stream()
                        .filter(file -> !file.sourcePath().equals(path))
                        .toList(),
                dump.chunks().stream()
                        .filter(chunk -> !chunk.sourcePath().equals(path))
                        .toList(),
                dump.ftsRows().stream()
                        .filter(row -> !path.equals(row.chunkPath()))
                        .toList(),
                List.of(),
                dump.queries());
    }

    private static Map<String, String> query(IndexResult result, String sql) throws SQLException {
        Map<String, String> rows = new LinkedHashMap<>();

        try (Connection connection = open(result);
                var statement = connection.createStatement();
                var rowsResult = statement.executeQuery(sql)) {
            while (rowsResult.next()) {
                rows.put(rowsResult.getString(1), rowsResult.getString(2));
            }
        }

        return rows;
    }

    private static Map<String, byte[]> vectors(IndexResult result) throws SQLException {
        Map<String, byte[]> vectors = new LinkedHashMap<>();

        try (Connection connection = open(result);
                var statement = connection.createStatement();
                var rows = statement.executeQuery("SELECT chunk_id, vector FROM chunk_embeddings")) {
            while (rows.next()) {
                vectors.put(rows.getString(1), rows.getBytes(2));
            }
        }

        return vectors;
    }

    private static Connection open(IndexResult result) throws SQLException {
        return DriverManager.getConnection("jdbc:sqlite:" + result.context().databasePath());
    }

    private static float[] decode(byte[] bytes) {
        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        float[] vector = new float[bytes.length / Float.BYTES];

        for (int index = 0; index < vector.length; index++) {
            vector[index] = buffer.getFloat();
        }

        return vector;
    }
}
