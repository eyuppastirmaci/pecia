package dev.eyuppastirmaci.pecia.cli.packaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.eyuppastirmaci.pecia.cli.JsonDocuments;
import dev.eyuppastirmaci.pecia.cli.packaging.PackagedCli.Result;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Terminal output through {@code java -jar}, offline: output that goes to a pipe carries no escape codes unless asked
 * for, colour follows {@code --color}, {@code NO_COLOR}, and {@code CLICOLOR_FORCE}, progress follows {@code
 * --progress}, and JSON stays plain.
 */
class PackagedCliTerminalIT {

    private static final String ESCAPE = "\u001b[";
    // Lets Pecia's own rules decide, instead of the plain output the shared options ask picocli for.
    private static final List<String> OWN_RULES = List.of("-Dpicocli.ansi=tty");

    @TempDir
    Path sandbox;

    private PackagedCli cli;
    private Path project;

    @BeforeEach
    void prepareProject() throws Exception {
        cli = PackagedCli.create(sandbox);
        project = cli.project();
        cli.enableOffline();
        Files.writeString(project.resolve(".pecia.toml"), "[chunk]\nmax_token = 64\n");
        Files.writeString(project.resolve("guide.md"), "# Guide\n\nRefunds are paid within five days.\n");
        Files.write(project.resolve("blob.txt"), new byte[] {'b', 0, 'n'});
    }

    @Test
    void pipedOutputHasNoEscapeCodesOrProgress() throws Exception {
        Result index = run(Map.of(), "index");
        Result query = run(Map.of(), "query", "refunds", "--mode", "lexical");

        assertEquals(1, index.exitCode());
        assertEquals(0, query.exitCode(), query.err());

        for (Result result : List.of(index, query)) {
            assertFalse(result.out().contains("\u001b"), result.out());
            assertFalse(result.err().contains("\u001b"), result.err());
            assertFalse(result.err().contains("\r"), result.err());
        }

        // The grouped summary, ending with the run's time.
        List<String> summary = index.out().lines().toList();
        assertEquals(
                "files: 1 indexed, 0 rechunked, 0 unchanged, 0 deleted, 1 rejected, 0 failed (2 found)",
                summary.get(1));
        assertEquals("chunks: 1 written", summary.get(2));
        assertTrue(summary.getLast().matches("time: [0-9.]+ s \\([0-9.,]+ chunks/s\\)"), index.out());
        // Scores are rounded for reading.
        String score = query.out().lines().toList().get(1);
        assertTrue(score.matches("  BM25: -?[0-9.]{1,10}"), score);
    }

    @Test
    void colorAlwaysPaintsResultsSummariesAndLabels() throws Exception {
        Result index = run(Map.of(), "--color=always", "index");
        Result query = run(Map.of(), "query", "refunds", "--mode", "lexical", "--color=always");

        assertTrue(index.out().contains(ESCAPE + "33m1 rejected" + ESCAPE + "0m"), index.out());
        assertTrue(index.err().startsWith(ESCAPE + "33mwarning:" + ESCAPE + "0m "), index.err());
        assertTrue(
                index.err().contains(ESCAPE + "1m" + ESCAPE + "33mpecia index:" + ESCAPE + "0m PARTIAL: "),
                index.err());
        assertTrue(query.out().startsWith(ESCAPE + "1m" + ESCAPE + "35mguide.md" + ESCAPE + "0m"), query.out());
        assertTrue(query.out().contains(ESCAPE + "1m" + ESCAPE + "31mRefunds" + ESCAPE + "0m"), query.out());
    }

    @Test
    void environmentVariablesDecideAutoAndTheFlagDecidesAboveThem() throws Exception {
        assertEquals(1, run(Map.of(), "index").exitCode());

        Result forced = run(Map.of("CLICOLOR_FORCE", "1"), "query", "refunds", "--mode", "lexical");
        Result vetoed = run(Map.of("CLICOLOR_FORCE", "1", "NO_COLOR", "1"), "query", "refunds", "--mode", "lexical");
        Result flagged = run(Map.of("NO_COLOR", "1"), "--color=always", "query", "refunds", "--mode", "lexical");
        Result never = run(Map.of("CLICOLOR_FORCE", "1"), "--color=never", "query", "refunds", "--mode", "lexical");

        assertEquals(0, forced.exitCode(), forced.err());
        assertTrue(forced.out().contains(ESCAPE), forced.out());
        assertFalse(vetoed.out().contains("\u001b"), vetoed.out());
        assertTrue(flagged.out().contains(ESCAPE), flagged.out());
        assertFalse(never.out().contains("\u001b"), never.out());
        assertEquals(vetoed.out(), never.out());
    }

    @Test
    void jsonStaysPlainWhateverTheColour() throws Exception {
        Result index = run(Map.of(), "--color=always", "index", "--json", "--progress=plain");
        Result query = run(Map.of("CLICOLOR_FORCE", "1"), "--color=always", "query", "refunds", "--json");

        assertEquals(0, query.exitCode(), query.err());
        assertEquals(1, JsonDocuments.parse(query.out()).getAsJsonArray("hits").size());

        for (Result result : List.of(query, index)) {
            assertFalse(result.out().contains("\u001b"), result.out());
            JsonDocuments.parse(result.out());
        }

        assertTrue(index.err().contains("] indexing: 2 of 2 files in "), index.err());
    }

    @Test
    void plainProgressListsThePhasesOnStderr() throws Exception {
        Result index = run(Map.of(), "index", "--progress=plain");

        List<String> lines = index.err().lines().toList();
        assertTrue(lines.get(0).matches("\\[0:0\\d] scanning: 2 files found in .+"), index.err());
        assertTrue(lines.get(1).matches("\\[0:0\\d] indexing: 2 of 2 files in .+"), index.err());
        assertTrue(lines.get(2).startsWith("warning: "), index.err());
        assertTrue(index.out().startsWith("index: "), index.out());
    }

    private Result run(Map<String, String> environment, String... arguments) throws Exception {
        return cli.run(project, environment, OWN_RULES, arguments);
    }
}
