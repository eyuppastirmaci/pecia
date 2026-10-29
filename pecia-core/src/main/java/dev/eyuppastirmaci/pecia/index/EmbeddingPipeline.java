package dev.eyuppastirmaci.pecia.index;

import dev.eyuppastirmaci.pecia.semantic.EmbeddingException;
import java.sql.SQLException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Embeds texts on worker threads while the calling thread reads files and owns storage. Work arrives in units, such
 * as a changed file's chunks or a page of stored chunks, whose texts are split into batches for the workers. Each
 * unit's vectors go back to its writer on the calling thread in the order the units arrived, so what is written does
 * not depend on the number of workers. Once {@code bound} texts wait to be written, the calling thread writes the
 * oldest units before it reads more, which bounds memory however large the project is.
 */
final class EmbeddingPipeline implements AutoCloseable {

    // Peak memory follows the texts embedded at once, and smaller batches on several workers embed faster than larger
    // ones on one.
    static final int BATCH_SIZE = 8;
    static final int QUEUE_BOUND = 128;
    private static final int MAX_INTRA_OP_THREADS = 4;

    private final TextEmbedder embedder;
    private final Cancellation cancellation;
    private final int bound;
    private final ExecutorService workers;
    private final Deque<Unit> units = new ArrayDeque<>();
    private int queued;

    EmbeddingPipeline(TextEmbedder embedder, Cancellation cancellation, int workers) {
        this(embedder, cancellation, workers, QUEUE_BOUND);
    }

    EmbeddingPipeline(TextEmbedder embedder, Cancellation cancellation, int workers, int bound) {
        if (workers < 1 || bound < 1) {
            throw new IllegalArgumentException("workers and bound must be at least 1");
        }

        this.embedder = Objects.requireNonNull(embedder, "embedder");
        this.cancellation = Objects.requireNonNull(cancellation, "cancellation");
        this.bound = bound;
        // Daemon threads never keep the JVM alive, even if an inference call hangs.
        this.workers = Executors.newFixedThreadPool(
                workers,
                Thread.ofPlatform().name("pecia-embedding-", 1).daemon().factory());
    }

    /**
     * Returns the threads each inference call uses so that the workers together take about half the processors, at
     * least one and at most four each; more threads per call barely speed up batches of short texts.
     */
    static int intraOpThreads(int processors, int workers) {
        return Math.clamp(processors / 2 / workers, 1, MAX_INTRA_OP_THREADS);
    }

    /**
     * Queues texts for embedding, then writes the oldest units while {@code bound} or more texts wait.
     *
     * @param writer receives one vector per text, in order, after every earlier unit was written
     * @throws EmbeddingException if embedding a unit that had to be written failed
     * @throws Cancellation.CancelledException if the run was cancelled before a unit that had to be written finished
     */
    void submit(List<String> texts, Writer writer)
            throws SQLException, EmbeddingException, Cancellation.CancelledException {
        Objects.requireNonNull(writer, "writer");
        List<Future<float[][]>> batches = new ArrayList<>();

        for (int start = 0; start < texts.size(); start += BATCH_SIZE) {
            List<String> batch = List.copyOf(texts.subList(start, Math.min(start + BATCH_SIZE, texts.size())));

            batches.add(workers.submit(() -> embed(batch)));
        }

        units.addLast(new Unit(texts.size(), batches, writer));
        queued += texts.size();

        while (queued >= bound) {
            writeOldest();
        }
    }

    /**
     * Waits for every queued unit and writes it in order. After a cancellation, units that finished before it are
     * still written, up to the first that did not.
     */
    void finish() throws SQLException, EmbeddingException, Cancellation.CancelledException {
        while (!units.isEmpty()) {
            writeOldest();
        }
    }

    /** Returns how many texts wait to be written. */
    int queued() {
        return queued;
    }

    /** Stops the workers and waits for running batches, so the embedder can be closed afterwards. */
    @Override
    public void close() {
        workers.shutdownNow();
        // Inference cannot be interrupted, so this waits for running batches; queued ones never start.
        workers.close();
    }

    private float[][] embed(List<String> texts) throws EmbeddingException, Cancellation.CancelledException {
        cancellation.check();
        float[][] vectors = embedder.embed(texts);

        if (vectors.length != texts.size()) {
            throw new IllegalStateException(
                    "The embedder returned " + vectors.length + " embeddings for " + texts.size() + " texts");
        }

        return vectors;
    }

    private void writeOldest() throws SQLException, EmbeddingException, Cancellation.CancelledException {
        Unit unit = units.removeFirst();
        List<float[]> vectors = new ArrayList<>(unit.size());

        for (Future<float[][]> batch : unit.batches()) {
            vectors.addAll(Arrays.asList(await(batch)));
        }

        queued -= unit.size();
        unit.writer().write(vectors);
    }

    private static float[][] await(Future<float[][]> batch) throws EmbeddingException, Cancellation.CancelledException {
        try {
            return batch.get();
        } catch (InterruptedException interrupted) {
            // An interrupted calling thread stops the run like a cancellation and keeps its interrupt status.
            Thread.currentThread().interrupt();
            throw new Cancellation.CancelledException();
        } catch (ExecutionException failure) {
            Throwable cause = failure.getCause();

            if (cause instanceof EmbeddingException embedding) {
                throw embedding;
            }

            if (cause instanceof Cancellation.CancelledException cancelled) {
                throw cancelled;
            }

            if (cause instanceof RuntimeException unchecked) {
                throw unchecked;
            }

            if (cause instanceof Error error) {
                throw error;
            }

            throw new IllegalStateException("An embedding worker failed", cause);
        }
    }

    /** Writes a unit's vectors on the calling thread. */
    @FunctionalInterface
    interface Writer {

        void write(List<float[]> vectors) throws SQLException;
    }

    private record Unit(int size, List<Future<float[][]>> batches, Writer writer) {}
}
