package dev.eyuppastirmaci.pecia.index;

import dev.eyuppastirmaci.pecia.config.PeciaConfig;
import dev.eyuppastirmaci.pecia.config.PeciaConfigLoader;
import dev.eyuppastirmaci.pecia.content.ExtractionException;
import dev.eyuppastirmaci.pecia.project.ProjectContext;
import dev.eyuppastirmaci.pecia.project.ProjectContextResolver;
import dev.eyuppastirmaci.pecia.storage.sqlite.SqliteStorage;
import java.io.IOException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Coordinates project discovery and per-file extraction, chunking, and storage replacement. */
public final class IndexService {

    private final ProjectContextResolver contextResolver;
    private final StorageOpener storageOpener;

    /** Creates an indexing service that opens SQLite storage for each resolved project. */
    public IndexService(PeciaConfigLoader configLoader) {
        this(configLoader, context -> SqliteStorage.open(context.databasePath(), context.projectRoot()));
    }

    IndexService(PeciaConfigLoader configLoader, StorageOpener storageOpener) {
        this.contextResolver = new ProjectContextResolver(configLoader);
        this.storageOpener = Objects.requireNonNull(storageOpener, "storageOpener");
    }

    /**
     * Discovers candidate files using project configuration without processing their contents or
     * writing index data.
     *
     * @param target directory whose descendants are scanned
     * @return the normalized absolute target, loaded configuration, and target-relative files with
     *     recoverable scan issues
     * @throws NullPointerException if target is null
     * @throws IOException if configuration cannot be read, the target is invalid, or traversal cannot
     *     start
     * @throws IllegalArgumentException if configuration or glob patterns are invalid
     */
    public IndexPreview preview(Path target) throws IOException {
        ProjectContext context = contextResolver.resolve(target);
        WalkResult result = scan(context);

        return new IndexPreview(context.target(), context.loadedConfig(), result);
    }

    /**
     * Indexes admitted files in deterministic order, atomically replacing each file independently.
     * Unchanged content with a matching profile is skipped. After a successful run, confirmed missing
     * files in the target scope are deleted as one atomic batch; excluded records are retained.
     * Content rejection/read errors retain old data, skip cleanup and produce a PARTIAL result. Storage
     * or cleanup failures abort while preserving earlier per-file commits. A
     * valid empty directory creates a queryable empty index. This service owns and closes storage.
     *
     * @throws IOException if target/configuration resolution or initial discovery fails, before
     *     storage is opened
     * @throws IllegalArgumentException if configuration, paths, globs or tokenizer budgets are
     *     invalid
     * @throws NullPointerException if target is null
     * @throws IndexException if discovery is incomplete or storage fails; includes completed work and
     *     the cause
     */
    public IndexResult index(Path target) throws IOException, IndexException {
        ProjectContext context = contextResolver.resolve(target);
        WalkResult scanResult = scan(context);

        requireCompleteScan(context, scanResult);

        return indexFiles(context, scanResult);
    }

    private void requireCompleteScan(ProjectContext context, WalkResult scanResult) throws IndexException {
        if (scanResult.complete()) {
            return;
        }

        IndexResult result = new IndexResult(
                context, IndexResult.Status.INCOMPLETE_SCAN, scanResult.files().size(), 0, 0, List.of());

        throw new IndexException("Indexing requires a complete scan", null, result, scanResult.issues());
    }

    private IndexResult indexFiles(ProjectContext context, WalkResult scanResult) throws IndexException {
        List<Path> candidates = scanResult.files();
        // Constructing the file pipeline validates the real tokenizer budget before opening storage.
        FileIndexer indexer = new FileIndexer(context);
        int indexedFiles = 0;
        int unchangedFiles = 0;
        int deletedFiles = 0;
        long writtenChunks = 0;
        List<IndexResult.FileIssue> issues = new ArrayList<>();

        try (SqliteStorage storage = storageOpener.open(context)) {
            for (Path candidate : candidates) {
                try {
                    FileIndexer.Result result = indexer.index(candidate, storage);
                    if (result.unchanged()) {
                        unchangedFiles++;
                    } else {
                        indexedFiles++;
                    }

                    writtenChunks += result.chunkCount();
                } catch (ExtractionException failure) {
                    IndexResult.FileIssue issue = new IndexResult.FileIssue(
                            context.sourcePath(candidate), failure.reason(), failure.getMessage());

                    issues.add(issue);
                }
            }

            if (issues.isEmpty()) {
                IndexDeletionPlanner planner = new IndexDeletionPlanner(context);
                var missing = planner.plan(scanResult, storage.files().findAll());

                // Recheck absence and scope immediately before applying the batch.
                deletedFiles = storage.deleteFiles(planner.plan(scanResult, missing));
            }
        } catch (IOException | SQLException failure) {
            IndexResult result = new IndexResult(
                    context,
                    IndexResult.Status.FAILED,
                    candidates.size(),
                    indexedFiles,
                    unchangedFiles,
                    deletedFiles,
                    writtenChunks,
                    issues);

            throw new IndexException("Index storage or cleanup operation failed", failure, result, List.of());
        }

        IndexResult.Status status = issues.isEmpty() ? IndexResult.Status.COMPLETE : IndexResult.Status.PARTIAL;

        return new IndexResult(
                context, status, candidates.size(), indexedFiles, unchangedFiles, deletedFiles, writtenChunks, issues);
    }

    private WalkResult scan(ProjectContext context) throws IOException {
        PeciaConfig config = context.loadedConfig().config();

        return new FileWalker(new GlobFilter(config.include(), config.exclude()))
                .scan(context.target(), context.projectRoot(), context.storageFiles());
    }

    @FunctionalInterface
    interface StorageOpener {

        SqliteStorage open(ProjectContext context) throws IOException, SQLException;
    }
}
