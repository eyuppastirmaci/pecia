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
     * @return the parsed config and the directory it was found in, or defaults rooted at the start directory
     * @throws IOException if a found config file cannot be read
     * @throws IllegalArgumentException if a found config file is malformed
     */
    public LoadedConfig load(Path startDir) throws IOException {
        Path start = startDir.toAbsolutePath().normalize();

        // Walk up toward the filesystem root until a .pecia.toml is found.
        for (Path dir = start; dir != null; dir = dir.getParent()) {
            Path file = dir.resolve(PeciaConfigFile.FILE_NAME);

            if (Files.isRegularFile(file)) {
                PeciaConfig config = parser.parse(Files.readString(file));

                return new LoadedConfig(config, dir, true);
            }
        }

        return new LoadedConfig(PeciaConfig.defaults(), start, false);
    }

    public record LoadedConfig(PeciaConfig config, Path root, boolean fromFile) {
    }
}
