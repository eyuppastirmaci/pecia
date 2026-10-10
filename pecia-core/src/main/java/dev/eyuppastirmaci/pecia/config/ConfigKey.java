package dev.eyuppastirmaci.pecia.config;

import java.util.Arrays;
import java.util.List;

/**
 * Every key Pecia reads from {@code .pecia.toml}. The parser reads keys only through these constants, so a key can
 * be supported only by listing it here, and anything not listed is unknown.
 */
enum ConfigKey {
    INDEX_INCLUDE("index", "include"),
    INDEX_EXCLUDE("index", "exclude"),
    INDEX_MAX_FILE_BYTES("index", "max_file_bytes"),
    CHUNK_MAX_TOKENS("chunk", "max_tokens"),
    CHUNK_OVERLAP_TOKENS("chunk", "overlap_tokens"),
    SEMANTIC_ENABLED("semantic", "enabled"),
    EMBED_CONCURRENCY("embed", "concurrency"),
    SEARCH_MODE("search", "mode"),
    STORE_PATH("store", "path");

    private final String table;
    private final String leaf;

    ConfigKey(String table, String leaf) {
        this.table = table;
        this.leaf = leaf;
    }

    /** Returns the table that holds this key, such as {@code chunk}. */
    String table() {
        return table;
    }

    /** Returns the key's name within its table, such as {@code max_tokens}. */
    String leaf() {
        return leaf;
    }

    /** Returns the dotted path, such as {@code chunk.max_tokens}. */
    String path() {
        return table + "." + leaf;
    }

    /** Returns the tables that hold known keys, in declaration order. */
    static List<String> tables() {
        return Arrays.stream(values()).map(ConfigKey::table).distinct().toList();
    }
}
