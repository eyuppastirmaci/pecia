package dev.eyuppastirmaci.pecia.index;

import java.util.Objects;
import java.util.Optional;

/** An aborted indexing run with its completed work. */
public final class IndexException extends Exception {

    private final IndexResult result;
    private final StorageFailure storageFailure;

    IndexException(String message, Throwable cause, IndexResult result) {
        this(message, cause, result, null);
    }

    IndexException(String message, Throwable cause, IndexResult result, StorageFailure storageFailure) {
        super(message, cause);

        this.result = Objects.requireNonNull(result, "result");
        this.storageFailure = storageFailure;
    }

    public IndexResult result() {
        return result;
    }

    /** Returns why storage failed, for a run that ended as {@link IndexResult.Status#FAILED}. */
    public Optional<StorageFailure> storageFailure() {
        return Optional.ofNullable(storageFailure);
    }
}
