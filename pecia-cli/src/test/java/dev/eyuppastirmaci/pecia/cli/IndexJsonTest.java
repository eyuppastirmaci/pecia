package dev.eyuppastirmaci.pecia.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import dev.eyuppastirmaci.pecia.config.PeciaConfigLoader;
import dev.eyuppastirmaci.pecia.config.PeciaConfigParser;
import dev.eyuppastirmaci.pecia.index.IndexService;
import dev.eyuppastirmaci.pecia.semantic.ModelException;
import dev.eyuppastirmaci.pecia.semantic.ModelOutcome;
import dev.eyuppastirmaci.pecia.semantic.ModelProvider;
import dev.eyuppastirmaci.pecia.semantic.SemanticModel;
import dev.eyuppastirmaci.pecia.testing.TinyModel;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

/** {@code index --json} prints one JSON document for every outcome and leaves stderr and exit codes as they are. */
class IndexJsonTest {

    private static final List<String> FIELDS = List.of(
            "schemaVersion",
            "command",
            "dryRun",
            "root",
            "database",
            "config",
            "status",
            "files",
            "chunks",
            "embeddings",
            "durationMillis",
            "phases",
            "issues",
            "scanIssues",
            "semanticIssues",
            "warnings",
            "error");

    @TempDir
    Path root;

    private final StringWriter out = new StringWriter();
    private final StringWriter err = new StringWriter();
    private ModelProvider models = listener -> TinyModel.cached();

    @BeforeEach
    void useCanonicalRoot() throws IOException {
        root = root.toRealPath();
    }

    @Test
    void aCompleteRunReplacesTheSummaryWithItsDocument() throws Exception {
        Files.writeString(root.resolve("guide.md"), "# Guide\n\nRefunds take five days.\n");
        Files.writeString(root.resolve("notes.txt"), "Notes.\n");

        assertEquals(0, run(root.toString(), "--json"));

        assertEquals("", err.toString());
        JsonObject document = JsonDocuments.parse(out.toString());
        assertEquals(FIELDS, JsonDocuments.fields(document));
        assertEquals(1, document.get("schemaVersion").getAsInt());
        assertEquals("index", document.get("command").getAsString());
        assertFalse(document.get("dryRun").getAsBoolean());
        assertEquals(root.toString(), document.get("root").getAsString());
        assertEquals(
                root.resolve(".pecia/index.db").toString(),
                document.get("database").getAsString());
        assertEquals(JsonNull.INSTANCE, document.get("config"));
        assertEquals("COMPLETE", document.get("status").getAsString());
        JsonObject files = document.getAsJsonObject("files");
        assertEquals(
                List.of("candidates", "indexed", "rechunked", "unchanged", "deleted", "rejected", "failed"),
                JsonDocuments.fields(files));
        assertEquals(List.of(2, 2, 0, 0, 0, 0, 0), numbers(files));
        assertEquals(2, document.getAsJsonObject("chunks").get("written").getAsInt());
        assertEquals(JsonNull.INSTANCE, document.get("embeddings"));
        assertTrue(document.get("durationMillis").getAsLong() >= 0);
        // A first run deletes nothing, so it has no cleanup phase.
        assertEquals(List.of("SCAN", "FILES"), JsonDocuments.fields(document.getAsJsonObject("phases")));
        assertTrue(document.getAsJsonObject("phases").get("FILES").getAsLong()
                <= document.get("durationMillis").getAsLong());
        assertEquals(JsonNull.INSTANCE, document.get("error"));

        for (String list : List.of("issues", "scanIssues", "semanticIssues", "warnings")) {
            assertEquals(new JsonArray(), document.get(list), list);
        }
    }

    @Test
    void aRerunCountsUnchangedAndDeletedFilesAndNamesTheConfiguration() throws Exception {
        Path config = Files.writeString(root.resolve(".pecia.toml"), "[chunk]\nmax_token = 64\n");
        Files.writeString(root.resolve("keep.md"), "# Keep\n\nText.\n");
        Files.writeString(root.resolve("old.md"), "# Old\n\nText.\n");
        assertEquals(0, run(root.toString()));
        Files.delete(root.resolve("old.md"));
        Files.writeString(root.resolve("new.md"), "# New\n\nText.\n");
        reset();

        assertEquals(0, run(root.toString(), "--json"));

        JsonObject document = JsonDocuments.parse(out.toString());
        assertEquals(config.toString(), document.get("config").getAsString());
        assertEquals(List.of(2, 1, 0, 1, 1, 0, 0), numbers(document.getAsJsonObject("files")));
        assertEquals(List.of("SCAN", "FILES", "CLEANUP"), JsonDocuments.fields(document.getAsJsonObject("phases")));
        JsonArray warnings = document.getAsJsonArray("warnings");
        assertEquals(1, warnings.size());
        assertEquals(
                "UNKNOWN_CONFIG_KEY",
                warnings.get(0).getAsJsonObject().get("code").getAsString());
        assertTrue(err.toString().startsWith("warning: " + config + ":2:1: "), err.toString());
    }

