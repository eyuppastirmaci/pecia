package dev.eyuppastirmaci.pecia.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dev.eyuppastirmaci.pecia.config.PeciaConfigLoader;
import dev.eyuppastirmaci.pecia.config.PeciaConfigParser;
import dev.eyuppastirmaci.pecia.index.IndexOptions;
import dev.eyuppastirmaci.pecia.index.IndexService;
import dev.eyuppastirmaci.pecia.search.QueryService;
import dev.eyuppastirmaci.pecia.testing.TinyModel;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

/** {@code --color} works before or after the subcommand and colours stderr labels, never JSON. */
class ColorOptionTest {

    private static final String ESCAPE = "\u001b[";
    private static final String TYPO = "[chunk]\nmax_token = 64\n";

    @TempDir
    Path root;

    private final PeciaConfigLoader loader = new PeciaConfigLoader(new PeciaConfigParser());
    private final StringWriter out = new StringWriter();
    private final StringWriter err = new StringWriter();
    private Terminal.Environment environment = TerminalTest.environment("Linux", false, null, "");

    @BeforeEach
    void writeProject() throws IOException {
        root = root.toRealPath();
        Files.writeString(root.resolve(".pecia.toml"), TYPO);
        Files.writeString(root.resolve("guide.md"), "# Guide\n\nneedle\n");
    }

    @Test
    void theOptionWorksBeforeAndAfterTheSubcommand() {
        assertEquals(0, run("--color=always", "index", root.toString()));
        assertTrue(err.toString().startsWith(ESCAPE + "33mwarning:" + ESCAPE + "0m "), err.toString());

        assertEquals(0, run("query", "needle", "--root", root.toString(), "--color", "always"));
        assertTrue(err.toString().startsWith(ESCAPE + "33mwarning:" + ESCAPE + "0m "), err.toString());
    }

    @Test
    void autoColoursOnlyATerminalAndNeverOverridesIt() {
        assertEquals(0, run("index", root.toString()));
        assertFalse(err.toString().contains(ESCAPE), err.toString());

        environment = TerminalTest.environment("Linux", true, null, "");

        assertEquals(0, run("index", root.toString()));
        assertTrue(err.toString().contains(ESCAPE + "33mwarning:"), err.toString());

        assertEquals(0, run("--color=never", "index", root.toString()));
        assertFalse(err.toString().contains(ESCAPE), err.toString());
    }

    @Test
    void failuresAreRedAndPartialRunsYellow() throws IOException {
        assertEquals(1, run("--color=always", "query", "needle", "--root", root.toString()));
        assertTrue(
                err.toString().contains(ESCAPE + "1m" + ESCAPE + "31mpecia query:" + ESCAPE + "0m INDEX_NOT_FOUND: "),
                err.toString());

        Files.write(root.resolve("blob.md"), new byte[] {0, 1, 0, 1});

        assertEquals(1, run("--color=always", "index", root.toString()));
        assertTrue(
                err.toString().contains(ESCAPE + "1m" + ESCAPE + "33mpecia index:" + ESCAPE + "0m PARTIAL: "),
                err.toString());
    }

    @Test
    void theSummaryColoursRejectedAndFailedFilesOnlyWhenThereAreAny() throws IOException {
        assertEquals(0, run("--color=always", "index", root.toString()));
        assertTrue(out.toString().contains(", 0 rejected, 0 failed ("), out.toString());

        Files.write(root.resolve("blob.md"), new byte[] {0, 1, 0, 1});
        Path blocked = Files.writeString(root.resolve("blocked.md"), "secret");
        assumeTrue(Files.getFileStore(blocked).supportsFileAttributeView("posix"), "POSIX permissions unavailable");
        Set<PosixFilePermission> original = Files.getPosixFilePermissions(blocked);

        try {
            Files.setPosixFilePermissions(blocked, Set.of());
            assumeTrue(!Files.isReadable(blocked), "Current account bypasses permissions");

            assertEquals(1, run("--color=always", "index", root.toString()));
        } finally {
            Files.setPosixFilePermissions(blocked, original);
        }

        assertTrue(
                out.toString()
                        .contains(", " + ESCAPE + "33m1 rejected" + ESCAPE + "0m, " + ESCAPE + "31m1 failed" + ESCAPE
                                + "0m ("),
                out.toString());
    }

