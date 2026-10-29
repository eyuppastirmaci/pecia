package dev.eyuppastirmaci.pecia.index;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.eyuppastirmaci.pecia.semantic.EmbeddingException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** The embedding pipeline's ordering, bound, failures, cancellation, and threads, with a fake embedder. */
class EmbeddingPipelineTest {

    private static final Duration PATIENCE = Duration.ofSeconds(10);

    // Stops the run on any failure, as the pipeline's callers decide for themselves.
    private static final EmbeddingPipeline.FailureHandler RETHROW = failure -> {
        throw failure;
    };

    private final Cancellation cancellation = new Cancellation();
    private final List<String> embedded = Collections.synchronizedList(new ArrayList<>());
    private final List<String> written = new ArrayList<>();

    @AfterEach
    void noWorkerOutlivesItsPipeline() {
        assertEquals(List.of(), workerThreads());
    }

    @ParameterizedTest(name = "{0} processors, {1} workers -> {2} threads")
    @CsvSource({"18, 2, 4", "8, 2, 2", "6, 2, 1", "4, 2, 1", "1, 1, 1", "64, 1, 4", "2, 4, 1", "16, 3, 2"})
    void workersTogetherTakeAboutHalfTheProcessorsWithOneToFourThreadsEach(int processors, int workers, int threads) {
        assertEquals(threads, EmbeddingPipeline.intraOpThreads(processors, workers));
    }

    @Test
    void unitsAreWrittenInArrivalOrderWithOneVectorPerTextInOrder() throws Exception {
        List<List<String>> units = List.of(texts("a", 20), List.of(), texts("b", 3), texts("c", 9));
        List<List<float[]>> vectors = new ArrayList<>();

        try (EmbeddingPipeline pipeline = new EmbeddingPipeline(echo(), cancellation, 3)) {
            for (List<String> unit : units) {
                pipeline.submit(unit, vectors::add, RETHROW);
            }

            pipeline.finish();
        }

        assertEquals(units.size(), vectors.size());

        for (int unit = 0; unit < units.size(); unit++) {
            assertEquals(units.get(unit), decode(vectors.get(unit)));
        }

        // Twenty texts make three batches of at most eight.
        assertEquals(3, embedded.stream().filter(batch -> batch.startsWith("a")).count());
    }

    @Test
    void aLaterUnitThatFinishesFirstWaitsForTheEarlierOne() throws Exception {
        CountDownLatch releaseFirst = new CountDownLatch(1);
        TextEmbedder embedder = gated(texts -> texts.getFirst().equals("first") ? releaseFirst : null);

        try (EmbeddingPipeline pipeline = new EmbeddingPipeline(embedder, cancellation, 2)) {
            pipeline.submit(List.of("first"), vectors -> written.add("first"), RETHROW);
            pipeline.submit(List.of("second"), vectors -> written.add("second"), RETHROW);
            awaitTrue(() -> embedded.contains("second"));

            Thread finisher = Thread.ofPlatform().daemon().start(() -> releaseAfter(releaseFirst, 100));
            pipeline.finish();
            finisher.join();
        }

        assertEquals(List.of("first", "second"), written);
    }

