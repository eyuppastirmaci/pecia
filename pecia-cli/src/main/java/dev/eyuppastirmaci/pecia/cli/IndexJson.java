package dev.eyuppastirmaci.pecia.cli;

import dev.eyuppastirmaci.pecia.config.PeciaConfigFile;
import dev.eyuppastirmaci.pecia.config.PeciaConfigLoader.LoadedConfig;
import dev.eyuppastirmaci.pecia.index.FileWalker;
import dev.eyuppastirmaci.pecia.index.IndexPreview;
import dev.eyuppastirmaci.pecia.index.IndexResult;
import dev.eyuppastirmaci.pecia.index.WalkResult;
import dev.eyuppastirmaci.pecia.semantic.ModelOutcome;
import dev.eyuppastirmaci.pecia.storage.model.EmbeddingCoverage;
import java.nio.file.Path;
import java.util.List;

/**
 * Builds the JSON document of an indexing run, its status, counts, issues, and embeddings, or of a dry run, what a run
 * would do; either says why it failed when it did.
 */
final class IndexJson {

    private IndexJson() {}

    /**
     * Describes a run that returned a result, complete or not.
     *
     * @param error why the run failed, from {@link #error}; null when it did not
     */
    static Json.JsonObject run(IndexResult result, Json.JsonObject error) {
        Path root = result.context().projectRoot();

        return document()
                .put("root", root.toString())
                .put("database", result.context().databasePath().toString())
                .put("config", config(result.context().loadedConfig()))
                .put("status", result.status().name())
                .put(
                        "files",
                        new Json.JsonObject()
                                .put("candidates", result.candidateCount())
                                .put("indexed", result.indexedFiles())
                                .put("rechunked", result.rechunkedFiles())
                                .put("unchanged", result.unchangedFiles())
                                .put("deleted", result.deletedFiles())
                                .put("rejected", result.rejectedFiles())
                                .put("failed", result.failedFiles()))
                .put("chunks", new Json.JsonObject().put("written", result.writtenChunks()))
                .put("embeddings", embeddings(result))
                .put("issues", result.issues().stream().map(IndexJson::issue).toList())
                .put("scanIssues", scanIssues(root, result.scanIssues()))
                .put(
                        "semanticIssues",
                        result.semanticIssues().stream()
                                .map(IndexJson::semanticIssue)
                                .toList())
                .put("warnings", WarningPrinter.json(result.warnings()))
                .put("error", error);
    }

    /** Describes a run that failed before it had a result, such as one whose folder does not exist. */
    static Json.JsonObject failure(Json.JsonObject error) {
        return document()
                .put("root", null)
                .put("database", null)
                .put("config", null)
                .put("status", null)
                .put("files", null)
                .put("chunks", null)
                .put("embeddings", null)
                .put("issues", List.of())
                .put("scanIssues", List.of())
                .put("semanticIssues", List.of())
                .put("warnings", List.of())
                .put("error", error);
    }

    /**
     * Describes what a run would do, found by a dry run; an unreadable index leaves the counts and files null.
     *
     * @param error why the dry run failed, from {@link #error}; null when it did not
     */
    static Json.JsonObject preview(IndexPreview preview, Json.JsonObject error) {
        IndexPreview.Changes changes = preview.changes();
        boolean readable = changes.index() != IndexPreview.IndexState.UNREADABLE;
        Path root = preview.loadedConfig().root();
        Json.JsonObject files = new Json.JsonObject()
                .put("new", changes.newFiles().size())
                .put("changed", changes.changedFiles().size())
                .put("rechunked", changes.rechunkedFiles().size())
                .put("unchanged", changes.unchangedFiles().size())
                .put("rejected", changes.rejectedFiles().size())
                .put("removed", changes.removedFiles().size());
        Json.JsonObject chunks = new Json.JsonObject()
                .put("toWrite", count(changes.chunksToWrite()))
                .put("kept", count(changes.chunksKept()))
                .put("removed", count(changes.chunksRemoved()));

        return document(true)
                .put("root", root.toString())
                .put("database", preview.databasePath().toString())
                .put("config", config(preview.loadedConfig()))
                .put("semantic", preview.semanticEnabled())
                .put("index", changes.index().name())
                .put("files", readable ? files : null)
                .put("chunks", readable ? chunks : null)
                .put("embeddings", plannedEmbeddings(changes.semantic()))
                .put("newFiles", planned(changes.newFiles()))
                .put("changedFiles", planned(changes.changedFiles()))
                .put("rechunkedFiles", planned(changes.rechunkedFiles()))
                .put(
                        "rejectedFiles",
                        changes.rejectedFiles().stream().map(IndexJson::issue).toList())
                .put("removedFiles", planned(changes.removedFiles()))
                .put("scanIssues", scanIssues(root, preview.walkResult().issues()))
                .put("warnings", WarningPrinter.json(preview.warnings()))
                .put("error", error);
    }

