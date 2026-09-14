package dev.eyuppastirmaci.pecia.tokenization;

/** Counts tokens with the same rules used by the configured embedding model. */
public interface TokenCounter {

    /**
     * Returns the versioned identity of the tokenizer and its input contract.
     *
     * @return the tokenizer identity
     */
    TokenizerIdentity identity();

    /**
     * Counts content tokens without model-added special tokens.
     *
     * @param text exact text to tokenize, including any rendered heading or context
     * @return the number of content tokens
     * @throws NullPointerException if text is null
     */
    int count(String text);

    /**
     * Counts a complete single-sequence model input including its special tokens.
     *
     * @param text exact model input text, including any rendered heading or context
     * @return the total number of model input tokens
     * @throws NullPointerException if text is null
     * @throws ArithmeticException if the token count exceeds the integer range
     */
    default int countModelInput(String text) {

        return Math.addExact(count(text), identity().specialTokenCount());
    }

    /**
     * Reports whether a complete single-sequence input fits the tokenizer's model limit.
     *
     * @param text exact model input text, including any rendered heading or context
     * @return true when content and special tokens fit within the model limit
     * @throws NullPointerException if text is null
     * @throws ArithmeticException if the token count exceeds the integer range
     */
    default boolean fitsModelInput(String text) {

        return countModelInput(text) <= identity().maxInputTokens();
    }
}