    @Test
    void theCallerWritesTheOldestUnitBeforeMoreThanTheBoundWait() throws Exception {
        Semaphore permits = new Semaphore(0);
        TextEmbedder embedder = blocking(permits);
        AtomicInteger returned = new AtomicInteger();
        List<Integer> queuedAtWrite = Collections.synchronizedList(new ArrayList<>());
        AtomicReference<Throwable> failure = new AtomicReference<>();

        try (EmbeddingPipeline pipeline = new EmbeddingPipeline(embedder, cancellation, 2, 4)) {
            Thread caller = Thread.ofPlatform().daemon().start(() -> {
                try {
                    for (int unit = 0; unit < 10; unit++) {
                        String text = "unit " + unit;
                        pipeline.submit(
                                List.of(text),
                                vectors -> {
                                    queuedAtWrite.add(pipeline.queued());
                                    written.add(text);
                                },
                                RETHROW);
                        returned.incrementAndGet();
                    }

                    pipeline.finish();
                } catch (Exception | Error thrown) {
                    failure.set(thrown);
                }
            });

            // The fourth unit reaches the bound, so its submission waits to write the first.
            awaitTrue(() -> caller.getState() == Thread.State.WAITING);
            assertEquals(3, returned.get());
            assertEquals(4, pipeline.queued());
            assertEquals(List.of(), written);

            permits.release(10);
            caller.join(PATIENCE.toMillis());
            assertFalse(caller.isAlive());
        }

        assertNull(failure.get());
        assertEquals(IntStream.range(0, 10).mapToObj(unit -> "unit " + unit).toList(), written);
        assertTrue(queuedAtWrite.stream().allMatch(queued -> queued < 4), queuedAtWrite.toString());
    }

    @Test
    void aUnitLargerThanTheBoundIsAdmittedAlone() throws Exception {
        List<List<float[]>> vectors = new ArrayList<>();

        try (EmbeddingPipeline pipeline = new EmbeddingPipeline(echo(), cancellation, 2, 4)) {
            pipeline.submit(texts("big", 10), vectors::add, RETHROW);

            assertEquals(1, vectors.size(), "Written before submit returns");
            assertEquals(0, pipeline.queued());
        }
    }

    @Test
    void aFailedBatchFailsItsUnitAndNoLaterUnitIsWritten() throws Exception {
        EmbeddingException injected =
                new EmbeddingException(EmbeddingException.Reason.INFERENCE_FAILED, "injected", null);
        TextEmbedder embedder = new TextEmbedder() {
            @Override
            public float[][] embed(List<String> texts) throws EmbeddingException {
                if (texts.contains("b 9")) {
                    throw injected;
                }

                return encode(texts);
            }

            @Override
            public void close() {}
        };

        try (EmbeddingPipeline pipeline = new EmbeddingPipeline(embedder, cancellation, 2)) {
            pipeline.submit(texts("a", 3), vectors -> written.add("a"), RETHROW);
            pipeline.submit(texts("b", 12), vectors -> written.add("b"), RETHROW);
            pipeline.submit(texts("c", 3), vectors -> written.add("c"), RETHROW);

            assertSame(injected, assertThrows(EmbeddingException.class, pipeline::finish));
        }

        assertEquals(List.of("a"), written);
    }

    @Test
    void aHandledFailureTakesItsUnitsTurnAndLaterUnitsAreStillWritten() throws Exception {
        TextEmbedder embedder = new TextEmbedder() {
            @Override
            public float[][] embed(List<String> texts) throws EmbeddingException {
                if (texts.contains("b 9")) {
                    throw new EmbeddingException(EmbeddingException.Reason.INVALID_OUTPUT, "not a unit vector", null);
                }

                return encode(texts);
            }

            @Override
            public void close() {}
        };

        try (EmbeddingPipeline pipeline = new EmbeddingPipeline(embedder, cancellation, 2)) {
            for (String unit : List.of("a", "b", "c")) {
                pipeline.submit(
                        texts(unit, 12),
                        vectors -> written.add(unit + " embedded"),
                        failure -> written.add(unit + " failed: " + failure.reason()));
            }

            pipeline.finish();
            assertEquals(0, pipeline.queued());
        }

        assertEquals(List.of("a embedded", "b failed: INVALID_OUTPUT", "c embedded"), written);
    }

    @Test
    void anEmbedderThatMiscountsFailsTheRun() throws Exception {
        TextEmbedder embedder = new TextEmbedder() {
            @Override
            public float[][] embed(List<String> texts) {
                return new float[texts.size() + 1][];
            }

            @Override
            public void close() {}
        };

        try (EmbeddingPipeline pipeline = new EmbeddingPipeline(embedder, cancellation, 1)) {
            pipeline.submit(List.of("one"), vectors -> written.add("one"), RETHROW);

            IllegalStateException failure = assertThrows(IllegalStateException.class, pipeline::finish);
            assertEquals("The embedder returned 2 embeddings for 1 texts", failure.getMessage());
        }

        assertEquals(List.of(), written);
    }

