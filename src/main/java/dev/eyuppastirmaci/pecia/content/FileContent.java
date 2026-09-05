package dev.eyuppastirmaci.pecia.content;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.Objects;

/** Immutable bytes read from one absolute source path. */
public final class FileContent {

    private final Path file;
    private final byte[] bytes;
    private final ContentHash contentHash;

    public FileContent(Path file, byte[] bytes) {
        Objects.requireNonNull(file, "file");
        Objects.requireNonNull(bytes, "bytes");

        if (!file.isAbsolute() || !file.normalize().equals(file)) {

            throw new IllegalArgumentException("file must be an absolute, normalized path: " + file);
        }

        this.file = file;
        this.bytes = Arrays.copyOf(bytes, bytes.length);
        this.contentHash = ContentHash.sha256(this.bytes);
    }

    /**
     * Returns the absolute normalized path from which the bytes were read.
     *
     * @return the source file path
     */
    public Path file() {

        return file;
    }

    /**
     * Returns the number of loaded source bytes.
     *
     * @return the byte count
     */
    public int size() {

        return bytes.length;
    }

    /**
     * Returns a defensive copy of the loaded source bytes.
     *
     * @return a copy of the source bytes
     */
    public byte[] bytes() {

        return Arrays.copyOf(bytes, bytes.length);
    }

    /**
     * Returns the digest computed from the loaded source bytes.
     *
     * @return the source content hash
     */
    public ContentHash contentHash() {

        return contentHash;
    }
}
