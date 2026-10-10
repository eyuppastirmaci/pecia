package dev.eyuppastirmaci.pecia.packaging.consumer;

import static dev.eyuppastirmaci.pecia.packaging.consumer.ConsumerChecks.check;
import static dev.eyuppastirmaci.pecia.packaging.consumer.ConsumerChecks.verifyArchiveOrigins;

import dev.eyuppastirmaci.pecia.config.PeciaConfig;
import dev.eyuppastirmaci.pecia.config.PeciaConfigLoader;
import dev.eyuppastirmaci.pecia.config.PeciaConfigParser;
import dev.eyuppastirmaci.pecia.diagnostic.Warning;
import dev.eyuppastirmaci.pecia.index.IndexOptions;
import dev.eyuppastirmaci.pecia.index.IndexResult;
import dev.eyuppastirmaci.pecia.index.IndexService;
import dev.eyuppastirmaci.pecia.search.QueryResult;
import dev.eyuppastirmaci.pecia.search.QueryService;
import dev.eyuppastirmaci.pecia.search.SearchRequest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Verifies configuration handling through the packaged services. */
final class ConfigurationScenario {

    private ConfigurationScenario() {}

    /** Verifies that an unknown key is reported by preview, indexing, and query results without changing settings. */
    static void verifyConfigurationWarnings(Path root, Path coreJar, Path runtimeDirectory) throws Exception {
        verifyArchiveOrigins(coreJar, runtimeDirectory);
        Path config = Files.writeString(root.resolve(".pecia.toml"), "[chunk]\nmax_token = 64\n");
        Files.writeString(root.resolve("guide.md"), "# Guide\n\nwarningneedle\n");
        PeciaConfigLoader loader = new PeciaConfigLoader(new PeciaConfigParser());
        IndexService indexService = new IndexService(loader);
        List<Warning> expected = List.of(new Warning(
                Warning.Code.UNKNOWN_CONFIG_KEY,
                config.toAbsolutePath().normalize(),
                new Warning.Position(2, 1),
                "unknown key chunk.max_token (did you mean chunk.max_tokens?)"));

        check(
                indexService.preview(root).warnings().equals(expected),
                "Packaged previews must carry configuration warnings");

        IndexResult indexed = indexService.index(root);

        check(
                indexed.status() == IndexResult.Status.COMPLETE
                        && indexed.warnings().equals(expected),
                "Packaged indexing must complete and carry configuration warnings");
        check(
                indexed.context().loadedConfig().config().maxTokens()
                        == PeciaConfig.defaults().maxTokens(),
                "An unknown key must not change packaged settings");

        QueryResult query = new QueryService(loader).query(root, new SearchRequest("warningneedle"));

        check(
                query.hits().size() == 1 && query.warnings().equals(expected),
                "Packaged queries must return hits and configuration warnings");
    }

    /** Verifies that a caller's choice for one run overrides semantic.enabled from the file. */
    static void verifySemanticIndexingOptions(Path root, Path coreJar, Path runtimeDirectory) throws Exception {
        verifyArchiveOrigins(coreJar, runtimeDirectory);
        Files.writeString(root.resolve(".pecia.toml"), "[semantic]\nenabled = true\n");
        Files.writeString(root.resolve("guide.md"), "# Guide\n\nsemanticneedle\n");
        IndexService indexService = new IndexService(new PeciaConfigLoader(new PeciaConfigParser()));

        check(indexService.preview(root).semanticEnabled(), "Packaged previews must apply semantic.enabled");
        check(
                !indexService.preview(root, IndexOptions.semantic(false)).semanticEnabled(),
                "A packaged caller's choice must override semantic.enabled");
        check(
                indexService.index(root, IndexOptions.semantic(true)).status() == IndexResult.Status.COMPLETE,
                "Packaged indexing must complete when the caller chooses semantic indexing");
    }
}
