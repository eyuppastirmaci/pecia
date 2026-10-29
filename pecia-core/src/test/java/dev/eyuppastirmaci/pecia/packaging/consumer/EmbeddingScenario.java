package dev.eyuppastirmaci.pecia.packaging.consumer;

import static dev.eyuppastirmaci.pecia.packaging.consumer.ConsumerChecks.check;

import dev.eyuppastirmaci.pecia.semantic.Embedder;
import dev.eyuppastirmaci.pecia.semantic.ModelOutcome;
import dev.eyuppastirmaci.pecia.semantic.SemanticModel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

/** Verifies that the packaged core and its runtime dependencies can embed texts with a model file. */
final class EmbeddingScenario {

    /** The tiny test model the packaging test copies into the project directory. */
    static final String MODEL_FILE = "tiny.onnx";

    private EmbeddingScenario() {}

    /** Embeds a padded batch twice, then checks that a closed embedder refuses work. */
    static void verifyEmbedding(Path root) throws Exception {
        Path model = root.resolve(MODEL_FILE);
        check(Files.isRegularFile(model), "The packaging test must supply " + model);
        List<String> texts = List.of("hello world", "Payment validation runs before the order is confirmed.");
        Embedder embedder = Embedder.open(new ModelOutcome(ModelOutcome.Status.CACHED, SemanticModel.MINILM, model));
        float[][] first;
        float[][] second;

        try (embedder) {
            first = embedder.embed(texts);
            second = embedder.embed(texts);
        }

        check(first.length == texts.size(), "The packaged embedder must return one embedding per text");

        for (int index = 0; index < first.length; index++) {
            check(first[index].length == Embedder.DIMENSIONS, "Packaged embeddings must have 384 dimensions");
            check(Math.abs(norm(first[index]) - 1) < 1e-5, "Packaged embeddings must have unit length");
            check(Arrays.equals(first[index], second[index]), "Repeated packaged embeddings must be identical");
        }

        check(!Arrays.equals(first[0], first[1]), "Different texts must have different embeddings");

        try {
            embedder.embed(texts);
            throw new AssertionError("A closed packaged embedder must refuse work");
        } catch (IllegalStateException expected) {
            // Closing releases the session for good.
        }
    }

    private static double norm(float[] vector) {
        double squares = 0;

        for (float value : vector) {
            squares += (double) value * value;
        }

        return Math.sqrt(squares);
    }
}
