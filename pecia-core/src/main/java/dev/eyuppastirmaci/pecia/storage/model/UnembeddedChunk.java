package dev.eyuppastirmaci.pecia.storage.model;

import java.util.Objects;

/**
 * A stored chunk that has no embedding yet, with the content an embedding would be made from.
 *
 * @param id the chunk's positive database ID
 * @param content the chunk's stored content
 */
public record UnembeddedChunk(long id, String content) {

    /** Validates a positive ID and non-null content. */
    public UnembeddedChunk {
        Objects.requireNonNull(content, "content");

        if (id <= 0) {
            throw new IllegalArgumentException("id must be positive: " + id);
        }
    }
}
