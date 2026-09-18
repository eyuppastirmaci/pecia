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
import dev.eyuppastirmaci.pecia.search.SearchHit;
import dev.eyuppastirmaci.pecia.search.SearchScore;
import dev.eyuppastirmaci.pecia.storage.model.StoredChunk;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SqliteLexicalEnrichmentTest {
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
        storage.close();
    }

    @Test
    void mapsAuthoritativeSourceLocationAndOrderedMetadataWithoutDecoration() throws SQLException {
        String content = "<T> & needle\r\nİstanbul 😀";
        ChunkMetadata metadata = new ChunkMetadata(
                List.of("Guide > Root", "Install Now", "Install Now"),
                Map.of("language", "java", "startOffset", "70", "endOffset", "95"));
        var stored = insert("docs/Ödeme rehberi.md", 3, content, metadata);

        var hits = retriever.search("\"needle\"", 10);
        SearchHit hit = hits.getFirst();

        assertEquals(1, hits.size());
        assertEquals(stored.id(), hit.chunkId());
        assertEquals(3, hit.chunkIndex());
        assertEquals(Path.of("docs/Ödeme rehberi.md"), hit.sourcePath());
        assertEquals(DocumentType.SOURCE_CODE, hit.documentType());
        assertEquals(new LineRange(118, 161), hit.sourceLocation());
        assertEquals(metadata, hit.metadata());
        assertEquals(content, hit.snippet());
        assertEquals(SearchScore.Kind.SQLITE_BM25, hit.score().kind());
        assertTrue(hit.score().kind().lowerIsBetter());
        assertThrows(UnsupportedOperationException.class, hits::clear);
        assertThrows(
                UnsupportedOperationException.class,
                () -> hit.metadata().headingPath().add("changed"));
        assertThrows(
                UnsupportedOperationException.class,
                () -> hit.metadata().attributes().put("changed", "true"));
    }

    @Test
    void preservesRankingAndLimitWhileEnrichingDifferentHeadings() throws SQLException {
        var weak = insert(
                "a.md",
                0,
                "needle filler filler filler",
                new ChunkMetadata(List.of("Alpha Beta", "Gamma Delta"), Map.of("rank", "weak")));
        var strong = insert(
                "z.md",
                0,
                "needle needle needle filler",
                new ChunkMetadata(List.of("Other Words", "More Words"), Map.of("rank", "strong")));

        var hits = retriever.search("\"needle\"", 10);

        assertEquals(
                List.of(strong.id(), weak.id()),
                hits.stream().map(SearchHit::chunkId).toList());
        assertTrue(hits.get(0).score().value() < hits.get(1).score().value());
        assertEquals(
                List.of("Other Words", "More Words"), hits.get(0).metadata().headingPath());
        assertEquals(
                List.of("Alpha Beta", "Gamma Delta"), hits.get(1).metadata().headingPath());
        assertEquals(List.of(hits.getFirst()), retriever.search("\"needle\"", 1));
    }

    @Test
    void retainsDistinctChunksWithoutDuplicatingMultifieldMatches() throws SQLException {
        var first = insert("needle.md", 0, "needle", new ChunkMetadata(List.of("needle", "needle"), Map.of()));
        var second = insert("needle.md", 1, "needle", new ChunkMetadata(List.of("needle", "needle"), Map.of()));

        assertEquals(
                List.of(first.id(), second.id()),
                retriever.search("\"needle\"", 10).stream()
                        .map(SearchHit::chunkId)
                        .toList());
    }

    @Test
    void suppliesEmptyMetadataWhenTheChunkHasNone() throws SQLException {
        insert("notes.txt", 0, "needle", ChunkMetadata.empty());

        assertEquals(
                ChunkMetadata.empty(),
                retriever.search("\"needle\"", 10).getFirst().metadata());
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 50, 100})
    void selectsAnExcerptAroundContentMatchesAtBeginningMiddleAndEnd(int before) throws SQLException {
        String content = "before ".repeat(before) + "needle" + " after".repeat(100 - before);
        insert("notes.txt", 0, content, ChunkMetadata.empty());

        SearchHit hit = retriever.search("\"needle\"", 10).getFirst();

        assertTrue(hit.snippet().contains("needle"));
        assertTrue(hit.snippet().contains("…"));
        assertTrue(hit.snippet().codePointCount(0, hit.snippet().length()) <= 400);
        assertEquals(new LineRange(118, 161), hit.sourceLocation());
        if (before == 0) {
            assertTrue(hit.snippet().startsWith("needle"));
        } else if (before == 100) {
            assertTrue(hit.snippet().endsWith("needle"));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void usesTheContentBeginningWhenOnlyHeadingOrPathMatches(boolean pathMatch) throws SQLException {
        String content = "intro\r\n" + "body ".repeat(80);
        insert(
                pathMatch ? "needle.md" : "notes.md",
                0,
                content,
                pathMatch ? ChunkMetadata.empty() : new ChunkMetadata(List.of("needle"), Map.of()));

        SearchHit hit = retriever.search("\"needle\"", 10).getFirst();

        assertTrue(hit.snippet().startsWith("intro\r\nbody "));
        assertFalse(hit.snippet().contains("needle"));
        assertTrue(hit.snippet().endsWith("…"));
    }

    @Test
    void capsLongSingleTokensWithoutLosingTheCodePointBoundary() throws SQLException {
        String content = "\ud801\udc00".repeat(450);
        insert("needle.md", 0, content, ChunkMetadata.empty());

        String snippet = retriever.search("\"needle\"", 10).getFirst().snippet();

        assertEquals("\ud801\udc00".repeat(399) + "…", snippet);
        assertEquals(400, snippet.codePointCount(0, snippet.length()));
    }

    @Test
    void keepsEmojiTogetherAtTheCharacterCapAndIncludesTheEllipsisInTheBudget() throws SQLException {
        String content = "x".repeat(398) + "🚀" + "y".repeat(40);
        insert("needle.md", 0, content, ChunkMetadata.empty());

        assertEquals(
                "x".repeat(398) + "🚀…",
                retriever.search("\"needle\"", 10).getFirst().snippet());
    }

    @Test
    void preservesExactBoundaryAndTokenlessContent() throws SQLException {
        insert("needle.md", 0, "🚀".repeat(400), ChunkMetadata.empty());

        assertEquals(
                "🚀".repeat(400), retriever.search("\"needle\"", 10).getFirst().snippet());
    }

    @Test
    void loadsOnlySelectedMetadataWithTwoBoundedQueries() throws SQLException {
        var first = insert("a.md", 0, "needle", new ChunkMetadata(List.of("Title"), Map.of("order", "first")));
        insert("b.md", 0, "needle", new ChunkMetadata(List.of("Title"), Map.of("order", "second")));
        var unselected = insert("z.md", 0, "needle", new ChunkMetadata(List.of("Title"), Map.of()));
        execute(storage.connection(), "UPDATE chunk_headings SET position = 2 WHERE chunk_id = ?", unselected.id());

        List<String> queries = new ArrayList<>();
        Connection counted = (Connection) Proxy.newProxyInstance(
                Connection.class.getClassLoader(), new Class<?>[] {Connection.class}, (proxy, method, arguments) -> {
                    if (method.getName().equals("prepareStatement")) {
                        queries.add((String) arguments[0]);
                    }
                    try {
                        return method.invoke(storage.connection(), arguments);
                    } catch (InvocationTargetException failure) {
                        throw failure.getCause();
                    }
                });
        var reader = new SqliteChunkMetadataReader(counted);
        var instrumented = new SqliteLexicalRetriever(storage, reader::readForChunks);

        var hits = instrumented.search("\"needle\"", 2);

        assertEquals(2, hits.size());
        assertEquals(first.id(), hits.getFirst().chunkId());
        assertEquals(2, queries.size());
        assertTrue(queries.stream().allMatch(sql -> sql.contains("IN (?,?)")));
        queries.clear();
        assertTrue(instrumented.search("\"absent\"", 10).isEmpty());
        assertTrue(queries.isEmpty());
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "UPDATE chunks SET start_line = 0",
                "UPDATE chunks SET end_line = 1",
                "UPDATE chunks SET chunk_index = -1",
                "UPDATE chunks SET content = ' '",
                "UPDATE files SET source_path = 'a//b'",
                "UPDATE files SET document_type = 'UNKNOWN'",
                "UPDATE chunk_headings SET position = 2",
                "UPDATE chunk_headings SET heading = ' '",
                "UPDATE chunk_attributes SET name = ' ' WHERE name = 'key'"
            })
    void rejectsCorruptResultsWithoutRollingBackTheCallersChanges(String corruption) throws SQLException {
        insert("needle.md", 0, "needle", new ChunkMetadata(List.of("Title"), Map.of("key", "value")));
        execute(storage.connection(), "PRAGMA ignore_check_constraints = ON");
        storage.connection().setAutoCommit(false);
        execute(storage.connection(), "INSERT INTO chunk_attributes VALUES (1, 'sentinel', 'retained')");
        execute(storage.connection(), corruption);

        assertThrows(SQLException.class, () -> retriever.search("\"needle\"", 10));
        assertFalse(storage.connection().getAutoCommit());
        try (var statement = storage.connection().createStatement();
                var rows = statement.executeQuery("SELECT value FROM chunk_attributes WHERE name = 'sentinel'")) {
            assertTrue(rows.next());
            assertEquals("retained", rows.getString(1));
        }

        storage.connection().rollback();
        storage.connection().setAutoCommit(true);
        assertEquals(
                List.of("Title"),
                retriever.search("\"needle\"", 10).getFirst().metadata().headingPath());
    }

    @Test
    void rollsBackOnlyItsReadScopeAfterMetadataFailureAndAllowsRetry() throws SQLException {
        var chunk = insert("needle.md", 0, "needle", new ChunkMetadata(List.of("Title"), Map.of()));
        execute(storage.connection(), "UPDATE chunk_headings SET position = 2 WHERE chunk_id = ?", chunk.id());

        assertThrows(SQLException.class, () -> retriever.search("\"needle\"", 10));
        assertTrue(storage.connection().getAutoCommit());
        assertFalse(storage.connection().isClosed());
        execute(storage.connection(), "UPDATE chunk_headings SET position = 0 WHERE chunk_id = ?", chunk.id());
        assertEquals(1, retriever.search("\"needle\"", 10).size());
    }

    @Test
    void preservesReadOnlyStateAndTheCallersTransactionOnSuccess() throws SQLException {
        storage.connection().setAutoCommit(false);
        insert("needle.md", 0, "needle", new ChunkMetadata(List.of("Title"), Map.of()));
        var before = SqliteFtsTestSupport.rows(storage.connection());
        execute(storage.connection(), "PRAGMA query_only = ON");

        assertEquals(1, retriever.search("\"needle\"", 10).size());
        assertFalse(storage.connection().getAutoCommit());
        assertFalse(storage.connection().isClosed());
        assertEquals(before, SqliteFtsTestSupport.rows(storage.connection()));
        storage.connection().rollback();
        assertTrue(retriever.search("\"needle\"", 10).isEmpty());
    }

    @Test
    void validatesArgumentsBeforeOpeningAReadScopeAndPropagatesClosedStorage() throws SQLException {
        storage.close();

        assertThrows(NullPointerException.class, () -> retriever.search(null, 10));
        assertThrows(IllegalArgumentException.class, () -> retriever.search("", 10));
        assertThrows(IllegalArgumentException.class, () -> retriever.search("\"needle\"", 0));
        assertThrows(IllegalArgumentException.class, () -> retriever.search("\"needle\"", 101));
        assertThrows(SQLException.class, () -> retriever.search("\"needle\"", 10));
    }

    private StoredChunk insert(String path, int index, String content, ChunkMetadata metadata) throws SQLException {
        Path source = Path.of(path);
        var file = storage.files()
                .save(source, DocumentType.SOURCE_CODE, ContentHash.sha256(content.getBytes(StandardCharsets.UTF_8)));
        return storage.chunks()
                .insert(
                        file.id(),
                        new Chunk(source, DocumentType.SOURCE_CODE, index, content, new LineRange(118, 161), metadata));
    }
}
