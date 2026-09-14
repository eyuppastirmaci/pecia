package dev.eyuppastirmaci.pecia.cli;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarFile;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PackagedCliIT {

    private static final String TOKENIZER_PATH = "dev/eyuppastirmaci/pecia/tokenization/all-MiniLM-L6-v2/";
    private static final String VOCABULARY_SHA256 = "07eced375cec144d27c900241f3e339478dec958f92fddbc551f295c992038a3";

    @TempDir
    Path sandbox;

    private Path project;
    private Path executableJar;

    @BeforeEach
    void prepareProject() throws IOException {
        String jarProperty = System.getProperty("pecia.it.jar");
        assertNotNull(jarProperty, "Failsafe must supply the packaged CLI JAR path");
        executableJar = Path.of(jarProperty);
        assertTrue(executableJar.isAbsolute(), "The CLI JAR path must be absolute");
        assertTrue(Files.isRegularFile(executableJar), "Missing packaged CLI JAR: " + executableJar);
        project = Files.createDirectory(sandbox.resolve("project with spaces")).toRealPath();
    }

    @Test
    void helpPreservesTheCommandContract() throws Exception {
        Result result = run(project, "--help");

        assertSuccess(result);
        assertEquals("""
                Usage: pecia [-hV] [COMMAND]
                Turns a folder of documents and source code into a searchable local vector
                index.
                  -h, --help      Show this help message and exit.
                  -V, --version   Print version information and exit.
                Commands:
                  init   Writes a .pecia.toml config file at the project root.
                  index  Indexes a folder: walks, chunks, embeds, and stores changed files.
                  query  Searches the index and prints the closest chunks.
                """, result.out());
    }

    @Test
    void versionPreservesTheCommandContract() throws Exception {
        Result result = run(project, "--version");

        assertSuccess(result);
        assertEquals("pecia 0.1.0-SNAPSHOT\n", result.out());
    }

    @Test
    void initWritesDefaultsAndPreservesAnExistingConfig() throws Exception {
        Path config = project.resolve(".pecia.toml");

        Result first = run(project, "init");

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

        Result repeated = run(project, "init");

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
        List<Path> before = projectEntries();

        Result result = run(project, "index", ".", "--dry-run");

        assertSuccess(result);
        assertEquals("""
                config: defaults (no .pecia.toml found)
                3 file(s) would be indexed:
                  Dockerfile
                  Main.JS
                  notes.md
                """, result.out());
        assertEquals(before, projectEntries());
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

        List<Path> before = projectEntries();

        Result result = run(docs, "index", ".", "--dry-run");

        assertSuccess(result);
        assertEquals("config: " + project.resolve(".pecia.toml") + "\n"
                + "1 file(s) would be indexed:\n  keep.md\n", result.out());
        assertEquals(config, Files.readString(project.resolve(".pecia.toml")));
        assertEquals(before, projectEntries());
    }

    @Test
    void missingTargetReturnsAConciseFailure() throws Exception {
        Result result = run(project, "index", "missing", "--dry-run");

        assertConciseFailure(result, project.resolve("missing").toString());
    }

    @Test
    void fileTargetReturnsAConciseFailure() throws Exception {
        Files.writeString(project.resolve("notes.md"), "notes");

        Result result = run(project, "index", "notes.md", "--dry-run");

        assertConciseFailure(result, "Target must be a directory:");
    }

    @Test
    void malformedConfigReturnsAConciseFailure() throws Exception {
        Files.writeString(project.resolve(".pecia.toml"), "[index\ninclude =");

        Result result = run(project, "index", ".", "--dry-run");

        assertConciseFailure(result, "Invalid .pecia.toml:");
    }

    @Test
    void malformedGlobReturnsAConciseFailure() throws Exception {
        Files.writeString(project.resolve(".pecia.toml"), "[index]\ninclude = [\"[\"]\n");

        Result result = run(project, "index", ".", "--dry-run");

        assertConciseFailure(result, "[");
    }

    @Test
    void partialScanListsUsableFilesAndWarnsWithFailureStatus() throws Exception {
        // An ignore path that is a directory fails consistently without platform-specific permission changes.
        Path invalidIgnore = Files.createDirectories(project.resolve("bad/.gitignore"));
        Files.writeString(project.resolve("good.md"), "good");

        Result result = run(project, "index", ".", "--dry-run");

        assertEquals(1, result.exitCode());
        assertEquals("config: defaults (no .pecia.toml found)\n"
                + "1 file(s) would be indexed:\n  good.md\n", result.out());
        assertTrue(result.err().startsWith("warning: " + invalidIgnore + ": "), result.err());
        assertTrue(result.err().endsWith("pecia index: incomplete scan; listed files are only partial results\n"),
                result.err());
        assertNoStackTrace(result.err());
        assertFalse(Files.exists(project.resolve(".pecia")));
    }

    @Test
    void indexingStillReportsThatOnlyDryRunIsImplemented() throws Exception {
        Files.writeString(project.resolve(".pecia.toml"), "[index\ninclude =");

        Result result = run(project, "index", ".");

        assertEquals(1, result.exitCode());
        assertEquals("", result.out());
        assertEquals("pecia index: only --dry-run is implemented yet\n", result.err());
        assertFalse(Files.exists(project.resolve(".pecia")));
    }

    @Test
    void queryStillReportsThatItIsNotImplemented() throws Exception {
        Result result = run(project, "query", "example");

        assertEquals(1, result.exitCode());
        assertEquals("", result.out());
        assertEquals("pecia query: not implemented yet (query: \"example\")\n", result.err());
        assertTrue(projectEntries().isEmpty());
    }

    @Test
    void executableJarIncludesCoreVocabularyAndLicenses() throws Exception {
        try (JarFile jar = new JarFile(executableJar.toFile())) {
            assertEquals("dev.eyuppastirmaci.pecia.Bootstrap",
                    jar.getManifest().getMainAttributes().getValue("Main-Class"));
            assertNotNull(jar.getJarEntry("dev/eyuppastirmaci/pecia/index/IndexService.class"));
            assertNotNull(jar.getJarEntry("picocli/CommandLine.class"));
            byte[] vocabulary = resource(jar, TOKENIZER_PATH + "vocab.txt");
            assertEquals(VOCABULARY_SHA256,
                    HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(vocabulary)));
            String notice = new String(resource(jar, TOKENIZER_PATH + "NOTICE.txt"), StandardCharsets.UTF_8);
            assertTrue(notice.contains("sentence-transformers/all-MiniLM-L6-v2"));
            assertTrue(notice.contains("1110a243fdf4706b3f48f1d95db1a4f5529b4d41"));
            assertTrue(notice.contains(VOCABULARY_SHA256));
            String tokenizerLicense = new String(resource(jar, TOKENIZER_PATH + "LICENSE.txt"), StandardCharsets.UTF_8);
            assertTrue(tokenizerLicense.contains("Apache License"));
            assertTrue(tokenizerLicense.contains("Version 2.0, January 2004"));
            String commonmarkLicense = new String(resource(jar, "META-INF/licenses/commonmark-LICENSE.txt"),
                    StandardCharsets.UTF_8);
            assertTrue(commonmarkLicense.contains("Copyright (c) 2015, Atlassian Pty Ltd"));
            assertTrue(commonmarkLicense.contains("Redistribution and use in source and binary forms"));
        }
    }

    /* Runs the distribution in an isolated directory and redirects both streams to avoid pipe deadlocks. */
    private Result run(Path directory, String... arguments) throws Exception {
        Path javaBin = Path.of(System.getProperty("java.home"), "bin");
        Path java = Files.isRegularFile(javaBin.resolve("java.exe")) ? javaBin.resolve("java.exe") : javaBin.resolve("java");
        List<String> command = new ArrayList<>(List.of(java.toString(), "-Dfile.encoding=UTF-8",
                "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8", "-Dpicocli.ansi=false",
                "-Dpicocli.usage.width=80", "-jar", executableJar.toString()));
        command.addAll(List.of(arguments));
        Path output = Files.createTempDirectory(sandbox, "process-");
        Path stdout = output.resolve("stdout.txt");
        Path stderr = output.resolve("stderr.txt");
        ProcessBuilder builder = new ProcessBuilder(command).directory(directory.toFile())
                .redirectOutput(stdout.toFile()).redirectError(stderr.toFile());
        builder.environment().keySet().removeAll(List.of("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS"));
        Process process = builder.start();

        try {
            process.getOutputStream().close();
            boolean completed = process.waitFor(30, TimeUnit.SECONDS);

            if (!completed) {
                process.destroyForcibly();
                assertTrue(process.waitFor(5, TimeUnit.SECONDS), "Could not terminate the CLI process");
            }

            assertTrue(completed, () -> "CLI timed out: " + command);

            return new Result(process.exitValue(), Files.readString(stdout).replace("\r\n", "\n"),
                    Files.readString(stderr).replace("\r\n", "\n"));
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    private List<Path> projectEntries() throws IOException {
        try (var paths = Files.walk(project)) {
            return paths.filter(path -> !path.equals(project))
                        .map(project::relativize)
                        .sorted().toList();
        }
    }

    private static byte[] resource(JarFile jar, String path) throws IOException {
        var entry = jar.getJarEntry(path);
        assertNotNull(entry, "Missing packaged resource: " + path);

        try (var input = jar.getInputStream(entry)) {
            return input.readAllBytes();
        }
    }

    private static void assertSuccess(Result result) {
        assertEquals(0, result.exitCode(), result.err());
        assertEquals("", result.err());
    }

    private static void assertConciseFailure(Result result, String expectedMessage) {
        assertEquals(1, result.exitCode());
        assertEquals("", result.out());
        assertTrue(result.err().startsWith("pecia index: "), result.err());
        assertTrue(result.err().contains(expectedMessage), result.err());
        assertNoStackTrace(result.err());
    }

    private static void assertNoStackTrace(String error) {
        assertFalse(error.contains("\tat "), error);
        assertFalse(error.contains("Exception in thread"), error);
        assertFalse(error.contains("Caused by:"), error);
    }

    private record Result(int exitCode, String out, String err) {
    }
}
