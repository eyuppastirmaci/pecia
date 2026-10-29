package dev.eyuppastirmaci.pecia.index;

import dev.eyuppastirmaci.pecia.config.PeciaConfig;
import dev.eyuppastirmaci.pecia.config.PeciaConfigLoader;
import dev.eyuppastirmaci.pecia.content.ExtractionException;
import dev.eyuppastirmaci.pecia.project.ProjectContext;
import dev.eyuppastirmaci.pecia.project.ProjectContextResolver;
import dev.eyuppastirmaci.pecia.semantic.DownloadListener;
import dev.eyuppastirmaci.pecia.semantic.EmbeddingException;
import dev.eyuppastirmaci.pecia.semantic.EmbeddingProfile;
import dev.eyuppastirmaci.pecia.semantic.ModelCache;
import dev.eyuppastirmaci.pecia.semantic.ModelException;
import dev.eyuppastirmaci.pecia.semantic.ModelOutcome;
import dev.eyuppastirmaci.pecia.semantic.ModelProvider;
import dev.eyuppastirmaci.pecia.storage.model.EmbeddingCoverage;
import dev.eyuppastirmaci.pecia.storage.sqlite.SqliteStorage;
import java.io.IOException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/** Coordinates project discovery and per-file extraction, chunking, and storage replacement. */
public final class IndexService {

    private final ProjectContextResolver contextResolver;
    private final StorageOpener storageOpener;
    private final ModelProvider models;
    private final TextEmbedder.Opener embedders;
    private final Supplier<EmbeddingProfile> profiles;

    /**
     * Creates an indexing service that opens SQLite storage for each resolved project and keeps the semantic model in
     * the user's cache. The cache is created only when a run enables semantic indexing, so lexical runs never touch
     * it.
     */
    public IndexService(PeciaConfigLoader configLoader) {
        this(configLoader, IndexService::openStorage, userModelCache());
    }

    /**
     * Creates an indexing service that obtains the semantic model from the given provider, for callers that manage
     * the model themselves and for tests.
     *
     * @throws NullPointerException if an argument is null
     */
    public static IndexService withModelProvider(PeciaConfigLoader configLoader, ModelProvider models) {
        return new IndexService(configLoader, IndexService::openStorage, models);
    }

    private static SqliteStorage openStorage(ProjectContext context) throws IOException, SQLException {
        return SqliteStorage.open(context.databasePath(), context.projectRoot());
    }

    IndexService(PeciaConfigLoader configLoader, StorageOpener storageOpener) {
        this(configLoader, storageOpener, userModelCache());
    }

    IndexService(PeciaConfigLoader configLoader, StorageOpener storageOpener, ModelProvider models) {
        this(configLoader, storageOpener, models, TextEmbedder::open);
    }

    IndexService(
            PeciaConfigLoader configLoader,
            StorageOpener storageOpener,
            ModelProvider models,
            TextEmbedder.Opener embedders) {
        // A lambda rather than a method reference, so the profile's class is reached only when a semantic run calls it.
        this(configLoader, storageOpener, models, embedders, () -> EmbeddingProfile.minilm());
    }

    IndexService(
            PeciaConfigLoader configLoader,
            StorageOpener storageOpener,
            ModelProvider models,
            TextEmbedder.Opener embedders,
            Supplier<EmbeddingProfile> profiles) {
        this.contextResolver = new ProjectContextResolver(configLoader);
        this.storageOpener = Objects.requireNonNull(storageOpener, "storageOpener");
        this.models = Objects.requireNonNull(models, "models");
        this.embedders = Objects.requireNonNull(embedders, "embedders");
        this.profiles = Objects.requireNonNull(profiles, "profiles");
    }

    /** Returns a provider that creates the user's model cache only when a run needs the model. */
    private static ModelProvider userModelCache() {
        return listener -> new ModelCache().ensure(listener);
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
        return preview(target, IndexOptions.configured());
    }

    /**
     * Previews like {@link #preview(Path)} and also reports whether the run would build embeddings for semantic
     * search, with the caller's choices overriding the project configuration.
     *
     * @param target directory whose descendants are scanned
     * @param options the caller's choices for the previewed run
     * @return the preview, including the resolved semantic indexing choice
     * @throws NullPointerException if target or options is null, before any filesystem access
     * @throws IOException if configuration cannot be read, the target is invalid, or traversal cannot start
     * @throws IllegalArgumentException if configuration or glob patterns are invalid
     */
    public IndexPreview preview(Path target, IndexOptions options) throws IOException {
        Objects.requireNonNull(options, "options");
        ProjectContext context = contextResolver.resolve(target);
        WalkResult result = scan(context);
        boolean semanticEnabled =
                options.resolveSemanticEnabled(context.loadedConfig().config());

        return new IndexPreview(context.target(), context.loadedConfig(), result, semanticEnabled);
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
        return index(target, IndexOptions.configured());
    }

