package dev.eyuppastirmaci.pecia.storage.sqlite;

import java.sql.SQLException;
import java.util.Objects;

/** A classified failure to read an existing index without creating or upgrading it. */
public final class IndexAccessException extends SQLException {

    private final Reason reason;

    public IndexAccessException(Reason reason, String message) {
        this(reason, message, null);
    }

    public IndexAccessException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = Objects.requireNonNull(reason, "reason");
    }

    public Reason reason() {

        return reason;
    }

    public enum Reason {

        NOT_FOUND,
        MIGRATION_REQUIRED,
        INCOMPATIBLE,
        WRONG_PROJECT,
        CORRUPT_INDEX
    }
}
