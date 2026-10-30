package dev.eyuppastirmaci.pecia.cli.packaging;

import static dev.eyuppastirmaci.pecia.cli.packaging.PackagedCliAssertions.assertNoStackTrace;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import dev.eyuppastirmaci.pecia.cli.JsonDocuments;
import dev.eyuppastirmaci.pecia.cli.packaging.PackagedCli.Result;
import dev.eyuppastirmaci.pecia.semantic.SemanticModel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code --json} through {@code java -jar}, offline where the command allows it: stdout carries exactly one JSON document for every outcome, in
 * UTF-8, while stderr and exit codes stay those of the text output.
 */
class PackagedCliJsonIT {

    @TempDir
    Path sandbox;

    private PackagedCli cli;
    private Path project;

    @BeforeEach
    void prepareProject() throws Exception {
        cli = PackagedCli.create(sandbox);
        project = cli.project();
    }

    @Test
    void indexingAndQueryingPrintOneDocumentEachWithUnicodeIntact() throws Exception {
        cli.enableOffline();
        Files.writeString(project.resolve("guide.md"), "# Rehber\n\nİstanbul 😀 iade süresi beş gün; İSTANBUL.\n");
        Files.writeString(project.resolve("notes.txt"), "Other notes.\n");

        Result index = cli.run(project, "index", "--json");

        assertEquals(0, index.exitCode(), index.err());
        assertEquals("", index.err());
        JsonObject run = JsonDocuments.parse(index.out());
        assertEquals("COMPLETE", run.get("status").getAsString());
        assertEquals(project.toString(), run.get("root").getAsString());
        assertEquals(2, run.getAsJsonObject("files").get("indexed").getAsInt());

        Result query = cli.runReadOnly(project, "query", "istanbul", "--json");

        JsonObject document = JsonDocuments.parse(query.out());
        // Without embeddings, auto searches lexically and says why only in the document.
        assertEquals("auto", document.get("requestedMode").getAsString());
        assertEquals("lexical", document.get("effectiveMode").getAsString());
        assertEquals(
                "EMBEDDINGS_MISSING",
                document.getAsJsonObject("fallback").get("reason").getAsString());
        JsonArray hits = document.getAsJsonArray("hits");
        assertEquals(1, hits.size());
        JsonObject hit = hits.get(0).getAsJsonObject();
        assertEquals("guide.md", hit.get("path").getAsString());
        assertEquals(List.of("Rehber"), strings(hit.getAsJsonArray("headingPath")));
        String snippet = hit.get("snippet").getAsString();
        assertTrue(snippet.contains("İstanbul 😀 iade süresi beş gün"), snippet);
        assertEquals(List.of("İstanbul", "İSTANBUL"), matched(hit));
    }

    @Test
    void queryFailuresPrintAnErrorDocumentAndKeepStderrAndExitCodes() throws Exception {
        cli.enableOffline();
        Result missingIndex = cli.run(project, "query", "needle", "--json");

        assertEquals(1, missingIndex.exitCode());
        JsonObject error = JsonDocuments.parse(missingIndex.out()).getAsJsonObject("error");
        assertEquals("INDEX_NOT_FOUND", error.get("reason").getAsString());
        List<String> lines = missingIndex.err().lines().toList();
        assertEquals("pecia query: INDEX_NOT_FOUND: " + error.get("message").getAsString(), lines.get(0));
        assertTrue(lines.get(1).startsWith("Run: java -jar '" + cli.executableJar() + "' index"), lines.get(1));
        assertFalse(Files.exists(project.resolve(".pecia")));

        Files.writeString(project.resolve("guide.md"), "# Guide\n\nneedle\n");
        assertEquals(0, cli.run(project, "index").exitCode());

        Result empty = cli.run(project, "query", "absent", "--json");

        assertEquals(0, empty.exitCode(), empty.err());
        assertEquals(new JsonArray(), JsonDocuments.parse(empty.out()).get("hits"));

        Result usage = cli.run(project, "query", "needle", "--mode", "fuzzy", "--json");

        assertEquals(2, usage.exitCode());
        assertEquals("", usage.out());
        assertNoStackTrace(usage.err());
    }

    @Test
    void aMissingSemanticPrerequisiteKeepsExitCodeThree() throws Exception {
        // An explicit semantic query reads the index's embedding state, which the lexical offline check forbids.
        Files.writeString(project.resolve("guide.md"), "# Guide\n\nneedle\n");
        assertEquals(0, cli.run(project, "index").exitCode());

        Result semantic = cli.run(project, "query", "needle", "--mode", "semantic", "--json");

        assertEquals(3, semantic.exitCode());
        JsonObject document = JsonDocuments.parse(semantic.out());
        assertEquals(JsonNull.INSTANCE, document.get("hits"));
        assertEquals(
                "EMBEDDINGS_MISSING",
                document.getAsJsonObject("error").get("reason").getAsString());
        assertTrue(semantic.err().startsWith("pecia query: EMBEDDINGS_MISSING: "), semantic.err());
    }

