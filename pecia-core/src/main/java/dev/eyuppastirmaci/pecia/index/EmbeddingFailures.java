package dev.eyuppastirmaci.pecia.index;

import dev.eyuppastirmaci.pecia.semantic.EmbeddingException;

/**
 * Decides when embedding failures end a run. A failure tied to one input, such as an output that is not a unit
 * vector, leaves that file or page without embeddings and the run continues; a broken runtime or model, or several
 * failures in a row, mean every later input would fail too.
 */
final class EmbeddingFailures {

    /** Failing files or pages in a row, in the order they are written, after which the run stops. */
    static final int CONSECUTIVE_LIMIT = 3;

    private int consecutive;

    /** Records a file or page whose embeddings were written. */
    void succeeded() {
        consecutive = 0;
    }

    /**
     * Records a failing file or page.
     *
     * @throws EmbeddingException the failure itself, when the run should stop
     */
    void failed(EmbeddingException failure) throws EmbeddingException {
        consecutive++;

        boolean runtimeBroken =
                switch (failure.reason()) {
                    case RUNTIME_UNAVAILABLE, INVALID_MODEL -> true;
                    case INFERENCE_FAILED, INVALID_OUTPUT -> false;
                };

        if (runtimeBroken || consecutive >= CONSECUTIVE_LIMIT) {
            throw failure;
        }
    }
}
