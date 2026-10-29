package dev.eyuppastirmaci.pecia.semantic;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class MeanPoolingTest {

    @Test
    void averagesOnlyRealTokensAndScalesToUnitLength() throws Exception {
        // Two inputs of up to three positions and two dimensions; the second input's last position is padding.
        float[] hidden = {
            1, 0, 3, 4, 2, 0,
            0, 2, 0, 4, 99, -99
        };

        float[][] pooled = MeanPooling.pool(hidden, new long[] {2, 3, 2}, new int[] {3, 2}, 2);

        // Averages (2, 4/3) and (0, 3), scaled to unit length.
        double norm = Math.hypot(2, 4.0 / 3);
        assertArrayEquals(new float[] {(float) (2 / norm), (float) (4.0 / 3 / norm)}, pooled[0], 1e-7f);
        assertArrayEquals(new float[] {0, 1}, pooled[1], 1e-7f);
    }

    @Test
    void anEmptyBatchHasNoEmbeddings() throws Exception {
        assertEquals(0, MeanPooling.pool(new float[0], new long[] {0, 0, 2}, new int[0], 2).length);
    }

    @Test
    void rejectsAShapeThatDoesNotMatchTheInputs() {
        // Values that would pool into valid embeddings, so only the shape can cause a rejection.
        float[] hidden = new float[12];
        Arrays.fill(hidden, 1);
        int[] lengths = {3, 2};

        // Each shape has one wrong dimension or the wrong rank.
        for (long[] shape : new long[][] {{2, 3, 3}, {1, 3, 2}, {2, 2, 2}, {2, 3, 2, 1}, {2, 3}}) {
            EmbeddingException failure =
                    assertThrows(EmbeddingException.class, () -> MeanPooling.pool(hidden, shape, lengths, 2));
            assertEquals(EmbeddingException.Reason.INVALID_OUTPUT, failure.reason());
        }

        EmbeddingException shortData = assertThrows(
                EmbeddingException.class, () -> MeanPooling.pool(new float[11], new long[] {2, 3, 2}, lengths, 2));
        assertEquals(EmbeddingException.Reason.INVALID_OUTPUT, shortData.reason());
    }

    @ParameterizedTest
    @ValueSource(floats = {Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, 0})
    void rejectsAveragesThatAreNotFiniteNonZeroVectors(float value) {
        float[] hidden = {value, value};

        EmbeddingException failure = assertThrows(
                EmbeddingException.class, () -> MeanPooling.pool(hidden, new long[] {1, 1, 2}, new int[] {1}, 2));

        assertEquals(EmbeddingException.Reason.INVALID_OUTPUT, failure.reason());
    }

    @Test
    void ignoresNonFiniteValuesInPadding() throws Exception {
        // The first input's second position is padding that the longer second input requires.
        float[] hidden = {3, 4, Float.NaN, Float.POSITIVE_INFINITY, 1, 0, 1, 0};

        float[][] pooled = MeanPooling.pool(hidden, new long[] {2, 2, 2}, new int[] {1, 2}, 2);

        assertArrayEquals(new float[] {0.6f, 0.8f}, pooled[0], 1e-7f);
        assertArrayEquals(new float[] {1, 0}, pooled[1], 1e-7f);
    }
}
