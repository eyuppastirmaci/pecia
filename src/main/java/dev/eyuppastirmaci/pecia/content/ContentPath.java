package dev.eyuppastirmaci.pecia.content;

import java.nio.file.Path;
import java.util.Objects;

final class ContentPath {

    private ContentPath() { }

    /* Validates that a source path is normalized, project-relative, and cannot escape its project root. */
    static Path requireProjectRelative(Path path) {
        Objects.requireNonNull(path, "sourcePath");

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
