package dev.eyuppastirmaci.pecia.index;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dev.eyuppastirmaci.pecia.config.PeciaConfigLoader;
import dev.eyuppastirmaci.pecia.config.PeciaConfigParser;
import dev.eyuppastirmaci.pecia.project.ProjectContext;
import dev.eyuppastirmaci.pecia.semantic.EmbeddingProfile;
import dev.eyuppastirmaci.pecia.semantic.ModelException;
import dev.eyuppastirmaci.pecia.semantic.ModelProvider;
import dev.eyuppastirmaci.pecia.storage.sqlite.SqliteStorage;
import dev.eyuppastirmaci.pecia.testing.IndexDump;
import dev.eyuppastirmaci.pecia.testing.LexicalTestCorpus;
import dev.eyuppastirmaci.pecia.testing.TinyModel;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** An indexing run reports each phase it reaches, in order, with counts that end where its result does. */
class IndexProgressTest {

    private static final long MILLI = Duration.ofMillis(1).toNanos();

    @TempDir
    Path root;

    private final PeciaConfigLoader loader = new PeciaConfigLoader(new PeciaConfigParser());
    private final List<IndexProgress> progress = new ArrayList<>();
    private ModelProvider models = listener -> TinyModel.cached();
    // Each reading moves the clock on by this much.
    private long step = ProgressReporter.UPDATE_INTERVAL.toNanos();
    private long clock;

    @BeforeEach
    void writeCorpus() throws IOException {
        root = root.toRealPath();
        LexicalTestCorpus.write(root);
    }

    @Test
    void aLexicalRunReportsDiscoveryFilesAndCleanupEndingAtItsResult() throws Exception {
        service().index(root);
        Files.delete(root.resolve(LexicalTestCorpus.CONFIG));
        Files.writeString(root.resolve(LexicalTestCorpus.NOTES), "Replaced notes about refunds.\n");
        progress.clear();

        IndexResult result = service().index(root, listening(IndexOptions.configured()));

        assertWellFormed();
        assertEquals(
                List.of(IndexProgress.Phase.SCAN, IndexProgress.Phase.FILES, IndexProgress.Phase.CLEANUP), phases());
        IndexProgress scanned = last(IndexProgress.Phase.SCAN);
        assertEquals(result.candidateCount(), scanned.completed());
        assertEquals(OptionalLong.empty(), scanned.total());
        assertFilesEndAt(result);
        assertUpdatedTo(IndexProgress.Phase.FILES, result.candidateCount());
        IndexProgress cleaned = last(IndexProgress.Phase.CLEANUP);
        assertEquals(1, cleaned.completed());
        assertEquals(OptionalLong.of(1), cleaned.total());
        assertEquals(
                List.of(IndexProgress.Phase.SCAN, IndexProgress.Phase.FILES, IndexProgress.Phase.CLEANUP),
                timed(result));
    }

    @Test
    void aSemanticRunAlsoReportsTheModelAndTheBackfill() throws Exception {
        service().index(root);
        Files.writeString(root.resolve("added.md"), "# Added\n\nA new file about refunds.\n");
        progress.clear();

        IndexResult result = service().index(root, listening(IndexOptions.semantic(true)));

        assertWellFormed();
        assertEquals(
                List.of(
                        IndexProgress.Phase.SCAN,
                        IndexProgress.Phase.MODEL,
                        IndexProgress.Phase.FILES,
                        IndexProgress.Phase.CLEANUP,
                        IndexProgress.Phase.BACKFILL),
                phases());
        assertFilesEndAt(result);
        // Files written after their embeddings report as they are written, not only when the phase ends.
        assertUpdatedTo(IndexProgress.Phase.FILES, result.candidateCount());
        assertTrue(result.embeddedChunks() > 0);
        IndexProgress backfilled = last(IndexProgress.Phase.BACKFILL);
        assertEquals(result.backfilledChunks(), backfilled.completed());
        assertEquals(OptionalLong.of(result.backfilledChunks()), backfilled.total());
        assertEquals(phases(), timed(result));
    }

    @Test
    void rejectedAndUnreadableFilesAreCountedAndSkipTheCleanup() throws Exception {
        Files.write(root.resolve("blob.txt"), new byte[] {0, 1, 0, 1});
        Files.write(root.resolve("latin1.txt"), new byte[] {(byte) 0xe9, 'a'});
        Path blocked = Files.writeString(root.resolve("blocked.txt"), "secret");
        assumeTrue(Files.getFileStore(blocked).supportsFileAttributeView("posix"), "POSIX permissions unavailable");
        Set<PosixFilePermission> original = Files.getPosixFilePermissions(blocked);
        IndexResult result;

        try {
            Files.setPosixFilePermissions(blocked, Set.of());
            assumeTrue(!Files.isReadable(blocked), "Current account bypasses permissions");

            result = service().index(root, listening(IndexOptions.configured()));
        } finally {
            Files.setPosixFilePermissions(blocked, original);
        }

        assertEquals(IndexResult.Status.PARTIAL, result.status());
        assertEquals(List.of(IndexProgress.Phase.SCAN, IndexProgress.Phase.FILES), phases());
        IndexProgress.FileCounts files = assertFilesEndAt(result);
        assertEquals(2, files.rejected());
        assertEquals(1, files.failed());
    }

