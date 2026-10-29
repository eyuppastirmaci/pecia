package dev.eyuppastirmaci.pecia.semantic;

/** Makes the semantic model available to an indexing run; {@link ModelCache} is the implementation Pecia ships. */
@FunctionalInterface
public interface ModelProvider {

    /**
     * Returns the verified model file, downloading it if needed.
     *
     * @param listener told before a download starts, so callers can explain a long wait
     * @throws ModelException if the model cannot be made available
     */
    ModelOutcome ensure(DownloadListener listener) throws ModelException;
}
