package dev.eyuppastirmaci.pecia.storage.sqlite;

import dev.eyuppastirmaci.pecia.content.ContentPath;
import java.nio.file.Path;
import java.util.StringJoiner;

/** Converts project-relative paths to and from the portable spelling stored in SQLite. */
public final class SqlitePath {
    private SqlitePath() {}

    /**
     * Encodes a project-relative path using slash-separated name elements.
     *
     * @param path the normalized project-relative path
     * @return the database path without changing case or Unicode
     * @throws IllegalArgumentException if the path violates the core path contract
     * @throws NullPointerException if path is null
     */
    public static String encode(Path path) {
        ContentPath.requireProjectRelative(path);
        StringJoiner names = new StringJoiner("/");

        for (Path name : path) {
            names.add(name.toString());
        }

        return names.toString();
    }

    /**
     * Decodes a stored path only when its exact spelling can round-trip on this host.
     *
     * @param value the stored slash-separated path
     * @return the validated project-relative path
     * @throws IllegalArgumentException if the stored path is invalid or cannot round-trip
     * @throws NullPointerException if value is null
     */
    public static Path decode(String value) {
        Path path = Path.of(value);

        if (!encode(path).equals(value)) {
            throw new IllegalArgumentException("Stored source path cannot round-trip on this host: " + value);
        }

        return path;
    }
}
