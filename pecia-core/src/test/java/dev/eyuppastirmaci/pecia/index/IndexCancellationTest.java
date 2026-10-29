package dev.eyuppastirmaci.pecia.index;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import dev.eyuppastirmaci.pecia.config.PeciaConfigLoader;
import dev.eyuppastirmaci.pecia.config.PeciaConfigParser;
import dev.eyuppastirmaci.pecia.project.ProjectContext;
import dev.eyuppastirmaci.pecia.semantic.EmbeddingException;
import dev.eyuppastirmaci.pecia.semantic.ModelOutcome;
import dev.eyuppastirmaci.pecia.storage.sqlite.SqliteStorage;
import dev.eyuppastirmaci.pecia.testing.IndexDump;
import dev.eyuppastirmaci.pecia.testing.LexicalTestCorpus;
import dev.eyuppastirmaci.pecia.testing.TinyModel;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A cancelled run stops between files, batches, and pages, keeps what it committed, and the next run finishes. */
class IndexCancellationTest {

    private static final ModelOutcome TINY = TinyModel.cached();

    @TempDir
    Path sandbox;

    private final PeciaConfigLoader loader = new PeciaConfigLoader(new PeciaConfigParser());
    private Cancellation cancellation = new Cancellation();
    private Predicate<List<String>> cancelDuring = texts -> false;
    private int embedCalls;
    private int closedEmbedders;

    @Test
    void aRunCancelledBeforeItStartsWritesNoFileAndTheNextRunCompletesIt() throws Exception {
        Path cancelled = corpus("cancelled");
        cancellation.cancel();

        IndexResult result = service().index(cancelled, options(false));

        assertEquals(IndexResult.Status.CANCELLED, result.status());
        assertEquals(7, result.candidateCount());
        assertEquals(0, result.indexedFiles() + result.unchangedFiles());
        assertEquals(0, result.writtenChunks());
        assertEquals(Set.of(), files(result));

        assertEquals(
                dump(service().index(corpus("uninterrupted"))), dump(service().index(cancelled)));
    }

    @Test
    void cancellingWhileAFileIsEmbeddedStopsBeforeItsNextBatchAndLeavesItUnwritten() throws Exception {
        Path cancelled = manyChunkProject("cancelled");
        cancelDuring = texts -> embedCalls == 1;

        IndexResult result = service().index(cancelled, options(true));

        assertEquals(IndexResult.Status.CANCELLED, result.status());
        assertEquals(1, embedCalls, "The second of nine batches never starts");
        assertEquals(1, closedEmbedders);
        assertEquals(0, result.indexedFiles());
        assertEquals(0, result.writtenChunks());
        assertEquals(0, result.embeddedChunks());
        assertNull(result.embeddingCoverage());
        assertEquals(Set.of(), files(result));

        assertResumes(cancelled, manyChunkProject("uninterrupted"));
    }

    @Test
    void cancellingBetweenFilesKeepsTheCommittedOnesAndSkipsTheRest() throws Exception {
        Path cancelled = threeFileProject("cancelled");
        cancelDuring = texts -> texts.getFirst().contains("b.txt");

        IndexResult result = service().index(cancelled, options(true));

        assertEquals(IndexResult.Status.CANCELLED, result.status());
        assertEquals(2, result.indexedFiles());
        assertEquals(2, result.writtenChunks());
        assertEquals(2, result.embeddedChunks());
        assertEquals(0, result.deletedFiles());
        assertEquals(1, closedEmbedders);
        assertEquals(Set.of("a.txt", "b.txt", "removed.txt"), files(result));
        assertEquals(2, dump(result).embeddings().size());

        assertResumes(cancelled, threeFileProject("uninterrupted"));
    }

    @Test
    void aRunCancelledDuringItsLastFileDeletesNothingBecauseItsScanMayBeStale() throws Exception {
        Path cancelled = threeFileProject("cancelled");
        cancelDuring = texts -> texts.getFirst().contains("c.txt");

        IndexResult result = service().index(cancelled, options(true));

        assertEquals(IndexResult.Status.CANCELLED, result.status());
        assertEquals(3, result.indexedFiles());
        assertEquals(0, result.deletedFiles());
        assertEquals(Set.of("a.txt", "b.txt", "c.txt", "removed.txt"), files(result));

        IndexResult resumed = resume(cancelled);

        assertEquals(IndexResult.Status.COMPLETE, resumed.status());
        assertEquals(1, resumed.deletedFiles());
        assertEquals(3, resumed.unchangedFiles());
        assertEquals(dump(service().index(threeFileProject("uninterrupted"), options(true))), dump(resumed));
    }

