package dev.eyuppastirmaci.pecia.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.eyuppastirmaci.pecia.config.PeciaConfigLoader;
import dev.eyuppastirmaci.pecia.config.PeciaConfigParser;
import dev.eyuppastirmaci.pecia.index.IndexService;
import dev.eyuppastirmaci.pecia.search.SearchRequest;
import dev.eyuppastirmaci.pecia.semantic.ModelException;
import dev.eyuppastirmaci.pecia.semantic.ModelOutcome;
import dev.eyuppastirmaci.pecia.semantic.ModelProvider;
import dev.eyuppastirmaci.pecia.semantic.SemanticModel;
import dev.eyuppastirmaci.pecia.storage.sqlite.SqliteStorage;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import picocli.CommandLine;

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

    // The misspelled key is ignored, so both configurations apply the same settings.
    private static final String TYPO_CONFIG = "[index]\ninclude = ['*.txt']\n[chunk]\nmax_token = 64\n";
    private static final String VALID_CONFIG = "[index]\ninclude = ['*.txt']\n[chunk]\n";

    @TempDir
    Path root;

    private final StringWriter out = new StringWriter();
    private final StringWriter err = new StringWriter();
    private ModelProvider models;

    @BeforeEach
    void useCanonicalRoot() throws IOException {
        // Resolved contexts use on-disk spellings; Windows runners may expose temp directories by short names.
        root = root.toRealPath();
    }

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

    @ParameterizedTest(name = "file {0}, flag {1} -> {2}")
    @CsvSource(
            nullValues = "none",
            value = {
                "none, none, disabled",
                "none, --semantic, enabled",
                "none, --no-semantic, disabled",
                "true, none, enabled",
                "true, --semantic, enabled",
                "true, --no-semantic, disabled",
                "false, none, disabled",
                "false, --semantic, enabled",
                "false, --no-semantic, disabled"
            })
    void dryRunReportsTheSemanticChoiceWithTheFlagOverridingTheFile(Boolean file, String flag, String expected)
            throws IOException {
        Files.writeString(root.resolve("notes.md"), "notes");

        if (file != null) {
            Files.writeString(root.resolve(".pecia.toml"), "[semantic]\nenabled = " + file + "\n");
        }

        int exitCode = flag == null ? run(root.toString(), "--dry-run") : run(root.toString(), "--dry-run", flag);

        assertEquals(0, exitCode);
        assertEquals("semantic: " + expected, out.toString().lines().toList().get(1));
        assertEquals("", err.toString());
    }

    @Test
    void semanticRunsAddTheModelToTheSummaryAndLexicalRunsDoNot() throws IOException {
        Files.writeString(root.resolve("a.txt"), "needle");

        assertEquals(0, run(root.toString(), "--semantic"));
        List<String> summary = out.toString().lines().toList();
        assertEquals("model: all-MiniLM-L6-v2 cached", summary.getLast());
        assertEquals("", err.toString());

        // The same project indexed from scratch without semantic indexing prints the rest of the summary unchanged.
        deleteIndex();
        assertSuccessfulIndex(1, 1, 0, 0, 1);
        assertEquals(
                summary.subList(0, summary.size() - 1), out.toString().lines().toList());

        deleteIndex();
        out.getBuffer().setLength(0);
        assertEquals(0, run(root.toString(), "--no-semantic"));
        assertEquals(
                summary.subList(0, summary.size() - 1), out.toString().lines().toList());
    }

    @Test
    void aDownloadIsAnnouncedOnStderrAndReportedInTheSummary() throws IOException {
        Files.writeString(root.resolve("a.txt"), "needle");
        Path file = root.resolve("cache/models/all-MiniLM-L6-v2/rev/model.onnx");
        models = listener -> {
            listener.downloading(SemanticModel.MINILM, file);
            return new ModelOutcome(ModelOutcome.Status.DOWNLOADED, SemanticModel.MINILM, file);
        };

        assertEquals(0, run(root.toString(), "--semantic"));

        assertEquals(
                "Downloading all-MiniLM-L6-v2 (90.4 MB) from huggingface.co into " + file.getParent()
                        + System.lineSeparator(),
                err.toString());
        assertEquals(
                "model: all-MiniLM-L6-v2 downloaded (90.4 MB)",
                out.toString().lines().toList().getLast());
    }

    @ParameterizedTest
    @EnumSource(ModelException.Reason.class)
    void anUnavailableModelPrintsTheReasonAndTheLexicalWayOutWithoutASummary(ModelException.Reason reason)
            throws IOException {
        Files.writeString(root.resolve("a.txt"), "needle");
        models = listener -> {
            throw new ModelException(reason, "explained failure", null);
        };

        assertEquals(1, run(root.toString(), "--semantic"));

        assertEquals("", out.toString());
        assertEquals(
                List.of(
                        "pecia index: MODEL_UNAVAILABLE: " + reason + ": explained failure",
                        "Run with --no-semantic to build only the lexical index."),
                err.toString().lines().toList());
        assertFalse(Files.exists(root.resolve(".pecia")));
    }

    @Test
    void configurationWarningsStillComeBeforeAnUnavailableModel() throws IOException {
        Path config = Files.writeString(root.resolve(".pecia.toml"), TYPO_CONFIG);
        Files.writeString(root.resolve("a.txt"), "needle");
        models = listener -> {
            throw new ModelException(ModelException.Reason.OFFLINE, "Could not reach huggingface.co", null);
        };

        assertEquals(1, run(root.toString(), "--semantic"));

        List<String> lines = err.toString().lines().toList();
        assertEquals(typoWarning(config), lines.get(0));
        assertTrue(lines.get(1).startsWith("pecia index: MODEL_UNAVAILABLE: OFFLINE:"), lines.get(1));
    }

    @Test
    void contradictorySemanticFlagsAreAUsageError() {
        assertEquals(2, run(root.toString(), "--dry-run", "--semantic", "--no-semantic"));
        assertTrue(err.toString().contains("option '--semantic' should be specified only once"), err.toString());
        assertFalse(Files.exists(root.resolve(".pecia")));
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
        assertEquals(
                String.join(
                        System.lineSeparator(),
                        "index: " + root.resolve(".pecia/index.db"),
                        "candidates: 2",
                        "indexed: 2",
                        "unchanged: 0",
                        "deleted: 0",
                        "chunks: 1",
                        "rejected: 0",
                        "failed: 0",
                        ""),
                out.toString());

        try (SqliteStorage storage = SqliteStorage.openReadOnly(root.resolve(".pecia/index.db"), root)) {
            assertEquals(
                    1,
                    storage.lexicalSearch().search(new SearchRequest("needle")).size());
        }
    }

    @Test
    void repeatedIndexReportsUnchangedFilesIncludingEmptyFiles() throws Exception {
        Files.writeString(root.resolve("a.txt"), "needle");
        Files.writeString(root.resolve("empty.txt"), "");
        assertEquals(0, run(root.toString()));
        out.getBuffer().setLength(0);
        err.getBuffer().setLength(0);

        assertEquals(0, run(root.toString()));
        assertEquals("", err.toString());
        assertEquals(
                String.join(
                        System.lineSeparator(),
                        "index: " + root.resolve(".pecia/index.db"),
                        "candidates: 2",
                        "indexed: 0",
                        "unchanged: 2",
                        "deleted: 0",
                        "chunks: 0",
                        "rejected: 0",
                        "failed: 0",
                        ""),
                out.toString());
    }

    @Test
    void deletedFileReportsCleanupOnceAndPreservesUnchangedCounters() throws Exception {
        Files.writeString(root.resolve(".pecia.toml"), "[index]\ninclude = ['*.txt']\n");
        Files.writeString(root.resolve("keep.txt"), "survivor");
        Path deleted = Files.writeString(root.resolve("delete.txt"), "obsolete");
        Files.writeString(root.resolve("empty.txt"), "");
        assertSuccessfulIndex(3, 3, 0, 0, 2);

        Files.delete(deleted);

        assertSuccessfulIndex(2, 0, 2, 1, 0);
        assertSuccessfulIndex(2, 0, 2, 0, 0);

        try (SqliteStorage storage = SqliteStorage.openReadOnly(root.resolve(".pecia/index.db"), root)) {
            assertTrue(storage.lexicalSearch()
                    .search(new SearchRequest("obsolete"))
                    .isEmpty());
            assertEquals(
                    1,
                    storage.lexicalSearch()
                            .search(new SearchRequest("survivor"))
                            .size());
        }
    }

    @Test
    void changedTomlTokenBudgetReportsNewChunksThenUnchangedFiles() throws IOException {
        Files.writeString(root.resolve("calendar.txt"), """
            needle january one two
            needle february one two
            needle march one two
            needle april one two
            needle may one two
            needle june one two
            """);
        writeChunkConfig(26, 1);
        assertSuccessfulIndex(1, 1, 0, 0, 1);

        writeChunkConfig(14, 1);

        assertSuccessfulIndex(1, 1, 0, 0, 2);
        assertSuccessfulIndex(1, 0, 1, 0, 0);
    }

    @Test
    void changedEmbeddingConcurrencyReportsOnlyUnchangedFiles() throws IOException {
        Files.writeString(root.resolve("notes.txt"), "needle");
        Files.writeString(root.resolve("empty.txt"), "");
        writeChunkConfig(26, 1);
        assertSuccessfulIndex(2, 2, 0, 0, 1);

        writeChunkConfig(26, 4);

        assertSuccessfulIndex(2, 0, 2, 0, 0);
    }

    @Test
    void partialRepeatSeparatesUnchangedFilesFromRejections() throws Exception {
        Files.writeString(root.resolve("good.txt"), "good");
        Files.writeString(root.resolve("bad.txt"), "old content");
        assertEquals(0, run(root.toString()));
        Files.writeString(root.resolve("bad.txt"), "binary\0");
        out.getBuffer().setLength(0);
        err.getBuffer().setLength(0);

        assertEquals(1, run(root.toString()));
        assertTrue(out.toString().contains("indexed: 0"));
        assertTrue(out.toString().contains("unchanged: 1"));
        assertTrue(out.toString().contains("deleted: 0"));
        assertTrue(out.toString().contains("chunks: 0"));
        assertTrue(out.toString().contains("rejected: 1"));
        assertTrue(out.toString().contains("failed: 0"));
        assertTrue(err.toString().contains("warning: bad.txt: BINARY_CONTENT:"));
    }

    @Test
    void emptyProjectIsSuccessful() {
        assertEquals(0, run(root.toString()));
        assertTrue(out.toString().contains("indexed: 0"));
        assertTrue(out.toString().contains("unchanged: 0"));
        assertTrue(out.toString().contains("deleted: 0"));
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
        assertTrue(err.toString().contains("pecia index: Index storage or cleanup operation failed:"));
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
    void indexPrintsConfigurationWarningsFirstWithoutChangingStdoutOrExitCode() throws IOException {
        Path config = Files.writeString(root.resolve(".pecia.toml"), TYPO_CONFIG);
        Files.writeString(root.resolve("a.txt"), "needle");

        assertEquals(0, run(root.toString()));
        String withWarning = out.toString();
        assertEquals(typoWarning(config) + System.lineSeparator(), err.toString());

        // The same project without the typo, indexed from scratch, prints the same summary and nothing on stderr.
        deleteIndex();
        Files.writeString(config, VALID_CONFIG);
        assertSuccessfulIndex(1, 1, 0, 0, 1);
        assertEquals(withWarning, out.toString());
    }

    @Test
    void dryRunPrintsConfigurationWarningsFirstWithoutChangingStdout() throws IOException {
        Path config = Files.writeString(root.resolve(".pecia.toml"), TYPO_CONFIG);
        Files.writeString(root.resolve("a.txt"), "needle");

        assertEquals(0, run(root.toString(), "--dry-run"));
        String withWarning = out.toString();
        assertEquals(typoWarning(config) + System.lineSeparator(), err.toString());

        Files.writeString(config, VALID_CONFIG);
        out.getBuffer().setLength(0);
        err.getBuffer().setLength(0);

        assertEquals(0, run(root.toString(), "--dry-run"));
        assertEquals(withWarning, out.toString());
        assertEquals("", err.toString());
    }

    @Test
    void failedIndexingPrintsConfigurationWarningsBeforeTheError() throws IOException {
        Path config = Files.writeString(root.resolve(".pecia.toml"), TYPO_CONFIG);
        Files.writeString(root.resolve("a.txt"), "needle");
        Files.writeString(Files.createDirectory(root.resolve(".pecia")).resolve("index.db"), "not a database");

        assertEquals(1, run(root.toString()));

        List<String> lines = err.toString().lines().toList();
        assertEquals(typoWarning(config), lines.get(0));
        assertTrue(lines.get(1).startsWith("pecia index: Index storage or cleanup operation failed:"), lines.get(1));
    }

    @Test
    void helpAndUsageErrorsDoNotOpenStorage() {
        assertEquals(0, run(root.toString(), "--help"));
        assertTrue(out.toString().contains("local lexical index"));
        assertTrue(out.toString().contains("--[no-]semantic"));
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

    private static String typoWarning(Path config) {
        return "warning: " + config + ":4:1: unknown key chunk.max_token (did you mean chunk.max_tokens?)";
    }

    private void deleteIndex() throws IOException {
        try (Stream<Path> paths = Files.walk(root.resolve(".pecia"))) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        }
    }

    private void writeChunkConfig(int maxTokens, int concurrency) throws IOException {
        Files.writeString(
                root.resolve(".pecia.toml"),
                "[index]\ninclude = ['*.txt']\n[chunk]\nmax_tokens = " + maxTokens
                        + "\noverlap_tokens = 0\n[embed]\nconcurrency = " + concurrency + "\n");
    }

    private void assertSuccessfulIndex(int candidates, int indexed, int unchanged, int deleted, int chunks) {
        out.getBuffer().setLength(0);
        err.getBuffer().setLength(0);

        assertEquals(0, run(root.toString()));
        assertEquals("", err.toString());
        assertEquals(
                String.join(
                        System.lineSeparator(),
                        "index: " + root.resolve(".pecia/index.db"),
                        "candidates: " + candidates,
                        "indexed: " + indexed,
                        "unchanged: " + unchanged,
                        "deleted: " + deleted,
                        "chunks: " + chunks,
                        "rejected: 0",
                        "failed: 0",
                        ""),
                out.toString());
    }

    private int run(String... args) {
        PeciaConfigLoader loader = new PeciaConfigLoader(new PeciaConfigParser());

        // Semantic runs get the model from a stub, so these tests never download it.
        ModelOutcome cached =
                new ModelOutcome(ModelOutcome.Status.CACHED, SemanticModel.MINILM, root.resolve("model.onnx"));
        ModelProvider provider = models != null ? models : listener -> cached;
        CommandLine commandLine = new CommandLine(new IndexCommand(IndexService.withModelProvider(loader, provider)));
        commandLine.setOut(new PrintWriter(out));
        commandLine.setErr(new PrintWriter(err));

        return commandLine.execute(args);
    }
}
