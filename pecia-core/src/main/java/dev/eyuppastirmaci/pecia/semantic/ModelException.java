package dev.eyuppastirmaci.pecia.semantic;

import java.util.Objects;

/** A failure to make the pinned model available, with a category callers can present and act on. */
public final class ModelException extends Exception {

    private final Reason reason;

    /**
     * Creates a failure with a required category and an optional underlying cause.
     *
     * @throws NullPointerException if reason is null
     */
    public ModelException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = Objects.requireNonNull(reason, "reason");
    }

    public Reason reason() {
        return reason;
    }

    /** Failure categories; none of them leaves an unverified file under the model's name. */
    public enum Reason {
        /** The download host could not be reached: no connection, unknown host, connect timeout, or denied access. */
        OFFLINE,
        /** The host answered with a status other than 200. */
        HTTP_STATUS,
        /** The connection failed or stalled after the download started. */
        TRANSFER_FAILED,
        /** The downloaded file's size or SHA-256 differs from the pinned values. */
        CHECKSUM_MISMATCH,
        /** The cache directory could not be created, locked, or written. */
        CACHE_UNAVAILABLE,
        /** The thread was interrupted while waiting for the cache or the download. */
        INTERRUPTED
    }
}
