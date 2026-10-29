package dev.eyuppastirmaci.pecia.search;

import java.util.Objects;

/** Something a caller should know about how a query was searched; it never changes whether the query succeeded. */
public record QueryNotice(Code code, String message) {

    /**
     * Validates the notice.
     *
     * @throws NullPointerException if code or message is null
     * @throws IllegalArgumentException if message is blank
     */
    public QueryNotice {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(message, "message");

        if (message.isBlank()) {
            throw new IllegalArgumentException("message must not be blank");
        }
    }

    /** Identifies the kind of notice, so callers can react to it without parsing the message. */
    public enum Code {
        /** The query text is longer than the model's input, so only its beginning was embedded. */
        QUERY_CUT_TO_MODEL_INPUT,
        /** The query text has too many parts for lexical search, so only the semantic side ranked a hybrid query. */
        LEXICAL_SIDE_SKIPPED
    }
}