    @Test
    void searchResultsShowPathsScoresAndMatchesInColour() {
        assertEquals(0, run("index", root.toString()));

        assertEquals(0, run("--color=always", "query", "needle", "--root", root.toString(), "--mode", "lexical"));

        List<String> lines = out.toString().lines().toList();
        assertEquals(
                ESCAPE + "1m" + ESCAPE + "35mguide.md" + ESCAPE + "0m" + ESCAPE + "2m:1-3" + ESCAPE + "0m",
                lines.get(0));
        assertTrue(lines.get(1).matches("  \\u001b\\[2mBM25: -?[0-9.]+\\u001b\\[0m"), lines.get(1));
        assertEquals("  " + ESCAPE + "2mheading: Guide" + ESCAPE + "0m", lines.get(2));
        assertTrue(lines.contains("  " + ESCAPE + "1m" + ESCAPE + "31mneedle" + ESCAPE + "0m"), out.toString());

        String coloured = out.toString();
        assertEquals(0, run("--color=never", "query", "needle", "--root", root.toString(), "--mode", "lexical"));

        assertEquals(coloured.replaceAll("\u001b\\[[0-9]+m", ""), out.toString());
    }

    @Test
    void notesAreCyan() throws Exception {
        IndexService.withModelProvider(loader, listener -> TinyModel.cached()).index(root, IndexOptions.semantic(true));

        // The index has embeddings but the test cache has no model, so auto notes that it searched lexically.
        assertEquals(0, run("--color=always", "query", "needle", "--root", root.toString()));

        List<String> lines = err.toString().lines().toList();
        assertTrue(lines.getLast().startsWith(ESCAPE + "36mnote:" + ESCAPE + "0m "), err.toString());
    }

    @Test
    void jsonStaysPlainWhileStderrMayBeColoured() {
        assertEquals(0, run("--color=always", "index", root.toString(), "--json"));
        assertFalse(out.toString().contains("\u001b"), out.toString());
        assertTrue(err.toString().contains(ESCAPE), err.toString());

        assertEquals(0, run("--color=always", "query", "needle", "--root", root.toString(), "--json"));
        assertFalse(out.toString().contains("\u001b"), out.toString());
        JsonDocuments.parse(out.toString());
    }

    @Test
    void anUnknownValueIsAUsageError() {
        assertEquals(2, run("--color=rainbow", "index", root.toString()));

        assertTrue(err.toString().contains("expected one of auto, always, never but was 'rainbow'"), err.toString());
        assertFalse(Files.exists(root.resolve(".pecia")));
    }

    @Test
    void helpNamesTheOption() {
        assertEquals(0, run("index", "--help"));

        assertTrue(out.toString().contains("--color=<when>"), out.toString());
    }

    private int run(String... args) {
        out.getBuffer().setLength(0);
        err.getBuffer().setLength(0);
        CommandLine.IFactory fallback = CommandLine.defaultFactory();
        CommandLine.IFactory factory = new CommandLine.IFactory() {
            @Override
            public <K> K create(Class<K> type) throws Exception {
                if (type == IndexCommand.class) {
                    return type.cast(new IndexCommand(
                            new IndexService(loader), InterruptHandlers.shutdownHooks(), () -> environment));
                }

                if (type == QueryCommand.class) {
                    return type.cast(new QueryCommand(new QueryService(loader), () -> environment));
                }

                return fallback.create(type);
            }
        };
        CommandLine commandLine = new CommandLine(new PeciaCommand(), factory);
        commandLine.setOut(new PrintWriter(out));
        commandLine.setErr(new PrintWriter(err));

        return commandLine.execute(args);
    }
}
