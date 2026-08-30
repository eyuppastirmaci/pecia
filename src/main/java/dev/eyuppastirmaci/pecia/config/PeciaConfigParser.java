package dev.eyuppastirmaci.pecia.config;

import org.tomlj.Toml;
import org.tomlj.TomlArray;
import org.tomlj.TomlInvalidTypeException;
import org.tomlj.TomlParseError;
import org.tomlj.TomlParseResult;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

public final class PeciaConfigParser {

    /**
     * Parses .pecia.toml content into a typed config, filling missing keys with defaults.
     *
     * @param toml raw TOML text
     * @return the parsed configuration
     * @throws IllegalArgumentException if the TOML is malformed or a value has the wrong type or an invalid range
     */
    public PeciaConfig parse(String toml) {
        TomlParseResult result = Toml.parse(toml);

        if (result.hasErrors()) {
            throw new IllegalArgumentException(describeErrors(result));
        }

        PeciaConfig defaults = PeciaConfig.defaults();

        return new PeciaConfig(
                stringList(result, "index.include", defaults.include()),
                stringList(result, "index.exclude", defaults.exclude()),
                intValue(result, "chunk.max_tokens", defaults.maxTokens()),
                intValue(result, "chunk.overlap_tokens", defaults.overlapTokens()),
                intValue(result, "embed.concurrency", defaults.embedConcurrency()),
                stringValue(result, "store.path", defaults.storePath())
        );
    }

    private static String describeErrors(TomlParseResult result) {
        StringBuilder message = new StringBuilder("Invalid .pecia.toml:");

        for (TomlParseError error : result.errors()) {
            message.append("\n  ").append(error.position()).append(": ").append(error.getMessage());
        }

        return message.toString();
    }

    private static List<String> stringList(TomlParseResult toml, String key, List<String> fallback) {
        TomlArray array = tomlValue(toml, key, "an array of strings", toml::getArray);

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
        Long value = tomlValue(toml, key, "an integer", toml::getLong);

        return value == null ? fallback : Math.toIntExact(value);
    }

    private static String stringValue(TomlParseResult toml, String key, String fallback) {
        String value = tomlValue(toml, key, "a string", toml::getString);

        return value == null ? fallback : value;
    }

    private static <T> T tomlValue(TomlParseResult toml, String key, String expected, Function<String, T> getter) {
        // tomlj throws when a key exists with the wrong type; turn that into our own clear error.
        try {
            return getter.apply(key);
        } catch (TomlInvalidTypeException e) {
            throw new IllegalArgumentException(key + " must be " + expected, e);
        }
    }
}
