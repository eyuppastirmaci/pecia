package dev.eyuppastirmaci.pecia.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.eyuppastirmaci.pecia.config.PeciaConfigLoader;
import dev.eyuppastirmaci.pecia.config.PeciaConfigParser;
import dev.eyuppastirmaci.pecia.config.SearchMode;
import dev.eyuppastirmaci.pecia.diagnostic.Warning;
import dev.eyuppastirmaci.pecia.index.IndexOptions;
import dev.eyuppastirmaci.pecia.index.IndexService;
import dev.eyuppastirmaci.pecia.semantic.Embedder;
import dev.eyuppastirmaci.pecia.semantic.EmbeddingException;
import dev.eyuppastirmaci.pecia.semantic.EmbeddingProfile;
import dev.eyuppastirmaci.pecia.semantic.ModelException;
import dev.eyuppastirmaci.pecia.semantic.ModelLookup;
import dev.eyuppastirmaci.pecia.semantic.ModelOutcome;
import dev.eyuppastirmaci.pecia.semantic.VectorExtensionAccess;
import dev.eyuppastirmaci.pecia.semantic.VectorExtensionException;
import dev.eyuppastirmaci.pecia.storage.model.EmbeddingCoverage;
import dev.eyuppastirmaci.pecia.storage.sqlite.SqliteStorage;
import dev.eyuppastirmaci.pecia.testing.TinyModel;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.sqlite.SQLiteConfig;

/** Semantic queries through the query service, with the tiny model and the bundled vector extension. */
class SemanticQueryTest {

    private static final QueryOptions SEMANTIC = QueryOptions.mode(SearchMode.SEMANTIC);
    private static final Map<String, String> FILES = Map.of(
            "payments.md", "Payment validation runs before the order is confirmed.\n",
            "refunds.md", "Refunds are issued to the original card within five days.\n",
            "shipping.md", "Parcels leave the warehouse every weekday morning.\n",
            "accounts.md", "Users reset forgotten passwords from the sign-in page.\n");

    @TempDir
    Path sandbox;

    private final PeciaConfigLoader loader = new PeciaConfigLoader(new PeciaConfigParser());
    private final AtomicInteger modelLookups = new AtomicInteger();
    private final AtomicInteger libraryLookups = new AtomicInteger();
    private Path root;
    private Path cache;

    @BeforeEach
    void prepareProject() throws IOException {
        root = Files.createDirectories(sandbox.resolve("project")).toRealPath();
        cache = sandbox.resolve("cache");

        for (var file : FILES.entrySet()) {
            Files.writeString(root.resolve(file.getKey()), file.getValue());
        }
    }

    @Test
    void aQueryWithAChunksTextFindsThatChunkFirstAndRanksTheRestAsTheirEmbeddingsDo() throws Exception {
        indexSemantically();
        Map<String, String> chunks = storedChunks();
        String text = chunks.get("refunds.md#0");

        QueryResult result = service(TinyModel.cached()).query(root, new SearchRequest(text), SEMANTIC);

        assertEquals(new EmbeddingCoverage(chunks.size(), 0, 0), result.embeddingCoverage());
        assertEquals(javaRanking(chunks, text), keys(result.hits()));
        assertEquals(Path.of("refunds.md"), result.hits().getFirst().sourcePath());
        assertEquals(1.0, result.hits().getFirst().score().value(), 1e-6);

        for (SearchHit hit : result.hits()) {
            assertEquals(SearchScore.Kind.COSINE_SIMILARITY, hit.score().kind());
        }

        assertEquals(
                2,
                service(TinyModel.cached())
                        .query(root, new SearchRequest(text, 2), SEMANTIC)
                        .hits()
                        .size());
    }

