package dev.eyuppastirmaci.pecia.diagnostic;

import java.nio.file.Path;
import java.util.Objects;

/**
 * A non-fatal problem found by a core operation, such as a configuration key Pecia does not recognize. Warnings never
 * change an operation's outcome; callers decide whether and how to report them.
 *
 * @param source the absolute, normalized file the warning refers to
 * @param position the location in the source, or null when the warning has no location
 * @param message a human-readable description that omits the source and position
 */
public record Warning(Code code, Path source, Position position, String message) {

    /**
     * Validates the warning's fields.
     *
     * @throws NullPointerException if code, source, or message is null
     * @throws IllegalArgumentException if source is relative or not normalized, or message is blank
     */
    public Warning {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(message, "message");

        if (!source.isAbsolute() || !source.equals(source.normalize())) {
            throw new IllegalArgumentException("source must be an absolute, normalized path: " + source);
        }

        if (message.isBlank()) {
            throw new IllegalArgumentException("message must not be blank");
        }
    }

    /** Identifies the kind of warning, so callers can react to it without parsing the message. */
    public enum Code {
        /** A configuration key or table that Pecia does not recognize; its value is ignored. */
        UNKNOWN_CONFIG_KEY
    }

    /** A 1-based line and column in a source file. */
    public record Position(int line, int column) {

        /**
         * Validates the location.
         *
         * @throws IllegalArgumentException if line or column is less than 1
         */
        public Position {
            if (line < 1 || column < 1) {
                throw new IllegalArgumentException("line and column must be at least 1, got " + line + ":" + column);
            }
        }
    }
}
