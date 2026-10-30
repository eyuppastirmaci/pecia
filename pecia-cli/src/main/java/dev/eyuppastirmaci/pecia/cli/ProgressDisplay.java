package dev.eyuppastirmaci.pecia.cli;

import dev.eyuppastirmaci.pecia.index.IndexProgress;
import dev.eyuppastirmaci.pecia.index.IndexProgressListener;
import java.io.PrintWriter;
import java.time.Duration;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.LongSupplier;

/**
 * Shows indexing progress on stderr. A display that redraws a line clears it before anything else is printed, so
 * diagnostics and summaries never mix with it.
 */
abstract class ProgressDisplay implements IndexProgressListener {

    /** A display that shows nothing. */
    static final ProgressDisplay NONE = new ProgressDisplay() {
        @Override
        public void progress(IndexProgress progress) {}
    };

    /**
     * Chooses the display for a run: a redrawn line in {@code auto} mode on an interactive terminal, plain lines in
     * {@code plain} mode, and nothing otherwise.
     */
    static ProgressDisplay of(ProgressMode mode, Terminal terminal, PrintWriter err, LongSupplier nanoTime) {
        return switch (mode) {
            case AUTO -> terminal.interactive() ? new Live(err, terminal.width()) : NONE;
            case PLAIN -> new Plain(err, nanoTime);
            case NONE -> NONE;
        };
    }

    /** Removes what the display drew, before other output; it may draw again on the next progress. */
    void clear() {}

    /**
     * One line redrawn in place with a carriage return and padding, so it needs no escape codes and works in every
     * console. It is cut to the terminal's width, because a line that wraps cannot be redrawn.
     */
    static final class Live extends ProgressDisplay {

        private final PrintWriter err;
        private final int width;
        private int shown;

        Live(PrintWriter err, int width) {
            this.err = Objects.requireNonNull(err, "err");
            this.width = width;
        }

        @Override
        public synchronized void progress(IndexProgress progress) {
            String text = progress.event() == IndexProgress.Event.FINISHED
                    ? ProgressText.finished(progress)
                    : ProgressText.running(progress);
            // The last column stays free: some terminals wrap as soon as it is written.
            String line = text.length() < width ? text : text.substring(0, Math.max(0, width - 1));

            err.print("\r" + line + " ".repeat(Math.max(0, shown - line.length())));
            err.flush();
            shown = line.length();
        }

        @Override
        synchronized void clear() {
            if (shown > 0) {
                err.print("\r" + " ".repeat(shown) + "\r");
                err.flush();
                shown = 0;
            }
        }
    }

    /**
     * Plain lines for logs: every {@link #INTERVAL} while a phase runs and once when it ends, each with the time since
     * the run started, such as {@code [0:12] indexing: 1,240 of 18,268 files}; a phase quicker than that is one line.
     */
    static final class Plain extends ProgressDisplay {

        /** The time from a phase's start to its first line, and between its lines until it ends. */
        static final Duration INTERVAL = Duration.ofSeconds(5);

        private final PrintWriter err;
        private final LongSupplier nanoTime;
        private final long started;
        private final Map<IndexProgress.Phase, Long> printed = new EnumMap<>(IndexProgress.Phase.class);

        Plain(PrintWriter err, LongSupplier nanoTime) {
            this.err = Objects.requireNonNull(err, "err");
            this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
            this.started = nanoTime.getAsLong();
        }

        @Override
        public synchronized void progress(IndexProgress progress) {
            long now = nanoTime.getAsLong();
            if (progress.event() == IndexProgress.Event.STARTED) {
                printed.put(progress.phase(), now);
                return;
            }

            boolean due = progress.event() == IndexProgress.Event.FINISHED
                    || now - printed.getOrDefault(progress.phase(), started) >= INTERVAL.toNanos();

            if (!due) {
                return;
            }

            printed.put(progress.phase(), now);
            String text = progress.event() == IndexProgress.Event.FINISHED
                    ? ProgressText.finished(progress)
                    : ProgressText.running(progress);
            err.println("[" + clock(Duration.ofNanos(now - started)) + "] " + text);
        }

        /** Formats the time since the run started as {@code m:ss}, or {@code h:mm:ss} from one hour. */
        private static String clock(Duration elapsed) {
            long seconds = elapsed.toSeconds();

            return seconds < 3600
                    ? String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60)
                    : String.format(Locale.ROOT, "%d:%02d:%02d", seconds / 3600, seconds % 3600 / 60, seconds % 60);
        }
    }
}
