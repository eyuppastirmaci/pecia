package dev.eyuppastirmaci.pecia.cli.packaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import dev.eyuppastirmaci.pecia.Bootstrap;
import dev.eyuppastirmaci.pecia.cli.JsonDocuments;
import dev.eyuppastirmaci.pecia.cli.packaging.PackagedCli.Result;
import dev.eyuppastirmaci.pecia.cli.packaging.PackagedCli.Started;
import dev.eyuppastirmaci.pecia.semantic.SemanticModel;
import dev.eyuppastirmaci.pecia.semantic.VectorExtensionAccess;
import dev.eyuppastirmaci.pecia.testing.OfflineEnvironment;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * Semantic workflows through {@code java -jar} with the real model, which {@code referenceTest} supplies in the cache
 * that {@code PECIA_CACHE_DIR} names. Each test copies the model into its own cache, so the source cache is never
 * written to, and nothing is ever downloaded; runs under the offline guard also prove that no network is reached.
 */
@Tag("reference")
class PackagedCliRealModelIT {

    // Loading the model and the runtime takes a few seconds, and slow CI runners take longer.
    private static final Duration SEMANTIC = Duration.ofSeconds(120);
    private static final String BOOTSTRAP = Bootstrap.class.getName();
    private static final String ONNX_RUNTIME = "ai.onnxruntime.OrtEnvironment";
    // Shares no words with the refunds page beyond "money" and "back", so lexical search, which needs every word,
    // finds nothing.
    private static final String PARAPHRASE = "how long until I get my money back";

    @TempDir
    Path sandbox;

    private PackagedCli cli;
    private Path project;

    @BeforeEach
    void prepareProject() throws IOException {
        cli = PackagedCli.create(sandbox);
        project = cli.project();
        Files.writeString(
                project.resolve("refunds.md"),
                "# Refunds\n\nCustomers get their money back on the original card within five business days after"
                        + " we receive the returned item.\n");
        Files.writeString(
                project.resolve("shipping.md"),
                "# Shipping\n\nParcels leave the warehouse every weekday morning and arrive in two to four days.\n");
        Files.writeString(
                project.resolve("accounts.md"),
                "# Accounts\n\nUsers who forget their password reset it from the sign-in page with a link sent by"
                        + " email.\n");
        Files.writeString(project.resolve("budget.txt"), "Meeting notes about the quarterly budget and hiring.\n");
    }

    @Test
    void semanticIndexingAfterALexicalIndexEmbedsTheStoredChunksAndEveryModeSearchesWithTheModel() throws Exception {
        cli.enableOffline();
        placeModel(cli.offlineCache());
        Result lexical = cli.run(project, "index", "--json");
        assertEquals(0, lexical.exitCode(), lexical.err());
        long chunks = JsonDocuments.parse(lexical.out())
                .getAsJsonObject("chunks")
                .get("written")
                .getAsLong();

        JsonObject semantic = semanticIndex();

        assertEquals(List.of(0L, 4L), counts(semantic.getAsJsonObject("files"), "indexed", "unchanged"));
        JsonObject embeddings = semantic.getAsJsonObject("embeddings");
        assertEquals("CACHED", embeddings.get("modelSource").getAsString());
        assertEquals(List.of(0L, chunks, 0L), counts(embeddings, "embedded", "backfilled", "discarded"));
        assertEquals(
                List.of(chunks, 0L, 0L),
                counts(embeddings.getAsJsonObject("coverage"), "embedded", "stale", "missing"));

        // The first semantic query places the vector extension in the cache; later ones reuse it.
        JsonObject semanticQuery = query(VectorExtensionAccess.cachedLibrary(), PARAPHRASE, "--mode", "semantic");
        assertEquals("refunds.md", firstPath(semanticQuery));
        assertEquals(
                "COSINE_SIMILARITY",
                firstHit(semanticQuery).getAsJsonObject("score").get("kind").getAsString());

        Result lexicalQuery = cli.run(project, "query", PARAPHRASE, "--mode", "lexical", "--json");
        assertEquals(0, lexicalQuery.exitCode(), lexicalQuery.err());
        assertEquals(new JsonArray(), JsonDocuments.parse(lexicalQuery.out()).get("hits"));

        // Only the semantic side finds the answer, so hybrid search ranks it first through that side alone.
        JsonObject hybrid = query(null, PARAPHRASE, "--mode", "hybrid");
        assertEquals("refunds.md", firstPath(hybrid));
        JsonObject ranks = firstHit(hybrid).getAsJsonObject("fusedRanks");
        assertEquals(JsonNull.INSTANCE, ranks.get("lexical"));
        assertEquals(1, ranks.get("semantic").getAsInt());

        // Both sides find this one.
        JsonObject both = query(null, "password reset link", "--mode", "hybrid");
        assertEquals("accounts.md", firstPath(both));
        assertEquals(
                1, firstHit(both).getAsJsonObject("fusedRanks").get("lexical").getAsInt());

        // Without --mode, auto searches in hybrid mode because the index has embeddings and the model is cached.
        Result auto = cli.run(
                project,
                Map.of(),
                SEMANTIC,
                evidence -> evidence.verifySemanticQuery(BOOTSTRAP, null),
                "query",
                PARAPHRASE,
                "--json");
        assertEquals(0, auto.exitCode(), auto.err());
        assertEquals("", auto.err());
        JsonObject automatic = JsonDocuments.parse(auto.out());
        assertEquals("auto", automatic.get("requestedMode").getAsString());
        assertEquals("hybrid", automatic.get("effectiveMode").getAsString());
        assertEquals(JsonNull.INSTANCE, automatic.get("fallback"));
        assertEquals("refunds.md", firstPath(automatic));
    }

