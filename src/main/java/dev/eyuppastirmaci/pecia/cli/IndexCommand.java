package dev.eyuppastirmaci.pecia.cli;

import dev.eyuppastirmaci.pecia.config.PeciaConfigFile;
import dev.eyuppastirmaci.pecia.config.PeciaConfigLoader;
import dev.eyuppastirmaci.pecia.config.PeciaConfigLoader.LoadedConfig;
import dev.eyuppastirmaci.pecia.index.FileWalker;
import dev.eyuppastirmaci.pecia.index.GlobFilter;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;

@Command(
        name = "index",
        description = "Indexes a folder: walks, chunks, embeds, and stores changed files.",
        mixinStandardHelpOptions = true
)
public class IndexCommand implements Callable<Integer> {

    @Parameters(index = "0", defaultValue = ".", description = "Folder to index (default: current directory).")
    Path path;

    @Option(names = "--dry-run", description = "Report what would be indexed without embedding anything.")
    boolean dryRun;

    @CommandLine.Spec
    CommandLine.Model.CommandSpec spec;

    private final PeciaConfigLoader configLoader;

    public IndexCommand(PeciaConfigLoader configLoader) {
        this.configLoader = configLoader;
    }

    /**
     * Runs the index command; only --dry-run is implemented so far.
     *
     * @return 0 on a successful dry run, 1 otherwise
     * @throws IOException if the config cannot be read or the folder cannot be walked
     */
    @Override
    public Integer call() throws IOException {
        Path target = path.toAbsolutePath().normalize();

        LoadedConfig loaded = configLoader.load(target);

        if (!dryRun) {
            spec.commandLine().getErr().println("pecia index: only --dry-run is implemented yet");
            return 1;
        }

        FileWalker walker = new FileWalker(new GlobFilter(loaded.config().include(), loaded.config().exclude()));

        List<Path> files = walker.walk(target);

        report(loaded, files);

        return 0;
    }

    private void report(LoadedConfig loaded, List<Path> files) {
        PrintWriter out = spec.commandLine().getOut();

        if (loaded.fromFile()) {
            out.println("config: " + loaded.root().resolve(PeciaConfigFile.FILE_NAME));
        } else {
            out.println("config: defaults (no " + PeciaConfigFile.FILE_NAME + " found)");
        }

        out.println(files.size() + " file(s) would be indexed:");

        for (Path file : files) {
            out.println("  " + file);
        }
    }
}
