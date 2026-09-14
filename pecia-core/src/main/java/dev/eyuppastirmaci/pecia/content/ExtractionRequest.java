package dev.eyuppastirmaci.pecia.content;

import java.nio.file.Path;
import java.util.Objects;

/** Absolute input location plus the stable identity stored in the index. */
public record ExtractionRequest(Path file, Path sourcePath, DocumentType type) {

    public ExtractionRequest {
        Objects.requireNonNull(file, "file");
        sourcePath = ContentPath.requireProjectRelative(sourcePath);
        Objects.requireNonNull(type, "type");

        if (!file.isAbsolute() || !file.normalize().equals(file)) {

            throw new IllegalArgumentException("file must be an absolute, normalized path: " + file);
        }
    }
}