    @Test
    void cancellingABackfillKeepsItsCommittedPages() throws Exception {
        Path cancelled = manyChunkProject("cancelled");
        Path uninterrupted = manyChunkProject("uninterrupted");
        service().index(cancelled);
        service().index(uninterrupted);
        // The fourth batch of eight completes the first page of 32.
        cancelDuring = texts -> embedCalls == 4;

        IndexResult result = service().index(cancelled, options(true));

        assertEquals(IndexResult.Status.CANCELLED, result.status());
        assertEquals(1, result.unchangedFiles());
        assertEquals(32, result.backfilledChunks());
        assertEquals(4, embedCalls);
        assertEquals(1, closedEmbedders);
        assertNull(result.embeddingCoverage());
        assertEquals(32, dump(result).embeddings().size());

        IndexResult resumed = resume(cancelled);

        assertEquals(38, resumed.backfilledChunks());
        assertEquals(dump(service().index(uninterrupted, options(true))), dump(resumed));
    }

    /** Resumes a cancelled project and compares it with an uninterrupted twin. */
    private void assertResumes(Path cancelled, Path uninterrupted) throws Exception {
        IndexResult resumed = resume(cancelled);

        assertEquals(IndexResult.Status.COMPLETE, resumed.status());
        assertEquals(dump(service().index(uninterrupted, options(true))), dump(resumed));
    }

    /** Runs a cancelled project again with a fresh handle, since cancelling is permanent. */
    private IndexResult resume(Path cancelled) throws Exception {
        cancelDuring = texts -> false;
        cancellation = new Cancellation();

        return service().index(cancelled, options(true));
    }

    private IndexOptions options(boolean semantic) {
        return IndexOptions.semantic(semantic).withCancellation(cancellation);
    }

    private Path corpus(String name) throws IOException {
        Path project = Files.createDirectories(sandbox.resolve(name)).toRealPath();
        LexicalTestCorpus.write(project);
        return project;
    }

    /** Writes a project whose single file splits into 70 six-token chunks, embedded by one worker in nine batches. */
    private Path manyChunkProject(String name) throws IOException {
        Path project = Files.createDirectories(sandbox.resolve(name)).toRealPath();
        Files.writeString(
                project.resolve(".pecia.toml"),
                "[chunk]\nmax_tokens = 6\noverlap_tokens = 0\n\n[embed]\nconcurrency = 1\n");
        Files.writeString(project.resolve("words.txt"), "alpha beta gamma delta ".repeat(70) + "\n");
        return project;
    }

    /** Writes three one-chunk files, embedded by one worker, over a lexical index that holds a file deleted since. */
    private Path threeFileProject(String name) throws Exception {
        Path project = Files.createDirectories(sandbox.resolve(name)).toRealPath();
        // One worker embeds in order: with more, a later file's batch may start before the cancelling batch ends,
        // and finished work is written.
        Files.writeString(project.resolve(".pecia.toml"), "[embed]\nconcurrency = 1\n");
        Files.writeString(project.resolve("removed.txt"), "removed before the semantic run\n");
        service().index(project);
        Files.delete(project.resolve("removed.txt"));

        for (String file : List.of("a", "b", "c")) {
            Files.writeString(project.resolve(file + ".txt"), "File " + file + " about refunds.\n");
        }

        return project;
    }

    private IndexService service() {
        return new IndexService(loader, IndexCancellationTest::openStorage, listener -> TINY, this::open);
    }

    private static SqliteStorage openStorage(ProjectContext context) throws IOException, SQLException {
        return SqliteStorage.open(context.databasePath(), context.projectRoot());
    }

    /** Opens the tiny model's embedder, which cancels the run while it embeds a batch that {@code cancelDuring} picks. */
    private TextEmbedder open(ModelOutcome model, int threads) throws EmbeddingException {
        TextEmbedder embedder = TextEmbedder.open(model, threads);

        return new TextEmbedder() {
            @Override
            public float[][] embed(List<String> texts) throws EmbeddingException {
                // Workers call concurrently; the lock orders the calls and their bookkeeping.
                synchronized (this) {
                    embedCalls++;
                }

                if (cancelDuring.test(texts)) {
                    cancellation.cancel();
                }

                return embedder.embed(texts);
            }

            @Override
            public void close() {
                closedEmbedders++;
                embedder.close();
            }
        };
    }

    private static IndexDump dump(IndexResult result) throws Exception {
        return IndexDump.read(result.context().databasePath(), result.context().projectRoot(), List.of());
    }

    private static Set<String> files(IndexResult result) throws Exception {
        Set<String> files = new TreeSet<>();
        dump(result).files().forEach(file -> files.add(file.sourcePath()));
        return files;
    }
}
