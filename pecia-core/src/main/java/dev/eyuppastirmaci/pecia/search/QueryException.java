package dev.eyuppastirmaci.pecia.search;

import java.util.Objects;

/** A project query failure with an actionable category and its original cause. */
public final class QueryException extends Exception {

    private final Reason reason;

    public QueryException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = Objects.requireNonNull(reason, "reason");
    }

    public Reason reason() {

        return reason;
    }

    public enum Reason {

        INDEX_NOT_FOUND,
        MIGRATION_REQUIRED,
        INCOMPATIBLE_INDEX,
        WRONG_PROJECT,
        CORRUPT_INDEX,
        READ_FAILED
    }
}
