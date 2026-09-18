package dev.eyuppastirmaci.pecia.storage.sqlite;

import static dev.eyuppastirmaci.pecia.storage.sqlite.SqliteFtsTestSupport.assertConsistent;
import static dev.eyuppastirmaci.pecia.storage.sqlite.SqliteFtsTestSupport.execute;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.eyuppastirmaci.pecia.content.Chunk;
import dev.eyuppastirmaci.pecia.content.ChunkMetadata;
import dev.eyuppastirmaci.pecia.content.ContentHash;
import dev.eyuppastirmaci.pecia.content.Document;
import dev.eyuppastirmaci.pecia.content.DocumentType;
import dev.eyuppastirmaci.pecia.content.LineRange;
import dev.eyuppastirmaci.pecia.index.IndexingProfile;
import dev.eyuppastirmaci.pecia.storage.model.StoredFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SqliteFileDeletionTest {

    @TempDir
    Path root;

    @Test
    void batchCascadesToAllDependentRowsAndCountsActualDeletions() throws Exception {
        try (var storage = SqliteStorage.open(root.resolve("index.db"), root)) {
            StoredFile first = save(storage, "a.md", "alpha");
            StoredFile second = save(storage, "b.md", "bravo");

            assertEquals(2, storage.deleteFiles(List.of(first, second, first)));
            assertEquals(0, storage.deleteFiles(List.of(first, second)));

            for (String table : List.of(
                    "files", "chunks", "chunk_headings", "chunk_attributes", "file_indexing_profiles", "chunks_fts")) {
                try (var statement = storage.connection().createStatement();
                        var result = statement.executeQuery("SELECT count(*) FROM " + table)) {
                    assertEquals(0, result.getInt(1), table);
                }
            }

            assertConsistent(storage.connection());
        }
    }

    @Test
    void failureOnSecondFileRollsBackEntireBatchIncludingProfilesAndFts() throws Exception {
        try (var storage = SqliteStorage.open(root.resolve("index.db"), root)) {
            StoredFile first = save(storage, "a.md", "alpha");
            StoredFile second = save(storage, "b.md", "bravo");
            var firstChunks = storage.chunks().findByFileId(first.id());
            var firstProfile = storage.files().findIndexingProfile(first.id());
            execute(storage.connection(), """
                CREATE TRIGGER fail_delete BEFORE DELETE ON files
                WHEN OLD.source_path = 'b.md'
                BEGIN SELECT RAISE(ABORT, 'injected cleanup failure'); END
                """);

            assertThrows(SQLException.class, () -> storage.deleteFiles(List.of(first, second)));
            assertEquals(List.of(first, second), storage.files().findAll());
            assertEquals(firstChunks, storage.chunks().findByFileId(first.id()));
            assertEquals(firstProfile, storage.files().findIndexingProfile(first.id()));
            assertConsistent(storage.connection());

            execute(storage.connection(), "DROP TRIGGER fail_delete");
            assertEquals(2, storage.deleteFiles(List.of(first, second)));
        }
    }

    @Test
    void staleManifestAndNullEntriesCannotCausePartialDeletion() throws Exception {
        try (var storage = SqliteStorage.open(root.resolve("index.db"), root)) {
            StoredFile first = save(storage, "a.md", "alpha");
            StoredFile second = save(storage, "b.md", "bravo");
            StoredFile replacement = save(storage, "b.md", "changed");

            assertThrows(SQLException.class, () -> storage.deleteFiles(List.of(first, second)));
            assertThrows(NullPointerException.class, () -> storage.deleteFiles(Arrays.asList(first, null)));
            assertEquals(List.of(first, replacement), storage.files().findAll());
            assertConsistent(storage.connection());
        }
    }

    @Test
    void callerCanRollbackSuccessfulBatch() throws Exception {
        try (var storage = SqliteStorage.open(root.resolve("index.db"), root)) {
            StoredFile file = save(storage, "a.md", "alpha");
            storage.connection().setAutoCommit(false);

            assertEquals(1, storage.deleteFiles(List.of(file)));
            storage.connection().rollback();
            assertEquals(List.of(file), storage.files().findAll());
            assertConsistent(storage.connection());
        }
    }

    private StoredFile save(SqliteStorage storage, String source, String text) throws SQLException {
        Path path = Path.of(source);
        Document document = new Document(
                path, DocumentType.MARKDOWN, text, ContentHash.sha256(text.getBytes(StandardCharsets.UTF_8)));
        Chunk chunk = new Chunk(
                path,
                document.type(),
                0,
                text,
                new LineRange(1, 1),
                new ChunkMetadata(List.of("Heading"), Map.of("kind", "test")));

        return storage.replaceFile(document, List.of(chunk), new IndexingProfile("test", 128, 8));
    }
}