    @Test
    void aSemanticRunReportsItsModelAndEmbeddings() throws Exception {
        Files.writeString(root.resolve("a.txt"), "needle");
        Path file = root.resolve("cache/models/all-MiniLM-L6-v2/rev/model.onnx");
        models = listener -> {
            listener.downloading(SemanticModel.MINILM, file);
            return new ModelOutcome(ModelOutcome.Status.DOWNLOADED, SemanticModel.MINILM, TinyModel.path("tiny"));
        };

        assertEquals(0, run(root.toString(), "--semantic", "--json"));

        assertTrue(err.toString().startsWith("Downloading all-MiniLM-L6-v2 (90.4 MB)"), err.toString());
        JsonObject embeddings = JsonDocuments.parse(out.toString()).getAsJsonObject("embeddings");
        assertEquals(
                List.of("model", "modelSource", "embedded", "backfilled", "discarded", "coverage"),
                JsonDocuments.fields(embeddings));
        assertEquals("all-MiniLM-L6-v2", embeddings.get("model").getAsString());
        assertEquals(
                List.of("SCAN", "MODEL", "FILES"),
                JsonDocuments.fields(JsonDocuments.parse(out.toString()).getAsJsonObject("phases")));
        assertEquals("DOWNLOADED", embeddings.get("modelSource").getAsString());
        assertEquals(1, embeddings.get("embedded").getAsLong());
        JsonObject coverage = embeddings.getAsJsonObject("coverage");
        assertEquals(List.of("embedded", "stale", "missing"), JsonDocuments.fields(coverage));
        assertEquals(1, coverage.get("embedded").getAsLong());
    }

    @Test
    void embeddingsOfAnEarlierProfileAreCountedAsDiscardedAndBackfilled() throws Exception {
        Files.writeString(root.resolve("a.txt"), "needle");
        Files.writeString(root.resolve("b.txt"), "haystack");
        Files.writeString(root.resolve("c.txt"), "straw");
        assertEquals(0, run(root.toString(), "--semantic"));
        IndexCommandTest.moveEmbeddingsToAnEarlierProfile(root.resolve(".pecia/index.db"));
        Files.writeString(root.resolve("c.txt"), "changed straw");
        reset();

        assertEquals(0, run(root.toString(), "--semantic", "--json"));

        JsonObject embeddings = JsonDocuments.parse(out.toString()).getAsJsonObject("embeddings");
        assertEquals("CACHED", embeddings.get("modelSource").getAsString());
        assertEquals(1, embeddings.get("embedded").getAsLong());
        assertEquals(2, embeddings.get("backfilled").getAsLong());
        assertEquals(3, embeddings.get("discarded").getAsLong());
    }

    @Test
    void aRejectedFileMakesThePartialRunListItsIssue() throws Exception {
        Files.writeString(root.resolve("good.txt"), "good");
        Files.writeString(root.resolve("bad.txt"), "binary\0");

        assertEquals(1, run(root.toString(), "--json"));

        JsonObject document = JsonDocuments.parse(out.toString());
        assertEquals("PARTIAL", document.get("status").getAsString());
        assertEquals(JsonNull.INSTANCE, document.get("error"));
        assertEquals(List.of(2, 1, 0, 0, 0, 1, 0), numbers(document.getAsJsonObject("files")));
        JsonObject issue = document.getAsJsonArray("issues").get(0).getAsJsonObject();
        assertEquals(List.of("path", "reason", "rejected", "message"), JsonDocuments.fields(issue));
        assertEquals("bad.txt", issue.get("path").getAsString());
        assertEquals("BINARY_CONTENT", issue.get("reason").getAsString());
        assertTrue(issue.get("rejected").getAsBoolean());
        List<String> lines = err.toString().lines().toList();
        assertEquals("warning: bad.txt: BINARY_CONTENT: " + issue.get("message").getAsString(), lines.get(0));
        assertTrue(lines.get(1).startsWith("pecia index: PARTIAL: "), lines.get(1));
    }