    /** Describes a dry run that failed before it could look at the project, such as one whose folder does not exist. */
    static Json.JsonObject previewFailure(Json.JsonObject error) {
        return document(true)
                .put("root", null)
                .put("database", null)
                .put("config", null)
                .put("semantic", null)
                .put("index", null)
                .put("files", null)
                .put("chunks", null)
                .put("embeddings", null)
                .put("newFiles", List.of())
                .put("changedFiles", List.of())
                .put("rechunkedFiles", List.of())
                .put("rejectedFiles", List.of())
                .put("removedFiles", List.of())
                .put("scanIssues", List.of())
                .put("warnings", List.of())
                .put("error", error);
    }

    /** Returns an error with the reason and message stderr shows. */
    static Json.JsonObject error(String reason, String message) {
        return new Json.JsonObject().put("reason", reason).put("message", message);
    }

    /** Returns the project's configuration file, or null when it uses the defaults. */
    static String config(LoadedConfig loaded) {
        return loaded.fromFile()
                ? loaded.root().resolve(PeciaConfigFile.FILE_NAME).toString()
                : null;
    }

    /** Describes paths discovery could not read, relative to the project root when they lie below it. */
    static List<Json.JsonObject> scanIssues(Path root, List<WalkResult.Issue> issues) {
        return issues.stream()
                .map(issue -> new Json.JsonObject()
                        .put("path", shownPath(root, issue.path()))
                        .put("cause", issue.cause().name())
                        .put("message", issue.reason()))
                .toList();
    }

    /** Describes a file that could not be read or was rejected. */
    static Json.JsonObject issue(IndexResult.FileIssue issue) {
        return new Json.JsonObject()
                .put("path", FileWalker.portablePath(issue.sourcePath()))
                .put("reason", issue.reason().name())
                .put("rejected", issue.rejected())
                .put("message", issue.message());
    }

    /** Describes an embedding coverage, or returns null when there is none. */
    private static Json.JsonObject coverage(EmbeddingCoverage coverage) {
        if (coverage == null) {
            return null;
        }

        return new Json.JsonObject()
                .put("embedded", coverage.embedded())
                .put("stale", coverage.stale())
                .put("missing", coverage.missing());
    }

    /** Starts an {@code index} document; {@code dryRun} is false here and true for previews. */
    static Json.JsonObject document(boolean dryRun) {
        return new Json.JsonObject()
                .put("schemaVersion", Json.SCHEMA_VERSION)
                .put("command", "index")
                .put("dryRun", dryRun);
    }

    private static Json.JsonObject document() {
        return document(false);
    }

    private static Json.JsonObject embeddings(IndexResult result) {
        ModelOutcome model = result.semanticModel();

        if (model == null) {
            return null;
        }

        return new Json.JsonObject()
                .put("model", model.model().name())
                .put("modelSource", model.status().name())
                .put("embedded", result.embeddedChunks())
                .put("backfilled", result.backfilledChunks())
                .put("discarded", result.discardedEmbeddings())
                .put("coverage", coverage(result.embeddingCoverage()));
    }

    private static Json.JsonObject semanticIssue(IndexResult.SemanticIssue issue) {
        return new Json.JsonObject()
                .put("path", FileWalker.portablePath(issue.sourcePath()))
                .put("reason", issue.reason().name())
                .put("message", issue.message());
    }

    private static Json.JsonObject plannedEmbeddings(IndexPreview.SemanticChanges semantic) {
        if (semantic == null) {
            return null;
        }

        return new Json.JsonObject()
                .put("model", semantic.model())
                .put("modelState", semantic.modelState().name())
                .put("downloadBytes", semantic.downloadBytes())
                .put("toEmbed", count(semantic.chunksToEmbed()))
                .put("toDiscard", semantic.embeddingsToDiscard());
    }

    private static List<Json.JsonObject> planned(List<IndexPreview.PlannedFile> files) {
        return files.stream()
                .map(file -> new Json.JsonObject()
                        .put("path", FileWalker.portablePath(file.sourcePath()))
                        .put("chunks", count(file.chunks())))
                .toList();
    }

    /** Describes a count and whether it is estimated from file sizes rather than read from the index. */
    private static Json.JsonObject count(IndexPreview.Count count) {
        return new Json.JsonObject().put("value", count.value()).put("estimated", count.estimated());
    }

    private static String shownPath(Path root, Path path) {
        return path.startsWith(root) ? FileWalker.portablePath(root.relativize(path)) : path.toString();
    }
}