    @Test
    void aCancelledRunFinishesThePhaseItStoppedIn() throws Exception {
        Cancellation cancellation = new Cancellation();
        IndexOptions options = IndexOptions.configured()
                .withCancellation(cancellation)
                .withProgressListener(update -> {
                    progress.add(update);

                    if (update.phase() == IndexProgress.Phase.FILES && update.event() == IndexProgress.Event.STARTED) {
                        cancellation.cancel();
                    }
                });

        IndexResult result = service().index(root, options);

        assertEquals(IndexResult.Status.CANCELLED, result.status());
        assertWellFormed();
        assertEquals(List.of(IndexProgress.Phase.SCAN, IndexProgress.Phase.FILES), phases());
        assertEquals(0, last(IndexProgress.Phase.FILES).completed());
        assertEquals(phases(), timed(result));
    }

    @Test
    void anEmbeddingFailureEndsTheFilesPhaseAtTheFilesItWrote() throws Exception {
        models = listener -> TinyModel.cached("non-finite");

        IndexException failure =
                assertThrows(IndexException.class, () -> service().index(root, listening(IndexOptions.semantic(true))));

        assertEquals(IndexResult.Status.EMBEDDING_FAILED, failure.result().status());
        assertWellFormed();
        IndexProgress.FileCounts files = assertFilesEndAt(failure.result());
        assertEquals(EmbeddingFailures.CONSECUTIVE_LIMIT, files.indexed());
        assertEquals(0, files.chunksEmbedded());
    }

    @Test
    void anUnavailableModelFinishesTheModelPhase() throws Exception {
        models = listener -> {
            throw new ModelException(ModelException.Reason.OFFLINE, "offline", null);
        };

        IndexException failure =
                assertThrows(IndexException.class, () -> service().index(root, listening(IndexOptions.semantic(true))));

        assertWellFormed();
        assertEquals(List.of(IndexProgress.Phase.SCAN, IndexProgress.Phase.MODEL), phases());
        assertEquals(phases(), timed(failure.result()));
    }

    @Test
    void aStorageFailureFinishesThePhasesItReached() throws Exception {
        Path database = Files.createDirectories(root.resolve(".pecia")).resolve("index.db");
        Files.write(database, "not a database".getBytes(StandardCharsets.UTF_8));

        IndexException failure =
                assertThrows(IndexException.class, () -> service().index(root, listening(IndexOptions.configured())));

        assertEquals(IndexResult.Status.FAILED, failure.result().status());
        assertWellFormed();
        assertEquals(List.of(IndexProgress.Phase.SCAN), phases());
        assertEquals(phases(), timed(failure.result()));
    }

    @Test
    void updatesAreSpacedOutButStartsAndEndsAlwaysCome() throws Exception {
        for (int file = 0; file < 40; file++) {
            Files.writeString(root.resolve("many-" + file + ".txt"), "File " + file + " about refunds.\n");
        }

        // Every clock reading moves 20 ms on, so a phase may report only every fifth reading.
        step = 20 * MILLI;

        IndexResult result = service().index(root, listening(IndexOptions.configured()));

        List<IndexProgress> files = of(IndexProgress.Phase.FILES);
        assertEquals(IndexProgress.Event.STARTED, files.getFirst().event());
        assertEquals(IndexProgress.Event.FINISHED, files.getLast().event());
        assertEquals(result.candidateCount(), files.getLast().completed());
        List<IndexProgress> updates = files.stream()
                .filter(update -> update.event() == IndexProgress.Event.UPDATED)
                .toList();
        assertTrue(updates.size() > 1, files.toString());
        assertTrue(updates.size() < result.candidateCount() / 2, files.toString());

        for (int index = 1; index < updates.size(); index++) {
            Duration gap =
                    updates.get(index).elapsed().minus(updates.get(index - 1).elapsed());
            assertTrue(gap.compareTo(ProgressReporter.UPDATE_INTERVAL) >= 0, gap.toString());
        }
    }

