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
