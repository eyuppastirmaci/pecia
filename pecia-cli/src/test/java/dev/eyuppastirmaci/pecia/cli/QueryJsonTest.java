package dev.eyuppastirmaci.pecia.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import dev.eyuppastirmaci.pecia.config.PeciaConfigLoader;
import dev.eyuppastirmaci.pecia.config.PeciaConfigParser;
import dev.eyuppastirmaci.pecia.config.SearchMode;
import dev.eyuppastirmaci.pecia.index.IndexOptions;
import dev.eyuppastirmaci.pecia.index.IndexService;
import dev.eyuppastirmaci.pecia.search.QueryOptions;
import dev.eyuppastirmaci.pecia.search.QueryService;
import dev.eyuppastirmaci.pecia.search.SearchHit;
import dev.eyuppastirmaci.pecia.search.SearchRequest;
import dev.eyuppastirmaci.pecia.testing.TinyModel;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

/** {@code query --json} prints one JSON document for every outcome and leaves stderr and exit codes as they are. */
class QueryJsonTest {

    private static final List<String> FIELDS = List.of(
            "schemaVersion",
            "command",
            "query",
            "limit",
            "requestedMode",
            "effectiveMode",
            "fallback",
            "notices",
            "queryShape",
            "embeddingCoverage",
            "warnings",
            "hits",
            "error");
    private static final List<String> HIT_FIELDS = List.of(
            "rank",
            "path",
            "documentType",
            "lines",
            "chunkIndex",
            "stableId",
            "headingPath",
            "score",
            "fusedRanks",
            "snippet",
            "matches");

    @TempDir
    Path root;

    private final PeciaConfigLoader loader = new PeciaConfigLoader(new PeciaConfigParser());
    private final StringWriter out = new StringWriter();
    private final StringWriter err = new StringWriter();

    @BeforeEach
    void useCanonicalRoot() throws IOException {
        root = root.toRealPath();
    }

    @Test
    void aLexicalQueryDescribesEveryHitWithItsMatches() throws Exception {
        Files.writeString(root.resolve("guide.md"), "# Guide\n\nNeedle İstanbul 😀 needle\n");
        Files.writeString(root.resolve("plain.txt"), "a needle\r\nsecond line\r\n");
        new IndexService(loader).index(root);
        List<SearchHit> expected = new QueryService(loader).search(root, new SearchRequest("needle"));

        assertEquals(0, run("needle", "--root", root.toString(), "--mode", "lexical", "--json"));

        assertEquals("", err.toString());
        JsonObject document = JsonDocuments.parse(out.toString());
        assertEquals(FIELDS, JsonDocuments.fields(document));
        assertEquals(1, document.get("schemaVersion").getAsInt());
        assertEquals("query", document.get("command").getAsString());
        assertEquals("needle", document.get("query").getAsString());
        assertEquals(SearchRequest.DEFAULT_LIMIT, document.get("limit").getAsInt());
        assertEquals("lexical", document.get("requestedMode").getAsString());
        assertEquals("lexical", document.get("effectiveMode").getAsString());
        assertNull(document, "fallback", "queryShape", "embeddingCoverage", "error");
        assertEquals(new JsonArray(), document.get("notices"));
        assertEquals(new JsonArray(), document.get("warnings"));

        JsonArray hits = document.getAsJsonArray("hits");
        assertEquals(expected.size(), hits.size());

        for (int index = 0; index < hits.size(); index++) {
            JsonObject hit = hits.get(index).getAsJsonObject();
            SearchHit want = expected.get(index);
            assertEquals(HIT_FIELDS, JsonDocuments.fields(hit));
            assertEquals(index + 1, hit.get("rank").getAsInt());
            assertEquals(want.sourcePath().toString(), hit.get("path").getAsString());
            assertEquals(want.chunkIndex(), hit.get("chunkIndex").getAsInt());
            assertEquals(
                    want.stableId().orElseThrow().value(), hit.get("stableId").getAsString());
            assertEquals("SQLITE_BM25", hit.getAsJsonObject("score").get("kind").getAsString());
            assertEquals(
                    want.score().value(),
                    hit.getAsJsonObject("score").get("value").getAsDouble());
            assertEquals(JsonNull.INSTANCE, hit.get("fusedRanks"));
            assertEquals(want.snippet(), hit.get("snippet").getAsString());
            assertEquals(want.metadata().headingPath(), strings(hit.getAsJsonArray("headingPath")));
        }

        JsonObject guide = hit(hits, "guide.md");
        assertEquals("MARKDOWN", guide.get("documentType").getAsString());
        assertEquals(List.of("Guide"), strings(guide.getAsJsonArray("headingPath")));
        assertEquals(
                List.of("Needle", "needle"),
                ranges(guide).stream()
                        .map(range -> cut(guide.get("snippet").getAsString(), range))
                        .toList());
        JsonObject plain = hit(hits, "plain.txt");
        assertEquals(List.of("start", "end"), JsonDocuments.fields(plain.getAsJsonObject("lines")));
        assertEquals(1, plain.getAsJsonObject("lines").get("start").getAsInt());
        assertEquals(2, plain.getAsJsonObject("lines").get("end").getAsInt());
        assertEquals(new JsonArray(), plain.getAsJsonArray("headingPath"));
    }

