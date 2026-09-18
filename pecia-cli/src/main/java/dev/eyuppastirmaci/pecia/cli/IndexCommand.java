package dev.eyuppastirmaci.pecia.cli;

import dev.eyuppastirmaci.pecia.config.PeciaConfigFile;
import dev.eyuppastirmaci.pecia.config.PeciaConfigLoader.LoadedConfig;
import dev.eyuppastirmaci.pecia.index.FileWalker;
import dev.eyuppastirmaci.pecia.index.IndexPreview;
import dev.eyuppastirmaci.pecia.index.IndexService;
import dev.eyuppastirmaci.pecia.index.WalkResult;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
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

    private final IndexService indexService;

    public IndexCommand(IndexService indexService) {
        this.indexService = Objects.requireNonNull(indexService, "indexService");
    }

    /**
     * Runs the index command; only --dry-run is implemented so far.
     *
     * @return 0 on a complete dry run, 1 on invalid input, incomplete scan, or unimplemented indexing
     */
    @Override
    public Integer call() {
        if (!dryRun) {
            spec.commandLine().getErr().println("pecia index: only --dry-run is implemented yet");

            return 1;
        }

        try {
            IndexPreview preview = indexService.preview(path);
            WalkResult result = preview.walkResult();
            report(preview.loadedConfig(), result.files());

            for (WalkResult.Issue issue : result.issues()) {
                spec.commandLine().getErr().println("warning: " + issue.path() + ": " + issue.reason());
            }

            if (!result.complete()) {
                spec.commandLine().getErr().println("pecia index: incomplete scan; listed files are only partial results");

                return 1;
            }

            return 0;
        } catch (IOException | IllegalArgumentException failure) {
            spec.commandLine().getErr().println("pecia index: " + failure.getMessage());

            return 1;
        }
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
            out.println("  " + FileWalker.portablePath(file));
        }
    }
}
