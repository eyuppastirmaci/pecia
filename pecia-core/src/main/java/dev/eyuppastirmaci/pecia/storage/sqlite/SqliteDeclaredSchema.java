package dev.eyuppastirmaci.pecia.storage.sqlite;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The tables, indexes, and triggers one bundled migration declares, compared exactly with a stored index's
 * definitions.
 */
final class SqliteDeclaredSchema {

    // Extract declarations only from the fixed resource layout; trigger bodies contain semicolons.
    private static final Pattern DECLARATION = Pattern.compile("(?ms)^CREATE TABLE ([a-z_]+) \\(.*?^\\);"
            + "|^CREATE (?:UNIQUE )?INDEX ([a-z_]+) ON [^\\r\\n]+;"
            + "|^CREATE TRIGGER ([a-z_]+)\\b.*?^END;");

    private final String script;
    private final String subject;
    private final Map<String, Definition> definitions;

    private SqliteDeclaredSchema(String script, String subject, Map<String, Definition> definitions) {
        this.script = script;
        this.subject = subject;
        this.definitions = definitions;
    }

    /**
     * Reads a migration resource and requires it to declare exactly the given objects.
     *
     * @param subject what the migration adds, as used in failure messages, such as {@code chunk-identity}
     */
    static SqliteDeclaredSchema load(String resource, Set<String> requiredObjects, String subject) throws IOException {
        String script;

        try (var input = SqliteDeclaredSchema.class.getResourceAsStream(resource)) {
            if (input == null) {
                throw new IOException("Missing SQLite schema resource: " + resource);
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
                throw new IOException("Duplicate bundled SQLite " + subject + " declaration: " + name);
            }
        }

        if (!definitions.keySet().equals(requiredObjects)) {
            throw new IOException("Incomplete bundled SQLite " + subject + " declarations in " + resource);
        }

        return new SqliteDeclaredSchema(script, subject, Map.copyOf(definitions));
    }

    String script() {
        return script;
    }

    /** Requires every declared object to exist with the same type and definition. */
    void validate(Connection connection) throws SQLException {
        try (var query = connection.prepareStatement("SELECT type, sql FROM main.sqlite_schema WHERE name = ?")) {
            for (var object : definitions.entrySet()) {
                query.setString(1, object.getKey());

                try (var result = query.executeQuery()) {
                    if (!result.next()
                            || !object.getValue().type().equals(result.getString(1))
                            || !object.getValue().sql().equals(canonical(result.getString(2)))) {
                        throw new SQLException(
                                "Missing or incompatible SQLite " + subject + " schema object: " + object.getKey());
                    }
                }
            }
        }
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
