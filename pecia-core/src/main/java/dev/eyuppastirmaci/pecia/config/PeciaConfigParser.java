package dev.eyuppastirmaci.pecia.config;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import org.tomlj.Toml;
import org.tomlj.TomlArray;
import org.tomlj.TomlInvalidTypeException;
import org.tomlj.TomlParseError;
import org.tomlj.TomlParseResult;

/** Parses project TOML and validates supported settings against their defaults. */
public final class PeciaConfigParser {

    private static final String BYTE_ORDER_MARK = "\uFEFF";
    private static final List<String> SECTIONS = List.of("index", "chunk", "embed", "store");

    /**
     * Parses .pecia.toml content into a typed config, filling missing keys with defaults. A leading byte order
     * mark is ignored.
     *
     * @param toml raw TOML text
     * @return the parsed configuration
     * @throws NullPointerException if toml is null
     * @throws IllegalArgumentException if the TOML is malformed or a value has the wrong type or an
     *     invalid range
     */
    public PeciaConfig parse(String toml) {
        // Some Windows editors start UTF-8 files with a byte order mark, which TOML parsing rejects.
        String text = toml.startsWith(BYTE_ORDER_MARK) ? toml.substring(1) : toml;
        TomlParseResult result = Toml.parse(text);

        if (result.hasErrors()) {
            throw new IllegalArgumentException(describeErrors(result));
        }

        requireTables(result);

        PeciaConfig defaults = PeciaConfig.defaults();

        return new PeciaConfig(
                stringList(result, "index.include", defaults.include()),
                stringList(result, "index.exclude", defaults.exclude()),
                intValue(result, "index.max_file_bytes", defaults.maxFileBytes()),
                intValue(result, "chunk.max_tokens", defaults.maxTokens()),
                intValue(result, "chunk.overlap_tokens", defaults.overlapTokens()),
                intValue(result, "embed.concurrency", defaults.embedConcurrency()),
                stringValue(result, "store.path", defaults.storePath()));
    }

    private static String describeErrors(TomlParseResult result) {
        StringBuilder message = new StringBuilder("Invalid .pecia.toml:");

        for (TomlParseError error : result.errors()) {
            message.append("\n  ").append(error.position()).append(": ").append(error.getMessage());
        }

        return message.toString();
    }

    private static void requireTables(TomlParseResult toml) {
        // A section written as a plain value would otherwise hide its keys and silently apply defaults.
        for (String section : SECTIONS) {
            if (toml.contains(section) && !toml.isTable(section)) {
                throw new IllegalArgumentException(section + " must be a table");
            }
        }
    }

    private static List<String> stringList(TomlParseResult toml, String key, List<String> fallback) {
        TomlArray array = tomlValue(key, "an array of strings", toml::getArray);

        if (array == null) {
            return fallback;
        }

        List<String> values = new ArrayList<>();

        for (Object item : array.toList()) {
            if (!(item instanceof String value)) {
                throw new IllegalArgumentException(key + " must contain only strings");
            }

            values.add(value);
        }

        return values;
    }

    private static int intValue(TomlParseResult toml, String key, int fallback) {
        Long value = tomlValue(key, "an integer", toml::getLong);

        if (value == null) {
            return fallback;
        }

        // TOML integers are 64-bit; report values outside int range like any other invalid value.
        if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(key + " must be a 32-bit integer, got " + value);
        }

        return value.intValue();
    }

    private static String stringValue(TomlParseResult toml, String key, String fallback) {
        String value = tomlValue(key, "a string", toml::getString);

        return value == null ? fallback : value;
    }

    private static <T> T tomlValue(String key, String expected, Function<String, T> getter) {
        // tomlj throws when a key exists with the wrong type; turn that into our own clear error.
        try {
            return getter.apply(key);
        } catch (TomlInvalidTypeException e) {
            throw new IllegalArgumentException(key + " must be " + expected, e);
        }
    }
}
