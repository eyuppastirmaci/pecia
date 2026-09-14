package dev.eyuppastirmaci.pecia.index;

import dev.eyuppastirmaci.pecia.config.PeciaConfigLoader.LoadedConfig;

import java.nio.file.Path;

public record IndexPreview(Path target, LoadedConfig loadedConfig, WalkResult walkResult) {
}
