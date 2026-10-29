package dev.eyuppastirmaci.pecia.packaging.consumer;

import static dev.eyuppastirmaci.pecia.packaging.consumer.ConsumerChecks.check;

import dev.eyuppastirmaci.pecia.config.PeciaConfigLoader;
import dev.eyuppastirmaci.pecia.config.PeciaConfigParser;
import dev.eyuppastirmaci.pecia.index.IndexOptions;
import dev.eyuppastirmaci.pecia.index.IndexResult;
import dev.eyuppastirmaci.pecia.index.IndexService;
import dev.eyuppastirmaci.pecia.search.QueryService;
import dev.eyuppastirmaci.pecia.search.SearchRequest;
import dev.eyuppastirmaci.pecia.semantic.Embedder;
import dev.eyuppastirmaci.pecia.semantic.ModelOutcome;
import dev.eyuppastirmaci.pecia.semantic.SemanticModel;
import dev.eyuppastirmaci.pecia.testing.LexicalTestCorpus;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Verifies that the packaged core and its runtime dependencies can embed texts with a model file. */
final class EmbeddingScenario {

    /** The tiny test model the packaging test copies into the project directory. */
    static final String MODEL_FILE = "tiny.onnx";

    private EmbeddingScenario() {}

    /** Embeds a padded batch twice, then checks that a closed embedder refuses work. */
    static void verifyEmbedding(Path root) throws Exception {
        Path model = root.resolve(MODEL_FILE);
        check(Files.isRegularFile(model), "The packaging test must supply " + model);
        List<String> texts = List.of("hello world", "Payment validation runs before the order is confirmed.");
        Embedder embedder = Embedder.open(new ModelOutcome(ModelOutcome.Status.CACHED, SemanticModel.MINILM, model));
        float[][] first;
        float[][] second;

        try (embedder) {
            first = embedder.embed(texts);
            second = embedder.embed(texts);
        }

        check(first.length == texts.size(), "The packaged embedder must return one embedding per text");

        for (int index = 0; index < first.length; index++) {
            check(first[index].length == Embedder.DIMENSIONS, "Packaged embeddings must have 384 dimensions");
            check(Math.abs(norm(first[index]) - 1) < 1e-5, "Packaged embeddings must have unit length");
            check(Arrays.equals(first[index], second[index]), "Repeated packaged embeddings must be identical");
        }

        check(!Arrays.equals(first[0], first[1]), "Different texts must have different embeddings");

        try {
            embedder.embed(texts);
            throw new AssertionError("A closed packaged embedder must refuse work");
        } catch (IllegalStateException expected) {
            // Closing releases the session for good.
        }
    }

    /** Indexes the shared corpus semantically with the tiny model, which the packaging test places beside the project. */
    static void indexSemantically(Path root) throws Exception {
        LexicalTestCorpus.write(root);
        ModelOutcome tiny =
                new ModelOutcome(ModelOutcome.Status.CACHED, SemanticModel.MINILM, root.resolveSibling(MODEL_FILE));

        IndexResult result = IndexService.withModelProvider(
                        new PeciaConfigLoader(new PeciaConfigParser()), listener -> tiny)
                .index(root, IndexOptions.semantic(true));

        check(
                result.status() == IndexResult.Status.COMPLETE
                        && result.embeddedChunks() == result.writtenChunks()
                        && result.embeddedChunks() > 0,
                "Packaged semantic indexing must embed every chunk");
    }

    /**
     * Changes one file and deletes another in a semantically indexed project, then indexes and queries lexically:
     * the changed and deleted files lose their vectors, every other vector stays, and search keeps working.
     */
    static void verifyLexicalAfterSemantic(Path root) throws Exception {
        Path database = root.resolve(".pecia/index.db");
        Map<String, String> before = vectors(database);
        Files.writeString(root.resolve(LexicalTestCorpus.NOTES), "Replaced notes about refunds.\n");
        Files.delete(root.resolve(LexicalTestCorpus.CONFIG));
        PeciaConfigLoader loader = new PeciaConfigLoader(new PeciaConfigParser());

        IndexResult result = new IndexService(loader).index(root);

        check(
                result.status() == IndexResult.Status.COMPLETE
                        && result.semanticModel() == null
                        && result.embeddedChunks() == 0
                        && result.indexedFiles() == 1
                        && result.deletedFiles() == 1,
                "Packaged lexical indexing must update an index that holds vectors without embedding");

        Map<String, String> expected = new LinkedHashMap<>(before);
        expected.keySet().removeIf(chunk -> chunk.startsWith("docs/notes.txt#") || chunk.startsWith("config.yaml#"));
        check(!expected.isEmpty() && vectors(database).equals(expected), "Only changed and deleted files lose vectors");

        QueryService queries = new QueryService(loader);
        check(
                !queries.query(root, new SearchRequest("refunds")).hits().isEmpty()
                        && !queries.query(root, new SearchRequest("payment"))
                                .hits()
                                .isEmpty(),
                "Packaged queries must search an index that holds vectors");
    }

    /** Maps each stored vector's chunk, as {@code path#index}, to the vector's bytes in hexadecimal. */
    private static Map<String, String> vectors(Path database) throws SQLException {
        Map<String, String> vectors = new LinkedHashMap<>();

        try (Connection connection = ConsumerChecks.openDatabase(database);
                var statement = connection.createStatement();
                var result = statement.executeQuery("""
                    SELECT f.source_path || '#' || c.chunk_index, hex(e.vector) FROM chunk_embeddings e
                    JOIN chunks c ON c.id = e.chunk_id JOIN files f ON f.id = c.file_id
                    ORDER BY f.source_path, c.chunk_index
                    """)) {
            while (result.next()) {
                vectors.put(result.getString(1), result.getString(2));
            }
        }

        return vectors;
    }

    private static double norm(float[] vector) {
        double squares = 0;

        for (float value : vector) {
            squares += (double) value * value;
        }

        return Math.sqrt(squares);
    }
}
