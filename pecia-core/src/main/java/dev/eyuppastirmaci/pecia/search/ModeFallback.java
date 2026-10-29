package dev.eyuppastirmaci.pecia.search;

import java.util.Objects;
import java.util.Set;

/**
 * Why an {@code auto} query searched lexically instead of in hybrid mode: the semantic prerequisite that was missing,
 * with the message an explicit semantic or hybrid query would have failed with.
 */
public record ModeFallback(QueryException.Reason reason, String message) {

    /** The reasons a missing semantic prerequisite can have. */
    static final Set<QueryException.Reason> REASONS = Set.of(
            QueryException.Reason.EMBEDDINGS_MISSING,
            QueryException.Reason.EMBEDDINGS_OUTDATED,
            QueryException.Reason.MODEL_NOT_CACHED,
            QueryException.Reason.VECTOR_SEARCH_UNAVAILABLE,
            QueryException.Reason.EMBEDDER_UNAVAILABLE,
            QueryException.Reason.EMBEDDING_FAILED);

    /**
     * Validates the fallback.
     *
     * @throws NullPointerException if reason or message is null
     * @throws IllegalArgumentException if the reason is not a missing semantic prerequisite or message is blank
     */
    public ModeFallback {
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(message, "message");

        if (!REASONS.contains(reason)) {
            throw new IllegalArgumentException(reason + " is not a missing semantic prerequisite");
        }

        if (message.isBlank()) {
            throw new IllegalArgumentException("message must not be blank");
        }
    }
}