    @Test
    void aPartialRunListsItsIssuesWhileStderrStillExplainsThem() throws Exception {
        cli.enableOffline();
        Path config = Files.writeString(project.resolve(".pecia.toml"), "[chunk]\nmax_token = 64\n");
        Files.writeString(project.resolve("good.txt"), "good\n");
        Files.write(project.resolve("blob.txt"), new byte[] {'b', 0, 'n'});

        Result partial = cli.run(project, "index", "--json");

        assertEquals(1, partial.exitCode());
        JsonObject document = JsonDocuments.parse(partial.out());
        assertEquals("PARTIAL", document.get("status").getAsString());
        assertEquals(config.toString(), document.get("config").getAsString());
        assertEquals(1, document.getAsJsonArray("warnings").size());
        JsonObject issue = document.getAsJsonArray("issues").get(0).getAsJsonObject();
        assertEquals("blob.txt", issue.get("path").getAsString());
        assertEquals("BINARY_CONTENT", issue.get("reason").getAsString());
        List<String> lines = partial.err().lines().toList();
        assertEquals(3, lines.size(), partial.err());
        assertTrue(lines.get(0).startsWith("warning: " + config + ":2:1: "), lines.get(0));
        assertTrue(lines.get(1).startsWith("warning: blob.txt: BINARY_CONTENT: "), lines.get(1));
        assertTrue(lines.get(2).startsWith("pecia index: PARTIAL: "), lines.get(2));
    }

    @Test
    void aDamagedIndexFailsTheRunAndTheDryRunWithTheSameError() throws Exception {
        cli.enableOffline();
        Files.writeString(project.resolve("notes.txt"), "needle\n");
        Path database = Files.createDirectories(project.resolve(".pecia")).resolve("index.db");
        byte[] damaged = "not a database".getBytes(StandardCharsets.UTF_8);
        Files.write(database, damaged);

        Result run = cli.run(project, "index", "--json");
        Result dryRun = cli.run(project, "index", "--dry-run", "--json");

        assertEquals(1, run.exitCode());
        assertEquals(1, dryRun.exitCode());
        JsonObject failed = JsonDocuments.parse(run.out());
        JsonObject previewed = JsonDocuments.parse(dryRun.out());
        assertEquals("FAILED", failed.get("status").getAsString());
        assertEquals("UNREADABLE", previewed.get("index").getAsString());
        assertEquals("CORRUPT", failed.getAsJsonObject("error").get("reason").getAsString());
        assertEquals("CORRUPT", previewed.getAsJsonObject("error").get("reason").getAsString());
        assertEquals(run.err(), dryRun.err());
        assertEquals(new String(damaged, StandardCharsets.UTF_8), Files.readString(database));
    }

    @Test
    void dryRunsPrintTheirPreviewAndWriteNothing() throws Exception {
        cli.enableOffline();
        Files.writeString(project.resolve("guide.md"), "# Guide\n\nRefunds take five days.\n");
        Files.writeString(project.resolve("old.txt"), "Old notes.\n");
        assertEquals(0, cli.run(project, "index").exitCode());
        Files.delete(project.resolve("old.txt"));
        Files.writeString(project.resolve("added.md"), "# Added\n\nA new page.\n");

        Result lexical = cli.runReadOnly(project, "index", "--dry-run", "--json");

        JsonObject document = JsonDocuments.parse(lexical.out());
        assertTrue(document.get("dryRun").getAsBoolean());
        assertEquals("CURRENT", document.get("index").getAsString());
        assertEquals(List.of("added.md"), paths(document.getAsJsonArray("newFiles")));
        assertEquals(List.of("old.txt"), paths(document.getAsJsonArray("removedFiles")));
        assertEquals(JsonNull.INSTANCE, document.get("embeddings"));

        Result semantic = cli.runSemanticPreview(project, "index", "--dry-run", "--semantic", "--json");

        JsonObject embeddings = JsonDocuments.parse(semantic.out()).getAsJsonObject("embeddings");
        assertEquals("DOWNLOAD", embeddings.get("modelState").getAsString());
        assertEquals(
                SemanticModel.MINILM.size(), embeddings.get("downloadBytes").getAsLong());
        assertTrue(embeddings.getAsJsonObject("toEmbed").get("estimated").getAsBoolean());
    }

    @Test
    void anOfflineSemanticRunReportsTheUnavailableModel() throws Exception {
        cli.enableOffline();
        Files.writeString(project.resolve("guide.md"), "# Guide\n\nneedle\n");
        SemanticModel model = SemanticModel.MINILM;
        Path lockFile = Path.of("models", model.name(), model.revision(), ".lock");

        Result semantic = cli.runDeniedDownload(
                project, model.downloadUri().getHost(), lockFile, "index", "--semantic", "--json");

        assertEquals(1, semantic.exitCode());
        JsonObject document = JsonDocuments.parse(semantic.out());
        assertEquals("MODEL_UNAVAILABLE", document.get("status").getAsString());
        assertEquals("OFFLINE", document.getAsJsonObject("error").get("reason").getAsString());
        assertTrue(semantic.err().startsWith("Downloading all-MiniLM-L6-v2"), semantic.err());
        assertFalse(Files.exists(project.resolve(".pecia")));
    }

    private static List<String> paths(JsonArray files) {
        return files.asList().stream()
                .map(file -> file.getAsJsonObject().get("path").getAsString())
                .toList();
    }

    private static List<String> strings(JsonArray array) {
        return array.asList().stream().map(JsonElement::getAsString).toList();
    }

    /** Returns the text of each match range, cut from the snippet by code points. */
    private static List<String> matched(JsonObject hit) {
        int[] codePoints = hit.get("snippet").getAsString().codePoints().toArray();

        return hit.getAsJsonArray("matches").asList().stream()
                .map(JsonElement::getAsJsonObject)
                .map(range -> new String(
                        codePoints,
                        range.get("start").getAsInt(),
                        range.get("end").getAsInt() - range.get("start").getAsInt()))
                .toList();
    }
}
