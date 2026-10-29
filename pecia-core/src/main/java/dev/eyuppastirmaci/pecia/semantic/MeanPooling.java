package dev.eyuppastirmaci.pecia.semantic;

import java.util.Arrays;

/** Turns token embeddings into unit-length sentence embeddings, as the model's pooling and normalization do. */
final class MeanPooling {

    private MeanPooling() {}

    /**
     * Averages each input's token embeddings over its real tokens and scales the average to unit length.
     *
     * @param hidden token embeddings in row-major {@code [inputs, positions, dimensions]} order
     * @param shape the reported shape of {@code hidden}
     * @param lengths the number of real tokens of each input; the positions after them are padding
     * @param dimensions the width each token embedding must have
     * @throws EmbeddingException if the shape does not match the inputs or a result is not a finite, non-zero vector
     */
    static float[][] pool(float[] hidden, long[] shape, int[] lengths, int dimensions) throws EmbeddingException {
        int positions = Arrays.stream(lengths).max().orElse(0);

        if (shape.length != 3
                || shape[0] != lengths.length
                || shape[1] != positions
                || shape[2] != dimensions
                || hidden.length != (long) lengths.length * positions * dimensions) {
            throw new EmbeddingException(
                    EmbeddingException.Reason.INVALID_OUTPUT,
                    "The model returned shape " + Arrays.toString(shape) + " instead of [" + lengths.length + ", "
                            + positions + ", " + dimensions + "]",
                    null);
        }

        float[][] embeddings = new float[lengths.length][];

        for (int input = 0; input < lengths.length; input++) {
            embeddings[input] = poolOne(hidden, input * positions * dimensions, lengths[input], dimensions);
        }

        return embeddings;
    }

    private static float[] poolOne(float[] hidden, int offset, int length, int dimensions) throws EmbeddingException {
        // Doubles keep the sum of up to 256 tokens from losing precision before the result is narrowed to floats.
        double[] sum = new double[dimensions];

        for (int position = 0; position < length; position++) {
            int start = offset + position * dimensions;

            for (int dimension = 0; dimension < dimensions; dimension++) {
                sum[dimension] += hidden[start + dimension];
            }
        }

        double squares = 0;

        for (int dimension = 0; dimension < dimensions; dimension++) {
            sum[dimension] /= length;
            squares += sum[dimension] * sum[dimension];
        }

        double norm = Math.sqrt(squares);

        if (!Double.isFinite(norm) || norm == 0) {
            throw new EmbeddingException(
                    EmbeddingException.Reason.INVALID_OUTPUT,
                    "The model returned token embeddings whose average is not a finite, non-zero vector",
                    null);
        }

        float[] embedding = new float[dimensions];

        for (int dimension = 0; dimension < dimensions; dimension++) {
            embedding[dimension] = (float) (sum[dimension] / norm);
        }

        return embedding;
    }
}
