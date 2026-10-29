package dev.eyuppastirmaci.pecia.search;

import dev.eyuppastirmaci.pecia.config.PeciaConfigLoader;
import dev.eyuppastirmaci.pecia.config.SearchMode;
import dev.eyuppastirmaci.pecia.diagnostic.Warning;
import dev.eyuppastirmaci.pecia.project.ProjectContext;
import dev.eyuppastirmaci.pecia.project.ProjectContextResolver;
import dev.eyuppastirmaci.pecia.semantic.Embedder;
import dev.eyuppastirmaci.pecia.semantic.EmbeddingException;
import dev.eyuppastirmaci.pecia.semantic.EmbeddingProfile;
import dev.eyuppastirmaci.pecia.semantic.ModelCache;
import dev.eyuppastirmaci.pecia.semantic.ModelException;
import dev.eyuppastirmaci.pecia.semantic.ModelLookup;
import dev.eyuppastirmaci.pecia.semantic.ModelOutcome;
import dev.eyuppastirmaci.pecia.semantic.VectorExtension;
import dev.eyuppastirmaci.pecia.semantic.VectorExtensionException;
import dev.eyuppastirmaci.pecia.storage.model.EmbeddingCoverage;
import dev.eyuppastirmaci.pecia.storage.sqlite.IndexAccessException;
import dev.eyuppastirmaci.pecia.storage.sqlite.SqliteStorage;
import java.io.IOException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/** Resolves project configuration and queries an existing index without scanning source files. */
public final class QueryService {

    private final ProjectContextResolver contextResolver;
    private final StorageOpener storageOpener;
    private final ModelLookup models;
    private final VectorLibrary vectorLibrary;
    private final Supplier<EmbeddingProfile> profiles;

    /**
     * Creates a service that resolves configuration, opens existing indexes read-only, and finds the semantic model in
     * the user's cache. Only semantic queries look for the model, and none downloads it.
     */
    public QueryService(PeciaConfigLoader configLoader) {
        this(configLoader, QueryService::openStorage);
    }

    /**
     * Creates a query service that obtains the semantic model from the given lookup, for callers that manage the model
     * themselves and for tests.
     *
     * @throws NullPointerException if an argument is null
     */
    public static QueryService withModelLookup(PeciaConfigLoader configLoader, ModelLookup models) {
        return new QueryService(configLoader, QueryService::openStorage, models);
    }

    private static SqliteStorage openStorage(ProjectContext context) throws IOException, SQLException {
        return SqliteStorage.openReadOnly(context.databasePath(), context.projectRoot());
    }

    QueryService(PeciaConfigLoader configLoader, StorageOpener storageOpener) {
        // Lambdas rather than method references, so semantic classes are reached only when a semantic query runs.
        this(configLoader, storageOpener, () -> new ModelCache().find());
    }

    QueryService(PeciaConfigLoader configLoader, StorageOpener storageOpener, ModelLookup models) {
        this(configLoader, storageOpener, models, () -> VectorExtension.library(), () -> EmbeddingProfile.minilm());
    }

    QueryService(
            PeciaConfigLoader configLoader,
            StorageOpener storageOpener,
            ModelLookup models,
            VectorLibrary vectorLibrary,
            Supplier<EmbeddingProfile> profiles) {
        contextResolver = new ProjectContextResolver(configLoader);
        this.storageOpener = Objects.requireNonNull(storageOpener, "storageOpener");
        this.models = Objects.requireNonNull(models, "models");
        this.vectorLibrary = Objects.requireNonNull(vectorLibrary, "vectorLibrary");
        this.profiles = Objects.requireNonNull(profiles, "profiles");
    }

    /**
     * Resolves the project from a starting directory and searches its complete existing index. The
     * starting directory does not filter hits to a subtree. No source content is reread. Owns and
     * closes the read-only connection on success and failure; never creates or upgrades an index.
     * Even punctuation-only queries require a valid existing index, then return an empty list.
     *
     * @param startDirectory project context starting directory, usually the caller's working
     *     directory
     * @param request the same validated plain-text request accepted by LexicalSearch
     * @return immutable ranked hits; no matches is a successful empty result
     * @throws QueryException if a semantic prerequisite of the configured mode is missing or context/index access or
     *     retrieval fails, preserving the cause
     * @throws IllegalArgumentException if query/configuration/path validation fails
     * @throws NullPointerException if a required argument is null
     */
    public List<SearchHit> search(Path startDirectory, SearchRequest request) throws QueryException {
        return query(startDirectory, request).hits();
    }

