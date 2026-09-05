package dev.eyuppastirmaci.pecia.cli;

import dev.eyuppastirmaci.pecia.config.PeciaConfigLoader;
import dev.eyuppastirmaci.pecia.config.PeciaConfigParser;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

class IndexCommandTest {

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
    void realIndexingIsStillNotImplemented() {
        int exitCode = run(root.toString());

        assertEquals(1, exitCode);
        assertTrue(err.toString().contains("only --dry-run"));
    }

    private int run(String... args) {
        PeciaConfigLoader loader = new PeciaConfigLoader(new PeciaConfigParser());

        CommandLine commandLine = new CommandLine(new IndexCommand(loader));
        commandLine.setOut(new PrintWriter(out));
        commandLine.setErr(new PrintWriter(err));

        return commandLine.execute(args);
    }
}
