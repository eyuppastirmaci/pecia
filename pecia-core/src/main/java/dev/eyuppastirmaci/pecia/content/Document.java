package dev.eyuppastirmaci.pecia.content;

import java.nio.file.Path;
import java.util.Objects;

/** Extracted text, its raw-byte hash, and its project-relative source identity. */
public record Document(Path sourcePath, DocumentType type, String content, ContentHash contentHash) {

    public Document {
        sourcePath = ContentPath.requireProjectRelative(sourcePath);
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(content, "content");
        Objects.requireNonNull(contentHash, "contentHash");
    }
}
