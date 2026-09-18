package dev.eyuppastirmaci.pecia.search;

import java.util.Objects;

/** A project query failure with an actionable category and its original cause. */
public final class QueryException extends Exception {

    private final Reason reason;

    /** Creates a query failure with a required category and an optional underlying cause. */
    public QueryException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = Objects.requireNonNull(reason, "reason");
    }

    public Reason reason() {
        return reason;
    }

    /** Failure categories used to distinguish index state from general read failures. */
    public enum Reason {
        /** No existing index was found at the configured path. */
        INDEX_NOT_FOUND,
        /** The index must be upgraded by an indexing operation before it can be queried. */
        MIGRATION_REQUIRED,
        /** The schema or index format is unsupported. */
        INCOMPATIBLE_INDEX,
        /** The index belongs to a different project root. */
        WRONG_PROJECT,
        /** Stored index structure or data failed validation. */
        CORRUPT_INDEX,
        /** Project resolution, index access, or result retrieval failed. */
        READ_FAILED
    }
}
