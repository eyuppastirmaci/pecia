package dev.eyuppastirmaci.pecia.storage.sqlite;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** The bundled V2 declarations are also the contract for accepting an existing search index. */
final class SqliteFtsSchema {
    private static final String RESOURCE = "/db/migration/V2__add_chunk_fts.sql";
    private static final Set<String> REQUIRED_OBJECTS = Set.of(
            "chunks_fts",
            "chunks_fts_insert",
            "chunks_fts_update",
            "chunks_fts_delete",
            "chunk_headings_fts_insert",
            "chunk_headings_fts_update",
            "chunk_headings_fts_delete",
            "files_fts_path_update");
    // Only extracts declarations from this fixed resource layout, never splits executable SQL at
    // semicolons.
    private static final Pattern DECLARATION = Pattern.compile(
            "(?ms)^CREATE VIRTUAL TABLE (chunks_fts) USING fts5\\(.*?^\\);|^CREATE TRIGGER" + " ([a-z_]+)\\b.*?^END;");

    private final String script;
    private final Map<String, Definition> definitions;

    private SqliteFtsSchema(String script, Map<String, Definition> definitions) {
        this.script = script;
        this.definitions = definitions;
    }

    static SqliteFtsSchema load() throws IOException {
        String script;
        try (var input = SqliteFtsSchema.class.getResourceAsStream(RESOURCE)) {
            if (input == null) {
                throw new IOException("Missing SQLite schema resource: " + RESOURCE);
            }
            // SQL resources may have been checked out with either LF or CRLF on different hosts.
            script = new String(input.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n");
        }

        Map<String, Definition> definitions = new LinkedHashMap<>();
        var matcher = DECLARATION.matcher(script);
        while (matcher.find()) {
            boolean table = matcher.group(1) != null;
            String name = table ? matcher.group(1) : matcher.group(2);
            if (definitions.put(name, new Definition(table ? "table" : "trigger", canonical(matcher.group())))
                    != null) {
                throw new IOException("Duplicate bundled SQLite FTS declaration: " + name);
            }
        }
        if (!definitions.keySet().equals(REQUIRED_OBJECTS)) {
            throw new IOException("Incomplete bundled SQLite FTS declarations in " + RESOURCE);
        }
        return new SqliteFtsSchema(script, Map.copyOf(definitions));
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
                        throw new SQLException("Missing or incompatible SQLite FTS schema object: " + object.getKey());
                    }
                }
            }
        }

        // Preparing the projection opens the virtual table and checks its columns, without scanning
        // chunk content.
        try (var statement = connection.createStatement()) {
            statement.execute("SELECT rowid, content, headings, source_path FROM main.chunks_fts LIMIT 0");
        }
    }

    private static String canonical(String sql) {
        if (sql == null) {
            return "";
        }
        String result = sql.replace("\r\n", "\n").strip();
        // sqlite_schema omits the terminator; preserve all internal whitespace and string literal
        // values.
        return result.endsWith(";") ? result.substring(0, result.length() - 1).stripTrailing() : result;
    }

    private record Definition(String type, String sql) {}
}
