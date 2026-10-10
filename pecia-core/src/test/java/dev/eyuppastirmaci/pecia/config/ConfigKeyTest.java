package dev.eyuppastirmaci.pecia.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.util.Arrays;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.tomlj.Toml;
import org.tomlj.TomlParseResult;
import org.tomlj.TomlTable;

/** The known-key registry, the keys the parser reads, and the default template must describe the same keys. */
class ConfigKeyTest {

    // One non-default value per key; a new key fails the completeness check until it gets one.
    private static final Map<ConfigKey, String> OVERRIDES = overrides();

    private final PeciaConfigParser parser = new PeciaConfigParser();

    @Test
    void everyKeyHasAnOverrideValue() {
        assertEquals(EnumSet.allOf(ConfigKey.class), OVERRIDES.keySet());
    }

    @ParameterizedTest
    @EnumSource(ConfigKey.class)
    void parserReadsEveryKnownKey(ConfigKey key) {
        String toml = "[" + key.table() + "]\n" + key.leaf() + " = " + OVERRIDES.get(key) + "\n";

        assertNotEquals(PeciaConfig.defaults(), parser.parse(toml), key.path() + " must change the parsed config");
    }

    @Test
    void defaultTemplateDefinesExactlyTheKnownKeys() {
        TomlParseResult template = Toml.parse(new PeciaConfigFile().defaultToml());
        assertFalse(template.hasErrors(), () -> template.errors().toString());

        assertEquals(knownPaths(), leafPaths(template, ""));
    }

    @Test
    void tablesAreTheKnownKeysTablesInOrder() {
        assertEquals(List.of("index", "chunk", "embed", "store"), ConfigKey.tables());
    }

    private static Set<String> knownPaths() {
        return Arrays.stream(ConfigKey.values()).map(ConfigKey::path).collect(Collectors.toCollection(TreeSet::new));
    }

    private static Set<String> leafPaths(TomlTable table, String prefix) {
        Set<String> paths = new TreeSet<>();

        for (String key : table.keySet()) {
            Object value = table.get(List.of(key));
            String path = prefix + key;

            if (value instanceof TomlTable child) {
                paths.addAll(leafPaths(child, path + "."));
            } else {
                paths.add(path);
            }
        }

        return paths;
    }

    private static Map<ConfigKey, String> overrides() {
        Map<ConfigKey, String> values = new EnumMap<>(ConfigKey.class);
        values.put(ConfigKey.INDEX_INCLUDE, "[\"**/*.rs\"]");
        values.put(ConfigKey.INDEX_EXCLUDE, "[\"**/build/**\"]");
        values.put(ConfigKey.INDEX_MAX_FILE_BYTES, "4096");
        values.put(ConfigKey.CHUNK_MAX_TOKENS, "128");
        values.put(ConfigKey.CHUNK_OVERLAP_TOKENS, "16");
        values.put(ConfigKey.EMBED_CONCURRENCY, "4");
        values.put(ConfigKey.STORE_PATH, "\"custom/index.db\"");

        return values;
    }
}
