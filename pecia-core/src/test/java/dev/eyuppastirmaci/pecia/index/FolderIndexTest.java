package dev.eyuppastirmaci.pecia.index;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.eyuppastirmaci.pecia.config.PeciaConfigLoader;
import dev.eyuppastirmaci.pecia.config.PeciaConfigParser;
import dev.eyuppastirmaci.pecia.search.SearchRequest;
import dev.eyuppastirmaci.pecia.storage.sqlite.SqliteStorage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class FolderIndexTest {

    @TempDir
    Path root;

    private final PeciaConfigLoader loader = new PeciaConfigLoader(new PeciaConfigParser());
    private final IndexService service = new IndexService(loader);

    @Test
    void indexesFilteredChildWithCorrectCountsAndRetainsUnscannedRecords() throws Exception {
        Files.writeString(root.resolve(".pecia.toml"), "[index]\ninclude = ['**/*.txt', '**/*.md']\n");
        Files.writeString(root.resolve("outside.txt"), "outsideneedle");
        Path child = Files.createDirectory(root.resolve("child"));
        Files.writeString(child.resolve("a.md"), "# Guide\n\nmarkdownneedle");
        Files.writeString(child.resolve("b.txt"), "textneedle");
        Files.writeString(child.resolve("empty.txt"), "");
        Files.writeString(child.resolve("excluded.bin"), "excluded");
        IndexResult first = service.index(root);

        assertEquals(4, first.indexedFiles());

        Files.delete(root.resolve("outside.txt"));
        IndexResult result = service.index(child);

        assertEquals(IndexResult.Status.COMPLETE, result.status());
        assertEquals(3, result.candidateCount());
        assertEquals(0, result.indexedFiles());
        assertEquals(3, result.unchangedFiles());
        assertEquals(0, result.writtenChunks());
        assertEquals(0, result.rejectedFiles());
        assertEquals(0, result.failedFiles());
        assertEquals(result, service.index(child));

        try (SqliteStorage storage = SqliteStorage.open(result.context().databasePath(), root)) {
            assertEquals(4, storage.files().findAll().size());
            assertEquals(
                    Path.of("child/a.md"),
                    storage.lexicalSearch()
                            .search(new SearchRequest("markdownneedle"))
                            .getFirst()
                            .sourcePath());
            assertEquals(
                    1,
                    storage.lexicalSearch()
                            .search(new SearchRequest("outsideneedle"))
                            .size());
        }
    }

    @Test
    void mixedRunCountsNewChangedAndUnchangedFilesThenSkipsAllOnRepeat() throws Exception {
        Files.writeString(root.resolve("changed.txt"), "oldneedle");
        Files.writeString(root.resolve("same.txt"), "sameneedle");
        Files.writeString(root.resolve("empty.txt"), "");
        service.index(root);
        Files.writeString(root.resolve("changed.txt"), "updatedneedle");
        Files.writeString(root.resolve("new.txt"), "newneedle");

        IndexResult mixed = service.index(root);

        assertEquals(IndexResult.Status.COMPLETE, mixed.status());
        assertEquals(4, mixed.candidateCount());
        assertEquals(2, mixed.indexedFiles());
        assertEquals(2, mixed.unchangedFiles());
        assertEquals(0, mixed.deletedFiles());
        assertEquals(2, mixed.writtenChunks());
        assertTrue(mixed.issues().isEmpty());

        IndexResult repeated = service.index(root);

        assertEquals(IndexResult.Status.COMPLETE, repeated.status());
        assertEquals(4, repeated.candidateCount());
        assertEquals(0, repeated.indexedFiles());
        assertEquals(4, repeated.unchangedFiles());
        assertEquals(0, repeated.deletedFiles());
        assertEquals(0, repeated.writtenChunks());
        assertTrue(repeated.issues().isEmpty());
    }

    @Test
    void goodBadGoodPreservesRejectedDataAndContinuesThenRetries() throws Exception {
        Files.writeString(root.resolve("a.txt"), "alpha");
        Files.writeString(root.resolve("b.txt"), "oldneedle");
        Files.writeString(root.resolve("c.txt"), "charlie");
        IndexResult initial = service.index(root);
        Files.writeString(root.resolve("b.txt"), "binary\0");
        Files.writeString(root.resolve("c.txt"), "updatedneedle");
        IndexResult partial = service.index(root);

        assertEquals(IndexResult.Status.PARTIAL, partial.status());
        assertEquals(1, partial.indexedFiles());
        assertEquals(1, partial.unchangedFiles());
        assertEquals(1, partial.rejectedFiles());
        assertEquals(0, partial.failedFiles());
        assertEquals(Path.of("b.txt"), partial.issues().getFirst().sourcePath());
        assertThrows(UnsupportedOperationException.class, () -> partial.issues().clear());

        try (SqliteStorage storage = SqliteStorage.open(initial.context().databasePath(), root)) {
            assertEquals(
                    1,
                    storage.lexicalSearch()
                            .search(new SearchRequest("oldneedle"))
                            .size());
            assertEquals(
                    1,
                    storage.lexicalSearch()
                            .search(new SearchRequest("updatedneedle"))
                            .size());
        }

        Files.writeString(root.resolve("b.txt"), "fixedneedle");

        assertEquals(IndexResult.Status.COMPLETE, service.index(root).status());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void readFailureAfterDiscoveryIsCountedSeparatelyAndConnectionCloses(boolean previouslyIndexed) throws Exception {
        Files.writeString(root.resolve("a.txt"), "alpha");
        Files.writeString(root.resolve("b.txt"), "bravo");
        Files.writeString(root.resolve("c.txt"), "charlie");

        if (previouslyIndexed) {
            service.index(root);
        }

        AtomicReference<SqliteStorage> captured = new AtomicReference<SqliteStorage>();
        IndexService deleting = new IndexService(loader, context -> {
            Files.delete(root.resolve("b.txt"));
            SqliteStorage storage = SqliteStorage.open(context.databasePath(), context.projectRoot());
            captured.set(storage);

            return storage;
        });
        IndexResult result = deleting.index(root);

        assertEquals(IndexResult.Status.PARTIAL, result.status());
        assertEquals(2, result.indexedFiles() + result.unchangedFiles());
        assertEquals(previouslyIndexed ? 2 : 0, result.unchangedFiles());
        assertEquals(previouslyIndexed ? 0 : 2, result.writtenChunks());
        assertEquals(0, result.deletedFiles());
        assertEquals(1, result.failedFiles());
        assertEquals(0, result.rejectedFiles());
        assertThrows(SQLException.class, () -> captured.get().files().findAll());

        if (previouslyIndexed) {
            try (SqliteStorage storage =
                    SqliteStorage.openReadOnly(result.context().databasePath(), root)) {
                assertEquals(3, storage.files().findAll().size());
                assertEquals(
                        1,
                        storage.lexicalSearch()
                                .search(new SearchRequest("bravo"))
                                .size());
            }
        }
    }

    @Test
    void incompleteScanDoesNotCreateOrChangeDatabase() throws Exception {
        Files.writeString(root.resolve("good.txt"), "good");
        Files.createDirectories(root.resolve("bad/.gitignore"));
        IndexException failure = assertThrows(IndexException.class, () -> service.index(root));

        assertEquals(IndexResult.Status.INCOMPLETE_SCAN, failure.result().status());
        assertEquals(0, failure.result().indexedFiles());
        assertEquals(0, failure.result().unchangedFiles());
        assertEquals(0, failure.result().deletedFiles());
        assertEquals(0, failure.result().writtenChunks());
        assertEquals(1, failure.scanIssues().size());
        assertThrows(
                UnsupportedOperationException.class, () -> failure.scanIssues().clear());
        assertFalse(Files.exists(root.resolve(".pecia")));

        Files.delete(root.resolve("bad/.gitignore"));
        IndexResult initial = service.index(root);
        byte[] before = Files.readAllBytes(initial.context().databasePath());
        Files.createDirectory(root.resolve("bad/.gitignore"));

        IndexException repeated = assertThrows(IndexException.class, () -> service.index(root));
        assertEquals(IndexResult.Status.INCOMPLETE_SCAN, repeated.result().status());
        assertEquals(0, repeated.result().indexedFiles());
        assertEquals(0, repeated.result().unchangedFiles());
        assertEquals(0, repeated.result().deletedFiles());
        assertArrayEquals(before, Files.readAllBytes(initial.context().databasePath()));
    }

    @Test
    void emptyDirectoryProducesQueryableIndexButInvalidBudgetDoesNotOpenStorage() throws Exception {
        IndexResult result = service.index(root);

        assertEquals(IndexResult.Status.COMPLETE, result.status());
        assertEquals(0, result.candidateCount());

        try (SqliteStorage storage = SqliteStorage.open(result.context().databasePath(), root)) {
            assertTrue(
                    storage.lexicalSearch().search(new SearchRequest("absent")).isEmpty());
        }

        Path other = Files.createDirectory(root.resolve("other"));
        Files.writeString(other.resolve(".pecia.toml"), "[chunk]\nmax_tokens = 512\n");

        assertThrows(IllegalArgumentException.class, () -> service.index(other));
        assertFalse(Files.exists(other.resolve(".pecia")));
        assertThrows(IOException.class, () -> service.index(root.resolve("missing")));
        assertFalse(Files.exists(root.resolve("missing")));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void storageFailureStopsAfterAtomicRollbackAndPreservesEarlierFiles(boolean withUnchangedFile) throws Exception {
        for (String name : List.of("a", "b", "c")) {
            Files.writeString(root.resolve(name + ".txt"), name + "old");
        }

        if (withUnchangedFile) {
            Files.writeString(root.resolve("aa.txt"), "unchangedneedle");
        }

        IndexResult initial = service.index(root);
        AtomicReference<SqliteStorage> captured = new AtomicReference<SqliteStorage>();
        IndexService failing = new IndexService(loader, context -> {
            SqliteStorage storage = SqliteStorage.open(context.databasePath(), context.projectRoot());
            captured.set(storage);

            try (Connection connection = DriverManager.getConnection(
                            "jdbc:sqlite:" + context.databasePath().toUri());
                    Statement statement = connection.createStatement()) {
                statement.execute("""
                    CREATE TRIGGER fail_b BEFORE INSERT ON chunks
                    WHEN NEW.file_id = (SELECT id FROM files WHERE source_path = 'b.txt')
                    BEGIN SELECT RAISE(ABORT, 'injected replacement failure'); END
                    """);
            }

            return storage;
        });

        for (String name : List.of("a", "b", "c")) {
            Files.writeString(root.resolve(name + ".txt"), name + "new");
        }

        IndexException failure = assertThrows(IndexException.class, () -> failing.index(root));

        assertEquals(IndexResult.Status.FAILED, failure.result().status());
        assertEquals(withUnchangedFile ? 4 : 3, failure.result().candidateCount());
        assertEquals(1, failure.result().indexedFiles());
        assertEquals(withUnchangedFile ? 1 : 0, failure.result().unchangedFiles());
        assertEquals(0, failure.result().deletedFiles());
        assertEquals(1, failure.result().writtenChunks());
        assertInstanceOf(SQLException.class, failure.getCause());
        assertThrows(SQLException.class, () -> captured.get().files().findAll());

        try (Connection connection = DriverManager.getConnection(
                        "jdbc:sqlite:" + initial.context().databasePath().toUri());
                Statement statement = connection.createStatement()) {
            statement.execute("DROP TRIGGER fail_b");
        }

        try (SqliteStorage storage = SqliteStorage.open(initial.context().databasePath(), root)) {
            for (String query : List.of("anew", "bold", "cold")) {
                assertEquals(
                        1,
                        storage.lexicalSearch().search(new SearchRequest(query)).size());
            }

            assertTrue(storage.lexicalSearch().search(new SearchRequest("bnew")).isEmpty());
        }

        IndexResult recovered = service.index(root);
        assertEquals(IndexResult.Status.COMPLETE, recovered.status());
        assertEquals(2, recovered.indexedFiles());
        assertEquals(withUnchangedFile ? 2 : 1, recovered.unchangedFiles());
        assertEquals(2, recovered.writtenChunks());
    }

    @Test
    void openingFailureRetainsCauseAndZeroProgressWhilePreviewNeverOpensStorage() throws Exception {
        IOException cause = new IOException("unavailable storage");
        IndexService failing = new IndexService(loader, context -> {
            throw cause;
        });
        Files.writeString(root.resolve("a.txt"), "alpha");

        assertEquals(1, failing.preview(root).walkResult().files().size());
        assertFalse(Files.exists(root.resolve(".pecia")));

        IndexException failure = assertThrows(IndexException.class, () -> failing.index(root));

        assertSame(cause, failure.getCause());
        assertEquals(0, failure.result().indexedFiles());
        assertEquals(IndexResult.Status.FAILED, failure.result().status());
    }
}
