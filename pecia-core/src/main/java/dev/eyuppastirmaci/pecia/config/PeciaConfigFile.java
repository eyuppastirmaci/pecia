package dev.eyuppastirmaci.pecia.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

public final class PeciaConfigFile {

    public static final String FILE_NAME = ".pecia.toml";

    /**
     * Returns the configuration written by pecia init.
     *
     * @return the default .pecia.toml content
     */
    public String defaultToml() {
        PeciaConfig defaults = PeciaConfig.defaults();

        return """
                # Pecia configuration. Flags on the command line override these values.
                # Globs are case-insensitive and relative to this file's directory.
                # Custom arrays replace defaults; an empty include accepts all candidates.

                [index]
                include = %s
                exclude = %s   # .gitignore is always applied too
                max_file_bytes = %d

                [chunk]
                max_tokens = %d
                overlap_tokens = %d

                [embed]
                concurrency = %d

                [store]
                path = %s
                """.formatted(tomlArray(defaults.include()), tomlArray(defaults.exclude()),
                defaults.maxFileBytes(), defaults.maxTokens(), defaults.overlapTokens(),
                defaults.embedConcurrency(), quote(defaults.storePath()));
    }

    private static String tomlArray(List<String> values) {

        return values.stream()
                     .map(PeciaConfigFile::quote)
                     .collect(Collectors.joining(", ", "[", "]"));
    }

    private static String quote(String value) {

        // Escape backslashes before quotes so the result remains a valid TOML basic string.
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    /**
     * Writes the default config into the given directory unless one is already there.
     *
     * @param dir directory the config file is written into
     * @return true if the file was created, false if it already existed
     * @throws NullPointerException if dir is null
     * @throws IOException if the file cannot be written
     */
    public boolean writeDefault(Path dir) throws IOException {
        Path file = dir.resolve(FILE_NAME);

        if (Files.exists(file)) {

            return false;
        }

        Files.writeString(file, defaultToml());

        return true;
    }
}