    /**
     * Indexes like {@link #index(Path)} with the caller's choices for this run, which override the project
     * configuration. When semantic indexing resolves to enabled, the semantic model is made available and its
     * embedder opened after the configuration and token budget are validated and before storage opens. Embeddings of
     * another embedding profile, such as an earlier model revision, are discarded first; each new or changed file's
     * chunks are then embedded by {@code embed.concurrency} workers that share the embedder and stored with their
     * vectors in the file's replacement. The calling thread keeps reading files while the workers embed, writes files
     * in candidate order, and waits once a bounded number of chunks are waiting to be written. After cleanup,
     * the stored chunks of unchanged files that have no embedding are embedded from storage in pages that commit on
     * their own, so an interrupted run leaves only the rest for the next one; the progress listener hears how far this
     * has come. {@link #preview(Path, IndexOptions)} reports the resolved choice and never needs the model.
     *
     * <p>A cancelled run stops before its next file, embedding batch, or backfill page, skips deletions, closes the
     * embedder and storage, and returns {@link IndexResult.Status#CANCELLED} with what it committed.
     *
     * @param target directory whose descendants are indexed
     * @param options the caller's choices for this run
     * @throws NullPointerException if target or options is null, before any filesystem access
     * @throws IOException if target/configuration resolution or initial discovery fails, before storage is opened
     * @throws IllegalArgumentException if configuration, paths, globs, tokenizer budgets, or the model cache or proxy
     *     settings are invalid
     * @throws IndexException if discovery is incomplete, the semantic model or its embedder is unavailable, embedding
     *     fails, or storage fails; includes completed work and the cause
     */
    public IndexResult index(Path target, IndexOptions options) throws IOException, IndexException {
        Objects.requireNonNull(options, "options");
        ProjectContext context = contextResolver.resolve(target);
        WalkResult scanResult = scan(context);

        requireCompleteScan(context, scanResult);

        boolean semanticEnabled =
                options.resolveSemanticEnabled(context.loadedConfig().config());

        return indexFiles(context, scanResult, options, semanticEnabled);
    }

    private void requireCompleteScan(ProjectContext context, WalkResult scanResult) throws IndexException {
        if (scanResult.complete()) {
            return;
        }

        IndexResult result = new IndexResult(
                context, IndexResult.Status.INCOMPLETE_SCAN, scanResult.files().size(), 0, 0, List.of());

        throw new IndexException("Indexing requires a complete scan", null, result, scanResult.issues());
    }

    /** Indexes the scanned files, making the semantic model available only when {@code semanticEnabled}. */
    private IndexResult indexFiles(
            ProjectContext context, WalkResult scanResult, IndexOptions options, boolean semanticEnabled)
            throws IndexException {
        List<Path> candidates = scanResult.files();
        Cancellation cancellation = options.cancellation() == null ? new Cancellation() : options.cancellation();
        // Constructing the file pipeline validates the real tokenizer budget before opening storage.
        FileIndexer indexer = new FileIndexer(context);
        ModelOutcome semanticModel = semanticEnabled ? ensureModel(context, candidates, options) : null;
        int workers = context.loadedConfig().config().embedConcurrency();
        // Semantic classes are created only for a semantic run; the profile comes first so nothing can fail between
        // opening the embedder and the try block that closes it.
        EmbeddingProfile profile = semanticModel == null ? null : profiles.get();
        TextEmbedder embedder =
                semanticModel == null ? null : openEmbedder(context, candidates, semanticModel, workers);
        Tally tally = new Tally(context, candidates.size(), semanticModel);

        // Resources close in reverse order: the workers stop before the embedder they use closes.
        try (embedder;
                SqliteStorage storage = storageOpener.open(context);
                EmbeddingPipeline pipeline =
                        embedder == null ? null : new EmbeddingPipeline(embedder, cancellation, workers)) {
            if (pipeline != null) {
                // Embeddings of another profile cannot be compared with this run's; the backfill below makes them
                // again from the stored chunks, so the index never holds two profiles.
                tally.discardedEmbeddings = storage.discardIncompatibleEmbeddings(profile);
            }

            for (Path candidate : candidates) {
                if (cancellation.isCancelled()) {
                    break;
                }

                try {
                    switch (indexer.prepare(candidate, storage)) {
                        case FileIndexer.Unchanged unchanged -> tally.add(unchanged.result());
                        case FileIndexer.Changed changed
                        when pipeline == null -> tally.add(indexer.write(changed, storage));
                        case FileIndexer.Changed changed ->
                            // The file is written once its chunks are embedded, after every earlier file.
                            pipeline.submit(
                                    changed.embeddingInputs(),
                                    vectors -> tally.add(indexer.write(changed, storage, profile, vectors)));
                    }
                } catch (ExtractionException failure) {
                    tally.issues.add(new IndexResult.FileIssue(
                            context.sourcePath(candidate), failure.reason(), failure.getMessage()));
                }
            }

            if (pipeline != null) {
                pipeline.finish();
            }

            // A cancelled run's scan may be stale by the time it stops, so it deletes nothing.
            cancellation.check();

            if (tally.issues.isEmpty()) {
                IndexDeletionPlanner planner = new IndexDeletionPlanner(context);
                var missing = planner.plan(scanResult, storage.files().findAll());

                // Recheck absence and scope immediately before applying the batch.
                tally.deletedFiles = storage.deleteFiles(planner.plan(scanResult, missing));
            }

            if (pipeline != null) {
                // Unchanged files' chunks match their content and chunking, so they are embedded from storage.
                tally.backfill = new EmbeddingBackfill(storage, pipeline, profile, options.progressListener());
                tally.backfill.run(tally.unchanged);
                tally.coverage = storage.embeddingCoverage(profile);
            }
        } catch (IOException | SQLException failure) {
            IndexResult result = tally.result(IndexResult.Status.FAILED);

            throw new IndexException("Index storage or cleanup operation failed", failure, result, List.of());
        } catch (EmbeddingException failure) {
            // A failing file is not written; a failure while embedding stored chunks keeps the committed pages and the
            // cleanup that ran before it.
            IndexResult result = tally.result(IndexResult.Status.EMBEDDING_FAILED);

            throw new IndexException("Semantic indexing could not embed a file's chunks", failure, result, List.of());
        } catch (Cancellation.CancelledException cancelled) {
            // Storage, the workers, and the embedder are already closed; no file in progress was written.
            return tally.result(IndexResult.Status.CANCELLED);
        }

        return tally.result(tally.issues.isEmpty() ? IndexResult.Status.COMPLETE : IndexResult.Status.PARTIAL);
    }

