package dev.eyuppastirmaci.pecia.content;

import java.nio.file.Path;

public final class ContentPath {

    private ContentPath() { }

    /**
     * Validates a normalized source path within its project root.
     *
     * @param path the project-relative source path
     * @return the validated path
     * @throws NullPointerException if path is null
     * @throws IllegalArgumentException if path is empty, absolute, unnormalized, or escapes the root
     */
    public static Path requireProjectRelative(Path path) {
        if (path.toString().isBlank() || path.getFileName() == null) {
            throw new IllegalArgumentException("sourcePath must identify a file");
        }

        if (path.isAbsolute()) {
            throw new IllegalArgumentException("sourcePath must be relative to the project root: " + path);
        }

        Path normalized = path.normalize();

        if (!normalized.equals(path) || normalized.startsWith("..")) {
            throw new IllegalArgumentException("sourcePath must be normalized and stay inside the project root: " + path);
        }

        return path;
    }
}
