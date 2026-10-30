package dev.eyuppastirmaci.pecia.cli;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Writes JSON documents for {@code --json} output. Values are {@link JsonObject}s, lists, strings, booleans, integers,
 * longs, finite doubles, and null; documents are indented by two spaces so people can read them too, and are plain
 * ASCII, so they arrive intact whatever encoding the console or the reading program assumes.
 */
final class Json {

    /** The version of the documents' shape, which changes only when a field changes meaning or goes away. */
    static final int SCHEMA_VERSION = 1;

    private Json() {}

    /** Returns the value as indented JSON, without a trailing newline. */
    static String write(Object value) {
        StringBuilder out = new StringBuilder();
        write(out, value, 0);
        return out.toString();
    }

    private static void write(StringBuilder out, Object value, int depth) {
        switch (value) {
            case null -> out.append("null");
            case JsonObject object -> writeObject(out, object.fields, depth);
            case List<?> list -> writeArray(out, list, depth);
            case String text -> writeString(out, text);
            case Boolean bool -> out.append(bool);
            case Integer number -> out.append(number);
            case Long number -> out.append(number);
            case Double number -> {
                if (!Double.isFinite(number)) {
                    throw new IllegalArgumentException("JSON has no " + number);
                }

                // Java's shortest round-trip form, such as 0.25 or 1.0E-6, is valid JSON.
                out.append(number);
            }
            default ->
                throw new IllegalArgumentException(
                        "Not a JSON value: " + value.getClass().getName());
        }
    }

    private static void writeObject(StringBuilder out, Map<String, Object> fields, int depth) {
        if (fields.isEmpty()) {
            out.append("{}");
            return;
        }

        out.append('{');
        String separator = "\n";

        for (Map.Entry<String, Object> field : fields.entrySet()) {
            out.append(separator);
            indent(out, depth + 1);
            writeString(out, field.getKey());
            out.append(": ");
            write(out, field.getValue(), depth + 1);
            separator = ",\n";
        }

        out.append('\n');
        indent(out, depth);
        out.append('}');
    }

    private static void writeArray(StringBuilder out, List<?> values, int depth) {
        if (values.isEmpty()) {
            out.append("[]");
            return;
        }

        out.append('[');
        String separator = "\n";

        for (Object value : values) {
            out.append(separator);
            indent(out, depth + 1);
            write(out, value, depth + 1);
            separator = ",\n";
        }

        out.append('\n');
        indent(out, depth);
        out.append(']');
    }

    /**
     * Quotes text, escaping what JSON requires and every character outside printable ASCII as a six-character Unicode
     * escape; a character outside the Basic Multilingual Plane becomes its escaped surrogate pair, as JSON specifies,
     * and an unpaired surrogate is escaped like any other character.
     */
    static void writeString(StringBuilder out, String text) {
        out.append('"');

        for (int index = 0; index < text.length(); index++) {
            char current = text.charAt(index);

            switch (current) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (current < 0x20 || current > 0x7e) {
                        out.append(String.format("\\u%04x", (int) current));
                    } else {
                        out.append(current);
                    }
                }
            }
        }

        out.append('"');
    }

    private static void indent(StringBuilder out, int depth) {
        out.append("  ".repeat(depth));
    }

    /** A JSON object whose fields keep the order they were added in. */
    static final class JsonObject {

        private final Map<String, Object> fields = new LinkedHashMap<>();

        /**
         * Adds a field.
         *
         * @throws IllegalArgumentException if the object already has the field
         */
        JsonObject put(String name, Object value) {
            if (fields.containsKey(name)) {
                throw new IllegalArgumentException("Duplicate JSON field: " + name);
            }

            fields.put(name, value);
            return this;
        }
    }
}
