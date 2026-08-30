package dev.eyuppastirmaci.pecia.cli;

import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

import java.nio.file.Path;
import java.util.concurrent.Callable;

@Command(
        name = "index",
        description = "Indexes a folder: walks, chunks, embeds, and stores changed files.",
        mixinStandardHelpOptions = true
)
public class IndexCommand implements Callable<Integer> {

    @Parameters(index = "0", defaultValue = ".", description = "Folder to index (default: current directory).")
    Path path;

    /**
     * Indexes the given folder; not implemented yet.
     *
     * @return 1 until the command is implemented
     */
    @Override
    public Integer call() {
        System.err.println("pecia index: not implemented yet (path: " + path.toAbsolutePath().normalize() + ")");
        return 1;
    }
}
