package dev.eyuppastirmaci.pecia.cli;

import dev.eyuppastirmaci.pecia.config.PeciaConfigFile;
import picocli.CommandLine.Command;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.Callable;

@Command(
        name = "init",
        description = "Writes a .pecia.toml config file at the project root.",
        mixinStandardHelpOptions = true
)
public class InitCommand implements Callable<Integer> {

    private final PeciaConfigFile configFile;

    public InitCommand(PeciaConfigFile configFile) {
        this.configFile = configFile;
    }

    /**
     * Writes the default config into the current directory unless it already exists.
     *
     * @return 0 in both cases, since re-running init is not an error
     * @throws IOException if the config file cannot be written
     */
    @Override
    public Integer call() throws IOException {
        Path dir = Path.of("").toAbsolutePath();

        boolean created = configFile.writeDefault(dir);

        if (created) {
            System.out.println("Wrote " + dir.resolve(PeciaConfigFile.FILE_NAME));
        } else {
            System.out.println(PeciaConfigFile.FILE_NAME + " already exists, left unchanged.");
        }

        return 0;
    }
}