    @Test
    void aFileThatCannotBeReadCountsAsFailedNotRejected() throws Exception {
        Files.writeString(root.resolve("good.txt"), "good");
        Path blocked = Files.writeString(root.resolve("blocked.txt"), "secret");
        assumeTrue(Files.getFileStore(blocked).supportsFileAttributeView("posix"), "POSIX permissions unavailable");
        Set<PosixFilePermission> original = Files.getPosixFilePermissions(blocked);

        try {
            Files.setPosixFilePermissions(blocked, Set.of());
            assumeTrue(!Files.isReadable(blocked), "Current account bypasses permissions");

            assertEquals(1, run(root.toString(), "--json"));
        } finally {
            Files.setPosixFilePermissions(blocked, original);
        }

        JsonObject document = JsonDocuments.parse(out.toString());
        assertEquals(List.of(2, 1, 0, 0, 0, 0, 1), numbers(document.getAsJsonObject("files")));
        JsonObject issue = document.getAsJsonArray("issues").get(0).getAsJsonObject();
        assertEquals("READ_FAILED", issue.get("reason").getAsString());
        assertFalse(issue.get("rejected").getAsBoolean());
    }

    @Test
    void otherChunkSettingsAreCountedAsRechunked() throws Exception {
        Files.writeString(root.resolve("a.md"), "# A\n\nText.\n");
        Files.writeString(root.resolve("b.md"), "# B\n\nText.\n");
        assertEquals(0, run(root.toString()));
        Files.writeString(root.resolve(".pecia.toml"), "[chunk]\nmax_tokens = 128\noverlap_tokens = 16\n");
        reset();

        assertEquals(0, run(root.toString(), "--json"));

        assertEquals(
                List.of(2, 2, 2, 0, 0, 0, 0),
                numbers(JsonDocuments.parse(out.toString()).getAsJsonObject("files")));
    }

    @Test
    void aChildFolderReportsItsProjectRoot() throws Exception {
        Files.writeString(root.resolve(".pecia.toml"), "[chunk]\n");
        Path docs = Files.createDirectories(root.resolve("docs"));
        Files.writeString(docs.resolve("a.md"), "# A\n\nText.\n");

        assertEquals(0, run(docs.toString(), "--json"));

        JsonObject document = JsonDocuments.parse(out.toString());
        assertEquals(root.toString(), document.get("root").getAsString());
        assertEquals(
                root.resolve(".pecia/index.db").toString(),
                document.get("database").getAsString());
    }

    @Test
    void anUnreadableFolderIsAScanIssue() throws Exception {
        Files.createDirectories(root.resolve("bad/.gitignore"));
        Files.writeString(root.resolve("good.txt"), "good");

        assertEquals(1, run(root.toString(), "--json"));

        JsonObject document = JsonDocuments.parse(out.toString());
        assertEquals("PARTIAL", document.get("status").getAsString());
        JsonObject issue = document.getAsJsonArray("scanIssues").get(0).getAsJsonObject();
        assertEquals(List.of("path", "cause", "message"), JsonDocuments.fields(issue));
        assertEquals("bad/.gitignore", issue.get("path").getAsString());
        assertEquals("IO_ERROR", issue.get("cause").getAsString());
        assertTrue(err.toString()
                .startsWith("warning: bad/.gitignore: IO_ERROR: "
                        + issue.get("message").getAsString()));
    }

    @Test
    void aFileThatCannotBeEmbeddedIsASemanticIssue() throws Exception {
        Files.writeString(root.resolve("a.txt"), "needle");
        models = listener -> TinyModel.cached("non-finite");

        assertEquals(1, run(root.toString(), "--semantic", "--json"));

        JsonObject document = JsonDocuments.parse(out.toString());
        assertEquals("PARTIAL", document.get("status").getAsString());
        JsonObject issue = document.getAsJsonArray("semanticIssues").get(0).getAsJsonObject();
        assertEquals(List.of("path", "reason", "message"), JsonDocuments.fields(issue));
        assertEquals("a.txt", issue.get("path").getAsString());
        assertEquals("INVALID_OUTPUT", issue.get("reason").getAsString());
    }

    @Test
    void anEmbeddingFailureThatStopsTheRunKeepsWhatItWrote() throws Exception {
        for (String name : List.of("a.txt", "b.txt", "c.txt")) {
            Files.writeString(root.resolve(name), "needle");
        }
        models = listener -> TinyModel.cached("non-finite");

        assertEquals(1, run(root.toString(), "--semantic", "--json"));

        JsonObject document = JsonDocuments.parse(out.toString());
        assertEquals("EMBEDDING_FAILED", document.get("status").getAsString());
        assertEquals(3, document.getAsJsonObject("files").get("indexed").getAsInt());
        assertEquals(3, document.getAsJsonArray("semanticIssues").size());
        JsonObject error = document.getAsJsonObject("error");
        assertEquals(List.of("reason", "message"), JsonDocuments.fields(error));
        assertEquals("INVALID_OUTPUT", error.get("reason").getAsString());
        assertTrue(err.toString()
                .contains("pecia index: EMBEDDING_FAILED: INVALID_OUTPUT: "
                        + error.get("message").getAsString()));
    }

