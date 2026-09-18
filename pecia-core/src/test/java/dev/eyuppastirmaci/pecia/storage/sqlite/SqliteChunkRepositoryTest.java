package dev.eyuppastirmaci.pecia.storage.sqlite;

import static dev.eyuppastirmaci.pecia.storage.sqlite.SqliteFtsTestSupport.assertConsistent;
import static dev.eyuppastirmaci.pecia.storage.sqlite.SqliteFtsTestSupport.assertMatches;
import static dev.eyuppastirmaci.pecia.storage.sqlite.SqliteFtsTestSupport.execute;
import static dev.eyuppastirmaci.pecia.storage.sqlite.SqliteFtsTestSupport.rows;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.eyuppastirmaci.pecia.content.Chunk;
import dev.eyuppastirmaci.pecia.content.ChunkMetadata;
import dev.eyuppastirmaci.pecia.content.ContentHash;
import dev.eyuppastirmaci.pecia.content.DocumentType;
import dev.eyuppastirmaci.pecia.content.LineRange;
import dev.eyuppastirmaci.pecia.content.SourceLocation;
import dev.eyuppastirmaci.pecia.storage.sqlite.SqliteFtsTestSupport.FtsRow;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SqliteChunkRepositoryTest {
    @TempDir
    Path root;

    private static final Path SOURCE = Path.of("docs", "İstanbul.md");
    private static final ChunkMetadata META = new ChunkMetadata(
            List.of("Başlık", "Başlık", "Alt 😀"),
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
            assertConsistent(storage.connection());
        }

        try (var storage = SqliteStorage.open(database, root)) {
            var chunks = storage.chunks().findByFileId(fileId);
            assertEquals(
                    List.of(chunk(0, ChunkMetadata.empty()), chunk(1, META), chunk(2, META)),
                    chunks.stream().map(stored -> stored.chunk()).toList());
            assertTrue(chunks.stream().allMatch(stored -> stored.id() > 0 && stored.fileId() == fileId));
            assertThrows(UnsupportedOperationException.class, chunks::clear);
            assertMatches(
                    storage.connection(),
                    "content: son",
                    chunks.stream().mapToLong(value -> value.id()).sorted().toArray());
            assertMatches(
                    storage.connection(),
                    "headings: başlık",
                    chunks.stream()
                            .filter(value ->
                                    !value.chunk().metadata().headingPath().isEmpty())
                            .mapToLong(value -> value.id())
                            .sorted()
                            .toArray());
            assertMatches(
                    storage.connection(),
                    "source_path: docs",
                    chunks.stream().mapToLong(value -> value.id()).sorted().toArray());
            assertConsistent(storage.connection());
            assertEquals(3, storage.chunks().deleteByFileId(fileId));
            assertTrue(storage.chunks().findByFileId(fileId).isEmpty());
            assertTrue(storage.files().findByPath(SOURCE).isPresent());

            try (var statement = storage.connection().createStatement();
                    var rows = statement.executeQuery(
                            "SELECT (SELECT count(*) FROM chunk_headings) + (SELECT count(*) FROM"
                                    + " chunk_attributes)")) {
                assertTrue(rows.next());
                assertEquals(0, rows.getInt(1));
            }
            assertMatches(storage.connection(), "son OR başlık OR docs");
            assertConsistent(storage.connection());
        }
        try (var storage = SqliteStorage.open(database, root)) {
            assertTrue(storage.chunks().findByFileId(fileId).isEmpty());
            assertMatches(storage.connection(), "son OR başlık OR docs");
            assertConsistent(storage.connection());
        }
    }

    @Test
    void rejectsMismatchedIdentityMissingFilesAndDuplicatePositions() throws Exception {
        try (var storage = SqliteStorage.open(root.resolve("index.db"), root)) {
            long id = file(storage);
            var original = storage.chunks().insert(id, chunk(0, META));
            assertThrows(SQLException.class, () -> storage.chunks().insert(id, chunk(0, META)));
            assertThrows(SQLException.class, () -> storage.chunks().insert(id + 1, chunk(1, META)));
            assertThrows(
                    SQLException.class,
                    () -> storage.chunks()
                            .insert(
                                    id,
                                    new Chunk(
                                            Path.of("other.md"),
                                            DocumentType.MARKDOWN,
                                            1,
                                            "text",
                                            new LineRange(1, 1),
                                            META)));
            assertThrows(
                    SQLException.class,
                    () -> storage.chunks()
                            .insert(
                                    id,
                                    new Chunk(SOURCE, DocumentType.PLAIN_TEXT, 1, "text", new LineRange(1, 1), META)));
            assertEquals(List.of(original), storage.chunks().findByFileId(id));
            assertMatches(storage.connection(), "content: son", original.id());
            assertMatches(storage.connection(), "headings: başlık", original.id());
            assertMatches(storage.connection(), "source_path: docs", original.id());
            assertConsistent(storage.connection());
        }
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "chunk_headings WHEN NEW.position = 1 AND NEW.chunk_id IN (SELECT id FROM chunks WHERE"
                        + " chunk_index = 1)",
                "chunk_attributes WHEN NEW.chunk_id IN (SELECT id FROM chunks WHERE chunk_index = 1)"
            })
    void metadataFailureRollsBackChunkAndHeadingsAndAllowsRetry(String failurePoint) throws Exception {
        Path database = root.resolve("index.db");
        Chunk candidate = new Chunk(
                SOURCE,
                DocumentType.MARKDOWN,
                1,
                "candidatebody",
                new LineRange(4, 5),
                new ChunkMetadata(
                        List.of("Candidateheading", "Candidatechild"), Map.of("custom", "candidateattribute")));
        long id;
        try (var storage = SqliteStorage.open(database, root)) {
            id = file(storage);
            var retained = storage.chunks().insert(id, chunk(0, META));
            var before = rows(storage.connection());

            try (var statement = storage.connection().createStatement()) {
                statement.executeUpdate("CREATE TRIGGER fail_metadata BEFORE INSERT ON "
                        + failurePoint
                        + " BEGIN SELECT RAISE(ABORT, 'injected'); END;");
                assertThrows(SQLException.class, () -> storage.chunks().insert(id, candidate));
                assertEquals(List.of(retained), storage.chunks().findByFileId(id));
                assertEquals(before, rows(storage.connection()));
                assertMatches(storage.connection(), "candidatebody OR candidateheading OR candidatechild");
                assertMatches(storage.connection(), "content: son", retained.id());
                assertMatches(storage.connection(), "headings: başlık", retained.id());
                assertMatches(storage.connection(), "source_path: docs", retained.id());
                assertConsistent(storage.connection());
                statement.executeUpdate("DROP TRIGGER fail_metadata");
            }
        }
        try (var storage = SqliteStorage.open(database, root)) {
            assertEquals(
                    List.of(chunk(0, META)),
                    storage.chunks().findByFileId(id).stream()
                            .map(value -> value.chunk())
                            .toList());
            assertMatches(storage.connection(), "candidatebody OR candidateheading OR candidatechild");
            assertConsistent(storage.connection());
            var saved = storage.chunks().insert(id, candidate);
            assertEquals(candidate, saved.chunk());
            assertMatches(storage.connection(), "content: candidatebody", saved.id());
            assertMatches(storage.connection(), "headings: \"candidateheading candidatechild\"", saved.id());
            assertMatches(storage.connection(), "candidateattribute");
            assertConsistent(storage.connection());
        }
        try (var storage = SqliteStorage.open(database, root)) {
            var saved = storage.chunks().findByFileId(id);
            assertEquals(
                    List.of(chunk(0, META), candidate),
                    saved.stream().map(value -> value.chunk()).toList());
            assertMatches(storage.connection(), "content: son", saved.getFirst().id());
            assertMatches(
                    storage.connection(),
                    "content: candidatebody",
                    saved.getLast().id());
            assertMatches(
                    storage.connection(),
                    "headings: \"candidateheading candidatechild\"",
                    saved.getLast().id());
            assertMatches(
                    storage.connection(),
                    "source_path: docs",
                    saved.getFirst().id(),
                    saved.getLast().id());
            assertConsistent(storage.connection());
        }
    }

    @Test
    void savepointsPreserveTheOuterTransactionAndEarlierWrites() throws Exception {
        try (var storage = SqliteStorage.open(root.resolve("index.db"), root)) {
            long id = file(storage);
            storage.connection().setAutoCommit(false);
            var saved = storage.chunks().insert(id, chunk(0, META));
            assertThrows(SQLException.class, () -> storage.chunks().insert(id, chunk(0, META)));
            assertEquals(1, storage.chunks().findByFileId(id).size());
            assertMatches(storage.connection(), "content: son", saved.id());
            assertMatches(storage.connection(), "headings: başlık", saved.id());
            assertMatches(storage.connection(), "source_path: docs", saved.id());
            assertConsistent(storage.connection());
            storage.connection().rollback();
            assertTrue(storage.chunks().findByFileId(id).isEmpty());
            assertMatches(storage.connection(), "son OR başlık OR docs");
            assertConsistent(storage.connection());
            storage.connection().setAutoCommit(true);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void deletingChunksSharesTheOuterTransactionAndLeavesOtherFilesSearchable(boolean commit) throws Exception {
        Path database = root.resolve("index.db");
        long id;
        long retainedId;
        try (var storage = SqliteStorage.open(database, root)) {
            id = file(storage);
            var saved = storage.chunks().insert(id, chunk(0, META));
            var other = storage.files()
                    .insert(Path.of("retained.md"), DocumentType.MARKDOWN, ContentHash.sha256(new byte[0]));
            retainedId = storage.chunks()
                    .insert(
                            other.id(),
                            new Chunk(
                                    other.sourcePath(),
                                    other.documentType(),
                                    0,
                                    "retainedbody",
                                    new LineRange(1, 1),
                                    new ChunkMetadata(List.of("Retainedheading"), Map.of())))
                    .id();
            var before = rows(storage.connection());
            storage.connection().setAutoCommit(false);
            assertEquals(1, storage.chunks().deleteByFileId(id));
            assertMatches(storage.connection(), "son OR başlık OR docs");
            assertMatches(storage.connection(), "content: retainedbody", retainedId);
            assertMatches(storage.connection(), "headings: retainedheading", retainedId);
            assertConsistent(storage.connection());
            if (commit) {
                storage.connection().commit();
            } else {
                storage.connection().rollback();
                assertEquals(List.of(saved), storage.chunks().findByFileId(id));
                assertEquals(before, rows(storage.connection()));
            }
            storage.connection().setAutoCommit(true);
        }
        try (var storage = SqliteStorage.open(database, root)) {
            var chunks = storage.chunks().findByFileId(id);
            assertEquals(
                    commit ? List.of() : List.of(chunk(0, META)),
                    chunks.stream().map(value -> value.chunk()).toList());
            long[] ids = chunks.stream().mapToLong(value -> value.id()).toArray();
            assertMatches(storage.connection(), "content: son", ids);
            assertMatches(storage.connection(), "headings: başlık", ids);
            assertMatches(storage.connection(), "source_path: docs", ids);
            assertMatches(storage.connection(), "content: retainedbody", retainedId);
            assertMatches(storage.connection(), "headings: retainedheading", retainedId);
            assertMatches(storage.connection(), "source_path: retained", retainedId);
            assertConsistent(storage.connection());
        }
    }

    @Test
    void unindexedMetadataChangesPreserveSearchResultsAcrossReopening() throws Exception {
        Path database = root.resolve("index.db");
        long fileId;
        long chunkId;
        List<FtsRow> before;
        try (var storage = SqliteStorage.open(database, root)) {
            fileId = file(storage);
            chunkId = storage.chunks().insert(fileId, chunk(0, META)).id();
            before = rows(storage.connection());
            execute(storage.connection(), "UPDATE chunks SET start_line = 10, end_line = 12 WHERE id = ?", chunkId);
            execute(
                    storage.connection(),
                    "UPDATE chunk_attributes SET value = 'attributeonlytoken' WHERE chunk_id = ? AND name ="
                            + " 'custom'",
                    chunkId);
            execute(
                    storage.connection(),
                    "UPDATE chunk_attributes SET value = '30' WHERE chunk_id = ? AND name = 'startOffset'",
                    chunkId);
            execute(
                    storage.connection(),
                    "UPDATE chunk_attributes SET value = '40' WHERE chunk_id = ? AND name = 'endOffset'",
                    chunkId);
            assertEquals(before, rows(storage.connection()));
            assertMatches(storage.connection(), "attributeonlytoken");
            assertConsistent(storage.connection());
        }
        try (var storage = SqliteStorage.open(database, root)) {
            var chunk = storage.chunks().findByFileId(fileId).getFirst().chunk();
            assertEquals(new LineRange(10, 12), chunk.sourceLocation());
            assertEquals("30", chunk.metadata().attributes().get("startOffset"));
            assertEquals("40", chunk.metadata().attributes().get("endOffset"));
            assertEquals("attributeonlytoken", chunk.metadata().attributes().get("custom"));
            assertEquals(before, rows(storage.connection()));
            assertMatches(storage.connection(), "attributeonlytoken");
            assertMatches(storage.connection(), "content: son", chunkId);
            assertMatches(storage.connection(), "headings: başlık", chunkId);
            assertMatches(storage.connection(), "source_path: docs", chunkId);
            assertConsistent(storage.connection());
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
            long other = storage.files()
                    .insert(Path.of("other.md"), DocumentType.MARKDOWN, ContentHash.sha256(new byte[0]))
                    .id();
            storage.chunks().insert(id, chunk(0, META));
            assertTrue(storage.chunks().findByFileId(other).isEmpty());
            assertTrue(storage.chunks().findByFileId(999).isEmpty());
            assertEquals(0, storage.chunks().deleteByFileId(other));
            assertEquals(1, storage.chunks().findByFileId(id).size());
            assertThrows(IllegalArgumentException.class, () -> storage.chunks().findByFileId(0));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> storage.chunks()
                            .insert(
                                    id,
                                    new Chunk(
                                            SOURCE, DocumentType.MARKDOWN, 1, "text", new SourceLocation() {}, META)));
        }
    }

    private static long file(SqliteStorage storage) throws SQLException {
        return storage.files()
                .insert(SOURCE, DocumentType.MARKDOWN, ContentHash.sha256(new byte[0]))
                .id();
    }

    private static Chunk chunk(int index, ChunkMetadata metadata) {
        return new Chunk(SOURCE, DocumentType.MARKDOWN, index, "İ😀 é\r\nson", new LineRange(2, 3), metadata);
    }
}
