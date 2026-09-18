package dev.eyuppastirmaci.pecia.search;

import dev.eyuppastirmaci.pecia.content.ChunkMetadata;
import dev.eyuppastirmaci.pecia.content.ContentPath;
import dev.eyuppastirmaci.pecia.content.DocumentType;
import dev.eyuppastirmaci.pecia.content.SourceLocation;
import java.nio.file.Path;
import java.util.Objects;

/**
 * A ranked chunk match with a project-relative path, zero-based chunk index, and bounded preview.
 */
public record SearchHit(
        long chunkId,
        int chunkIndex,
        Path sourcePath,
        DocumentType documentType,
        SourceLocation sourceLocation,
        ChunkMetadata metadata,
        SearchScore score,
        String snippet) {
    /**
     * Caps previews at 400 Unicode code points to balance useful context and concise terminal output.
     */
    public static final int MAX_SNIPPET_CODE_POINTS = 400;

    /**
     * Validates the match identity, required values, and preview length.
     *
     * @throws NullPointerException if a reference component is null
     * @throws IllegalArgumentException if the ID, position, path or snippet length is invalid
     */
    public SearchHit {
        if (chunkId <= 0) {
            throw new IllegalArgumentException("chunkId must be positive");
        }

        if (chunkIndex < 0) {
            throw new IllegalArgumentException("chunkIndex must not be negative");
        }

        ContentPath.requireProjectRelative(sourcePath);
        Objects.requireNonNull(documentType, "documentType");
        Objects.requireNonNull(sourceLocation, "sourceLocation");
        Objects.requireNonNull(metadata, "metadata");
        Objects.requireNonNull(score, "score");

        if (snippet.codePointCount(0, snippet.length()) > MAX_SNIPPET_CODE_POINTS) {
            throw new IllegalArgumentException(
                    "snippet must contain at most " + MAX_SNIPPET_CODE_POINTS + " code points");
        }
    }
}
