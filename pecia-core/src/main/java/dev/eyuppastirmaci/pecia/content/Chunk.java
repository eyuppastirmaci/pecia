package dev.eyuppastirmaci.pecia.content;

import java.nio.file.Path;
import java.util.Objects;

/**
 * A self-contained piece of a document ready for indexing.
 *
 * @param index zero-based position within the source document
 */
public record Chunk(
        Path sourcePath,
        DocumentType documentType,
        int index,
        String content,
        SourceLocation sourceLocation,
        ChunkMetadata metadata) {

    /** Validates non-null components, a project-relative path, and non-blank content. */
    public Chunk {
        sourcePath = ContentPath.requireProjectRelative(sourcePath);
        Objects.requireNonNull(documentType, "documentType");
        Objects.requireNonNull(content, "content");
        Objects.requireNonNull(sourceLocation, "sourceLocation");
        Objects.requireNonNull(metadata, "metadata");

        if (index < 0) {
            throw new IllegalArgumentException("index must not be negative: " + index);
        }

        if (content.isBlank()) {
            throw new IllegalArgumentException("content must not be blank");
        }
    }
}