    @Test
    void anEmbedderThatCannotOpenIsAnError() throws Exception {
        Files.writeString(root.resolve("a.txt"), "needle");
        models = listener -> TinyModel.cached("missing-input");

        assertEquals(1, run(root.toString(), "--semantic", "--json"));

        JsonObject document = JsonDocuments.parse(out.toString());
        assertEquals("EMBEDDER_UNAVAILABLE", document.get("status").getAsString());
        assertEquals(
                "INVALID_MODEL", document.getAsJsonObject("error").get("reason").getAsString());
        assertEquals(2, err.toString().lines().count(), err.toString());
    }

    @Test
    void anUnavailableModelIsAnError() throws Exception {
        Files.writeString(root.resolve("a.txt"), "needle");
        models = listener -> {
            throw new ModelException(ModelException.Reason.OFFLINE, "explained failure", null);
        };

        assertEquals(1, run(root.toString(), "--semantic", "--json"));

        JsonObject document = JsonDocuments.parse(out.toString());
        assertEquals("MODEL_UNAVAILABLE", document.get("status").getAsString());
        assertEquals(0, document.getAsJsonObject("files").get("indexed").getAsInt());
        JsonObject error = document.getAsJsonObject("error");
        assertEquals("OFFLINE", error.get("reason").getAsString());
        assertEquals("explained failure", error.get("message").getAsString());
        assertFalse(Files.exists(root.resolve(".pecia")));
    }

    @Test
    void aStorageFailureNamesItsReasonAndCause() throws Exception {
        Path database = Files.createDirectories(root.resolve(".pecia")).resolve("index.db");
        Files.write(database, "not a database".getBytes(StandardCharsets.UTF_8));

        assertEquals(1, run(root.toString(), "--json"));

        JsonObject document = JsonDocuments.parse(out.toString());
        assertEquals("FAILED", document.get("status").getAsString());
        JsonObject error = document.getAsJsonObject("error");
        assertEquals("CORRUPT", error.get("reason").getAsString());
        assertTrue(error.get("message").getAsString().contains("not a database"), error.toString());
        List<String> lines = err.toString().lines().toList();
        assertEquals("pecia index: FAILED: CORRUPT: " + error.get("message").getAsString(), lines.get(0));
        assertTrue(lines.get(1).startsWith("The index is damaged; delete "), lines.get(1));
    }

    @Test
    void aMissingFolderIsAnErrorWithoutAResult() {
        assertEquals(1, run(root.resolve("missing").toString(), "--json"));

        JsonObject document = JsonDocuments.parse(out.toString());
        assertEquals(FIELDS, JsonDocuments.fields(document));

        for (String field : List.of(
                "root", "database", "config", "status", "files", "chunks", "embeddings", "durationMillis", "phases")) {
            assertEquals(JsonNull.INSTANCE, document.get(field), field);
        }

        JsonObject error = document.getAsJsonObject("error");
        assertEquals(IndexCommand.IO_ERROR, error.get("reason").getAsString());
        assertEquals(
                "pecia index: " + error.get("message").getAsString(),
                err.toString().strip());
    }

    @Test
    void anInvalidConfigurationIsAnInvalidInputError() throws Exception {
        Files.writeString(root.resolve(".pecia.toml"), "[index]\ninclude = [\"[\"]\n");

        assertEquals(1, run(root.toString(), "--json"));

        JsonObject error = JsonDocuments.parse(out.toString()).getAsJsonObject("error");
        assertEquals(QueryCommand.INVALID_INPUT, error.get("reason").getAsString());
        assertEquals(
                "pecia index: " + error.get("message").getAsString(),
                err.toString().strip());
    }

    @Test
    void usageErrorsStayPlainTextWithNothingOnStdout() {
        assertEquals(2, run(root.toString(), "--semantic", "--no-semantic", "--json"));

        assertEquals("", out.toString());
    }

    private static List<Integer> numbers(JsonObject object) {
        return object.entrySet().stream()
                .map(entry -> entry.getValue().getAsInt())
                .toList();
    }

    private void reset() {
        out.getBuffer().setLength(0);
        err.getBuffer().setLength(0);
    }

    private int run(String... args) {
        PeciaConfigLoader loader = new PeciaConfigLoader(new PeciaConfigParser());
        CommandLine commandLine = new CommandLine(new IndexCommand(IndexService.withModelProvider(loader, models)));
        commandLine.setOut(new PrintWriter(out));
        commandLine.setErr(new PrintWriter(err));

        return commandLine.execute(args);
    }
}
