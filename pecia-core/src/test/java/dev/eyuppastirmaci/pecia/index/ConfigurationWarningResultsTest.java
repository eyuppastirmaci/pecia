package dev.eyuppastirmaci.pecia.index;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.eyuppastirmaci.pecia.config.PeciaConfig;
import dev.eyuppastirmaci.pecia.config.PeciaConfigLoader;
import dev.eyuppastirmaci.pecia.config.PeciaConfigParser;
import dev.eyuppastirmaci.pecia.diagnostic.Warning;
import dev.eyuppastirmaci.pecia.search.QueryException;
import dev.eyuppastirmaci.pecia.search.QueryResult;
import dev.eyuppastirmaci.pecia.search.QueryService;
import dev.eyuppastirmaci.pecia.search.SearchRequest;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Configuration warnings reach every indexing, preview, and query outcome without changing settings. */
class ConfigurationWarningResultsTest {

    @TempDir
    Path root;

    private final PeciaConfigLoader loader = new PeciaConfigLoader(new PeciaConfigParser());
    private final IndexService indexService = new IndexService(loader);
    private final QueryService queryService = new QueryService(loader);
    private List<Warning> expected;

    @BeforeEach
    void writeProjectWithAMisspelledKey() throws IOException {
        root = root.toRealPath();
        Path config = Files.writeString(root.resolve(".pecia.toml"), "[chunk]\nmax_token = 64\n");
        Files.writeString(root.resolve("guide.md"), "# Guide\n\nwarningneedle\n");
        expected = List.of(new Warning(
                Warning.Code.UNKNOWN_CONFIG_KEY,
                config,
                new Warning.Position(2, 1),
                "unknown key chunk.max_token (did you mean chunk.max_tokens?)"));
    }

    @Test
    void completeIndexCarriesWarningsAndKeepsTheDefaultSetting() throws Exception {
        IndexResult result = indexService.index(root);

        assertEquals(IndexResult.Status.COMPLETE, result.status());
        assertEquals(expected, result.warnings());
        assertEquals(
                PeciaConfig.defaults().maxTokens(),
                result.context().loadedConfig().config().maxTokens());
    }

    @Test
    void previewCarriesWarnings() throws Exception {
        assertEquals(expected, indexService.preview(root).warnings());
    }

    @Test
    void abortedIndexingCarriesWarnings() {
        IndexService failing = new IndexService(loader, context -> {
            throw new SQLException("storage unavailable");
        });

        IndexException failure = assertThrows(IndexException.class, () -> failing.index(root));

        assertEquals(IndexResult.Status.FAILED, failure.result().status());
        assertEquals(expected, failure.result().warnings());
    }

    @Test
    void queryResultCarriesWarningsAndSearchReturnsTheSameHits() throws Exception {
        indexService.index(root);
        SearchRequest request = new SearchRequest("warningneedle");

        QueryResult result = queryService.query(root, request);

        assertEquals(1, result.hits().size());
        assertEquals(expected, result.warnings());
        assertEquals(result.hits(), queryService.search(root, request));
    }

    @Test
    void queryFailureAfterConfigurationLoadsCarriesWarnings() {
        QueryException failure =
                assertThrows(QueryException.class, () -> queryService.query(root, new SearchRequest("warningneedle")));

        assertEquals(QueryException.Reason.INDEX_NOT_FOUND, failure.reason());
        assertEquals(expected, failure.warnings());
    }

    @Test
    void validConfigurationProducesNoWarnings() throws Exception {
        Files.writeString(root.resolve(".pecia.toml"), "[chunk]\nmax_tokens = 128\n");

        IndexResult indexed = indexService.index(root);

        assertEquals(List.of(), indexed.warnings());
        assertEquals(List.of(), indexService.preview(root).warnings());
        assertEquals(
                List.of(),
                queryService.query(root, new SearchRequest("warningneedle")).warnings());
        assertEquals(128, indexed.context().loadedConfig().config().maxTokens());
    }
}
