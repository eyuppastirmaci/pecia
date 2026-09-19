package dev.eyuppastirmaci.pecia.content;

/**
 * Deterministic chunk identity as a lowercase SHA-256 digest, distinct from a database row ID.
 * It identifies a position under a chunking profile, not the freshness of its content.
 */
public record ChunkId(String value) {

    /** Rejects values that are not lowercase, 64-character hexadecimal digests. */
    public ChunkId {
        if (!value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("value must be a lowercase 64-character SHA-256 digest");
        }
    }
}
