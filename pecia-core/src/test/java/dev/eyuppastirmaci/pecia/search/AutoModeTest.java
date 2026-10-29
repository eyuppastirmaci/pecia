package dev.eyuppastirmaci.pecia.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.eyuppastirmaci.pecia.config.PeciaConfigLoader;
import dev.eyuppastirmaci.pecia.config.PeciaConfigParser;
import dev.eyuppastirmaci.pecia.config.SearchMode;
import dev.eyuppastirmaci.pecia.index.IndexOptions;
import dev.eyuppastirmaci.pecia.index.IndexService;
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
import java.sql.SQLException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** How {@code auto} chooses between hybrid and lexical search, with the tiny model and the bundled extension. */
class AutoModeTest {

    private static final QueryOptions AUTO = QueryOptions.mode(SearchMode.AUTO);
    private static final SearchRequest REQUEST = new SearchRequest("refunds card");
    private static final Map<String, String> FILES = Map.of(
            "payments.md", "Payment validation runs before the order is confirmed.\n",
            "refunds.md", "Refunds are issued to the original card within five days.\n",
            "shipping.md", "Parcels leave the warehouse every weekday morning.\n");

    @TempDir
    Path sandbox;

    private final PeciaConfigLoader loader = new PeciaConfigLoader(new PeciaConfigParser());
    private final AtomicInteger modelLookups = new AtomicInteger();
    private final AtomicInteger libraryLookups = new AtomicInteger();
    private final AtomicInteger profileLookups = new AtomicInteger();
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
    void anIndexWithoutEmbeddingsIsSearchedLexicallyWithoutReachingTheSemanticModel() throws Exception {
        new IndexService(loader).index(root);

        QueryResult result = service(lookup(TinyModel.cached())).query(root, REQUEST, AUTO);

        assertEquals(explicitLexicalHits(), result.hits());
        assertEquals(SearchMode.AUTO, result.requestedMode());
        assertEquals(SearchMode.LEXICAL, result.effectiveMode());
        assertEquals(
                QueryException.Reason.EMBEDDINGS_MISSING,
                result.fallback().orElseThrow().reason());
        assertNull(result.embeddingCoverage());
        assertEquals(0, profileLookups.get(), "Not even the embedding profile is needed");
        assertEquals(0, modelLookups.get());
        assertEquals(0, libraryLookups.get());
        assertFalse(Files.exists(cache));
    }

    @Test
    void anIndexWithEmbeddingsIsSearchedInHybridModeEvenWithPartialCoverage() throws Exception {
        indexSemantically();
        QueryService service = service(lookup(TinyModel.cached()));
        QueryResult hybrid = service.query(root, REQUEST, QueryOptions.mode(SearchMode.HYBRID));

        QueryResult result = service.query(root, REQUEST, AUTO);

        assertEquals(hybrid.hits(), result.hits());
        assertEquals(SearchMode.AUTO, result.requestedMode());
        assertEquals(SearchMode.HYBRID, result.effectiveMode());
        assertEquals(Optional.empty(), result.fallback());
        assertEquals(new EmbeddingCoverage(FILES.size(), 0, 0), result.embeddingCoverage());

        Files.writeString(root.resolve("shipping.md"), "Parcels now leave the warehouse at noon.\n");
        new IndexService(loader).index(root);
        QueryResult partial = service.query(root, REQUEST, AUTO);

        assertEquals(SearchMode.HYBRID, partial.effectiveMode());
        assertEquals(new EmbeddingCoverage(2, 0, 1), partial.embeddingCoverage());
    }

    @Test
    void theConfiguredAutoModeChoosesTheSameWay() throws Exception {
        indexSemantically();

        QueryResult result = service(lookup(TinyModel.cached())).query(root, REQUEST);

        assertEquals(SearchMode.AUTO, result.requestedMode());
        assertEquals(SearchMode.HYBRID, result.effectiveMode());
    }

    @Test
    void anEarlierModelsEmbeddingsFallBackToLexicalSearch() throws Exception {
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

        assertFallsBack(
                service(lookup(TinyModel.cached()), this::library, () -> later),
                QueryException.Reason.EMBEDDINGS_OUTDATED);
        assertEquals(0, modelLookups.get());
    }

    @Test
    void aModelThatIsNotCachedOrCannotBeReadFallsBackToLexicalSearch() throws Exception {
        indexSemantically();
        ModelException unreadable = new ModelException(ModelException.Reason.CACHE_UNAVAILABLE, "unreadable", null);

        assertFallsBack(service(Optional::empty), QueryException.Reason.MODEL_NOT_CACHED);
        assertFallsBack(
                service(() -> {
                    throw unreadable;
                }),
                QueryException.Reason.MODEL_NOT_CACHED);
        assertEquals(0, libraryLookups.get());
    }