    @Test
    void noMatchesGiveAnEmptyListOfHits() throws Exception {
        Files.writeString(root.resolve("guide.md"), "# Guide\n\nText.\n");
        new IndexService(loader).index(root);

        assertEquals(0, run("absent", "--root", root.toString(), "--json"));

        JsonObject document = JsonDocuments.parse(out.toString());
        assertEquals(new JsonArray(), document.get("hits"));
        assertFalse(out.toString().contains("No results."));
    }

    @Test
    void aHybridQueryReportsItsShapeCoverageAndTheSidesOfEachHit() throws Exception {
        String refunds = "Refunds are issued to the original card within five days.";
        Files.writeString(root.resolve("refunds.txt"), refunds + "\n");
        Files.writeString(root.resolve("shipping.txt"), "Parcels leave the warehouse every weekday morning.\n");
        IndexService.withModelProvider(loader, listener -> TinyModel.cached()).index(root, IndexOptions.semantic(true));
        // Indexed lexically afterwards, so its chunk has no embedding.
        Files.writeString(root.resolve("notes.txt"), "Meeting notes.\n");
        new IndexService(loader).index(root);
        QueryService hybrid = QueryService.withModelLookup(loader, () -> Optional.of(TinyModel.cached()));
        List<SearchHit> expected = hybrid.query(
                        root, new SearchRequest("refunds"), QueryOptions.mode(SearchMode.HYBRID))
                .hits();

        assertEquals(0, run(hybrid, "refunds", "--root", root.toString(), "--mode", "hybrid", "--json"));

        assertEquals("", err.toString());
        JsonObject document = JsonDocuments.parse(out.toString());
        assertEquals("hybrid", document.get("requestedMode").getAsString());
        assertEquals("hybrid", document.get("effectiveMode").getAsString());
        assertEquals("NATURAL_LANGUAGE", document.get("queryShape").getAsString());
        JsonObject coverage = document.getAsJsonObject("embeddingCoverage");
        assertEquals(List.of("embedded", "stale", "missing"), JsonDocuments.fields(coverage));
        assertEquals(2, coverage.get("embedded").getAsLong());
        assertEquals(0, coverage.get("stale").getAsLong());
        assertEquals(1, coverage.get("missing").getAsLong());

        JsonArray hits = document.getAsJsonArray("hits");
        assertEquals(2, hits.size());
        JsonObject first = hits.get(0).getAsJsonObject();
        assertEquals("refunds.txt", first.get("path").getAsString());
        assertEquals(
                "RECIPROCAL_RANK_FUSION",
                first.getAsJsonObject("score").get("kind").getAsString());
        assertEquals(
                expected.get(0).score().value(),
                first.getAsJsonObject("score").get("value").getAsDouble());
        assertEquals(1, first.getAsJsonObject("fusedRanks").get("lexical").getAsInt());
        assertEquals(1, first.getAsJsonObject("fusedRanks").get("semantic").getAsInt());
        assertEquals(
                List.of("Refunds"),
                ranges(first).stream()
                        .map(range -> cut(first.get("snippet").getAsString(), range))
                        .toList());
        // Only the semantic side ranked the second hit, so its lexical rank is null and it has no ranges.
        JsonObject second = hits.get(1).getAsJsonObject();
        assertEquals(JsonNull.INSTANCE, second.getAsJsonObject("fusedRanks").get("lexical"));
        assertEquals(2, second.getAsJsonObject("fusedRanks").get("semantic").getAsInt());
        assertEquals(new JsonArray(), second.get("matches"));
    }

