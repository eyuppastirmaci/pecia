package dev.eyuppastirmaci.pecia.search;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * What a query's text looks like, which decides how hybrid search weighs its two rankings. For identifier-like text
 * the lexical order decides: the chunks that name the identifier exactly would otherwise be outranked by lower lexical
 * candidates that also have a semantic rank. Semantic ranks then only order the chunks lexical search did not find,
 * after all lexical candidates.
 */
public enum QueryShape {
    /** A few words, at least one of which looks like code: a class, method, constant, package, or path. */
    // Below 61 / (160 * 161), so even the deepest neighbouring lexical ranks keep their order whatever the semantic
    // ranks.
    IDENTIFIER(1.0, 0.002),
    /** Anything else, such as a question or a description; both rankings count equally. */
    NATURAL_LANGUAGE(1.0, 1.0);

    // Identifier-like text has at most this many whitespace-separated parts.
    private static final int MAX_IDENTIFIER_PARTS = 3;
    private static final List<Pattern> CODE_LIKE = List.of(
            // camelCase and PascalCase: a lowercase letter, then possibly more, then an uppercase one.
            Pattern.compile("\\p{Ll}[\\p{Ll}\\p{Nd}]*\\p{Lu}"),
            // snake_case and SCREAMING_CASE.
            Pattern.compile("[\\p{L}\\p{Nd}]_[\\p{L}\\p{Nd}]"),
            // Letters and digits inside one word, such as HTTP2 or v1beta.
            Pattern.compile("\\p{L}\\p{Nd}|\\p{Nd}\\p{L}"),
            // Package, path, member, and pointer separators inside a word.
            Pattern.compile("[\\p{L}\\p{Nd}](\\.|/|::|#|->)[\\p{L}\\p{Nd}]"));

    private final double lexicalWeight;
    private final double semanticWeight;

    QueryShape(double lexicalWeight, double semanticWeight) {
        this.lexicalWeight = lexicalWeight;
        this.semanticWeight = semanticWeight;
    }

    /** Returns the weight of a chunk's lexical rank in reciprocal rank fusion. */
    public double lexicalWeight() {
        return lexicalWeight;
    }

    /** Returns the weight of a chunk's semantic rank in reciprocal rank fusion. */
    public double semanticWeight() {
        return semanticWeight;
    }

    /**
     * Classifies query text with deterministic rules: at most three whitespace-separated parts, at least one of which
     * looks like code, is an identifier; anything else is natural language.
     *
     * @throws NullPointerException if query is null
     */
    public static QueryShape of(String query) {
        Objects.requireNonNull(query, "query");
        List<String> parts = parts(query);

        if (parts.isEmpty() || parts.size() > MAX_IDENTIFIER_PARTS) {
            return NATURAL_LANGUAGE;
        }

        for (String part : parts) {
            for (Pattern pattern : CODE_LIKE) {
                if (pattern.matcher(part).find()) {
                    return IDENTIFIER;
                }
            }
        }

        return NATURAL_LANGUAGE;
    }

    /** Splits text at the same whitespace that lexical search uses to separate query parts. */
    private static List<String> parts(String query) {
        List<String> parts = new ArrayList<>();
        StringBuilder part = new StringBuilder();

        for (int offset = 0; offset < query.length(); ) {
            int codePoint = query.codePointAt(offset);

            if (SearchRequest.isQueryWhitespace(codePoint)) {
                if (!part.isEmpty()) {
                    parts.add(part.toString());
                    part.setLength(0);
                }
            } else {
                part.appendCodePoint(codePoint);
            }

            offset += Character.charCount(codePoint);
        }

        if (!part.isEmpty()) {
            parts.add(part.toString());
        }

        return parts;
    }
}
