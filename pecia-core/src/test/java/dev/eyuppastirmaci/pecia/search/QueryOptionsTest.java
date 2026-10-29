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
import org.junit.jupiter.params.provider.EnumSource;

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

    @ParameterizedTest(name = "file {0}, option {1} -> available {2}")
    @CsvSource(
            nullValues = "none",
            value = {
                "none, none, true",
                "none, lexical, true",
                "none, auto, true",
                "none, semantic, false",
                "none, hybrid, false",
                "lexical, none, true",
                "lexical, auto, true",
                "lexical, semantic, false",
                "lexical, hybrid, false",
                "semantic, none, false",
                "semantic, lexical, true",
                "semantic, auto, true",
                "semantic, hybrid, false",
                "hybrid, none, false",
                "hybrid, lexical, true"
            })
    void anExplicitModeOverridesTheFileWhichOverridesTheDefault(String file, String option, boolean available)
            throws Exception {
        if (file != null) {
            Files.writeString(root.resolve(".pecia.toml"), "[search]\nmode = \"" + file + "\"\n");
        }

        new IndexService(loader).index(root);
        QueryOptions options = option == null
                ? QueryOptions.configured()
                : QueryOptions.mode(SearchMode.fromConfigValue(option).orElseThrow());

        assertOutcome(available, () -> service.query(root, REQUEST, options).hits());

        if (option == null) {
            assertOutcome(available, () -> service.query(root, REQUEST).hits());
            assertOutcome(available, () -> service.search(root, REQUEST));
        }
    }

    @ParameterizedTest
    @EnumSource(
            value = SearchMode.class,
            names = {"SEMANTIC", "HYBRID"})
    void unavailableModesExplainThemselvesAndKeepTheWarnings(SearchMode mode) throws Exception {
        String config = "[search]\nmode = \"" + mode.configValue() + "\"\n\n[chunk]\nmax_token = 1\n";
        Files.writeString(root.resolve(".pecia.toml"), config);
        new IndexService(loader).index(root);

        QueryException failure = assertThrows(QueryException.class, () -> service.query(root, REQUEST));

        assertEquals(QueryException.Reason.SEMANTIC_UNAVAILABLE, failure.reason());
        assertEquals(
                mode.configValue() + " search is not available yet; use the lexical or auto mode",
                failure.getMessage());
        assertNull(failure.getCause());
        assertEquals(
                List.of("unknown key chunk.max_token (did you mean chunk.max_tokens?)"),
                failure.warnings().stream().map(Warning::message).toList());
    }

    @Test
    void unavailableModesFailBeforeOpeningTheIndexAndWithoutOne() throws Exception {
        new IndexService(loader).index(root);
        AtomicBoolean opened = new AtomicBoolean();
        QueryService tracking = new QueryService(loader, context -> {
            opened.set(true);

            return SqliteStorage.openReadOnly(context.databasePath(), context.projectRoot());
        });

        QueryException failure = assertThrows(
                QueryException.class, () -> tracking.query(root, REQUEST, QueryOptions.mode(SearchMode.SEMANTIC)));

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

    private static void assertOutcome(boolean available, HitsCall query) throws Exception {
        if (available) {
            assertEquals(1, query.hits().size());
            return;
        }

        QueryException failure = assertThrows(QueryException.class, query::hits);
        assertEquals(QueryException.Reason.SEMANTIC_UNAVAILABLE, failure.reason());
    }

    @FunctionalInterface
    private interface HitsCall {

        List<SearchHit> hits() throws Exception;
    }
}
