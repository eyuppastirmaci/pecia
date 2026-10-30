package dev.eyuppastirmaci.pecia.index;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * How far one phase of an indexing run has come. A phase reports when it starts, at most every 100 ms while it runs,
 * and when it finishes, also when the run fails or is cancelled during it.
 *
 * @param completed items of the phase done so far
 * @param total items the phase processes, when known; it stays the same within a phase once known
 * @param elapsed time since the phase started
 * @param files what happened to the candidates so far; present exactly for {@link Phase#FILES}
 */
public record IndexProgress(
        Phase phase, Event event, long completed, OptionalLong total, Duration elapsed, Optional<FileCounts> files) {

    /**
     * Validates the progress.
     *
     * @throws NullPointerException if a component is null
     * @throws IllegalArgumentException if completed is negative or above a known total, elapsed is negative, or file
     *     counts are present for another phase than {@link Phase#FILES} or missing for it
     */
    public IndexProgress {
        Objects.requireNonNull(phase, "phase");
        Objects.requireNonNull(event, "event");
        Objects.requireNonNull(total, "total");
        Objects.requireNonNull(elapsed, "elapsed");
        Objects.requireNonNull(files, "files");

        if (completed < 0 || (total.isPresent() && completed > total.getAsLong())) {
            throw new IllegalArgumentException(
                    "Progress must satisfy 0 <= completed <= total: " + completed + "/" + total);
        }

        if (elapsed.isNegative()) {
            throw new IllegalArgumentException("elapsed must not be negative: " + elapsed);
        }

        if (files.isPresent() != (phase == Phase.FILES)) {
            throw new IllegalArgumentException("File counts come exactly with the FILES phase");
        }
    }

    /** The parts of an indexing run, in the order they run; a run reports only the phases it runs. */
    public enum Phase {
        /** Finding the candidates; items are files found, and the total is unknown. */
        SCAN,
        /**
         * Making the semantic model available and opening its embedder; semantic runs only. When the model is
         * downloaded, items are its bytes, and the total becomes known when the download starts.
         */
        MODEL,
        /**
         * Reading, chunking, and writing each candidate; items are candidates. In a semantic run a file is done once
         * its chunks are embedded and written.
         */
        FILES,
        /** Removing indexed files that are gone; items are files. Reported only when some are gone. */
        CLEANUP,
        /** Embedding stored chunks that have no embedding yet; items are chunks. Semantic runs only. */
        BACKFILL
    }

    /** What a progress report marks. */
    public enum Event {
        /** The phase started. */
        STARTED,
        /** The phase went on; reports are spaced out, so not every item gets one. */
        UPDATED,
        /** The phase ended, completed or not. */
        FINISHED
    }

    /**
     * What happened to the candidates so far.
     *
     * @param indexed candidates written, including files written without embeddings
     * @param unchanged candidates skipped because their stored version is current
     * @param rejected candidates refused for their content, such as binary or invalid UTF-8 text
     * @param failed candidates that could not be read
     * @param chunksWritten chunks the written candidates stored
     * @param chunksEmbedded chunks stored with an embedding
     */
    public record FileCounts(
            int indexed, int unchanged, int rejected, int failed, long chunksWritten, long chunksEmbedded) {

        /** Rejects negative counts and more embedded chunks than written ones. */
        public FileCounts {
            if (indexed < 0
                    || unchanged < 0
                    || rejected < 0
                    || failed < 0
                    || chunksWritten < 0
                    || chunksEmbedded < 0
                    || chunksEmbedded > chunksWritten) {
                throw new IllegalArgumentException("Invalid file counts");
            }
        }

        /** Returns the candidates done, whatever happened to them. */
        public long done() {
            return (long) indexed + unchanged + rejected + failed;
        }
    }
}