    @Test
    void anExtensionThatCannotBePlacedOrLoadedFallsBackToLexicalSearch() throws Exception {
        indexSemantically();
        Path notALibrary = Files.writeString(sandbox.resolve("not-a-library.so"), "not a library");

        assertFallsBack(
                service(
                        lookup(TinyModel.cached()),
                        () -> {
                            throw new VectorExtensionException("No sqlite-vec library is bundled", null);
                        },
                        EmbeddingProfile::minilm),
                QueryException.Reason.VECTOR_SEARCH_UNAVAILABLE);
        assertFallsBack(
                service(lookup(TinyModel.cached()), () -> notALibrary, EmbeddingProfile::minilm),
                QueryException.Reason.VECTOR_SEARCH_UNAVAILABLE);
    }

    @Test
    void anEmbedderThatCannotOpenOrEmbedFallsBackToLexicalSearch() throws Exception {
        indexSemantically();
        // The embedder opens after the extension, whose verified copy is the only file a semantic query writes.
        VectorExtensionAccess.library(cache);

        assertFallsBack(service(lookup(TinyModel.cached("missing-input"))), QueryException.Reason.EMBEDDER_UNAVAILABLE);
        assertFallsBack(service(lookup(TinyModel.cached("non-finite"))), QueryException.Reason.EMBEDDING_FAILED);
    }

    @ParameterizedTest
    @EnumSource(
            value = SearchMode.class,
            names = {"LEXICAL", "SEMANTIC", "HYBRID"})
    void explicitModesReportThemselvesAsRequestedAndEffective(SearchMode mode) throws Exception {
        indexSemantically();

        QueryResult result = service(lookup(TinyModel.cached())).query(root, REQUEST, QueryOptions.mode(mode));

        assertEquals(mode, result.requestedMode());
        assertEquals(mode, result.effectiveMode());
        assertEquals(Optional.empty(), result.fallback());
    }

    @Test
    void indexFailuresStayFailures() throws Exception {
        Path unindexed = Files.createDirectories(sandbox.resolve("unindexed")).toRealPath();
        QueryService broken = new QueryService(
                loader,
                context -> {
                    throw new SQLException("disk I/O error");
                },
                lookup(TinyModel.cached()),
                this::library,
                EmbeddingProfile::minilm);

        assertEquals(
                QueryException.Reason.INDEX_NOT_FOUND,
                assertThrows(
                                QueryException.class,
                                () -> service(lookup(TinyModel.cached())).query(unindexed, REQUEST, AUTO))
                        .reason());
        assertEquals(
                QueryException.Reason.READ_FAILED,
                assertThrows(QueryException.class, () -> broken.query(root, REQUEST, AUTO))
                        .reason());
    }

    /** Requires a lexical result with the given fallback, identical to an explicit lexical query, writing nothing. */
    private void assertFallsBack(QueryService service, QueryException.Reason reason) throws Exception {
        Map<Path, String> project = snapshot(root);
        Map<Path, String> cached = snapshot(cache);

        QueryResult result = service.query(root, REQUEST, AUTO);

        assertEquals(SearchMode.LEXICAL, result.effectiveMode());
        assertEquals(reason, result.fallback().orElseThrow().reason());
        assertFalse(result.fallback().orElseThrow().message().isBlank());
        assertNull(result.embeddingCoverage());
        assertEquals(explicitLexicalHits(), result.hits());
        assertEquals(project, snapshot(root));
        assertEquals(cached, snapshot(cache));
    }

    private List<SearchHit> explicitLexicalHits() throws QueryException {
        return new QueryService(loader)
                .query(root, REQUEST, QueryOptions.mode(SearchMode.LEXICAL))
                .hits();
    }

    private void indexSemantically() throws Exception {
        IndexService.withModelProvider(loader, listener -> TinyModel.cached()).index(root, IndexOptions.semantic(true));
    }

    private QueryService service(ModelLookup models) {
        return service(models, this::library, EmbeddingProfile::minilm);
    }

    private QueryService service(
            ModelLookup models, QueryService.VectorLibrary library, Supplier<EmbeddingProfile> profiles) {
        ModelLookup counted = () -> {
            modelLookups.incrementAndGet();
            return models.find();
        };
        Supplier<EmbeddingProfile> countedProfiles = () -> {
            profileLookups.incrementAndGet();
            return profiles.get();
        };

        return new QueryService(
                loader,
                context -> SqliteStorage.openReadOnly(context.databasePath(), context.projectRoot()),
                counted,
                library,
                countedProfiles);
    }

    private static ModelLookup lookup(ModelOutcome model) {
        return () -> Optional.of(model);
    }

    private Path library() throws VectorExtensionException {
        libraryLookups.incrementAndGet();

        return VectorExtensionAccess.library(cache);
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
