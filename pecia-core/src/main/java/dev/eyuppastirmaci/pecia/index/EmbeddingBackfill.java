package dev.eyuppastirmaci.pecia.index;

import dev.eyuppastirmaci.pecia.semantic.EmbeddingException;
import dev.eyuppastirmaci.pecia.semantic.EmbeddingInput;
import dev.eyuppastirmaci.pecia.semantic.EmbeddingProfile;
import dev.eyuppastirmaci.pecia.storage.model.UnembeddedChunk;
import dev.eyuppastirmaci.pecia.storage.sqlite.SqliteStorage;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Embeds the stored chunks of unchanged files that have no embedding, in pages that each commit on their own, so an
 * interrupted run leaves only the remaining chunks for the next one. Pages are embedded by the run's workers and
 * committed in order; a page that cannot be embedded is skipped, its chunks stay without embeddings, and its files
 * are reported.
 */
final class EmbeddingBackfill {

    // Each page is one storage transaction; the workers embed it in several batches.
    static final int PAGE_SIZE = 32;

    private final SqliteStorage storage;
    private final EmbeddingPipeline pipeline;
    private final EmbeddingProfile profile;
    private final IndexProgressListener listener;
    private final EmbeddingFailures failures;
    private final Consumer<IndexResult.SemanticIssue> issues;
    private long stored;
    private long processed;

    EmbeddingBackfill(
            SqliteStorage storage,
            EmbeddingPipeline pipeline,
            EmbeddingProfile profile,
            IndexProgressListener listener,
            EmbeddingFailures failures,
            Consumer<IndexResult.SemanticIssue> issues) {
        this.storage = Objects.requireNonNull(storage, "storage");
        this.pipeline = Objects.requireNonNull(pipeline, "pipeline");
        this.profile = Objects.requireNonNull(profile, "profile");
        this.listener = listener;
        this.failures = Objects.requireNonNull(failures, "failures");
        this.issues = Objects.requireNonNull(issues, "issues");
    }

    /**
     * Embeds the files' chunks that have no embedding, in file order.
     *
     * @param fileIds files whose stored chunks are known to match their current content and chunking
     * @throws EmbeddingException if embedding failures stop the run; earlier pages stay committed and are counted by
     *     {@link #stored()}
     * @throws Cancellation.CancelledException if the run is cancelled; pages embedded before it stay committed
     */
    void run(List<Long> fileIds) throws SQLException, EmbeddingException, Cancellation.CancelledException {
        // Counting first keeps only the pipeline's bound of content in memory while still reporting the total up front.
        List<Long> pending = new ArrayList<>();
        long total = 0;

        for (long fileId : fileIds) {
            int missing = storage.findUnembeddedChunks(fileId).size();

            if (missing > 0) {
                pending.add(fileId);
                total += missing;
            }
        }

        if (total == 0) {
            return;
        }

        report(total);
        List<UnembeddedChunk> page = new ArrayList<>(PAGE_SIZE);

        for (long fileId : pending) {
            for (UnembeddedChunk chunk : storage.findUnembeddedChunks(fileId)) {
                page.add(chunk);

                if (page.size() == PAGE_SIZE) {
                    submit(page, total);
                    page = new ArrayList<>(PAGE_SIZE);
                }
            }
        }

        if (!page.isEmpty()) {
            submit(page, total);
        }

        pipeline.finish();
    }

    /** Returns how many embeddings committed pages stored; chunks changed, deleted, or embedded meanwhile are skipped. */
    long stored() {
        return stored;
    }

    private void submit(List<UnembeddedChunk> page, long total)
            throws SQLException, EmbeddingException, Cancellation.CancelledException {
        List<String> inputs = page.stream()
                .map(chunk -> EmbeddingInput.of(chunk.sourcePath(), chunk.headingPath(), chunk.content()))
                .toList();

        pipeline.submit(
                inputs,
                vectors -> {
                    stored += storage.storeEmbeddings(profile, page, vectors);
                    processed += page.size();
                    report(total);
                    failures.succeeded();
                },
                failure -> {
                    // Skipped chunks are still processed, so progress reaches its total.
                    processed += page.size();
                    report(total);
                    page.stream()
                            .map(UnembeddedChunk::sourcePath)
                            .distinct()
                            .forEach(path -> issues.accept(
                                    new IndexResult.SemanticIssue(path, failure.reason(), failure.getMessage())));
                    failures.failed(failure);
                });
    }

    /** Reports progress, capped at the total because chunks added by another process after counting are embedded too. */
    private void report(long total) {
        if (listener != null) {
            listener.progress(new IndexProgress(IndexProgress.Stage.BACKFILL, Math.min(processed, total), total));
        }
    }
}
