package dev.eyuppastirmaci.pecia.tokenization;

import java.nio.charset.StandardCharsets;

/**
 * Tokenization behavior and input limits, independent of embedding model names and revisions.
 * The algorithm identifier must change when normalization, splitting, or special-token handling changes.
 *
 * @param maxInputTokens maximum input length including special tokens
 * @param specialTokenCount number of tokens reserved around each input sequence
 */
public record TokenizerCompatibility(
        String algorithm, String vocabularySha256, int vocabularySize, int maxInputTokens, int specialTokenCount) {

    /** Validates a non-blank Unicode algorithm identifier, vocabulary digest, and token limits. */
    public TokenizerCompatibility {
        if (algorithm.isBlank() || !StandardCharsets.UTF_8.newEncoder().canEncode(algorithm)) {
            throw new IllegalArgumentException("algorithm must be non-blank, well-formed Unicode text");
        }

        if (!vocabularySha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("vocabularySha256 must be a lowercase SHA-256 digest");
        }

        if (vocabularySize <= 0) {
            throw new IllegalArgumentException("vocabularySize must be positive: " + vocabularySize);
        }

        if (maxInputTokens <= 0) {
            throw new IllegalArgumentException("maxInputTokens must be positive: " + maxInputTokens);
        }

        if (specialTokenCount < 0 || specialTokenCount >= maxInputTokens) {
            throw new IllegalArgumentException("specialTokenCount must be between zero and maxInputTokens");
        }
    }

    /**
     * Selects behavior metadata without loading tokenizer assets or retaining model provenance.
     * A model revision alone does not change compatibility; changed tokenization must be reflected
     * in the algorithm, vocabulary, or input limits.
     *
     * @throws NullPointerException if identity is null
     * @throws IllegalArgumentException if its algorithm is not well-formed Unicode text
     */
    public static TokenizerCompatibility from(TokenizerIdentity identity) {
        return new TokenizerCompatibility(
                identity.algorithm(),
                identity.vocabularySha256(),
                identity.vocabularySize(),
                identity.maxInputTokens(),
                identity.specialTokenCount());
    }
}