    /**
     * Searches like {@link #search} and also returns the configuration warnings found while resolving the project.
     * A {@link QueryException} raised after the configuration loaded carries the same warnings. The search mode comes
     * from {@code search.mode}, as described for {@link #query(Path, SearchRequest, QueryOptions)}.
     *
     * @param startDirectory project context starting directory, usually the caller's working directory
     * @param request the same validated plain-text request accepted by LexicalSearch
     * @return immutable ranked hits and configuration warnings
     * @throws QueryException if a semantic prerequisite of the configured mode is missing or context/index access or
     *     retrieval fails, preserving the cause
     * @throws IllegalArgumentException if query/configuration/path validation fails
     * @throws NullPointerException if a required argument is null
     */
    public QueryResult query(Path startDirectory, SearchRequest request) throws QueryException {
        return query(startDirectory, request, QueryOptions.configured());
    }

    /**
     * Searches like {@link #query(Path, SearchRequest)} in the mode the caller chooses, which overrides {@code
     * search.mode}. {@link SearchMode#LEXICAL} and {@link SearchMode#AUTO} search lexically.
     *
     * <p>{@link SearchMode#SEMANTIC} ranks the chunks that have an embedding of the current model by their similarity
     * to the embedded query text. {@link SearchMode#HYBRID} fuses that ranking with the lexical one by reciprocal rank
     * fusion, as {@link HybridSearch} describes, so chunks without an embedding can still come from the lexical side.
     * Both report the embedding coverage in the result and check the same prerequisites in order, each failing with its
     * own reason and without writing anything: stored embeddings of the current model ({@link
     * QueryException.Reason#EMBEDDINGS_MISSING}), the cached model, which is never downloaded ({@link
     * QueryException.Reason#MODEL_NOT_CACHED}), the vector search extension ({@link
     * QueryException.Reason#VECTOR_SEARCH_UNAVAILABLE}), and the embedder ({@link
     * QueryException.Reason#EMBEDDER_UNAVAILABLE}, then {@link QueryException.Reason#EMBEDDING_FAILED}). Only the
     * extension's verified copy in the user's cache is written, once. An index without chunks returns no hits.
     *
     * @param startDirectory project context starting directory, usually the caller's working directory
     * @param request the same validated plain-text request accepted by LexicalSearch
     * @param options the caller's choices for this query
     * @return immutable ranked hits and configuration warnings
     * @throws QueryException if a semantic prerequisite is missing or context/index access or retrieval fails,
     *     preserving the cause
     * @throws IllegalArgumentException if query/configuration/path validation fails
     * @throws NullPointerException if a required argument is null, before any filesystem access
     */
    public QueryResult query(Path startDirectory, SearchRequest request, QueryOptions options) throws QueryException {
        // Apply the existing compiler's limits before any filesystem or database access.
        new LexicalQueryCompiler().compile(request);
        Objects.requireNonNull(options, "options");
        List<Warning> warnings = List.of();

        try {
            ProjectContext context = contextResolver.resolve(startDirectory);
            warnings = context.loadedConfig().warnings();
            SearchMode mode = options.resolveMode(context.loadedConfig().config());

            try (SqliteStorage storage = storageOpener.open(context)) {
                return switch (mode) {
                    // Auto stays lexical until it can choose by what the index holds.
                    case LEXICAL, AUTO ->
                        new QueryResult(storage.lexicalSearch().search(request), warnings);
                    case SEMANTIC, HYBRID -> embeddingQuery(storage, request, mode, warnings);
                };
            }
        } catch (IndexAccessException failure) {
            QueryException.Reason reason =
                    switch (failure.reason()) {
                        case NOT_FOUND -> QueryException.Reason.INDEX_NOT_FOUND;
                        case MIGRATION_REQUIRED -> QueryException.Reason.MIGRATION_REQUIRED;
                        case INCOMPATIBLE -> QueryException.Reason.INCOMPATIBLE_INDEX;
                        case WRONG_PROJECT -> QueryException.Reason.WRONG_PROJECT;
                        case CORRUPT_INDEX -> QueryException.Reason.CORRUPT_INDEX;
                    };

            throw new QueryException(reason, failure.getMessage(), failure, warnings);
        } catch (IOException | SQLException | SearchException failure) {
            throw new QueryException(
                    QueryException.Reason.READ_FAILED, "Could not query project index", failure, warnings);
        }
    }