    @Test
    void aSemanticRerunEmbedsOnlyTheChangedFileAndDropsTheDeletedOne() throws Exception {
        cli.enableOffline();
        placeModel(cli.offlineCache());
        semanticIndex();
        Files.writeString(
                project.resolve("shipping.md"),
                "# Shipping\n\nOrders above fifty euros travel free of charge with a tracked courier.\n");
        Files.delete(project.resolve("budget.txt"));

        JsonObject rerun = semanticIndex();

        assertEquals(List.of(1L, 2L, 1L), counts(rerun.getAsJsonObject("files"), "indexed", "unchanged", "deleted"));
        long written = rerun.getAsJsonObject("chunks").get("written").getAsLong();
        JsonObject embeddings = rerun.getAsJsonObject("embeddings");
        assertEquals(List.of(written, 0L, 0L), counts(embeddings, "embedded", "backfilled", "discarded"));
        assertEquals(0, embeddings.getAsJsonObject("coverage").get("missing").getAsLong());

        JsonObject found =
                query(VectorExtensionAccess.cachedLibrary(), "is delivery free for large orders", "--mode", "semantic");
        assertEquals("shipping.md", firstPath(found));
        assertFalse(paths(found).contains("budget.txt"), found.toString());
    }

    @Test
    void embeddingsOfAnEarlierModelAreRebuiltWithoutRechunkingTheFiles() throws Exception {
        cli.enableOffline();
        placeModel(cli.offlineCache());
        JsonObject first = semanticIndex();
        long chunks = first.getAsJsonObject("chunks").get("written").getAsLong();
        Path database = project.resolve(".pecia/index.db");
        List<String> stored = chunkRows(database);
        moveEmbeddingsToAnEarlierProfile(database);

        JsonObject rebuilt = semanticIndex();

        assertEquals(List.of(0L, 4L), counts(rebuilt.getAsJsonObject("files"), "indexed", "unchanged"));
        assertEquals(0, rebuilt.getAsJsonObject("chunks").get("written").getAsLong());
        JsonObject embeddings = rebuilt.getAsJsonObject("embeddings");
        assertEquals(List.of(0L, chunks, chunks), counts(embeddings, "embedded", "backfilled", "discarded"));
        assertEquals(
                List.of(chunks, 0L, 0L),
                counts(embeddings.getAsJsonObject("coverage"), "embedded", "stale", "missing"));
        assertEquals(stored, chunkRows(database), "The chunks and their identities are kept");
        assertEquals(
                "refunds.md",
                firstPath(query(VectorExtensionAccess.cachedLibrary(), PARAPHRASE, "--mode", "semantic")));
    }

