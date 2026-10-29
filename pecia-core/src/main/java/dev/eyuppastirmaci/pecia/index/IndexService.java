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
     * chunks are then embedded and stored with their vectors in the file's replacement. After cleanup,
     * the stored chunks of unchanged files that have no embedding are embedded from storage in pages that commit on
     * their own, so an interrupted run leaves only the rest for the next one; the progress listener hears how far this
     * has come. {@link #preview(Path, IndexOptions)} reports the resolved choice and never needs the model.
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

        return indexFiles(context, scanResult, semanticEnabled ? options : null);
    }

    private void requireCompleteScan(ProjectContext context, WalkResult scanResult) throws IndexException {
        if (scanResult.complete()) {
            return;
        }

        IndexResult result = new IndexResult(
                context, IndexResult.Status.INCOMPLETE_SCAN, scanResult.files().size(), 0, 0, List.of());

        throw new IndexException("Indexing requires a complete scan", null, result, scanResult.issues());
    }

    /** Indexes the scanned files; {@code semanticOptions} is null when the run does not need the semantic model. */
    private IndexResult indexFiles(ProjectContext context, WalkResult scanResult, IndexOptions semanticOptions)
            throws IndexException {
        List<Path> candidates = scanResult.files();
        // Constructing the file pipeline validates the real tokenizer budget before opening storage.
        FileIndexer indexer = new FileIndexer(context);
        ModelOutcome semanticModel = semanticOptions == null ? null : ensureModel(context, candidates, semanticOptions);
        // Semantic classes are created only for a semantic run; the profile comes first so nothing can fail between
        // opening the embedder and the try block that closes it.
        EmbeddingProfile profile = semanticModel == null ? null : profiles.get();
        TextEmbedder embedder = semanticModel == null ? null : openEmbedder(context, candidates, semanticModel);
        SemanticIndexing semantic = embedder == null ? null : new SemanticIndexing(embedder, profile);
        int indexedFiles = 0;
        int unchangedFiles = 0;
        int deletedFiles = 0;
        long writtenChunks = 0;
        long embeddedChunks = 0;
        long discardedEmbeddings = 0;
        EmbeddingCoverage coverage = null;
        EmbeddingBackfill backfill = null;
        List<Long> unchanged = new ArrayList<>();
        List<IndexResult.FileIssue> issues = new ArrayList<>();

        try (embedder;
                SqliteStorage storage = storageOpener.open(context)) {
            if (semantic != null) {
                // Embeddings of another profile cannot be compared with this run's; the backfill below makes them
                // again from the stored chunks, so the index never holds two profiles.
                discardedEmbeddings = storage.discardIncompatibleEmbeddings(profile);
            }

            for (Path candidate : candidates) {
                try {
                    FileIndexer.Result result = indexer.index(candidate, storage, semantic);
                    if (result.unchanged()) {
                        unchangedFiles++;
                        unchanged.add(result.file().id());
                    } else {
                        indexedFiles++;
                    }

                    writtenChunks += result.chunkCount();
                    embeddedChunks += result.embeddedChunks();
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

            if (semantic != null) {
                // Unchanged files' chunks match their content and chunking, so they are embedded from storage.
                backfill = new EmbeddingBackfill(storage, semantic, semanticOptions.progressListener());
                backfill.run(unchanged);
                coverage = storage.embeddingCoverage(profile);
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
                    issues,
                    semanticModel,
                    embeddedChunks,
                    backfilled(backfill),
                    discardedEmbeddings,
                    null);

            throw new IndexException("Index storage or cleanup operation failed", failure, result, List.of());
        } catch (EmbeddingException failure) {
            // A failing file is not written; a failure while embedding stored chunks keeps the committed pages and the
            // cleanup that ran before it.
            IndexResult result = new IndexResult(
                    context,
                    IndexResult.Status.EMBEDDING_FAILED,
                    candidates.size(),
                    indexedFiles,
                    unchangedFiles,
                    deletedFiles,
                    writtenChunks,
                    issues,
                    semanticModel,
                    embeddedChunks,
                    backfilled(backfill),
                    discardedEmbeddings,
                    null);

            throw new IndexException("Semantic indexing could not embed a file's chunks", failure, result, List.of());
        }

        IndexResult.Status status = issues.isEmpty() ? IndexResult.Status.COMPLETE : IndexResult.Status.PARTIAL;

        return new IndexResult(
                context,
                status,
                candidates.size(),
                indexedFiles,
                unchangedFiles,
                deletedFiles,
                writtenChunks,
                issues,
                semanticModel,
                embeddedChunks,
                backfilled(backfill),
                discardedEmbeddings,
                coverage);
    }

    private static long backfilled(EmbeddingBackfill backfill) {
        return backfill == null ? 0 : backfill.stored();
    }

    private TextEmbedder openEmbedder(ProjectContext context, List<Path> candidates, ModelOutcome model)
            throws IndexException {
        try {
            return embedders.open(model);
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

    @FunctionalInterface
    interface StorageOpener {

        SqliteStorage open(ProjectContext context) throws IOException, SQLException;
    }
}