    @Test
    void autoReportsWhyItSearchedLexicallyAndStderrStillSaysSo() throws Exception {
        Files.writeString(root.resolve("refunds.txt"), "Refunds are issued to the original card.\n");
        IndexService.withModelProvider(loader, listener -> TinyModel.cached()).index(root, IndexOptions.semantic(true));

        // The test cache never holds the model.
        assertEquals(0, run("refunds", "--root", root.toString(), "--json"));

        JsonObject document = JsonDocuments.parse(out.toString());
        assertEquals("auto", document.get("requestedMode").getAsString());
        assertEquals("lexical", document.get("effectiveMode").getAsString());
        JsonObject fallback = document.getAsJsonObject("fallback");
        assertEquals("MODEL_NOT_CACHED", fallback.get("reason").getAsString());
        assertTrue(err.toString()
                .startsWith(
                        "note: searched lexically: " + fallback.get("message").getAsString()));
    }

    @Test
    void noticesCarryTheirCodes() throws Exception {
        Files.writeString(root.resolve("refunds.txt"), "Refunds are issued to the original card.\n");
        IndexService.withModelProvider(loader, listener -> TinyModel.cached()).index(root, IndexOptions.semantic(true));
        QueryService service = QueryService.withModelLookup(loader, () -> Optional.of(TinyModel.cached()));

        assertEquals(0, run(service, "refunds ".repeat(65).strip(), "--root", root.toString(), "--json"));

        JsonArray notices = JsonDocuments.parse(out.toString()).getAsJsonArray("notices");
        assertEquals(1, notices.size());
        assertEquals(
                "LEXICAL_SIDE_SKIPPED",
                notices.get(0).getAsJsonObject().get("code").getAsString());
        assertEquals(
                "note: " + notices.get(0).getAsJsonObject().get("message").getAsString(),
                err.toString().strip());
    }

    @Test
    void configurationWarningsAreListedAndStillPrinted() throws Exception {
        Path config = Files.writeString(root.resolve(".pecia.toml"), "[chunk]\nmax_token = 64\n");
        Files.writeString(root.resolve("guide.md"), "# Guide\n\nneedle\n");
        new IndexService(loader).index(root);

        assertEquals(0, run("needle", "--root", root.toString(), "--json"));

        JsonArray warnings = JsonDocuments.parse(out.toString()).getAsJsonArray("warnings");
        assertEquals(1, warnings.size());
        JsonObject warning = warnings.get(0).getAsJsonObject();
        assertEquals(List.of("code", "source", "line", "column", "message"), JsonDocuments.fields(warning));
        assertEquals("UNKNOWN_CONFIG_KEY", warning.get("code").getAsString());
        assertEquals(config.toString(), warning.get("source").getAsString());
        assertEquals(2, warning.get("line").getAsInt());
        assertEquals(1, warning.get("column").getAsInt());
        assertTrue(err.toString().startsWith("warning: " + config + ":2:1: "), err.toString());
    }

    @Test
    void aMissingIndexIsAnErrorDocumentWithTheSameStderrAndExitCode() throws Exception {
        Path config = Files.writeString(root.resolve(".pecia.toml"), "[chunk]\nmax_token = 64\n");

        assertEquals(1, run("needle", "--root", root.toString(), "--json"));

        JsonObject document = JsonDocuments.parse(out.toString());
        assertEquals(FIELDS, JsonDocuments.fields(document));
        assertNull(document, "requestedMode", "effectiveMode", "fallback", "queryShape", "embeddingCoverage", "hits");
        assertEquals(1, document.getAsJsonArray("warnings").size());
        JsonObject error = document.getAsJsonObject("error");
        assertEquals(List.of("reason", "message"), JsonDocuments.fields(error));
        assertEquals("INDEX_NOT_FOUND", error.get("reason").getAsString());
        List<String> lines = err.toString().lines().toList();
        assertTrue(lines.get(0).startsWith("warning: " + config), lines.get(0));
        assertEquals("pecia query: INDEX_NOT_FOUND: " + error.get("message").getAsString(), lines.get(1));
        assertTrue(lines.get(2).startsWith("Run: java -jar "), lines.get(2));
    }

