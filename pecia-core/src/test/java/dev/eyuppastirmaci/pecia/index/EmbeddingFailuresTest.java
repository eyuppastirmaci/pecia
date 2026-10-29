package dev.eyuppastirmaci.pecia.index;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.eyuppastirmaci.pecia.semantic.EmbeddingException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** When embedding failures end a run: a broken runtime or model at once, input-level failures after three in a row. */
class EmbeddingFailuresTest {

    private final EmbeddingFailures failures = new EmbeddingFailures();

    @ParameterizedTest
    @EnumSource(
            value = EmbeddingException.Reason.class,
            names = {"RUNTIME_UNAVAILABLE", "INVALID_MODEL"})
    void aBrokenRuntimeOrModelStopsTheRunAtOnce(EmbeddingException.Reason reason) {
        EmbeddingException failure = failure(reason);

        assertSame(failure, assertThrows(EmbeddingException.class, () -> failures.failed(failure)));
    }

    @ParameterizedTest
    @EnumSource(
            value = EmbeddingException.Reason.class,
            names = {"INFERENCE_FAILED", "INVALID_OUTPUT"})
    void inputFailuresStopTheRunOnlyThreeInARow(EmbeddingException.Reason reason) {
        assertEquals(3, EmbeddingFailures.CONSECUTIVE_LIMIT);
        assertDoesNotThrow(() -> failures.failed(failure(reason)));
        assertDoesNotThrow(() -> failures.failed(failure(reason)));

        EmbeddingException third = failure(reason);

        assertSame(third, assertThrows(EmbeddingException.class, () -> failures.failed(third)));
    }

    @Test
    void aSuccessStartsTheCountAgain() {
        assertDoesNotThrow(() -> failures.failed(failure(EmbeddingException.Reason.INFERENCE_FAILED)));
        assertDoesNotThrow(() -> failures.failed(failure(EmbeddingException.Reason.INVALID_OUTPUT)));
        failures.succeeded();
        assertDoesNotThrow(() -> failures.failed(failure(EmbeddingException.Reason.INFERENCE_FAILED)));
        assertDoesNotThrow(() -> failures.failed(failure(EmbeddingException.Reason.INFERENCE_FAILED)));
        assertThrows(
                EmbeddingException.class, () -> failures.failed(failure(EmbeddingException.Reason.INFERENCE_FAILED)));
    }

    private static EmbeddingException failure(EmbeddingException.Reason reason) {
        return new EmbeddingException(reason, "injected", null);
    }
}