    @Test
    void withoutAListenerTheRunIsTheSameAndStillTimed(@TempDir Path other) throws Exception {
        other = other.toRealPath();
        LexicalTestCorpus.write(other);

        IndexResult listened = service().index(root, listening(IndexOptions.configured()));
        IndexResult unheard = service().index(other);

        assertEquals(listened.indexedFiles(), unheard.indexedFiles());
        assertEquals(listened.writtenChunks(), unheard.writtenChunks());
        assertEquals(
                IndexDump.read(root.resolve(".pecia/index.db"), root, List.of())
                        .chunks()
                        .size(),
                IndexDump.read(other.resolve(".pecia/index.db"), other, List.of())
                        .chunks()
                        .size());
        assertEquals(timed(listened), timed(unheard));
        assertTrue(unheard.timings().total().compareTo(Duration.ZERO) > 0);
    }

    @Test
    void aFailingListenerStopsTheRunAndTheNextRunCompletesTheIndex() throws Exception {
        IndexOptions failing = IndexOptions.configured().withProgressListener(update -> {
            if (update.phase() == IndexProgress.Phase.FILES && update.completed() == 3) {
                throw new IllegalStateException("listener failed");
            }
        });

        assertThrows(IllegalStateException.class, () -> service().index(root, failing));
        IndexResult next = service().index(root);

        assertEquals(IndexResult.Status.COMPLETE, next.status());
        assertEquals(next.candidateCount(), next.indexedFiles() + next.unchangedFiles());
        assertTrue(next.unchangedFiles() > 0, next.toString());
    }