    @Test
    void chunksWithoutAnEmbeddingAreNotSearchedAndTheCoverageSaysSo() throws Exception {
        indexSemantically();
        Files.writeString(root.resolve("shipping.md"), "Parcels now leave the warehouse at noon.\n");
        new IndexService(loader).index(root);

        QueryResult result = service(TinyModel.cached())
                .query(root, new SearchRequest("Parcels now leave the warehouse at noon."), SEMANTIC);

        assertEquals(new EmbeddingCoverage(3, 0, 1), result.embeddingCoverage());
        assertEquals(3, result.hits().size());
        assertFalse(keys(result.hits()).contains("shipping.md#0"));
    }

    @ParameterizedTest
    @EnumSource(
            value = SearchMode.class,
            names = {"SEMANTIC", "HYBRID"})
    void anIndexWithoutEmbeddingsFailsBeforeLookingForTheModel(SearchMode mode) throws Exception {
        new IndexService(loader).index(root);

        QueryException failure = assertFailsWithoutChanges(service(TinyModel.cached()), "refunds", mode);

        assertEquals(QueryException.Reason.EMBEDDINGS_MISSING, failure.reason());
        assertEquals(
                "The index has no embeddings for semantic search; index the project semantically first",
                failure.getMessage());
        assertEquals(0, modelLookups.get());
    }

    @ParameterizedTest
    @EnumSource(
            value = SearchMode.class,
            names = {"SEMANTIC", "HYBRID"})
    void embeddingsOfAnotherModelAreReportedAsFromAnEarlierModel(SearchMode mode) throws Exception {
        indexSemantically();
        EmbeddingProfile current = EmbeddingProfile.minilm();
        EmbeddingProfile later = new EmbeddingProfile(
                current.modelId(),
                "later-revision",
                current.modelSha256(),
                current.dimensions(),
                current.tokenizerAlgorithm(),
                current.vocabularySha256(),
                current.maxInputTokens(),
                current.pooling(),
                current.normalization(),
                current.input());
        QueryService service =
                new QueryService(loader, readOnly(), lookup(TinyModel.cached()), this::library, () -> later);

        QueryException failure = assertFailsWithoutChanges(service, "refunds", mode);

        assertEquals(QueryException.Reason.EMBEDDINGS_OUTDATED, failure.reason());
        assertTrue(failure.getMessage().contains("only embeddings of an earlier semantic model"), failure.getMessage());
        assertEquals(0, modelLookups.get());
    }

    @ParameterizedTest
    @EnumSource(
            value = SearchMode.class,
            names = {"SEMANTIC", "HYBRID"})
    void aModelThatIsNotCachedFailsBeforeTheExtensionIsPlaced(SearchMode mode) throws Exception {
        indexSemantically();

        QueryException missing = assertFailsWithoutChanges(service(Optional::empty), "refunds", mode);

        assertEquals(QueryException.Reason.MODEL_NOT_CACHED, missing.reason());
        assertEquals(
                "The semantic model " + EmbeddingProfile.minilm().modelId()
                        + " is not cached; queries never download it, semantic indexing does",
                missing.getMessage());

        ModelException unreadable = new ModelException(ModelException.Reason.CACHE_UNAVAILABLE, "unreadable", null);
        QueryException failed = assertFailsWithoutChanges(
                service(() -> {
                    throw unreadable;
                }),
                "refunds",
                mode);

        assertEquals(QueryException.Reason.MODEL_NOT_CACHED, failed.reason());
        assertEquals(unreadable, failed.getCause());
        assertEquals(0, libraryLookups.get());
    }