    @Test
    void aMissingModelKeepsExitCodeThree() throws Exception {
        Files.writeString(root.resolve("refunds.txt"), "Refunds are issued to the original card.\n");
        IndexService.withModelProvider(loader, listener -> TinyModel.cached()).index(root, IndexOptions.semantic(true));

        assertEquals(3, run("refunds", "--root", root.toString(), "--mode", "semantic", "--json"));

        JsonObject error = JsonDocuments.parse(out.toString()).getAsJsonObject("error");
        assertEquals("MODEL_NOT_CACHED", error.get("reason").getAsString());
        assertTrue(err.toString().startsWith("pecia query: MODEL_NOT_CACHED: "), err.toString());
    }

    @Test
    void anEmbedderFailureIncludesItsCauseAsStderrDoes() throws Exception {
        Files.writeString(root.resolve("refunds.txt"), "Refunds are issued to the original card.\n");
        IndexService.withModelProvider(loader, listener -> TinyModel.cached()).index(root, IndexOptions.semantic(true));
        QueryService broken =
                QueryService.withModelLookup(loader, () -> Optional.of(TinyModel.cached("missing-input")));

        assertEquals(3, run(broken, "refunds", "--root", root.toString(), "--mode", "hybrid", "--json"));

        JsonObject error = JsonDocuments.parse(out.toString()).getAsJsonObject("error");
        assertEquals("EMBEDDER_UNAVAILABLE", error.get("reason").getAsString());
        assertEquals(
                "pecia query: EMBEDDER_UNAVAILABLE: " + error.get("message").getAsString(),
                err.toString().lines().findFirst().orElseThrow());
    }

    @Test
    void anInvalidLimitIsAnInvalidInputError() {
        assertEquals(1, run("needle", "--root", root.toString(), "--limit", "0", "--json"));

        JsonObject document = JsonDocuments.parse(out.toString());
        assertEquals(0, document.get("limit").getAsInt());
        JsonObject error = document.getAsJsonObject("error");
        assertEquals("INVALID_INPUT", error.get("reason").getAsString());
        assertEquals(
                "pecia query: " + error.get("message").getAsString(),
                err.toString().strip());
        assertFalse(Files.exists(root.resolve(".pecia")));
    }

    @Test
    void usageErrorsStayPlainTextWithNothingOnStdout() {
        assertEquals(2, run("needle", "--root", root.toString(), "--mode", "fuzzy", "--json"));

        assertEquals("", out.toString());
        assertTrue(err.toString().contains("expected one of"), err.toString());
    }

    private static void assertNull(JsonObject document, String... names) {
        for (String name : names) {
            assertEquals(JsonNull.INSTANCE, document.get(name), name);
        }
    }

    private static JsonObject hit(JsonArray hits, String path) {
        for (JsonElement hit : hits) {
            if (hit.getAsJsonObject().get("path").getAsString().equals(path)) {
                return hit.getAsJsonObject();
            }
        }

        throw new AssertionError("No hit for " + path + " in " + hits);
    }

    private static List<int[]> ranges(JsonObject hit) {
        return hit.getAsJsonArray("matches").asList().stream()
                .map(JsonElement::getAsJsonObject)
                .map(range -> new int[] {
                    range.get("start").getAsInt(), range.get("end").getAsInt()
                })
                .toList();
    }

    /** Cuts a range from the snippet by code points, as the ranges count them. */
    private static String cut(String snippet, int[] range) {
        int[] codePoints = snippet.codePoints().toArray();
        return new String(codePoints, range[0], range[1] - range[0]);
    }

    private static List<String> strings(JsonArray array) {
        return array.asList().stream().map(JsonElement::getAsString).toList();
    }

    private int run(String... args) {
        return run(new QueryService(loader), args);
    }

    private int run(QueryService service, String... args) {
        CommandLine command = new CommandLine(new QueryCommand(service));
        command.setOut(new PrintWriter(out));
        command.setErr(new PrintWriter(err));

        return command.execute(args);
    }
}
