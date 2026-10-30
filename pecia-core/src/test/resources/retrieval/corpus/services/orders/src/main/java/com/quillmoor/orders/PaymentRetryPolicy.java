package com.quillmoor.orders;

import java.time.Duration;

/**
 * Decides whether a failed charge is tried again and how long to wait first. Temporary provider errors are retried
 * with exponential backoff; declined cards never are.
 */
public final class PaymentRetryPolicy {

    public static final int MAX_ATTEMPTS = 5;
    private static final Duration FIRST_DELAY = Duration.ofSeconds(2);
    private static final Duration MAX_DELAY = Duration.ofMinutes(1);

    private final int maxAttempts;

    public PaymentRetryPolicy(int maxAttempts) {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be positive");
        }
        this.maxAttempts = maxAttempts;
    }

    /** Returns whether another attempt is allowed after {@code attempt} attempts ended with {@code failure}. */
    public boolean shouldRetry(int attempt, ChargeFailure failure) {
        return failure.temporary() && attempt < maxAttempts;
    }

    /** Returns the backoff before the given attempt: two seconds, doubled each time, at most one minute. */
    public Duration delayBefore(int attempt) {
        Duration delay = FIRST_DELAY.multipliedBy(1L << Math.max(0, attempt - 2));
        return delay.compareTo(MAX_DELAY) > 0 ? MAX_DELAY : delay;
    }

    public record ChargeFailure(String code, boolean temporary) {}
}
