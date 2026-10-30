package dev.eyuppastirmaci.pecia.performance;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.eyuppastirmaci.pecia.config.PeciaConfigLoader;
import dev.eyuppastirmaci.pecia.config.PeciaConfigParser;
import dev.eyuppastirmaci.pecia.config.SearchMode;
import dev.eyuppastirmaci.pecia.index.IndexOptions;
import dev.eyuppastirmaci.pecia.index.IndexResult;
import dev.eyuppastirmaci.pecia.index.IndexService;
import dev.eyuppastirmaci.pecia.search.QueryOptions;
import dev.eyuppastirmaci.pecia.search.QueryService;
import dev.eyuppastirmaci.pecia.search.ReferenceQueryServices;
import dev.eyuppastirmaci.pecia.search.SearchRequest;
import dev.eyuppastirmaci.pecia.semantic.ModelCache;
import dev.eyuppastirmaci.pecia.semantic.ModelOutcome;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Random;
import java.util.stream.Stream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Measures indexing and query performance on a synthetic corpus and writes a report; it asserts nothing about time.
 * Only the {@code performanceTest} task runs it. The semantic part runs when the model is in the cache that {@code
 * PECIA_CACHE_DIR} names, and is reported as skipped otherwise.
 */
@Tag("performance")
class IndexPerformanceTest {

    private static final long SEED = 20261030L;
    private static final int FILES = Integer.getInteger("pecia.performance.files", 10_000);
    private static final int SEMANTIC_FILES = Integer.getInteger("pecia.performance.semanticFiles", 1_000);
    // Each query runs once to warm up and then this many times.
    private static final int REPEATS = 5;

    @TempDir
    Path sandbox;

    private final PeciaConfigLoader loader = new PeciaConfigLoader(new PeciaConfigParser());
    private final SyntheticCorpus corpus = new SyntheticCorpus(SEED);
    private final List<String> rows = new ArrayList<>();

    @Test
    void measureIndexingAndQueries() throws Exception {
        Path project = Files.createDirectory(sandbox.resolve("lexical")).toRealPath();
        List<Path> files = corpus.write(project, FILES);
        IndexService indexer = new IndexService(loader);

        Measured<IndexResult> first = measure(() -> indexer.index(project, IndexOptions.semantic(false)));
        assertEquals(FILES, first.value().indexedFiles(), "Every generated file is indexed");
        row("lexical index, " + FILES + " files", first, chunksPerSecond(first));
        row("database size", String.format(Locale.ROOT, "%.1f MB", directorySize(project.resolve(".pecia")) / 1e6));

        Measured<IndexResult> unchanged = measure(() -> indexer.index(project, IndexOptions.semantic(false)));
        assertEquals(FILES, unchanged.value().unchangedFiles());
        row("rerun, nothing changed", unchanged, "");

        int edits = Math.max(1, FILES / 100);
        for (int index = 0; index < edits; index++) {
            corpus.edit(project, files.get(index * (FILES / edits)), index);
        }
        Measured<IndexResult> incremental = measure(() -> indexer.index(project, IndexOptions.semantic(false)));
        assertEquals(edits, incremental.value().indexedFiles());
        row("rerun, " + edits + " files edited", incremental, "");

        row("lexical query latency", latencies(new QueryService(loader), project, SearchMode.LEXICAL));
        measureSemantic();
        writeReport();
    }

    private void measureSemantic() throws Exception {
        Optional<ModelOutcome> cached = new ModelCache().find();

        if (cached.isEmpty()) {
            row("semantic", "skipped: no model in the cache PECIA_CACHE_DIR names");
            return;
        }

        Path project = Files.createDirectory(sandbox.resolve("semantic")).toRealPath();
        corpus.write(project, SEMANTIC_FILES);
        IndexService indexer = IndexService.withModelProvider(loader, listener -> cached.orElseThrow());

        Measured<IndexResult> indexed = measure(() -> indexer.index(project, IndexOptions.semantic(true)));
        assertEquals(indexed.value().writtenChunks(), indexed.value().embeddedChunks(), "Every chunk is embedded");
        row("semantic index, " + SEMANTIC_FILES + " files", indexed, chunksPerSecond(indexed));

        QueryService service = ReferenceQueryServices.withVectorExtensionIn(loader, sandbox.resolve("cache"));
        row("semantic query latency", latencies(service, project, SearchMode.SEMANTIC));
        row("hybrid query latency", latencies(service, project, SearchMode.HYBRID));
    }