    @Test
    @DisabledOnOs(value = OS.WINDOWS, disabledReason = "Process.destroy() kills at once on Windows, so no hook runs")
    void aStoppedSemanticRunKeepsAConsistentIndexThatTheNextRunCompletes() throws Exception {
        Path cache = Files.createDirectory(sandbox.resolve("cache"));
        placeModel(cache);
        Map<String, String> environment = Map.of("PECIA_CACHE_DIR", cache.toString());
        writeCorpus(project.resolve("many"), 200);
        Path database = project.resolve(".pecia/index.db");

        Started started = cli.start(project, environment, "index", "--semantic", "--progress=none");
        awaitCommittedFile(started.process(), database);
        started.process().destroy();

        assertTrue(started.process().waitFor(SEMANTIC.toSeconds(), TimeUnit.SECONDS), "The run did not stop");
        // The JVM reports 128 plus the signal number once the shutdown hook returns.
        assertEquals(143, started.process().exitValue());
        assertEquals(
                "pecia index: cancelled; the files indexed so far are kept, and the next run continues from there\n",
                Files.readString(started.stderr()).replace("\r\n", "\n"));
        assertEquals("ok", queryDatabase(database, "SELECT integrity_check FROM pragma_integrity_check"));
        long committed = Long.parseLong(queryDatabase(database, "SELECT count(*) FROM files"));
        assertTrue(committed > 0 && committed < 204, committed + " files were committed before the stop");

        Result next = cli.run(project, environment, SEMANTIC, null, "index", "--semantic", "--json");

        assertEquals(0, next.exitCode(), next.err());
        JsonObject document = JsonDocuments.parse(next.out());
        assertEquals("COMPLETE", document.get("status").getAsString());
        assertEquals(
                204 - committed,
                document.getAsJsonObject("files").get("indexed").getAsLong());
        JsonObject coverage = document.getAsJsonObject("embeddings").getAsJsonObject("coverage");
        assertEquals(0, coverage.get("missing").getAsLong(), coverage.toString());
        assertEquals(0, coverage.get("stale").getAsLong(), coverage.toString());
    }

    @Test
    void aDamagedCachedModelIsNeverLoaded() throws Exception {
        cli.enableOffline();
        Path model = placeModel(cli.offlineCache());
        byte[] bytes = Files.readAllBytes(model);
        bytes[bytes.length / 2] ^= 1;
        Files.delete(model);
        Files.write(model, bytes);

        Result semantic = cli.run(
                project,
                Map.of(),
                SEMANTIC,
                evidence -> {
                    // The damaged copy is removed and a new download is attempted, which the guard denies.
                    assertEquals(
                            "READY\nDENY CONNECT "
                                    + SemanticModel.MINILM.downloadUri().getHost() + ":-1\n",
                            Files.readString(evidence.auditLog()));
                    assertFalse(OfflineEnvironment.initialized(evidence.initializationLog(), ONNX_RUNTIME));
                },
                "index",
                "--semantic",
                "--json");

        assertEquals(1, semantic.exitCode());
        JsonObject document = JsonDocuments.parse(semantic.out());
        assertEquals("MODEL_UNAVAILABLE", document.get("status").getAsString());
        assertEquals("OFFLINE", document.getAsJsonObject("error").get("reason").getAsString());
        assertFalse(Files.exists(model), "The damaged model is deleted, never used");
        assertFalse(Files.exists(project.resolve(".pecia")));
    }

    /** Runs a semantic index offline and returns its JSON document. */
    private JsonObject semanticIndex() throws Exception {
        Result result = cli.run(
                project,
                Map.of(),
                SEMANTIC,
                evidence -> evidence.verifyEmbedding(BOOTSTRAP),
                "index",
                "--semantic",
                "--json");

        assertEquals(0, result.exitCode(), result.err());
        return JsonDocuments.parse(result.out());
    }

    /**
     * Runs a query offline and returns its JSON document; {@code placedLibrary} is the vector extension the query must
     * place in the cache, or null when an earlier query placed it.
     */
    private JsonObject query(Path placedLibrary, String text, String... options) throws Exception {
        List<String> arguments = new ArrayList<>(List.of("query", text, "--json"));
        arguments.addAll(List.of(options));
        Result result = cli.run(
                project,
                Map.of(),
                SEMANTIC,
                evidence -> evidence.verifySemanticQuery(BOOTSTRAP, placedLibrary),
                arguments.toArray(String[]::new));

        assertEquals(0, result.exitCode(), result.err());
        return JsonDocuments.parse(result.out());
    }

    /**
     * Copies the model that {@code PECIA_CACHE_DIR} names into {@code cache}, with the lock file that the first run
     * would otherwise create, so the offline checks see only the runtime's own files appear.
     */
    private static Path placeModel(Path cache) throws IOException {
        String source = System.getenv("PECIA_CACHE_DIR");
        assertNotNull(source, "The real-model checks need PECIA_CACHE_DIR set to a cache that holds the model");
        Path from = modelFile(Path.of(source));
        assertTrue(Files.isRegularFile(from), "The real-model checks need the cached model at " + from);
        Path to = modelFile(cache);
        Files.createDirectories(to.getParent());
        Files.copy(from, to);
        Files.createFile(to.resolveSibling(".lock"));
        return to;
    }

