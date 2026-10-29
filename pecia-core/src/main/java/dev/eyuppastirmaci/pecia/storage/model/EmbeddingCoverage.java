package dev.eyuppastirmaci.pecia.storage.model;

/**
 * How many stored chunks have an embedding of one profile. Only those embeddings can be compared with a query embedded
 * under the same profile.
 *
 * @param embedded chunks with an embedding of the profile
 * @param stale chunks with an embedding of another profile, which a semantic run discards and makes again
 * @param missing chunks without any embedding
 */
public record EmbeddingCoverage(long embedded, long stale, long missing) {

    /** Rejects negative counts. */
    public EmbeddingCoverage {
        if (embedded < 0 || stale < 0 || missing < 0) {
            throw new IllegalArgumentException("Coverage counts must not be negative");
        }
    }

    /** Returns the number of stored chunks. */
    public long chunks() {
        return embedded + stale + missing;
    }
}
