package dev.eyuppastirmaci.pecia.search;

public record SearchRequest(String query, int limit) {
    public static final int DEFAULT_LIMIT = 10;
    public static final int MAX_LIMIT = 100;
    public static final int MAX_QUERY_CODE_POINTS = 4096;

    /**
     * Validates the request without interpreting or changing its text.
     *
     * @throws NullPointerException if query is null
     * @throws IllegalArgumentException if query is blank, contains NUL, exceeds the length bound,
     *                                  or limit is outside the supported range
     */
    public SearchRequest {
        if (query.codePointCount(0, query.length()) > MAX_QUERY_CODE_POINTS) {
            throw new IllegalArgumentException("query must contain at most " + MAX_QUERY_CODE_POINTS + " code points");
        }

        if (query.codePoints().allMatch(SearchRequest::isQueryWhitespace)) {
            throw new IllegalArgumentException("query must not be blank");
        }

        if (query.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("query must not contain NUL");
        }

        if (limit < 1 || limit > MAX_LIMIT) {
            throw new IllegalArgumentException("limit must be between 1 and " + MAX_LIMIT);
        }
    }

    /**
     * Creates a request for at most {@value #DEFAULT_LIMIT} hits with the same validation as
     * {@link #SearchRequest(String, int)}.
     *
     * @param query the original plain-text query
     */
    public SearchRequest(String query) {
        this(query, DEFAULT_LIMIT);
    }

    // Includes non-breaking spaces and NEXT LINE, which String.isBlank() does not recognize.
    static boolean isQueryWhitespace(int codePoint) {
        return Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint) || codePoint == 0x85;
    }
}
