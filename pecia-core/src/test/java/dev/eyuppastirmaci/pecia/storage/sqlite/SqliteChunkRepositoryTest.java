package dev.eyuppastirmaci.pecia.storage.sqlite;

import dev.eyuppastirmaci.pecia.content.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SqliteChunkRepositoryTest {
    @TempDir
    Path root;
    private static final Path SOURCE = Path.of("docs", "İstanbul.md");
    private static final ChunkMetadata META = new ChunkMetadata(List.of("Başlık", "Başlık", "Alt 😀"),
            Map.of("startOffset", "10", "endOffset", "20", "empty", "", "custom", "'\"\n\r\tİ😀"));

    @Test
    void roundTripsExactChunksInIndexOrderAcrossReopening() throws Exception {
        Path database = root.resolve("index.db");
        long fileId;

        try (var storage = SqliteStorage.open(database, root)) {
            fileId = file(storage);
            storage.chunks().insert(fileId, chunk(2, META));
            storage.chunks().insert(fileId, chunk(0, ChunkMetadata.empty()));
            storage.chunks().insert(fileId, chunk(1, META));
        }

        try (var storage = SqliteStorage.open(database, root)) {
            var chunks = storage.chunks().findByFileId(fileId);
            assertEquals(List.of(chunk(0, ChunkMetadata.empty()), chunk(1, META), chunk(2, META)),
                    chunks.stream().map(stored -> stored.chunk()).toList());
            assertTrue(chunks.stream().allMatch(stored -> stored.id() > 0 && stored.fileId() == fileId));
            assertThrows(UnsupportedOperationException.class, chunks::clear);
            assertEquals(3, storage.chunks().deleteByFileId(fileId));
            assertTrue(storage.chunks().findByFileId(fileId).isEmpty());
            assertTrue(storage.files().findByPath(SOURCE).isPresent());

            try (var statement = storage.connection().createStatement();
                 var rows = statement.executeQuery("SELECT (SELECT count(*) FROM chunk_headings) + (SELECT count(*) FROM chunk_attributes)")) {
                assertTrue(rows.next());
                assertEquals(0, rows.getInt(1));
            }
        }
    }

    @Test
    void rejectsMismatchedIdentityMissingFilesAndDuplicatePositions() throws Exception {
        try (var storage = SqliteStorage.open(root.resolve("index.db"), root)) {
            long id = file(storage);
            var original = storage.chunks().insert(id, chunk(0, META));
            assertThrows(SQLException.class, () -> storage.chunks().insert(id, chunk(0, META)));
            assertThrows(SQLException.class, () -> storage.chunks().insert(id + 1, chunk(1, META)));
            assertThrows(SQLException.class, () -> storage.chunks().insert(id,
                    new Chunk(Path.of("other.md"), DocumentType.MARKDOWN, 1, "text", new LineRange(1, 1), META)));
            assertThrows(SQLException.class, () -> storage.chunks().insert(id,
                    new Chunk(SOURCE, DocumentType.PLAIN_TEXT, 1, "text", new LineRange(1, 1), META)));
            assertEquals(List.of(original), storage.chunks().findByFileId(id));
        }
    }

    @Test
    void metadataFailureRollsBackChunkAndHeadingsAndAllowsRetry() throws Exception {
        try (var storage = SqliteStorage.open(root.resolve("index.db"), root)) {
            long id = file(storage);

            try (var statement = storage.connection().createStatement()) {
                statement.executeUpdate("CREATE TRIGGER fail_metadata BEFORE INSERT ON chunk_attributes BEGIN SELECT RAISE(ABORT, 'injected'); END;");
                assertThrows(SQLException.class, () -> storage.chunks().insert(id, chunk(0, META)));
                assertTrue(storage.chunks().findByFileId(id).isEmpty());
                statement.executeUpdate("DROP TRIGGER fail_metadata");
            }

            assertEquals(chunk(0, META), storage.chunks().insert(id, chunk(0, META)).chunk());
        }
    }

    @Test
    void savepointsPreserveTheOuterTransactionAndEarlierWrites() throws Exception {
        try (var storage = SqliteStorage.open(root.resolve("index.db"), root)) {
            long id = file(storage);
            storage.connection().setAutoCommit(false);
            storage.chunks().insert(id, chunk(0, META));
            assertThrows(SQLException.class, () -> storage.chunks().insert(id, chunk(0, META)));
            assertEquals(1, storage.chunks().findByFileId(id).size());
            storage.connection().rollback();
            assertTrue(storage.chunks().findByFileId(id).isEmpty());
            storage.connection().setAutoCommit(true);
        }
    }

    @Test
    void rejectsCorruptHeadingOrderAndInvalidChunkRows() throws Exception {
        try (var storage = SqliteStorage.open(root.resolve("index.db"), root)) {
            long id = file(storage);
            storage.chunks().insert(id, chunk(0, META));

            try (var statement = storage.connection().createStatement()) {
                statement.executeUpdate("DELETE FROM chunk_headings WHERE position = 0");
                assertThrows(SQLException.class, () -> storage.chunks().findByFileId(id));
                statement.executeUpdate("DELETE FROM chunk_headings; UPDATE chunks SET content = '   '");
                assertThrows(SQLException.class, () -> storage.chunks().findByFileId(id));
            }
        }
    }

    @Test
    void isolatesFilesAndHandlesEmptyOrMissingFiles() throws Exception {
        try (var storage = SqliteStorage.open(root.resolve("index.db"), root)) {
            long id = file(storage);
            long other = storage.files().insert(Path.of("other.md"), DocumentType.MARKDOWN, ContentHash.sha256(new byte[0])).id();
            storage.chunks().insert(id, chunk(0, META));
            assertTrue(storage.chunks().findByFileId(other).isEmpty());
            assertTrue(storage.chunks().findByFileId(999).isEmpty());
            assertEquals(0, storage.chunks().deleteByFileId(other));
            assertEquals(1, storage.chunks().findByFileId(id).size());
            assertThrows(IllegalArgumentException.class, () -> storage.chunks().findByFileId(0));
            assertThrows(IllegalArgumentException.class, () -> storage.chunks().insert(id,
                    new Chunk(SOURCE, DocumentType.MARKDOWN, 1, "text", new SourceLocation() { }, META)));
        }
    }

    private static long file(SqliteStorage storage) throws SQLException {
        return storage.files().insert(SOURCE, DocumentType.MARKDOWN, ContentHash.sha256(new byte[0])).id();
    }

    private static Chunk chunk(int index, ChunkMetadata metadata) {
        return new Chunk(SOURCE, DocumentType.MARKDOWN, index, "İ😀 é\r\nson", new LineRange(2, 3), metadata);
    }
}
