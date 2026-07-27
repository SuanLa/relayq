package com.suanla.relayq.core.retry;

import com.suanla.relayq.core.domain.FailureKind;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RetryDeciderTest {

    private final RetryDecider decider = new RetryDecider(
            retryNumber -> Duration.ofSeconds(retryNumber));

    @ParameterizedTest
    @EnumSource(
            value = FailureKind.class,
            names = {
                    "HANDLER_NOT_FOUND",
                    "PARAM_DESERIALIZE_FAILED",
                    "VALIDATION_FAILED"
            })
    void poisonTaskGoesDeadImmediately(FailureKind failureKind) {
        RetryDecision decision = decider.decide(failureKind, 0, 10);

        assertTrue(decision.isDead());
        assertFalse(decision.shouldRetry());
        assertEquals(0L, decision.delayMillis());
    }

    @Test
    void retryableFailureGetsDelayBelowLimit() {
        RetryDecision decision = decider.decide(FailureKind.BUSINESS_ERROR, 2, 3);

        assertTrue(decision.shouldRetry());
        assertEquals(3_000L, decision.delayMillis());
    }

    @Test
    void retryableFailureGoesDeadAtLimit() {
        RetryDecision decision = decider.decide(FailureKind.HANDLER_TIMEOUT, 3, 3);

        assertTrue(decision.isDead());
        assertEquals(0L, decision.delayMillis());
    }
}
