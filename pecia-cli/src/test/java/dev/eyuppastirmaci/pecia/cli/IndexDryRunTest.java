package dev.eyuppastirmaci.pecia.cli;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.eyuppastirmaci.pecia.config.PeciaConfigLoader;
import dev.eyuppastirmaci.pecia.config.PeciaConfigParser;
import dev.eyuppastirmaci.pecia.index.IndexPreview;
import dev.eyuppastirmaci.pecia.index.IndexService;
import dev.eyuppastirmaci.pecia.semantic.ModelOutcome;
import dev.eyuppastirmaci.pecia.testing.TinyModel;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

/** A dry run prints a summary of what the run would do, then the files it would change, and writes nothing. */
class IndexDryRunTest {

    private static final ModelOutcome TINY = TinyModel.cached();

    @TempDir
    Path root;

    private final StringWriter out = new StringWriter();
    private final StringWriter err = new StringWriter();
    private Path database;

    @BeforeEach
    void writeProject() throws Exception {
        Files.writeString(root.resolve("guide.md"), "# Guide\n\nRefunds take five days.\n");
        Files.writeString(root.resolve("notes.txt"), "Notes about refunds.\n");
        Files.writeString(root.resolve("old.txt"), "Old notes.\n");
        database = root.toRealPath().resolve(".pecia/index.db");
    }

    @Test
    void printsTheSummaryAndTheFilesTheRunWouldChange() throws Exception {
        Files.writeString(root.resolve("long.md"), "# Long\n\n" + "Refunds are paid within five days. ".repeat(120));
        assertEquals(0, run("--no-semantic"));
        Files.writeString(root.resolve("notes.txt"), "Replaced notes about refunds and returns.\n");
        Files.writeString(root.resolve("added.md"), "# Added\n\nA new page.\n");
        Files.delete(root.resolve("old.txt"));
        byte[] before = Files.readAllBytes(database);

        assertEquals(0, run("--dry-run", "--no-semantic"));

        assertEquals("""
                config: defaults (no .pecia.toml found)
                index: %s (current)
                semantic: disabled
                files: 1 new, 1 changed, 0 rechunked, 2 unchanged, 0 rejected, 1 removed
                chunks: ~2 to write, 8 kept, 1 removed

                new:
                  added.md (~1 chunk)
                changed:
                  notes.txt (~1 chunk)
                removed:
                  old.txt (1 chunk)
                """.formatted(database), printed());
        assertEquals("", err.toString());
        assertArrayEquals(before, Files.readAllBytes(database), "The index is unchanged");
    }

    @Test
    void rejectedFilesAreListedWithTheirReasonsAndTheDryRunStillSucceeds() throws Exception {
        assertEquals(0, run("--no-semantic"));
        Files.write(root.resolve("blob.txt"), new byte[] {0, 1, 0, 1});
        Files.delete(root.resolve("old.txt"));

        assertEquals(0, run("--dry-run", "--no-semantic"));

        List<String> lines = printed().lines().toList();
        assertTrue(
                lines.contains("files: 0 new, 0 changed, 0 rechunked, 2 unchanged, 1 rejected, 0 removed"), printed());
        int rejected = lines.indexOf("rejected:");
        assertTrue(rejected > 0, printed());
        assertTrue(lines.get(rejected + 1).startsWith("  blob.txt: BINARY_CONTENT: "), printed());
        // A rejected file keeps the run from deleting, so the deleted file is not listed as removed.
        assertFalse(printed().contains("removed:"), printed());
    }

    @Test
    void otherChunkSettingsListEveryFileAsRechunked() throws Exception {
        assertEquals(0, run("--no-semantic"));
        Files.writeString(root.resolve(".pecia.toml"), "[chunk]\nmax_tokens = 128\noverlap_tokens = 16\n");

        assertEquals(0, run("--dry-run", "--no-semantic"));

        assertTrue(printed().contains("files: 0 new, 0 changed, 3 rechunked, 0 unchanged"), printed());
        assertTrue(printed().contains("\nrechunked:\n  guide.md (~1 chunk)\n"), printed());
    }

