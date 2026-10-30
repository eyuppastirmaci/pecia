package dev.eyuppastirmaci.pecia.index;

import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.function.LongSupplier;

/**
 * Times a run's phases and tells the caller's listener how far they have come, spacing out the reports so a listener
 * that redraws a terminal line never slows the run. Without a listener it only times the phases.
 */
final class ProgressReporter {

    /** The shortest time between two reports of one phase, other than its start and its end. */
    static final Duration UPDATE_INTERVAL = Duration.ofMillis(100);

    private static final IndexProgress.FileCounts NO_FILES = new IndexProgress.FileCounts(0, 0, 0, 0, 0, 0);

    private final IndexProgressListener listener;
    private final LongSupplier nanoTime;
    private final long started;
    private final Map<IndexProgress.Phase, Duration> durations = new EnumMap<>(IndexProgress.Phase.class);
    private final List<Phase> open = new ArrayList<>();

    /**
     * Starts timing a run.
     *
     * @param listener told about each phase; null when nobody listens
     * @param nanoTime a monotonic clock, as {@link System#nanoTime()}
     */
    ProgressReporter(IndexProgressListener listener, LongSupplier nanoTime) {
        this.listener = listener;
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
        this.started = nanoTime.getAsLong();
    }

    /** Starts a phase and reports it; {@code total} is empty when the phase cannot know it. */
    Phase start(IndexProgress.Phase phase, OptionalLong total) {
        Phase started = new Phase(phase, total);
        open.add(started);
        started.report(IndexProgress.Event.STARTED, started.phaseStarted);
        return started;
    }

    /**
     * Finishes the phases still running, as when a run fails or is cancelled during them, and returns how long the run
     * and each phase took.
     */
    IndexTimings timings() {
        for (Phase phase : List.copyOf(open)) {
            phase.finish();
        }

        return new IndexTimings(Duration.ofNanos(nanoTime.getAsLong() - started), durations);
    }

    /** One running phase; it finishes once, and later calls do nothing. */
    final class Phase {

        private final IndexProgress.Phase phase;
        private OptionalLong total;
        private final long phaseStarted;
        private long lastReport;
        private long completed;
        private IndexProgress.FileCounts files = NO_FILES;
        private boolean finished;

        private Phase(IndexProgress.Phase phase, OptionalLong total) {
            this.phase = Objects.requireNonNull(phase, "phase");
            this.total = Objects.requireNonNull(total, "total");
            this.phaseStarted = nanoTime.getAsLong();
            this.lastReport = phaseStarted;
        }

        /** Records how far the phase has come and reports it, unless it reported less than the interval ago. */
        void update(long completed) {
            if (finished) {
                return;
            }

            this.completed = completed;
            long now = nanoTime.getAsLong();

            if (now - lastReport >= UPDATE_INTERVAL.toNanos()) {
                lastReport = now;
                report(IndexProgress.Event.UPDATED, now);
            }
        }

        /**
         * Records how far the phase has come of a total it learned only now, such as a download's size, and reports it
         * unless it reported less than the interval ago.
         */
        void update(long completed, long total) {
            if (!finished) {
                this.total = OptionalLong.of(total);
                update(completed);
            }
        }

        /** Records the files phase's counts and reports them, unless it reported less than the interval ago. */
        void update(IndexProgress.FileCounts files) {
            if (!finished) {
                this.files = Objects.requireNonNull(files, "files");
                update(files.done());
            }
        }

        /** Records where the phase ended, its duration, and reports it. */
        void finish(long completed) {
            if (!finished) {
                this.completed = completed;
                finish();
            }
        }

        /** Records the files phase's final counts, its duration, and reports them. */
        void finish(IndexProgress.FileCounts files) {
            if (!finished) {
                this.files = Objects.requireNonNull(files, "files");
                finish(files.done());
            }
        }

        /** Ends the phase where its last update left it. */
        void finish() {
            if (finished) {
                return;
            }

            finished = true;
            open.remove(this);
            long now = nanoTime.getAsLong();
            durations.put(phase, Duration.ofNanos(now - phaseStarted));
            report(IndexProgress.Event.FINISHED, now);
        }

        private void report(IndexProgress.Event event, long now) {
            if (listener == null) {
                return;
            }

            // A phase that found more than it counted, such as chunks added meanwhile, never reports past its total.
            long shown = total.isPresent() ? Math.min(completed, total.getAsLong()) : completed;
            Optional<IndexProgress.FileCounts> counts =
                    phase == IndexProgress.Phase.FILES ? Optional.of(files) : Optional.empty();

            listener.progress(
                    new IndexProgress(phase, event, shown, total, Duration.ofNanos(now - phaseStarted), counts));
        }
    }
}
