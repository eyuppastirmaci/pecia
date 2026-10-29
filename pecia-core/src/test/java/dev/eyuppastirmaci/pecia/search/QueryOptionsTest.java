package dev.eyuppastirmaci.pecia.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.eyuppastirmaci.pecia.config.PeciaConfigLoader;
import dev.eyuppastirmaci.pecia.config.PeciaConfigParser;
import dev.eyuppastirmaci.pecia.config.SearchMode;
import dev.eyuppastirmaci.pecia.diagnostic.Warning;
import dev.eyuppastirmaci.pecia.index.IndexService;
import dev.eyuppastirmaci.pecia.storage.sqlite.SqliteStorage;
import dev.eyuppastirmaci.pecia.testing.LexicalTestCorpus;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class QueryOptionsTest {

    private static final SearchRequest REQUEST = new SearchRequest("modeneedle");

    @TempDir
    Path sandbox;

    private final PeciaConfigLoader loader = new PeciaConfigLoader(new PeciaConfigParser());
    private final QueryService service = new QueryService(loader);
    private Path root;

    @BeforeEach
    void prepareProject() throws IOException {
        root = Files.createDirectories(sandbox.resolve("project")).toRealPath();
        Files.writeString(root.resolve("guide.md"), "# Guide\n\nmodeneedle\n");
    }

    @Test
    void factoriesLeaveTheModeToTheConfigurationOrChooseItExplicitly() {
        assertNull(QueryOptions.configured().mode());
        assertEquals(SearchMode.HYBRID, QueryOptions.mode(SearchMode.HYBRID).mode());
        assertThrows(NullPointerException.class, () -> QueryOptions.mode(null));
    }

    // The lexical index has no embeddings, so a semantic query fails before it looks for the model.
    @ParameterizedTest(name = "file {0}, option {1} -> {2}")
    @CsvSource(
            nullValues = "none",
            value = {
                "none, none, hits",
                "none, lexical, hits",
                "none, auto, hits",
                "none, semantic, EMBEDDINGS_MISSING",
                "none, hybrid, SEMANTIC_UNAVAILABLE",
                "lexical, none, hits",
                "lexical, auto, hits",
                "lexical, semantic, EMBEDDINGS_MISSING",
                "lexical, hybrid, SEMANTIC_UNAVAILABLE",
                "semantic, none, EMBEDDINGS_MISSING",
                "semantic, lexical, hits",
                "semantic, auto, hits",
                "semantic, hybrid, SEMANTIC_UNAVAILABLE",
                "hybrid, none, SEMANTIC_UNAVAILABLE",
                "hybrid, lexical, hits",
                "hybrid, semantic, EMBEDDINGS_MISSING"
            })
    void anExplicitModeOverridesTheFileWhichOverridesTheDefault(String file, String option, String outcome)
            throws Exception {
        if (file != null) {
            Files.writeString(root.resolve(".pecia.toml"), "[search]\nmode = \"" + file + "\"\n");
        }

        new IndexService(loader).index(root);
        QueryOptions options = option == null
                ? QueryOptions.configured()
                : QueryOptions.mode(SearchMode.fromConfigValue(option).orElseThrow());

        assertOutcome(outcome, () -> service.query(root, REQUEST, options).hits());

        if (option == null) {
            assertOutcome(outcome, () -> service.query(root, REQUEST).hits());
            assertOutcome(outcome, () -> service.search(root, REQUEST));
        }
    }

    @Test
    void anUnavailableModeExplainsItselfAndKeepsTheWarnings() throws Exception {
        String config = "[search]\nmode = \"hybrid\"\n\n[chunk]\nmax_token = 1\n";
        Files.writeString(root.resolve(".pecia.toml"), config);
        new IndexService(loader).index(root);

        QueryException failure = assertThrows(QueryException.class, () -> service.query(root, REQUEST));

        assertEquals(QueryException.Reason.SEMANTIC_UNAVAILABLE, failure.reason());
        assertEquals("hybrid search is not available yet; use the lexical or auto mode", failure.getMessage());
        assertNull(failure.getCause());
        assertEquals(
                List.of("unknown key chunk.max_token (did you mean chunk.max_tokens?)"),
                failure.warnings().stream().map(Warning::message).toList());
    }

    @Test
    void anUnavailableModeFailsBeforeOpeningTheIndexAndWithoutOne() throws Exception {
        new IndexService(loader).index(root);
        AtomicBoolean opened = new AtomicBoolean();
        QueryService tracking = new QueryService(loader, context -> {
            opened.set(true);

            return SqliteStorage.openReadOnly(context.databasePath(), context.projectRoot());
        });

        QueryException failure = assertThrows(
                QueryException.class, () -> tracking.query(root, REQUEST, QueryOptions.mode(SearchMode.HYBRID)));

        assertEquals(QueryException.Reason.SEMANTIC_UNAVAILABLE, failure.reason());
        assertFalse(opened.get());
        assertEquals(
                1,
                tracking.query(root, REQUEST, QueryOptions.mode(SearchMode.LEXICAL))
                        .hits()
                        .size());
        assertTrue(opened.get());

        Path unindexed = Files.createDirectories(sandbox.resolve("unindexed")).toRealPath();
        QueryException withoutIndex = assertThrows(
                QueryException.class, () -> service.query(unindexed, REQUEST, QueryOptions.mode(SearchMode.HYBRID)));

        assertEquals(QueryException.Reason.SEMANTIC_UNAVAILABLE, withoutIndex.reason());

        try (Stream<Path> files = Files.list(unindexed)) {
            assertEquals(0, files.count());
        }
    }

    @Test
    void lexicalAndAutoReturnTheSameHitsAsTheConfiguredDefault() throws Exception {
        Path corpus = Files.createDirectories(sandbox.resolve("corpus")).toRealPath();
        LexicalTestCorpus.write(corpus);
        new IndexService(loader).index(corpus);

        for (String text : LexicalTestCorpus.QUERIES) {
            SearchRequest request = new SearchRequest(text, SearchRequest.MAX_LIMIT);
            List<SearchHit> configured = service.search(corpus, request);

            assertFalse(configured.isEmpty(), text);
            assertEquals(
                    configured,
                    service.query(corpus, request, QueryOptions.mode(SearchMode.LEXICAL))
                            .hits());
            assertEquals(
                    configured,
                    service.query(corpus, request, QueryOptions.mode(SearchMode.AUTO))
                            .hits());
        }
    }

    @Test
    void nullOptionsAreRejectedBeforeTheProjectIsResolved() {
        // Resolving a missing directory would fail as READ_FAILED instead.
        Path missing = sandbox.resolve("missing");

        assertThrows(NullPointerException.class, () -> service.query(missing, REQUEST, null));
    }

    private static void assertOutcome(String outcome, HitsCall query) throws Exception {
        if (outcome.equals("hits")) {
            assertEquals(1, query.hits().size());
            return;
        }

        QueryException failure = assertThrows(QueryException.class, query::hits);
        assertEquals(QueryException.Reason.valueOf(outcome), failure.reason());
    }

    @FunctionalInterface
    private interface HitsCall {

        List<SearchHit> hits() throws Exception;
    }
}
