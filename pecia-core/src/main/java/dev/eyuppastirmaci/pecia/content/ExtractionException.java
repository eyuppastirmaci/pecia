package dev.eyuppastirmaci.pecia.content;

import java.nio.file.Path;
import java.util.Objects;

/** A classified, file-level extraction failure. */
public final class ExtractionException extends Exception {

    private final Reason reason;
    private final Path path;

    /** Creates a classified failure without an underlying cause. */
    public ExtractionException(Reason reason, Path path, String message) {
        this(reason, path, message, null);
    }

    /** Creates a failure with a non-null category and path, and an optional underlying cause. */
    public ExtractionException(Reason reason, Path path, String message, Throwable cause) {
        super(message, cause);
        this.reason = Objects.requireNonNull(reason, "reason");
        this.path = Objects.requireNonNull(path, "path");
    }

    /**
     * Returns the stable category assigned to this extraction failure.
     *
     * @return the failure reason
     */
    public Reason reason() {
        return reason;
    }

    /**
     * Returns the source path associated with this extraction failure.
     *
     * @return the failing source path
     */
    public Path path() {
        return path;
    }

    /** Stable categories for rejected files and extraction failures. */
    public enum Reason {
        NOT_REGULAR_FILE,
        TOO_LARGE,
        READ_FAILED,
        INVALID_UTF8,
        BINARY_CONTENT,
        FILE_CHANGED,
        UNSUPPORTED_TYPE
    }
}