    /** Returns the median and 95th percentile of every query's timed runs, after one untimed run each. */
    private String latencies(QueryService service, Path project, SearchMode mode) throws Exception {
        List<Double> millis = new ArrayList<>();

        for (String query : queries()) {
            SearchRequest request = new SearchRequest(query);
            service.query(project, request, QueryOptions.mode(mode));

            for (int repeat = 0; repeat < REPEATS; repeat++) {
                long start = System.nanoTime();
                service.query(project, request, QueryOptions.mode(mode));
                millis.add((System.nanoTime() - start) / 1e6);
            }
        }

        Collections.sort(millis);
        return String.format(
                Locale.ROOT,
                "median %.1f ms, p95 %.1f ms (%d queries x %d)",
                percentile(millis, 0.5),
                percentile(millis, 0.95),
                queries().size(),
                REPEATS);
    }

    /** Ten common words, ten rare words, and ten pairs of a common and a less common word. */
    private List<String> queries() {
        List<String> words = corpus.vocabulary();
        List<String> queries = new ArrayList<>();
        Random random = new Random(SEED);

        for (int index = 0; index < 10; index++) {
            queries.add(words.get(5 + index * 4));
            queries.add(words.get(1000 + random.nextInt(2000)));
            queries.add(words.get(index) + " " + words.get(100 + random.nextInt(400)));
        }

        return queries;
    }

    private static double percentile(List<Double> sorted, double fraction) {
        return sorted.get((int) Math.min(sorted.size() - 1, Math.round(fraction * (sorted.size() - 1))));
    }

    private static String chunksPerSecond(Measured<IndexResult> measured) {
        double seconds = measured.nanos() / 1e9;
        return String.format(Locale.ROOT, "%.0f chunks/s", measured.value().writtenChunks() / seconds);
    }

    /** Runs the action after a garbage collection and returns its result, its time, and the peak heap it reached. */
    private static <T> Measured<T> measure(Action<T> action) throws Exception {
        List<MemoryPoolMXBean> heap = ManagementFactory.getMemoryPoolMXBeans().stream()
                .filter(pool -> pool.getType() == MemoryType.HEAP)
                .toList();
        System.gc();
        heap.forEach(MemoryPoolMXBean::resetPeakUsage);
        long start = System.nanoTime();

        T value = action.run();

        long nanos = System.nanoTime() - start;
        long peak =
                heap.stream().mapToLong(pool -> pool.getPeakUsage().getUsed()).sum();
        return new Measured<>(value, nanos, peak);
    }

    private void row(String what, Measured<?> measured, String rate) {
        row(
                what,
                String.format(
                        Locale.ROOT,
                        "%.2f s, peak heap %.0f MB%s",
                        measured.nanos() / 1e9,
                        measured.peakHeapBytes() / 1e6,
                        rate.isEmpty() ? "" : ", " + rate));
    }

    private void row(String what, String result) {
        rows.add("| " + what + " | " + result + " |");
    }

    private void writeReport() throws IOException {
        Runtime runtime = Runtime.getRuntime();
        StringBuilder report = new StringBuilder("# Index performance\n\n");
        report.append(String.format(
                Locale.ROOT,
                "%s; %s %s, %d processors, Java %s, max heap %d MB; seed %d.%n%n",
                LocalDate.now(),
                System.getProperty("os.name"),
                System.getProperty("os.arch"),
                runtime.availableProcessors(),
                System.getProperty("java.version"),
                runtime.maxMemory() / 1_000_000,
                SEED));
        report.append("| Measurement | Result |\n| --- | --- |\n");
        rows.forEach(row -> report.append(row).append('\n'));
        Path directory = Path.of(System.getProperty("pecia.performance.reports", "build/reports/performance"));
        Files.createDirectories(directory);
        Files.writeString(directory.resolve("index.md"), report);
    }

    private static long directorySize(Path directory) throws IOException {
        try (Stream<Path> paths = Files.walk(directory)) {
            long size = 0;

            for (Path path : paths.filter(Files::isRegularFile).toList()) {
                size += Files.size(path);
            }

            return size;
        }
    }

    private record Measured<T>(T value, long nanos, long peakHeapBytes) {}

    private interface Action<T> {
        T run() throws Exception;
    }
}
