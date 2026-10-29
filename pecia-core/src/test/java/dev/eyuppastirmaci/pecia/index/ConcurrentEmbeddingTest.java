package dev.eyuppastirmaci.pecia.index;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.eyuppastirmaci.pecia.config.PeciaConfigLoader;
import dev.eyuppastirmaci.pecia.config.PeciaConfigParser;
import dev.eyuppastirmaci.pecia.project.ProjectContext;
import dev.eyuppastirmaci.pecia.semantic.EmbeddingException;
import dev.eyuppastirmaci.pecia.semantic.ModelOutcome;
import dev.eyuppastirmaci.pecia.storage.sqlite.SqliteStorage;
import dev.eyuppastirmaci.pecia.testing.IndexDump;
import dev.eyuppastirmaci.pecia.testing.LexicalTestCorpus;
import dev.eyuppastirmaci.pecia.testing.TinyModel;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Semantic runs with several embedding workers write exactly what one worker writes, and leave no thread behind. */
class ConcurrentEmbeddingTest {

    private static final ModelOutcome TINY = TinyModel.cached();
    private static final List<Integer> WORKERS = List.of(1, 2, 5);
    private static final List<String> WORDS = List.of(
            "refund",
            "payment",
            "order",
            "invoice",
            "retry",
            "token",
            "session",
            "cache",
            "deploy",
            "ödeme",
            "日本",
            "validation",
            "handler",
            "queue",
            "worker",
            "limit");

    private static final String SMALL_CHUNKS = "[chunk]\nmax_tokens = 16\noverlap_tokens = 0\n\n";

    @TempDir
    Path sandbox;

    private final PeciaConfigLoader loader = new PeciaConfigLoader(new PeciaConfigParser());
    private final List<Integer> openedWithThreads = new ArrayList<>();

    @AfterEach
    void noWorkerOutlivesItsRun() {
        assertEquals(
                List.of(),
                Thread.getAllStackTraces().keySet().stream()
                        .filter(thread -> thread.getName().startsWith("pecia-embedding-"))
                        .toList());
    }

    @Test
    void theSharedCorpusIndexesIdenticallyWithAnyNumberOfWorkers() throws Exception {
        List<IndexDump> dumps = new ArrayList<>();

        for (int workers : WORKERS) {
            Path project = project("corpus-" + workers, workers);
            LexicalTestCorpus.write(project);

            dumps.add(dump(service().index(project, IndexOptions.semantic(true))));
        }

        assertFalse(dumps.getFirst().embeddings().isEmpty());
        assertEquals(
                dumps.getFirst().chunks().size(), dumps.getFirst().embeddings().size());
        dumps.forEach(dump -> assertEquals(dumps.getFirst(), dump));
    }

    @ParameterizedTest(name = "seed {0}")
    @ValueSource(longs = {7, 2026, 90210})
    void randomCorporaIndexAndBackfillIdenticallyWithAnyNumberOfWorkers(long seed) throws Exception {
        List<IndexDump> indexed = new ArrayList<>();
        List<IndexDump> backfilled = new ArrayList<>();

        for (int workers : WORKERS) {
            // Small chunks give the corpus more chunks than the pipeline holds.
            Path direct = project("direct-" + workers, workers, SMALL_CHUNKS);
            Path later = project("later-" + workers, workers, SMALL_CHUNKS);
            writeRandomCorpus(direct, seed);
            writeRandomCorpus(later, seed);

            indexed.add(dump(service().index(direct, IndexOptions.semantic(true))));
            // A lexical index first leaves every chunk to the backfill.
            service().index(later);
            backfilled.add(dump(service().index(later, IndexOptions.semantic(true))));
        }

        assertTrue(indexed.getFirst().chunks().size() > EmbeddingPipeline.QUEUE_BOUND);
        assertEquals(
                indexed.getFirst().chunks().size(),
                indexed.getFirst().embeddings().size());
        indexed.forEach(dump -> assertEquals(indexed.getFirst(), dump));
        backfilled.forEach(dump -> assertEquals(indexed.getFirst(), dump));
    }

    @ParameterizedTest(name = "{0} workers")
    @ValueSource(ints = {1, 2, 3})
    void theEmbedderOpensWithTheThreadsItsWorkersShare(int workers) throws Exception {
        Path project = project("threads", workers);
        Files.writeString(project.resolve("notes.txt"), "Refund notes.\n");

        service().index(project, IndexOptions.semantic(true));

        int processors = Runtime.getRuntime().availableProcessors();
        assertEquals(List.of(EmbeddingPipeline.intraOpThreads(processors, workers)), openedWithThreads);
    }

