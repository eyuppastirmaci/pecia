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
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;

import static dev.eyuppastirmaci.pecia.storage.sqlite.SqliteFtsTestSupport.*;
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
        long unrelatedChunkId;

        try (var storage = SqliteStorage.open(database, root)) {
            id = storage.replaceFile(old, List.of(chunk(old, 0))).id();
            var unrelated = storage.files().insert(Path.of("other.txt"), DocumentType.PLAIN_TEXT, old.contentHash());
            unrelatedChunkId = storage.chunks().insert(unrelated.id(), new Chunk(unrelated.sourcePath(),
                    unrelated.documentType(), 0, "unrelatedbody", new LineRange(1, 1),
                    new ChunkMetadata(List.of("Unrelatedheading"), Map.of()))).id();
            assertIndexed(storage, id, "old", "originalheading");
            var saved = storage.replaceFile(updated, List.of(chunk(updated, 0), chunk(updated, 1)));
            assertEquals(id, saved.id());
            assertEquals(updated.contentHash(), saved.contentHash());
            assertEquals(updated.type(), saved.documentType());
            assertEquals(unrelated, storage.files().findByPath(unrelated.sourcePath()).orElseThrow());
            assertIndexed(storage, id, "new", "replacementheading");
            assertMatches(storage.connection(), "old OR originalheading");
            assertUnrelated(storage.connection(), unrelatedChunkId);

            // Replacing identical input must leave exactly one search row per current chunk.
            assertEquals(id, storage.replaceFile(updated, List.of(chunk(updated, 0), chunk(updated, 1))).id());
            assertIndexed(storage, id, "new", "replacementheading");
            assertUnrelated(storage.connection(), unrelatedChunkId);
        }

        try (var storage = SqliteStorage.open(database, root)) {
            assertEquals(List.of(chunk(updated, 0), chunk(updated, 1)),
                    storage.chunks().findByFileId(id).stream().map(value -> value.chunk()).toList());
            assertEquals(updated.contentHash(), storage.files().findByPath(SOURCE).orElseThrow().contentHash());
            assertIndexed(storage, id, "new", "replacementheading");
            Document reduced = document(DocumentType.MARKDOWN, "reducedbody");
            assertEquals(id, storage.replaceFile(reduced, List.of(chunk(reduced, 0))).id());
            assertIndexed(storage, id, "reducedbody", "originalheading");
            assertMatches(storage.connection(), "new OR replacementheading");
            assertUnrelated(storage.connection(), unrelatedChunkId);

            assertEquals(id, storage.replaceFile(document(DocumentType.PLAIN_TEXT, ""), List.of()).id());
            assertTrue(storage.chunks().findByFileId(id).isEmpty());
            assertMatches(storage.connection(), "old OR new OR reducedbody OR originalheading OR replacementheading OR docs");
            assertUnrelated(storage.connection(), unrelatedChunkId);
            assertConsistent(storage.connection());
        }

        try (var storage = SqliteStorage.open(database, root)) {
            assertEquals(id, storage.files().findByPath(SOURCE).orElseThrow().id());
            assertEquals(document(DocumentType.PLAIN_TEXT, "").contentHash(),
                    storage.files().findByPath(SOURCE).orElseThrow().contentHash());
            assertTrue(storage.chunks().findByFileId(id).isEmpty());
            assertMatches(storage.connection(), "old OR new OR reducedbody OR originalheading OR replacementheading OR docs");
            assertUnrelated(storage.connection(), unrelatedChunkId);
            assertConsistent(storage.connection());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"BEFORE UPDATE ON files", "BEFORE DELETE ON chunks",
            "BEFORE DELETE ON chunks WHEN OLD.chunk_index = 1",
            "BEFORE INSERT ON chunks WHEN NEW.chunk_index = 1",
            "BEFORE INSERT ON chunk_attributes WHEN NEW.chunk_id IN (SELECT id FROM chunks WHERE chunk_index = 1)"})
    void failedReplacementRestoresTheExactPreviousSnapshot(String failurePoint) throws Exception {
        Path database = root.resolve("index.db");
        Document old = document(DocumentType.MARKDOWN, "old");
        Document updated = document(DocumentType.PLAIN_TEXT, "updated");

        try (var storage = SqliteStorage.open(database, root)) {
            var file = storage.replaceFile(old, List.of(chunk(old, 0), chunk(old, 1)));
            var before = storage.chunks().findByFileId(file.id());
            var ftsBefore = rows(storage.connection());
            assertIndexed(storage, file.id(), "old", "originalheading");

            try (var statement = storage.connection().createStatement()) {
                statement.executeUpdate("CREATE TRIGGER injected " + failurePoint
                        + " BEGIN SELECT RAISE(ABORT, 'replacement failure'); END;");
                assertThrows(SQLException.class,
                        () -> storage.replaceFile(updated, List.of(chunk(updated, 0), chunk(updated, 1))));
                assertEquals(file, storage.files().findByPath(SOURCE).orElseThrow());
                assertEquals(before, storage.chunks().findByFileId(file.id()));
                assertEquals(ftsBefore, rows(storage.connection()));
                assertIndexed(storage, file.id(), "old", "originalheading");
                assertMatches(storage.connection(), "updated OR replacementheading");
                statement.executeUpdate("DROP TRIGGER injected");
            }
        }

        try (var storage = SqliteStorage.open(database, root)) {
            var file = storage.files().findByPath(SOURCE).orElseThrow();
            assertEquals(old.contentHash(), file.contentHash());
            assertEquals(List.of(chunk(old, 0), chunk(old, 1)),
                    storage.chunks().findByFileId(file.id()).stream().map(value -> value.chunk()).toList());
            assertIndexed(storage, file.id(), "old", "originalheading");
            assertMatches(storage.connection(), "updated OR replacementheading");
            assertEquals(file.id(), storage.replaceFile(updated, List.of(chunk(updated, 0), chunk(updated, 1))).id());
            assertIndexed(storage, file.id(), "updated", "replacementheading");
            assertMatches(storage.connection(), "old OR originalheading");
        }

        try (var storage = SqliteStorage.open(database, root)) {
            var file = storage.files().findByPath(SOURCE).orElseThrow();
            assertEquals(List.of(chunk(updated, 0), chunk(updated, 1)),
                    storage.chunks().findByFileId(file.id()).stream().map(value -> value.chunk()).toList());
            assertIndexed(storage, file.id(), "updated", "replacementheading");
            assertMatches(storage.connection(), "old OR originalheading");
        }
    }

    @Test
    void failedFirstInsertionLeavesNoManifestOrOrphanedMetadata() throws Exception {
        try (var storage = SqliteStorage.open(root.resolve("index.db"), root);
             var statement = storage.connection().createStatement()) {
            Document document = document(DocumentType.MARKDOWN, "new");
            statement.executeUpdate("CREATE TRIGGER injected BEFORE INSERT ON chunk_attributes BEGIN SELECT RAISE(ABORT, 'failure'); END;");
            assertThrows(SQLException.class, () -> storage.replaceFile(document, List.of(chunk(document, 0))));

            for (String table : List.of("files", "chunks", "chunk_headings", "chunk_attributes", "chunks_fts")) {
                try (var rows = statement.executeQuery("SELECT count(*) FROM " + table)) {
                    assertTrue(rows.next());
                    assertEquals(0, rows.getInt(1));
                }
            }
            assertMatches(storage.connection(), "new OR originalheading OR docs");
            assertConsistent(storage.connection());
            statement.executeUpdate("DROP TRIGGER injected");
            var file = storage.replaceFile(document, List.of(chunk(document, 0)));
            assertIndexed(storage, file.id(), "new", "originalheading");
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
            assertIndexed(storage, file.id(), "old", "originalheading");
            assertMatches(storage.connection(), "wrong");
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void replacementIsVisibleToAnotherConnectionOnlyAfterOuterCommit(boolean commit) throws Exception {
        Path database = root.resolve("index.db");
        try (var storage = SqliteStorage.open(database, root);
             var reader = SqliteStorage.open(database, root)) {
            Document old = document(DocumentType.MARKDOWN, "old");
            Document updated = document(DocumentType.PLAIN_TEXT, "new");
            var file = storage.replaceFile(old, List.of(chunk(old, 0)));
            var before = storage.chunks().findByFileId(file.id());
            var ftsBefore = rows(reader.connection());
            storage.connection().setAutoCommit(false);
            storage.replaceFile(updated, List.of(chunk(updated, 0)));
            assertIndexed(storage, file.id(), "new", "replacementheading");
            assertMatches(storage.connection(), "old OR originalheading");
            // Keep the reader read-only while the writer holds the transaction; integrity-check writes.
            assertEquals(file, reader.files().findByPath(SOURCE).orElseThrow());
            assertEquals(before, reader.chunks().findByFileId(file.id()));
            assertEquals(ftsBefore, rows(reader.connection()));
            assertMatches(reader.connection(), "content: old", before.getFirst().id());
            assertMatches(reader.connection(), "headings: originalheading", before.getFirst().id());
            assertMatches(reader.connection(), "source_path: docs", before.getFirst().id());
            assertMatches(reader.connection(), "new OR replacementheading");

            if (commit) {
                storage.connection().commit();
            } else {
                storage.connection().rollback();
                assertEquals(file, storage.files().findByPath(SOURCE).orElseThrow());
                assertEquals(before, storage.chunks().findByFileId(file.id()));
                assertEquals(ftsBefore, rows(storage.connection()));
            }
            storage.connection().setAutoCommit(true);
            assertEquals(commit ? updated.contentHash() : old.contentHash(),
                    reader.files().findByPath(SOURCE).orElseThrow().contentHash());
            assertIndexed(reader, file.id(), commit ? "new" : "old",
                    commit ? "replacementheading" : "originalheading");
            assertMatches(reader.connection(), commit ? "old OR originalheading" : "new OR replacementheading");
        }
        try (var storage = SqliteStorage.open(database, root)) {
            long id = storage.files().findByPath(SOURCE).orElseThrow().id();
            assertIndexed(storage, id, commit ? "new" : "old",
                    commit ? "replacementheading" : "originalheading");
            assertMatches(storage.connection(), commit ? "old OR originalheading" : "new OR replacementheading");
        }
    }

    @Test
    void failedReplacementPreservesEarlierWritesInTheOuterTransaction() throws Exception {
        Path database = root.resolve("index.db");
        Document old = document(DocumentType.MARKDOWN, "old");
        Document updated = document(DocumentType.PLAIN_TEXT, "updated");
        long fileId;
        long retainedId;
        try (var storage = SqliteStorage.open(database, root)) {
            fileId = storage.replaceFile(old, List.of(chunk(old, 0))).id();
            var original = storage.chunks().findByFileId(fileId);
            execute(storage.connection(), """
                    CREATE TRIGGER injected BEFORE INSERT ON chunk_attributes
                    WHEN NEW.chunk_id IN (SELECT id FROM chunks WHERE chunk_index = 1)
                    BEGIN SELECT RAISE(ABORT, 'replacement failure'); END;
                    """);
            storage.connection().setAutoCommit(false);
            var unrelated = storage.files().insert(Path.of("other.txt"), DocumentType.PLAIN_TEXT, old.contentHash());
            retainedId = storage.chunks().insert(unrelated.id(), new Chunk(unrelated.sourcePath(), unrelated.documentType(),
                    0, "unrelatedbody", new LineRange(1, 1),
                    new ChunkMetadata(List.of("Unrelatedheading"), Map.of()))).id();
            var before = rows(storage.connection());
            assertThrows(SQLException.class,
                    () -> storage.replaceFile(updated, List.of(chunk(updated, 0), chunk(updated, 1))));
            assertFalse(storage.connection().getAutoCommit());
            assertEquals(original, storage.chunks().findByFileId(fileId));
            assertEquals(before, rows(storage.connection()));
            assertIndexed(storage, fileId, "old", "originalheading");
            assertMatches(storage.connection(), "updated OR replacementheading");
            assertUnrelated(storage.connection(), retainedId);
            execute(storage.connection(), "DROP TRIGGER injected");
            storage.connection().commit();
            storage.connection().setAutoCommit(true);
        }
        try (var storage = SqliteStorage.open(database, root)) {
            assertEquals(old.contentHash(), storage.files().findByPath(SOURCE).orElseThrow().contentHash());
            assertIndexed(storage, fileId, "old", "originalheading");
            assertMatches(storage.connection(), "updated OR replacementheading");
            assertUnrelated(storage.connection(), retainedId);
        }
    }

    private static void assertIndexed(SqliteStorage storage, long fileId, String content, String heading)
            throws SQLException {
        long[] ids = storage.chunks().findByFileId(fileId).stream().mapToLong(value -> value.id()).sorted().toArray();
        assertTrue(ids.length > 0);
        assertMatches(storage.connection(), "content: " + content, ids);
        assertMatches(storage.connection(), "headings: " + heading, ids);
        assertMatches(storage.connection(), "source_path: docs", ids);
        assertConsistent(storage.connection());
    }

    private static void assertUnrelated(Connection connection, long id) throws SQLException {
        assertMatches(connection, "content: unrelatedbody", id);
        assertMatches(connection, "headings: unrelatedheading", id);
        assertMatches(connection, "source_path: other", id);
    }

    private static Document document(DocumentType type, String content) {
        return new Document(SOURCE, type, content, ContentHash.sha256(content.getBytes(StandardCharsets.UTF_8)));
    }

    private static Chunk chunk(Document document, int index) {
        return new Chunk(document.sourcePath(), document.type(), index, document.content(), new LineRange(1, 2),
                new ChunkMetadata(List.of("Başlık", document.type() == DocumentType.MARKDOWN
                        ? "Originalheading" : "Replacementheading"),
                        Map.of("startOffset", "0", "endOffset", Integer.toString(document.content().length()))));
    }
}
