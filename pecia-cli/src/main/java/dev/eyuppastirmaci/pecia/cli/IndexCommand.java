package dev.eyuppastirmaci.pecia.cli;

import dev.eyuppastirmaci.pecia.config.PeciaConfigFile;
import dev.eyuppastirmaci.pecia.config.PeciaConfigLoader.LoadedConfig;
import dev.eyuppastirmaci.pecia.index.FileWalker;
import dev.eyuppastirmaci.pecia.index.IndexException;
import dev.eyuppastirmaci.pecia.index.IndexOptions;
import dev.eyuppastirmaci.pecia.index.IndexPreview;
import dev.eyuppastirmaci.pecia.index.IndexResult;
import dev.eyuppastirmaci.pecia.index.IndexService;
import dev.eyuppastirmaci.pecia.index.WalkResult;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/** Builds a folder's lexical index or previews candidate discovery. */
@Command(
        name = "index",
        description = "Builds a local lexical index from a folder of text files.",
        mixinStandardHelpOptions = true)
public class IndexCommand implements Callable<Integer> {

    @Parameters(index = "0", defaultValue = ".", description = "Folder to index (default: current directory).")
    Path path;

    @Option(names = "--dry-run", description = "List candidate files without processing contents or writing an index.")
    boolean dryRun;

    // Null when neither form is given, so semantic.enabled in .pecia.toml applies.
    @Option(
            names = "--semantic",
            negatable = true,
            description =
                    "Enable or disable semantic indexing for this run, overriding semantic.enabled in .pecia.toml.")
    Boolean semantic;

    @CommandLine.Spec
    CommandLine.Model.CommandSpec spec;

    private final IndexService indexService;

    /** Creates a command using the supplied non-null indexing service. */
    public IndexCommand(IndexService indexService) {
        this.indexService = Objects.requireNonNull(indexService, "indexService");
    }

    /**
     * Indexes a folder or previews discovery without processing content. Configuration warnings are printed to stderr
     * before any other output and do not change the exit code. The semantic flags override {@code semantic.enabled} for
     * this run, and the preview reports the resolved choice.
     *
     * @return 0 on complete indexing/preview, 1 on invalid input, file issues, incomplete scan or
     *     storage failure
     */
    @Override
    public Integer call() {
        try {
            IndexOptions options = semantic == null ? IndexOptions.configured() : IndexOptions.semantic(semantic);

            if (!dryRun) {
                IndexResult result = indexService.index(path, options);
                WarningPrinter.print(spec.commandLine().getErr(), result.warnings());
                reportIndex(result);

                return result.status() == IndexResult.Status.COMPLETE ? 0 : 1;
            }

            IndexPreview preview = indexService.preview(path, options);
            WarningPrinter.print(spec.commandLine().getErr(), preview.warnings());
            WalkResult result = preview.walkResult();
            report(preview);

            for (WalkResult.Issue issue : result.issues()) {
                spec.commandLine().getErr().println("warning: " + issue.path() + ": " + issue.reason());
            }

            if (!result.complete()) {
                spec.commandLine()
                        .getErr()
                        .println("pecia index: incomplete scan; listed files are only partial results");

                return 1;
            }

            return 0;
        } catch (IndexException failure) {
            WarningPrinter.print(spec.commandLine().getErr(), failure.result().warnings());
            reportIndex(failure.result());

            for (WalkResult.Issue issue : failure.scanIssues()) {
                spec.commandLine().getErr().println("warning: " + issue.path() + ": " + issue.reason());
            }

            String detail =
                    failure.getCause() == null ? "" : ": " + failure.getCause().getMessage();
            spec.commandLine().getErr().println("pecia index: " + failure.getMessage() + detail);

            return 1;
        } catch (IOException | IllegalArgumentException failure) {
            spec.commandLine().getErr().println("pecia index: " + failure.getMessage());

            return 1;
        }
    }

    private void reportIndex(IndexResult result) {
        PrintWriter out = spec.commandLine().getOut();

        out.println("index: " + result.context().databasePath());
        out.println("candidates: " + result.candidateCount());
        out.println("indexed: " + result.indexedFiles());
        out.println("unchanged: " + result.unchangedFiles());
        out.println("deleted: " + result.deletedFiles());
        out.println("chunks: " + result.writtenChunks());
        out.println("rejected: " + result.rejectedFiles());
        out.println("failed: " + result.failedFiles());

        for (IndexResult.FileIssue issue : result.issues()) {
            spec.commandLine()
                    .getErr()
                    .println("warning: "
                            + FileWalker.portablePath(issue.sourcePath())
                            + ": "
                            + issue.reason()
                            + ": "
                            + issue.message());
        }
    }

    private void report(IndexPreview preview) {
        PrintWriter out = spec.commandLine().getOut();
        LoadedConfig loaded = preview.loadedConfig();
        List<Path> files = preview.walkResult().files();

        if (loaded.fromFile()) {
            out.println("config: " + loaded.root().resolve(PeciaConfigFile.FILE_NAME));
        } else {
            out.println("config: defaults (no " + PeciaConfigFile.FILE_NAME + " found)");
        }

        out.println("semantic: " + (preview.semanticEnabled() ? "enabled" : "disabled"));
        out.println(files.size() + " file(s) would be indexed:");

        for (Path file : files) {
            out.println("  " + FileWalker.portablePath(file));
        }
    }
}
