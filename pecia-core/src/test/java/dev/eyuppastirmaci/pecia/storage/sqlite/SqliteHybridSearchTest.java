package dev.eyuppastirmaci.pecia.storage.sqlite;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.eyuppastirmaci.pecia.chunking.ChunkingIdentity;
import dev.eyuppastirmaci.pecia.content.Chunk;
import dev.eyuppastirmaci.pecia.content.ChunkMetadata;
import dev.eyuppastirmaci.pecia.content.ContentHash;
import dev.eyuppastirmaci.pecia.content.Document;
import dev.eyuppastirmaci.pecia.content.DocumentType;
import dev.eyuppastirmaci.pecia.content.LineRange;
import dev.eyuppastirmaci.pecia.search.FusedRanks;
import dev.eyuppastirmaci.pecia.search.HybridSearch;
import dev.eyuppastirmaci.pecia.search.MatchRange;
import dev.eyuppastirmaci.pecia.search.SearchException;
import dev.eyuppastirmaci.pecia.search.SearchHit;
import dev.eyuppastirmaci.pecia.search.SearchRequest;
import dev.eyuppastirmaci.pecia.search.SearchScore;
import dev.eyuppastirmaci.pecia.semantic.EmbeddingProfile;
import dev.eyuppastirmaci.pecia.semantic.VectorExtensionAccess;
import dev.eyuppastirmaci.pecia.tokenization.TokenizerCompatibility;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/** Reads lexical and semantic candidates for one query in one snapshot and fuses their ranks. */
class SqliteHybridSearchTest {

    private static final TokenizerCompatibility TOKENIZER =
            new TokenizerCompatibility("wordpiece", "a".repeat(64), 100, 512, 2);
    private static final ChunkingIdentity IDENTITY = new ChunkingIdentity("extract-v1", "chunk-v1", TOKENIZER, 256, 32);
    private static final EmbeddingProfile PROFILE = new EmbeddingProfile(
            "test/model", "rev", "b".repeat(64), 4, "wordpiece", "c".repeat(64), 256, "mean", "l2", "chunk-content");
    private static final float[] EAST = {1, 0, 0, 0};

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
    void eachHitCarriesItsRanksInTheSeparateLexicalAndSemanticSearches() throws Exception {
        Random random = new Random(26);

        try (SqliteStorage storage = SqliteStorage.open(root.resolve("index.db"), root)) {
            for (int index = 0; index < 120; index++) {
                String words = index % 3 == 0 ? "needle haystack" : index % 3 == 1 ? "needle" : "haystack only";
                write(storage, "file-" + index + ".md", words + " " + index, unit(random));
            }

            storage.loadVectorExtension(library);
            SearchRequest request = new SearchRequest("needle", SearchRequest.MAX_LIMIT);
            float[] query = unit(random);
            List<Long> lexical = ids(storage.lexicalSearch().search(request));
            List<Long> semantic = ids(storage.semanticSearch(PROFILE).search(query, SearchRequest.MAX_LIMIT));

            List<SearchHit> hits = storage.hybridSearch(PROFILE).search(request, query);

            assertEquals(SearchRequest.MAX_LIMIT, hits.size());
            assertEquals(80, lexical.size(), "Two thirds of the chunks contain the term");

            for (SearchHit hit : hits) {
                FusedRanks ranks = hit.fusedRanks().orElseThrow();

                assertEquals(
                        SearchScore.Kind.RECIPROCAL_RANK_FUSION, hit.score().kind());
                assertEquals(rank(lexical, hit.chunkId()), ranks.lexical());
                assertEquals(rank(semantic, hit.chunkId()), ranks.semantic());
                // Chunks the lexical side found keep its match ranges; the others have none.
                assertEquals(
                        ranks.lexical().isPresent() ? List.of(new MatchRange(0, 6)) : List.of(),
                        hit.matches(),
                        hit.snippet());
            }

            assertEquals(
                    hits.subList(0, 5), storage.hybridSearch(PROFILE).search(new SearchRequest("needle", 5), query));
        }
    }

