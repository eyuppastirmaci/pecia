package dev.eyuppastirmaci.pecia.storage.sqlite;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Validates full chunking profiles, identity ownership, and invalidation against the V4 resource. */
final class SqliteChunkIdentitySchema {

    private static final String RESOURCE = "/db/migration/V4__add_chunk_identities.sql";
    private static final Set<String> REQUIRED_OBJECTS = Set.of(
            "file_chunking_profiles",
            "chunks_identity_owner",
            "chunk_identities",
            "chunk_identities_file",
            "files_chunking_profile_update",
            "chunks_chunking_profile_insert",
            "chunks_chunking_profile_update",
            "chunks_chunking_profile_delete",
            "chunk_headings_chunking_profile_insert",
            "chunk_headings_chunking_profile_update",
            "chunk_headings_chunking_profile_delete",
            "chunk_attributes_chunking_profile_insert",
            "chunk_attributes_chunking_profile_update",
            "chunk_attributes_chunking_profile_delete",
            "file_chunking_profiles_update");

    // Extract declarations only from this fixed resource layout; trigger bodies contain semicolons.
    private static final Pattern DECLARATION = Pattern.compile("(?ms)^CREATE TABLE ([a-z_]+) \\(.*?^\\);"
            + "|^CREATE (?:UNIQUE )?INDEX ([a-z_]+) ON [^\\r\\n]+;"
            + "|^CREATE TRIGGER ([a-z_]+)\\b.*?^END;");

    private final String script;
    private final Map<String, Definition> definitions;

    private SqliteChunkIdentitySchema(String script, Map<String, Definition> definitions) {
        this.script = script;
        this.definitions = definitions;
    }

    static SqliteChunkIdentitySchema load() throws IOException {
        String script;

        try (var input = SqliteChunkIdentitySchema.class.getResourceAsStream(RESOURCE)) {
            if (input == null) {
                throw new IOException("Missing SQLite schema resource: " + RESOURCE);
            }

            script = new String(input.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n");
        }

        Map<String, Definition> definitions = new LinkedHashMap<>();
        var matcher = DECLARATION.matcher(script);

        while (matcher.find()) {
            String type;
            String name;

            if (matcher.group(1) != null) {
                type = "table";
                name = matcher.group(1);
            } else if (matcher.group(2) != null) {
                type = "index";
                name = matcher.group(2);
            } else {
                type = "trigger";
                name = matcher.group(3);
            }

            if (definitions.put(name, new Definition(type, canonical(matcher.group()))) != null) {
                throw new IOException("Duplicate bundled SQLite chunk-identity declaration: " + name);
            }
        }

        if (!definitions.keySet().equals(REQUIRED_OBJECTS)) {
            throw new IOException("Incomplete bundled SQLite chunk-identity declarations in " + RESOURCE);
        }

        return new SqliteChunkIdentitySchema(script, Map.copyOf(definitions));
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
                                "Missing or incompatible SQLite chunk-identity schema object: " + object.getKey());
                    }
                }
            }
        }

        try (var statement = connection.createStatement();
                var ignored = statement.executeQuery("""
                    SELECT file_id, extraction_version, chunking_version, tokenizer_algorithm,
                           vocabulary_sha256, vocabulary_size, max_input_tokens, special_token_count,
                           max_tokens, overlap_tokens, fingerprint FROM main.file_chunking_profiles LIMIT 0
                    """)) {}

        try (var statement = connection.createStatement();
                var ignored = statement.executeQuery(
                        "SELECT chunk_id, file_id, stable_id FROM main.chunk_identities LIMIT 0")) {}
    }

    private static String canonical(String sql) {
        if (sql == null) {
            return "";
        }

        String result = sql.replace("\r\n", "\n").strip();
        // sqlite_schema omits the final terminator; preserve internal whitespace and string literals.
        return result.endsWith(";") ? result.substring(0, result.length() - 1).stripTrailing() : result;
    }

    private record Definition(String type, String sql) {}
}
