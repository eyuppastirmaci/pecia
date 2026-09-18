package dev.eyuppastirmaci.pecia.search;

/** A search storage or result-mapping failure, distinct from an empty result or invalid request. */
public final class SearchException extends Exception {
    public SearchException(String message, Throwable cause) {
        super(message, cause);
    }
}
