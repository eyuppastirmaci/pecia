package dev.eyuppastirmaci.pecia.search;

import java.util.List;

/**
 * Nearest-neighbor search over stored embeddings of one embedding profile, obtained from {@link
 * dev.eyuppastirmaci.pecia.storage.sqlite.SqliteStorage#semanticSearch}. Does not own or close storage, and adds no
 * concurrent-use guarantee.
 */
public abstract class SemanticSearch {

    /** Creates a search service that validates query embeddings before retrieval. */
    protected SemanticSearch() {}

    /**
     * Returns the chunks whose embeddings are most similar to the query embedding, best first. Scores are cosine
     * similarities of kind {@link SearchScore.Kind#COSINE_SIMILARITY}; ties use binary source path, chunk position,
     * then chunk ID. Snippets are the beginning of each chunk's content. Chunks without an embedding of the profile are
     * never returned.
     *
     * @param queryEmbedding the query embedded under the same profile, with only finite values
     * @param limit the maximum number of hits, between 1 and {@link SearchRequest#MAX_LIMIT}
     * @return up to {@code limit} hits, empty when no chunk has an embedding of the profile
     * @throws NullPointerException if queryEmbedding is null
     * @throws IllegalArgumentException if the embedding has non-finite values or the wrong length, or limit is out of
     *     bounds
     * @throws SearchException if storage is closed or reading fails, preserving the cause
     */
    public final List<SearchHit> search(float[] queryEmbedding, int limit) throws SearchException {
        requireFinite(queryEmbedding);

        if (limit < 1 || limit > SearchRequest.MAX_LIMIT) {
            throw new IllegalArgumentException("limit must be between 1 and " + SearchRequest.MAX_LIMIT);
        }

        return retrieve(queryEmbedding.clone(), limit);
    }

    static void requireFinite(float[] queryEmbedding) {
        for (float value : queryEmbedding) {
            if (!Float.isFinite(value)) {
                throw new IllegalArgumentException("queryEmbedding must hold only finite values");
            }
        }
    }

    /** Reads ranked hits for a validated query embedding. */
    protected abstract List<SearchHit> retrieve(float[] queryEmbedding, int limit) throws SearchException;
}
