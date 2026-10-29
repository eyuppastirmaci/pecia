package dev.eyuppastirmaci.pecia.index;

import dev.eyuppastirmaci.pecia.semantic.EmbeddingException;
import dev.eyuppastirmaci.pecia.semantic.EmbeddingInput;
import dev.eyuppastirmaci.pecia.storage.model.UnembeddedChunk;
import dev.eyuppastirmaci.pecia.storage.sqlite.SqliteStorage;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Embeds the stored chunks of unchanged files that have no embedding, in pages that each commit on their own, so an
 * interrupted run leaves only the remaining chunks for the next one.
 */
final class EmbeddingBackfill {

    private final SqliteStorage storage;
    private final SemanticIndexing semantic;
    private final IndexProgressListener listener;
    private long stored;

    EmbeddingBackfill(SqliteStorage storage, SemanticIndexing semantic, IndexProgressListener listener) {
        this.storage = Objects.requireNonNull(storage, "storage");
        this.semantic = Objects.requireNonNull(semantic, "semantic");
        this.listener = listener;
    }

    /**
     * Embeds the files' chunks that have no embedding, in file order.
     *
     * @param fileIds files whose stored chunks are known to match their current content and chunking
     * @throws EmbeddingException if embedding fails; earlier pages stay committed and are counted by {@link #stored()}
     * @throws Cancellation.CancelledException if the run is cancelled before a page; earlier pages stay committed
     */
    void run(List<Long> fileIds) throws SQLException, EmbeddingException, Cancellation.CancelledException {
        // Counting first keeps only one page of content in memory while still reporting the total up front.
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

        report(0, total);
        List<UnembeddedChunk> page = new ArrayList<>(SemanticIndexing.BATCH_SIZE);
        long processed = 0;

        for (long fileId : pending) {
            for (UnembeddedChunk chunk : storage.findUnembeddedChunks(fileId)) {
                page.add(chunk);

                if (page.size() == SemanticIndexing.BATCH_SIZE) {
                    store(page);
                    processed = report(processed + page.size(), total);
                    page.clear();
                }
            }
        }

        if (!page.isEmpty()) {
            store(page);
            report(processed + page.size(), total);
        }
    }

    /** Returns how many embeddings committed pages stored; chunks changed, deleted, or embedded meanwhile are skipped. */
    long stored() {
        return stored;
    }

    private void store(List<UnembeddedChunk> page)
            throws SQLException, EmbeddingException, Cancellation.CancelledException {
        List<float[]> vectors = semantic.embedTexts(page.stream()
                .map(chunk -> EmbeddingInput.of(chunk.sourcePath(), chunk.headingPath(), chunk.content()))
                .toList());

        stored += storage.storeEmbeddings(semantic.profile(), page, vectors);
    }

    /** Reports progress, capped at the total because chunks added by another process after counting are embedded too. */
    private long report(long completed, long total) {
        long capped = Math.min(completed, total);

        if (listener != null) {
            listener.progress(new IndexProgress(IndexProgress.Stage.BACKFILL, capped, total));
        }

        return completed;
    }
}
