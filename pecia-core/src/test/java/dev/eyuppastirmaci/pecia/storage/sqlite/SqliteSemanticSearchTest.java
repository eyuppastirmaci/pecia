package dev.eyuppastirmaci.pecia.storage.sqlite;

import static dev.eyuppastirmaci.pecia.storage.sqlite.SqliteFtsTestSupport.execute;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.eyuppastirmaci.pecia.chunking.ChunkingIdentity;
import dev.eyuppastirmaci.pecia.content.Chunk;
import dev.eyuppastirmaci.pecia.content.ChunkMetadata;
import dev.eyuppastirmaci.pecia.content.ContentHash;
import dev.eyuppastirmaci.pecia.content.Document;
import dev.eyuppastirmaci.pecia.content.DocumentType;
import dev.eyuppastirmaci.pecia.content.LineRange;
import dev.eyuppastirmaci.pecia.search.SearchHit;
import dev.eyuppastirmaci.pecia.search.SearchRequest;
import dev.eyuppastirmaci.pecia.search.SearchScore;
import dev.eyuppastirmaci.pecia.search.SemanticSearch;
import dev.eyuppastirmaci.pecia.semantic.EmbeddingProfile;
import dev.eyuppastirmaci.pecia.semantic.VectorExtensionAccess;
import dev.eyuppastirmaci.pecia.tokenization.TokenizerCompatibility;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Ranks stored chunks by the cosine similarity of their embeddings to a query embedding. */
class SqliteSemanticSearchTest {

    private static final TokenizerCompatibility TOKENIZER =
            new TokenizerCompatibility("wordpiece", "a".repeat(64), 100, 512, 2);
    private static final ChunkingIdentity IDENTITY = new ChunkingIdentity("extract-v1", "chunk-v1", TOKENIZER, 256, 32);
    private static final EmbeddingProfile PROFILE = profile("rev");
    private static final EmbeddingProfile OTHER = profile("other-rev");

    @TempDir
    Path sandbox;

    private Path root;
    private Path library;

    @BeforeEach
    void prepare() throws Exception {
        root = Files.createDirectories(sandbox.resolve("project")).toRealPath();
        library = VectorExtensionAccess.library(sandbox.resolve("cache"));
    }

    @Test
    void hitsAreRankedByCosineSimilarityAsAJavaComputationRanksThem() throws Exception {
        Random random = new Random(11);
        Map<String, List<float[]>> files = Map.of(
                "a.md", List.of(unit(random), unit(random), unit(random)),
                "b.md", List.of(unit(random), unit(random)),
                "c.md", List.of(unit(random), unit(random), unit(random), unit(random)));
        float[] query = unit(random);
        List<Expected> expected = new ArrayList<>();

        try (SqliteStorage storage = SqliteStorage.open(root.resolve("index.db"), root)) {
            for (var file : files.entrySet()) {
                write(storage, file.getKey(), file.getValue());

                for (int index = 0; index < file.getValue().size(); index++) {
                    expected.add(new Expected(
                            file.getKey(), index, dot(query, file.getValue().get(index))));
                }
            }
        }

        expected.sort(Comparator.comparingDouble(Expected::similarity).reversed());

        try (SqliteStorage storage = SqliteStorage.openReadOnly(root.resolve("index.db"), root)) {
            storage.loadVectorExtension(library);
            List<SearchHit> hits = storage.semanticSearch(PROFILE).search(query, SearchRequest.MAX_LIMIT);

            assertEquals(expected.size(), hits.size());

            for (int rank = 0; rank < hits.size(); rank++) {
                SearchHit hit = hits.get(rank);
                Expected want = expected.get(rank);

                assertEquals(
                        want.path() + "#" + want.index(), hit.sourcePath() + "#" + hit.chunkIndex(), "rank " + rank);
                assertEquals(want.similarity(), hit.score().value(), 1e-6);
                assertEquals(SearchScore.Kind.COSINE_SIMILARITY, hit.score().kind());
                assertTrue(hit.stableId().isPresent());
                assertEquals(List.of("Heading"), hit.metadata().headingPath());
            }

            assertEquals(3, storage.semanticSearch(PROFILE).search(query, 3).size());
        }
    }

