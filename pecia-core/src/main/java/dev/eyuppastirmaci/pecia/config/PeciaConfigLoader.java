package dev.eyuppastirmaci.pecia.config;

import dev.eyuppastirmaci.pecia.diagnostic.Warning;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/** Loads the nearest project configuration, falling back to defaults when no file exists. */
public final class PeciaConfigLoader {

    private final PeciaConfigParser parser;

    /** Creates a loader using the supplied non-null parser. */
    public PeciaConfigLoader(PeciaConfigParser parser) {
        this.parser = Objects.requireNonNull(parser, "parser");
    }

    /**
     * Finds and parses the nearest .pecia.toml, searching from the start directory upward.
     *
     * @param startDir directory the search starts in
     * @return the config, its directory, and warnings about keys the file uses that Pecia does not recognize;
     *     without a file, defaults rooted at the nearest Git root or start directory
     * @throws NullPointerException if startDir is null
     * @throws IOException if a found config file cannot be read
     * @throws IllegalArgumentException if a found config file is malformed or not valid UTF-8
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
                PeciaConfigParser.Parsed parsed = parser.parse(readUtf8(file), file);

                return new LoadedConfig(parsed.config(), dir, true, parsed.warnings());
            }
        }

        return new LoadedConfig(PeciaConfig.defaults(), gitRoot == null ? start : gitRoot, false);
    }

    private static String readUtf8(Path file) throws IOException {
        byte[] bytes = Files.readAllBytes(file);

        // A strict decoder reports malformed bytes; they are a malformed file, not a read failure.
        try {
            return StandardCharsets.UTF_8
                    .newDecoder()
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException e) {
            throw new IllegalArgumentException("Invalid .pecia.toml: the file is not valid UTF-8", e);
        }
    }

    /**
     * Configuration with its project root, whether it came from a file rather than defaults, and the file's warnings.
     *
     * @param warnings warnings about the configuration file in source order; empty when defaults apply
     */
    public record LoadedConfig(PeciaConfig config, Path root, boolean fromFile, List<Warning> warnings) {
        public LoadedConfig {
            Objects.requireNonNull(config, "config");
            Objects.requireNonNull(root, "root");
            warnings = List.copyOf(warnings);
        }

        /** Creates a loaded configuration without warnings. */
        public LoadedConfig(PeciaConfig config, Path root, boolean fromFile) {
            this(config, root, fromFile, List.of());
        }
    }
}
