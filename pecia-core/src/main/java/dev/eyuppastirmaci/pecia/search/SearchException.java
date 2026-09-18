package dev.eyuppastirmaci.pecia.search;

/** A search storage or result-mapping failure, distinct from an empty result or invalid request. */
public final class SearchException extends Exception {
    /** Creates a retrieval failure that preserves its underlying cause. */
    public SearchException(String message, Throwable cause) {
        super(message, cause);
    }
}
