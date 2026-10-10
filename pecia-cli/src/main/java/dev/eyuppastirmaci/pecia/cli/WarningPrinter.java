package dev.eyuppastirmaci.pecia.cli;

import dev.eyuppastirmaci.pecia.diagnostic.Warning;
import java.io.PrintWriter;
import java.util.List;

/** Prints core warnings as compiler-style lines: {@code warning: <path>:<line>:<column>: <message>}. */
final class WarningPrinter {

    private WarningPrinter() {}

    /** Prints each warning on its own line, in the order given. */
    static void print(PrintWriter err, List<Warning> warnings) {
        for (Warning warning : warnings) {
            err.println(format(warning));
        }
    }

    /** Formats one warning, omitting the line and column when the warning has no position. */
    static String format(Warning warning) {
        Warning.Position position = warning.position();
        String location = position == null
                ? warning.source().toString()
                : warning.source() + ":" + position.line() + ":" + position.column();

        return "warning: " + location + ": " + warning.message();
    }
}
