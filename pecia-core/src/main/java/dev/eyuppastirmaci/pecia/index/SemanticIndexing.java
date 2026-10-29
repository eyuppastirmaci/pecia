package dev.eyuppastirmaci.pecia.index;

import dev.eyuppastirmaci.pecia.content.Chunk;
import dev.eyuppastirmaci.pecia.semantic.EmbeddingException;
import dev.eyuppastirmaci.pecia.semantic.EmbeddingProfile;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/** The embedder and profile of one semantic indexing run, which embeds each changed file's chunks in batches. */
final class SemanticIndexing {

    // Bounds the memory of one inference call; on the CPU, larger batches do not embed full chunks faster.
    static final int BATCH_SIZE = 32;

    private final TextEmbedder embedder;
    private final EmbeddingProfile profile;

    SemanticIndexing(TextEmbedder embedder, EmbeddingProfile profile) {
        this.embedder = Objects.requireNonNull(embedder, "embedder");
        this.profile = Objects.requireNonNull(profile, "profile");
    }

    EmbeddingProfile profile() {
        return profile;
    }

    /** Embeds each chunk's stored content, as the profile's {@code chunk-content} input describes. */
    List<float[]> embed(List<Chunk> chunks) throws EmbeddingException {
        return embedTexts(chunks.stream().map(Chunk::content).toList());
    }

    /** Embeds stored chunk contents in batches, one embedding per text in order. */
    List<float[]> embedTexts(List<String> contents) throws EmbeddingException {
        List<float[]> vectors = new ArrayList<>(contents.size());

        for (int start = 0; start < contents.size(); start += BATCH_SIZE) {
            List<String> texts = contents.subList(start, Math.min(start + BATCH_SIZE, contents.size()));
            float[][] batch = embedder.embed(texts);

            if (batch.length != texts.size()) {
                throw new IllegalStateException(
                        "The embedder returned " + batch.length + " embeddings for " + texts.size() + " texts");
            }

            vectors.addAll(Arrays.asList(batch));
        }

        return vectors;
    }
}
