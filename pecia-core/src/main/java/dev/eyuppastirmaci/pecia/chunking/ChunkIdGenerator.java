package dev.eyuppastirmaci.pecia.chunking;

import dev.eyuppastirmaci.pecia.content.Chunk;
import dev.eyuppastirmaci.pecia.content.ChunkId;
import dev.eyuppastirmaci.pecia.content.ContentPath;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Generates project-local chunk identities from a relative path, position, and chunking profile.
 * Instances are immutable and reusable across files, threads, and processing orders.
 */
public final class ChunkIdGenerator {

    private static final String ID_FORMAT = "pecia-chunk-id-v1";

    private final String chunkingFingerprint;

    /**
     * Captures the chunking fingerprint without loading tokenizer assets or reading files.
     *
     * @throws NullPointerException if identity is null
     */
    public ChunkIdGenerator(ChunkingIdentity identity) {
        this.chunkingFingerprint = identity.fingerprint();
    }

    /**
     * Generates an ID without reading the source or using its absolute project root.
     *
     * <p>The SHA-256 input is the UTF-8 format tag {@code pecia-chunk-id-v1}, slash-separated source
     * path, zero-based chunk index, and lowercase chunking fingerprint, in that order. Each string
     * has a big-endian 32-bit byte-length prefix; the index is a big-endian 32-bit integer. Path case
     * and Unicode spelling are preserved. Paths must already be normalized.
     *
     * <p>Content and source locations are not inputs: edits at the same position retain the ID when
     * the profile is unchanged. Content freshness must be tracked separately. IDs are scoped to a
     * project; equal relative paths, positions, and profiles in separate projects produce equal IDs.
     *
     * @param sourcePath normalized project-relative path, without a root or parent traversal
     * @param chunkIndex non-negative, zero-based chunk position
     * @throws NullPointerException if sourcePath is null
     * @throws IllegalArgumentException if the path or index is invalid, or the path cannot be
     *     represented losslessly as Unicode text
     * @throws IllegalStateException if the Java runtime does not provide SHA-256
     */
    public ChunkId generate(Path sourcePath, int chunkIndex) {
        if (chunkIndex < 0) {
            throw new IllegalArgumentException("chunkIndex must not be negative: " + chunkIndex);
        }

        String path = ContentPath.encode(sourcePath);

        // Native filenames with invalid bytes can share the same replacement-character spelling.
        if (!StandardCharsets.UTF_8.newEncoder().canEncode(path)
                || !sourcePath.equals(sourcePath.getFileSystem().getPath(sourcePath.toString()))) {
            throw new IllegalArgumentException("sourcePath must have a lossless Unicode representation");
        }

        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            updateText(digest, ID_FORMAT);
            updateText(digest, path);
            updateInt(digest, chunkIndex);
            updateText(digest, chunkingFingerprint);

            return new ChunkId(HexFormat.of().formatHex(digest.digest()));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable in this Java runtime", impossible);
        }
    }

    /**
     * Generates an ID using only the chunk's path and index under this generator's profile.
     *
     * @throws NullPointerException if chunk is null
     * @throws IllegalArgumentException if the chunk path cannot be encoded as a project-relative ID
     */
    public ChunkId generate(Chunk chunk) {
        return generate(chunk.sourcePath(), chunk.index());
    }

    private static void updateText(MessageDigest digest, String text) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        updateInt(digest, bytes.length);
        digest.update(bytes);
    }

    private static void updateInt(MessageDigest digest, int value) {
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(value).array());
    }
}
