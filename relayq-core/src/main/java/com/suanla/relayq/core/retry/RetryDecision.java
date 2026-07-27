package com.suanla.relayq.core.retry;

public record RetryDecision(boolean shouldRetry, long delayMillis) {

    public static RetryDecision retryAfter(long delayMillis) {
        if (delayMillis < 0L) {
            throw new IllegalArgumentException("delayMillis must not be negative: " + delayMillis);
        }
        return new RetryDecision(true, delayMillis);
    }

    public static RetryDecision dead() {
        return new RetryDecision(false, 0L);
    }

    public boolean isDead() {
        return !shouldRetry;
    }
}
