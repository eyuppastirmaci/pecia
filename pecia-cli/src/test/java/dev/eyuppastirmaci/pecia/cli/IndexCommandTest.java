package dev.eyuppastirmaci.pecia.cli;

import dev.eyuppastirmaci.pecia.config.PeciaConfigLoader;
import dev.eyuppastirmaci.pecia.config.PeciaConfigParser;
import dev.eyuppastirmaci.pecia.index.IndexService;
import dev.eyuppastirmaci.pecia.storage.sqlite.SqliteStorage;
import dev.eyuppastirmaci.pecia.search.SearchRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import picocli.CommandLine;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IndexCommandTest {

    @Test
    void rejectsMissingIndexServiceDuringConstruction() {
        assertThrows(NullPointerException.class, () -> new IndexCommand(null));
    }

    @Test
    void gitRootRulesApplyWithoutConfig() throws IOException {
        Files.createDirectory(root.resolve(".git"));
        Files.writeString(root.resolve(".gitignore"), "drop.md\n");
        Path docs = Files.createDirectory(root.resolve("docs"));
        Files.writeString(docs.resolve("keep.md"), "text");
        Files.writeString(docs.resolve("drop.md"), "text");
        assertEquals(0, run(docs.toString(), "--dry-run"));
        assertTrue(out.toString().contains("1 file(s)"));
        assertFalse(out.toString().contains("drop.md"));
    }

    @Test
    void fileTargetIsRejectedWithoutStackTrace() throws IOException {
        Path file = Files.writeString(root.resolve("file.md"), "text");
        assertEquals(1, run(file.toString(), "--dry-run"));
        assertTrue(err.toString().contains("Target must be a directory"));
        assertFalse(err.toString().contains("\tat "));
    }

    @Test
    void invalidTargetReturnsConciseError() {
        assertEquals(1, run(root.resolve("missing").toString(), "--dry-run"));
        assertTrue(err.toString().contains("pecia index:"));
        assertFalse(err.toString().contains("\tat "));
    }

    @Test
    void malformedGlobReturnsConciseError() throws IOException {
        Files.writeString(root.resolve(".pecia.toml"), "[index]\ninclude = [\"[\"]\n");
        assertEquals(1, run(root.toString(), "--dry-run"));
        assertTrue(err.toString().contains("pecia index:"));
        assertFalse(err.toString().contains("\tat "));
    }

    @Test
    void malformedConfigReturnsConciseError() throws IOException {
        Files.writeString(root.resolve(".pecia.toml"), "[index\ninclude =");

        int exitCode = run(root.toString(), "--dry-run");

        assertEquals(1, exitCode);
        assertTrue(err.toString().contains("pecia index: Invalid .pecia.toml:"));
        assertFalse(err.toString().contains("\tat "));
        assertEquals("", out.toString());
    }

    @Test
    void partialScanListsUsableFilesAndReportsFailure() throws IOException {
        Files.createDirectories(root.resolve("bad/.gitignore"));
        Files.writeString(root.resolve("good.md"), "text");
        assertEquals(1, run(root.toString(), "--dry-run"));
        assertTrue(out.toString().contains("good.md"));
        assertTrue(err.toString().contains("warning:"));
        assertTrue(err.toString().contains("incomplete scan"));
    }

    @Test
    void childTargetUsesConfigRootAndInheritedIgnoreRules() throws IOException {
        Files.writeString(root.resolve(".pecia.toml"), "[index]\ninclude = [\"docs/*.md\"]\n");
        Files.writeString(root.resolve(".gitignore"), "drop.md\n");
        Path docs = Files.createDirectory(root.resolve("docs"));
        Files.writeString(docs.resolve("keep.md"), "text");
        Files.writeString(docs.resolve("drop.md"), "text");
        assertEquals(0, run(docs.toString(), "--dry-run"));
        assertTrue(out.toString().contains("  keep.md"));
        assertFalse(out.toString().contains("drop.md"));
    }

    @Test
    void defaultsDiscoverNewFormatsWithoutWritingAnything() throws IOException {
        Files.writeString(root.resolve("Main.JS"), "text");
        Files.writeString(root.resolve("Dockerfile"), "text");
        Files.writeString(root.resolve("app.min.js"), "text");
        Files.writeString(root.resolve("document.pdf"), "text");
        assertEquals(0, run(root.toString(), "--dry-run"));
        assertTrue(out.toString().contains("2 file(s)"));
        assertTrue(out.toString().contains("Main.JS"));
        assertTrue(out.toString().contains("Dockerfile"));
        assertFalse(Files.exists(root.resolve(".pecia")));
        assertFalse(Files.exists(root.resolve(".pecia.toml")));
    }

    @TempDir
    Path root;

    private final StringWriter out = new StringWriter();
    private final StringWriter err = new StringWriter();

    @Test
    void dryRunListsFilesMatchingTheConfig() throws IOException {
        Files.writeString(root.resolve(".pecia.toml"), "[index]\ninclude = [\"**/*.md\"]\n");
        Files.writeString(root.resolve("notes.md"), "notes");
        Files.writeString(root.resolve("data.bin"), "data");

        int exitCode = run(root.toString(), "--dry-run");

        assertEquals(0, exitCode);
        assertTrue(out.toString().contains("1 file(s) would be indexed:"));
        assertTrue(out.toString().contains("notes.md"));
        assertFalse(out.toString().contains("data.bin"));
    }

    @Test
    void dryRunReportsWhenDefaultsAreUsed() throws IOException {
        Files.writeString(root.resolve("notes.md"), "notes");

        int exitCode = run(root.toString(), "--dry-run");

        assertEquals(0, exitCode);
        assertTrue(out.toString().contains("defaults (no .pecia.toml found)"));
    }

    @Test
    void realIndexingReportsMalformedConfigBeforeCreatingAnIndex() throws IOException {
        Files.writeString(root.resolve(".pecia.toml"), "[index\ninclude =");

        int exitCode = run(root.toString());

        assertEquals(1, exitCode);
        assertTrue(err.toString().startsWith("pecia index: Invalid .pecia.toml:"));
        assertEquals("", out.toString());
        assertFalse(Files.exists(root.resolve(".pecia")));
    }

    @Test
    void indexesAndReportsCountersThroughCommandWriters() throws Exception {
        Files.writeString(root.resolve("a.txt"), "needle");
        Files.writeString(root.resolve("empty.txt"), "");

        assertEquals(0, run(root.toString()));
        assertEquals("", err.toString());
        assertEquals(String.join(System.lineSeparator(),
                "index: " + root.resolve(".pecia/index.db"), "candidates: 2", "indexed: 2",
                "chunks: 1", "rejected: 0", "failed: 0", ""), out.toString());

        try (SqliteStorage storage = SqliteStorage.openReadOnly(root.resolve(".pecia/index.db"), root)) {
            assertEquals(1, storage.lexicalSearch().search(new SearchRequest("needle")).size());
        }
    }

    @Test
    void emptyProjectIsSuccessful() {
        assertEquals(0, run(root.toString()));
        assertTrue(out.toString().contains("indexed: 0"));
        assertEquals("", err.toString());
        assertTrue(Files.isRegularFile(root.resolve(".pecia/index.db")));
    }

    @Test
    void fileRejectionReportsPartialCountersAndReasonWithoutStackTrace() throws IOException {
        Files.writeString(root.resolve("good.txt"), "good");
        Files.writeString(root.resolve("bad.txt"), "binary\0");

        assertEquals(1, run(root.toString()));
        assertTrue(out.toString().contains("candidates: 2"));
        assertTrue(out.toString().contains("indexed: 1"));
        assertTrue(out.toString().contains("rejected: 1"));
        assertTrue(err.toString().contains("warning: bad.txt: BINARY_CONTENT:"));
        assertFalse(out.toString().contains("warning:"));
        assertFalse(err.toString().contains("\tat "));
    }

    @Test
    void incompleteRealScanReportsIssuesWithoutCreatingStorage() throws IOException {
        Path bad = Files.createDirectories(root.resolve("bad/.gitignore"));
        Files.writeString(root.resolve("good.txt"), "good");

        assertEquals(1, run(root.toString()));
        assertTrue(out.toString().contains("indexed: 0"));
        assertTrue(err.toString().contains("warning: " + bad));
        assertTrue(err.toString().contains("pecia index: Indexing requires a complete scan"));
        assertFalse(err.toString().contains("\tat "));
        assertFalse(Files.exists(root.resolve(".pecia")));
    }

    @Test
    void storageFailureIncludesUsefulCauseWithoutStackTrace() throws IOException {
        Path database = root.resolve(".pecia/index.db");
        Files.createDirectory(database.getParent());
        byte[] corrupt = "not a database".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Files.write(database, corrupt);

        assertEquals(1, run(root.toString()));
        assertTrue(err.toString().contains("pecia index: Index storage operation failed:"));
        assertTrue(err.toString().contains("not a database"));
        assertFalse(err.toString().contains("\tat "));
        assertTrue(out.toString().contains("indexed: 0"));
        org.junit.jupiter.api.Assertions.assertArrayEquals(corrupt, Files.readAllBytes(database));
    }

    @Test
    void invalidTokenBudgetFailsRealIndexButDoesNotAffectDryRun() throws IOException {
        Files.writeString(root.resolve(".pecia.toml"), "[index]\ninclude = ['*.txt']\n[chunk]\nmax_tokens = 512\n");
        Files.writeString(root.resolve("a.txt"), "needle");

        assertEquals(1, run(root.toString()));
        assertFalse(Files.exists(root.resolve(".pecia")));

        out.getBuffer().setLength(0);
        err.getBuffer().setLength(0);

        assertEquals(0, run(root.toString(), "--dry-run"));
        assertTrue(out.toString().contains("1 file(s) would be indexed"));
        assertFalse(Files.exists(root.resolve(".pecia")));
    }

    @Test
    void helpAndUsageErrorsDoNotOpenStorage() {
        assertEquals(0, run(root.toString(), "--help"));
        assertTrue(out.toString().contains("local lexical index"));
        assertFalse(out.toString().contains("embeds"));
        assertFalse(Files.exists(root.resolve(".pecia")));

        out.getBuffer().setLength(0);
        err.getBuffer().setLength(0);

        assertEquals(2, run(root.toString(), "--unknown-option"));
        assertTrue(err.toString().contains("Unknown option"));
        assertFalse(Files.exists(root.resolve(".pecia")));
    }

    @Test
    void omittedTargetDefaultsToTheWorkingDirectory() {
        IndexCommand command = new IndexCommand(new IndexService(new PeciaConfigLoader(new PeciaConfigParser())));
        new CommandLine(command).parseArgs();

        assertEquals(Path.of("."), command.path);
    }

    private int run(String... args) {
        PeciaConfigLoader loader = new PeciaConfigLoader(new PeciaConfigParser());

        CommandLine commandLine = new CommandLine(new IndexCommand(new IndexService(loader)));
        commandLine.setOut(new PrintWriter(out));
        commandLine.setErr(new PrintWriter(err));

        return commandLine.execute(args);
    }
}