    @Test
    void equalSimilaritiesBreakByPathThenPositionAndScoresStayWithinOneAndMinusOne() throws Exception {
        float[] east = {1, 0, 0, 0};
        float[] west = {-1, 0, 0, 0};

        try (SqliteStorage storage = SqliteStorage.open(root.resolve("index.db"), root)) {
            write(storage, "b.md", List.of(east, west));
            write(storage, "a.md", List.of(east, east));
            storage.loadVectorExtension(library);

            List<SearchHit> hits = storage.semanticSearch(PROFILE).search(east, 10);

            assertEquals(List.of("a.md#0", "a.md#1", "b.md#0", "b.md#1"), keys(hits));
            assertEquals(1.0, hits.getFirst().score().value());
            assertEquals(-1.0, hits.getLast().score().value());
        }
    }

    @Test
    void aChunksOwnEmbeddingScoresOneDespiteRounding() throws Exception {
        Random random = new Random(5);
        List<float[]> vectors = new ArrayList<>();

        // Unnormalized vectors make the cosine distance of a vector to itself round a hair away from zero.
        for (int index = 0; index < 64; index++) {
            float[] vector = unit(random);

            for (int dimension = 0; dimension < vector.length; dimension++) {
                vector[dimension] *= 1 + random.nextFloat() * 1000;
            }

            vectors.add(vector);
        }

        try (SqliteStorage storage = SqliteStorage.open(root.resolve("index.db"), root)) {
            for (int index = 0; index < vectors.size(); index++) {
                write(storage, "file-" + index + ".md", List.of(vectors.get(index)));
            }

            storage.loadVectorExtension(library);
            SemanticSearch search = storage.semanticSearch(PROFILE);

            for (float[] vector : vectors) {
                double score = search.search(vector, 1).getFirst().score().value();

                assertTrue(score <= 1 && score > 0.9999, "score " + score);
            }
        }
    }

    @Test
    void onlyTheProfilesEmbeddingsAreSearchedAndChunksWithoutOneNever() throws Exception {
        try (SqliteStorage storage = SqliteStorage.open(root.resolve("index.db"), root)) {
            write(storage, "embedded.md", List.of(new float[] {1, 0, 0, 0}));
            Document lexical = document("lexical.md", "lexical content");
            storage.replaceFile(lexical, chunks(lexical, 1), IDENTITY);
            // An index written before a model change can still hold another profile's embeddings.
            execute(storage.connection(), """
                    INSERT INTO embedding_profiles(model_id, model_revision, model_sha256, dimensions,
                        tokenizer_algorithm, vocabulary_sha256, max_input_tokens, pooling, normalization, input,
                        fingerprint)
                    VALUES ('test/model', 'other-rev', ?, 4, 'wordpiece', ?, 256, 'mean', 'l2', 'chunk-content', ?)
                    """, "b".repeat(64), "c".repeat(64), OTHER.fingerprint());
            execute(storage.connection(), """
                    INSERT INTO chunk_embeddings(chunk_id, profile_id, vector)
                    SELECT c.id, (SELECT id FROM embedding_profiles WHERE fingerprint = ?), ?
                    FROM chunks c JOIN files f ON f.id = c.file_id WHERE f.source_path = 'lexical.md'
                    """, OTHER.fingerprint(), SqliteEmbeddingRepository.encode(new float[] {
                1, 0, 0, 0
            }));
            storage.loadVectorExtension(library);

            assertEquals(
                    List.of("embedded.md#0"),
                    keys(storage.semanticSearch(PROFILE).search(new float[] {1, 0, 0, 0}, 10)));
            assertEquals(
                    List.of("lexical.md#0"), keys(storage.semanticSearch(OTHER).search(new float[] {1, 0, 0, 0}, 10)));
        }
    }

