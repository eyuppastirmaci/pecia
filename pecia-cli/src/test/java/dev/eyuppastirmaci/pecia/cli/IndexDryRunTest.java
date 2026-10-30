package dev.eyuppastirmaci.pecia.cli;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import dev.eyuppastirmaci.pecia.config.PeciaConfigLoader;
import dev.eyuppastirmaci.pecia.config.PeciaConfigParser;
import dev.eyuppastirmaci.pecia.index.IndexPreview;
import dev.eyuppastirmaci.pecia.index.IndexService;
import dev.eyuppastirmaci.pecia.semantic.ModelOutcome;
import dev.eyuppastirmaci.pecia.semantic.SemanticModel;
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
    void theJsonDocumentReplacesTheSummaryWithEveryGroupAndCount() throws Exception {
        Files.writeString(root.resolve("long.md"), "# Long\n\n" + "Refunds are paid within five days. ".repeat(120));
        assertEquals(0, run("--no-semantic"));
        Files.writeString(root.resolve("notes.txt"), "Replaced notes about refunds and returns.\n");
        Files.writeString(root.resolve("added.md"), "# Added\n\nA new page.\n");
        Files.delete(root.resolve("old.txt"));
        byte[] before = Files.readAllBytes(database);

        assertEquals(0, run("--dry-run", "--no-semantic", "--json"));

        assertEquals("", err.toString());
        JsonObject document = JsonDocuments.parse(printed());
        assertEquals(
                List.of(
                        "schemaVersion",
                        "command",
                        "dryRun",
                        "root",
                        "database",
                        "config",
                        "semantic",
                        "index",
                        "files",
                        "chunks",
                        "embeddings",
                        "newFiles",
                        "changedFiles",
                        "rechunkedFiles",
                        "rejectedFiles",
                        "removedFiles",
                        "scanIssues",
                        "warnings",
                        "error"),
                JsonDocuments.fields(document));
        assertEquals("index", document.get("command").getAsString());
        assertTrue(document.get("dryRun").getAsBoolean());
        assertEquals(root.toRealPath().toString(), document.get("root").getAsString());
        assertEquals(database.toString(), document.get("database").getAsString());
        assertEquals(JsonNull.INSTANCE, document.get("config"));
        assertFalse(document.get("semantic").getAsBoolean());
        assertEquals("CURRENT", document.get("index").getAsString());
        JsonObject files = document.getAsJsonObject("files");
        assertEquals(
                List.of("new", "changed", "rechunked", "unchanged", "rejected", "removed"),
                JsonDocuments.fields(files));
        assertEquals(List.of(1, 1, 0, 2, 0, 1), numbers(files));
        JsonObject chunks = document.getAsJsonObject("chunks");
        assertEquals(List.of("toWrite", "kept", "removed"), JsonDocuments.fields(chunks));
        assertEquals(count(2, true), chunks.get("toWrite"));
        assertEquals(count(8, false), chunks.get("kept"));
        assertEquals(count(1, false), chunks.get("removed"));
        assertEquals(JsonNull.INSTANCE, document.get("embeddings"));
        assertEquals(planned("added.md", 1, true), document.get("newFiles"));
        assertEquals(planned("notes.txt", 1, true), document.get("changedFiles"));
        assertEquals(planned("old.txt", 1, false), document.get("removedFiles"));
        assertEquals(new JsonArray(), document.get("rechunkedFiles"));
        assertEquals(new JsonArray(), document.get("rejectedFiles"));
        assertEquals(JsonNull.INSTANCE, document.get("error"));
        assertArrayEquals(before, Files.readAllBytes(database), "The index is unchanged");
    }

    @Test
    void rejectedAndRechunkedFilesAreListedInJson() throws Exception {
        assertEquals(0, run("--no-semantic"));
        Files.write(root.resolve("blob.txt"), new byte[] {0, 1, 0, 1});
        Path config = Files.writeString(
                root.resolve(".pecia.toml"), "[chunk]\nmax_tokens = 128\noverlap_tokens = 16\nmax_token = 1\n");

        assertEquals(0, run("--dry-run", "--no-semantic", "--json"));

        JsonObject document = JsonDocuments.parse(printed());
        assertEquals(config.toString(), document.get("config").getAsString());
        assertEquals(1, document.getAsJsonArray("warnings").size());
        assertEquals(List.of(0, 0, 3, 0, 1, 0), numbers(document.getAsJsonObject("files")));
        assertEquals(3, document.getAsJsonArray("rechunkedFiles").size());
        JsonObject rejected = document.getAsJsonArray("rejectedFiles").get(0).getAsJsonObject();
        assertEquals(List.of("path", "reason", "rejected", "message"), JsonDocuments.fields(rejected));
        assertEquals("blob.txt", rejected.get("path").getAsString());
        assertEquals("BINARY_CONTENT", rejected.get("reason").getAsString());
    }

    @Test
    void aSemanticDryRunSaysWhatItWouldDownloadAndEmbedInJson() throws Exception {
        assertEquals(0, run("--dry-run", "--semantic", "--json"));

        JsonObject document = JsonDocuments.parse(printed());
        assertTrue(document.get("semantic").getAsBoolean());
        assertEquals("NONE", document.get("index").getAsString());
        JsonObject embeddings = document.getAsJsonObject("embeddings");
        assertEquals(
                List.of("model", "modelState", "downloadBytes", "toEmbed", "toDiscard"),
                JsonDocuments.fields(embeddings));
        assertEquals("all-MiniLM-L6-v2", embeddings.get("model").getAsString());
        assertEquals("DOWNLOAD", embeddings.get("modelState").getAsString());
        assertEquals(
                SemanticModel.MINILM.size(), embeddings.get("downloadBytes").getAsLong());
        assertEquals(count(3, true), embeddings.get("toEmbed"));
        assertEquals(0, embeddings.get("toDiscard").getAsLong());
        assertFalse(Files.exists(database));
    }

    @Test
    void embeddingsToDiscardAreKnownCountsInJson() throws Exception {
        assertEquals(0, run("--semantic"));
        IndexCommandTest.moveEmbeddingsToAnEarlierProfile(database);

        assertEquals(0, run("--dry-run", "--semantic", "--json"));

        JsonObject embeddings = JsonDocuments.parse(printed()).getAsJsonObject("embeddings");
        assertEquals(count(3, false), embeddings.get("toEmbed"));
        assertEquals(3, embeddings.get("toDiscard").getAsLong());
    }

    @Test
    void aDamagedIndexIsAnErrorDocumentWithoutCounts() throws Exception {
        Files.createDirectories(database.getParent());
        Files.writeString(database, "not a database");

        assertEquals(1, run("--dry-run", "--json"));

        JsonObject document = JsonDocuments.parse(printed());
        assertEquals("UNREADABLE", document.get("index").getAsString());
        assertEquals(JsonNull.INSTANCE, document.get("files"));
        assertEquals(JsonNull.INSTANCE, document.get("chunks"));
        assertEquals(new JsonArray(), document.get("newFiles"));
        JsonObject error = document.getAsJsonObject("error");
        assertEquals("CORRUPT", error.get("reason").getAsString());
        List<String> errors = err.toString().replace("\r\n", "\n").lines().toList();
        assertEquals(2, errors.size(), err.toString());
        assertEquals("pecia index: FAILED: CORRUPT: " + error.get("message").getAsString(), errors.get(0));
        assertEquals("not a database", Files.readString(database));
    }

    @Test
    void anIncompleteScanListsItsIssueAndExitsWithOne() throws Exception {
        Files.createDirectories(root.resolve("drafts/.gitignore"));

        assertEquals(1, run("--dry-run", "--json"));

        JsonObject document = JsonDocuments.parse(printed());
        JsonObject issue = document.getAsJsonArray("scanIssues").get(0).getAsJsonObject();
        assertEquals("drafts/.gitignore", issue.get("path").getAsString());
        assertEquals("IO_ERROR", issue.get("cause").getAsString());
        assertEquals(JsonNull.INSTANCE, document.get("error"));
        assertEquals(3, document.getAsJsonObject("files").get("new").getAsInt());
        assertTrue(err.toString().contains("pecia index: incomplete scan"), err.toString());
    }

    @Test
    void aChildFolderReportsTheProjectRootAndPathsBelowIt() throws Exception {
        Files.writeString(root.resolve(".pecia.toml"), "[chunk]\n");
        Path docs = Files.createDirectories(root.resolve("docs"));
        Files.writeString(docs.resolve("a.md"), "# A\n\nText.\n");
        Files.createDirectories(docs.resolve("drafts/.gitignore"));

        assertEquals(1, runAt(docs, "--dry-run", "--json"));

        JsonObject document = JsonDocuments.parse(printed());
        assertEquals(root.toRealPath().toString(), document.get("root").getAsString());
        assertEquals(
                "docs/drafts/.gitignore",
                document.getAsJsonArray("scanIssues")
                        .get(0)
                        .getAsJsonObject()
                        .get("path")
                        .getAsString());
        assertEquals(planned("docs/a.md", 1, true), document.get("newFiles"));
    }

    @Test
    void aMissingFolderIsAnErrorDocumentWithoutAPreview() {
        assertEquals(1, runAt(root.resolve("missing"), "--dry-run", "--json"));

        JsonObject document = JsonDocuments.parse(printed());
        assertTrue(document.get("dryRun").getAsBoolean());
        assertEquals(19, document.size());

        for (String field :
                List.of("root", "database", "config", "semantic", "index", "files", "chunks", "embeddings")) {
            assertEquals(JsonNull.INSTANCE, document.get(field), field);
        }

        assertEquals(
                IndexCommand.IO_ERROR,
                document.getAsJsonObject("error").get("reason").getAsString());
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

    private static JsonObject count(long value, boolean estimated) {
        JsonObject count = new JsonObject();
        count.addProperty("value", value);
        count.addProperty("estimated", estimated);
        return count;
    }

    private static JsonArray planned(String path, long chunks, boolean estimated) {
        JsonObject file = new JsonObject();
        file.addProperty("path", path);
        file.add("chunks", count(chunks, estimated));
        JsonArray files = new JsonArray();
        files.add(file);
        return files;
    }

    private static List<Integer> numbers(JsonObject object) {
        return object.entrySet().stream()
                .map(entry -> entry.getValue().getAsInt())
                .toList();
    }

    private static IndexPreview.SemanticChanges semantic(IndexPreview.ModelState state, long bytes) {
        return new IndexPreview.SemanticChanges("model", state, bytes, IndexPreview.Count.known(0), 0);
    }

    private int run(String... args) {
        return runAt(root, args);
    }

    private int runAt(Path target, String... args) {
        out.getBuffer().setLength(0);
        err.getBuffer().setLength(0);
        IndexService service =
                IndexService.withModelProvider(new PeciaConfigLoader(new PeciaConfigParser()), listener -> TINY);
        CommandLine commandLine = new CommandLine(new IndexCommand(service));
        commandLine.setOut(new PrintWriter(out));
        commandLine.setErr(new PrintWriter(err));

        String[] arguments = new String[args.length + 1];
        arguments[0] = target.toString();
        System.arraycopy(args, 0, arguments, 1, args.length);

        return commandLine.execute(arguments);
    }

    /** Returns standard output with Windows line endings normalized. */
    private String printed() {
        return out.toString().replace("\r\n", "\n");
    }
}
