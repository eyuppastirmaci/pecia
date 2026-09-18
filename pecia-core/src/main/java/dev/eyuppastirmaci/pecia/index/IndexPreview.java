package dev.eyuppastirmaci.pecia.index;

import dev.eyuppastirmaci.pecia.config.PeciaConfigLoader.LoadedConfig;

import java.nio.file.Path;
import java.util.Objects;

public record IndexPreview(Path target, LoadedConfig loadedConfig, WalkResult walkResult) {
    public IndexPreview {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(loadedConfig, "loadedConfig");
        Objects.requireNonNull(walkResult, "walkResult");
    }
}
