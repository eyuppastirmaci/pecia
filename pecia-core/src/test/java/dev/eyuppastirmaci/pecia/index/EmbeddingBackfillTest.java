package dev.eyuppastirmaci.pecia.index;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.eyuppastirmaci.pecia.config.PeciaConfigLoader;
import dev.eyuppastirmaci.pecia.config.PeciaConfigParser;
import dev.eyuppastirmaci.pecia.project.ProjectContext;
import dev.eyuppastirmaci.pecia.semantic.Embedder;
import dev.eyuppastirmaci.pecia.semantic.EmbeddingException;
import dev.eyuppastirmaci.pecia.semantic.ModelOutcome;
import dev.eyuppastirmaci.pecia.storage.sqlite.SqliteStorage;
import dev.eyuppastirmaci.pecia.testing.IndexDump;
import dev.eyuppastirmaci.pecia.testing.IndexWriteObserver;
import dev.eyuppastirmaci.pecia.testing.LexicalTestCorpus;
import dev.eyuppastirmaci.pecia.testing.TinyModel;
import java.io.IOException;
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
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Semantic runs embed the stored chunks of unchanged files from the index, resumably and with progress. */
class EmbeddingBackfillTest {

    private static final ModelOutcome TINY = TinyModel.cached();

    @TempDir
    Path sandbox;

    private final PeciaConfigLoader loader = new PeciaConfigLoader(new PeciaConfigParser());
    private final List<Integer> batchSizes = new ArrayList<>();
    private final List<IndexProgress> progress = new ArrayList<>();
    private int failOnCall = -1;
    private Runnable duringFirstCall = () -> {};
    private int embedCalls;
    private Path root;

    @BeforeEach
    void prepareProject() throws Exception {
        root = Files.createDirectories(sandbox.resolve("project")).toRealPath();
        LexicalTestCorpus.write(root);
    }

    @Test
    void aLexicalIndexGainsEveryEmbeddingFromStorageWithoutRewritingChunks() throws Exception {
        IndexResult lexical = service().index(root);
        IndexDump before = dump(lexical);
        IndexWriteObserver observer = IndexWriteObserver.start(lexical.context().databasePath());

        IndexResult semantic = service().index(root, semantic());

        assertEquals(IndexResult.Status.COMPLETE, semantic.status());
        assertEquals(0, semantic.indexedFiles());
        assertEquals(0, semantic.embeddedChunks());
        assertEquals(before.chunks().size(), semantic.backfilledChunks());
        assertEquals(0, observer.finish().writeCount(), "No file, chunk, identity, or profile is written");
        assertEquals(before, dump(semantic).withoutEmbeddings());

        try (Embedder embedder = Embedder.open(TINY)) {
            for (var chunk : storedVectors(semantic).entrySet()) {
                assertArrayEquals(embedder.embed(List.of(chunk.getKey()))[0], chunk.getValue(), 1e-6f, chunk.getKey());
            }
        }
    }

    @Test
    void aSecondSemanticRunHasNothingToBackfill() throws Exception {
        service().index(root);
        service().index(root, semantic());
        int calls = embedCalls;
        progress.clear();

        IndexResult again = service().index(root, semantic());

        assertEquals(0, again.backfilledChunks());
        assertEquals(calls, embedCalls);
        assertEquals(List.of(), progress);
    }

    @Test
    void anInterruptedBackfillKeepsItsPagesAndTheNextRunFinishesIt() throws Exception {
        Path interrupted = manyChunkProject("interrupted");
        Path uninterrupted = manyChunkProject("uninterrupted");
        for (Path project : List.of(interrupted, uninterrupted)) {
            Files.writeString(project.resolve("removed.txt"), "removed before the semantic run\n");
            service().index(project);
            Files.delete(project.resolve("removed.txt"));
        }
        failOnCall = 2;

        IndexException failure =
                assertThrows(IndexException.class, () -> service().index(interrupted, semantic()));

        assertEquals(IndexResult.Status.EMBEDDING_FAILED, failure.result().status());
        // Cleanup runs before the backfill, so the failed run still reports and keeps the deletion.
        assertEquals(1, failure.result().deletedFiles());
        assertEquals(32, failure.result().backfilledChunks());
        assertEquals(32, dump(failure.result()).embeddings().size());

        failOnCall = -1;
        IndexResult resumed = service().index(interrupted, semantic());
        IndexResult complete = service().index(uninterrupted, semantic());

        assertEquals(38, resumed.backfilledChunks());
        assertEquals(70, complete.backfilledChunks());
        assertEquals(dump(complete), dump(resumed));
    }

