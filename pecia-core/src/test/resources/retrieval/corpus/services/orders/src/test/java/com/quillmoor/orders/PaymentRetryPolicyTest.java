package com.quillmoor.orders;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class PaymentRetryPolicyTest {

    private final PaymentRetryPolicy policy = new PaymentRetryPolicy(PaymentRetryPolicy.MAX_ATTEMPTS);

    @Test
    void temporaryFailuresAreRetriedUntilTheLimit() {
        var timeout = new PaymentRetryPolicy.ChargeFailure("E1043", true);

        assertTrue(policy.shouldRetry(1, timeout));
        assertFalse(policy.shouldRetry(PaymentRetryPolicy.MAX_ATTEMPTS, timeout));
    }

    @Test
    void declinedCardsAreNeverRetried() {
        assertFalse(policy.shouldRetry(1, new PaymentRetryPolicy.ChargeFailure("E1042", false)));
    }

    @Test
    void theDelayDoublesAndIsCapped() {
        assertEquals(Duration.ofSeconds(2), policy.delayBefore(2));
        assertEquals(Duration.ofSeconds(4), policy.delayBefore(3));
        assertEquals(Duration.ofMinutes(1), policy.delayBefore(10));
    }
}
