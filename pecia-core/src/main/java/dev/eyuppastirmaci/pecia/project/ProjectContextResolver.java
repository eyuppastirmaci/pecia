package dev.eyuppastirmaci.pecia.project;

import dev.eyuppastirmaci.pecia.config.PeciaConfigLoader;
import dev.eyuppastirmaci.pecia.config.PeciaConfigLoader.LoadedConfig;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Objects;

/** Resolves shared index/query paths without creating directories, configuration or storage. */
public final class ProjectContextResolver {

    private final PeciaConfigLoader configLoader;

    /** Creates a resolver using the supplied non-null configuration loader. */
    public ProjectContextResolver(PeciaConfigLoader configLoader) {
        this.configLoader = Objects.requireNonNull(configLoader, "configLoader");
    }

    /**
     * Uses the existing nearest-config/Git-root rules for a directory target. Relative store paths
     * resolve against the loaded project root; absolute paths may be outside it. Existing store
     * ancestors are resolved to their real paths so aliases cannot hide the index from discovery.
     * Storage opening remains responsible for validating database ownership and schema.
     *
     * @throws IOException if the target is not a real directory, contains a symbolic link, or
     *     paths/config cannot be read
     * @throws IllegalArgumentException if configuration or a configured path is invalid
     * @throws NullPointerException if target is null
     */
    public ProjectContext resolve(Path target) throws IOException {
        Path normalized = target.toAbsolutePath().normalize();

        for (Path part = normalized; part != null; part = part.getParent()) {
            if (Files.isSymbolicLink(part)) {
                throw new IOException("Symbolic-link targets are not supported: " + part);
            }
        }

        if (!Files.readAttributes(normalized, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS)
                .isDirectory()) {
            throw new IOException("Target must be a directory: " + normalized);
        }

        LoadedConfig loaded = configLoader.load(normalized);
        Path database =
                loaded.root().resolve(Path.of(loaded.config().storePath())).normalize();
        Path existing = database;

        while (!Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) {
            existing = existing.getParent();
        }

        database = existing.toRealPath().resolve(existing.relativize(database));

        if (Files.isDirectory(database)) {
            throw new IOException("Database path must identify a file: " + database);
        }

        return new ProjectContext(normalized, loaded, database);
    }
}
