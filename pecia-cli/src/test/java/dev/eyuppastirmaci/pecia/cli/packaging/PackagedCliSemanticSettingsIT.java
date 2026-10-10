package dev.eyuppastirmaci.pecia.cli.packaging;

import static dev.eyuppastirmaci.pecia.cli.packaging.PackagedCliAssertions.assertSuccess;
import static dev.eyuppastirmaci.pecia.cli.packaging.PackagedCliAssertions.hitLocations;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.eyuppastirmaci.pecia.cli.packaging.PackagedCli.Result;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Semantic indexing and search mode settings through {@code java -jar}: flags override {@code .pecia.toml} for one
 * run, and a configured mode that is not available fails without touching the index.
 */
class PackagedCliSemanticSettingsIT {

    @TempDir
    Path sandbox;

    private PackagedCli cli;
    private Path project;
    private Path config;

    @BeforeEach
    void prepareProject() throws IOException {
        cli = PackagedCli.create(sandbox);
        project = cli.project();
        config = project.resolve(".pecia.toml");
        Files.writeString(project.resolve("guide.md"), "# Guide\n\nmodeneedle\n");
    }

    @Test
    void dryRunsReportTheSemanticChoiceWithFlagsOverridingTheFile() throws Exception {
        // Dry runs must stay offline whatever semantic indexing resolves to.
        cli.enableOffline();
        Files.writeString(config, "[semantic]\nenabled = true\n");

        assertEquals("semantic: enabled", semanticLine(cli.runReadOnly(project, "index", "--dry-run")));
        assertEquals(
                "semantic: disabled", semanticLine(cli.runReadOnly(project, "index", "--dry-run", "--no-semantic")));

        Files.writeString(config, "[semantic]\nenabled = false\n");

        assertEquals("semantic: enabled", semanticLine(cli.runReadOnly(project, "index", "--dry-run", "--semantic")));
    }

    @Test
    void modeFlagOverridesAConfiguredModeThatIsNotAvailable() throws Exception {
        Files.writeString(config, "[search]\nmode = \"semantic\"\n");
        assertSuccess(cli.run(project, "index"));
        Path database = project.resolve(".pecia/index.db");
        List<Path> entries = cli.projectEntries();
        byte[] before = Files.readAllBytes(database);

        Result configured = cli.run(project, "query", "modeneedle");

        assertEquals(1, configured.exitCode());
        assertEquals("", configured.out());
        assertEquals(
                "pecia query: SEMANTIC_UNAVAILABLE: semantic search is not available yet; use the lexical or auto mode\n",
                configured.err());
        assertEquals(entries, cli.projectEntries());
        assertArrayEquals(before, Files.readAllBytes(database));

        Result lexical = cli.runReadOnly(project, "query", "modeneedle", "--mode", "lexical");

        assertEquals(List.of("guide.md:1-3"), hitLocations(lexical));
    }

    private static String semanticLine(Result result) {
        return result.out().lines().toList().get(1);
    }
}
