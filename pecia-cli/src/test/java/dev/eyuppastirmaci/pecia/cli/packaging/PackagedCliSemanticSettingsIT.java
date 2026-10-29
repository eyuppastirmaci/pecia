package dev.eyuppastirmaci.pecia.cli.packaging;

import static dev.eyuppastirmaci.pecia.cli.packaging.PackagedCliAssertions.assertSuccess;
import static dev.eyuppastirmaci.pecia.cli.packaging.PackagedCliAssertions.hitLocations;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.eyuppastirmaci.pecia.cli.packaging.PackagedCli.Result;
import dev.eyuppastirmaci.pecia.semantic.SemanticModel;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Semantic indexing and search mode settings through {@code java -jar}: flags override {@code .pecia.toml} for one
 * run, and a configured mode that is not available fails without touching the index.
 */
class PackagedCliSemanticSettingsIT {

    @TempDir
    Path sandbox;

    private PackagedCli cli;
    private Path project;
    private Path config;

    @BeforeEach
    void prepareProject() throws IOException {
        cli = PackagedCli.create(sandbox);
        project = cli.project();
        config = project.resolve(".pecia.toml");
        Files.writeString(project.resolve("guide.md"), "# Guide\n\nmodeneedle\n");
    }

    @Test
    void dryRunsReportTheSemanticChoiceWithFlagsOverridingTheFile() throws Exception {
        // Dry runs must stay offline whatever semantic indexing resolves to.
        cli.enableOffline();
        Files.writeString(config, "[semantic]\nenabled = true\n");

        assertEquals("semantic: enabled", semanticLine(cli.runReadOnly(project, "index", "--dry-run")));
        assertEquals(
                "semantic: disabled", semanticLine(cli.runReadOnly(project, "index", "--dry-run", "--no-semantic")));

        Files.writeString(config, "[semantic]\nenabled = false\n");

        assertEquals("semantic: enabled", semanticLine(cli.runReadOnly(project, "index", "--dry-run", "--semantic")));
    }

    @Test
    void modeFlagOverridesAConfiguredModeThatTheIndexCannotServe() throws Exception {
        Files.writeString(config, "[search]\nmode = \"hybrid\"\n");
        assertSuccess(cli.run(project, "index"));
        Path database = project.resolve(".pecia/index.db");
        List<Path> entries = cli.projectEntries();
        byte[] before = Files.readAllBytes(database);

        Result configured = cli.run(project, "query", "modeneedle");

        assertEquals(3, configured.exitCode());
        assertEquals("", configured.out());
        assertEquals(
                "pecia query: EMBEDDINGS_MISSING: The index has no embeddings for semantic search; index the project"
                        + " semantically first\nRun: java -jar '" + cli.executableJar() + "' index --semantic '"
                        + project + "'\n",
                configured.err());
        assertEquals(entries, cli.projectEntries());
        assertArrayEquals(before, Files.readAllBytes(database));

        Result lexical = cli.runReadOnly(project, "query", "modeneedle", "--mode", "lexical");

        assertEquals(List.of("guide.md:1-3"), hitLocations(lexical));
    }

    @Test
    void offlineSemanticIndexingFailsWithTheLexicalWayOutAndLexicalCommandsStayOffline() throws Exception {
        cli.enableOffline();
        SemanticModel model = SemanticModel.MINILM;
        Path lockFile = Path.of("models", model.name(), model.revision(), ".lock");

        Result semantic =
                cli.runDeniedDownload(project, model.downloadUri().getHost(), lockFile, "index", "--semantic");

        assertEquals(1, semantic.exitCode());
        assertEquals("", semantic.out());
        // The notice comes first: the download is announced before its connection is denied.
        assertEquals(
                List.of(
                        "Downloading all-MiniLM-L6-v2 (90.4 MB) from huggingface.co into "
                                + cacheDirectory().resolve(lockFile).getParent(),
                        "pecia index: MODEL_UNAVAILABLE: OFFLINE: Could not reach huggingface.co to download"
                                + " all-MiniLM-L6-v2",
                        "Run with --no-semantic to build only the lexical index."),
                semantic.err().lines().toList());
        assertFalse(Files.exists(project.resolve(".pecia")));

        // The lexical way out works offline, and nothing below may reach for the network or the model.
        Result lexical = cli.run(project, "index", "--no-semantic");
        assertSuccess(lexical);
        assertFalse(lexical.out().contains("model:"), lexical.out());
        Files.writeString(config, "[semantic]\nenabled = true\n");
        assertEquals("semantic: enabled", semanticLine(cli.runReadOnly(project, "index", "--dry-run")));
        assertEquals(List.of("guide.md:1-3"), hitLocations(cli.runReadOnly(project, "query", "modeneedle")));
    }

    @Test
    void lexicalCommandsUpdateAndSearchAnIndexWithEmbeddingsOffline() throws Exception {
        cli.enableOffline();
        Files.writeString(project.resolve("notes.txt"), "notesneedle\n");
        Files.writeString(project.resolve("old.txt"), "oldneedle\n");
        assertSuccess(cli.run(project, "index"));
        Path database = project.resolve(".pecia/index.db");
        storeVectorsForEveryChunk(database);
        Files.writeString(project.resolve("notes.txt"), "changednotes\n");
        Files.delete(project.resolve("old.txt"));

        // Offline runs also check that no semantic or ONNX Runtime class is initialized.
        Result index = cli.run(project, "index");

        assertSuccess(index);
        assertTrue(index.out().contains("deleted: 1\n"), index.out());
        assertFalse(index.out().contains("embedded:"), index.out());
        assertEquals(List.of("guide.md"), filesWithVectors(database));
        // Explicitly lexical: in auto mode an index with vectors would reach for the model.
        assertEquals(
                List.of("notes.txt:1-1"),
                hitLocations(cli.runReadOnly(project, "query", "changednotes", "--mode", "lexical")));
        assertEquals(
                List.of("guide.md:1-3"),
                hitLocations(cli.runReadOnly(project, "query", "modeneedle", "--mode", "lexical")));
    }

    /** Gives every stored chunk a four-dimensional vector, as a semantic run would with a four-dimensional model. */
    private static void storeVectorsForEveryChunk(Path database) throws SQLException {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database.toUri());
                Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    INSERT INTO embedding_profiles(model_id, model_revision, model_sha256, dimensions,
                        tokenizer_algorithm, vocabulary_sha256, max_input_tokens, pooling, normalization, input,
                        fingerprint)
                    VALUES ('test/model', 'rev', '%s', 4, 'wordpiece', '%s', 256, 'mean', 'l2', 'chunk-content', '%s')
                    """.formatted("b".repeat(64), "c".repeat(64), "d".repeat(64)));
            statement.executeUpdate(
                    "INSERT INTO chunk_embeddings(chunk_id, profile_id, vector) SELECT id, 1, zeroblob(16) FROM chunks");
        }
    }

    private static List<String> filesWithVectors(Path database) throws SQLException {
        List<String> files = new ArrayList<>();

        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database.toUri());
                Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery("""
                    SELECT DISTINCT f.source_path FROM chunk_embeddings e
                    JOIN chunks c ON c.id = e.chunk_id JOIN files f ON f.id = c.file_id
                    ORDER BY f.source_path
                    """)) {
            while (result.next()) {
                files.add(result.getString(1));
            }
        }

        return files;
    }

    /** Returns the cache directory the offline guard gives the CLI as {@code PECIA_CACHE_DIR}. */
    private Path cacheDirectory() {
        return sandbox.resolve("offline/cache/pecia");
    }

    private static String semanticLine(Result result) {
        return result.out().lines().toList().get(1);
    }
}
