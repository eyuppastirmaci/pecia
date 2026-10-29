package dev.eyuppastirmaci.pecia.semantic;

import ai.onnxruntime.NodeInfo;
import ai.onnxruntime.OnnxJavaType;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OnnxValue;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtLoggingLevel;
import ai.onnxruntime.OrtSession;
import ai.onnxruntime.TensorInfo;
import dev.eyuppastirmaci.pecia.tokenization.MiniLmTokenizer;
import java.nio.FloatBuffer;
import java.nio.LongBuffer;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Turns texts into all-MiniLM-L6-v2 sentence embeddings: unit-length vectors of {@value #DIMENSIONS} floats, computed
 * on the CPU with ONNX Runtime. ONNX Runtime is loaded only when the first embedder opens. Instances may be shared
 * between threads; {@link #close()} waits for running calls and makes later ones fail.
 */
public final class Embedder implements AutoCloseable {

    /** The number of components in every embedding. */
    public static final int DIMENSIONS = 384;

    private static final String INPUT_IDS = "input_ids";
    private static final String ATTENTION_MASK = "attention_mask";
    private static final String TOKEN_TYPE_IDS = "token_type_ids";
    private static final String OUTPUT = "last_hidden_state";
    private static final int PADDING_ID = 0;

    private final OrtEnvironment environment;
    private final OrtSession session;
    private final MiniLmTokenizer tokenizer = MiniLmTokenizer.bundled();
    private final ReadWriteLock lock = new ReentrantReadWriteLock();
    private boolean closed;

    private Embedder(OrtEnvironment environment, OrtSession session) {
        this.environment = environment;
        this.session = session;
    }

    /**
     * Opens the verified model file for inference.
     *
     * @throws EmbeddingException if ONNX Runtime is not available on this platform, or the file cannot be loaded or
     *     lacks the model's inputs and output
     * @throws NullPointerException if model is null
     */
    public static Embedder open(ModelOutcome model) throws EmbeddingException {
        return open(model.file(), System.getProperty("os.name", ""), System.getProperty("os.arch", ""));
    }

    static Embedder open(Path file, String osName, String osArch) throws EmbeddingException {
        // Checking the bundled libraries first gives a clear failure without touching ONNX Runtime.
        if (!RuntimePlatform.bundled(osName, osArch)) {
            throw new EmbeddingException(
                    EmbeddingException.Reason.RUNTIME_UNAVAILABLE,
                    "ONNX Runtime has no native library for " + osName + " on " + osArch
                            + "; semantic indexing runs on " + RuntimePlatform.SUPPORTED,
                    null);
        }

        OrtEnvironment environment = environment(osName, osArch);
        OrtSession session;

        try (OrtSession.SessionOptions options = new OrtSession.SessionOptions()) {
            options.setSessionLogLevel(OrtLoggingLevel.ORT_LOGGING_LEVEL_ERROR);
            session = environment.createSession(file.toString(), options);
        } catch (OrtException failure) {
            throw new EmbeddingException(
                    EmbeddingException.Reason.INVALID_MODEL, "Could not load the model at " + file, failure);
        }

        try {
            checkContract(session, file);
        } catch (EmbeddingException | RuntimeException failure) {
            closeQuietly(session, failure);
            throw failure;
        }

        return new Embedder(environment, session);
    }

    private static OrtEnvironment environment(String osName, String osArch) throws EmbeddingException {
        try {
            // The process-wide environment lives until exit; only warnings worth an error reach standard error.
            return OrtEnvironment.getEnvironment(OrtLoggingLevel.ORT_LOGGING_LEVEL_ERROR, "pecia");
        } catch (LinkageError | RuntimeException failure) {
            // A native library that fails to load surfaces as a linkage error, also on every later attempt.
            throw new EmbeddingException(
                    EmbeddingException.Reason.RUNTIME_UNAVAILABLE,
                    "ONNX Runtime could not load its native library on " + osName + " on " + osArch,
                    failure);
        }
    }

    private static void checkContract(OrtSession session, Path file) throws EmbeddingException {
        try {
            Map<String, NodeInfo> inputs = session.getInputInfo();
            boolean inputsMatch = inputs.keySet().equals(Set.of(INPUT_IDS, ATTENTION_MASK, TOKEN_TYPE_IDS))
                    && inputs.values().stream().allMatch(input -> isTensor(input, OnnxJavaType.INT64, 2));
            NodeInfo output = session.getOutputInfo().get(OUTPUT);
            boolean outputMatches = output != null
                    && isTensor(output, OnnxJavaType.FLOAT, 3)
                    && widthMayMatch(((TensorInfo) output.getInfo()).getShape()[2]);

            if (!inputsMatch || !outputMatches) {
                throw new EmbeddingException(
                        EmbeddingException.Reason.INVALID_MODEL,
                        "The model at " + file + " does not take int64 " + INPUT_IDS + ", " + ATTENTION_MASK + ", and "
                                + TOKEN_TYPE_IDS + " and return " + DIMENSIONS + "-wide float " + OUTPUT,
                        null);
            }
        } catch (OrtException failure) {
            throw new EmbeddingException(
                    EmbeddingException.Reason.INVALID_MODEL, "Could not inspect the model at " + file, failure);
        }
    }

    private static boolean isTensor(NodeInfo node, OnnxJavaType type, int rank) {
        return node.getInfo() instanceof TensorInfo tensor && tensor.type == type && tensor.getShape().length == rank;
    }

    /** A width unknown until inference is checked on every result instead. */
    private static boolean widthMayMatch(long width) {
        return width == DIMENSIONS || width < 0;
    }

    /**
     * Returns one unit-length embedding per text, in order. The texts run as one batch, padded to the longest; each
     * text is cut to the model's 256-token limit.
     *
     * @return {@code texts.size()} arrays of {@value #DIMENSIONS} floats
     * @throws EmbeddingException if inference fails or returns values that do not form unit vectors
     * @throws NullPointerException if texts or any text is null
     * @throws IllegalStateException if the embedder is closed
     */
    public float[][] embed(List<String> texts) throws EmbeddingException {
        List<String> inputs = List.copyOf(texts);

        lock.readLock().lock();

        try {
            if (closed) {
                throw new IllegalStateException("The embedder is closed");
            }

            return inputs.isEmpty() ? new float[0][] : run(inputs);
        } finally {
            lock.readLock().unlock();
        }
    }

    private float[][] run(List<String> texts) throws EmbeddingException {
        int[][] encoded = texts.stream().map(tokenizer::encode).toArray(int[][]::new);
        int[] lengths = new int[encoded.length];
        int positions = 0;

        for (int input = 0; input < encoded.length; input++) {
            lengths[input] = encoded[input].length;
            positions = Math.max(positions, lengths[input]);
        }

        long[] ids = new long[encoded.length * positions];
        long[] mask = new long[ids.length];

        for (int input = 0; input < encoded.length; input++) {
            for (int position = 0; position < positions; position++) {
                boolean real = position < lengths[input];
                ids[input * positions + position] = real ? encoded[input][position] : PADDING_ID;
                mask[input * positions + position] = real ? 1 : 0;
            }
        }

        long[] shape = {encoded.length, positions};

        // Every tensor and result is closed after the batch, so native memory does not wait for garbage collection.
        try (OnnxTensor idTensor = OnnxTensor.createTensor(environment, LongBuffer.wrap(ids), shape);
                OnnxTensor maskTensor = OnnxTensor.createTensor(environment, LongBuffer.wrap(mask), shape);
                OnnxTensor typeTensor =
                        OnnxTensor.createTensor(environment, LongBuffer.wrap(new long[ids.length]), shape);
                OrtSession.Result result = session.run(
                        Map.of(INPUT_IDS, idTensor, ATTENTION_MASK, maskTensor, TOKEN_TYPE_IDS, typeTensor))) {
            OnnxValue output = result.get(OUTPUT).orElseThrow();

            if (!(output instanceof OnnxTensor tensor) || tensor.getInfo().type != OnnxJavaType.FLOAT) {
                throw new EmbeddingException(
                        EmbeddingException.Reason.INVALID_OUTPUT,
                        "The model returned " + OUTPUT + " as non-float",
                        null);
            }

            FloatBuffer values = tensor.getFloatBuffer();
            float[] hidden = new float[values.remaining()];
            values.get(hidden);

            return MeanPooling.pool(hidden, tensor.getInfo().getShape(), lengths, DIMENSIONS);
        } catch (OrtException failure) {
            throw new EmbeddingException(
                    EmbeddingException.Reason.INFERENCE_FAILED, "ONNX Runtime failed to run the model", failure);
        }
    }

    /**
     * Releases the model's session after running calls finish. Closing again has no effect.
     *
     * @throws IllegalStateException if ONNX Runtime fails to release the session
     */
    @Override
    public void close() {
        lock.writeLock().lock();

        try {
            if (closed) {
                return;
            }

            closed = true;
            session.close();
        } catch (OrtException failure) {
            throw new IllegalStateException("ONNX Runtime failed to release the model's session", failure);
        } finally {
            lock.writeLock().unlock();
        }
    }

    private static void closeQuietly(OrtSession session, Exception primary) {
        try {
            session.close();
        } catch (OrtException suppressed) {
            primary.addSuppressed(suppressed);
        }
    }
}
