package dev.eyuppastirmaci.pecia.storage.model;

import dev.eyuppastirmaci.pecia.content.ContentHash;
import dev.eyuppastirmaci.pecia.content.ContentPath;
import dev.eyuppastirmaci.pecia.content.DocumentType;
import java.nio.file.Path;
import java.util.Objects;

/** A manifest entry with a positive database ID, project-relative path, and raw-content hash. */
public record StoredFile(long id, Path sourcePath, DocumentType documentType, ContentHash contentHash) {
    /** Validates the manifest identity and required content metadata. */
    public StoredFile {
        if (id <= 0) {
            throw new IllegalArgumentException("id must be positive: " + id);
        }

        sourcePath = ContentPath.requireProjectRelative(sourcePath);
        Objects.requireNonNull(documentType, "documentType");
        Objects.requireNonNull(contentHash, "contentHash");
    }
}
