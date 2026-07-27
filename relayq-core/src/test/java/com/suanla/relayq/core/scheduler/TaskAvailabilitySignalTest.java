package com.suanla.relayq.core.scheduler;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaskAvailabilitySignalTest {

    @Test
    void consecutiveSignalsCoalesceIntoOneWake() throws Exception {
        CoalescingTaskAvailabilitySignal signal = new CoalescingTaskAvailabilitySignal();
        long observedGeneration = signal.generation();

        signal.signal();
        signal.signal();
        signal.signal();

        long currentGeneration = signal.awaitChange(
                observedGeneration, 0L, TimeUnit.MILLISECONDS);
        assertEquals(3L, currentGeneration);

        ExecutorService executor = Executors.newSingleThreadExecutor();
        CountDownLatch waiting = new CountDownLatch(1);
        try {
            Future<Long> secondWait = executor.submit(() -> {
                waiting.countDown();
                return signal.awaitChange(
                        currentGeneration, 10L, TimeUnit.SECONDS);
            });
            assertTrue(waiting.await(1L, TimeUnit.SECONDS));
            assertThrows(TimeoutException.class, () -> secondWait.get(
                    100L, TimeUnit.MILLISECONDS));
            secondWait.cancel(true);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void signalBeforeWaitIsNotLost() throws InterruptedException {
        CoalescingTaskAvailabilitySignal signal = new CoalescingTaskAvailabilitySignal();
        long observedGeneration = signal.generation();

        signal.signal();

        assertEquals(
                observedGeneration + 1L,
                signal.awaitChange(observedGeneration, 0L, TimeUnit.MILLISECONDS));
    }
}