    private static Path modelFile(Path cache) {
        SemanticModel model = SemanticModel.MINILM;
        String file = model.file().substring(model.file().lastIndexOf('/') + 1);

        return cache.resolve("models")
                .resolve(model.name())
                .resolve(model.revision())
                .resolve(file);
    }

    private static List<Long> counts(JsonObject object, String... names) {
        List<Long> counts = new ArrayList<>();

        for (String name : names) {
            counts.add(object.get(name).getAsLong());
        }

        return counts;
    }

    private static JsonObject firstHit(JsonObject document) {
        JsonArray hits = document.getAsJsonArray("hits");
        assertFalse(hits.isEmpty(), document.toString());
        return hits.get(0).getAsJsonObject();
    }

    private static String firstPath(JsonObject document) {
        return firstHit(document).get("path").getAsString();
    }

    private static List<String> paths(JsonObject document) {
        return document.getAsJsonArray("hits").asList().stream()
                .map(hit -> hit.getAsJsonObject().get("path").getAsString())
                .toList();
    }

    /** Returns every stored chunk with its file, position, content, and stable identity, in a fixed order. */
    private static List<String> chunkRows(Path database) throws SQLException {
        List<String> rows = new ArrayList<>();

        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database.toUri());
                ResultSet result = connection.createStatement().executeQuery("""
                    SELECT c.id, f.source_path, c.chunk_index, c.content, i.stable_id
                    FROM chunks c
                    JOIN files f ON f.id = c.file_id
                    JOIN chunk_identities i ON i.chunk_id = c.id
                    ORDER BY c.id
                    """)) {
            while (result.next()) {
                rows.add(result.getLong(1) + "|" + result.getString(2) + "|" + result.getInt(3) + "|"
                        + result.getString(4) + "|" + result.getString(5));
            }
        }

        return rows;
    }

    /** Rewrites the stored embeddings under another profile, as an index written before a model update holds them. */
    private static void moveEmbeddingsToAnEarlierProfile(Path database) throws SQLException {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database.toUri());
                Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                INSERT INTO embedding_profiles(model_id, model_revision, model_sha256, dimensions, tokenizer_algorithm,
                    vocabulary_sha256, max_input_tokens, pooling, normalization, input, fingerprint)
                SELECT model_id, 'earlier-revision', model_sha256, dimensions, tokenizer_algorithm, vocabulary_sha256,
                    max_input_tokens, pooling, normalization, input, printf('%064d', 0)
                FROM embedding_profiles
                """);
            statement.executeUpdate("""
                UPDATE chunk_embeddings SET profile_id = (SELECT id FROM embedding_profiles WHERE model_revision =
                    'earlier-revision')
                """);
        }
    }

    private static String queryDatabase(Path database, String sql) throws SQLException {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database.toUri());
                ResultSet rows = connection.createStatement().executeQuery(sql)) {
            rows.next();
            return rows.getString(1);
        }
    }

    /** Writes enough Markdown files that embedding them takes several seconds. */
    private static void writeCorpus(Path directory, int files) throws IOException {
        Files.createDirectories(directory);
        StringBuilder paragraph = new StringBuilder();

        for (int line = 0; line < 40; line++) {
            paragraph.append("Refund policy line ").append(line).append(" explains returns. ");
        }

        String body = (paragraph + "\n\n").repeat(4);

        for (int file = 0; file < files; file++) {
            Files.writeString(directory.resolve("note" + file + ".md"), "# Note " + file + "\n\n" + body);
        }
    }

    /** Waits until the run has committed at least one file, so the stop lands in the middle of the run. */
    private static void awaitCommittedFile(Process process, Path database) throws Exception {
        long deadline = System.nanoTime() + SEMANTIC.toNanos();

        while (true) {
            assertTrue(process.isAlive(), "The run ended before it could be stopped");
            assertTrue(System.nanoTime() < deadline, "The run committed no file in time");

            if (Files.exists(database)) {
                try {
                    if (Long.parseLong(queryDatabase(database, "SELECT count(*) FROM files")) > 0) {
                        return;
                    }
                } catch (SQLException busyOrUncreated) {
                    // The run may hold a write lock or still be creating the schema.
                }
            }

            Thread.sleep(20);
        }
    }
}
