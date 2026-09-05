package dev.eyuppastirmaci.pecia.content;

/** A one-based, inclusive source line range. */
public record LineRange(int startLine, int endLine) implements SourceLocation {

    public LineRange {

        if (startLine < 1) {

            throw new IllegalArgumentException("startLine must be at least 1: " + startLine);
        }

        if (endLine < startLine) {

            throw new IllegalArgumentException("endLine must be greater than or equal to startLine: " + endLine);
        }
    }
}
