package dev.eyuppastirmaci.pecia.index;

import dev.eyuppastirmaci.pecia.semantic.Embedder;
import dev.eyuppastirmaci.pecia.semantic.EmbeddingException;
import dev.eyuppastirmaci.pecia.semantic.ModelOutcome;
import java.util.List;

/** Turns texts into embeddings for one indexing run; a seam over {@link Embedder} for tests. */
interface TextEmbedder extends AutoCloseable {

    /** Returns one embedding per text, in order. */
    float[][] embed(List<String> texts) throws EmbeddingException;

    @Override
    void close();

    /** Opens the embedder that indexing runs use for a verified model file. */
    static TextEmbedder open(ModelOutcome model) throws EmbeddingException {
        Embedder embedder = Embedder.open(model);

        return new TextEmbedder() {
            @Override
            public float[][] embed(List<String> texts) throws EmbeddingException {
                return embedder.embed(texts);
            }

            @Override
            public void close() {
                embedder.close();
            }
        };
    }

    /** Opens a {@link TextEmbedder} for the model a run made available. */
    @FunctionalInterface
    interface Opener {

        TextEmbedder open(ModelOutcome model) throws EmbeddingException;
    }
}
