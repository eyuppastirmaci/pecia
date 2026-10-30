package dev.eyuppastirmaci.pecia.index;

import dev.eyuppastirmaci.pecia.config.PeciaConfig;
import dev.eyuppastirmaci.pecia.config.PeciaConfigLoader;
import dev.eyuppastirmaci.pecia.content.Document;
import dev.eyuppastirmaci.pecia.content.ExtractionException;
import dev.eyuppastirmaci.pecia.project.ProjectContext;
import dev.eyuppastirmaci.pecia.project.ProjectContextResolver;
import dev.eyuppastirmaci.pecia.semantic.DownloadListener;
import dev.eyuppastirmaci.pecia.semantic.DownloadProgressListener;
import dev.eyuppastirmaci.pecia.semantic.EmbeddingException;
import dev.eyuppastirmaci.pecia.semantic.EmbeddingProfile;
import dev.eyuppastirmaci.pecia.semantic.ModelCache;
import dev.eyuppastirmaci.pecia.semantic.ModelException;
import dev.eyuppastirmaci.pecia.semantic.ModelLookup;
import dev.eyuppastirmaci.pecia.semantic.ModelOutcome;
import dev.eyuppastirmaci.pecia.semantic.ModelProvider;
import dev.eyuppastirmaci.pecia.semantic.SemanticModel;
import dev.eyuppastirmaci.pecia.storage.model.EmbeddingCoverage;
import dev.eyuppastirmaci.pecia.storage.model.StoredFile;
import dev.eyuppastirmaci.pecia.storage.sqlite.IndexAccessException;
import dev.eyuppastirmaci.pecia.storage.sqlite.SqliteStorage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.function.LongConsumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/** Coordinates project discovery and per-file extraction, chunking, and storage replacement. */
public final class IndexService {

