package dev.eyuppastirmaci.pecia.storage.sqlite;

import dev.eyuppastirmaci.pecia.search.MatchRange;
import dev.eyuppastirmaci.pecia.search.SearchHit;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * A snippet with the ranges where it matches the query, read from the markers FTS5 puts around each match.
 *
 * @param matches ordered, non-overlapping code point ranges within the text
 */
record HighlightedSnippet(String text, List<MatchRange> matches) {

    /** Opens a match in a marked snippet: U+FDD0, a noncharacter that text almost never holds. */
    static final String SQL_OPEN = "char(64976)";
    /** Closes a match in a marked snippet: U+FDD1. */
    static final String SQL_CLOSE = "char(64977)";

    private static final int OPEN = 0xFDD0;
    private static final int CLOSE = 0xFDD1;

    /** Copies the ranges and requires the text. */
    HighlightedSnippet {
        Objects.requireNonNull(text, "text");
        matches = List.copyOf(matches);
    }

    /**
     * Reads the ranges from {@code marked}, which FTS5 built like {@code plain} but with markers around each match.
     * When removing the markers does not give exactly {@code plain}, the text itself held a marker character, so the
     * ranges cannot be told apart and the snippet has none; ranges are never wrong, at worst missing.
     */
    static HighlightedSnippet read(String plain, String marked) {
        List<MatchRange> matches = new ArrayList<>();
        StringBuilder text = new StringBuilder(plain.length());
        int position = 0;
        int start = -1;

        for (int offset = 0; offset < marked.length(); ) {
            int codePoint = marked.codePointAt(offset);
            offset += Character.charCount(codePoint);

            if (codePoint == OPEN) {
                if (start >= 0) {
                    return new HighlightedSnippet(plain, List.of());
                }

                start = position;
            } else if (codePoint == CLOSE) {
                if (start < 0) {
                    return new HighlightedSnippet(plain, List.of());
                }

                if (position > start) {
                    matches.add(new MatchRange(start, position));
                }

                start = -1;
            } else {
                text.appendCodePoint(codePoint);
                position++;
            }
        }

        if (start >= 0 || !text.toString().equals(plain)) {
            return new HighlightedSnippet(plain, List.of());
        }

        return new HighlightedSnippet(plain, matches);
    }

    /**
     * Caps the text at {@link SearchHit#MAX_SNIPPET_CODE_POINTS}, marking a cut with an ellipsis; ranges past the cut
     * are dropped and a range across it ends there.
     */
    HighlightedSnippet bounded() {
        if (text.codePointCount(0, text.length()) <= SearchHit.MAX_SNIPPET_CODE_POINTS) {
            return this;
        }

        int kept = SearchHit.MAX_SNIPPET_CODE_POINTS - 1;
        List<MatchRange> cut = matches.stream()
                .filter(match -> match.start() < kept)
                .map(match -> new MatchRange(match.start(), Math.min(match.end(), kept)))
                .toList();

        return new HighlightedSnippet(text.substring(0, text.offsetByCodePoints(0, kept)) + "…", cut);
    }
}
