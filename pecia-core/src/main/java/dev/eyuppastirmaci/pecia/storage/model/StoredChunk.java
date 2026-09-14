package dev.eyuppastirmaci.pecia.storage.model;

import dev.eyuppastirmaci.pecia.content.Chunk;
import dev.eyuppastirmaci.pecia.content.LineRange;

public record StoredChunk(long id, long fileId, Chunk chunk) {
    public StoredChunk {
        if (id <= 0 || fileId <= 0) {
            throw new IllegalArgumentException("id and fileId must be positive");
        }

        if (!(chunk.sourceLocation() instanceof LineRange)) {
            throw new IllegalArgumentException("stored chunks must have a line range");
        }
    }
}