    private final ProjectContextResolver contextResolver;
    private final StorageOpener storageOpener;
    private final ModelProvider models;
    private final TextEmbedder.Opener embedders;
    private final Supplier<EmbeddingProfile> profiles;
    private final ModelLookup modelLookup;
    private final LongSupplier nanoTime;

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
        // A lambda, so the cache's class is reached only when a semantic preview looks for the model.
        this(configLoader, storageOpener, models, embedders, profiles, () -> new ModelCache().find());
    }

    IndexService(
            PeciaConfigLoader configLoader,
            StorageOpener storageOpener,
            ModelProvider models,
            TextEmbedder.Opener embedders,
            Supplier<EmbeddingProfile> profiles,
            ModelLookup modelLookup) {
        this(configLoader, storageOpener, models, embedders, profiles, modelLookup, System::nanoTime);
    }

    IndexService(
            PeciaConfigLoader configLoader,
            StorageOpener storageOpener,
            ModelProvider models,
            TextEmbedder.Opener embedders,
            Supplier<EmbeddingProfile> profiles,
            ModelLookup modelLookup,
            LongSupplier nanoTime) {
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
        this.modelLookup = Objects.requireNonNull(modelLookup, "modelLookup");
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
     * Finds what an indexing run would do without writing anything: discovers the candidates, then reads, hashes,
     * and validates each one and compares it with the index, which is opened read-only (an index of an older format
     * is upgraded in a temporary copy). Candidates are never chunked or embedded, and nothing is downloaded.
     *
     * @param target directory whose descendants are scanned
     * @return the normalized absolute target, loaded configuration, target-relative files with recoverable scan
     *     issues, and what the run would do with the index and each candidate
     * @throws NullPointerException if target is null
     * @throws IOException if configuration cannot be read, the target is invalid, or traversal cannot
     *     start
     * @throws IllegalArgumentException if configuration, glob patterns, or the chunk budget are invalid
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
     * @throws IllegalArgumentException if configuration, glob patterns, or the chunk budget are invalid
     */
    public IndexPreview preview(Path target, IndexOptions options) throws IOException {
        Objects.requireNonNull(options, "options");
        ProjectContext context = contextResolver.resolve(target);
        WalkResult result = scan(context);
        boolean semanticEnabled =
                options.resolveSemanticEnabled(context.loadedConfig().config());
        // Constructing the inspector validates the chunk budget, as a run does before it opens storage.
        Preview preview =
                new Preview(context, result, new FileInspector(context), semanticEnabled ? profiles.get() : null);
        IndexPreview.Changes changes = preview.changes();

        if (semanticEnabled && changes.index() != IndexPreview.IndexState.UNREADABLE) {
            changes = withModel(changes);
        }

        return new IndexPreview(
                context.target(), context.databasePath(), context.loadedConfig(), result, semanticEnabled, changes);
    }

    /** Adds whether the model is cached, which the preview finds out without downloading or loading anything. */
    private IndexPreview.Changes withModel(IndexPreview.Changes changes) {
        IndexPreview.SemanticChanges semantic = changes.semantic();
        IndexPreview.ModelState state;

        try {
            state = modelLookup.find().isPresent() ? IndexPreview.ModelState.CACHED : IndexPreview.ModelState.DOWNLOAD;
        } catch (ModelException unreadable) {
            state = IndexPreview.ModelState.UNAVAILABLE;
        }

        SemanticModel model = SemanticModel.MINILM;
        IndexPreview.SemanticChanges withModel = new IndexPreview.SemanticChanges(
                model.name(),
                state,
                state == IndexPreview.ModelState.DOWNLOAD ? model.size() : 0,
                semantic.chunksToEmbed(),
                semantic.embeddingsToDiscard());

        return new IndexPreview.Changes(
                changes.index(),
                changes.newFiles(),
                changes.changedFiles(),
                changes.rechunkedFiles(),
                changes.unchangedFiles(),
                changes.rejectedFiles(),
                changes.removedFiles(),
                withModel,
                null,
                null);
    }

    /**
     * Classifies a project's candidates against its index as the run would find it, and counts their chunks and, for
     * a semantic run, what it would embed; {@code profile} is null for a lexical run.
     */
    private record Preview(ProjectContext context, WalkResult scan, FileInspector inspector, EmbeddingProfile profile) {

        IndexPreview.Changes changes() throws IOException {
            // Opening read-only never creates the index; a missing one is reported as not found.
            try (SqliteStorage storage = SqliteStorage.openReadOnly(context.databasePath(), context.projectRoot())) {
                return classify(storage, IndexPreview.IndexState.CURRENT);
            } catch (IndexAccessException failure) {
                if (failure.reason() == IndexAccessException.Reason.NOT_FOUND) {
                    return classifyWithoutIndex();
                }

                if (failure.reason() != IndexAccessException.Reason.MIGRATION_REQUIRED) {
                    return IndexPreview.Changes.unreadable(StorageFailure.of(failure), failure.getMessage());
                }
            } catch (SQLException failure) {
                return IndexPreview.Changes.unreadable(StorageFailure.of(failure), failure.getMessage());
            }

            return classifyUpgraded();
        }

        /** Upgrades a copy of an index of an older format outside the project and classifies against the copy. */
        private IndexPreview.Changes classifyUpgraded() throws IOException {
            Path database = context.databasePath();
            Path directory = Files.createTempDirectory("pecia-preview-");

            try {
                Path copy = directory.resolve(database.getFileName());
                Files.copy(database, copy);
                // A journal left by an interrupted run belongs to the database; the upgrade rolls it back first.
                Path journal = database.resolveSibling(database.getFileName() + "-journal");

                if (Files.exists(journal, LinkOption.NOFOLLOW_LINKS)) {
                    Files.copy(journal, directory.resolve(journal.getFileName()));
                }

                try (SqliteStorage storage = SqliteStorage.open(copy, context.projectRoot())) {
                    return classify(storage, IndexPreview.IndexState.UPGRADE);
                } catch (SQLException failure) {
                    return IndexPreview.Changes.unreadable(StorageFailure.of(failure), failure.getMessage());
                }
            } finally {
                deleteRecursively(directory);
            }
        }

        private IndexPreview.Changes classifyWithoutIndex() {
            ChunkEstimate estimate = new ChunkEstimate(context.loadedConfig().config());
            List<IndexPreview.PlannedFile> added = new ArrayList<>();
            List<IndexResult.FileIssue> rejected = new ArrayList<>();

            for (Path candidate : scan.files()) {
                try {
                    added.add(planned(inspector.inspectNew(candidate), estimate));
                } catch (ExtractionException failure) {
                    rejected.add(issue(context, candidate, failure));
                }
            }

            IndexPreview.Changes changes = new IndexPreview.Changes(
                    IndexPreview.IndexState.NONE,
                    added,
                    List.of(),
                    List.of(),
                    List.of(),
                    rejected,
                    List.of(),
                    null,
                    null,
                    null);

            return profile == null ? changes : withEmbeddings(changes, 0, 0);
        }

        private IndexPreview.Changes classify(SqliteStorage storage, IndexPreview.IndexState state)
                throws IOException, SQLException {
            ChunkEstimate estimate = new ChunkEstimate(context.loadedConfig().config());
            Map<Long, Long> stored = storage.chunkCounts();
            Map<Long, EmbeddingCoverage> coverage =
                    profile == null ? Map.of() : storage.embeddingCoverageByFile(profile);
            List<IndexPreview.PlannedFile> added = new ArrayList<>();
            List<IndexPreview.PlannedFile> changed = new ArrayList<>();
            List<IndexPreview.PlannedFile> rechunked = new ArrayList<>();
            List<IndexPreview.PlannedFile> unchanged = new ArrayList<>();
            List<IndexResult.FileIssue> rejected = new ArrayList<>();
            long backfill = 0;

            for (Path candidate : scan.files()) {
                try {
                    switch (inspector.inspect(candidate, storage)) {
                        case FileInspector.Unchanged kept -> {
                            unchanged.add(storedFile(kept.file(), stored));
                            EmbeddingCoverage fileCoverage =
                                    coverage.get(kept.file().id());

                            // Unchanged chunks without an embedding of this model are embedded from storage.
                            if (fileCoverage != null) {
                                backfill += fileCoverage.stale() + fileCoverage.missing();
                            }
                        }
                        case FileInspector.Readable readable -> {
                            List<IndexPreview.PlannedFile> kind =
                                    switch (readable.kind()) {
                                        case NEW -> added;
                                        case CHANGED -> changed;
                                        case RECHUNKED -> rechunked;
                                    };
                            kind.add(planned(readable, estimate));
                        }
                    }
                } catch (ExtractionException failure) {
                    rejected.add(issue(context, candidate, failure));
                }
            }

            List<IndexPreview.PlannedFile> removed = List.of();

            // A run deletes only after reading every candidate and path, as indexFiles does.
            if (rejected.isEmpty() && scan.complete()) {
                IndexDeletionPlanner planner = new IndexDeletionPlanner(context);
                removed = planner.plan(scan, storage.files().findAll()).stream()
                        .map(file -> storedFile(file, stored))
                        .toList();
            }

            IndexPreview.Changes changes = new IndexPreview.Changes(
                    state, added, changed, rechunked, unchanged, rejected, removed, null, null, null);

            if (profile == null) {
                return changes;
            }

            // The run discards every embedding of another model version before it embeds anything.
            return withEmbeddings(
                    changes, backfill, storage.embeddingCoverage(profile).stale());
        }

        /** Adds the semantic counts; whether the model is cached is filled in by the caller. */
        private static IndexPreview.Changes withEmbeddings(
                IndexPreview.Changes changes, long backfill, long discarded) {
            IndexPreview.SemanticChanges semantic = new IndexPreview.SemanticChanges(
                    SemanticModel.MINILM.name(),
                    IndexPreview.ModelState.CACHED,
                    0,
                    changes.chunksToWrite().plus(IndexPreview.Count.known(backfill)),
                    discarded);

            return new IndexPreview.Changes(
                    changes.index(),
                    changes.newFiles(),
                    changes.changedFiles(),
                    changes.rechunkedFiles(),
                    changes.unchangedFiles(),
                    changes.rejectedFiles(),
                    changes.removedFiles(),
                    semantic,
                    null,
                    null);
        }

        private static IndexPreview.PlannedFile planned(FileInspector.Readable readable, ChunkEstimate estimate) {
            Document document = readable.document();
            // An empty file has no chunks for certain.
            IndexPreview.Count chunks = readable.size() == 0
                    ? IndexPreview.Count.known(0)
                    : IndexPreview.Count.estimate(estimate.chunks(document.type(), readable.size()));

            return new IndexPreview.PlannedFile(document.sourcePath(), chunks);
        }

        private static IndexPreview.PlannedFile storedFile(StoredFile file, Map<Long, Long> stored) {
            return new IndexPreview.PlannedFile(
                    file.sourcePath(), IndexPreview.Count.known(stored.getOrDefault(file.id(), 0L)));
        }
    }

    private static IndexResult.FileIssue issue(ProjectContext context, Path candidate, ExtractionException failure) {
        return new IndexResult.FileIssue(context.sourcePath(candidate), failure.reason(), failure.getMessage());
    }

    private static void deleteRecursively(Path directory) throws IOException {
        try (var paths = Files.walk(directory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    /**
     * Indexes admitted files in deterministic order, atomically replacing each file independently.
     * Unchanged content with a matching profile is skipped. After a successful run, confirmed missing
     * files in the target scope are deleted as one atomic batch; excluded records are retained.
     * Content rejection/read errors retain old data, skip cleanup and produce a PARTIAL result. Paths that discovery
     * cannot read, such as a directory without permission, are reported as scan issues: the files that were found are
     * indexed, cleanup is skipped, and the result is PARTIAL. Storage
     * or cleanup failures abort while preserving earlier per-file commits. A
     * valid empty directory creates a queryable empty index. This service owns and closes storage.
     *
     * @throws IOException if target/configuration resolution or initial discovery fails, before
     *     storage is opened
     * @throws IllegalArgumentException if configuration, paths, globs or tokenizer budgets are
     *     invalid
     * @throws NullPointerException if target is null
     * @throws IndexException if storage fails; includes completed work and
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
     * their own, so an interrupted run leaves only the rest for the next one. The progress listener hears each phase
     * the run reaches, from discovery to the backfill, when it starts, at most every 100 ms while it runs, and when it
     * ends, also when the run fails or is cancelled; the result says how long the run and each phase took. {@link #preview(Path, IndexOptions)} reports the resolved choice and never needs the model.
     *
     * <p>A file whose chunks cannot be embedded is written without embeddings and reported as a semantic issue, and a
     * backfill page that cannot be embedded is skipped; the result is then PARTIAL, and a later semantic run embeds
     * them. A broken runtime or model, or {@value EmbeddingFailures#CONSECUTIVE_LIMIT} failing files or pages in a row,
     * stop the run as EMBEDDING_FAILED instead.
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
     * @throws IndexException if the semantic model or its embedder is unavailable, embedding
     *     fails, or storage fails; includes completed work and the cause
     */
    public IndexResult index(Path target, IndexOptions options) throws IOException, IndexException {
        Objects.requireNonNull(options, "options");
        ProgressReporter progress = new ProgressReporter(options.progressListener(), nanoTime);
        ProjectContext context = contextResolver.resolve(target);
        ProgressReporter.Phase scanning = progress.start(IndexProgress.Phase.SCAN, OptionalLong.empty());
        WalkResult scanResult;

        try {
            scanResult = scan(context, scanning::update);
        } finally {
            scanning.finish();
        }

        boolean semanticEnabled =
                options.resolveSemanticEnabled(context.loadedConfig().config());

        return indexFiles(context, scanResult, options, semanticEnabled, progress);
    }

    /** Indexes the scanned files, making the semantic model available only when {@code semanticEnabled}. */
    private IndexResult indexFiles(
            ProjectContext context,
            WalkResult scanResult,
            IndexOptions options,
            boolean semanticEnabled,
            ProgressReporter progress)
            throws IndexException {
        List<Path> candidates = scanResult.files();
        Cancellation cancellation = options.cancellation() == null ? new Cancellation() : options.cancellation();
        // Constructing the file pipeline validates the real tokenizer budget before opening storage.
        FileIndexer indexer = new FileIndexer(context);
        ProgressReporter.Phase loading =
                semanticEnabled ? progress.start(IndexProgress.Phase.MODEL, OptionalLong.empty()) : null;
        ModelOutcome semanticModel =
                semanticEnabled ? ensureModel(context, candidates, options, progress, loading) : null;
        int workers = context.loadedConfig().config().embedConcurrency();
        // Semantic classes are created only for a semantic run; the profile comes first so nothing can fail between
        // opening the embedder and the try block that closes it.
        EmbeddingProfile profile = semanticModel == null ? null : profiles.get();
        TextEmbedder embedder =
                semanticModel == null ? null : openEmbedder(context, candidates, semanticModel, workers, progress);
        Tally tally = new Tally(context, candidates.size(), semanticModel, scanResult.issues(), progress);
        EmbeddingFailures failures = new EmbeddingFailures();

        // Resources close in reverse order: the workers stop before the embedder they use closes.
        try (embedder;
                SqliteStorage storage = storageOpener.open(context);
                EmbeddingPipeline pipeline =
                        embedder == null ? null : new EmbeddingPipeline(embedder, cancellation, workers)) {
            if (loading != null) {
                // Finished inside the try, so the embedder closes even if the listener fails.
                loading.finish();
            }

            if (pipeline != null) {
                // Embeddings of another profile cannot be compared with this run's; the backfill below makes them
                // again from the stored chunks, so the index never holds two profiles.
                tally.discardedEmbeddings = storage.discardIncompatibleEmbeddings(profile);
            }

            ProgressReporter.Phase files =
                    progress.start(IndexProgress.Phase.FILES, OptionalLong.of(candidates.size()));

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
                                    vectors -> {
                                        tally.add(indexer.write(changed, storage, profile, vectors));
                                        files.update(tally.fileCounts());
                                        failures.succeeded();
                                    },
                                    failure -> {
                                        // Its new chunks keep lexical search current; a later run embeds them.
                                        tally.add(indexer.write(changed, storage));
                                        files.update(tally.fileCounts());
                                        tally.semanticIssues.add(new IndexResult.SemanticIssue(
                                                changed.document().sourcePath(),
                                                failure.reason(),
                                                failure.getMessage()));
                                        failures.failed(failure);
                                    });
                    }
                } catch (ExtractionException failure) {
                    tally.addIssue(new IndexResult.FileIssue(
                            context.sourcePath(candidate), failure.reason(), failure.getMessage()));
                }

                files.update(tally.fileCounts());
            }

            if (pipeline != null) {
                pipeline.finish();
            }

            files.finish(tally.fileCounts());

            // A cancelled run's scan may be stale by the time it stops, so it deletes nothing.
            cancellation.check();

            // A path that could not be read or discovered is not known to be gone.
            if (tally.issues.isEmpty() && scanResult.complete()) {
                IndexDeletionPlanner planner = new IndexDeletionPlanner(context);
                var missing = planner.plan(scanResult, storage.files().findAll());
                // A cleanup with nothing to remove is not worth reporting.
                ProgressReporter.Phase cleanup = missing.isEmpty()
                        ? null
                        : progress.start(IndexProgress.Phase.CLEANUP, OptionalLong.of(missing.size()));

                // Recheck absence and scope immediately before applying the batch.
                tally.deletedFiles = storage.deleteFiles(planner.plan(scanResult, missing));

                if (cleanup != null) {
                    cleanup.finish(tally.deletedFiles);
                }
            }

            if (pipeline != null) {
                // Unchanged files' chunks match their content and chunking, so they are embedded from storage.
                tally.backfill = new EmbeddingBackfill(
                        storage, pipeline, profile, progress, failures, tally.semanticIssues::add);
                tally.backfill.run(tally.unchanged);
                tally.coverage = storage.embeddingCoverage(profile);
            }
        } catch (IOException | SQLException failure) {
            IndexResult result = tally.result(IndexResult.Status.FAILED);

            throw new IndexException(
                    "Index storage or cleanup operation failed", failure, result, StorageFailure.of(failure));
        } catch (EmbeddingException failure) {
            // Files and pages before the one that stopped the run stay, as does the cleanup that ran before it.
            IndexResult result = tally.result(IndexResult.Status.EMBEDDING_FAILED);

            throw new IndexException("Semantic indexing could not embed a file's chunks", failure, result);
        } catch (Cancellation.CancelledException cancelled) {
            // Storage, the workers, and the embedder are already closed; no file in progress was written.
            return tally.result(IndexResult.Status.CANCELLED);
        }

        boolean partial = !tally.issues.isEmpty() || !scanResult.complete() || !tally.semanticIssues.isEmpty();

        return tally.result(partial ? IndexResult.Status.PARTIAL : IndexResult.Status.COMPLETE);
    }

    private TextEmbedder openEmbedder(
            ProjectContext context, List<Path> candidates, ModelOutcome model, int workers, ProgressReporter progress)
            throws IndexException {
        int threads = EmbeddingPipeline.intraOpThreads(Runtime.getRuntime().availableProcessors(), workers);

        try {
            return embedders.open(model, threads);
        } catch (EmbeddingException failure) {
            IndexResult result =
                    nothingIndexed(context, IndexResult.Status.EMBEDDER_UNAVAILABLE, candidates, model, progress);

            throw new IndexException("Semantic indexing could not open its embedder", failure, result);
        }
    }

    private ModelOutcome ensureModel(
            ProjectContext context,
            List<Path> candidates,
            IndexOptions options,
            ProgressReporter progress,
            ProgressReporter.Phase loading)
            throws IndexException {
        // Semantic classes are reached only here, so lexical runs never initialize them.
        DownloadListener caller =
                options.downloadListener() == null ? DownloadListener.NONE : options.downloadListener();
        // The caller hears the download as before, and the model phase reports its bytes.
        DownloadListener listener = new DownloadProgressListener() {
            @Override
            public void downloading(SemanticModel model, Path file) {
                caller.downloading(model, file);
            }

            @Override
            public void received(long received, long size) {
                if (caller instanceof DownloadProgressListener progress) {
                    progress.received(received, size);
                }

                loading.update(received, size);
            }
        };

        try {
            return models.ensure(listener);
        } catch (ModelException failure) {
            IndexResult result =
                    nothingIndexed(context, IndexResult.Status.MODEL_UNAVAILABLE, candidates, null, progress);

            throw new IndexException("Semantic indexing needs its model", failure, result);
        }
    }

    /** Returns the result of a semantic run that stopped before it indexed anything. */
    private static IndexResult nothingIndexed(
            ProjectContext context,
            IndexResult.Status status,
            List<Path> candidates,
            ModelOutcome model,
            ProgressReporter progress) {
        return new IndexResult(
                context,
                status,
                candidates.size(),
                0,
                0,
                0,
                0,
                List.of(),
                model,
                0,
                0,
                0,
                null,
                0,
                List.of(),
                List.of(),
                progress.timings());
    }

    private WalkResult scan(ProjectContext context) throws IOException {
        return scan(context, found -> {});
    }

    /** Discovers the candidates, telling {@code found} how many it has after each one. */
    private WalkResult scan(ProjectContext context, LongConsumer found) throws IOException {
        PeciaConfig config = context.loadedConfig().config();
        GlobFilter filter = new GlobFilter(config.include(), config.exclude());
        FileWalker walker = new FileWalker(filter);

        return walker.scan(context.target(), context.projectRoot(), context.storageFiles(), found);
    }

    /** What a run has committed so far, reported in its result however the run ends. */
    private static final class Tally {

        private final ProjectContext context;
        private final int candidateCount;
        private final ModelOutcome semanticModel;
        private final List<WalkResult.Issue> scanIssues;
        private final List<Long> unchanged = new ArrayList<>();
        private final List<IndexResult.FileIssue> issues = new ArrayList<>();
        private final List<IndexResult.SemanticIssue> semanticIssues = new ArrayList<>();
        private final ProgressReporter progress;
        private int indexedFiles;
        private int rejectedFiles;
        private int rechunkedFiles;
        private int deletedFiles;
        private long writtenChunks;
        private long embeddedChunks;
        private long discardedEmbeddings;
        private EmbeddingBackfill backfill;
        private EmbeddingCoverage coverage;

        Tally(
                ProjectContext context,
                int candidateCount,
                ModelOutcome semanticModel,
                List<WalkResult.Issue> scanIssues,
                ProgressReporter progress) {
            this.context = context;
            this.progress = progress;
            this.candidateCount = candidateCount;
            this.semanticModel = semanticModel;
            this.scanIssues = scanIssues;
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

        void addIssue(IndexResult.FileIssue issue) {
            issues.add(issue);

            if (issue.rejected()) {
                rejectedFiles++;
            }
        }

        /** Returns what happened to the candidates so far. */
        IndexProgress.FileCounts fileCounts() {
            return new IndexProgress.FileCounts(
                    indexedFiles,
                    unchanged.size(),
                    rejectedFiles,
                    issues.size() - rejectedFiles,
                    writtenChunks,
                    embeddedChunks);
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
                    rechunkedFiles,
                    scanIssues,
                    semanticIssues,
                    progress.timings());
        }
    }

    @FunctionalInterface
    interface StorageOpener {

        SqliteStorage open(ProjectContext context) throws IOException, SQLException;
    }
}
