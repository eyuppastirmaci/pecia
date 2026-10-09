package dev.eyuppastirmaci.pecia.testing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.eyuppastirmaci.pecia.config.PeciaConfigLoader;
import dev.eyuppastirmaci.pecia.config.PeciaConfigParser;
import dev.eyuppastirmaci.pecia.index.IndexResult;
import dev.eyuppastirmaci.pecia.index.IndexService;
import dev.eyuppastirmaci.pecia.search.SearchRequest;
import dev.eyuppastirmaci.pecia.storage.model.StoredChunk;
import dev.eyuppastirmaci.pecia.storage.sqlite.SqliteStorage;
import dev.eyuppastirmaci.pecia.testing.IndexWriteObserver.ChunkKey;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class IndexWriteObserverTest {

    private static final Path ALPHA = Path.of("alpha.txt");
    private static final Path BETA = Path.of("beta.txt");
    // Indexed last, so its chunks hold the highest row IDs and a replacement can reuse them.
    private static final Path OMEGA = Path.of("omega.txt");

    @TempDir
    Path root;

    private final IndexService indexService = new IndexService(new PeciaConfigLoader(new PeciaConfigParser()));
    private Path database;

    @BeforeEach
    void indexCorpus() throws Exception {
        root = root.toRealPath();
        Files.writeString(root.resolve(ALPHA), "alpha needle\n");
        Files.writeString(root.resolve(BETA), "beta needle\n");
        Files.writeString(root.resolve(OMEGA), "omega needle\n");
        database = index().context().databasePath();
    }

    @Test
    void auditObjectsLeaveIndexingAndQueriesWorkingAndAnUnchangedRunWritesNothing() throws Exception {
        IndexWriteObserver observer = IndexWriteObserver.start(database);
        IndexDump expected = IndexDump.read(database, root, List.of("needle"));

        IndexResult rerun = index();
        IndexWriteObserver.Writes writes = observer.finish();

        assertEquals(3, rerun.unchangedFiles());
        assertEquals(0, writes.writeCount());
        assertEquals(sorted(ALPHA, BETA, OMEGA), writes.retainedFiles());
        assertEquals(sorted(), writes.writtenFiles());
        assertEquals(sorted(), writes.removedFiles());
        assertEquals(chunks(ALPHA, BETA, OMEGA), writes.retainedChunks());
        // Reopening for writes and for reads must accept the extra test-owned schema objects.
        assertEquals(expected, IndexDump.read(database, root, List.of("needle")));
        try (SqliteStorage storage = SqliteStorage.open(database, root)) {
            assertEquals(
                    3,
                    storage.lexicalSearch().search(new SearchRequest("needle")).size());
        }
    }

    @Test
    void contentEditReportsOnlyTheEditedFileAndItsChunksAsWritten() throws Exception {
        IndexWriteObserver observer = IndexWriteObserver.start(database);
        Files.writeString(root.resolve(BETA), "beta replaced\n");

        index();
        IndexWriteObserver.Writes writes = observer.finish();

        assertTrue(writes.writeCount() > 0);
        assertEquals(sorted(BETA), writes.writtenFiles());
        assertEquals(sorted(ALPHA, OMEGA), writes.retainedFiles());
        assertEquals(sorted(), writes.removedFiles());
        assertEquals(chunks(BETA), writes.writtenChunks());
        assertEquals(chunks(ALPHA, OMEGA), writes.retainedChunks());
    }

    @Test
    void replacementThatReusesRowIdsIsStillReportedAsWritten() throws Exception {
        List<Long> before = chunkIds(OMEGA);
        IndexWriteObserver observer = IndexWriteObserver.start(database);
        Files.writeString(root.resolve(OMEGA), "omega replaced\n");

        index();
        IndexWriteObserver.Writes writes = observer.finish();

        // This is the blind spot of row-ID comparison: the replacement chunks received the deleted IDs.
        assertEquals(before, chunkIds(OMEGA));
        assertEquals(sorted(OMEGA), writes.writtenFiles());
        assertEquals(chunks(OMEGA), writes.writtenChunks());
        assertEquals(chunks(ALPHA, BETA), writes.retainedChunks());
    }

    @Test
    void deletionAndRenameReportRemovedOldPathsAndWrittenNewPaths() throws Exception {
        IndexWriteObserver observer = IndexWriteObserver.start(database);
        Files.delete(root.resolve(ALPHA));
        Files.move(root.resolve(BETA), root.resolve("gamma.txt"));

        index();
        IndexWriteObserver.Writes writes = observer.finish();

        assertEquals(sorted(Path.of("gamma.txt")), writes.writtenFiles());
        assertEquals(sorted(OMEGA), writes.retainedFiles());
        assertEquals(sorted(ALPHA, BETA), writes.removedFiles());
        assertEquals(chunks(Path.of("gamma.txt")), writes.writtenChunks());
        assertEquals(chunks(ALPHA, BETA), writes.removedChunks());
    }

    @Test
    void restartingClearsEarlierLogEntries() throws Exception {
        IndexWriteObserver.start(database);
        Files.writeString(root.resolve(ALPHA), "alpha replaced\n");
        index();

        IndexWriteObserver observer = IndexWriteObserver.start(database);
        index();

        assertEquals(0, observer.finish().writeCount());
    }

    private IndexResult index() throws Exception {
        IndexResult result = indexService.index(root);
        assertEquals(IndexResult.Status.COMPLETE, result.status());
        return result;
    }

    private List<Long> chunkIds(Path sourcePath) throws Exception {
        try (SqliteStorage storage = SqliteStorage.openReadOnly(database, root)) {
            long fileId = storage.files().findByPath(sourcePath).orElseThrow().id();
            return storage.chunks().findByFileId(fileId).stream()
                    .map(StoredChunk::id)
                    .toList();
        }
    }

    private static SortedSet<Path> sorted(Path... paths) {
        return new TreeSet<>(Set.of(paths));
    }

    /* Every corpus file is a single line, so each produces exactly one chunk at index zero. */
    private static SortedSet<ChunkKey> chunks(Path... paths) {
        SortedSet<ChunkKey> keys = new TreeSet<>();

        for (Path path : paths) {
            keys.add(new ChunkKey(path, 0));
        }

        return keys;
    }
}
