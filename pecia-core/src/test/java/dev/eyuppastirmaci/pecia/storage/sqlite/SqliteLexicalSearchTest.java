package dev.eyuppastirmaci.pecia.storage.sqlite;

import dev.eyuppastirmaci.pecia.content.*;
import dev.eyuppastirmaci.pecia.search.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SqliteLexicalSearchTest {
    @TempDir
    Path root;

    @Test
    void runsTheDocumentedExample() throws Exception {
        try (var storage = SqliteStorage.open(root.resolve("index.db"), root)) {
            var path = Path.of("src/Auth.java");
            var text = "JWT_SECRET authentication middleware";
            var document = new Document(path, DocumentType.SOURCE_CODE, text,
                    ContentHash.sha256(text.getBytes(StandardCharsets.UTF_8)));
            var chunk = new Chunk(path, document.type(), 0, text,
                    new LineRange(1, 1), ChunkMetadata.empty());
            storage.replaceFile(document, List.of(chunk));
            var hits = storage.lexicalSearch().search(new SearchRequest("JWT_SECRET"));
            assertEquals(1, hits.size());
            assertEquals(path, hits.getFirst().sourcePath());
            assertEquals(text, hits.getFirst().snippet());
            assertEquals(new LineRange(1, 1), hits.getFirst().sourceLocation());
        }
    }

    @Test
    void searchesMixedCorpusLiterallyAndPreservesImmutableRankedResults() throws Exception {
        try (var storage = SqliteStorage.open(root.resolve("index.db"), root)) {
            put(storage, "src/Auth.java", DocumentType.SOURCE_CODE,
                    "JWT_SECRET authentication middleware UserRepository.findByEmail", "Auth");
            put(storage, "docs/guide.md", DocumentType.MARKDOWN,
                    "authentication guide café İstanbul", "Guide");
            put(storage, "notes.txt", DocumentType.PLAIN_TEXT, "authentication OR middleware", "Notes");
            var search = storage.lexicalSearch();
            assertEquals(Path.of("src/Auth.java"), search.search(new SearchRequest("JWT_SECRET")).getFirst().sourcePath());
            assertEquals(1, search.search(new SearchRequest("UserRepository.findByEmail")).size());
            assertEquals(Path.of("docs/guide.md"), search.search(new SearchRequest("café İstanbul")).getFirst().sourcePath());
            assertEquals(2, search.search(new SearchRequest("authentication middleware")).size());
            assertEquals(Path.of("notes.txt"), search.search(new SearchRequest("authentication OR middleware")).getFirst().sourcePath());
            var hits = search.search(new SearchRequest("authentication"));
            assertEquals(3, hits.size());
            assertEquals(hits, search.search(new SearchRequest("authentication")));
            assertEquals(hits.subList(0, 1), search.search(new SearchRequest("authentication", 1)));
            assertThrows(UnsupportedOperationException.class, () -> hits.clear());
            assertTrue(hits.getFirst().score().kind().lowerIsBetter());
        }
    }

    @Test
    void distinguishesEmptyQueriesInvalidRequestsAndClosedStorage() throws Exception {
        var storage = SqliteStorage.open(root.resolve("index.db"), root);
        var search = storage.lexicalSearch();
        try (storage) {
            assertTrue(search.search(new SearchRequest("absent")).isEmpty());
            put(storage, "a.txt", DocumentType.PLAIN_TEXT, "needle", "Heading");
            assertTrue(search.search(new SearchRequest("absent")).isEmpty());
            assertTrue(search.search(new SearchRequest("!!! () \"\"")).isEmpty());
            assertThrows(NullPointerException.class, () -> search.search(null));
            assertThrows(IllegalArgumentException.class, () -> search.search(new SearchRequest(" ")));
            assertThrows(IllegalArgumentException.class, () -> search.search(new SearchRequest("needle", 0)));
            assertThrows(IllegalArgumentException.class, () -> search.search(new SearchRequest("word ".repeat(65))));
        }
        assertInstanceOf(SQLException.class,
                assertThrows(SearchException.class, () -> search.search(new SearchRequest("needle"))).getCause());
        assertTrue(search.search(new SearchRequest("!!!")).isEmpty());
        assertThrows(NullPointerException.class, () -> search.search(null));
    }

    @Test
    void seesReplacementEmptyReplacementDeletionAndRollbackThroughTheSameService() throws Exception {
        try (var storage = SqliteStorage.open(root.resolve("index.db"), root)) {
            var search = storage.lexicalSearch();
            put(storage, "a.txt", DocumentType.PLAIN_TEXT, "oldword", "Old");
            storage.connection().setAutoCommit(false);
            put(storage, "a.txt", DocumentType.PLAIN_TEXT, "newword", "New");
            assertTrue(search.search(new SearchRequest("oldword")).isEmpty());
            assertEquals(1, search.search(new SearchRequest("newword")).size());
            assertFalse(storage.connection().getAutoCommit());
            storage.connection().rollback();
            storage.connection().setAutoCommit(true);
            assertEquals(1, search.search(new SearchRequest("oldword")).size());
            assertTrue(search.search(new SearchRequest("newword")).isEmpty());
            put(storage, "a.txt", DocumentType.PLAIN_TEXT, "newword", "New");
            assertEquals(1, search.search(new SearchRequest("newword")).size());
            assertTrue(search.search(new SearchRequest("oldword")).isEmpty());
            var path = Path.of("a.txt");
            storage.replaceFile(document(path, DocumentType.PLAIN_TEXT, "newword"), List.of());
            assertTrue(search.search(new SearchRequest("newword")).isEmpty());
            put(storage, "a.txt", DocumentType.PLAIN_TEXT, "deletedword", "Delete");
            storage.files().delete(storage.files().findByPath(path).orElseThrow().id());
            assertTrue(search.search(new SearchRequest("deletedword")).isEmpty());
        }
    }

    @Test
    void queryOnlySearchDoesNotWriteOrCloseTheBorrowedConnection() throws Exception {
        try (var storage = SqliteStorage.open(root.resolve("index.db"), root)) {
            put(storage, "a.txt", DocumentType.PLAIN_TEXT, "needle", "Heading");
            var files = storage.files().findAll();
            var chunks = storage.chunks().findByFileId(files.getFirst().id());
            try (var statement = storage.connection().createStatement()) {
                statement.execute("PRAGMA query_only = ON");
                long changes;
                try (var rows = statement.executeQuery("SELECT total_changes()")) {
                    assertTrue(rows.next());
                    changes = rows.getLong(1);
                }
                var search = storage.lexicalSearch();
                var hits = search.search(new SearchRequest("needle"));
                assertEquals(1, hits.size());
                assertEquals(hits, search.search(new SearchRequest("needle")));
                try (var rows = statement.executeQuery("SELECT total_changes()")) {
                    assertTrue(rows.next());
                    assertEquals(changes, rows.getLong(1));
                }
            }
            assertFalse(storage.connection().isClosed());
            assertTrue(storage.connection().getAutoCommit());
            assertEquals(files, storage.files().findAll());
            assertEquals(chunks, storage.chunks().findByFileId(files.getFirst().id()));
        }
    }

    @Test
    void preservesMappingFailureCauseAndCallerTransactionThenAllowsRetry() throws Exception {
        try (var storage = SqliteStorage.open(root.resolve("index.db"), root)) {
            put(storage, "a.txt", DocumentType.PLAIN_TEXT, "needle", "Heading");
            storage.connection().setAutoCommit(false);
            try (var statement = storage.connection().createStatement()) {
                statement.executeUpdate("UPDATE chunk_headings SET position = 2");
                var failure = assertThrows(SearchException.class,
                        () -> storage.lexicalSearch().search(new SearchRequest("needle")));
                assertInstanceOf(SQLException.class, failure.getCause());
                assertEquals("Non-contiguous stored heading positions", failure.getCause().getMessage());
                assertFalse(storage.connection().getAutoCommit());
                try (var rows = statement.executeQuery("SELECT position FROM chunk_headings")) {
                    assertTrue(rows.next());
                    assertEquals(2, rows.getInt(1));
                }
            }
            storage.connection().rollback();
            storage.connection().setAutoCommit(true);
            assertEquals(1, storage.lexicalSearch().search(new SearchRequest("needle")).size());
        }
    }

    private static Document document(Path path, DocumentType type, String content) {
        return new Document(path, type, content, ContentHash.sha256(content.getBytes(StandardCharsets.UTF_8)));
    }

    private static void put(SqliteStorage storage, String path, DocumentType type, String content, String heading)
            throws SQLException {
        var document = document(Path.of(path), type, content);
        storage.replaceFile(document, List.of(new Chunk(document.sourcePath(), type, 0, content,
                new LineRange(2, 5), new ChunkMetadata(List.of(heading), Map.of("language", "test")))));
    }
}
