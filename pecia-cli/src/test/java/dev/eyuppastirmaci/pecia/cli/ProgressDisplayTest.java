package dev.eyuppastirmaci.pecia.cli;

import static dev.eyuppastirmaci.pecia.cli.ProgressTextTest.progress;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;

import dev.eyuppastirmaci.pecia.index.IndexProgress;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

/** A live display redraws one line and clears it; a plain one writes spaced-out lines for logs. */
class ProgressDisplayTest {

    private final StringWriter err = new StringWriter();
    private final PrintWriter writer = new PrintWriter(err, true);
    private long clock;

    @Test
    void theModeAndTheTerminalChooseTheDisplay() {
        Terminal interactive = new Terminal(false, true, 80);

        assertInstanceOf(
                ProgressDisplay.Live.class, ProgressDisplay.of(ProgressMode.AUTO, interactive, writer, this::now));
        assertSame(ProgressDisplay.NONE, ProgressDisplay.of(ProgressMode.AUTO, Terminal.PLAIN, writer, this::now));
        assertInstanceOf(
                ProgressDisplay.Plain.class, ProgressDisplay.of(ProgressMode.PLAIN, Terminal.PLAIN, writer, this::now));
        assertSame(ProgressDisplay.NONE, ProgressDisplay.of(ProgressMode.NONE, interactive, writer, this::now));
    }

    @Test
    void aLiveLineIsRedrawnPaddedAndCleared() {
        ProgressDisplay live = new ProgressDisplay.Live(writer, 80);

        live.progress(progress(IndexProgress.Phase.SCAN, IndexProgress.Event.UPDATED, 12_345, -1, 100));
        live.progress(progress(IndexProgress.Phase.SCAN, IndexProgress.Event.UPDATED, 7, -1, 200));
        live.clear();
        live.clear();
        live.progress(progress(IndexProgress.Phase.CLEANUP, IndexProgress.Event.FINISHED, 1, 1, 0));

        assertEquals(
                "\rscanning: 12,345 files found"
                        + "\rscanning: 7 files found" + " ".repeat(5)
                        + "\r" + " ".repeat(23) + "\r"
                        + "\rremoving deleted files: 1 file removed in 0.0 s",
                err.toString());
    }

    @Test
    void aLiveLineKeepsToTheTerminalWidth() {
        ProgressDisplay live = new ProgressDisplay.Live(writer, 20);

        live.progress(progress(IndexProgress.Phase.SCAN, IndexProgress.Event.UPDATED, 12_345, -1, 100));

        assertEquals("\rscanning: 12,345 fi", err.toString());
    }

    @Test
    void plainLinesComeEveryFiveSecondsAndAtTheEnd() {
        ProgressDisplay plain = new ProgressDisplay.Plain(writer, this::now);

        at(0);
        plain.progress(progress(IndexProgress.Phase.BACKFILL, IndexProgress.Event.STARTED, 0, 100, 0));
        at(4_000);
        plain.progress(progress(IndexProgress.Phase.BACKFILL, IndexProgress.Event.UPDATED, 10, 100, 4_000));
        at(5_000);
        plain.progress(progress(IndexProgress.Phase.BACKFILL, IndexProgress.Event.UPDATED, 20, 100, 5_000));
        at(9_000);
        plain.progress(progress(IndexProgress.Phase.BACKFILL, IndexProgress.Event.UPDATED, 30, 100, 9_000));
        at(65_000);
        plain.progress(progress(IndexProgress.Phase.BACKFILL, IndexProgress.Event.FINISHED, 100, 100, 65_000));
        // A quick phase is one line, when it ends.
        at(3_725_000);
        plain.progress(progress(IndexProgress.Phase.CLEANUP, IndexProgress.Event.STARTED, 0, 1, 0));
        at(3_725_300);
        plain.progress(progress(IndexProgress.Phase.CLEANUP, IndexProgress.Event.FINISHED, 1, 1, 300));

        assertEquals(
                String.join(
                        System.lineSeparator(),
                        "[0:05] embedding stored chunks: 20 of 100 chunks, 4.0 chunks/s, about 20 s left",
                        "[1:05] embedding stored chunks: 100 of 100 chunks in 1 min 5 s",
                        "[1:02:05] removing deleted files: 1 file removed in 0.3 s",
                        ""),
                err.toString());
    }

    @Test
    void eachPhaseKeepsItsOwnPlainCadence() {
        ProgressDisplay plain = new ProgressDisplay.Plain(writer, this::now);

        at(0);
        plain.progress(progress(IndexProgress.Phase.SCAN, IndexProgress.Event.STARTED, 0, -1, 0));
        at(4_000);
        plain.progress(progress(IndexProgress.Phase.CLEANUP, IndexProgress.Event.STARTED, 0, 2, 0));
        at(5_000);
        plain.progress(progress(IndexProgress.Phase.SCAN, IndexProgress.Event.UPDATED, 5, -1, 5_000));
        // Six seconds into the run but only two into the cleanup: too early for its first line.
        at(6_000);
        plain.progress(progress(IndexProgress.Phase.CLEANUP, IndexProgress.Event.UPDATED, 0, 2, 2_000));
        // Five and a half seconds after the cleanup started, although the scan printed a line 4.5 s ago.
        at(9_500);
        plain.progress(progress(IndexProgress.Phase.CLEANUP, IndexProgress.Event.UPDATED, 1, 2, 5_500));

        assertEquals(
                List.of(
                        "[0:05] scanning: 5 files found",
                        "[0:09] removing deleted files: 1 of 2 files, about 6 s left"),
                err.toString().lines().toList());
    }

    private void at(long millis) {
        clock = Duration.ofMillis(millis).toNanos();
    }

    private long now() {
        return clock;
    }
}