    @Test
    void snippetsAreTheBeginningOfTheChunkContent() throws Exception {
        String longContent = "ö".repeat(450);

        try (SqliteStorage storage = SqliteStorage.open(root.resolve("index.db"), root)) {
            Document document = document("long.md", longContent);
            storage.replaceFile(document, chunks(document, 1), IDENTITY, PROFILE, List.of(new float[] {0, 1, 0, 0}));
            storage.loadVectorExtension(library);

            String snippet = storage.semanticSearch(PROFILE)
                    .search(new float[] {0, 1, 0, 0}, 1)
                    .getFirst()
                    .snippet();

            assertEquals(SearchHit.MAX_SNIPPET_CODE_POINTS, snippet.codePointCount(0, snippet.length()));
            assertEquals("ö".repeat(SearchHit.MAX_SNIPPET_CODE_POINTS - 1) + "…", snippet);
        }
    }

    @Test
    void invalidQueriesAndAnUnloadedExtensionAreRefused() throws Exception {
        try (SqliteStorage storage = SqliteStorage.open(root.resolve("index.db"), root)) {
            assertThrows(IllegalStateException.class, () -> storage.semanticSearch(PROFILE));

            storage.loadVectorExtension(library);
            SemanticSearch search = storage.semanticSearch(PROFILE);

            assertEquals(List.of(), search.search(new float[] {1, 0, 0, 0}, 10), "An index without embeddings");
            assertThrows(IllegalArgumentException.class, () -> search.search(new float[] {1, 0, 0}, 10));
            assertThrows(IllegalArgumentException.class, () -> search.search(new float[] {Float.NaN, 0, 0, 0}, 10));
            assertThrows(IllegalArgumentException.class, () -> search.search(new float[] {1, 0, 0, 0}, 0));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> search.search(new float[] {1, 0, 0, 0}, SearchRequest.MAX_LIMIT + 1));
            assertThrows(NullPointerException.class, () -> search.search(null, 10));
            assertThrows(NullPointerException.class, () -> storage.semanticSearch(null));
        }
    }

    private static void write(SqliteStorage storage, String path, List<float[]> vectors) throws Exception {
        Document document = document(path, path + " content");
        storage.replaceFile(document, chunks(document, vectors.size()), IDENTITY, PROFILE, vectors);
    }

    private static List<String> keys(List<SearchHit> hits) {
        return hits.stream()
                .map(hit -> hit.sourcePath() + "#" + hit.chunkIndex())
                .toList();
    }

    private static float[] unit(Random random) {
        float[] vector = new float[4];
        double norm = 0;

        for (int index = 0; index < vector.length; index++) {
            vector[index] = (float) random.nextGaussian();
            norm += vector[index] * vector[index];
        }

        for (int index = 0; index < vector.length; index++) {
            vector[index] /= (float) Math.sqrt(norm);
        }

        return vector;
    }

    private static double dot(float[] first, float[] second) {
        double sum = 0;

        for (int index = 0; index < first.length; index++) {
            sum += (double) first[index] * second[index];
        }

        return sum;
    }

    private static EmbeddingProfile profile(String revision) {
        return new EmbeddingProfile(
                "test/model",
                revision,
                "b".repeat(64),
                4,
                "wordpiece",
                "c".repeat(64),
                256,
                "mean",
                "l2",
                "chunk-content");
    }

    private static Document document(String path, String content) {
        return new Document(
                Path.of(path),
                DocumentType.MARKDOWN,
                content,
                ContentHash.sha256(content.getBytes(StandardCharsets.UTF_8)));
    }

    private static List<Chunk> chunks(Document document, int count) {
        List<Chunk> chunks = new ArrayList<>();

        for (int index = 0; index < count; index++) {
            chunks.add(new Chunk(
                    document.sourcePath(),
                    document.type(),
                    index,
                    document.content(),
                    new LineRange(index + 1, index + 1),
                    new ChunkMetadata(List.of("Heading"), Map.of())));
        }

        return chunks;
    }

    private record Expected(String path, int index, double similarity) {}
}
