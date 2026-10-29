package dev.eyuppastirmaci.pecia.index;

import java.util.Objects;

/**
 * How far one stage of an indexing run has come.
 *
 * @param completed items of the stage processed so far
 * @param total items the stage processes in this run
 */
public record IndexProgress(Stage stage, long completed, long total) {

    /** Validates a stage and {@code 0 <= completed <= total}. */
    public IndexProgress {
        Objects.requireNonNull(stage, "stage");

        if (completed < 0 || completed > total) {
            throw new IllegalArgumentException(
                    "Progress must satisfy 0 <= completed <= total: " + completed + "/" + total);
        }
    }

    /** The parts of an indexing run that report progress. */
    public enum Stage {
        /** Embedding stored chunks that have no embedding yet; items are chunks. */
        BACKFILL
    }
}
