package dev.eyuppastirmaci.pecia.storage.sqlite;

import java.sql.SQLException;
import java.util.Objects;

/** A classified failure to read an existing index without creating or upgrading it. */
public final class IndexAccessException extends SQLException {

    private final Reason reason;

    /** Creates a classified index-access failure without an underlying cause. */
    public IndexAccessException(Reason reason, String message) {
        this(reason, message, null);
    }

    /** Creates a classified index-access failure with an optional underlying cause. */
    public IndexAccessException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = Objects.requireNonNull(reason, "reason");
    }

    public Reason reason() {
        return reason;
    }

    /** Index states that prevent read-only access. */
    public enum Reason {
        /** No index exists at the configured path. */
        NOT_FOUND,
        /** The index requires an upgrade through a writable connection. */
        MIGRATION_REQUIRED,
        /** The schema or index format is unsupported. */
        INCOMPATIBLE,
        /** The stored project root differs from the requested project. */
        WRONG_PROJECT,
        /** The index is not a valid database or has invalid stored structure. */
        CORRUPT_INDEX
    }
}