    @Test
    void textWithoutSearchableCharactersLeavesTheSemanticOrder() throws Exception {
        try (SqliteStorage storage = SqliteStorage.open(root.resolve("index.db"), root)) {
            write(storage, "a.md", "alpha", new float[] {0, 1, 0, 0});
            write(storage, "b.md", "beta", EAST);
            write(storage, "c.md", "gamma", new float[] {-1, 0, 0, 0});
            storage.loadVectorExtension(library);

            List<SearchHit> hits = storage.hybridSearch(PROFILE).search(new SearchRequest("?! ..."), EAST);

            assertEquals(List.of("b.md", "a.md", "c.md"), paths(hits));

            for (SearchHit hit : hits) {
                assertTrue(hit.fusedRanks().orElseThrow().lexical().isEmpty());
            }
        }
    }

    @Test
    void textWithMorePartsThanLexicalSearchAcceptsLeavesTheSemanticOrder() throws Exception {
        try (SqliteStorage storage = SqliteStorage.open(root.resolve("index.db"), root)) {
            write(storage, "a.md", "alpha", new float[] {0, 1, 0, 0});
            write(storage, "b.md", "alpha", EAST);
            storage.loadVectorExtension(library);

            List<SearchHit> hits = storage.hybridSearch(PROFILE).search(new SearchRequest("alpha ".repeat(65)), EAST);

            assertEquals(List.of("b.md", "a.md"), paths(hits));
            assertTrue(hits.stream()
                    .allMatch(hit -> hit.fusedRanks().orElseThrow().lexical().isEmpty()));
            // Within the limit both sides rank both chunks.
            assertTrue(storage.hybridSearch(PROFILE).search(new SearchRequest("alpha ".repeat(64)), EAST).stream()
                    .allMatch(hit -> hit.fusedRanks().orElseThrow().lexical().isPresent()));
        }
    }

    @Test
    void chunksWithoutAnEmbeddingComeOnlyFromTheLexicalSide() throws Exception {
        try (SqliteStorage storage = SqliteStorage.open(root.resolve("index.db"), root)) {
            write(storage, "embedded.md", "needle embedded", EAST);
            Document lexicalOnly = document("lexical.md", "needle lexical");
            storage.replaceFile(lexicalOnly, chunks(lexicalOnly), IDENTITY);
            storage.loadVectorExtension(library);

            List<SearchHit> hits = storage.hybridSearch(PROFILE).search(new SearchRequest("needle lexical"), EAST);

            // Lexical terms are combined with AND, so only the chunk without an embedding matches lexically; each chunk
            // is ranked first by one side, and the tie breaks by path.
            assertEquals(List.of("embedded.md", "lexical.md"), paths(hits));
            assertEquals(
                    new FusedRanks(OptionalInt.empty(), OptionalInt.of(1)),
                    hits.getFirst().fusedRanks().orElseThrow());
            assertEquals(
                    new FusedRanks(OptionalInt.of(1), OptionalInt.empty()),
                    hits.getLast().fusedRanks().orElseThrow());
            assertEquals("needle lexical", hits.getLast().snippet(), "The lexical snippet");
        }
    }

