package dev.eyuppastirmaci.pecia.index;

import java.util.Objects;

/** An aborted indexing run with its completed work. */
public final class IndexException extends Exception {

    private final IndexResult result;

    IndexException(String message, Throwable cause, IndexResult result) {
        super(message, cause);

        this.result = Objects.requireNonNull(result, "result");
    }

    public IndexResult result() {
        return result;
    }
}