    @ParameterizedTest
    @EnumSource(
            value = SearchMode.class,
            names = {"SEMANTIC", "HYBRID"})
    void anExtensionThatCannotBePlacedOrLoadedMakesVectorSearchUnavailable(SearchMode mode) throws Exception {
        indexSemantically();
        VectorExtensionException unplaced = new VectorExtensionException("No sqlite-vec library is bundled", null);
        Path notALibrary = Files.writeString(sandbox.resolve("not-a-library.so"), "not a library");

        QueryException unplacedFailure = assertFailsWithoutChanges(
                new QueryService(
                        loader,
                        readOnly(),
                        lookup(TinyModel.cached()),
                        () -> {
                            throw unplaced;
                        },
                        () -> EmbeddingProfile.minilm()),
                "refunds",
                mode);
        QueryException unloadable = assertFailsWithoutChanges(
                new QueryService(
                        loader,
                        readOnly(),
                        lookup(TinyModel.cached()),
                        () -> notALibrary,
                        () -> EmbeddingProfile.minilm()),
                "refunds",
                mode);

        assertEquals(QueryException.Reason.VECTOR_SEARCH_UNAVAILABLE, unplacedFailure.reason());
        assertEquals("No sqlite-vec library is bundled", unplacedFailure.getMessage());
        assertEquals(unplaced, unplacedFailure.getCause());
        assertEquals(QueryException.Reason.VECTOR_SEARCH_UNAVAILABLE, unloadable.reason());
        assertInstanceOf(SQLException.class, unloadable.getCause());
    }

    @ParameterizedTest
    @EnumSource(
            value = SearchMode.class,
            names = {"SEMANTIC", "HYBRID"})
    void anEmbedderThatCannotOpenOrEmbedFailsWithItsOwnReason(SearchMode mode) throws Exception {
        indexSemantically();
        // The embedder opens after the extension, whose verified copy is the only file a semantic query writes.
        VectorExtensionAccess.library(cache);

        QueryException unopened =
                assertFailsWithoutChanges(service(TinyModel.cached("missing-input")), "refunds", mode);
        QueryException failed = assertFailsWithoutChanges(service(TinyModel.cached("non-finite")), "refunds", mode);

        assertEquals(QueryException.Reason.EMBEDDER_UNAVAILABLE, unopened.reason());
        assertInstanceOf(EmbeddingException.class, unopened.getCause());
        assertEquals(QueryException.Reason.EMBEDDING_FAILED, failed.reason());
        assertInstanceOf(EmbeddingException.class, failed.getCause());
    }

    @ParameterizedTest
    @EnumSource(
            value = SearchMode.class,
            names = {"SEMANTIC", "HYBRID"})
    void anIndexWithoutChunksReturnsNoHitsWithoutLookingForTheModel(SearchMode mode) throws Exception {
        Path empty = Files.createDirectories(sandbox.resolve("empty")).toRealPath();
        new IndexService(loader).index(empty);

        QueryResult result =
                service(TinyModel.cached()).query(empty, new SearchRequest("refunds"), QueryOptions.mode(mode));

        assertEquals(List.of(), result.hits());
        assertEquals(new EmbeddingCoverage(0, 0, 0), result.embeddingCoverage());
        assertEquals(0, modelLookups.get());
    }

    @Test
    void semanticResultsAndFailuresCarryTheConfigurationWarnings() throws Exception {
        Files.writeString(root.resolve(".pecia.toml"), "[search]\nmode = \"semantic\"\n\n[chunk]\nmax_token = 1\n");
        List<String> expected = List.of("unknown key chunk.max_token (did you mean chunk.max_tokens?)");
        new IndexService(loader).index(root);

        QueryException failure = assertThrows(
                QueryException.class, () -> service(TinyModel.cached()).query(root, new SearchRequest("refunds")));

        assertEquals(QueryException.Reason.EMBEDDINGS_MISSING, failure.reason());
        assertEquals(expected, failure.warnings().stream().map(Warning::message).toList());

        indexSemantically();

        assertEquals(
                expected,
                service(TinyModel.cached()).query(root, new SearchRequest("refunds")).warnings().stream()
                        .map(Warning::message)
                        .toList());
    }

    @Test
    void lexicalQueriesOfASemanticIndexNeverLookForTheModelOrExtension() throws Exception {
        indexSemantically();

        QueryResult result = service(TinyModel.cached())
                .query(root, new SearchRequest("refunds"), QueryOptions.mode(SearchMode.LEXICAL));

        assertEquals(List.of("refunds.md#0"), keys(result.hits()));
        assertEquals(
                SearchScore.Kind.SQLITE_BM25, result.hits().getFirst().score().kind());
        assertNull(result.embeddingCoverage());
        assertEquals(SearchMode.LEXICAL, result.effectiveMode());

        assertEquals(0, modelLookups.get());
        assertEquals(0, libraryLookups.get());
        assertFalse(Files.exists(cache));
    }

