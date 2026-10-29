package dev.eyuppastirmaci.pecia.storage.model;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * A stored chunk that has no embedding yet, with what an embedding is made from: its content and where it comes from.
 *
 * @param id the chunk's positive database ID
 * @param sourcePath the chunk's project-relative path
 * @param headingPath the headings above the chunk, outermost first
 * @param content the chunk's stored content
 */
public record UnembeddedChunk(long id, Path sourcePath, List<String> headingPath, String content) {

    /** Validates a positive ID and copies the headings. */
    public UnembeddedChunk {
        Objects.requireNonNull(sourcePath, "sourcePath");
        headingPath = List.copyOf(headingPath);
        Objects.requireNonNull(content, "content");

        if (id <= 0) {
            throw new IllegalArgumentException("id must be positive: " + id);
        }
    }
}