    @Test
    void cancellingWhileWorkersAreBusyWritesTheFinishedUnitsAndStartsNoOtherBatch() throws Exception {
        Semaphore permits = new Semaphore(0);
        TextEmbedder embedder = blocking(permits);

        try (EmbeddingPipeline pipeline = new EmbeddingPipeline(embedder, cancellation, 2)) {
            pipeline.submit(List.of("a"), vectors -> written.add("a"), RETHROW);
            pipeline.submit(List.of("b"), vectors -> written.add("b"), RETHROW);
            pipeline.submit(texts("c", 16), vectors -> written.add("c"), RETHROW);
            awaitTrue(() -> permits.hasQueuedThreads() && permits.getQueueLength() == 2);

            cancellation.cancel();
            permits.release(100);

            assertThrows(Cancellation.CancelledException.class, pipeline::finish);
        }

        assertEquals(List.of("a", "b"), written);
        assertEquals(Set.of("a", "b"), Set.copyOf(embedded), "Batches queued behind the cancellation never start");
        assertEquals(2, embedded.size());
    }

    @Test
    void anInterruptedCallerStopsLikeACancelledOneAndKeepsItsInterrupt() throws Exception {
        Semaphore permits = new Semaphore(0);
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        AtomicReference<Boolean> interrupted = new AtomicReference<>();

        try (EmbeddingPipeline pipeline = new EmbeddingPipeline(blocking(permits), cancellation, 1)) {
            pipeline.submit(List.of("a"), vectors -> written.add("a"), RETHROW);
            Thread caller = Thread.ofPlatform().daemon().start(() -> {
                try {
                    pipeline.finish();
                } catch (Exception failure) {
                    thrown.set(failure);
                    interrupted.set(Thread.currentThread().isInterrupted());
                }
            });
            awaitTrue(() -> caller.getState() == Thread.State.WAITING);

            caller.interrupt();
            caller.join(PATIENCE.toMillis());
            permits.release(10);
        }

        assertTrue(thrown.get() instanceof Cancellation.CancelledException, String.valueOf(thrown.get()));
        assertEquals(Boolean.TRUE, interrupted.get());
        assertEquals(List.of(), written);
    }

    @Test
    void closingWaitsForRunningBatchesAndStopsEveryWorker() throws Exception {
        Semaphore permits = new Semaphore(0);
        AtomicInteger finished = new AtomicInteger();
        TextEmbedder embedder = new TextEmbedder() {
            @Override
            public float[][] embed(List<String> texts) {
                acquire(permits);
                finished.incrementAndGet();
                return encode(texts);
            }

            @Override
            public void close() {}
        };
        EmbeddingPipeline pipeline = new EmbeddingPipeline(embedder, cancellation, 3);
        pipeline.submit(texts("a", 40), vectors -> written.add("a"), RETHROW);
        awaitTrue(() -> permits.getQueueLength() == 3);
        assertEquals(3, workerThreads().size());
        assertTrue(workerThreads().stream().allMatch(Thread::isDaemon), "Workers never keep the JVM alive");

        Thread.ofPlatform().daemon().start(() -> releaseAfter(permits, 100));
        pipeline.close();

        // The three running batches finished before close returned; the two queued ones never started.
        assertEquals(3, finished.get());
        assertEquals(List.of(), workerThreads());
        assertEquals(List.of(), written);
    }

    @Test
    void noWorkerThreadIsAliveOnceClosed() throws Exception {
        // The executor reports termination while its last thread is still exiting, which about one close in ten
        // shows, so many rounds catch a close that does not wait for its threads.
        for (int round = 0; round < 300; round++) {
            List<Thread> workers;

            try (EmbeddingPipeline pipeline = new EmbeddingPipeline(echo(), cancellation, 2)) {
                pipeline.submit(texts("r" + round, 16), vectors -> {}, RETHROW);
                pipeline.finish();
                workers = workerThreads();
            }

            // Asked at once: listing every thread again takes long enough for a late thread to end meanwhile.
            assertEquals(List.of(), workers.stream().filter(Thread::isAlive).toList(), "round " + round);
        }
    }

