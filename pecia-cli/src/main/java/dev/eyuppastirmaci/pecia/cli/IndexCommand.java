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
import dev.eyuppastirmaci.pecia.semantic.EmbeddingException;
import dev.eyuppastirmaci.pecia.semantic.ModelException;
import dev.eyuppastirmaci.pecia.semantic.ModelOutcome;
import dev.eyuppastirmaci.pecia.semantic.SemanticModel;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
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
     * this run, and the preview reports the resolved choice. A semantic run announces a model download on stderr when
     * one starts and reports the model, the chunks embedded with changed files, and the stored chunks whose missing
     * embeddings it made in its summary.
     *
     * @return 0 on complete indexing/preview, 1 on invalid input, file issues, incomplete scan, an unavailable model or
     *     embedder, an embedding failure, or storage failure
     */
    @Override
    public Integer call() {
        try {
            IndexOptions options = semantic == null ? IndexOptions.configured() : IndexOptions.semantic(semantic);
            options = options.withDownloadListener(this::announceDownload);

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

            IndexResult.Status status = failure.result().status();

            if (status == IndexResult.Status.MODEL_UNAVAILABLE && failure.getCause() instanceof ModelException model) {
                reportSemanticFailure(status, model.reason(), model.getMessage());

                return 1;
            }

            if (status == IndexResult.Status.EMBEDDER_UNAVAILABLE
                    && failure.getCause() instanceof EmbeddingException embedding) {
                reportSemanticFailure(status, embedding.reason(), embedding.getMessage());

                return 1;
            }

            if (status == IndexResult.Status.EMBEDDING_FAILED
                    && failure.getCause() instanceof EmbeddingException embedding) {
                // Earlier files stay committed, so the summary shows what this run wrote before it stopped.
                reportIndex(failure.result());
                reportSemanticFailure(status, embedding.reason(), embedding.getMessage());

                return 1;
            }

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

        ModelOutcome model = result.semanticModel();

        if (model != null) {
            out.println("model: "
                    + model.model().name()
                    + (model.status() == ModelOutcome.Status.DOWNLOADED
                            ? " downloaded (" + megabytes(model.model().size()) + ")"
                            : " cached"));
            out.println("embedded: " + result.embeddedChunks());
            out.println("backfilled: " + result.backfilledChunks());
        }

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

    private void announceDownload(SemanticModel model, Path file) {
        spec.commandLine()
                .getErr()
                .println("Downloading "
                        + model.name()
                        + " ("
                        + megabytes(model.size())
                        + ") from "
                        + model.downloadUri().getHost()
                        + " into "
                        + file.getParent());
    }

    private void reportSemanticFailure(IndexResult.Status status, Enum<?> reason, String message) {
        PrintWriter err = spec.commandLine().getErr();

        err.println("pecia index: " + status + ": " + reason + ": " + message);
        err.println("Run with --no-semantic to build only the lexical index.");
    }

    private static String megabytes(long bytes) {
        return String.format(Locale.ROOT, "%.1f MB", bytes / 1_000_000.0);
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