    @Test
    void progressTimingsAndCountsAreValidated() {
        Optional<IndexProgress.FileCounts> none = Optional.empty();
        Optional<IndexProgress.FileCounts> zero = Optional.of(new IndexProgress.FileCounts(0, 0, 0, 0, 0, 0));

        assertThrows(
                IllegalArgumentException.class,
                () -> new IndexProgress(
                        IndexProgress.Phase.SCAN,
                        IndexProgress.Event.UPDATED,
                        -1,
                        OptionalLong.empty(),
                        Duration.ZERO,
                        none));
        assertThrows(
                IllegalArgumentException.class,
                () -> new IndexProgress(
                        IndexProgress.Phase.CLEANUP,
                        IndexProgress.Event.UPDATED,
                        2,
                        OptionalLong.of(1),
                        Duration.ZERO,
                        none));
        assertThrows(
                IllegalArgumentException.class,
                () -> new IndexProgress(
                        IndexProgress.Phase.SCAN,
                        IndexProgress.Event.UPDATED,
                        0,
                        OptionalLong.empty(),
                        Duration.ofNanos(-1),
                        none));
        assertThrows(
                IllegalArgumentException.class,
                () -> new IndexProgress(
                        IndexProgress.Phase.SCAN,
                        IndexProgress.Event.UPDATED,
                        0,
                        OptionalLong.empty(),
                        Duration.ZERO,
                        zero));
        assertThrows(
                IllegalArgumentException.class,
                () -> new IndexProgress(
                        IndexProgress.Phase.FILES,
                        IndexProgress.Event.UPDATED,
                        0,
                        OptionalLong.of(1),
                        Duration.ZERO,
                        none));
        assertEquals(
                2,
                new IndexProgress(
                                IndexProgress.Phase.CLEANUP,
                                IndexProgress.Event.FINISHED,
                                2,
                                OptionalLong.of(2),
                                Duration.ZERO,
                                none)
                        .completed());
        assertThrows(IllegalArgumentException.class, () -> new IndexProgress.FileCounts(-1, 0, 0, 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new IndexProgress.FileCounts(0, 0, 0, 0, 1, 2));
        assertEquals(10, new IndexProgress.FileCounts(1, 2, 3, 4, 5, 5).done());

        Map<IndexProgress.Phase, Duration> reversed = new EnumMap<>(IndexProgress.Phase.class);
        reversed.put(IndexProgress.Phase.FILES, Duration.ofMillis(2));
        reversed.put(IndexProgress.Phase.SCAN, Duration.ofMillis(1));
        IndexTimings timings = new IndexTimings(Duration.ofMillis(5), Map.copyOf(reversed));
        assertEquals(
                List.of(IndexProgress.Phase.SCAN, IndexProgress.Phase.FILES),
                List.copyOf(timings.phases().keySet()));
        assertThrows(UnsupportedOperationException.class, () -> timings.phases().clear());
        assertThrows(IllegalArgumentException.class, () -> new IndexTimings(Duration.ofMillis(-1), Map.of()));
        assertThrows(
                IllegalArgumentException.class,
                () -> new IndexTimings(Duration.ZERO, Map.of(IndexProgress.Phase.SCAN, Duration.ofMillis(-1))));
    }

    @Test
    void aReporterFinishesEachPhaseOnceAndCapsCountsAtTheTotal() {
        ProgressReporter reporter = new ProgressReporter(progress::add, () -> clock += 50 * MILLI);
        ProgressReporter.Phase cleanup = reporter.start(IndexProgress.Phase.CLEANUP, OptionalLong.of(3));
        cleanup.update(1);
        cleanup.update(5);
        ProgressReporter.Phase backfill = reporter.start(IndexProgress.Phase.BACKFILL, OptionalLong.of(4));

        IndexTimings timings = reporter.timings();
        cleanup.finish(2);
        cleanup.update(3);
        backfill.finish();

        assertEquals(
                List.of(
                        "CLEANUP STARTED 0",
                        "CLEANUP UPDATED 3",
                        "BACKFILL STARTED 0",
                        "CLEANUP FINISHED 3",
                        "BACKFILL FINISHED 0"),
                progress.stream()
                        .map(update -> update.phase() + " " + update.event() + " " + update.completed())
                        .toList());
        // Each phase counts its time from its own start; the clock moves 50 ms on at every reading.
        assertEquals(
                List.of(0L, 100L, 0L, 200L, 100L),
                progress.stream().map(update -> update.elapsed().toMillis()).toList());
        assertEquals(
                List.of(IndexProgress.Phase.CLEANUP, IndexProgress.Phase.BACKFILL),
                List.copyOf(timings.phases().keySet()));
        assertFalse(timings.total().isNegative());
        // Finished phases keep their durations.
        assertEquals(timings.phases(), reporter.timings().phases());
    }

    /** Checks that each phase starts once, then only updates, then finishes once, with counts and time rising. */
    private void assertWellFormed() {
        for (IndexProgress.Phase phase : phases()) {
            List<IndexProgress> updates = of(phase);
            assertEquals(IndexProgress.Event.STARTED, updates.getFirst().event(), updates.toString());
            assertEquals(IndexProgress.Event.FINISHED, updates.getLast().event(), updates.toString());

            for (int index = 1; index < updates.size(); index++) {
                IndexProgress previous = updates.get(index - 1);
                IndexProgress current = updates.get(index);
                assertTrue(current.completed() >= previous.completed(), updates.toString());
                assertTrue(current.elapsed().compareTo(previous.elapsed()) >= 0, updates.toString());
                assertEquals(previous.total(), current.total(), updates.toString());

                if (index < updates.size() - 1) {
                    assertEquals(IndexProgress.Event.UPDATED, current.event(), updates.toString());
                }
            }
        }

        // Phases do not overlap: each starts after the previous one finished.
        List<IndexProgress.Phase> order = progress.stream()
                .filter(update -> update.event() != IndexProgress.Event.UPDATED)
                .map(IndexProgress::phase)
                .toList();
        List<IndexProgress.Phase> paired = new ArrayList<>();
        phases().forEach(phase -> {
            paired.add(phase);
            paired.add(phase);
        });
        assertEquals(paired, order);
    }

    /** Checks that an update before the phase's end already reached {@code completed}. */
    private void assertUpdatedTo(IndexProgress.Phase phase, long completed) {
        assertTrue(
                of(phase).stream()
                        .anyMatch(update ->
                                update.event() == IndexProgress.Event.UPDATED && update.completed() == completed),
                of(phase).toString());
    }

    /** Checks that the files phase ends at the result's counts, done or not, and returns them. */
    private IndexProgress.FileCounts assertFilesEndAt(IndexResult result) {
        IndexProgress finished = last(IndexProgress.Phase.FILES);
        IndexProgress.FileCounts files = finished.files().orElseThrow();
        assertEquals(OptionalLong.of(result.candidateCount()), finished.total());
        assertEquals(files.done(), finished.completed());
        assertEquals(result.indexedFiles(), files.indexed());
        assertEquals(result.unchangedFiles(), files.unchanged());
        assertEquals(result.rejectedFiles(), files.rejected());
        assertEquals(result.failedFiles(), files.failed());
        assertEquals(result.writtenChunks(), files.chunksWritten());
        assertEquals(result.embeddedChunks(), files.chunksEmbedded());
        return files;
    }

    private List<IndexProgress.Phase> phases() {
        return progress.stream().map(IndexProgress::phase).distinct().toList();
    }

    private List<IndexProgress> of(IndexProgress.Phase phase) {
        return progress.stream().filter(update -> update.phase() == phase).toList();
    }

    private IndexProgress last(IndexProgress.Phase phase) {
        return of(phase).getLast();
    }

    private static List<IndexProgress.Phase> timed(IndexResult result) {
        return List.copyOf(result.timings().phases().keySet());
    }

    private IndexOptions listening(IndexOptions options) {
        return options.withProgressListener(progress::add);
    }

    private IndexService service() {
        return new IndexService(
                loader,
                IndexProgressTest::openStorage,
                listener -> models.ensure(listener),
                TextEmbedder::open,
                EmbeddingProfile::minilm,
                Optional::empty,
                () -> clock += step);
    }

    private static SqliteStorage openStorage(ProjectContext context) throws IOException, SQLException {
        return SqliteStorage.open(context.databasePath(), context.projectRoot());
    }
}
