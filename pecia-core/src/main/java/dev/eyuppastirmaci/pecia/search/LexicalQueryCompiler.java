package dev.eyuppastirmaci.pecia.search;

import java.util.Optional;
import java.util.StringJoiner;

/** Converts plain query text into literal FTS phrases without exposing raw FTS syntax. */
final class LexicalQueryCompiler {
    // Bounds query complexity before filtering so punctuation-only parts cannot bypass the limit.
    static final int MAX_QUERY_PARTS = 64;

    /**
     * Quotes each searchable whitespace-delimited part and combines the phrases with AND.
     * SQLite remains responsible for tokenization and case/diacritic handling within each phrase.
     *
     * @param request the validated request whose original text is preserved
     * @return a MATCH expression to bind as a SQL parameter, or empty when no searchable parts remain
     * @throws NullPointerException if request is null
     * @throws IllegalArgumentException if the text contains more than 64 raw query parts
     */
    Optional<String> compile(SearchRequest request) {
        String query = request.query();
        StringJoiner phrases = new StringJoiner(" AND ");
        int offset = 0;
        int partCount = 0;

        while (offset < query.length()) {
            int codePoint = query.codePointAt(offset);

            if (SearchRequest.isQueryWhitespace(codePoint)) {
                offset += Character.charCount(codePoint);
                continue;
            }

            int start = offset;

            do {
                offset += Character.charCount(query.codePointAt(offset));
            } while (offset < query.length() && !SearchRequest.isQueryWhitespace(query.codePointAt(offset)));

            if (++partCount > MAX_QUERY_PARTS) {
                throw new IllegalArgumentException("query must contain at most " + MAX_QUERY_PARTS + " raw parts");
            }

            String part = query.substring(start, offset);

            if (part.codePoints().anyMatch(LexicalQueryCompiler::containsSearchableCharacter)) {
                phrases.add("\"" + part.replace("\"", "\"\"") + "\"");
            }
        }

        return phrases.length() == 0 ? Optional.empty() : Optional.of(phrases.toString());
    }

    private static boolean containsSearchableCharacter(int codePoint) {
        return Character.isLetter(codePoint) || switch (Character.getType(codePoint)) {
            case Character.DECIMAL_DIGIT_NUMBER, Character.LETTER_NUMBER, Character.OTHER_NUMBER,
                    Character.PRIVATE_USE -> true;
            default -> false;
        };
    }
}
