package dev.eyuppastirmaci.pecia.semantic;

import java.util.Optional;

/** Finds the semantic model for a query, which never downloads it; {@link ModelCache} is the implementation Pecia ships. */
@FunctionalInterface
public interface ModelLookup {

    /**
     * Returns the verified model file if it is already available, without downloading or creating anything.
     *
     * @return the cached model, or empty when it is missing or does not match its pinned size and SHA-256
     * @throws ModelException if the cache cannot be read
     */
    Optional<ModelOutcome> find() throws ModelException;
}