    /** Answers a semantic or hybrid query once the prerequisites both modes share are in place. */
    private QueryResult embeddingQuery(
            SqliteStorage storage, SearchRequest request, SearchMode mode, List<Warning> warnings)
            throws QueryException, SQLException, SearchException {
        EmbeddingProfile profile = profiles.get();
        EmbeddingCoverage coverage = storage.embeddingCoverage(profile);

        if (coverage.chunks() == 0) {
            // Nothing is stored to compare, like a lexical query that matches nothing.
            return new QueryResult(List.of(), warnings, coverage);
        }

        if (coverage.embedded() == 0) {
            String message = coverage.stale() > 0
                    ? "The index holds only embeddings of an earlier semantic model; index the project semantically to"
                            + " rebuild them"
                    : "The index has no embeddings for semantic search; index the project semantically first";

            throw new QueryException(QueryException.Reason.EMBEDDINGS_MISSING, message, null, warnings);
        }

        ModelOutcome model = findModel(profile, warnings);
        loadVectorExtension(storage, warnings);
        float[] queryEmbedding = embedQuery(model, request.query(), warnings);
        List<SearchHit> hits = mode == SearchMode.HYBRID
                ? storage.hybridSearch(profile).search(request, queryEmbedding)
                : storage.semanticSearch(profile).search(queryEmbedding, request.limit());

        return new QueryResult(hits, warnings, coverage);
    }

    private ModelOutcome findModel(EmbeddingProfile profile, List<Warning> warnings) throws QueryException {
        Optional<ModelOutcome> model;

        try {
            model = models.find();
        } catch (ModelException failure) {
            throw new QueryException(
                    QueryException.Reason.MODEL_NOT_CACHED,
                    "Could not read the semantic model from the cache",
                    failure,
                    warnings);
        }

        return model.orElseThrow(() -> new QueryException(
                QueryException.Reason.MODEL_NOT_CACHED,
                "The semantic model " + profile.modelId() + " is not cached; queries never download it, semantic"
                        + " indexing does",
                null,
                warnings));
    }

    private void loadVectorExtension(SqliteStorage storage, List<Warning> warnings) throws QueryException {
        try {
            storage.loadVectorExtension(vectorLibrary.locate());
        } catch (VectorExtensionException failure) {
            throw new QueryException(
                    QueryException.Reason.VECTOR_SEARCH_UNAVAILABLE, failure.getMessage(), failure, warnings);
        } catch (SQLException failure) {
            throw new QueryException(
                    QueryException.Reason.VECTOR_SEARCH_UNAVAILABLE,
                    "Could not load the sqlite-vec extension for semantic search",
                    failure,
                    warnings);
        }
    }

    private static float[] embedQuery(ModelOutcome model, String text, List<Warning> warnings) throws QueryException {
        Embedder embedder;

        try {
            embedder = Embedder.open(model);
        } catch (EmbeddingException failure) {
            throw new QueryException(
                    QueryException.Reason.EMBEDDER_UNAVAILABLE,
                    "Semantic search could not open its embedder",
                    failure,
                    warnings);
        }

        try (embedder) {
            return embedder.embed(List.of(text))[0];
        } catch (EmbeddingException failure) {
            throw new QueryException(
                    QueryException.Reason.EMBEDDING_FAILED, "Could not embed the query text", failure, warnings);
        }
    }

    @FunctionalInterface
    interface StorageOpener {

        SqliteStorage open(ProjectContext context) throws IOException, SQLException;
    }

    /** Places the vector search extension on disk; a seam over {@link VectorExtension#library()} for tests. */
    @FunctionalInterface
    interface VectorLibrary {

        Path locate() throws VectorExtensionException;
    }
}
