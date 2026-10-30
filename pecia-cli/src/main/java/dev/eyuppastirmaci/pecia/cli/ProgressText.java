package dev.eyuppastirmaci.pecia.cli;

import dev.eyuppastirmaci.pecia.index.IndexProgress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Describes indexing progress in one line of plain ASCII text, such as {@code indexing: 1,240 of 18,268 files, 9,812
 * chunks, 197 chunks/s, about 1 min left}, so it reads the same in any console.
 */
final class ProgressText {

    // Below this, a rate or an estimate of the time left varies too much to help.
    private static final Duration SETTLED = Duration.ofSeconds(1);

    private ProgressText() {}

    /** Describes a phase while it runs, with its rate and the time left once they can be told. */
    static String running(IndexProgress progress) {
        List<String> parts = new ArrayList<>();
        long completed = progress.completed();

        switch (progress.phase()) {
            case SCAN -> parts.add(count(completed, "file") + " found");
            case MODEL -> {
                if (progress.total().isEmpty()) {
                    return label(progress);
                }

                parts.add(megabytes(completed) + " of "
                        + megabytes(progress.total().getAsLong()) + " MB");
                rate(progress, completed / 1_000_000.0, "MB/s").ifPresent(parts::add);
            }
            case FILES -> {
                IndexProgress.FileCounts files = progress.files().orElseThrow();
                parts.add(count(completed) + " of " + total(progress, "file"));
                parts.add(count(files.chunksWritten(), "chunk"));
                // Embedding sets the pace of a semantic run, so its rate is in chunks.
                (files.chunksEmbedded() > 0
                                ? rate(progress, files.chunksEmbedded(), "chunks/s")
                                : rate(progress, completed, "files/s"))
                        .ifPresent(parts::add);
            }
            case CLEANUP -> parts.add(count(completed) + " of " + total(progress, "file"));
            case BACKFILL -> {
                parts.add(count(completed) + " of " + total(progress, "chunk"));
                rate(progress, completed, "chunks/s").ifPresent(parts::add);
            }
        }

        timeLeft(progress).ifPresent(parts::add);

        return label(progress) + ": " + String.join(", ", parts);
    }

    /** Describes a phase that ended, with what it did and how long it took. */
    static String finished(IndexProgress progress) {
        String done =
                switch (progress.phase()) {
                    case SCAN -> count(progress.completed(), "file") + " found";
                    case MODEL -> progress.total().isEmpty() ? "ready" : megabytes(progress.completed()) + " MB";
                    case FILES -> count(progress.completed()) + " of " + total(progress, "file");
                    case CLEANUP -> count(progress.completed(), "file") + " removed";
                    case BACKFILL -> count(progress.completed()) + " of " + total(progress, "chunk");
                };

        return label(progress) + ": " + done + " in " + duration(progress.elapsed());
    }

    /** Formats a duration for people: {@code 0.4 s}, {@code 12 s}, {@code 1 min 32 s}, or {@code 1 h 5 min}. */
    static String duration(Duration duration) {
        long seconds = duration.toSeconds();

        if (seconds < 10) {
            return String.format(Locale.ROOT, "%.1f s", duration.toMillis() / 1000.0);
        }

        if (seconds < 60) {
            return seconds + " s";
        }

        if (seconds < 3600) {
            return seconds / 60 + " min" + (seconds % 60 == 0 ? "" : " " + seconds % 60 + " s");
        }

        return seconds / 3600 + " h" + (seconds % 3600 / 60 == 0 ? "" : " " + seconds % 3600 / 60 + " min");
    }

    private static String label(IndexProgress progress) {
        return switch (progress.phase()) {
            case SCAN -> "scanning";
            // A model phase learns its total when a download starts.
            case MODEL -> progress.total().isPresent() ? "downloading the model" : "loading the model";
            case FILES -> "indexing";
            case CLEANUP -> "removing deleted files";
            case BACKFILL -> "embedding stored chunks";
        };
    }

    private static Optional<String> rate(IndexProgress progress, double items, String unit) {
        if (progress.elapsed().compareTo(SETTLED) < 0) {
            return Optional.empty();
        }

        return rate(items, progress.elapsed(), unit);
    }

    /** Describes how many items came per second, such as {@code 197 chunks/s}; empty without items or time. */
    static Optional<String> rate(double items, Duration elapsed, String unit) {
        if (items <= 0 || elapsed.isZero() || elapsed.isNegative()) {
            return Optional.empty();
        }

        double perSecond = items / (elapsed.toNanos() / 1e9);
        String value = perSecond >= 10 ? count(Math.round(perSecond)) : String.format(Locale.ROOT, "%.1f", perSecond);

        return Optional.of(value + " " + unit);
    }

    /** Estimates the time left from the pace so far, rounded so it does not flicker. */
    private static Optional<String> timeLeft(IndexProgress progress) {
        long completed = progress.completed();

        if (progress.total().isEmpty()
                || completed == 0
                || completed >= progress.total().getAsLong()
                || progress.elapsed().compareTo(SETTLED) < 0) {
            return Optional.empty();
        }

        double remaining = progress.total().getAsLong() - completed;
        long seconds = Math.round(progress.elapsed().toNanos() / 1e9 * remaining / completed);
        String left = seconds < 60
                ? Math.max(1, seconds) + " s"
                : seconds < 3600
                        ? Math.round(seconds / 60.0) + " min"
                        : duration(Duration.ofSeconds(seconds / 60 * 60));

        return Optional.of("about " + left + " left");
    }

    /** Counts the phase's total with its noun, singular for one: {@code 1 file}, {@code 18,268 files}. */
    private static String total(IndexProgress progress, String noun) {
        return count(progress.total().orElse(progress.completed()), noun);
    }

    private static String count(long value, String noun) {
        return count(value) + " " + noun + (value == 1 ? "" : "s");
    }

    private static String count(long value) {
        return String.format(Locale.ROOT, "%,d", value);
    }

    private static String megabytes(long bytes) {
        return String.format(Locale.ROOT, "%.1f", bytes / 1_000_000.0);
    }
}
