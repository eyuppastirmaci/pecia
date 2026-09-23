package dev.eyuppastirmaci.pecia.index;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dev.eyuppastirmaci.pecia.config.PeciaConfigLoader;
import dev.eyuppastirmaci.pecia.config.PeciaConfigParser;
import dev.eyuppastirmaci.pecia.search.SearchHit;
import dev.eyuppastirmaci.pecia.search.SearchRequest;
import dev.eyuppastirmaci.pecia.storage.sqlite.SqliteStorage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class IndexCleanupTest {

    @TempDir
    Path root;

    private final PeciaConfigLoader loader = new PeciaConfigLoader(new PeciaConfigParser());
    private final IndexService service = new IndexService(loader);

    @Test
    void lifecycleAddsChangesSkipsDeletesAndReindexesRecreatedFile() throws Exception {
        Path file = Files.writeString(root.resolve("note.txt"), "originalneedle");
        assertEquals(1, service.index(root).indexedFiles());
        assertEquals(1, service.index(root).unchangedFiles());
        Files.writeString(file, "replacementneedle");
        assertEquals(1, service.index(root).indexedFiles());
        Files.delete(file);

        IndexResult deleted = service.index(root);

        assertEquals(IndexResult.Status.COMPLETE, deleted.status());
        assertEquals(0, deleted.candidateCount());
        assertEquals(0, deleted.indexedFiles());
        assertEquals(0, deleted.writtenChunks());
        assertEquals(1, deleted.deletedFiles());
        try (var storage = SqliteStorage.openReadOnly(deleted.context().databasePath(), root)) {
            assertTrue(storage.files().findAll().isEmpty());
            assertTrue(storage.lexicalSearch()
                    .search(new SearchRequest("replacementneedle"))
                    .isEmpty());
        }

        assertEquals(0, service.index(root).deletedFiles());
        Files.writeString(file, "recreatedneedle");
        assertEquals(1, service.index(root).indexedFiles());
    }

    @Test
    void caseOnlyFileAndDirectoryRenamesReplaceTheOldSpellingWithoutDuplicateHits() throws Exception {
        Files.createDirectory(root.resolve("Docs"));
        Files.writeString(root.resolve("Docs/Guide.txt"), "caseneedle");
        service.index(root);
        renameSpelling(root.resolve("Docs/Guide.txt"), "guide.txt");
        renameSpelling(root.resolve("Docs"), "docs");

        IndexResult renamed = service.index(root);

        assertEquals(IndexResult.Status.COMPLETE, renamed.status());
        assertEquals(1, renamed.indexedFiles());
        assertEquals(1, renamed.deletedFiles());
        try (var storage = SqliteStorage.openReadOnly(renamed.context().databasePath(), root)) {
            assertEquals(1, storage.files().findAll().size());
            assertEquals(
                    List.of(Path.of("docs/guide.txt")),
                    storage.lexicalSearch().search(new SearchRequest("caseneedle")).stream()
                            .map(SearchHit::sourcePath)
                            .toList());
        }

        IndexResult repeated = service.index(root);
        assertEquals(1, repeated.unchangedFiles());
        assertEquals(0, repeated.deletedFiles());
    }

    @Test
    void childTargetTypedWithAnotherCaseReusesTheStoredSpelling() throws Exception {
        Files.writeString(root.resolve(".pecia.toml"), "[index]\ninclude = ['**/*.txt']\n");
        Files.createDirectory(root.resolve("Docs"));
        Files.writeString(root.resolve("Docs/a.txt"), "targetneedle");
        service.index(root);
        Path alias = root.resolve("docs");
        assumeTrue(Files.isDirectory(alias), "Filesystem is case-sensitive");

        IndexResult child = service.index(alias);

        assertEquals(0, child.indexedFiles());
        assertEquals(1, child.unchangedFiles());
        try (var storage = SqliteStorage.openReadOnly(child.context().databasePath(), root)) {
            assertEquals(
                    List.of(Path.of("Docs/a.txt")),
                    storage.lexicalSearch().search(new SearchRequest("targetneedle")).stream()
                            .map(SearchHit::sourcePath)
                            .toList());
        }
    }

    @Test
    void childCleanupRetainsMissingOutsideAndExcludedRecords() throws Exception {
        Files.writeString(root.resolve(".pecia.toml"), "[index]\ninclude = ['**/*.txt']\n");
        Path child = Files.createDirectory(root.resolve("child"));
        Path removed = Files.writeString(child.resolve("removed.txt"), "removedneedle");
        Path ignored = Files.writeString(child.resolve("ignored.txt"), "ignoredneedle");
        Path outside = Files.writeString(root.resolve("outside.txt"), "outsideneedle");
        service.index(root);
        Files.delete(removed);
        Files.delete(ignored);
        Files.delete(outside);
        Files.writeString(child.resolve(".gitignore"), "ignored.txt\n");

        IndexResult result = service.index(child);

        assertEquals(1, result.deletedFiles());
        try (var storage = SqliteStorage.openReadOnly(result.context().databasePath(), root)) {
            assertEquals(2, storage.files().findAll().size());
            assertEquals(
                    1,
                    storage.lexicalSearch()
                            .search(new SearchRequest("ignoredneedle"))
                            .size());
            assertEquals(
                    1,
                    storage.lexicalSearch()
                            .search(new SearchRequest("outsideneedle"))
                            .size());
        }
    }

    @Test
    void partialRunDefersCleanupUntilSuccessfulRetry() throws Exception {
        Path missing = Files.writeString(root.resolve("missing.txt"), "retainedneedle");
        Path invalid = Files.writeString(root.resolve("invalid.txt"), "valid");
        service.index(root);
        Files.delete(missing);
        Files.writeString(invalid, "binary\0");

        IndexResult partial = service.index(root);

        assertEquals(IndexResult.Status.PARTIAL, partial.status());
        assertEquals(0, partial.deletedFiles());
        try (var storage = SqliteStorage.openReadOnly(partial.context().databasePath(), root)) {
            assertEquals(2, storage.files().findAll().size());
        }

        Files.writeString(invalid, "fixed");
        assertEquals(1, service.index(root).deletedFiles());
    }

    @Test
    void cleanupFailureReportsZeroDeletionsAndRetainsEarlierIndexingProgress() throws Exception {
        Path first = Files.writeString(root.resolve("a.txt"), "alpha");
        Path second = Files.writeString(root.resolve("b.txt"), "bravo");
        Path present = Files.writeString(root.resolve("c.txt"), "old");
        IndexResult initial = service.index(root);
        Files.delete(first);
        Files.delete(second);
        Files.writeString(present, "newneedle");

        try (var connection = DriverManager.getConnection(
                        "jdbc:sqlite:" + initial.context().databasePath().toUri());
                var statement = connection.createStatement()) {
            statement.execute("""
                CREATE TRIGGER fail_cleanup BEFORE DELETE ON files
                WHEN OLD.source_path = 'b.txt'
                BEGIN SELECT RAISE(ABORT, 'injected cleanup failure'); END
                """);
        }

        IndexException failure = assertThrows(IndexException.class, () -> service.index(root));
        assertEquals(IndexResult.Status.FAILED, failure.result().status());
        assertEquals(1, failure.result().indexedFiles());
        assertEquals(1, failure.result().writtenChunks());
        assertEquals(0, failure.result().deletedFiles());
        try (var storage = SqliteStorage.openReadOnly(initial.context().databasePath(), root)) {
            assertEquals(3, storage.files().findAll().size());
            assertEquals(
                    1,
                    storage.lexicalSearch()
                            .search(new SearchRequest("newneedle"))
                            .size());
        }

        try (var connection = DriverManager.getConnection(
                        "jdbc:sqlite:" + initial.context().databasePath().toUri());
                var statement = connection.createStatement()) {
            statement.execute("DROP TRIGGER fail_cleanup");
        }

        IndexResult retried = service.index(root);
        assertEquals(1, retried.unchangedFiles());
        assertEquals(2, retried.deletedFiles());
    }

    /** Renames through a temporary name because a case-only move is a no-op on case-insensitive filesystems. */
    private static void renameSpelling(Path path, String name) throws IOException {
        Path temporary = Files.move(path, path.resolveSibling(name + ".renaming"));
        Files.move(temporary, path.resolveSibling(name));
    }
}
