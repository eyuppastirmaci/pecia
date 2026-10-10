package dev.eyuppastirmaci.pecia.cli.packaging;

import static dev.eyuppastirmaci.pecia.cli.packaging.PackagedCliAssertions.assertNoStackTrace;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.eyuppastirmaci.pecia.cli.packaging.PackagedCli.Result;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Configuration warnings through {@code java -jar}: they appear only on stderr, and every command otherwise behaves
 * exactly as it does for the same project without the unknown key.
 */
class PackagedCliWarningsIT {

    // The misspelled key is ignored, so both configurations apply the same settings.
    private static final String TYPO_CONFIG = "[chunk]\nmax_token = 64\n";
    private static final String VALID_CONFIG = "[chunk]\n";

    @TempDir
    Path sandbox;

    private PackagedCli cli;
    private Path project;
    private Path config;

    @BeforeEach
    void prepareProject() throws IOException {
        cli = PackagedCli.create(sandbox);
        project = cli.project();
        config = Files.writeString(project.resolve(".pecia.toml"), TYPO_CONFIG);
        Files.writeString(project.resolve("guide.md"), "# Guide\n\nwarningneedle\n");
    }

    @Test
    void everyCommandWarnsOnStderrAndOtherwiseBehavesAsWithoutTheTypo() throws Exception {
        List<Result> withTypo = runEveryCommand();

        for (Result result : withTypo) {
            assertEquals(0, result.exitCode(), result.err());
            assertEquals(typoWarning() + "\n", result.err());
        }

        Files.writeString(config, VALID_CONFIG);
        deleteIndex();
        List<Result> withoutTypo = runEveryCommand();

        for (int command = 0; command < withTypo.size(); command++) {
            Result clean = withoutTypo.get(command);
            assertEquals(0, clean.exitCode(), clean.err());
            assertEquals("", clean.err());
            assertEquals(withTypo.get(command).out(), clean.out(), "Warnings must not change stdout");
        }
    }

    @Test
    void failedQueryPrintsTheWarningBeforeTheError() throws Exception {
        Result result = cli.run(project, "query", "warningneedle");

        assertEquals(1, result.exitCode());
        assertEquals("", result.out());
        List<String> lines = result.err().lines().toList();
        assertEquals(typoWarning(), lines.get(0));
        assertTrue(lines.get(1).startsWith("pecia query: INDEX_NOT_FOUND:"), lines.get(1));
        assertNoStackTrace(result.err());
    }

    /** Runs a dry run, a first index, and a query, in that order. */
    private List<Result> runEveryCommand() throws Exception {
        return List.of(
                cli.run(project, "index", "--dry-run"),
                cli.run(project, "index"),
                cli.run(project, "query", "warningneedle"));
    }

    private String typoWarning() {
        return "warning: " + config + ":2:1: unknown key chunk.max_token (did you mean chunk.max_tokens?)";
    }

    private void deleteIndex() throws IOException {
        try (Stream<Path> paths = Files.walk(project.resolve(".pecia"))) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        }
    }
}
