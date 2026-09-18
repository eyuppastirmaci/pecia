package dev.eyuppastirmaci.pecia.storage.sqlite;

import static dev.eyuppastirmaci.pecia.storage.sqlite.SqliteFtsTestSupport.execute;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.eyuppastirmaci.pecia.content.Chunk;
import dev.eyuppastirmaci.pecia.content.ChunkMetadata;
import dev.eyuppastirmaci.pecia.content.ContentHash;
import dev.eyuppastirmaci.pecia.content.DocumentType;
import dev.eyuppastirmaci.pecia.content.LineRange;
import dev.eyuppastirmaci.pecia.storage.model.StoredChunk;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SqliteLexicalRetrieverTest {
    @TempDir
    Path root;

    private SqliteStorage storage;
    private SqliteLexicalRetriever retriever;

    @BeforeEach
    void openStorage() throws Exception {
        storage = SqliteStorage.open(root.resolve("index.db"), root);
        retriever = new SqliteLexicalRetriever(storage);
    }

    @AfterEach
    void closeStorage() throws SQLException {
        if (storage != null) {
            storage.close();
        }
    }

    @Test
    void readsAllThreeFieldsWithEqualWeightsAndPreservesDatabaseIdentity() throws SQLException {
        var body = insert("a/guide.md", 0, "needle", "other");
        var heading = insert("b/guide.md", 0, "other", "needle");
        var path = insert("needle/guide.md", 0, "other", "other");

        var results = retriever.retrieve("\"needle\"", 10);

        assertEquals(List.of(body.id(), heading.id(), path.id()), ids(results));
        assertEquals(
                List.of(body.fileId(), heading.fileId(), path.fileId()),
                results.stream().map(SqliteLexicalRetriever.Candidate::fileId).toList());
        assertEquals(
                List.of("a/guide.md", "b/guide.md", "needle/guide.md"),
                results.stream()
                        .map(SqliteLexicalRetriever.Candidate::sourcePath)
                        .toList());
        // All rows have equal total token counts and one occurrence in different columns.
        assertEquals(results.get(0).bm25Score(), results.get(1).bm25Score());
        assertEquals(results.get(0).bm25Score(), results.get(2).bm25Score());
    }

    @Test
    void ranksRepeatedTermsBeforeWeakerMatchesAndAppliesLimitAfterRanking() throws SQLException {
        var weak = insert("a.md", 0, "needle filler filler filler");
        var strong = insert("z.md", 0, "needle needle needle filler");
        insert("other.md", 0, "unrelated material");

        var results = retriever.retrieve("\"needle\"", 10);

        assertEquals(List.of(strong.id(), weak.id()), ids(results));
        assertTrue(results.get(0).bm25Score() < results.get(1).bm25Score());
        assertTrue(results.stream().allMatch(hit -> Double.isFinite(hit.bm25Score()) && hit.bm25Score() < 0));
        assertEquals(List.of(strong.id()), ids(retriever.retrieve("\"needle\"", 1)));
    }

    @Test
    void normalizesForDocumentLengthAtEqualTermFrequency() throws SQLException {
        var longChunk = insert("a.md", 0, "needle " + "filler ".repeat(40));
        var shortChunk = insert("z.md", 0, "needle");

        var results = retriever.retrieve("\"needle\"", 10);

        assertEquals(List.of(shortChunk.id(), longChunk.id()), ids(results));
        assertTrue(results.get(0).bm25Score() < results.get(1).bm25Score());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void breaksEqualScoresByBinaryPathThenChunkPositionRegardlessOfInsertionOrder(boolean reverse) throws SQLException {
        List<String> paths = new ArrayList<>(List.of("A.md", "a.md", "z.md", "İ.md", "ı.md"));

        if (reverse) {
            Collections.reverse(paths);
        }

        for (String path : paths) {
            for (int index : new int[] {2, 0, 1}) {
                insert(path, index, "needle");
            }
        }

        var results = retriever.retrieve("\"needle\"", 100);

        assertEquals(
                List.of(
                        "A.md:0", "A.md:1", "A.md:2", "a.md:0", "a.md:1", "a.md:2", "z.md:0", "z.md:1", "z.md:2",
                        "İ.md:0", "İ.md:1", "İ.md:2", "ı.md:0", "ı.md:1", "ı.md:2"),
                results.stream()
                        .map(hit -> hit.sourcePath() + ":" + hit.chunkIndex())
                        .toList());
        assertTrue(results.stream()
                .allMatch(hit -> hit.bm25Score() == results.getFirst().bm25Score()));
        assertEquals(results.subList(0, 4), retriever.retrieve("\"needle\"", 4));
        assertEquals(results, retriever.retrieve("\"needle\"", 100));
    }

    @Test
    void returnsEachChunkOnceEvenWhenSeveralHeadingsAndFieldsMatch() throws SQLException {
        var first = insert("needle.md", 0, "needle", "needle", "needle");
        var second = insert("needle.md", 1, "needle", "needle", "needle");

        var results = retriever.retrieve("\"needle\"", 2);

        assertEquals(List.of(first.id(), second.id()), ids(results));
        assertThrows(UnsupportedOperationException.class, results::clear);
    }

    @Test
    void honorsMaximumLimitWithoutReturningAllMatchingChunks() throws SQLException {
        for (int index = 104; index >= 0; index--) {
            insert(String.format(Locale.ROOT, "docs/f%03d.md", index), 0, "needle");
        }

        var results = retriever.retrieve("\"needle\"", 100);

        assertEquals(100, results.size());
        assertEquals("docs/f000.md", results.getFirst().sourcePath());
        assertEquals("docs/f099.md", results.getLast().sourcePath());
    }

    @Test
    void returnsImmutableEmptyListsForEmptyIndexAndNoMatch() throws SQLException {
        var empty = retriever.retrieve("\"needle\"", 10);
        assertTrue(empty.isEmpty());
        assertThrows(UnsupportedOperationException.class, () -> empty.add(null));

        insert("notes.md", 0, "different");
        assertTrue(retriever.retrieve("\"needle\"", 10).isEmpty());
    }

    @Test
    void bindsCompiledExpressionAsDataIncludingSqlLookingText() throws SQLException {
        var literal = insert("notes.md", 0, "O'Reilly DROP TABLE chunks");
        insert("other.md", 0, "other text");

        assertEquals(List.of(literal.id()), ids(retriever.retrieve("\"O'Reilly\"", 10)));
        assertEquals(List.of(literal.id()), ids(retriever.retrieve("\"'; DROP TABLE chunks; --\"", 10)));
        assertEquals(1, storage.chunks().findByFileId(literal.fileId()).size());
        assertTrue(retriever.retrieve("\"' OR 1=1 --\"", 10).isEmpty());
    }

    @Test
    void executesPrecompiledAndAndIdentifierPhrases() throws SQLException {
        var both = insert("one.md", 0, "middleware uses JWT_SECRET for authentication");
        insert("two.md", 0, "authentication only JWT unrelated SECRET");

        assertEquals(List.of(both.id()), ids(retriever.retrieve("\"authentication\" AND \"middleware\"", 10)));
        assertEquals(List.of(both.id()), ids(retriever.retrieve("\"JWT_SECRET\"", 10)));
    }

    @ParameterizedTest
    @ValueSource(ints = {Integer.MIN_VALUE, -1, 0, 101, Integer.MAX_VALUE})
    void rejectsInvalidLimits(int limit) {
        assertThrows(IllegalArgumentException.class, () -> retriever.retrieve("\"needle\"", limit));
    }

    @Test
    void rejectsMissingExpressionAndStorage() {
        assertThrows(NullPointerException.class, () -> new SqliteLexicalRetriever(null));
        assertThrows(NullPointerException.class, () -> retriever.retrieve(null, 10));
        assertThrows(IllegalArgumentException.class, () -> retriever.retrieve("", 10));
        assertThrows(IllegalArgumentException.class, () -> retriever.retrieve(" \t\n", 10));
    }

    @Test
    void propagatesSqlFailureAndAllowsQueriesAndWritesAfterward() throws SQLException {
        var first = insert("one.md", 0, "needle");

        assertThrows(SQLException.class, () -> retriever.retrieve("\"unterminated", 10));
        assertFalse(storage.connection().isClosed());
        assertEquals(List.of(first.id()), ids(retriever.retrieve("\"needle\"", 10)));

        var second = insert("two.md", 0, "needle");
        assertEquals(List.of(first.id(), second.id()), ids(retriever.retrieve("\"needle\"", 10)));
    }

    @Test
    void distinguishesClosedOrBrokenStorageFromNoMatches() throws SQLException {
        insert("one.md", 0, "needle");
        execute(storage.connection(), "DROP TABLE chunks_fts");
        assertThrows(SQLException.class, () -> retriever.retrieve("\"needle\"", 10));
        storage.close();
        assertThrows(SQLException.class, () -> retriever.retrieve("\"needle\"", 10));
    }

    @Test
    void worksWithoutWritesAndLeavesTheCallerConnectionOpen() throws SQLException {
        var chunk = insert("notes.md", 0, "needle");
        var before = SqliteFtsTestSupport.rows(storage.connection());
        execute(storage.connection(), "PRAGMA query_only = ON");

        assertEquals(List.of(chunk.id()), ids(retriever.retrieve("\"needle\"", 10)));
        assertEquals(before, SqliteFtsTestSupport.rows(storage.connection()));
        assertFalse(storage.connection().isClosed());
    }

    @Test
    void doesNotCommitOrRollBackTheCallersTransaction() throws SQLException {
        storage.connection().setAutoCommit(false);
        var chunk = insert("notes.md", 0, "needle");

        assertEquals(List.of(chunk.id()), ids(retriever.retrieve("\"needle\"", 10)));
        assertThrows(SQLException.class, () -> retriever.retrieve("\"unterminated", 10));
        assertFalse(storage.connection().getAutoCommit());
        assertEquals(List.of(chunk.id()), ids(retriever.retrieve("\"needle\"", 10)));

        storage.connection().rollback();
        assertTrue(retriever.retrieve("\"needle\"", 10).isEmpty());
        storage.connection().setAutoCommit(true);
    }

    private StoredChunk insert(String path, int index, String content, String... headings) throws SQLException {
        Path source = Path.of(path);
        var file = storage.files()
                .save(source, DocumentType.MARKDOWN, ContentHash.sha256(content.getBytes(StandardCharsets.UTF_8)));
        return storage.chunks()
                .insert(
                        file.id(),
                        new Chunk(
                                source,
                                DocumentType.MARKDOWN,
                                index,
                                content,
                                new LineRange(1, 2),
                                new ChunkMetadata(List.of(headings), Collections.emptyMap())));
    }

    private static List<Long> ids(List<SqliteLexicalRetriever.Candidate> candidates) {
        return candidates.stream()
                .map(SqliteLexicalRetriever.Candidate::chunkId)
                .toList();
    }
}
