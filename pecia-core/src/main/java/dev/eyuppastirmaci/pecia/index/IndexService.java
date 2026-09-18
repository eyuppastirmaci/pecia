package dev.eyuppastirmaci.pecia.index;

import dev.eyuppastirmaci.pecia.config.PeciaConfigLoader;
import dev.eyuppastirmaci.pecia.config.PeciaConfigLoader.LoadedConfig;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;

public final class IndexService {

    private final PeciaConfigLoader configLoader;

    public IndexService(PeciaConfigLoader configLoader) {
        this.configLoader = Objects.requireNonNull(configLoader, "configLoader");
    }

    /**
     * Discovers candidate files using project configuration without processing their contents or writing index data.
     *
     * @param target directory whose descendants are scanned
     * @return the normalized absolute target, loaded configuration, and target-relative files with recoverable scan issues
     * @throws NullPointerException if target is null
     * @throws IOException if configuration cannot be read, the target is invalid, or traversal cannot start
     * @throws IllegalArgumentException if configuration or glob patterns are invalid
     */
    public IndexPreview preview(Path target) throws IOException {
        Path normalizedTarget = target.toAbsolutePath().normalize();
        LoadedConfig loaded = configLoader.load(normalizedTarget);
        FileWalker walker = new FileWalker(new GlobFilter(loaded.config().include(), loaded.config().exclude()));
        WalkResult result = walker.scan(normalizedTarget, loaded.root());

        return new IndexPreview(normalizedTarget, loaded, result);
    }
}
