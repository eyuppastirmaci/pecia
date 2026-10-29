package dev.eyuppastirmaci.pecia.packaging.consumer;

import static dev.eyuppastirmaci.pecia.packaging.consumer.ConsumerChecks.check;

import dev.eyuppastirmaci.pecia.config.PeciaConfigLoader;
import dev.eyuppastirmaci.pecia.config.PeciaConfigParser;
import dev.eyuppastirmaci.pecia.config.SearchMode;
import dev.eyuppastirmaci.pecia.index.IndexOptions;
import dev.eyuppastirmaci.pecia.index.IndexResult;
import dev.eyuppastirmaci.pecia.index.IndexService;
import dev.eyuppastirmaci.pecia.search.QueryException;
import dev.eyuppastirmaci.pecia.search.QueryOptions;
import dev.eyuppastirmaci.pecia.search.QueryResult;
import dev.eyuppastirmaci.pecia.search.QueryService;
import dev.eyuppastirmaci.pecia.search.SearchHit;
import dev.eyuppastirmaci.pecia.search.SearchRequest;
import dev.eyuppastirmaci.pecia.search.SearchScore;
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
import java.util.Optional;

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

    /**
     * Queries the semantically indexed corpus with its shortest chunk's text and the tiny model beside the project,
     * semantically and then in hybrid mode: that chunk comes first, with similarity 1 and then ranked first by both
     * sides, and every chunk could be compared.
     */
    static void querySemantically(Path root) throws Exception {
        ModelOutcome tiny =
                new ModelOutcome(ModelOutcome.Status.CACHED, SemanticModel.MINILM, root.resolveSibling(MODEL_FILE));
        Map<String, String> chunks = chunks(root.resolve(".pecia/index.db"));
        String key = chunks.keySet().stream()
                .min((first, second) -> Integer.compare(
                        chunks.get(first).length(), chunks.get(second).length()))
                .orElseThrow();

        QueryResult result = QueryService.withModelLookup(
                        new PeciaConfigLoader(new PeciaConfigParser()), () -> Optional.of(tiny))
                .query(root, new SearchRequest(chunks.get(key)), QueryOptions.mode(SearchMode.SEMANTIC));
        SearchHit first = result.hits().getFirst();

        check(
                result.embeddingCoverage() != null
                        && result.embeddingCoverage().embedded() == chunks.size()
                        && result.embeddingCoverage().chunks() == chunks.size(),
                "A packaged semantic query must compare every embedded chunk");
        check(
                (first.sourcePath().toString().replace('\\', '/') + "#" + first.chunkIndex()).equals(key)
                        && first.score().kind() == SearchScore.Kind.COSINE_SIMILARITY
                        && Math.abs(first.score().value() - 1) < 1e-5,
                "A packaged semantic query with a chunk's text must find that chunk first");

        QueryResult hybrid = QueryService.withModelLookup(
                        new PeciaConfigLoader(new PeciaConfigParser()), () -> Optional.of(tiny))
                .query(root, new SearchRequest(chunks.get(key)), QueryOptions.mode(SearchMode.HYBRID));
        SearchHit fused = hybrid.hits().getFirst();

        check(
                hybrid.embeddingCoverage() != null
                        && hybrid.embeddingCoverage().embedded() == chunks.size()
                        && (fused.sourcePath().toString().replace('\\', '/') + "#" + fused.chunkIndex()).equals(key)
                        && fused.score().kind() == SearchScore.Kind.RECIPROCAL_RANK_FUSION
                        && fused.fusedRanks().orElseThrow().semantic().orElse(0) == 1
                        && fused.fusedRanks().orElseThrow().lexical().isPresent(),
                "A packaged hybrid query with a chunk's text must find that chunk first on both sides");
    }

    /** Queries semantically with the user's empty model cache, which must fail without downloading anything. */
    static void verifyUncachedSemanticQuery(Path root) throws Exception {
        try {
            new QueryService(new PeciaConfigLoader(new PeciaConfigParser()))
                    .query(root, new SearchRequest("refunds"), QueryOptions.mode(SearchMode.SEMANTIC));
            throw new AssertionError("A packaged semantic query must fail without a cached model");
        } catch (QueryException failure) {
            check(
                    failure.reason() == QueryException.Reason.MODEL_NOT_CACHED,
                    "A packaged semantic query must report the uncached model");
        }
    }

    /** Maps each stored chunk, as {@code path#index}, to its content. */
    private static Map<String, String> chunks(Path database) throws SQLException {
        Map<String, String> chunks = new LinkedHashMap<>();

        try (Connection connection = ConsumerChecks.openDatabase(database);
                var statement = connection.createStatement();
                var result = statement.executeQuery("""
                    SELECT f.source_path || '#' || c.chunk_index, c.content FROM chunks c
                    JOIN files f ON f.id = c.file_id ORDER BY f.source_path, c.chunk_index
                    """)) {
            while (result.next()) {
                chunks.put(result.getString(1), result.getString(2));
            }
        }

        return chunks;
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
