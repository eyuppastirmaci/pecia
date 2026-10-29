package dev.eyuppastirmaci.pecia.semantic;

import java.nio.file.Path;
import java.util.Objects;

/**
 * How the pinned model became available for a run.
 *
 * @param file the verified model file in the cache
 */
public record ModelOutcome(Status status, SemanticModel model, Path file) {

    /** Rejects missing components. */
    public ModelOutcome {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(file, "file");
    }

    /** Whether the verified file was already cached or was downloaded by this run. */
    public enum Status {
        /** The cached file matched its pinned size and SHA-256. */
        CACHED,
        /** The file was downloaded and verified by this run. */
        DOWNLOADED
    }
}
