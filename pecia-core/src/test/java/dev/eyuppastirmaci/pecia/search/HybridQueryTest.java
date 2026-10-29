package dev.eyuppastirmaci.pecia.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.eyuppastirmaci.pecia.config.PeciaConfigLoader;
import dev.eyuppastirmaci.pecia.config.PeciaConfigParser;
import dev.eyuppastirmaci.pecia.config.SearchMode;
import dev.eyuppastirmaci.pecia.index.IndexOptions;
import dev.eyuppastirmaci.pecia.index.IndexService;
import dev.eyuppastirmaci.pecia.semantic.EmbeddingProfile;
import dev.eyuppastirmaci.pecia.semantic.VectorExtensionAccess;
import dev.eyuppastirmaci.pecia.storage.model.EmbeddingCoverage;
import dev.eyuppastirmaci.pecia.storage.sqlite.SqliteStorage;
import dev.eyuppastirmaci.pecia.testing.TinyModel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Hybrid queries through the query service, with the tiny model and the bundled vector extension. */
class HybridQueryTest {

    private static final QueryOptions HYBRID = QueryOptions.mode(SearchMode.HYBRID);
    private static final String REFUNDS = "Refunds are issued to the original card within five days.";
    private static final Map<String, String> FILES = Map.of(
            "payments.md", "Payment validation runs before the order is confirmed.\n",
            "refunds.md", REFUNDS + "\n",
            "shipping.md", "Parcels leave the warehouse every weekday morning.\n",
            "accounts.md", "Users reset forgotten passwords from the sign-in page before the order.\n");

    @TempDir
    Path sandbox;

    private final PeciaConfigLoader loader = new PeciaConfigLoader(new PeciaConfigParser());
    private Path root;
    private QueryService service;

    @BeforeEach
    void indexSemantically() throws Exception {
        root = Files.createDirectories(sandbox.resolve("project")).toRealPath();

        for (var file : FILES.entrySet()) {
            Files.writeString(root.resolve(file.getKey()), file.getValue());
        }

        IndexService.withModelProvider(loader, listener -> TinyModel.cached()).index(root, IndexOptions.semantic(true));
        Path cache = sandbox.resolve("cache");
        service = new QueryService(
                loader,
                context -> SqliteStorage.openReadOnly(context.databasePath(), context.projectRoot()),
                () -> Optional.of(TinyModel.cached()),
                () -> VectorExtensionAccess.library(cache),
                () -> EmbeddingProfile.minilm());
    }

    @Test
    void aQueryWithAChunksTextRanksThatChunkFirstOnBothSides() throws Exception {
        QueryResult result = service.query(root, new SearchRequest(REFUNDS), HYBRID);
        SearchHit first = result.hits().getFirst();

        assertEquals(new EmbeddingCoverage(FILES.size(), 0, 0), result.embeddingCoverage());
        assertEquals(Path.of("refunds.md"), first.sourcePath());
        assertEquals(SearchScore.Kind.RECIPROCAL_RANK_FUSION, first.score().kind());
        assertEquals(
                new FusedRanks(OptionalInt.of(1), OptionalInt.of(1)),
                first.fusedRanks().orElseThrow());
        assertEquals(2.0 / 61, first.score().value());
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "the order",
                "warehouse",
                "Refunds are issued",
                "card passwords",
                "?!",
                "refunds.md",
                "sign-in page"
            })
    void hitsAreTheFusionOfTheSeparateLexicalAndSemanticQueries(String text) throws Exception {
        List<SearchHit> lexical = service.query(
                        root, new SearchRequest(text, SearchRequest.MAX_LIMIT), QueryOptions.mode(SearchMode.LEXICAL))
                .hits();
        List<SearchHit> semantic = service.query(
                        root, new SearchRequest(text, SearchRequest.MAX_LIMIT), QueryOptions.mode(SearchMode.SEMANTIC))
                .hits();

        QueryResult hybrid = service.query(root, new SearchRequest(text, 3), HYBRID);

        assertEquals(Optional.of(QueryShape.of(text)), hybrid.queryShape());
        assertEquals(RankFusion.fuse(lexical, semantic, 3, QueryShape.of(text)), hybrid.hits());
    }

    @Test
    void anIdentifierQueryKeepsTheLexicalOrder() throws Exception {
        QueryResult result = service.query(root, new SearchRequest("refunds.md"), HYBRID);

        assertEquals(Optional.of(QueryShape.IDENTIFIER), result.queryShape());
        assertEquals(Path.of("refunds.md"), result.hits().getFirst().sourcePath());
        assertEquals(
                OptionalInt.of(1),
                result.hits().getFirst().fusedRanks().orElseThrow().lexical());

        // Every chunk the lexical side found comes before every chunk only the semantic side found.
        List<Boolean> lexicallyFound = result.hits().stream()
                .map(hit -> hit.fusedRanks().orElseThrow().lexical().isPresent())
                .toList();
        assertEquals(
                lexicallyFound.stream().sorted((a, b) -> Boolean.compare(b, a)).toList(), lexicallyFound);
        assertEquals(
                Optional.of(QueryShape.NATURAL_LANGUAGE),
                service.query(root, new SearchRequest("Refunds are issued"), HYBRID)
                        .queryShape());
    }

    @Test
    void textWithoutSearchableCharactersKeepsTheSemanticOrder() throws Exception {
        List<SearchHit> semantic = service.query(root, new SearchRequest("?!"), QueryOptions.mode(SearchMode.SEMANTIC))
                .hits();
        List<SearchHit> hybrid =
                service.query(root, new SearchRequest("?!"), HYBRID).hits();

        assertEquals(
                semantic.stream().map(SearchHit::chunkId).toList(),
                hybrid.stream().map(SearchHit::chunkId).toList());

        for (SearchHit hit : hybrid) {
            assertTrue(hit.fusedRanks().orElseThrow().lexical().isEmpty());
        }
    }

    @Test
    void aChunkWithoutAnEmbeddingCanStillComeFromTheLexicalSide() throws Exception {
        Files.writeString(root.resolve("shipping.md"), "Parcels now leave the warehouse at noon.\n");
        new IndexService(loader).index(root);

        QueryResult result = service.query(root, new SearchRequest("warehouse noon"), HYBRID);

        assertEquals(new EmbeddingCoverage(3, 0, 1), result.embeddingCoverage());
        SearchHit shipping = result.hits().stream()
                .filter(hit -> hit.sourcePath().equals(Path.of("shipping.md")))
                .findFirst()
                .orElseThrow();
        assertEquals(
                new FusedRanks(OptionalInt.of(1), OptionalInt.empty()),
                shipping.fusedRanks().orElseThrow());
        assertEquals(4, result.hits().size());
    }
}