    @Test
    void invalidSizesAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new EmbeddingPipeline(echo(), cancellation, 0));
        assertThrows(IllegalArgumentException.class, () -> new EmbeddingPipeline(echo(), cancellation, 1, 0));
    }

    /** Returns texts {@code prefix 0} to {@code prefix count-1}. */
    private static List<String> texts(String prefix, int count) {
        return IntStream.range(0, count).mapToObj(index -> prefix + " " + index).toList();
    }

    /** An embedder whose vectors encode their texts, recording each batch by its first text. */
    private TextEmbedder echo() {
        return gated(texts -> null);
    }

    /** Like {@link #echo()}, but a batch waits for the latch the gate returns for it, if any. */
    private TextEmbedder gated(Function<List<String>, CountDownLatch> gate) {
        return new TextEmbedder() {
            @Override
            public float[][] embed(List<String> texts) {
                CountDownLatch latch = gate.apply(texts);

                if (latch != null) {
                    await(latch);
                }

                embedded.add(texts.getFirst());
                return encode(texts);
            }

            @Override
            public void close() {}
        };
    }

    /** Like {@link #echo()}, but each batch first takes a permit, recording it once it has one. */
    private TextEmbedder blocking(Semaphore permits) {
        return new TextEmbedder() {
            @Override
            public float[][] embed(List<String> texts) {
                acquire(permits);
                embedded.add(texts.getFirst());
                return encode(texts);
            }

            @Override
            public void close() {}
        };
    }

    /** Encodes each text as a vector of its characters. */
    private static float[][] encode(List<String> texts) {
        float[][] vectors = new float[texts.size()][];

        for (int index = 0; index < texts.size(); index++) {
            String text = texts.get(index);
            vectors[index] = new float[text.length()];

            for (int character = 0; character < text.length(); character++) {
                vectors[index][character] = text.charAt(character);
            }
        }

        return vectors;
    }

    private static List<String> decode(List<float[]> vectors) {
        List<String> texts = new ArrayList<>();

        for (float[] vector : vectors) {
            StringBuilder text = new StringBuilder();

            for (float character : vector) {
                text.append((char) character);
            }

            texts.add(text.toString());
        }

        return texts;
    }

    private static List<Thread> workerThreads() {
        return Thread.getAllStackTraces().keySet().stream()
                .filter(thread -> thread.getName().startsWith("pecia-embedding-") && thread.isAlive())
                .toList();
    }

    private static void awaitTrue(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + PATIENCE.toNanos();

        while (!condition.getAsBoolean()) {
            assertTrue(System.nanoTime() < deadline, "Timed out waiting");
            Thread.sleep(5);
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(PATIENCE.toMillis(), TimeUnit.MILLISECONDS));
        } catch (InterruptedException interrupted) {
            throw new IllegalStateException(interrupted);
        }
    }

    /**
     * Takes a permit like a running inference call that ignores interrupts, but gives up after a while, so a failing
     * test never leaves a worker waiting for a release that will not come.
     */
    private static void acquire(Semaphore permits) {
        boolean interrupted = false;
        long deadline = System.nanoTime() + PATIENCE.toNanos();

        try {
            while (true) {
                try {
                    if (permits.tryAcquire(deadline - System.nanoTime(), TimeUnit.NANOSECONDS)) {
                        return;
                    }

                    throw new IllegalStateException("No permit was released");
                } catch (InterruptedException ignored) {
                    interrupted = true;
                }
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static void releaseAfter(CountDownLatch latch, long millis) {
        sleep(millis);
        latch.countDown();
    }

    private static void releaseAfter(Semaphore permits, long millis) {
        sleep(millis);
        permits.release(100);
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            throw new IllegalStateException(interrupted);
        }
    }
}
