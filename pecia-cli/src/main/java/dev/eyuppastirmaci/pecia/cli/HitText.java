package dev.eyuppastirmaci.pecia.cli;

import dev.eyuppastirmaci.pecia.search.MatchRange;
import java.math.BigDecimal;
import java.math.MathContext;
import java.util.ArrayList;
import java.util.List;

/** Formats parts of a search hit for people: rounded scores and snippets with their matches highlighted. */
final class HitText {

    private static final MathContext SIGNIFICANT = new MathContext(3);

    private HitText() {}

    /**
     * Rounds a score to three significant digits, without trailing zeros or an exponent, such as {@code -0.859},
     * {@code 0.0328}, or {@code -0.000002}; JSON keeps the full value.
     */
    static String score(double value) {
        // Negative zero becomes plain zero, which strips to "0".
        return new BigDecimal(value).round(SIGNIFICANT).stripTrailingZeros().toPlainString();
    }

    /**
     * Splits the snippet into lines as {@link String#lines()} does and paints the matched ranges, which count code
     * points from the snippet's start; a match that spans a line break is painted on both lines. Without colour the
     * lines are plain.
     */
    static List<String> snippetLines(String snippet, List<MatchRange> matches, Terminal terminal) {
        if (!terminal.color() || matches.isEmpty()) {
            return snippet.lines().toList();
        }

        int[] codePoints = snippet.codePoints().toArray();
        boolean[] matched = new boolean[codePoints.length];

        for (MatchRange match : matches) {
            for (int index = match.start(); index < Math.min(match.end(), codePoints.length); index++) {
                matched[index] = true;
            }
        }

        List<String> lines = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        StringBuilder run = new StringBuilder();
        boolean inMatch = false;

        for (int index = 0; index < codePoints.length; index++) {
            int codePoint = codePoints[index];

            if (codePoint == '\n' || codePoint == '\r') {
                line.append(flush(run, inMatch, terminal));
                lines.add(line.toString());
                line.setLength(0);

                // A carriage return and a line feed end one line together.
                if (codePoint == '\r' && index + 1 < codePoints.length && codePoints[index + 1] == '\n') {
                    index++;
                }

                continue;
            }

            if (matched[index] != inMatch) {
                line.append(flush(run, inMatch, terminal));
                inMatch = matched[index];
            }

            run.appendCodePoint(codePoint);
        }

        line.append(flush(run, inMatch, terminal));

        if (!line.isEmpty()) {
            lines.add(line.toString());
        }

        return lines;
    }

    /** Returns the run, painted when it is a match, and empties it; an empty run stays empty. */
    private static String flush(StringBuilder run, boolean match, Terminal terminal) {
        String text = run.toString();
        run.setLength(0);

        return match ? terminal.paint(text, Terminal.Style.BOLD, Terminal.Style.RED) : text;
    }
}
