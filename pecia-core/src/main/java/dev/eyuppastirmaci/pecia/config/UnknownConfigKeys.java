package dev.eyuppastirmaci.pecia.config;

import dev.eyuppastirmaci.pecia.diagnostic.Warning;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.tomlj.TomlParseResult;
import org.tomlj.TomlPosition;
import org.tomlj.TomlTable;

/**
 * Finds keys in parsed configuration that Pecia does not read. Every unknown leaf is reported with its full dotted
 * path, including leaves inside unknown tables, so each one can get its own suggestion; an empty unknown table is
 * reported as a table. Arrays, including arrays of tables, are leaves. A key named {@code model} names the only
 * supported embedding model instead of suggesting a key.
 */
final class UnknownConfigKeys {

    private static final Pattern BARE_KEY = Pattern.compile("[A-Za-z0-9_-]+");
    private static final String MODEL_KEY = "model";
    private static final String ONLY_SUPPORTED_MODEL = "all-MiniLM-L6-v2 is the only supported embedding model";
    private static final Comparator<Warning> SOURCE_ORDER = Comparator.comparing(
                    Warning::position,
                    Comparator.nullsLast(
                            Comparator.comparingInt(Warning.Position::line).thenComparingInt(Warning.Position::column)))
            .thenComparing(Warning::message);

    private UnknownConfigKeys() {}

    /**
     * Returns a warning for each unknown key in source order.
     *
     * @param toml configuration that parsed and validated without errors
     * @param source the absolute, normalized configuration file
     */
    static List<Warning> find(TomlParseResult toml, Path source) {
        List<Warning> warnings = new ArrayList<>();
        collect(toml, toml, List.of(), source, warnings);
        warnings.sort(SOURCE_ORDER);

        return List.copyOf(warnings);
    }

    private static void collect(
            TomlParseResult toml, TomlTable table, List<String> parent, Path source, List<Warning> warnings) {
        for (String key : table.keySet()) {
            List<String> path = append(parent, key);
            Object value = table.get(List.of(key));

            if (value instanceof TomlTable child) {
                // Walking unknown tables too gives each leaf its own warning; only an empty one is reported itself.
                if (isKnownTable(path) || !child.isEmpty()) {
                    collect(toml, child, path, source, warnings);
                } else {
                    warnings.add(
                            warning(toml, path, source, "unknown table ", suggestion(KeySuggestion.forTable(path))));
                }
            } else if (!isKnownKey(path)) {
                warnings.add(warning(toml, path, source, "unknown key ", keyNote(path)));
            }
        }
    }

    private static String keyNote(List<String> path) {
        // A model choice would otherwise be pointed at search.mode, which is one edit away from "model".
        if (path.getLast().equals(MODEL_KEY)) {
            return ONLY_SUPPORTED_MODEL;
        }

        return suggestion(KeySuggestion.forKey(path));
    }

    private static String suggestion(String knownName) {
        return knownName == null ? null : "did you mean " + knownName + "?";
    }

    private static Warning warning(TomlParseResult toml, List<String> path, Path source, String kind, String note) {
        TomlPosition position = toml.inputPositionOf(path);
        String message = kind + display(path) + (note == null ? "" : " (" + note + ")");

        return new Warning(
                Warning.Code.UNKNOWN_CONFIG_KEY,
                source,
                position == null ? null : new Warning.Position(position.line(), position.column()),
                message);
    }

    private static boolean isKnownTable(List<String> path) {
        return path.size() == 1 && ConfigKey.tables().contains(path.get(0));
    }

    private static boolean isKnownKey(List<String> path) {
        if (path.size() != 2) {
            return false;
        }

        for (ConfigKey key : ConfigKey.values()) {
            if (key.table().equals(path.get(0)) && key.leaf().equals(path.get(1))) {
                return true;
            }
        }

        return false;
    }

    /** Joins key segments as TOML writes them, quoting only segments that are not bare keys. */
    static String display(List<String> path) {
        List<String> segments = new ArrayList<>();

        for (String segment : path) {
            segments.add(BARE_KEY.matcher(segment).matches() ? segment : quote(segment));
        }

        return String.join(".", segments);
    }

    private static String quote(String segment) {
        StringBuilder quoted = new StringBuilder("\"");

        // Escape only what a TOML basic string requires, so non-ASCII letters stay readable.
        for (char c : segment.toCharArray()) {
            switch (c) {
                case '"' -> quoted.append("\\\"");
                case '\\' -> quoted.append("\\\\");
                case '\t' -> quoted.append("\\t");
                case '\n' -> quoted.append("\\n");
                case '\r' -> quoted.append("\\r");
                default -> {
                    if (c < 0x20 || c == 0x7f) {
                        quoted.append(String.format(Locale.ROOT, "\\u%04X", (int) c));
                    } else {
                        quoted.append(c);
                    }
                }
            }
        }

        return quoted.append('"').toString();
    }

    private static List<String> append(List<String> parent, String key) {
        List<String> path = new ArrayList<>(parent);
        path.add(key);

        return List.copyOf(path);
    }
}
