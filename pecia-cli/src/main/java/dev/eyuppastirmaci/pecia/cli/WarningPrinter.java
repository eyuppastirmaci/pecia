package dev.eyuppastirmaci.pecia.cli;

import dev.eyuppastirmaci.pecia.diagnostic.Warning;
import java.util.ArrayList;
import java.util.List;

/**
 * Shows core warnings as compiler-style lines, {@code warning: <path>:<line>:<column>: <message>}, or as JSON objects.
 */
final class WarningPrinter {

    private WarningPrinter() {}

    /** Prints each warning on its own line, in the order given. */
    static void print(Diagnostics diagnostics, List<Warning> warnings) {
        for (Warning warning : warnings) {
            diagnostics.warning(describe(warning));
        }
    }

    /** Formats one warning as an uncoloured line. */
    static String format(Warning warning) {
        return "warning: " + describe(warning);
    }

    /** Describes one warning without its label, omitting the line and column when the warning has no position. */
    private static String describe(Warning warning) {
        Warning.Position position = warning.position();
        String location = position == null
                ? warning.source().toString()
                : warning.source() + ":" + position.line() + ":" + position.column();

        return location + ": " + warning.message();
    }

    /** Describes each warning as a JSON object; {@code line} and {@code column} are null without a position. */
    static List<Json.JsonObject> json(List<Warning> warnings) {
        List<Json.JsonObject> objects = new ArrayList<>();

        for (Warning warning : warnings) {
            Warning.Position position = warning.position();
            objects.add(new Json.JsonObject()
                    .put("code", warning.code().name())
                    .put("source", warning.source().toString())
                    .put("line", position == null ? null : position.line())
                    .put("column", position == null ? null : position.column())
                    .put("message", warning.message()));
        }

        return objects;
    }
}
