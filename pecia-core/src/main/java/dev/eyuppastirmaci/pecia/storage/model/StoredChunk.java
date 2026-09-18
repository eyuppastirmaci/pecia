package dev.eyuppastirmaci.pecia.storage.model;

import dev.eyuppastirmaci.pecia.content.Chunk;
import dev.eyuppastirmaci.pecia.content.LineRange;

/** A chunk with positive database identities and a line-based source location. */
public record StoredChunk(long id, long fileId, Chunk chunk) {
    /** Validates the database identities and supported source location. */
    public StoredChunk {
        if (id <= 0 || fileId <= 0) {
            throw new IllegalArgumentException("id and fileId must be positive");
        }

        if (!(chunk.sourceLocation() instanceof LineRange)) {
            throw new IllegalArgumentException("stored chunks must have a line range");
        }
    }
}
