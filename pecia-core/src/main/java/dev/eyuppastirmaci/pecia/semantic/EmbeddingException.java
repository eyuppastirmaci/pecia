package dev.eyuppastirmaci.pecia.semantic;

import java.util.Objects;

/** A failure to open or run the semantic model, with a category callers can present and act on. */
public final class EmbeddingException extends Exception {

    private final Reason reason;

    /**
     * Creates a failure with a required category and an optional underlying cause.
     *
     * @throws NullPointerException if reason is null
     */
    public EmbeddingException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = Objects.requireNonNull(reason, "reason");
    }

    public Reason reason() {
        return reason;
    }

    /** Failure categories. */
    public enum Reason {
        /** ONNX Runtime has no native library for this platform, or its library could not be loaded. */
        RUNTIME_UNAVAILABLE,
        /** The model file could not be loaded or does not have the expected inputs and output. */
        INVALID_MODEL,
        /** ONNX Runtime failed while running the model. */
        INFERENCE_FAILED,
        /** The model returned values of the wrong shape, or values that cannot form a unit vector. */
        INVALID_OUTPUT
    }
}
