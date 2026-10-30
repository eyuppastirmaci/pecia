package dev.eyuppastirmaci.pecia.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.eyuppastirmaci.pecia.config.PeciaConfigLoader;
import dev.eyuppastirmaci.pecia.config.PeciaConfigParser;
import dev.eyuppastirmaci.pecia.index.IndexService;
import dev.eyuppastirmaci.pecia.semantic.ModelOutcome;
import dev.eyuppastirmaci.pecia.semantic.ModelProvider;
import dev.eyuppastirmaci.pecia.semantic.SemanticModel;
import dev.eyuppastirmaci.pecia.testing.TinyModel;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

/** {@code index --progress} shows each phase on stderr without touching stdout or mixing with diagnostics. */
class IndexProgressOutputTest {

    @TempDir
    Path root;

    private final StringWriter out = new StringWriter();
    private final StringWriter err = new StringWriter();
    private Terminal.Environment environment = TerminalTest.environment("Linux", false, null, "");
    private ModelProvider models = listener -> TinyModel.cached();

    @BeforeEach
    void writeProject() throws IOException {
        root = root.toRealPath();
        Files.writeString(root.resolve("guide.md"), "# Guide\n\nRefunds take five days.\n");
        Files.writeString(root.resolve("notes.txt"), "Notes about refunds.\n");
    }

    @Test
    void plainProgressListsEachPhaseAndLeavesTheSummaryAlone() {
        assertEquals(0, run(root.toString(), "--progress=plain"));

        assertTrue(out.toString().startsWith("index: "), out.toString());
        assertFalse(out.toString().contains("scanning"), out.toString());
        List<String> lines = stderr();
        // Quick phases are one line each, and nothing was deleted, so there is no cleanup.
        List<String> patterns = List.of("scanning: 2 files found in .+", "indexing: 2 of 2 files in .+");
        assertEquals(patterns.size(), lines.size(), err.toString());

        for (int line = 0; line < patterns.size(); line++) {
            // A slow runner may pass a second or two.
            assertTrue(lines.get(line).matches("\\[0:0\\d] " + patterns.get(line)), lines.get(line));
        }
    }

    @Test
    void autoRedrawsOneLineOnATerminalAndClearsItBeforeDiagnostics() throws IOException {
        Files.write(root.resolve("blob.txt"), new byte[] {0, 1, 0, 1});
        // A terminal without colour, so the diagnostics compare as plain text.
        environment = TerminalTest.environment("Linux", true, null, "NO_COLOR=1");

        assertEquals(1, run(root.toString()));

        String stderr = err.toString().replace("\r\n", "\n");
        assertTrue(stderr.startsWith("\rscanning: "), stderr);
        int cleared = stderr.lastIndexOf('\r');
        // Everything after the last carriage return is diagnostics on lines of their own.
        List<String> after = stderr.substring(cleared + 1).lines().toList();
        assertEquals(2, after.size(), stderr);
        assertTrue(after.get(0).startsWith("warning: blob.txt: BINARY_CONTENT: "), after.get(0));
        assertTrue(after.get(1).startsWith("pecia index: PARTIAL: "), after.get(1));
        assertTrue(stderr.substring(0, cleared).endsWith(" "), "The last line drawn is blanked out");
        // Windows ends stdout lines with CRLF; no other carriage return may reach stdout.
        assertFalse(out.toString().replace("\r\n", "\n").contains("\r"), out.toString());
    }

    @Test
    void aCompleteRunClearsItsLineBeforeTheSummary() {
        environment = TerminalTest.environment("Linux", true, null, "NO_COLOR=1");

        assertEquals(0, run(root.toString()));

        String stderr = err.toString();
        assertTrue(stderr.startsWith("\rscanning: "), stderr);
        assertTrue(stderr.matches("(?s).*\r +\r"), stderr);
    }

    @Test
    void aDownloadNoticeStartsOnALineOfItsOwn() {
        environment = TerminalTest.environment("Linux", true, null, "NO_COLOR=1");
        models = listener -> {
            listener.downloading(SemanticModel.MINILM, root.resolve("cache/model.onnx"));
            ModelOutcome cached = TinyModel.cached();
            return new ModelOutcome(ModelOutcome.Status.DOWNLOADED, cached.model(), cached.file());
        };

        assertEquals(0, run(root.toString(), "--semantic"));

        // The model phase's line is blanked out before the notice.
        assertTrue(err.toString().matches("(?s)\rscanning: .*\r +\rDownloading all-MiniLM-L6-v2 .*"), err.toString());
    }

    @Test
    void autoShowsNothingWhenNotOnATerminalAndNoneNeverDoes() {
        assertEquals(0, run(root.toString()));
        assertEquals("", err.toString());

        environment = TerminalTest.environment("Linux", true, null, "");

        assertEquals(0, run(root.toString(), "--progress=none"));
        assertEquals("", err.toString());
    }

    @Test
    void jsonKeepsStdoutForTheDocumentWhileProgressGoesToStderr() {
        assertEquals(0, run(root.toString(), "--progress=plain", "--json"));

        JsonDocuments.parse(out.toString());
        assertTrue(err.toString().contains("] indexing: 2 of 2 files in "), err.toString());
    }

    @Test
    void dryRunsShowNoProgress() {
        assertEquals(0, run(root.toString(), "--dry-run", "--progress=plain"));

        assertEquals("", err.toString());
    }

    @Test
    void anUnknownModeIsAUsageError() {
        assertEquals(2, run(root.toString(), "--progress=fancy"));

        assertTrue(err.toString().contains("expected one of auto, plain, none but was 'fancy'"), err.toString());
        assertFalse(Files.exists(root.resolve(".pecia")));
    }

    private List<String> stderr() {
        return err.toString().replace("\r\n", "\n").lines().toList();
    }

    private int run(String... args) {
        out.getBuffer().setLength(0);
        err.getBuffer().setLength(0);
        IndexService service = IndexService.withModelProvider(new PeciaConfigLoader(new PeciaConfigParser()), models);
        CommandLine commandLine =
                new CommandLine(new IndexCommand(service, InterruptHandlers.shutdownHooks(), () -> environment));
        commandLine.setOut(new PrintWriter(out));
        commandLine.setErr(new PrintWriter(err));

        return commandLine.execute(args);
    }
}
