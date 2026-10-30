package dev.eyuppastirmaci.pecia.cli;

import dev.eyuppastirmaci.pecia.config.PeciaConfigFile;
import dev.eyuppastirmaci.pecia.config.PeciaConfigLoader.LoadedConfig;
import dev.eyuppastirmaci.pecia.index.Cancellation;
import dev.eyuppastirmaci.pecia.index.FileWalker;
import dev.eyuppastirmaci.pecia.index.IndexException;
import dev.eyuppastirmaci.pecia.index.IndexOptions;
import dev.eyuppastirmaci.pecia.index.IndexPreview;
import dev.eyuppastirmaci.pecia.index.IndexResult;
import dev.eyuppastirmaci.pecia.index.IndexService;
import dev.eyuppastirmaci.pecia.index.StorageFailure;
import dev.eyuppastirmaci.pecia.index.WalkResult;
import dev.eyuppastirmaci.pecia.semantic.EmbeddingException;
import dev.eyuppastirmaci.pecia.semantic.ModelException;
import dev.eyuppastirmaci.pecia.semantic.ModelOutcome;
import dev.eyuppastirmaci.pecia.semantic.SemanticModel;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/** Builds a folder's index, or shows what indexing would change without writing anything. */
@Command(
        name = "index",
        description = "Builds a local index from a folder of text files, with embeddings when semantic indexing is on.",
        mixinStandardHelpOptions = true)
public class IndexCommand implements Callable<Integer> {

    @Parameters(index = "0", defaultValue = ".", description = "Folder to index (default: current directory).")
    Path path;

    @Option(
            names = "--dry-run",
            description = "Show what indexing would change, with chunk and embedding counts, without writing anything.")
    boolean dryRun;

    // Null when neither form is given, so semantic.enabled in .pecia.toml applies.
    @Option(
            names = "--semantic",
            negatable = true,
            description =
                    "Enable or disable semantic indexing for this run, overriding semantic.enabled in .pecia.toml.")
    Boolean semantic;

    @Option(
            names = "--json",
            description = "Print the outcome as one JSON document on stdout; warnings and issues stay on stderr.")
    boolean json;

    // The shell's exit status for a process stopped by SIGINT: 128 plus the signal number.
    static final int INTERRUPTED = 130;

    /** The JSON error reason when the folder or its configuration cannot be read. */
    static final String IO_ERROR = "IO_ERROR";

    @CommandLine.Spec
    CommandLine.Model.CommandSpec spec;

    // How long Ctrl+C waits for the run to stop and print its summary before the JVM exits anyway.
    private static final Duration STOP_TIMEOUT = Duration.ofSeconds(10);

    private final IndexService indexService;
    private final InterruptHandlers interrupts;

    /** Creates a command using the supplied non-null indexing service. */
    public IndexCommand(IndexService indexService) {
        this(indexService, InterruptHandlers.shutdownHooks());
    }

    IndexCommand(IndexService indexService, InterruptHandlers interrupts) {
        this.indexService = Objects.requireNonNull(indexService, "indexService");
        this.interrupts = Objects.requireNonNull(interrupts, "interrupts");
    }

    /**
     * Indexes a folder or shows what indexing would change without writing. Configuration warnings are printed to stderr
     * before any other output and do not change the exit code. The semantic flags override {@code semantic.enabled} for
     * this run, and the preview reports the resolved choice. A semantic run announces a model download on stderr when
     * one starts and reports the model, the embeddings of an earlier profile it discarded, the chunks embedded with
     * changed files, and the stored chunks whose missing embeddings it made in its summary. Every summary also counts
     * the indexed files whose unchanged content was chunked again for new settings. Ctrl+C stops the run before its
     * next file, batch, or page and prints what it finished; the files it indexed stay, and the next run continues.
     * With {@code --json}, a run or dry run prints one JSON document on stdout in place of its summary, whatever its
     * outcome, and stderr and the exit code stay the same.
     *
     * @return 0 on complete indexing/preview, 1 on invalid input, file or scan issues, an unavailable model or
     *     embedder, an embedding failure, or storage failure, 130 when Ctrl+C stopped the run
     */
    @Override
    public Integer call() {
        if (dryRun) {
            return execute(null);
        }

        Cancellation cancellation = new Cancellation();
        CountDownLatch reported = new CountDownLatch(1);

        InterruptHandlers.Registration hook = interrupts.register(() -> stop(cancellation, reported));

        try {
            return execute(cancellation);
        } finally {
            hook.close();
            // Picocli's writers flush every line, so the summary is out once the hook is released.
            reported.countDown();
        }
    }

