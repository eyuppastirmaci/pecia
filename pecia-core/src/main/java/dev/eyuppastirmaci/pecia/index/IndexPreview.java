package dev.eyuppastirmaci.pecia.index;

import dev.eyuppastirmaci.pecia.config.PeciaConfigLoader.LoadedConfig;
import dev.eyuppastirmaci.pecia.diagnostic.Warning;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * Discovery-only result containing the absolute target, project configuration, and scan outcome.
 */
public record IndexPreview(Path target, LoadedConfig loadedConfig, WalkResult walkResult) {
    public IndexPreview {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(loadedConfig, "loadedConfig");
        Objects.requireNonNull(walkResult, "walkResult");
    }

    /** Returns the configuration warnings found while resolving the project, in source order. */
    public List<Warning> warnings() {
        return loadedConfig.warnings();
    }
}
