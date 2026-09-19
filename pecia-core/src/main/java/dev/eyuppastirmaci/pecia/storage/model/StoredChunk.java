package dev.eyuppastirmaci.pecia.storage.model;

import dev.eyuppastirmaci.pecia.content.Chunk;
import dev.eyuppastirmaci.pecia.content.ChunkId;
import dev.eyuppastirmaci.pecia.content.LineRange;
import java.util.Objects;
import java.util.Optional;

/**
 * A chunk with positive database identities and a line-based source location.
 * The project-local stable ID is absent for legacy or invalidated processing state.
 */
public record StoredChunk(long id, long fileId, Chunk chunk, Optional<ChunkId> stableId) {
    /** Validates the database identities and supported source location. */
    public StoredChunk {
        Objects.requireNonNull(stableId, "stableId");

        if (id <= 0 || fileId <= 0) {
            throw new IllegalArgumentException("id and fileId must be positive");
        }

        if (!(chunk.sourceLocation() instanceof LineRange)) {
            throw new IllegalArgumentException("stored chunks must have a line range");
        }
    }

    /** Creates a stored chunk whose deterministic identity is unknown. */
    public StoredChunk(long id, long fileId, Chunk chunk) {
        this(id, fileId, chunk, Optional.empty());
    }
}
