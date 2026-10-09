package dev.eyuppastirmaci.pecia.cli.packaging;

import static dev.eyuppastirmaci.pecia.cli.packaging.PackagedCliAssertions.assertConciseFailure;
import static dev.eyuppastirmaci.pecia.cli.packaging.PackagedCliAssertions.assertNoStackTrace;
import static dev.eyuppastirmaci.pecia.cli.packaging.PackagedCliAssertions.assertSuccess;
import static dev.eyuppastirmaci.pecia.cli.packaging.PackagedCliAssertions.initialized;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.eyuppastirmaci.pecia.cli.packaging.PackagedCli.Result;
import dev.eyuppastirmaci.pecia.testing.OfflineSandbox;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PackagedCliDiscoveryIT {

    @TempDir
    Path sandbox;

    private PackagedCli cli;
    private Path project;

    @BeforeEach
    void prepareProject() throws IOException {
        cli = PackagedCli.create(sandbox);
        project = cli.project();
    }

    @Test
    void initWritesDefaultsAndPreservesAnExistingConfig() throws Exception {
        Path config = project.resolve(".pecia.toml");

        Result first = cli.run(project, "init");

        assertSuccess(first);
        assertEquals("Wrote " + config + "\n", first.out());
        String defaults = Files.readString(config);
        assertTrue(defaults.contains("[index]\ninclude = ["));
        assertTrue(defaults.contains("\nexclude = ["));
        assertTrue(defaults.contains("max_file_bytes = 5242880\n"));
        assertTrue(defaults.contains("[chunk]\nmax_tokens = 256\noverlap_tokens = 32\n"));
        assertTrue(defaults.contains("[embed]\nconcurrency = 2\n"));
        assertTrue(defaults.contains("[store]\npath = \".pecia/index.db\"\n"));
        byte[] customized = "# Preserve my settings and line endings.\r\n[index]\r\ninclude = [\"**/*.md\"]\r\n"
                .getBytes(StandardCharsets.UTF_8);
        Files.write(config, customized);

        Result repeated = cli.run(project, "init");

        assertSuccess(repeated);
        assertEquals(".pecia.toml already exists, left unchanged.\n", repeated.out());
        assertArrayEquals(customized, Files.readAllBytes(config));
        assertFalse(Files.exists(project.resolve(".pecia")));
    }

    @Test
    void defaultDryRunDiscoversFilesWithoutWritingOutput() throws Exception {
        Files.writeString(project.resolve("notes.md"), "notes");
        Files.writeString(project.resolve("Main.JS"), "source");
        Files.writeString(project.resolve("Dockerfile"), "FROM scratch");
        Files.writeString(project.resolve("app.min.js"), "excluded source");
        Files.writeString(project.resolve("document.pdf"), "unsupported");
        List<Path> before = cli.projectEntries();

        Result result = cli.run(project, "index", ".", "--dry-run");

        assertSuccess(result);
        assertEquals("""
            config: defaults (no .pecia.toml found)
            3 file(s) would be indexed:
              Dockerfile
              Main.JS
              notes.md
            """, result.out());
        assertEquals(before, cli.projectEntries());
        assertFalse(Files.exists(project.resolve(".pecia.toml")));
        assertFalse(Files.exists(project.resolve(".pecia")));
    }

    @Test
    void childDryRunUsesProjectConfigAndInheritedIgnoreRules() throws Exception {
        String config = """
            [index]
            include = ["docs/*.md"]
            exclude = ["docs/excluded.md"]
            """;
        Files.writeString(project.resolve(".pecia.toml"), config);
        Files.writeString(project.resolve(".gitignore"), "ignored.md\n");
        Files.writeString(project.resolve("outside.md"), "outside target");
        Path docs = Files.createDirectory(project.resolve("docs"));
        Files.writeString(docs.resolve(".gitignore"), "local.md\n");

        for (String name : List.of("keep.md", "ignored.md", "local.md", "excluded.md", "Other.java")) {
            Files.writeString(docs.resolve(name), "text");
        }

        List<Path> before = cli.projectEntries();

        Result result = cli.run(docs, "index", ".", "--dry-run");

        assertSuccess(result);
        assertEquals(
                "config: " + project.resolve(".pecia.toml") + "\n" + "1 file(s) would be indexed:\n  keep.md\n",
                result.out());
        assertEquals(config, Files.readString(project.resolve(".pecia.toml")));
        assertEquals(before, cli.projectEntries());
    }

    @Test
    void missingTargetReturnsAConciseFailure() throws Exception {
        Result result = cli.run(project, "index", "missing", "--dry-run");

        assertConciseFailure(result, project.resolve("missing").toString());
    }

    @Test
    void fileTargetReturnsAConciseFailure() throws Exception {
        Files.writeString(project.resolve("notes.md"), "notes");

        Result result = cli.run(project, "index", "notes.md", "--dry-run");

        assertConciseFailure(result, "Target must be a directory:");
    }

    @Test
    void malformedConfigReturnsAConciseFailure() throws Exception {
        Files.writeString(project.resolve(".pecia.toml"), "[index\ninclude =");

        Result result = cli.run(project, "index", ".", "--dry-run");

        assertConciseFailure(result, "Invalid .pecia.toml:");
    }

    @Test
    void malformedGlobReturnsAConciseFailure() throws Exception {
        Files.writeString(project.resolve(".pecia.toml"), "[index]\ninclude = [\"[\"]\n");

        Result result = cli.run(project, "index", ".", "--dry-run");

        assertConciseFailure(result, "[");
    }

    @Test
    void partialScanListsUsableFilesAndWarnsWithFailureStatus() throws Exception {
        // An ignore path that is a directory fails consistently without platform-specific permission
        // changes.
        Path invalidIgnore = Files.createDirectories(project.resolve("bad/.gitignore"));
        Files.writeString(project.resolve("good.md"), "good");

        Result result = cli.run(project, "index", ".", "--dry-run");

        assertEquals(1, result.exitCode());
        assertEquals(
                "config: defaults (no .pecia.toml found)\n" + "1 file(s) would be indexed:\n  good.md\n", result.out());
        assertTrue(result.err().startsWith("warning: " + invalidIgnore + ": "), result.err());
        assertTrue(
                result.err().endsWith("pecia index: incomplete scan; listed files are only partial results\n"),
                result.err());
        assertNoStackTrace(result.err());
        assertFalse(Files.exists(project.resolve(".pecia")));
    }

    @Test
    void helpAndDryRunDoNotInitializeTheTokenizerOrWriteAnIndex() throws Exception {
        Files.writeString(project.resolve("notes.txt"), "text\n");
        List<Path> before = cli.projectEntries();
        List<List<String>> commands = List.of(
                List.of("--help"),
                List.of("index", "--help"),
                List.of("query", "--help"),
                List.of("index", "--dry-run"));

        for (int index = 0; index < commands.size(); index++) {
            Path log = sandbox.resolve("discovery-initialization-" + index + ".log");
            Result result = cli.run(
                    project,
                    List.of(OfflineSandbox.initializationLogArgument(log)),
                    commands.get(index).toArray(String[]::new));

            assertSuccess(result);
            assertTrue(initialized(log, "dev/eyuppastirmaci/pecia/Bootstrap"), "The JVM probe must be active");
            assertFalse(initialized(log, "dev/eyuppastirmaci/pecia/tokenization/MiniLmTokenizer"));
            assertEquals(before, cli.projectEntries());
        }
    }
}
