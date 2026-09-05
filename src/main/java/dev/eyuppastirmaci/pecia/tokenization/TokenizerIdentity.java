package dev.eyuppastirmaci.pecia.tokenization;

import java.util.Objects;

/**
 * Versioned tokenizer properties that determine chunk and embedding compatibility.
 *
 * @param modelId embedding model whose tokenization contract is implemented
 * @param revision pinned model repository revision
 * @param algorithm tokenizer algorithm identifier
 * @param vocabularySha256 SHA-256 digest of the exact vocabulary bytes
 * @param vocabularySize number of entries in the vocabulary
 * @param maxInputTokens maximum model input length including special tokens
 * @param specialTokenCount number of tokens added around one input sequence
 */
public record TokenizerIdentity(
        String modelId,
        String revision,
        String algorithm,
        String vocabularySha256,
        int vocabularySize,
        int maxInputTokens,
        int specialTokenCount
) {

    private static final int SHA_256_HEX_LENGTH = 64;

    public TokenizerIdentity {
        modelId = requireText(modelId, "modelId");
        revision = requireText(revision, "revision");
        algorithm = requireText(algorithm, "algorithm");
        vocabularySha256 = requireText(vocabularySha256, "vocabularySha256");

        if (vocabularySha256.length() != SHA_256_HEX_LENGTH
                || !vocabularySha256.matches("[0-9a-f]{64}")) {

            throw new IllegalArgumentException("vocabularySha256 must be a lowercase SHA-256 digest");
        }

        if (vocabularySize <= 0) {

            throw new IllegalArgumentException("vocabularySize must be positive: " + vocabularySize);
        }

        if (maxInputTokens <= 0) {

            throw new IllegalArgumentException("maxInputTokens must be positive: " + maxInputTokens);
        }

        if (specialTokenCount < 0 || specialTokenCount >= maxInputTokens) {

            throw new IllegalArgumentException(
                    "specialTokenCount must be between zero and maxInputTokens: " + specialTokenCount);
        }
    }

    /**
     * Returns a stable key suitable for persisted chunking compatibility metadata.
     *
     * @return the complete tokenizer compatibility key
     */
    public String compatibilityKey() {

        return String.join(":", modelId + "@" + revision, algorithm, vocabularySha256,
                Integer.toString(vocabularySize), Integer.toString(maxInputTokens),
                Integer.toString(specialTokenCount));
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);

        if (value.isBlank()) {

            throw new IllegalArgumentException(name + " must not be blank");
        }

        return value;
    }
}