    @Test
    @Timeout(20)
    void bothSidesReadOneSnapshotWhileAnotherConnectionWrites() throws Exception {
        Path database = root.resolve("index.db");

        try (SqliteStorage reader = SqliteStorage.open(database, root)) {
            try (var statement = reader.connection().createStatement();
                    var rows = statement.executeQuery("PRAGMA journal_mode = WAL")) {
                assertTrue(rows.next());
                assertEquals("wal", rows.getString(1));
            }

            write(reader, "old.md", "needle old", new float[] {0, 1, 0, 0});
            reader.loadVectorExtension(library);

            try (SqliteStorage writer = SqliteStorage.open(database, root)) {
                CountDownLatch lexicalRead = new CountDownLatch(1);
                CountDownLatch writerCommitted = new CountDownLatch(1);
                var executor = Executors.newSingleThreadExecutor();
                // A new chunk that the semantic side would rank first if it saw the write.
                var write = executor.submit(() -> {
                    try {
                        await(lexicalRead);
                        write(writer, "new.md", "unrelated words", EAST);
                    } finally {
                        writerCommitted.countDown();
                    }

                    return null;
                });

                try {
                    var metadata = new SqliteChunkMetadataReader(reader.connection());
                    var coordinated = new SqliteLexicalRetriever(reader, ids -> {
                        lexicalRead.countDown();
                        await(writerCommitted);
                        return metadata.readForChunks(ids);
                    });
                    HybridSearch search =
                            new SqliteHybridSearch(reader, coordinated, new SqliteVectorRetriever(reader), PROFILE);

                    List<SearchHit> during = search.search(new SearchRequest("needle"), EAST);
                    write.get(5, TimeUnit.SECONDS);

                    assertEquals(List.of("old.md"), paths(during));
                    // Afterwards the new chunk is the semantic side's first candidate, below the chunk both sides rank.
                    assertEquals(
                            List.of("old.md", "new.md"),
                            paths(reader.hybridSearch(PROFILE).search(new SearchRequest("needle"), EAST)));
                    assertTrue(reader.connection().getAutoCommit());
                } finally {
                    lexicalRead.countDown();
                    writerCommitted.countDown();
                    write.cancel(true);
                    executor.shutdownNow();
                    assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
                }
            }
        }
    }

    @Test
    void invalidQueriesAnUnloadedExtensionAndClosedStorageAreRefused() throws Exception {
        HybridSearch search;

        try (SqliteStorage storage = SqliteStorage.open(root.resolve("index.db"), root)) {
            assertThrows(IllegalStateException.class, () -> storage.hybridSearch(PROFILE));
            assertThrows(NullPointerException.class, () -> storage.hybridSearch(null));

            storage.loadVectorExtension(library);
            search = storage.hybridSearch(PROFILE);
            SearchRequest request = new SearchRequest("needle");

            assertEquals(List.of(), search.search(request, EAST), "An index without chunks");
            assertThrows(IllegalArgumentException.class, () -> search.search(request, new float[] {1, 0, 0}));
            assertThrows(
                    IllegalArgumentException.class, () -> search.search(request, new float[] {Float.NaN, 0, 0, 0}));
            assertThrows(NullPointerException.class, () -> search.search(null, EAST));
            assertThrows(NullPointerException.class, () -> search.search(request, null));
        }

        SearchException closed = assertThrows(SearchException.class, () -> search.search(new SearchRequest("x"), EAST));
        assertTrue(closed.getCause() instanceof SQLException);
        assertFalse(closed.getMessage().isBlank());
    }

    private static void write(SqliteStorage storage, String path, String content, float[] vector) throws SQLException {
        Document document = document(path, content);
        storage.replaceFile(document, chunks(document), IDENTITY, PROFILE, List.of(vector));
    }

    private static Document document(String path, String content) {
        return new Document(
                Path.of(path),
                DocumentType.MARKDOWN,
                content,
                ContentHash.sha256(content.getBytes(StandardCharsets.UTF_8)));
    }

    private static List<Chunk> chunks(Document document) {
        return List.of(new Chunk(
                document.sourcePath(),
                document.type(),
                0,
                document.content(),
                new LineRange(1, 1),
                new ChunkMetadata(List.of(), Map.of())));
    }

    private static OptionalInt rank(List<Long> ids, long id) {
        int index = ids.indexOf(id);

        return index < 0 ? OptionalInt.empty() : OptionalInt.of(index + 1);
    }

    private static List<Long> ids(List<SearchHit> hits) {
        return hits.stream().map(SearchHit::chunkId).toList();
    }

    private static List<String> paths(List<SearchHit> hits) {
        return hits.stream().map(hit -> hit.sourcePath().toString()).toList();
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

    private static void await(CountDownLatch latch) throws SQLException {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new SQLException("Timed out waiting for snapshot test coordination");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new SQLException("Snapshot test interrupted", interrupted);
        }
    }
}
