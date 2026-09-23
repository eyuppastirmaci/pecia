package dev.eyuppastirmaci.pecia.storage.sqlite;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Validates profile storage and invalidation against the bundled V3 declarations. */
final class SqliteIndexingProfileSchema {
    private static final String RESOURCE = "/db/migration/V3__add_file_indexing_profiles.sql";
    private static final Set<String> REQUIRED_OBJECTS = Set.of(
            "file_indexing_profiles",
            "files_indexing_profile_update",
            "chunks_indexing_profile_insert",
            "chunks_indexing_profile_update",
            "chunks_indexing_profile_delete",
            "chunk_headings_indexing_profile_insert",
            "chunk_headings_indexing_profile_update",
            "chunk_headings_indexing_profile_delete",
            "chunk_attributes_indexing_profile_insert",
            "chunk_attributes_indexing_profile_update",
            "chunk_attributes_indexing_profile_delete");
    // Extract declarations only from this fixed resource layout; trigger bodies contain semicolons.
    private static final Pattern DECLARATION = Pattern.compile(
            "(?ms)^CREATE TABLE (file_indexing_profiles) \\(.*?^\\);|^CREATE TRIGGER ([a-z_]+)\\b.*?^END;");

    private final String script;
    private final Map<String, Definition> definitions;

    private SqliteIndexingProfileSchema(String script, Map<String, Definition> definitions) {
        this.script = script;
        this.definitions = definitions;
    }

    static SqliteIndexingProfileSchema load() throws IOException {
        String script;
        try (var input = SqliteIndexingProfileSchema.class.getResourceAsStream(RESOURCE)) {
            if (input == null) {
                throw new IOException("Missing SQLite schema resource: " + RESOURCE);
            }
            script = new String(input.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n");
        }

        Map<String, Definition> definitions = new LinkedHashMap<>();
        var matcher = DECLARATION.matcher(script);
        while (matcher.find()) {
            boolean table = matcher.group(1) != null;
            String name = table ? matcher.group(1) : matcher.group(2);
            if (definitions.put(name, new Definition(table ? "table" : "trigger", canonical(matcher.group())))
                    != null) {
                throw new IOException("Duplicate bundled SQLite indexing-profile declaration: " + name);
            }
        }
        if (!definitions.keySet().equals(REQUIRED_OBJECTS)) {
            throw new IOException("Incomplete bundled SQLite indexing-profile declarations in " + RESOURCE);
        }
        return new SqliteIndexingProfileSchema(script, Map.copyOf(definitions));
    }

    String script() {
        return script;
    }

    void validate(Connection connection) throws SQLException {
        try (var query = connection.prepareStatement("SELECT type, sql FROM main.sqlite_schema WHERE name = ?")) {
            for (var object : definitions.entrySet()) {
                query.setString(1, object.getKey());
                try (var result = query.executeQuery()) {
                    if (!result.next()
                            || !object.getValue().type().equals(result.getString(1))
                            || !object.getValue().sql().equals(canonical(result.getString(2)))) {
                        throw new SQLException(
                                "Missing or incompatible SQLite indexing-profile schema object: " + object.getKey());
                    }
                }
            }
        }

        try (var statement = connection.createStatement()) {
            statement.execute(
                    "SELECT file_id, tokenizer_key, max_tokens, overlap_tokens FROM main.file_indexing_profiles LIMIT 0");
        }
    }

    private static String canonical(String sql) {
        if (sql == null) {
            return "";
        }
        String result = sql.replace("\r\n", "\n").strip();
        // sqlite_schema omits the final terminator; preserve whitespace and string literals inside SQL.
        return result.endsWith(";") ? result.substring(0, result.length() - 1).stripTrailing() : result;
    }

    private record Definition(String type, String sql) {}
}
