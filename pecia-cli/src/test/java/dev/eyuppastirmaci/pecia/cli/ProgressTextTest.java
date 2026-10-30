package dev.eyuppastirmaci.pecia.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.eyuppastirmaci.pecia.index.IndexProgress;
import java.time.Duration;
import java.util.Optional;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Progress reads as one ASCII line with counts, a rate, and the time left once they settle. */
class ProgressTextTest {

    @Test
    void eachPhaseDescribesItsItems() {
        assertEquals("scanning: 1,240 files found", running(IndexProgress.Phase.SCAN, 1_240, -1, 500));
        assertEquals("loading the model", running(IndexProgress.Phase.MODEL, 0, -1, 2_000));
        assertEquals(
                "downloading the model: 45.2 of 90.4 MB, 9.0 MB/s, about 5 s left",
                running(IndexProgress.Phase.MODEL, 45_202_607, 90_405_214, 5_000));
        assertEquals("removing deleted files: 2 of 3 files", running(IndexProgress.Phase.CLEANUP, 2, 3, 50));
        assertEquals(
                "embedding stored chunks: 1,024 of 9,000 chunks, 205 chunks/s, about 39 s left",
                running(IndexProgress.Phase.BACKFILL, 1_024, 9_000, 5_000));
    }

    @Test
    void filesShowTheirRateInFilesUntilChunksAreEmbedded() {
        assertEquals(
                "indexing: 1,240 of 18,268 files, 9,812 chunks, 248 files/s, about 1 min left",
                files(1_240, 18_268, new IndexProgress.FileCounts(1_000, 240, 0, 0, 9_812, 0), 5_000));
        assertEquals(
                "indexing: 1,240 of 18,268 files, 9,812 chunks, 1,962 chunks/s, about 1 min left",
                files(1_240, 18_268, new IndexProgress.FileCounts(1_000, 240, 0, 0, 9_812, 9_812), 5_000));
    }

    @Test
    void rateAndTimeLeftWaitForASecondAndForProgress() {
        assertEquals(
                "indexing: 3 of 100 files, 12 chunks",
                files(3, 100, new IndexProgress.FileCounts(3, 0, 0, 0, 12, 0), 900));
        assertEquals(
                "indexing: 0 of 100 files, 0 chunks",
                files(0, 100, new IndexProgress.FileCounts(0, 0, 0, 0, 0, 0), 4_000));
        // Done, so nothing is left to estimate.
        assertEquals(
                "indexing: 100 of 100 files, 400 chunks, 50 files/s",
                files(100, 100, new IndexProgress.FileCounts(100, 0, 0, 0, 400, 0), 2_000));
        // Slow rates keep a decimal.
        assertEquals(
                "embedding stored chunks: 3 of 1,000 chunks, 1.5 chunks/s, about 11 min left",
                running(IndexProgress.Phase.BACKFILL, 3, 1_000, 2_000));
        assertEquals(
                "embedding stored chunks: 1 of 10,000 chunks, 0.1 chunks/s, about 27 h 46 min left",
                running(IndexProgress.Phase.BACKFILL, 1, 10_000, 10_000));
    }

    @Test
    void oneItemIsSingular() {
        assertEquals("scanning: 1 file found", running(IndexProgress.Phase.SCAN, 1, -1, 100));
        assertEquals("removing deleted files: 0 of 1 file", running(IndexProgress.Phase.CLEANUP, 0, 1, 100));
        assertEquals(
                "embedding stored chunks: 1 of 1 chunk in 0.1 s", finished(IndexProgress.Phase.BACKFILL, 1, 1, 100));
        assertEquals(
                "removing deleted files: 1 file removed in 0.1 s", finished(IndexProgress.Phase.CLEANUP, 1, 1, 100));
    }