    /** Cancels the run and waits until its summary is printed, since the JVM exits as soon as this returns. */
    private static void stop(Cancellation cancellation, CountDownLatch reported) {
        cancellation.cancel();

        try {
            reported.await(STOP_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    /** Indexes or previews; {@code cancellation} is null for a preview. */
    private Integer execute(Cancellation cancellation) {
        try {
            IndexOptions options = semantic == null ? IndexOptions.configured() : IndexOptions.semantic(semantic);
            options = options.withDownloadListener(this::announceDownload);

            if (!dryRun) {
                IndexResult result = indexService.index(path, options.withCancellation(cancellation));
                WarningPrinter.print(spec.commandLine().getErr(), result.warnings());
                reportIndex(result);
                printJson(IndexJson.run(result, null));

                if (result.status() == IndexResult.Status.CANCELLED) {
                    spec.commandLine()
                            .getErr()
                            .println("pecia index: cancelled; the files indexed so far are kept, and the next run"
                                    + " continues from there");

                    return INTERRUPTED;
                }

                if (result.status() == IndexResult.Status.PARTIAL) {
                    reportPartial(result);
                }

                return result.status() == IndexResult.Status.COMPLETE ? 0 : 1;
            }

            IndexPreview preview = indexService.preview(path, options);
            WarningPrinter.print(spec.commandLine().getErr(), preview.warnings());
            WalkResult result = preview.walkResult();

            if (!json) {
                report(preview);
            }

            for (WalkResult.Issue issue : result.issues()) {
                reportScanIssue(preview.target(), issue);
            }

            IndexPreview.Changes changes = preview.changes();

            if (changes.index() == IndexPreview.IndexState.UNREADABLE) {
                // The run would fail the same way, so the dry run says so as index does.
                spec.commandLine()
                        .getErr()
                        .println("pecia index: " + IndexResult.Status.FAILED + ": " + changes.storageFailure() + ": "
                                + changes.storageMessage());
                spec.commandLine().getErr().println(storageAdvice(changes.storageFailure(), preview.databasePath()));
                printJson(IndexJson.preview(
                        preview, IndexJson.error(changes.storageFailure().name(), changes.storageMessage())));

                return 1;
            }

            printJson(IndexJson.preview(preview, null));

            if (!result.complete()) {
                spec.commandLine()
                        .getErr()
                        .println("pecia index: incomplete scan; listed files are only partial results");

                return 1;
            }

            return 0;
        } catch (IndexException failure) {
            WarningPrinter.print(spec.commandLine().getErr(), failure.result().warnings());
            printJson(IndexJson.run(failure.result(), reportFailure(failure)));

            return 1;
        } catch (IOException | IllegalArgumentException failure) {
            spec.commandLine().getErr().println("pecia index: " + failure.getMessage());
            String reason = failure instanceof IOException ? IO_ERROR : QueryCommand.INVALID_INPUT;
            Json.JsonObject error = IndexJson.error(reason, failure.getMessage());
            printJson(dryRun ? IndexJson.previewFailure(error) : IndexJson.failure(error));

            return 1;
        }
    }

    /**
     * Prints a failed run's summary, if it wrote anything, and why it failed; returns the reason and message as a JSON
     * error.
     */
    private Json.JsonObject reportFailure(IndexException failure) {
        IndexResult.Status status = failure.result().status();

        if (status == IndexResult.Status.MODEL_UNAVAILABLE && failure.getCause() instanceof ModelException model) {
            return reportSemanticFailure(status, model.reason(), model.getMessage());
        }

        if (status == IndexResult.Status.EMBEDDER_UNAVAILABLE
                && failure.getCause() instanceof EmbeddingException embedding) {
            return reportSemanticFailure(status, embedding.reason(), embedding.getMessage());
        }

        if (status == IndexResult.Status.EMBEDDING_FAILED
                && failure.getCause() instanceof EmbeddingException embedding) {
            // Earlier files stay committed, so the summary shows what this run wrote before it stopped.
            reportIndex(failure.result());

            return reportSemanticFailure(status, embedding.reason(), embedding.getMessage());
        }

        reportIndex(failure.result());

        if (failure.storageFailure().isPresent() && failure.getCause() != null) {
            StorageFailure reason = failure.storageFailure().orElseThrow();
            String message = failure.getCause().getMessage();

            spec.commandLine().getErr().println("pecia index: " + status + ": " + reason + ": " + message);
            spec.commandLine()
                    .getErr()
                    .println(storageAdvice(reason, failure.result().context().databasePath()));

            return IndexJson.error(reason.name(), message);
        }

        String detail =
                failure.getCause() == null ? "" : ": " + failure.getCause().getMessage();
        spec.commandLine().getErr().println("pecia index: " + failure.getMessage() + detail);

        return IndexJson.error(status.name(), failure.getMessage() + detail);
    }

    /** Prints a JSON document on stdout when {@code --json} asked for one. */
    private void printJson(Json.JsonObject document) {
        if (json) {
            spec.commandLine().getOut().println(Json.write(document));
        }
    }

    /** Prints the run's summary on stdout, unless JSON replaces it, and its issues on stderr. */
    private void reportIndex(IndexResult result) {
        if (!json) {
            printSummary(result);
        }

        for (WalkResult.Issue issue : result.scanIssues()) {
            reportScanIssue(result.context().projectRoot(), issue);
        }

        for (IndexResult.SemanticIssue issue : result.semanticIssues()) {
            spec.commandLine()
                    .getErr()
                    .println("warning: "
                            + FileWalker.portablePath(issue.sourcePath())
                            + ": "
                            + issue.reason()
                            + ": "
                            + issue.message()
                            + " (indexed without embeddings)");
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

    private void printSummary(IndexResult result) {
        PrintWriter out = spec.commandLine().getOut();

        out.println("index: " + result.context().databasePath());
        out.println("candidates: " + result.candidateCount());
        out.println("indexed: " + result.indexedFiles());
        out.println("rechunked: " + result.rechunkedFiles());
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
            out.println("discarded: " + result.discardedEmbeddings());
            out.println("embedded: " + result.embeddedChunks());
            out.println("backfilled: " + result.backfilledChunks());
        }
    }

    /** Prints a path discovery could not read, relative to {@code base} when it lies below it. */
    private void reportScanIssue(Path base, WalkResult.Issue issue) {
        Path path = issue.path();
        String shown = path.startsWith(base) ? FileWalker.portablePath(base.relativize(path)) : path.toString();
        // Without its ignore rules, a folder's files could include some the user meant to leave out.
        String effect = path.getFileName().toString().equals(".gitignore") ? "its folder was skipped" : "not scanned";

        spec.commandLine()
                .getErr()
                .println("warning: " + shown + ": " + issue.cause() + ": " + issue.reason() + " (" + effect + ")");
    }

    /** Summarizes what a partial run left out, after the issues themselves. */
    private void reportPartial(IndexResult result) {
        List<String> parts = new ArrayList<>();

        if (!result.issues().isEmpty()) {
            parts.add(count(result.issues().size(), "file") + " could not be read or were rejected");
        }

        if (!result.semanticIssues().isEmpty()) {
            parts.add(count(result.semanticIssues().size(), "file") + " could not be embedded");
        }

        if (!result.scanIssues().isEmpty()) {
            parts.add(count(result.scanIssues().size(), "path") + " could not be scanned");
        }

        boolean keptDeletions =
                !result.issues().isEmpty() || !result.scanIssues().isEmpty();

        spec.commandLine()
                .getErr()
                .println("pecia index: PARTIAL: "
                        + String.join("; ", parts)
                        + "; every other file was indexed"
                        + (keptDeletions ? ", and no file was removed from the index" : ""));
    }

    private static String count(int count, String noun) {
        return count + " " + noun + (count == 1 ? "" : "s");
    }

    /** Says what to do about a storage failure; files committed before it stay in the index. */
    static String storageAdvice(StorageFailure reason, Path database) {
        return switch (reason) {
            case DISK_FULL -> "Free some disk space and run the command again; the files indexed so far are kept.";
            case BUSY ->
                "Another process is using the index, such as another 'pecia index'; run the command again when it"
                        + " has finished.";
            case READ_ONLY -> "Make " + database + " and its folder writable, then run the command again.";
            case IO_ERROR ->
                "Check that the disk is available and " + database.getParent() + " is writable, then run the"
                        + " command again.";
            case CORRUPT ->
                "The index is damaged; delete " + database + " and run the command again to rebuild it from your"
                        + " files.";
            case WRONG_PROJECT ->
                "The index belongs to another project folder; delete " + database + " to index this one.";
            case INCOMPATIBLE ->
                "The index was written by another version of Pecia; delete " + database
                        + " and run the command again to rebuild it.";
            case OTHER -> "Run the command again; the files indexed so far are kept.";
        };
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

    private Json.JsonObject reportSemanticFailure(IndexResult.Status status, Enum<?> reason, String message) {
        PrintWriter err = spec.commandLine().getErr();

        err.println("pecia index: " + status + ": " + reason + ": " + message);
        err.println("Run with --no-semantic to build only the lexical index.");

        return IndexJson.error(reason.name(), message);
    }

    private static String megabytes(long bytes) {
        return String.format(Locale.ROOT, "%.1f MB", bytes / 1_000_000.0);
    }

    /** Prints what the run would do: a summary, then the files it would change, by kind. */
    private void report(IndexPreview preview) {
        PrintWriter out = spec.commandLine().getOut();
        LoadedConfig loaded = preview.loadedConfig();
        IndexPreview.Changes changes = preview.changes();

        if (loaded.fromFile()) {
            out.println("config: " + loaded.root().resolve(PeciaConfigFile.FILE_NAME));
        } else {
            out.println("config: defaults (no " + PeciaConfigFile.FILE_NAME + " found)");
        }

        out.println("index: " + preview.databasePath() + " (" + describe(changes.index()) + ")");
        out.println("semantic: " + (preview.semanticEnabled() ? "enabled" : "disabled"));

        if (changes.index() == IndexPreview.IndexState.UNREADABLE) {
            return;
        }

        out.println("files: "
                + changes.newFiles().size() + " new, "
                + changes.changedFiles().size() + " changed, "
                + changes.rechunkedFiles().size() + " rechunked, "
                + changes.unchangedFiles().size() + " unchanged, "
                + changes.rejectedFiles().size() + " rejected, "
                + changes.removedFiles().size() + " removed");
        out.println("chunks: "
                + format(changes.chunksToWrite()) + " to write, "
                + format(changes.chunksKept()) + " kept, "
                + format(changes.chunksRemoved()) + " removed");

        IndexPreview.SemanticChanges semantic = changes.semantic();

        if (semantic != null) {
            out.println("model: " + semantic.model() + " " + describe(semantic));
            out.println("embeddings: "
                    + format(semantic.chunksToEmbed()) + " to make, "
                    + semantic.embeddingsToDiscard() + " to discard");
        }

        List<String> groups = new ArrayList<>();
        addGroup(groups, "new", planned(changes.newFiles()));
        addGroup(groups, "changed", planned(changes.changedFiles()));
        addGroup(groups, "rechunked", planned(changes.rechunkedFiles()));
        addGroup(
                groups,
                "rejected",
                changes.rejectedFiles().stream()
                        .map(issue -> FileWalker.portablePath(issue.sourcePath()) + ": " + issue.reason() + ": "
                                + issue.message())
                        .toList());
        addGroup(groups, "removed", planned(changes.removedFiles()));

        if (!groups.isEmpty()) {
            out.println();
            groups.forEach(out::println);
        }
    }

    /** Adds a heading and its indented entries, unless there are no entries. */
    private static void addGroup(List<String> lines, String heading, List<String> entries) {
        if (entries.isEmpty()) {
            return;
        }

        lines.add(heading + ":");

        for (String entry : entries) {
            lines.add("  " + entry);
        }
    }

    /** Describes each file by its path and chunk count. */
    private static List<String> planned(List<IndexPreview.PlannedFile> files) {
        return files.stream()
                .map(file -> FileWalker.portablePath(file.sourcePath()) + " (" + format(file.chunks()) + " chunk"
                        + (file.chunks().value() == 1 ? "" : "s") + ")")
                .toList();
    }

    /** Formats a count, marking an estimate with a tilde. */
    private static String format(IndexPreview.Count count) {
        return (count.estimated() ? "~" : "") + count.value();
    }

    static String describe(IndexPreview.IndexState state) {
        return switch (state) {
            case NONE -> "none yet; would be created";
            case CURRENT -> "current";
            case UPGRADE -> "older format; would be upgraded";
            case UNREADABLE -> "cannot be read";
        };
    }

    static String describe(IndexPreview.SemanticChanges semantic) {
        return switch (semantic.modelState()) {
            case CACHED -> "cached";
            case DOWNLOAD -> "would be downloaded (" + megabytes(semantic.downloadBytes()) + ")";
            case UNAVAILABLE -> "cache cannot be read";
        };
    }
}
