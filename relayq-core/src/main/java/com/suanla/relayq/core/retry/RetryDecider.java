package com.suanla.relayq.core.retry;

import com.suanla.relayq.core.domain.FailureKind;

import java.time.Duration;
import java.util.Objects;

public class RetryDecider {

    private final BackoffPolicy backoffPolicy;

    public RetryDecider(BackoffPolicy backoffPolicy) {
        this.backoffPolicy = Objects.requireNonNull(backoffPolicy, "backoffPolicy must not be null");
    }

    public RetryDecision decide(FailureKind failureKind, int retryCount, int maxRetry) {
        Objects.requireNonNull(failureKind, "failureKind must not be null");
        if (retryCount < 0) {
            throw new IllegalArgumentException("retryCount must not be negative: " + retryCount);
        }
        if (maxRetry < 0) {
            throw new IllegalArgumentException("maxRetry must not be negative: " + maxRetry);
        }
        if (!failureKind.isRetryable() || retryCount >= maxRetry) {
            return RetryDecision.dead();
        }

        int retryNumber;
        try {
            retryNumber = Math.addExact(retryCount, 1);
        } catch (ArithmeticException error) {
            return RetryDecision.dead();
        }
        Duration delay = Objects.requireNonNull(
                backoffPolicy.nextDelay(retryNumber), "backoff delay must not be null");
        return RetryDecision.retryAfter(delay.toMillis());
    }
}