    @Test
    void timeLeftIsRoundedAndNeverZero() {
        assertEquals(
                "embedding stored chunks: 999 of 1,000 chunks, 500 chunks/s, about 1 s left",
                running(IndexProgress.Phase.BACKFILL, 999, 1_000, 2_000));
        // 100 s left reads as about 2 min, not 1.
        assertEquals(
                "embedding stored chunks: 1 of 51 chunks, 0.5 chunks/s, about 2 min left",
                running(IndexProgress.Phase.BACKFILL, 1, 51, 2_000));
    }

    @Test
    void finishedPhasesSayWhatTheyDidAndHowLong() {
        assertEquals("scanning: 18,268 files found in 0.4 s", finished(IndexProgress.Phase.SCAN, 18_268, -1, 400));
        assertEquals("loading the model: ready in 1.2 s", finished(IndexProgress.Phase.MODEL, 0, -1, 1_200));
        assertEquals(
                "downloading the model: 90.4 MB in 18 s",
                finished(IndexProgress.Phase.MODEL, 90_405_214, 90_405_214, 18_000));
        assertEquals(
                "removing deleted files: 3 files removed in 0.0 s", finished(IndexProgress.Phase.CLEANUP, 3, 3, 9));
        assertEquals(
                "embedding stored chunks: 70 of 70 chunks in 1 min 32 s",
                finished(IndexProgress.Phase.BACKFILL, 70, 70, 92_000));
        assertEquals(
                "indexing: 5 of 7 files in 2.0 s",
                ProgressText.finished(new IndexProgress(
                        IndexProgress.Phase.FILES,
                        IndexProgress.Event.FINISHED,
                        5,
                        OptionalLong.of(7),
                        Duration.ofSeconds(2),
                        Optional.of(new IndexProgress.FileCounts(5, 0, 0, 0, 5, 0)))));
    }

    @Test
    void aRateNeedsItemsAndTime() {
        assertEquals(Optional.of("2.5 chunks/s"), ProgressText.rate(1, Duration.ofMillis(400), "chunks/s"));
        assertEquals(Optional.of("1,000 files/s"), ProgressText.rate(2_000, Duration.ofSeconds(2), "files/s"));
        assertEquals(Optional.empty(), ProgressText.rate(0, Duration.ofSeconds(2), "files/s"));
        assertEquals(Optional.empty(), ProgressText.rate(5, Duration.ZERO, "files/s"));
    }

    @ParameterizedTest(name = "{0} ms is {1}")
    @CsvSource({
        "0, 0.0 s",
        "9949, 9.9 s",
        "10000, 10 s",
        "59999, 59 s",
        "60000, 1 min",
        "92000, 1 min 32 s",
        "3599000, 59 min 59 s",
        "3600000, 1 h",
        "3900000, 1 h 5 min"
    })
    void durationsReadNaturally(long millis, String text) {
        assertEquals(text, ProgressText.duration(Duration.ofMillis(millis)));
    }

    private static String running(IndexProgress.Phase phase, long completed, long total, long millis) {
        return ProgressText.running(progress(phase, IndexProgress.Event.UPDATED, completed, total, millis));
    }

    private static String finished(IndexProgress.Phase phase, long completed, long total, long millis) {
        return ProgressText.finished(progress(phase, IndexProgress.Event.FINISHED, completed, total, millis));
    }

    private static String files(long completed, long total, IndexProgress.FileCounts counts, long millis) {
        return ProgressText.running(new IndexProgress(
                IndexProgress.Phase.FILES,
                IndexProgress.Event.UPDATED,
                completed,
                OptionalLong.of(total),
                Duration.ofMillis(millis),
                Optional.of(counts)));
    }

    static IndexProgress progress(
            IndexProgress.Phase phase, IndexProgress.Event event, long completed, long total, long millis) {
        return new IndexProgress(
                phase,
                event,
                completed,
                total < 0 ? OptionalLong.empty() : OptionalLong.of(total),
                Duration.ofMillis(millis),
                Optional.empty());
    }
}