    private TextEmbedder openEmbedder(ProjectContext context, List<Path> candidates, ModelOutcome model, int workers)
            throws IndexException {
        int threads = EmbeddingPipeline.intraOpThreads(Runtime.getRuntime().availableProcessors(), workers);

        try {
            return embedders.open(model, threads);
        } catch (EmbeddingException failure) {
            IndexResult result = new IndexResult(
                    context, IndexResult.Status.EMBEDDER_UNAVAILABLE, candidates.size(), 0, 0, 0, 0, List.of(), model);

            throw new IndexException("Semantic indexing could not open its embedder", failure, result, List.of());
        }
    }

    private ModelOutcome ensureModel(ProjectContext context, List<Path> candidates, IndexOptions options)
            throws IndexException {
        // Semantic classes are reached only here, so lexical runs never initialize them.
        DownloadListener listener =
                options.downloadListener() == null ? DownloadListener.NONE : options.downloadListener();

        try {
            return models.ensure(listener);
        } catch (ModelException failure) {
            IndexResult result =
                    new IndexResult(context, IndexResult.Status.MODEL_UNAVAILABLE, candidates.size(), 0, 0, List.of());

            throw new IndexException("Semantic indexing needs its model", failure, result, List.of());
        }
    }

    private WalkResult scan(ProjectContext context) throws IOException {
        PeciaConfig config = context.loadedConfig().config();
        GlobFilter filter = new GlobFilter(config.include(), config.exclude());
        FileWalker walker = new FileWalker(filter);

        return walker.scan(context.target(), context.projectRoot(), context.storageFiles());
    }

    /** What a run has committed so far, reported in its result however the run ends. */
    private static final class Tally {

        private final ProjectContext context;
        private final int candidateCount;
        private final ModelOutcome semanticModel;
        private final List<Long> unchanged = new ArrayList<>();
        private final List<IndexResult.FileIssue> issues = new ArrayList<>();
        private int indexedFiles;
        private int rechunkedFiles;
        private int deletedFiles;
        private long writtenChunks;
        private long embeddedChunks;
        private long discardedEmbeddings;
        private EmbeddingBackfill backfill;
        private EmbeddingCoverage coverage;

        Tally(ProjectContext context, int candidateCount, ModelOutcome semanticModel) {
            this.context = context;
            this.candidateCount = candidateCount;
            this.semanticModel = semanticModel;
        }

        void add(FileIndexer.Result result) {
            if (result.unchanged()) {
                unchanged.add(result.file().id());
            } else {
                indexedFiles++;

                if (result.rechunked()) {
                    rechunkedFiles++;
                }
            }

            writtenChunks += result.chunkCount();
            embeddedChunks += result.embeddedChunks();
        }

        /** Builds the result; coverage is set only once a run has completed its embedding. */
        IndexResult result(IndexResult.Status status) {
            return new IndexResult(
                    context,
                    status,
                    candidateCount,
                    indexedFiles,
                    unchanged.size(),
                    deletedFiles,
                    writtenChunks,
                    issues,
                    semanticModel,
                    embeddedChunks,
                    backfill == null ? 0 : backfill.stored(),
                    discardedEmbeddings,
                    coverage,
                    rechunkedFiles);
        }
    }

    @FunctionalInterface
    interface StorageOpener {

        SqliteStorage open(ProjectContext context) throws IOException, SQLException;
    }
}