    @ParameterizedTest(name = "{0} workers")
    @ValueSource(ints = {1, 3})
    void theConfiguredNumberOfWorkersEmbedsAtOnce(int workers) throws Exception {
        Path project = project("parallel", workers, "[chunk]\nmax_tokens = 6\noverlap_tokens = 0\n\n");
        Files.writeString(project.resolve("words.txt"), "alpha beta gamma delta ".repeat(70) + "\n");
        // Each call waits briefly for the others, so calls that can overlap do.
        CountDownLatch together = new CountDownLatch(workers);
        AtomicInteger running = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        IndexService service =
                new IndexService(loader, ConcurrentEmbeddingTest::openStorage, listener -> TINY, (model, threads) -> {
                    TextEmbedder embedder = TextEmbedder.open(model, threads);

                    return new TextEmbedder() {
                        @Override
                        public float[][] embed(List<String> texts) throws EmbeddingException {
                            peak.accumulateAndGet(running.incrementAndGet(), Math::max);
                            together.countDown();

                            try {
                                together.await(2, TimeUnit.SECONDS);
                                return embedder.embed(texts);
                            } catch (InterruptedException interrupted) {
                                throw new IllegalStateException(interrupted);
                            } finally {
                                running.decrementAndGet();
                            }
                        }

                        @Override
                        public void close() {
                            embedder.close();
                        }
                    };
                });

        service.index(project, IndexOptions.semantic(true));

        assertEquals(workers, peak.get());
    }

    @Test
    void aFailingWorkerStopsTheRunAndClosesEverything() throws Exception {
        Path project = project("failing", 3);
        LexicalTestCorpus.write(project);
        List<String> events = new ArrayList<>();
        IndexService service =
                new IndexService(loader, ConcurrentEmbeddingTest::openStorage, listener -> TINY, (model, threads) -> {
                    TextEmbedder embedder = TextEmbedder.open(model, threads);
                    events.add("open");

                    return new TextEmbedder() {
                        @Override
                        public float[][] embed(List<String> texts) throws EmbeddingException {
                            if (texts.getFirst().startsWith("handbook.txt")) {
                                throw new EmbeddingException(
                                        EmbeddingException.Reason.INFERENCE_FAILED, "injected", null);
                            }

                            return embedder.embed(texts);
                        }

                        @Override
                        public void close() {
                            events.add("close");
                            embedder.close();
                        }
                    };
                });

        IndexException failure =
                assertThrows(IndexException.class, () -> service.index(project, IndexOptions.semantic(true)));

        assertEquals(IndexResult.Status.EMBEDDING_FAILED, failure.result().status());
        assertEquals(List.of("open", "close"), events);
        // Files before handbook.txt in path order are written with their embeddings; it and later ones are not.
        IndexDump after = dump(failure.result());
        assertEquals(
                List.of("config.yaml", "docs/notes.txt", "docs/Ödeme rehberi.md", "empty.txt"),
                after.files().stream().map(IndexDump.FileEntry::sourcePath).toList());
        assertEquals(after.chunks().size(), after.embeddings().size());
        assertEquals(after.chunks().size(), failure.result().embeddedChunks());
    }

    private Path project(String name, int workers) throws IOException {
        return project(name, workers, "");
    }

    private Path project(String name, int workers, String config) throws IOException {
        Path project = Files.createDirectories(sandbox.resolve(name)).toRealPath();
        Files.writeString(project.resolve(".pecia.toml"), config + "[embed]\nconcurrency = " + workers + "\n");
        return project;
    }

    /** Writes Markdown and text files of seeded random sizes, from empty to several pages of chunks. */
    private static void writeRandomCorpus(Path project, long seed) throws IOException {
        Random random = new Random(seed);

        for (int file = 0; file < 24; file++) {
            boolean markdown = random.nextBoolean();
            StringBuilder text = new StringBuilder();
            int paragraphs = random.nextInt(random.nextInt(10) == 0 ? 60 : 6);

            for (int paragraph = 0; paragraph < paragraphs; paragraph++) {
                if (markdown && random.nextInt(3) == 0) {
                    text.append("#".repeat(1 + random.nextInt(3)))
                            .append(' ')
                            .append(word(random))
                            .append("\n\n");
                }

                for (int word = random.nextInt(40); word >= 0; word--) {
                    text.append(word(random)).append(word == 0 ? ".\n\n" : " ");
                }
            }

            Path path = project.resolve("dir" + random.nextInt(4)).resolve("file" + file + (markdown ? ".md" : ".txt"));
            Files.createDirectories(path.getParent());
            Files.writeString(path, text);
        }
    }

    private static String word(Random random) {
        return WORDS.get(random.nextInt(WORDS.size()));
    }

    private IndexService service() {
        return new IndexService(loader, ConcurrentEmbeddingTest::openStorage, listener -> TINY, (model, threads) -> {
            openedWithThreads.add(threads);
            return TextEmbedder.open(model, threads);
        });
    }

    private static SqliteStorage openStorage(ProjectContext context) throws IOException, SQLException {
        return SqliteStorage.open(context.databasePath(), context.projectRoot());
    }

    private static IndexDump dump(IndexResult result) throws Exception {
        return IndexDump.read(result.context().databasePath(), result.context().projectRoot(), List.of());
    }
}
