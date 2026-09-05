package dev.eyuppastirmaci.pecia.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class PeciaConfigLoader {

    private final PeciaConfigParser parser;

    public PeciaConfigLoader(PeciaConfigParser parser) {
        this.parser = parser;
    }

    /**
     * Finds and parses the nearest .pecia.toml, searching from the start directory upward.
     *
     * @param startDir directory the search starts in
     * @return the config and its directory; without config, defaults rooted at the nearest Git root or start directory
     * @throws NullPointerException if startDir is null
     * @throws IOException if a found config file cannot be read
     * @throws IllegalArgumentException if a found config file is malformed
     */
    public LoadedConfig load(Path startDir) throws IOException {
        Path start = startDir.toAbsolutePath().normalize();
        Path gitRoot = null;

        // Walk up toward the filesystem root until a .pecia.toml is found.
        for (Path dir = start; dir != null; dir = dir.getParent()) {

            if (gitRoot == null && Files.exists(dir.resolve(".git"))) {
                gitRoot = dir;
            }

            Path file = dir.resolve(PeciaConfigFile.FILE_NAME);

            if (Files.isRegularFile(file)) {
                PeciaConfig config = parser.parse(Files.readString(file));

                return new LoadedConfig(config, dir, true);
            }
        }

        return new LoadedConfig(PeciaConfig.defaults(), gitRoot == null ? start : gitRoot, false);
    }

    public record LoadedConfig(PeciaConfig config, Path root, boolean fromFile) {
    }
}
