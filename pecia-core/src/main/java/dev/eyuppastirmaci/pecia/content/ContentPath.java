package dev.eyuppastirmaci.pecia.content;

import java.nio.file.Path;
import java.util.StringJoiner;

/** Validates and encodes source paths relative to a project root. */
public final class ContentPath {

    private ContentPath() {}

    /**
     * Validates a normalized source path within its project root.
     *
     * @param path the project-relative source path
     * @return the validated path
     * @throws NullPointerException if path is null
     * @throws IllegalArgumentException if path is empty, rooted, unnormalized, or escapes the root
     */
    public static Path requireProjectRelative(Path path) {
        if (path.toString().isBlank() || path.getFileName() == null) {
            throw new IllegalArgumentException("sourcePath must identify a file");
        }

        if (path.isAbsolute() || path.getRoot() != null) {
            throw new IllegalArgumentException("sourcePath must be relative to the project root: " + path);
        }

        Path normalized = path.normalize();

        if (!normalized.equals(path) || normalized.startsWith("..")) {
            throw new IllegalArgumentException(
                    "sourcePath must be normalized and stay inside the project root: " + path);
        }

        return path;
    }

    /**
     * Encodes a project-relative path using slash-separated name elements.
     *
     * <p>Preserves each element's case and Unicode spelling. Backslashes within a name element are
     * preserved on hosts where they are literal filename characters.
     *
     * @param path the normalized project-relative path
     * @return the portable spelling of the path
     * @throws IllegalArgumentException if the path violates the core path contract
     * @throws NullPointerException if path is null
     */
    public static String encode(Path path) {
        requireProjectRelative(path);
        StringJoiner names = new StringJoiner("/");

        for (Path name : path) {
            names.add(name.toString());
        }

        return names.toString();
    }
}
