package dev.eyuppastirmaci.pecia.project;

import dev.eyuppastirmaci.pecia.config.PeciaConfigLoader.LoadedConfig;
import dev.eyuppastirmaci.pecia.content.ContentPath;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Set;

/**
 * Shared index/query context with normalized absolute paths and a target inside the project root.
 */
public record ProjectContext(Path target, LoadedConfig loadedConfig, Path databasePath) {

    /**
     * Rejects relative or unnormalized paths, an out-of-project target, or a missing database name.
     */
    public ProjectContext {
        Objects.requireNonNull(loadedConfig, "loadedConfig");
        requireAbsoluteNormalized(target);
        requireAbsoluteNormalized(loadedConfig.root());
        requireAbsoluteNormalized(databasePath);

        if (!target.startsWith(loadedConfig.root())) {
            throw new IllegalArgumentException("Target must be inside project root: " + target);
        }

        if (databasePath.getFileName() == null) {
            throw new IllegalArgumentException("Database path must identify a file");
        }
    }

    public Path projectRoot() {
        return loadedConfig.root();
    }

    /** Resolves a normalized target-relative discovery candidate without reading its contents. */
    public Path absoluteSource(Path candidate) {
        ContentPath.requireProjectRelative(candidate);

        return target.resolve(candidate);
    }

    /** Converts a target-relative discovery candidate to the domain/storage project-relative path. */
    public Path sourcePath(Path candidate) {
        return ContentPath.requireProjectRelative(projectRoot().relativize(absoluteSource(candidate)));
    }

    /** Exact database and SQLite sidecar paths, independent of configured include patterns. */
    public Set<Path> storageFiles() {
        String name = databasePath.getFileName().toString();

        return Set.of(
                databasePath,
                databasePath.resolveSibling(name + "-wal"),
                databasePath.resolveSibling(name + "-shm"),
                databasePath.resolveSibling(name + "-journal"));
    }

    private static void requireAbsoluteNormalized(Path path) {
        if (!path.isAbsolute() || !path.equals(path.normalize())) {
            throw new IllegalArgumentException("Context paths must be normalized and absolute: " + path);
        }
    }
}
