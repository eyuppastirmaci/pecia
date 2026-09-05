package dev.eyuppastirmaci.pecia.content;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/** Lowercase hexadecimal SHA-256 digest of the exact source bytes. */
public record ContentHash(String value) {

    private static final int HEX_LENGTH = 64;

    public ContentHash {
        Objects.requireNonNull(value, "value");

        if (value.length() != HEX_LENGTH || !value.matches("[0-9a-f]{64}")) {

            throw new IllegalArgumentException("value must be a lowercase 64-character SHA-256 digest");
        }
    }

    /**
     * Computes the SHA-256 digest of the supplied bytes.
     *
     * @param bytes exact source bytes to hash
     * @return the lowercase hexadecimal SHA-256 digest
     * @throws NullPointerException if bytes is null
     * @throws IllegalStateException if the Java runtime does not provide SHA-256
     */
    public static ContentHash sha256(byte[] bytes) {
        Objects.requireNonNull(bytes, "bytes");

        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");

            return new ContentHash(HexFormat.of().formatHex(digest.digest(bytes)));
        } catch (NoSuchAlgorithmException impossible) {

            throw new IllegalStateException("SHA-256 is unavailable in this Java runtime", impossible);
        }
    }
}
