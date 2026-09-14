package dev.eyuppastirmaci.pecia.storage.model;

import dev.eyuppastirmaci.pecia.content.ContentHash;
import dev.eyuppastirmaci.pecia.content.ContentPath;
import dev.eyuppastirmaci.pecia.content.DocumentType;

import java.nio.file.Path;
import java.util.Objects;

public record StoredFile(long id, Path sourcePath, DocumentType documentType, ContentHash contentHash) {
    public StoredFile {
        if (id <= 0) {
            throw new IllegalArgumentException("id must be positive: " + id);
        }

        sourcePath = ContentPath.requireProjectRelative(sourcePath);
        Objects.requireNonNull(documentType, "documentType");
        Objects.requireNonNull(contentHash, "contentHash");
    }
}