    @Test
    void aSemanticDryRunSaysWhatItWouldDownloadAndEmbed() throws Exception {
        assertEquals(0, run("--dry-run", "--semantic"));

        List<String> lines = printed().lines().toList();
        assertTrue(
                lines.contains("model: all-MiniLM-L6-v2 would be downloaded (90.4 MB)"),
                "The test cache never holds the model: " + printed());
        assertTrue(lines.contains("embeddings: ~3 to make, 0 to discard"), printed());
        assertTrue(lines.contains("index: " + database + " (none yet; would be created)"), printed());
        assertFalse(Files.exists(database));
    }

    @Test
    void embeddingsOfAnotherModelVersionAreCountedAsDiscardedAndMadeAgain() throws Exception {
        assertEquals(0, run("--semantic"));
        IndexCommandTest.moveEmbeddingsToAnEarlierProfile(database);

        assertEquals(0, run("--dry-run", "--semantic"));

        assertTrue(printed().contains("\nembeddings: 3 to make, 3 to discard\n"), printed());
    }

    @Test
    void aDamagedIndexFailsTheDryRunAsItFailsTheRun() throws Exception {
        Files.createDirectories(database.getParent());
        Files.writeString(database, "not a database");

        assertEquals(1, run("--dry-run"));

        assertEquals(
                "config: defaults (no .pecia.toml found)\nindex: " + database
                        + " (cannot be read)\nsemantic: disabled\n",
                printed());
        List<String> errors = err.toString().replace("\r\n", "\n").lines().toList();
        assertEquals(2, errors.size(), err.toString());
        assertTrue(errors.get(0).startsWith("pecia index: FAILED: CORRUPT: "), errors.get(0));
        assertEquals(
                "The index is damaged; delete " + database + " and run the command again to rebuild it from your"
                        + " files.",
                errors.get(1));
        assertEquals("not a database", Files.readString(database));
    }

    @Test
    void eachStateHasItsOwnDescription() {
        assertEquals("none yet; would be created", IndexCommand.describe(IndexPreview.IndexState.NONE));
        assertEquals("current", IndexCommand.describe(IndexPreview.IndexState.CURRENT));
        assertEquals("older format; would be upgraded", IndexCommand.describe(IndexPreview.IndexState.UPGRADE));
        assertEquals("cannot be read", IndexCommand.describe(IndexPreview.IndexState.UNREADABLE));
        assertEquals("cached", IndexCommand.describe(semantic(IndexPreview.ModelState.CACHED, 0)));
        assertEquals(
                "would be downloaded (90.4 MB)",
                IndexCommand.describe(semantic(IndexPreview.ModelState.DOWNLOAD, 90_405_214)));
        assertEquals("cache cannot be read", IndexCommand.describe(semantic(IndexPreview.ModelState.UNAVAILABLE, 0)));
    }

    private static IndexPreview.SemanticChanges semantic(IndexPreview.ModelState state, long bytes) {
        return new IndexPreview.SemanticChanges("model", state, bytes, IndexPreview.Count.known(0), 0);
    }

    private int run(String... args) {
        out.getBuffer().setLength(0);
        err.getBuffer().setLength(0);
        IndexService service =
                IndexService.withModelProvider(new PeciaConfigLoader(new PeciaConfigParser()), listener -> TINY);
        CommandLine commandLine = new CommandLine(new IndexCommand(service));
        commandLine.setOut(new PrintWriter(out));
        commandLine.setErr(new PrintWriter(err));

        String[] arguments = new String[args.length + 1];
        arguments[0] = root.toString();
        System.arraycopy(args, 0, arguments, 1, args.length);

        return commandLine.execute(arguments);
    }

    /** Returns standard output with Windows line endings normalized. */
    private String printed() {
        return out.toString().replace("\r\n", "\n");
    }
}
