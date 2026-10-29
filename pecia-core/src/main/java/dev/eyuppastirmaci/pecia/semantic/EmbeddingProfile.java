package dev.eyuppastirmaci.pecia.semantic;

import dev.eyuppastirmaci.pecia.tokenization.MiniLmTokenizer;
import dev.eyuppastirmaci.pecia.tokenization.TokenizerIdentity;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * How a set of embeddings was made: the model, its input preparation, and the pooling that turned token embeddings
 * into one vector. Vectors are comparable only when their profiles have the same fingerprint.
 *
 * @param modelId the model's repository, such as {@code sentence-transformers/all-MiniLM-L6-v2}
 * @param modelSha256 the lowercase SHA-256 of the model file
 * @param maxInputTokens the model input limit, including special tokens, beyond which input is cut
 * @param input what text of a chunk is embedded, such as {@code chunk-content}
 */
public record EmbeddingProfile(
        String modelId,
        String modelRevision,
        String modelSha256,
        int dimensions,
        String tokenizerAlgorithm,
        String vocabularySha256,
        int maxInputTokens,
        String pooling,
        String normalization,
        String input) {

    private static final String FINGERPRINT_FORMAT = "pecia-embedding-profile-v1";
    private static final int MAX_DIMENSIONS = 65_536;

    /** Validates non-blank text, lowercase SHA-256 digests, and positive limits. */
    public EmbeddingProfile {
        requireText(modelId, "modelId");
        requireText(modelRevision, "modelRevision");
        requireDigest(modelSha256, "modelSha256");
        requireText(tokenizerAlgorithm, "tokenizerAlgorithm");
        requireDigest(vocabularySha256, "vocabularySha256");
        requireText(pooling, "pooling");
        requireText(normalization, "normalization");
        requireText(input, "input");

        if (dimensions <= 0 || dimensions > MAX_DIMENSIONS) {
            throw new IllegalArgumentException(
                    "dimensions must be between 1 and " + MAX_DIMENSIONS + ": " + dimensions);
        }

        if (maxInputTokens <= 0) {
            throw new IllegalArgumentException("maxInputTokens must be positive: " + maxInputTokens);
        }
    }

    /**
     * Describes the embeddings Pecia makes: each chunk's stored content run through the pinned all-MiniLM-L6-v2 with
     * its bundled tokenizer, mean-pooled over the attention mask and scaled to unit length.
     *
     * @throws IllegalStateException if the bundled tokenizer vocabulary cannot be loaded
     */
    public static EmbeddingProfile minilm() {
        SemanticModel model = SemanticModel.MINILM;
        TokenizerIdentity tokenizer = MiniLmTokenizer.bundled().identity();

        return new EmbeddingProfile(
                model.repository(),
                model.revision(),
                model.sha256(),
                Embedder.DIMENSIONS,
                tokenizer.algorithm(),
                tokenizer.vocabularySha256(),
                tokenizer.maxInputTokens(),
                "mean",
                "l2",
                EmbeddingInput.NAME);
    }

    /**
     * Returns the lowercase SHA-256 fingerprint of the versioned canonical representation.
     *
     * <p>Strings are UTF-8, each prefixed by its byte length as a big-endian 32-bit integer, in this order:
     * {@code pecia-embedding-profile-v1}, model ID, model revision, model SHA-256, tokenizer algorithm, vocabulary
     * digest, pooling, normalization, and input. Two big-endian 32-bit integers follow: dimensions and maximum input
     * tokens. Text is preserved exactly.
     */
    public String fingerprint() {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");

            for (String text : new String[] {
                FINGERPRINT_FORMAT,
                modelId,
                modelRevision,
                modelSha256,
                tokenizerAlgorithm,
                vocabularySha256,
                pooling,
                normalization,
                input
            }) {
                byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
                digest.update(
                        ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
                digest.update(bytes);
            }

            digest.update(ByteBuffer.allocate(2 * Integer.BYTES)
                    .putInt(dimensions)
                    .putInt(maxInputTokens)
                    .array());

            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable in this Java runtime", impossible);
        }
    }

    private static void requireText(String value, String name) {
        if (value.isBlank() || !StandardCharsets.UTF_8.newEncoder().canEncode(value)) {
            throw new IllegalArgumentException(name + " must be non-blank, well-formed Unicode text");
        }
    }

    private static void requireDigest(String value, String name) {
        if (!value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(name + " must be a lowercase SHA-256 digest");
        }
    }
}
