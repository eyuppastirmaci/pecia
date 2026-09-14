package dev.eyuppastirmaci.pecia.storage.sqlite;

import dev.eyuppastirmaci.pecia.content.Chunk;
import dev.eyuppastirmaci.pecia.content.ChunkMetadata;
import dev.eyuppastirmaci.pecia.content.ContentHash;
import dev.eyuppastirmaci.pecia.content.Document;
import dev.eyuppastirmaci.pecia.content.DocumentType;
import dev.eyuppastirmaci.pecia.content.LineRange;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SqliteFileReplacementTest {
    @TempDir
    Path root;
    private static final Path SOURCE = Path.of("docs", "İstanbul.md");

    @Test
    void insertsThenReplacesTypeHashAndChunksWithoutChangingFileId() throws Exception {
        Path database = root.resolve("index.db");
        Document old = document(DocumentType.MARKDOWN, "old");
        Document updated = document(DocumentType.PLAIN_TEXT, "new 😀\r\nnext");
        long id;

        try (var storage = SqliteStorage.open(database, root)) {
            id = storage.replaceFile(old, List.of(chunk(old, 0))).id();
            var unrelated = storage.files().insert(Path.of("other.txt"), DocumentType.PLAIN_TEXT, old.contentHash());
            var saved = storage.replaceFile(updated, List.of(chunk(updated, 0), chunk(updated, 1)));
            assertEquals(id, saved.id());
            assertEquals(updated.contentHash(), saved.contentHash());
            assertEquals(updated.type(), saved.documentType());
            assertEquals(unrelated, storage.files().findByPath(unrelated.sourcePath()).orElseThrow());
        }

        try (var storage = SqliteStorage.open(database, root)) {
            assertEquals(List.of(chunk(updated, 0), chunk(updated, 1)),
                    storage.chunks().findByFileId(id).stream().map(value -> value.chunk()).toList());
            assertEquals(updated.contentHash(), storage.files().findByPath(SOURCE).orElseThrow().contentHash());
            assertEquals(id, storage.replaceFile(updated, List.of()).id());
            assertTrue(storage.chunks().findByFileId(id).isEmpty());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"BEFORE UPDATE ON files", "BEFORE DELETE ON chunks",
            "BEFORE INSERT ON chunks WHEN NEW.chunk_index = 1",
            "BEFORE INSERT ON chunk_attributes WHEN NEW.chunk_id IN (SELECT id FROM chunks WHERE chunk_index = 1)"})
    void failedReplacementRestoresTheExactPreviousSnapshot(String failurePoint) throws Exception {
        Path database = root.resolve("index.db");
        Document old = document(DocumentType.MARKDOWN, "old");
        Document updated = document(DocumentType.PLAIN_TEXT, "updated");

        try (var storage = SqliteStorage.open(database, root)) {
            var file = storage.replaceFile(old, List.of(chunk(old, 0)));
            var before = storage.chunks().findByFileId(file.id());

            try (var statement = storage.connection().createStatement()) {
                statement.executeUpdate("CREATE TRIGGER injected " + failurePoint
                        + " BEGIN SELECT RAISE(ABORT, 'replacement failure'); END;");
                assertThrows(SQLException.class,
                        () -> storage.replaceFile(updated, List.of(chunk(updated, 0), chunk(updated, 1))));
                assertEquals(file, storage.files().findByPath(SOURCE).orElseThrow());
                assertEquals(before, storage.chunks().findByFileId(file.id()));
                statement.executeUpdate("DROP TRIGGER injected");
            }
        }

        try (var storage = SqliteStorage.open(database, root)) {
            var file = storage.files().findByPath(SOURCE).orElseThrow();
            assertEquals(old.contentHash(), file.contentHash());
            assertEquals(List.of(chunk(old, 0)), storage.chunks().findByFileId(file.id()).stream().map(value -> value.chunk()).toList());
            assertEquals(file.id(), storage.replaceFile(updated, List.of(chunk(updated, 0))).id());
        }
    }

    @Test
    void failedFirstInsertionLeavesNoManifestOrOrphanedMetadata() throws Exception {
        try (var storage = SqliteStorage.open(root.resolve("index.db"), root);
             var statement = storage.connection().createStatement()) {
            Document document = document(DocumentType.MARKDOWN, "new");
            statement.executeUpdate("CREATE TRIGGER injected BEFORE INSERT ON chunk_attributes BEGIN SELECT RAISE(ABORT, 'failure'); END;");
            assertThrows(SQLException.class, () -> storage.replaceFile(document, List.of(chunk(document, 0))));

            for (String table : List.of("files", "chunks", "chunk_headings", "chunk_attributes")) {
                try (var rows = statement.executeQuery("SELECT count(*) FROM " + table)) {
                    assertTrue(rows.next());
                    assertEquals(0, rows.getInt(1));
                }
            }
        }
    }

    @Test
    void validatesTheCompleteReplacementBeforeModifyingExistingData() throws Exception {
        try (var storage = SqliteStorage.open(root.resolve("index.db"), root)) {
            Document document = document(DocumentType.MARKDOWN, "old");
            var file = storage.replaceFile(document, List.of(chunk(document, 0)));
            var before = storage.chunks().findByFileId(file.id());
            assertThrows(IllegalArgumentException.class,
                    () -> storage.replaceFile(document, List.of(chunk(document, 0), chunk(document, 0))));
            assertThrows(IllegalArgumentException.class,
                    () -> storage.replaceFile(document, List.of(chunk(document(DocumentType.PLAIN_TEXT, "wrong type"), 0))));
            assertThrows(IllegalArgumentException.class, () -> storage.replaceFile(document,
                    List.of(new Chunk(Path.of("other.md"), document.type(), 0, "wrong path", new LineRange(1, 1), ChunkMetadata.empty()))));
            assertThrows(NullPointerException.class, () -> storage.replaceFile(document, null));
            assertEquals(before, storage.chunks().findByFileId(file.id()));
        }
    }

    @Test
    void replacementDoesNotCommitAnOuterTransaction() throws Exception {
        try (var storage = SqliteStorage.open(root.resolve("index.db"), root)) {
            Document old = document(DocumentType.MARKDOWN, "old");
            Document updated = document(DocumentType.PLAIN_TEXT, "new");
            var file = storage.replaceFile(old, List.of(chunk(old, 0)));
            var before = storage.chunks().findByFileId(file.id());
            storage.connection().setAutoCommit(false);
            storage.replaceFile(updated, List.of(chunk(updated, 0)));
            storage.connection().rollback();
            assertEquals(file, storage.files().findByPath(SOURCE).orElseThrow());
            assertEquals(before, storage.chunks().findByFileId(file.id()));
            storage.connection().setAutoCommit(true);
        }
    }

    private static Document document(DocumentType type, String content) {
        return new Document(SOURCE, type, content, ContentHash.sha256(content.getBytes(StandardCharsets.UTF_8)));
    }

    private static Chunk chunk(Document document, int index) {
        return new Chunk(document.sourcePath(), document.type(), index, document.content(), new LineRange(1, 2),
                new ChunkMetadata(List.of("Başlık"), Map.of("startOffset", "0", "endOffset", Integer.toString(document.content().length()))));
    }
}