    private void indexSemantically() throws Exception {
        IndexService.withModelProvider(loader, listener -> TinyModel.cached()).index(root, IndexOptions.semantic(true));
    }

    private QueryService service(ModelOutcome model) {
        return service(lookup(model));
    }

    private QueryService service(ModelLookup models) {
        ModelLookup counted = () -> {
            modelLookups.incrementAndGet();
            return models.find();
        };

        return new QueryService(loader, readOnly(), counted, this::library, () -> EmbeddingProfile.minilm());
    }

    private static QueryService.StorageOpener readOnly() {
        return context -> SqliteStorage.openReadOnly(context.databasePath(), context.projectRoot());
    }

    private static ModelLookup lookup(ModelOutcome model) {
        return () -> Optional.of(model);
    }

    private Path library() throws VectorExtensionException {
        libraryLookups.incrementAndGet();

        return VectorExtensionAccess.library(cache);
    }

    /** Runs a failing semantic query and checks that neither the project nor the cache changed. */
    private QueryException assertFailsWithoutChanges(QueryService service, String text, SearchMode mode)
            throws Exception {
        Map<Path, String> project = snapshot(root);
        Map<Path, String> cached = snapshot(cache);

        QueryException failure = assertThrows(
                QueryException.class, () -> service.query(root, new SearchRequest(text), QueryOptions.mode(mode)));

        assertEquals(project, snapshot(root));
        assertEquals(cached, snapshot(cache));

        return failure;
    }

    private Map<String, String> storedChunks() throws SQLException {
        Map<String, String> chunks = new LinkedHashMap<>();
        SQLiteConfig config = new SQLiteConfig();
        config.setReadOnly(true);

        try (Connection connection = config.createConnection("jdbc:sqlite:" + root.resolve(".pecia/index.db"));
                var rows = connection.createStatement().executeQuery("""
                    SELECT f.source_path || '#' || c.chunk_index, c.content
                    FROM chunks c JOIN files f ON f.id = c.file_id
                    ORDER BY f.source_path, c.chunk_index
                    """)) {
            while (rows.next()) {
                chunks.put(rows.getString(1), rows.getString(2));
            }
        }

        return chunks;
    }

    /** Ranks the chunks by the dot product of their unit embeddings with the query's, as Java computes it. */
    private static List<String> javaRanking(Map<String, String> chunks, String text) throws EmbeddingException {
        List<String> keys = new ArrayList<>(chunks.keySet());
        List<String> texts = new ArrayList<>(List.of(text));
        texts.addAll(chunks.values());
        float[][] embeddings;

        try (Embedder embedder = Embedder.open(TinyModel.cached())) {
            embeddings = embedder.embed(texts);
        }

        Map<String, Double> similarities = new LinkedHashMap<>();

        for (int index = 0; index < keys.size(); index++) {
            double dot = 0;

            for (int dimension = 0; dimension < embeddings[0].length; dimension++) {
                dot += (double) embeddings[0][dimension] * embeddings[index + 1][dimension];
            }

            similarities.put(keys.get(index), dot);
        }

        return similarities.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue(Comparator.reverseOrder()))
                .map(Map.Entry::getKey)
                .toList();
    }

    private static List<String> keys(List<SearchHit> hits) {
        return hits.stream()
                .map(hit -> hit.sourcePath() + "#" + hit.chunkIndex())
                .toList();
    }

    private static Map<Path, String> snapshot(Path directory) throws IOException {
        Map<Path, String> entries = new TreeMap<>();

        if (!Files.exists(directory)) {
            return entries;
        }

        try (Stream<Path> paths = Files.walk(directory)) {
            for (Path path : paths.toList()) {
                entries.put(directory.relativize(path), Files.isDirectory(path) ? "directory" : sha256(path));
            }
        }

        return entries;
    }

    private static String sha256(Path file) throws IOException {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
