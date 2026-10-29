package dev.eyuppastirmaci.pecia.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.eyuppastirmaci.pecia.config.PeciaConfigLoader;
import dev.eyuppastirmaci.pecia.config.PeciaConfigParser;
import dev.eyuppastirmaci.pecia.config.SearchMode;
import dev.eyuppastirmaci.pecia.index.IndexOptions;
import dev.eyuppastirmaci.pecia.index.IndexResult;
import dev.eyuppastirmaci.pecia.index.IndexService;
import dev.eyuppastirmaci.pecia.semantic.EmbeddingProfile;
import dev.eyuppastirmaci.pecia.semantic.ModelCache;
import dev.eyuppastirmaci.pecia.semantic.ModelOutcome;
import dev.eyuppastirmaci.pecia.semantic.VectorExtensionAccess;
import dev.eyuppastirmaci.pecia.storage.model.EmbeddingCoverage;
import dev.eyuppastirmaci.pecia.storage.sqlite.SqliteStorage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.OptionalInt;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Ranks a small fixed corpus with the real model: questions that share almost no words with the text that answers
 * them find it first. Only the {@code referenceTest} task runs it, with the model in the cache that {@code
 * PECIA_CACHE_DIR} names; it never downloads, and it places the vector extension in its own directory.
 */
@Tag("reference")
class ReferenceRetrievalTest {

    private static final Map<String, String> CORPUS = Map.of(
            "refunds.md",
            "Customers who return a purchase get their money back on the original card within five business days.\n",
            "passwords.md",
            "To recover your account, open the sign-in page and choose the forgotten password link; we email a reset"
                    + " code.\n",
            "shipping.md",
            "Parcels leave our warehouse every weekday morning, and couriers deliver them in two to four days.\n",
            "backups.md",
            "Nightly backups copy the PostgreSQL database to encrypted object storage and keep thirty days of"
                    + " snapshots.\n",
            "weather.md",
            "Heavy rain and strong winds are expected along the coast tomorrow afternoon.\n",
            "cooking.md",
            "Simmer the tomatoes with garlic and olive oil for twenty minutes to make a simple pasta sauce.\n",
            "cats.md",
            "Cats sleep for most of the day and groom themselves carefully after every meal.\n",
            "docs/runbook.md",
            "Deploys run every Tuesday after the staging checks pass. If the build server reports X9Q7ZK, clear the"
                    + " artifact cache and start the pipeline again. Rollbacks restore the previous release from the"
                    + " archive within ten minutes.\n",
            "inventory.md",
            "Warehouse shelves hold the X9, Q7, and ZK barcode scanner models.\n");

    private static final Path RUNBOOK = Path.of("docs", "runbook.md");

    @TempDir
    static Path sandbox;

    private static Path root;
    private static QueryService service;

    @BeforeAll
    static void indexTheCorpusWithTheCachedModel() throws Exception {
        ModelOutcome cached = new ModelCache()
                .find()
                .orElseThrow(() -> new AssertionError(
                        "The retrieval check needs the cached model; set PECIA_CACHE_DIR to a cache that holds it"));
        root = Files.createDirectories(sandbox.resolve("project")).toRealPath();

        for (var file : CORPUS.entrySet()) {
            Path path = root.resolve(file.getKey());
            Files.createDirectories(path.getParent());
            Files.writeString(path, file.getValue());
        }

        PeciaConfigLoader loader = new PeciaConfigLoader(new PeciaConfigParser());
        IndexResult indexed =
                IndexService.withModelProvider(loader, listener -> cached).index(root, IndexOptions.semantic(true));

        assertEquals(CORPUS.size(), indexed.embeddedChunks());

        Path cache = sandbox.resolve("cache");
        service = new QueryService(
                loader,
                context -> SqliteStorage.openReadOnly(context.databasePath(), context.projectRoot()),
                () -> new ModelCache().find(),
                () -> VectorExtensionAccess.library(cache),
                () -> EmbeddingProfile.minilm());
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource(
            delimiter = '|',
            value = {
                "How do I get reimbursed for an item I sent back? | refunds.md",
                "I cannot remember my login credentials | passwords.md",
                "When will my package arrive? | shipping.md",
                "How is our data protected against loss? | backups.md",
                "What is the forecast for the seaside? | weather.md",
                "A recipe for an Italian dish | cooking.md",
                "How do felines behave? | cats.md"
            })
    void aQuestionFindsTheTextThatAnswersItFirst(String question, String answer) throws Exception {
        QueryResult result = service.query(root, new SearchRequest(question), QueryOptions.mode(SearchMode.SEMANTIC));

        assertEquals(new EmbeddingCoverage(CORPUS.size(), 0, 0), result.embeddingCoverage());
        assertEquals(CORPUS.size(), result.hits().size());
        assertEquals(Path.of(answer), result.hits().getFirst().sourcePath(), () -> ranking(result));
        assertTrue(result.hits().getFirst().score().value()
                > result.hits().get(1).score().value());
    }

    @Test
    void anOpaqueCodeInALongerTextIsFoundFirstOnlyWithTheLexicalSide() throws Exception {
        // The code appears once in a paragraph about deploys, while another file names similar models; the model
        // embeds the code's pieces, so semantic search alone prefers the similar names.
        SearchRequest request = new SearchRequest("X9Q7ZK");

        QueryResult semantic = service.query(root, request, QueryOptions.mode(SearchMode.SEMANTIC));
        QueryResult hybrid = service.query(root, request, QueryOptions.mode(SearchMode.HYBRID));

        assertNotEquals(RUNBOOK, semantic.hits().getFirst().sourcePath(), () -> ranking(semantic));
        assertEquals(RUNBOOK, hybrid.hits().getFirst().sourcePath(), () -> ranking(hybrid));
        assertEquals(
                OptionalInt.of(1),
                hybrid.hits().getFirst().fusedRanks().orElseThrow().lexical());
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource(
            delimiter = '|',
            value = {
                "How do I get reimbursed for an item I sent back? | refunds.md",
                "When will my package arrive? | shipping.md",
                "How do felines behave? | cats.md"
            })
    void aQuestionThatLexicalSearchMissesFindsItsAnswerFirstInHybridMode(String question, String answer)
            throws Exception {
        SearchRequest request = new SearchRequest(question);

        QueryResult lexical = service.query(root, request, QueryOptions.mode(SearchMode.LEXICAL));
        QueryResult hybrid = service.query(root, request, QueryOptions.mode(SearchMode.HYBRID));

        assertTrue(lexical.hits().stream().noneMatch(hit -> hit.sourcePath().equals(Path.of(answer))));
        assertEquals(Path.of(answer), hybrid.hits().getFirst().sourcePath(), () -> ranking(hybrid));
    }

    private static String ranking(QueryResult result) {
        StringBuilder ranking = new StringBuilder("ranking:");

        for (SearchHit hit : result.hits()) {
            ranking.append(' ')
                    .append(hit.sourcePath())
                    .append('=')
                    .append(hit.score().value());
        }

        return ranking.toString();
    }
}
