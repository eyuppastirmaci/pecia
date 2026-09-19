package dev.eyuppastirmaci.pecia.chunking;

import dev.eyuppastirmaci.pecia.chunking.internal.ChunkingBudget;
import dev.eyuppastirmaci.pecia.config.PeciaConfig;
import dev.eyuppastirmaci.pecia.tokenization.TokenizerCompatibility;
import dev.eyuppastirmaci.pecia.tokenization.TokenizerIdentity;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Versioned extraction and chunking behavior with the effective tokenizer and token budgets.
 * Custom pipelines must supply their own behavior versions, including any output-affecting settings.
 *
 * @param maxTokens total chunk budget including special tokens
 * @param overlapTokens maximum shared content tokens between adjacent chunks
 */
public record ChunkingIdentity(
        String extractionVersion,
        String chunkingVersion,
        TokenizerCompatibility tokenizer,
        int maxTokens,
        int overlapTokens) {

    private static final String FINGERPRINT_FORMAT = "pecia-chunking-identity-v1";

    // Bump when UTF-8 decoding, BOM handling, or binary admission behavior changes.
    private static final String BUILT_IN_EXTRACTION_VERSION = "pecia-utf8-extraction-v1";

    // Bump for changes to built-in routing, boundaries, context, overlap, locations, or metadata.
    private static final String BUILT_IN_CHUNKING_VERSION = "pecia-built-in-chunking-v1";

    /** Validates behavior versions and chunk limits against the non-null tokenizer contract. */
    public ChunkingIdentity {
        requireVersion(extractionVersion, "extractionVersion");
        requireVersion(chunkingVersion, "chunkingVersion");
        ChunkingBudget.validate(tokenizer, maxTokens, overlapTokens);
    }

    /**
     * Describes the built-in UTF-8 extractor and complete text, Markdown, and source chunking pipeline
     * without loading a tokenizer or reading files. Custom strategies and routing overrides must use
     * the constructor with their own versions.
     *
     * <p>Discovery, file-size admission limits, storage, concurrency, and embedding model provenance
     * do not affect this identity. Document type and source content remain separate indexing inputs.
     *
     * @throws NullPointerException if config or tokenizerIdentity is null
     * @throws IllegalArgumentException if the chunk budget or tokenizer behavior is invalid
     */
    public static ChunkingIdentity from(PeciaConfig config, TokenizerIdentity tokenizerIdentity) {
        return new ChunkingIdentity(
                BUILT_IN_EXTRACTION_VERSION,
                BUILT_IN_CHUNKING_VERSION,
                TokenizerCompatibility.from(tokenizerIdentity),
                config.maxTokens(),
                config.overlapTokens());
    }

    /**
     * Returns the lowercase SHA-256 fingerprint of the versioned canonical representation.
     *
     * <p>Strings are UTF-8, each prefixed by its byte length as a big-endian 32-bit integer. Their
     * order is {@code pecia-chunking-identity-v1}, extraction version, chunking version, tokenizer
     * algorithm, and vocabulary digest. Five big-endian 32-bit integers follow: vocabulary size,
     * maximum input tokens, special-token count, maximum chunk tokens, and overlap tokens. Text is
     * preserved exactly, without trimming, case folding, or Unicode normalization.
     *
     * @throws IllegalStateException if the Java runtime does not provide SHA-256
     */
    public String fingerprint() {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            updateText(digest, FINGERPRINT_FORMAT);
            updateText(digest, extractionVersion);
            updateText(digest, chunkingVersion);
            updateText(digest, tokenizer.algorithm());
            updateText(digest, tokenizer.vocabularySha256());
            updateInt(digest, tokenizer.vocabularySize());
            updateInt(digest, tokenizer.maxInputTokens());
            updateInt(digest, tokenizer.specialTokenCount());
            updateInt(digest, maxTokens);
            updateInt(digest, overlapTokens);

            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable in this Java runtime", impossible);
        }
    }

    private static void requireVersion(String version, String name) {
        if (version.isBlank() || !StandardCharsets.UTF_8.newEncoder().canEncode(version)) {
            throw new IllegalArgumentException(name + " must be non-blank, well-formed Unicode text");
        }
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
