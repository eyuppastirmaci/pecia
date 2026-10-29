package dev.eyuppastirmaci.pecia.semantic;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.eyuppastirmaci.pecia.testing.TinyModel;
import dev.eyuppastirmaci.pecia.tokenization.MiniLmTokenizer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Runs the embedder on the tiny models of {@link TinyModel}. The valid model
 * shifts the values of masked positions, of non-zero token types, and of every token in an input whose padding is not
 * masked, so only correct inputs and pooling give the embeddings {@link #expected(String)} computes.
 */
class EmbedderTest {

    private static final float TOLERANCE = 1e-5f;

    @TempDir
    Path sandbox;

    @Test
    void embedsEachTextAsTheMeanOfItsTokensScaledToUnitLength() throws Exception {
        try (Embedder embedder = open("tiny")) {
            float[][] embeddings = embedder.embed(List.of("hello world"));

            assertEquals(1, embeddings.length);
            assertArrayEquals(expected("hello world"), embeddings[0], TOLERANCE);
            assertEquals(1, norm(embeddings[0]), 1e-6);
        }
    }

    @Test
    void paddingInABatchLeavesEveryEmbeddingAsItIsAlone() throws Exception {
        List<String> texts = List.of("a", "Payment validation runs before the order is confirmed.", "", "two words");

        try (Embedder embedder = open("tiny")) {
            float[][] batch = embedder.embed(texts);

            assertEquals(texts.size(), batch.length);

            for (int index = 0; index < texts.size(); index++) {
                String text = texts.get(index);
                assertArrayEquals(expected(text), batch[index], TOLERANCE, text);
                assertArrayEquals(embedder.embed(List.of(text))[0], batch[index], 1e-6f, text);
            }
        }
    }

    @Test
    void textsAreCutToTheModelLimit() throws Exception {
        String longText = "validation ".repeat(400);

        try (Embedder embedder = open("tiny")) {
            assertArrayEquals(expected(longText), embedder.embed(List.of(longText))[0], TOLERANCE);
        }
    }

    @Test
    void repeatedRunsGiveIdenticalEmbeddings() throws Exception {
        List<String> texts = List.of("deploy 🚀 succeeded", "İstanbul'da ödeme");

        try (Embedder embedder = open("tiny")) {
            float[][] first = embedder.embed(texts);

            for (int run = 0; run < 20; run++) {
                float[][] again = embedder.embed(texts);

                for (int index = 0; index < texts.size(); index++) {
                    assertArrayEquals(first[index], again[index]);
                }
            }
        }
    }

    @Test
    void anEmptyListHasNoEmbeddings() throws Exception {
        try (Embedder embedder = open("tiny")) {
            assertEquals(0, embedder.embed(List.of()).length);
        }
    }

    @Test
    void nullTextsAreRejected() throws Exception {
        List<String> withNull = new ArrayList<>(List.of("text"));
        withNull.add(null);

        try (Embedder embedder = open("tiny")) {
            assertThrows(NullPointerException.class, () -> embedder.embed(null));
            assertThrows(NullPointerException.class, () -> embedder.embed(withNull));
        }

        assertThrows(NullPointerException.class, () -> Embedder.open(null));
    }

    @Test
    void aClosedEmbedderRefusesWorkAndClosesOnce() throws Exception {
        Embedder embedder = open("tiny");
        embedder.close();

        IllegalStateException failure =
                assertThrows(IllegalStateException.class, () -> embedder.embed(List.of("hello")));

        assertEquals("The embedder is closed", failure.getMessage());
        embedder.close();
    }

    @Test
    void manyEmbeddersCanOpenAndCloseInTurn() throws Exception {
        for (int round = 0; round < 50; round++) {
            try (Embedder embedder = open("tiny")) {
                embedder.embed(List.of("round " + round));
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"declared-width", "missing-input", "pooled-output"})
    void modelsWithoutTheExpectedInputsAndOutputAreRejectedWhenOpened(String model) {
        EmbeddingException failure = assertThrows(EmbeddingException.class, () -> open(model));

        assertEquals(EmbeddingException.Reason.INVALID_MODEL, failure.reason());
        assertTrue(failure.getMessage().contains("does not take int64 input_ids"), failure.getMessage());
    }

    @Test
    void unreadableModelsAreRejectedWhenOpened() throws Exception {
        Path corrupt = Files.write(sandbox.resolve("corrupt.onnx"), "not a model".getBytes());
        Path missing = sandbox.resolve("missing.onnx");

        for (Path file : List.of(corrupt, missing)) {
            EmbeddingException failure = assertThrows(EmbeddingException.class, () -> open(file));

            assertEquals(EmbeddingException.Reason.INVALID_MODEL, failure.reason());
            assertEquals("Could not load the model at " + file, failure.getMessage());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"dynamic-width", "non-finite"})
    void outputsThatCannotFormEmbeddingsAreRejected(String model) throws Exception {
        try (Embedder embedder = open(model)) {
            EmbeddingException failure =
                    assertThrows(EmbeddingException.class, () -> embedder.embed(List.of("hello world")));

            assertEquals(EmbeddingException.Reason.INVALID_OUTPUT, failure.reason());
        }
    }

    @ParameterizedTest
    @CsvSource({"Mac OS X, x86_64", "Windows 11, aarch64", "SunOS, sparcv9"})
    void platformsWithoutANativeRuntimeFailClearly(String osName, String osArch) throws Exception {
        EmbeddingException failure =
                assertThrows(EmbeddingException.class, () -> Embedder.open(model("tiny"), osName, osArch));

        assertEquals(EmbeddingException.Reason.RUNTIME_UNAVAILABLE, failure.reason());
        assertEquals(
                "ONNX Runtime has no native library for " + osName + " on " + osArch + "; semantic indexing runs on"
                        + " Linux on x64 or arm64, macOS on Apple silicon, and Windows on x64",
                failure.getMessage());
    }

    private static Embedder open(String model) throws Exception {
        return open(model(model));
    }

    private static Embedder open(Path file) throws EmbeddingException {
        return Embedder.open(new ModelOutcome(ModelOutcome.Status.CACHED, SemanticModel.MINILM, file));
    }

    static Path model(String name) {
        return TinyModel.path(name);
    }

    /** Computes the valid tiny model's embedding in float arithmetic, as ONNX Runtime does before pooling. */
    private static float[] expected(String text) {
        int[] ids = MiniLmTokenizer.bundled().encode(text);
        double[] sum = new double[Embedder.DIMENSIONS];

        for (int id : ids) {
            for (int dimension = 0; dimension < sum.length; dimension++) {
                float frequency = (float) ((dimension + 1) * 1e-5);
                float phase = (float) (dimension * 0.1);
                sum[dimension] += (float) Math.sin((float) id * frequency + phase);
            }
        }

        double norm = Math.sqrt(Arrays.stream(sum).map(value -> value * value).sum());
        float[] embedding = new float[sum.length];

        for (int dimension = 0; dimension < sum.length; dimension++) {
            embedding[dimension] = (float) (sum[dimension] / norm);
        }

        return embedding;
    }

    private static double norm(float[] vector) {
        double squares = 0;

        for (float value : vector) {
            squares += (double) value * value;
        }

        return Math.sqrt(squares);
    }
}
