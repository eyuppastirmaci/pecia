package dev.eyuppastirmaci.pecia.index;

import dev.eyuppastirmaci.pecia.config.PeciaConfig;
import dev.eyuppastirmaci.pecia.content.DocumentType;
import dev.eyuppastirmaci.pecia.semantic.EmbeddingInput;
import dev.eyuppastirmaci.pecia.tokenization.TokenizerIdentity;
import java.util.Map;

/**
 * Estimates how many chunks a file makes from its size, without the tokenizer. Bytes per chunk were measured per
 * document type on two projects, a Java design-pattern collection and Pecia's own sources, at the default budget, and
 * pooled; across those projects totals came within about 15% and most files within one chunk or a quarter. Other
 * budgets scale the ratio by how far each chunk advances.
 */
final class ChunkEstimate {

    private static final Map<DocumentType, Integer> BYTES_PER_CHUNK = Map.of(
            DocumentType.MARKDOWN, 387,
            DocumentType.PLAIN_TEXT, 845,
            DocumentType.SOURCE_CODE, 601,
            DocumentType.STRUCTURED_TEXT, 457);
    private static final int DEFAULT_STRIDE = stride(PeciaConfig.defaults());

    private final double bytesPerChunkScale;

    ChunkEstimate(PeciaConfig config) {
        this.bytesPerChunkScale = (double) stride(config) / DEFAULT_STRIDE;
    }

    /** Returns the estimated chunk count: none for an empty file, otherwise at least one. */
    long chunks(DocumentType type, long bytes) {
        if (bytes == 0) {
            return 0;
        }

        return Math.max(1, Math.round(bytes / (BYTES_PER_CHUNK.get(type) * bytesPerChunkScale)));
    }

    /** Returns the content tokens each chunk adds beyond the previous one, with the run's own budget limits. */
    private static int stride(PeciaConfig config) {
        TokenizerIdentity tokenizer = TokenizerIdentity.MINILM;
        // The constant is inlined, so a lexical preview never initializes the semantic class that declares it.
        int maxTokens = Math.min(config.maxTokens(), tokenizer.maxInputTokens() - EmbeddingInput.CONTEXT_TOKENS);
        int content = maxTokens - tokenizer.specialTokenCount();
        int overlap = Math.min(config.overlapTokens(), content - 1);

        return Math.max(1, content - overlap);
    }
}
