package dev.eyuppastirmaci.pecia.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class PeciaConfigFile {

    public static final String FILE_NAME = ".pecia.toml";

    /**
     * Returns the configuration written by pecia init.
     *
     * @return the default .pecia.toml content
     */
    public String defaultToml() {
        return """
                # Pecia configuration. Flags on the command line override these values.

                [index]
                include = ["**/*.md", "**/*.txt", "**/*.java", "**/*.kt", "**/*.py", "**/*.ts"]
                exclude = ["**/target/**", "**/node_modules/**"]   # .gitignore is always applied too

                [chunk]
                max_tokens = 256
                overlap_tokens = 32

                [embed]
                concurrency = 2

                [store]
                path = ".pecia/index.db"
                """;
    }

    /**
     * Writes the default config into the given directory unless one is already there.
     *
     * @param dir directory the config file is written into
     * @return true if the file was created, false if it already existed
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
