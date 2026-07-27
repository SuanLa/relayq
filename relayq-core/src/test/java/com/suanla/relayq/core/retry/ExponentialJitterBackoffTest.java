package com.suanla.relayq.core.retry;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExponentialJitterBackoffTest {

    @Test
    void delayGrowsExponentiallyAndStopsAtMaximum() {
        ExponentialJitterBackoff backoff = new ExponentialJitterBackoff(
                100L, 2.0D, 1_000L, 0.0D);

        assertEquals(Duration.ofMillis(100L), backoff.nextDelay(1));
        assertEquals(Duration.ofMillis(200L), backoff.nextDelay(2));
        assertEquals(Duration.ofMillis(400L), backoff.nextDelay(3));
        assertEquals(Duration.ofMillis(800L), backoff.nextDelay(4));
        assertEquals(Duration.ofMillis(1_000L), backoff.nextDelay(5));
        assertEquals(Duration.ofMillis(1_000L), backoff.nextDelay(20));
    }

    @Test
    void jitterStaysWithinConfiguredRange() {
        ExponentialJitterBackoff lowerBound = new ExponentialJitterBackoff(
                1_000L, 2.0D, 10_000L, 0.2D, () -> 0.0D);
        ExponentialJitterBackoff upperBound = new ExponentialJitterBackoff(
                1_000L, 2.0D, 10_000L, 0.2D, () -> Math.nextDown(1.0D));

        long lowerDelay = lowerBound.nextDelay(2).toMillis();
        long upperDelay = upperBound.nextDelay(2).toMillis();

        assertEquals(1_600L, lowerDelay);
        assertTrue(upperDelay >= 2_399L && upperDelay <= 2_400L);
    }

    @Test
    void positiveJitterNeverBreaksMaximumDelay() {
        ExponentialJitterBackoff backoff = new ExponentialJitterBackoff(
                100L, 3.0D, 1_000L, 0.5D, () -> Math.nextDown(1.0D));

        assertEquals(1_000L, backoff.nextDelay(100).toMillis());
    }

    @Test
    void hugeRetryNumberDoesNotOverflow() {
        ExponentialJitterBackoff backoff = new ExponentialJitterBackoff(
                Long.MAX_VALUE / 4L,
                Double.MAX_VALUE,
                Long.MAX_VALUE,
                0.0D);

        Duration delay = backoff.nextDelay(Integer.MAX_VALUE);

        assertEquals(Long.MAX_VALUE, delay.toMillis());
    }
}
