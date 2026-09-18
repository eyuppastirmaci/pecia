package dev.eyuppastirmaci.pecia.cli;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.eyuppastirmaci.pecia.content.ContentHash;
import dev.eyuppastirmaci.pecia.content.DocumentType;
import dev.eyuppastirmaci.pecia.content.LineRange;
import dev.eyuppastirmaci.pecia.search.SearchHit;
import dev.eyuppastirmaci.pecia.search.SearchRequest;
import dev.eyuppastirmaci.pecia.storage.model.StoredFile;
import dev.eyuppastirmaci.pecia.storage.sqlite.SqliteStorage;
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
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
            Indexes documents and source code for offline lexical search.
              -h, --help      Show this help message and exit.
              -V, --version   Print version information and exit.
            Commands:
              init   Writes a .pecia.toml config file at the project root.
              index  Builds a local lexical index from a folder of text files.
              query  Searches the local index using BM25 lexical ranking.
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
        assertEquals(
                "config: " + project.resolve(".pecia.toml") + "\n" + "1 file(s) would be indexed:\n  keep.md\n",
                result.out());
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
        // An ignore path that is a directory fails consistently without platform-specific permission
        // changes.
        Path invalidIgnore = Files.createDirectories(project.resolve("bad/.gitignore"));
        Files.writeString(project.resolve("good.md"), "good");

        Result result = run(project, "index", ".", "--dry-run");

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
    void defaultIndexPersistsManifestChunksAndFtsWithoutAccumulatingReplacements() throws Exception {
        Files.writeString(project.resolve("notes.txt"), "legacyneedle notes\n");
        Files.writeString(project.resolve("README.md"), "# Guide\n\nAPI_TOKEN configuration.\n");
        Files.createDirectory(project.resolve("src"));
        String source = "class Auth { String token = \"JWT_SECRET\"; }\n";
        Files.writeString(project.resolve("src/Auth.java"), source);
        Path database = project.resolve(".pecia/index.db");
        Path initializationLog = sandbox.resolve("index-initialization.log");

        Result first = run(project, List.of(initializationLogging(initializationLog)), "index");

        assertIndexSummary(first, database, 3, 3);
        assertTrue(initialized(initializationLog, "dev/eyuppastirmaci/pecia/tokenization/MiniLmTokenizer"));
        assertFalse(Files.exists(project.resolve(".pecia.toml")));

        try (SqliteStorage storage = SqliteStorage.openReadOnly(database, project)) {
            List<StoredFile> files = storage.files().findAll();

            assertEquals(
                    List.of(Path.of("README.md"), Path.of("notes.txt"), Path.of("src/Auth.java")),
                    files.stream().map(file -> file.sourcePath()).toList());

            for (StoredFile file : files) {
                assertEquals(1, storage.chunks().findByFileId(file.id()).size());
            }

            StoredFile auth =
                    storage.files().findByPath(Path.of("src/Auth.java")).orElseThrow();
            assertEquals(DocumentType.SOURCE_CODE, auth.documentType());
            assertEquals(ContentHash.sha256(source.getBytes(StandardCharsets.UTF_8)), auth.contentHash());

            List<SearchHit> codeHits = storage.lexicalSearch().search(new SearchRequest("JWT_SECRET"));
            assertEquals(1, codeHits.size());
            assertEquals(auth.sourcePath(), codeHits.getFirst().sourcePath());
            assertEquals(new LineRange(1, 1), codeHits.getFirst().sourceLocation());

            List<SearchHit> markdownHits = storage.lexicalSearch().search(new SearchRequest("API_TOKEN"));
            assertEquals(1, markdownHits.size());
            assertEquals(Path.of("README.md"), markdownHits.getFirst().sourcePath());
            assertEquals(List.of("Guide"), markdownHits.getFirst().metadata().headingPath());
            assertEquals(
                    1,
                    storage.lexicalSearch()
                            .search(new SearchRequest("legacyneedle"))
                            .size());
        }

        Files.writeString(project.resolve("notes.txt"), "replacementneedle notes\n");

        assertIndexSummary(run(project, "index"), database, 3, 1, 1);

        try (SqliteStorage storage = SqliteStorage.openReadOnly(database, project)) {
            List<StoredFile> files = storage.files().findAll();

            assertEquals(3, files.size());

            for (StoredFile file : files) {
                assertEquals(1, storage.chunks().findByFileId(file.id()).size());
            }

            assertTrue(storage.lexicalSearch()
                    .search(new SearchRequest("legacyneedle"))
                    .isEmpty());

            List<SearchHit> replacement = storage.lexicalSearch().search(new SearchRequest("replacementneedle"));
            assertEquals(1, replacement.size());
            assertEquals(Path.of("notes.txt"), replacement.getFirst().sourcePath());
        }
    }

    @Test
    void explicitChildIndexUsesRootConfigAndPersistsProjectRelativePaths() throws Exception {
        Files.writeString(project.resolve(".pecia.toml"), "[store]\npath = \"state/search.sqlite\"\n");
        Files.writeString(project.resolve(".gitignore"), "ignored.txt\n");
        Files.writeString(project.resolve("outside.txt"), "outside target\n");
        Path docs = Files.createDirectory(project.resolve("docs"));
        Files.writeString(docs.resolve("Ödeme notları.txt"), "childneedle ödeme\n");
        Files.writeString(docs.resolve("ignored.txt"), "ignored target\n");
        Path database = project.resolve("state/search.sqlite");

        assertIndexSummary(run(project, "index", "docs"), database, 1, 1);
        assertIndexSummary(run(docs, "index"), database, 1, 0, 0);

        try (SqliteStorage storage = SqliteStorage.openReadOnly(database, project)) {
            List<StoredFile> files = storage.files().findAll();

            assertEquals(1, files.size());
            assertEquals(Path.of("docs/Ödeme notları.txt"), files.getFirst().sourcePath());
            assertEquals(1, storage.chunks().findByFileId(files.getFirst().id()).size());

            List<SearchHit> hits = storage.lexicalSearch().search(new SearchRequest("childneedle"));
            assertEquals(1, hits.size());
            assertEquals(files.getFirst().sourcePath(), hits.getFirst().sourcePath());
        }

        assertFalse(Files.exists(project.resolve(".pecia")));
        assertFalse(Files.exists(docs.resolve("state")));
    }

    @Test
    void helpAndDryRunDoNotInitializeTheTokenizerOrWriteAnIndex() throws Exception {
        Files.writeString(project.resolve("notes.txt"), "text\n");
        List<Path> before = projectEntries();
        List<List<String>> commands = List.of(
                List.of("--help"),
                List.of("index", "--help"),
                List.of("query", "--help"),
                List.of("index", "--dry-run"));

        for (int index = 0; index < commands.size(); index++) {
            Path log = sandbox.resolve("discovery-initialization-" + index + ".log");
            Result result = run(
                    project,
                    List.of(initializationLogging(log)),
                    commands.get(index).toArray(String[]::new));

            assertSuccess(result);
            assertTrue(initialized(log, "dev/eyuppastirmaci/pecia/Bootstrap"), "The JVM probe must be active");
            assertFalse(initialized(log, "dev/eyuppastirmaci/pecia/tokenization/MiniLmTokenizer"));
            assertEquals(before, projectEntries());
        }
    }

    @Test
    void queryReadsThePackagedIndexFromAnotherDirectoryWithoutSourceFilesOrTokenizer() throws Exception {
        Files.createDirectory(project.resolve("src"));
        Path source = project.resolve("src/PaymentService.java");
        Files.writeString(source, """
            class PaymentService {
                String secret = "JWT_SECRET";
                void charge() {}
            }
            """);
        Files.createDirectory(project.resolve("docs"));
        Path document = project.resolve("docs/Ödeme.md");
        Files.writeString(document, "# Ödeme rehberi\n\nJWT_SECRET ödeme işlemini doğrular.\n");
        Path database = project.resolve(".pecia/index.db");
        assertIndexSummary(run(project, "index"), database, 2, 2);

        List<SearchHit> expected;

        try (SqliteStorage storage = SqliteStorage.openReadOnly(database, project)) {
            expected = storage.lexicalSearch().search(new SearchRequest("JWT_SECRET"));
        }

        assertEquals(2, expected.size());
        Files.delete(source);
        Files.delete(document);
        byte[] indexedBytes = Files.readAllBytes(database);
        List<Path> entriesBeforeQuery = projectEntries();
        Path workingDirectory = Files.createDirectory(sandbox.resolve("elsewhere"));
        Path initializationLog = sandbox.resolve("query-initialization.log");

        Result identifier = run(
                workingDirectory,
                List.of(initializationLogging(initializationLog)),
                "query",
                "JWT_SECRET",
                "--root",
                project.toString());

        assertSuccess(identifier);
        assertEquals(
                expected.stream().map(hit -> "  BM25: " + hit.score().value()).toList(),
                identifier
                        .out()
                        .lines()
                        .filter(line -> line.startsWith("  BM25: "))
                        .toList());
        assertTrue(
                identifier.out().indexOf(portablePath(expected.getFirst().sourcePath()) + ":")
                        < identifier
                                .out()
                                .indexOf(portablePath(expected.getLast().sourcePath()) + ":"),
                identifier.out());
        assertTrue(identifier.out().contains("src/PaymentService.java:1-4\n  BM25: "), identifier.out());
        assertTrue(
                identifier
                        .out()
                        .contains("  class PaymentService {\n      String secret = \"JWT_SECRET\";\n"
                                + "      void charge() {}\n  }\n"),
                identifier.out());
        assertTrue(identifier.out().contains("docs/Ödeme.md:1-3\n  BM25: "), identifier.out());
        assertTrue(
                identifier
                        .out()
                        .contains("  heading: Ödeme rehberi\n  # Ödeme rehberi\n  \n"
                                + "  JWT_SECRET ödeme işlemini doğrular.\n"),
                identifier.out());
        assertTrue(identifier.out().endsWith("\n\n"), identifier.out());
        assertFalse(identifier.out().contains("\u001b"));
        assertTrue(initialized(initializationLog, "dev/eyuppastirmaci/pecia/Bootstrap"));
        assertFalse(initialized(initializationLog, "dev/eyuppastirmaci/pecia/tokenization/MiniLmTokenizer"));

        Result limited = run(workingDirectory, "query", "JWT_SECRET", "--root", project.toString(), "--limit", "1");

        assertSuccess(limited);
        assertTrue(limited.out().startsWith(portablePath(expected.getFirst().sourcePath()) + ":"), limited.out());
        assertEquals(
                1,
                limited.out()
                        .lines()
                        .filter(line -> line.startsWith("  BM25: "))
                        .count());

        Result unicode = run(project, "query", "ödeme");

        assertSuccess(unicode);
        assertTrue(unicode.out().startsWith("docs/Ödeme.md:1-3\n"), unicode.out());
        assertTrue(unicode.out().contains("  heading: Ödeme rehberi\n"), unicode.out());
        assertTrue(unicode.out().contains("ödeme işlemini doğrular."), unicode.out());

        Result missing = run(workingDirectory, "query", "unfindablelexicalterm", "--root", project.toString());

        assertSuccess(missing);
        assertEquals("No results.\n", missing.out());
        assertEquals(entriesBeforeQuery, projectEntries());
        assertArrayEquals(indexedBytes, Files.readAllBytes(database));

        try (Stream<Path> entries = Files.list(workingDirectory)) {
            assertEquals(0, entries.count());
        }
    }

    @Test
    void missingIndexReportsAnIndexCommandWithoutCreatingFiles() throws Exception {
        Result result = run(project, "query", "example");

        assertEquals(1, result.exitCode());
        assertEquals("", result.out());
        assertTrue(result.err().startsWith("pecia query: "), result.err());
        assertTrue(result.err().contains("java -jar "), result.err());
        assertTrue(result.err().contains(" index "), result.err());
        assertTrue(result.err().contains(project.toString()), result.err());
        assertNoStackTrace(result.err());
        assertTrue(projectEntries().isEmpty());
    }

    @Test
    void partialIndexKeepsRejectedFilesSearchableAndUpdatesSuccessfulFiles() throws Exception {
        Path rejected = project.resolve("bad.txt");
        Files.writeString(rejected, "retainedneedle\n");
        Files.writeString(project.resolve("good.txt"), "outdatedneedle\n");
        Path database = project.resolve(".pecia/index.db");
        assertIndexSummary(run(project, "index"), database, 2, 2);
        Files.write(rejected, new byte[] {(byte) 0xc3, 0x28});
        Files.writeString(project.resolve("good.txt"), "updatedneedle\n");

        Result partial = run(project, "index");

        assertEquals(1, partial.exitCode());
        assertEquals(
                "index: " + database
                        + "\ncandidates: 2\nindexed: 1\nunchanged: 0\ndeleted: 0\nchunks: 1\nrejected: 1\nfailed: 0\n",
                partial.out());
        assertTrue(partial.err().startsWith("warning: bad.txt: INVALID_UTF8: "), partial.err());
        assertNoStackTrace(partial.err());

        Result retained = run(project, "query", "retainedneedle");
        Result updated = run(project, "query", "updatedneedle");
        Result outdated = run(project, "query", "outdatedneedle");

        assertSuccess(retained);
        assertTrue(retained.out().startsWith("bad.txt:1-1\n"), retained.out());
        assertTrue(retained.out().contains("  retainedneedle\n"), retained.out());
        assertSuccess(updated);
        assertTrue(updated.out().startsWith("good.txt:1-1\n"), updated.out());
        assertSuccess(outdated);
        assertEquals("No results.\n", outdated.out());
    }

    @Test
    void executableJarIncludesCoreVocabularyAndLicenses() throws Exception {
        try (JarFile jar = new JarFile(executableJar.toFile())) {
            assertEquals(
                    "dev.eyuppastirmaci.pecia.Bootstrap",
                    jar.getManifest().getMainAttributes().getValue("Main-Class"));
            assertNotNull(jar.getJarEntry("dev/eyuppastirmaci/pecia/index/IndexService.class"));
            assertNotNull(jar.getJarEntry("dev/eyuppastirmaci/pecia/search/QueryService.class"));
            assertNotNull(jar.getJarEntry("picocli/CommandLine.class"));
            assertTrue(
                    jar.stream()
                            .map(entry -> entry.getName())
                            .noneMatch(name -> name.startsWith("ai/onnxruntime/")
                                    || name.startsWith("ai/djl/")
                                    || name.startsWith("org/tensorflow/")
                                    || name.startsWith("org/pytorch/")
                                    || name.endsWith(".onnx")
                                    || name.endsWith(".safetensors")
                                    || name.endsWith("pytorch_model.bin")),
                    "The lexical JAR must not bundle a semantic runtime or model");
            byte[] vocabulary = resource(jar, TOKENIZER_PATH + "vocab.txt");
            assertEquals(
                    VOCABULARY_SHA256,
                    HexFormat.of()
                            .formatHex(MessageDigest.getInstance("SHA-256").digest(vocabulary)));
            String notice = new String(resource(jar, TOKENIZER_PATH + "NOTICE.txt"), StandardCharsets.UTF_8);
            assertTrue(notice.contains("sentence-transformers/all-MiniLM-L6-v2"));
            assertTrue(notice.contains("1110a243fdf4706b3f48f1d95db1a4f5529b4d41"));
            assertTrue(notice.contains(VOCABULARY_SHA256));
            String tokenizerLicense = new String(resource(jar, TOKENIZER_PATH + "LICENSE.txt"), StandardCharsets.UTF_8);
            assertTrue(tokenizerLicense.contains("Apache License"));
            assertTrue(tokenizerLicense.contains("Version 2.0, January 2004"));
            String commonmarkLicense =
                    new String(resource(jar, "META-INF/licenses/commonmark-LICENSE.txt"), StandardCharsets.UTF_8);
            assertTrue(commonmarkLicense.contains("Copyright (c) 2015, Atlassian Pty Ltd"));
            assertTrue(commonmarkLicense.contains("Redistribution and use in source and binary forms"));
        }
    }

    /* Runs the distribution in an isolated directory and redirects both streams to avoid pipe deadlocks. */
    private Result run(Path directory, String... arguments) throws Exception {
        return run(directory, List.of(), arguments);
    }

    private Result run(Path directory, List<String> jvmArguments, String... arguments) throws Exception {
        Path javaBin = Path.of(System.getProperty("java.home"), "bin");
        Path java = Files.isRegularFile(javaBin.resolve("java.exe"))
                ? javaBin.resolve("java.exe")
                : javaBin.resolve("java");
        List<String> command = new ArrayList<>(List.of(
                java.toString(),
                "-Dfile.encoding=UTF-8",
                "-Dstdout.encoding=UTF-8",
                "-Dstderr.encoding=UTF-8",
                "-Dpicocli.ansi=false",
                "-Dpicocli.usage.width=80"));

        command.addAll(jvmArguments);
        command.addAll(List.of("-jar", executableJar.toString()));
        command.addAll(List.of(arguments));

        Path output = Files.createTempDirectory(sandbox, "process-");
        Path stdout = output.resolve("stdout.txt");
        Path stderr = output.resolve("stderr.txt");
        ProcessBuilder builder = new ProcessBuilder(command)
                .directory(directory.toFile())
                .redirectOutput(stdout.toFile())
                .redirectError(stderr.toFile());
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

            return new Result(
                    process.exitValue(),
                    Files.readString(stdout).replace("\r\n", "\n"),
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
                    .sorted()
                    .toList();
        }
    }

    private static byte[] resource(JarFile jar, String path) throws IOException {
        var entry = jar.getJarEntry(path);
        assertNotNull(entry, "Missing packaged resource: " + path);

        try (var input = jar.getInputStream(entry)) {
            return input.readAllBytes();
        }
    }

    @Test
    void indexingDeletedFileRemovesSearchResultsAndReportsCleanup() throws Exception {
        Path source = Files.writeString(project.resolve("removed.txt"), "deletedneedle");
        assertSuccess(run(project, "index"));
        Files.delete(source);

        Result cleanup = run(project, "index");

        assertSuccess(cleanup);
        assertEquals(
                "index: " + project.resolve(".pecia/index.db")
                        + "\ncandidates: 0\nindexed: 0\nunchanged: 0\ndeleted: 1\nchunks: 0\nrejected: 0\nfailed: 0\n",
                cleanup.out());
        Result query = run(project, "query", "deletedneedle");
        assertSuccess(query);
        assertEquals("No results.\n", query.out());
        assertIndexSummary(run(project, "index"), project.resolve(".pecia/index.db"), 0, 0);
    }

    private static void assertSuccess(Result result) {
        assertEquals(0, result.exitCode(), result.err());
        assertEquals("", result.err());
    }

    private static void assertIndexSummary(Result result, Path database, int files, int chunks) {
        assertIndexSummary(result, database, files, files, chunks);
    }

    private static void assertIndexSummary(Result result, Path database, int candidates, int indexed, int chunks) {
        assertSuccess(result);
        assertEquals(
                "index: "
                        + database
                        + "\ncandidates: "
                        + candidates
                        + "\nindexed: "
                        + indexed
                        + "\nunchanged: "
                        + (candidates - indexed)
                        + "\ndeleted: 0"
                        + "\nchunks: "
                        + chunks
                        + "\nrejected: 0\nfailed: 0\n",
                result.out());
    }

    private static String initializationLogging(Path destination) {
        return "-Xlog:class+init=info:file=" + destination;
    }

    private static boolean initialized(Path log, String className) throws IOException {
        return Files.readString(log).contains("Initializing '" + className + "'");
    }

    private static String portablePath(Path path) {
        return path.toString().replace(path.getFileSystem().getSeparator(), "/");
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

    private record Result(int exitCode, String out, String err) {}
}
