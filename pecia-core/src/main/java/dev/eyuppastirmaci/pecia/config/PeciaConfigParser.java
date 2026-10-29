package dev.eyuppastirmaci.pecia.config;

import dev.eyuppastirmaci.pecia.diagnostic.Warning;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import org.tomlj.Toml;
import org.tomlj.TomlArray;
import org.tomlj.TomlInvalidTypeException;
import org.tomlj.TomlParseError;
import org.tomlj.TomlParseResult;

/** Parses project TOML and validates supported settings against their defaults. */
public final class PeciaConfigParser {

    private static final String BYTE_ORDER_MARK = "\uFEFF";

    /**
     * Parses .pecia.toml content into a typed config, filling missing keys with defaults. A leading byte order
     * mark is ignored.
     *
     * @param toml raw TOML text
     * @return the parsed configuration
     * @throws NullPointerException if toml is null
     * @throws IllegalArgumentException if the TOML is malformed or a value has the wrong type, an invalid range,
     *     or an unknown spelling
     */
    public PeciaConfig parse(String toml) {
        return read(parseToml(toml));
    }

    /**
     * Parses configuration read from a file and reports the keys Pecia does not recognize. Warnings are collected
     * only after the configuration is valid, and they never change which settings apply.
     *
     * @param toml raw TOML text
     * @param source the absolute, normalized file the text came from
     * @throws IllegalArgumentException if the TOML is malformed or a value is invalid
     */
    Parsed parse(String toml, Path source) {
        TomlParseResult result = parseToml(toml);
        PeciaConfig config = read(result);

        return new Parsed(config, UnknownConfigKeys.find(result, source));
    }

    private static TomlParseResult parseToml(String toml) {
        // Some Windows editors start UTF-8 files with a byte order mark, which TOML parsing rejects.
        String text = toml.startsWith(BYTE_ORDER_MARK) ? toml.substring(1) : toml;
        TomlParseResult result = Toml.parse(text);

        if (result.hasErrors()) {
            throw new IllegalArgumentException(describeErrors(result));
        }

        return result;
    }

    private static PeciaConfig read(TomlParseResult result) {
        requireTables(result);

        PeciaConfig defaults = PeciaConfig.defaults();

        return new PeciaConfig(
                stringList(result, ConfigKey.INDEX_INCLUDE, defaults.include()),
                stringList(result, ConfigKey.INDEX_EXCLUDE, defaults.exclude()),
                intValue(result, ConfigKey.INDEX_MAX_FILE_BYTES, defaults.maxFileBytes()),
                intValue(result, ConfigKey.CHUNK_MAX_TOKENS, defaults.maxTokens()),
                intValue(result, ConfigKey.CHUNK_OVERLAP_TOKENS, defaults.overlapTokens()),
                intValue(result, ConfigKey.EMBED_CONCURRENCY, defaults.embedConcurrency()),
                stringValue(result, ConfigKey.STORE_PATH, defaults.storePath()),
                booleanValue(result, ConfigKey.SEMANTIC_ENABLED, defaults.semanticEnabled()),
                searchModeValue(result, ConfigKey.SEARCH_MODE, defaults.searchMode()));
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
        for (String table : ConfigKey.tables()) {
            if (toml.contains(table) && !toml.isTable(table)) {
                throw new IllegalArgumentException(table + " must be a table");
            }
        }
    }

    private static List<String> stringList(TomlParseResult toml, ConfigKey key, List<String> fallback) {
        TomlArray array = tomlValue(key, "an array of strings", toml::getArray);

        if (array == null) {
            return fallback;
        }

        List<String> values = new ArrayList<>();

        for (Object item : array.toList()) {
            if (!(item instanceof String value)) {
                throw new IllegalArgumentException(key.path() + " must contain only strings");
            }

            values.add(value);
        }

        return values;
    }

    private static int intValue(TomlParseResult toml, ConfigKey key, int fallback) {
        Long value = tomlValue(key, "an integer", toml::getLong);

        if (value == null) {
            return fallback;
        }

        // TOML integers are 64-bit; report values outside int range like any other invalid value.
        if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(key.path() + " must be a 32-bit integer, got " + value);
        }

        return value.intValue();
    }

    private static String stringValue(TomlParseResult toml, ConfigKey key, String fallback) {
        String value = tomlValue(key, "a string", toml::getString);

        return value == null ? fallback : value;
    }

    private static boolean booleanValue(TomlParseResult toml, ConfigKey key, boolean fallback) {
        Boolean value = tomlValue(key, "a boolean", toml::getBoolean);

        return value == null ? fallback : value;
    }

    private static SearchMode searchModeValue(TomlParseResult toml, ConfigKey key, SearchMode fallback) {
        String value = tomlValue(key, "a string", toml::getString);

        if (value == null) {
            return fallback;
        }

        return SearchMode.fromConfigValue(value)
                .orElseThrow(() -> new IllegalArgumentException(key.path()
                        + " must be one of "
                        + String.join(", ", SearchMode.configValues())
                        + ", got \""
                        + value
                        + "\""));
    }

    /** A parsed configuration and the warnings found in its file, in source order. */
    record Parsed(PeciaConfig config, List<Warning> warnings) {
        Parsed {
            Objects.requireNonNull(config, "config");
            warnings = List.copyOf(warnings);
        }
    }

    private static <T> T tomlValue(ConfigKey key, String expected, Function<String, T> getter) {
        // tomlj throws when a key exists with the wrong type; turn that into our own clear error.
        try {
            return getter.apply(key.path());
        } catch (TomlInvalidTypeException e) {
            throw new IllegalArgumentException(key.path() + " must be " + expected, e);
        }
    }
}
