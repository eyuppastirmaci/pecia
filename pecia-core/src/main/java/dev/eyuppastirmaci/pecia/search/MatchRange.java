package dev.eyuppastirmaci.pecia.search;

/**
 * Where a snippet matches the query, in Unicode code points from the snippet's start, so callers can highlight it
 * without parsing the text.
 *
 * @param start the first matching code point
 * @param end the code point after the last matching one
 */
public record MatchRange(int start, int end) {

    /**
     * Validates the range.
     *
     * @throws IllegalArgumentException if start is negative or end is not after start
     */
    public MatchRange {
        if (start < 0 || end <= start) {
            throw new IllegalArgumentException("Invalid match range: " + start + ".." + end);
        }
    }
}