    @Test
    void chunksChangedOrDeletedWhileTheyAreEmbeddedGetNoEmbedding() throws Exception {
        IndexResult lexical = service().index(root);
        Path database = lexical.context().databasePath();
        int chunks = dump(lexical).chunks().size();
        duringFirstCall = () -> {
            // Another process edits one chunk and deletes a file while the first page is being embedded.
            try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database)) {
                connection.createStatement().execute("PRAGMA foreign_keys = ON");
                connection
                        .createStatement()
                        .executeUpdate(
                                "UPDATE chunks SET content = 'edited elsewhere' WHERE id = (SELECT min(c.id)"
                                        + " FROM chunks c JOIN files f ON f.id = c.file_id WHERE f.source_path = 'handbook.txt')");
                connection.createStatement().executeUpdate("DELETE FROM files WHERE source_path = 'config.yaml'");
            } catch (SQLException failure) {
                throw new IllegalStateException(failure);
            }
        };

        IndexResult semantic = service().index(root, semantic());
        Set<String> embedded = filesWithEmbeddings(semantic);

        assertEquals(IndexResult.Status.COMPLETE, semantic.status());
        assertFalse(storedVectors(semantic).containsKey("edited elsewhere"));
        assertFalse(embedded.contains("config.yaml"));
        assertTrue(semantic.backfilledChunks() < chunks, semantic.backfilledChunks() + " of " + chunks);
        assertEquals(semantic.backfilledChunks(), dump(semantic).embeddings().size());
    }

    @Test
    void aSubdirectoryTargetBackfillsOnlyItsFiles() throws Exception {
        // The configuration file marks the project root, so the subdirectory is indexed into the project's index.
        Files.writeString(root.resolve(".pecia.toml"), "");
        IndexResult whole = service().index(root);

        IndexResult docs = service().index(root.resolve("docs"), semantic());

        assertEquals(whole.context().databasePath(), docs.context().databasePath());

        assertEquals(
                Set.of(
                        LexicalTestCorpus.GUIDE.toString().replace('\\', '/'),
                        LexicalTestCorpus.NOTES.toString().replace('\\', '/')),
                filesWithEmbeddings(docs));
    }

    @Test
    void filesWrittenInTheRunAreNotEmbeddedTwice() throws Exception {
        IndexResult lexical = service().index(root);
        int chunks = dump(lexical).chunks().size();
        Files.writeString(root.resolve(LexicalTestCorpus.NOTES), "Replaced notes about refunds.\n");

        IndexResult semantic = service().index(root, semantic());

        assertEquals(1, semantic.embeddedChunks());
        assertEquals(chunks - 1, semantic.backfilledChunks());
        assertEquals(chunks, batchSizes.stream().mapToInt(Integer::intValue).sum());
        assertEquals(chunks, dump(semantic).embeddings().size());
    }

    @Test
    void progressStartsWithTheTotalAndRisesPageByPage() throws Exception {
        Path project = manyChunkProject("progress");
        service().index(project);

        IndexResult semantic = service().index(project, semantic());

        assertEquals(
                List.of(
                        new IndexProgress(IndexProgress.Stage.BACKFILL, 0, 70),
                        new IndexProgress(IndexProgress.Stage.BACKFILL, 32, 70),
                        new IndexProgress(IndexProgress.Stage.BACKFILL, 64, 70),
                        new IndexProgress(IndexProgress.Stage.BACKFILL, 70, 70)),
                progress);
        assertEquals(70, semantic.backfilledChunks());
    }

    @Test
    void lexicalRunsAndPreviewsNeverBackfillOrReportProgress() throws Exception {
        IndexOptions lexical = IndexOptions.semantic(false).withProgressListener(progress::add);

        service().index(root, lexical);
        IndexResult again = service().index(root, lexical);
        service().preview(root, semantic());

        assertEquals(0, again.backfilledChunks());
        assertEquals(List.of(), dump(again).embeddings());
        assertEquals(List.of(), progress);
        assertEquals(0, embedCalls);
    }

    @Test
    void progressValuesAreValidated() {
        assertThrows(IllegalArgumentException.class, () -> new IndexProgress(IndexProgress.Stage.BACKFILL, -1, 1));
        assertThrows(IllegalArgumentException.class, () -> new IndexProgress(IndexProgress.Stage.BACKFILL, 2, 1));
        assertThrows(NullPointerException.class, () -> new IndexProgress(null, 0, 0));
        assertThrows(NullPointerException.class, () -> IndexOptions.configured().withProgressListener(null));
    }

    private IndexOptions semantic() {
        return IndexOptions.semantic(true).withProgressListener(progress::add);
    }

    /** Writes a project whose single file splits into 70 chunks with six-token chunks. */
    private Path manyChunkProject(String name) throws IOException {
        Path project = Files.createDirectories(sandbox.resolve(name)).toRealPath();
        Files.writeString(project.resolve(".pecia.toml"), "[chunk]\nmax_tokens = 6\noverlap_tokens = 0\n");
        Files.writeString(project.resolve("words.txt"), "alpha beta gamma delta ".repeat(70) + "\n");
        return project;
    }

    private IndexService service() {
        return new IndexService(loader, EmbeddingBackfillTest::openStorage, listener -> TINY, this::open);
    }

    private static SqliteStorage openStorage(ProjectContext context) throws IOException, SQLException {
        return SqliteStorage.open(context.databasePath(), context.projectRoot());
    }

    private TextEmbedder open(ModelOutcome model) throws EmbeddingException {
        TextEmbedder embedder = TextEmbedder.open(model);

        return new TextEmbedder() {
            @Override
            public float[][] embed(List<String> texts) throws EmbeddingException {
                embedCalls++;
                batchSizes.add(texts.size());

                if (embedCalls == 1) {
                    duringFirstCall.run();
                }

                if (embedCalls == failOnCall) {
                    throw new EmbeddingException(EmbeddingException.Reason.INFERENCE_FAILED, "injected", null);
                }

                return embedder.embed(texts);
            }

            @Override
            public void close() {
                embedder.close();
            }
        };
    }

    private static IndexDump dump(IndexResult result) throws Exception {
        return IndexDump.read(result.context().databasePath(), result.context().projectRoot(), List.of());
    }

    private static Set<String> filesWithEmbeddings(IndexResult result) throws Exception {
        Set<String> files = new TreeSet<>();
        dump(result).embeddings().forEach(entry -> files.add(entry.sourcePath()));
        return files;
    }

    /** Maps each stored chunk's content to its embedding. */
    private static Map<String, float[]> storedVectors(IndexResult result) throws SQLException {
        Map<String, float[]> vectors = new LinkedHashMap<>();

        try (Connection connection = DriverManager.getConnection(
                        "jdbc:sqlite:" + result.context().databasePath());
                var rows = connection
                        .createStatement()
                        .executeQuery(
                                "SELECT c.content, e.vector FROM chunks c JOIN chunk_embeddings e ON e.chunk_id = c.id")) {
            while (rows.next()) {
                ByteBuffer buffer = ByteBuffer.wrap(rows.getBytes(2)).order(ByteOrder.LITTLE_ENDIAN);
                float[] vector = new float[buffer.remaining() / Float.BYTES];

                for (int index = 0; index < vector.length; index++) {
                    vector[index] = buffer.getFloat();
                }

                vectors.put(rows.getString(1), vector);
            }
        }

        return vectors;
    }
}
