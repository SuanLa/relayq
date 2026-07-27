package com.suanla.relayq.core.executor;

import com.suanla.relayq.core.config.RelayqProperties;
import com.suanla.relayq.core.service.TaskStateMachine;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TaskWorkerPoolTest {

    private final List<AutoCloseable> closeables = new ArrayList<>();

    @AfterEach
    void tearDown() throws Exception {
        for (int index = closeables.size() - 1; index >= 0; index--) {
            closeables.get(index).close();
        }
    }

    @Test
    void concurrentReservationsNeverExceedCapacity() throws Exception {
        PoolFixture fixture = fixture(2, 4, 8);
        ExecutorService callers = Executors.newFixedThreadPool(16);
        closeables.add(() -> {
            callers.shutdownNow();
            callers.awaitTermination(5, TimeUnit.SECONDS);
        });
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger granted = new AtomicInteger();
        List<Runnable> attempts = new ArrayList<>();
        for (int index = 0; index < 64; index++) {
            attempts.add(() -> {
                await(start);
                granted.addAndGet(fixture.pool().tryReserve(1));
            });
        }

        for (Runnable attempt : attempts) {
            callers.submit(attempt);
        }
        start.countDown();
        callers.shutdown();
        assertTrue(callers.awaitTermination(5, TimeUnit.SECONDS));

        assertEquals(12, granted.get());
        assertEquals(12, fixture.pool().getReservedCount());
        assertEquals(0, fixture.pool().getAvailableCapacity());
        fixture.pool().releaseReservations(granted.get());
        assertEquals(0, fixture.pool().getReservedCount());
    }

    @Test
    void completionReturnsEveryReservationAcrossRepeatedRuns() throws Exception {
        PoolFixture fixture = fixture(2, 2, 2);

        for (int round = 0; round < 50; round++) {
            int granted = fixture.pool().tryReserve(4);
            assertTrue(granted > 0 && granted <= 4);
            CountDownLatch completed = new CountDownLatch(granted);
            CountDownLatch releaseTasks = new CountDownLatch(1);
            List<TaskIdentifiedRunnable> tasks = new ArrayList<>();
            for (int index = 0; index < granted; index++) {
                long taskId = round * 10L + index;
                tasks.add(new TaskExecutionRunnable(taskId, () -> {
                    await(releaseTasks);
                    completed.countDown();
                }));
            }

            assertEquals(
                    granted,
                    fixture.pool().submitReservedBatch(tasks),
                    "unexpected rejection in round " + round);
            releaseTasks.countDown();
            assertTrue(completed.await(5, TimeUnit.SECONDS));
            awaitReservations(fixture.pool(), 0);
            awaitPositiveCapacity(fixture.pool());
        }
    }

    @Test
    void firstRejectionStopsBatchAndReturnsAllReservations() {
        PoolFixture fixture = fixture(1, 1, 1);
        assertEquals(2, fixture.pool().tryReserve(2));
        fixture.pool().stop();
        List<TaskIdentifiedRunnable> tasks = List.of(
                new TaskExecutionRunnable(101L, () -> {
                }),
                new TaskExecutionRunnable(102L, () -> {
                }));

        assertEquals(0, fixture.pool().submitReservedBatch(tasks));

        awaitReservations(fixture.pool(), 0);
        verify(fixture.stateMachine(), timeout(2_000).times(1))
                .requeueRejected(anyCollection(), eq("test-owner"));
    }

    private PoolFixture fixture(int coreSize, int maxSize, int queueCapacity) {
        RelayqProperties.Worker worker = new RelayqProperties.Worker();
        worker.setCoreSize(coreSize);
        worker.setMaxSize(maxSize);
        worker.setQueueCapacity(queueCapacity);
        worker.setShutdownGraceSeconds(0L);
        TaskStateMachine stateMachine = mock(TaskStateMachine.class);
        when(stateMachine.requeueRejected(anyCollection(), eq("test-owner")))
                .thenAnswer(invocation -> invocation.<List<Long>>getArgument(0).size());
        RequeueRejectedHandler rejectedHandler = new RequeueRejectedHandler(
                stateMachine, "test-owner", 4, 1L);
        TaskWorkerPool pool = new TaskWorkerPool(worker, rejectedHandler);
        closeables.add(rejectedHandler);
        closeables.add(pool);
        return new PoolFixture(pool, stateMachine);
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("latch timed out");
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted while waiting for latch", error);
        }
    }

    private static void awaitReservations(TaskWorkerPool pool, int expected) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (pool.getReservedCount() != expected && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        assertEquals(expected, pool.getReservedCount());
    }

    private static void awaitPositiveCapacity(TaskWorkerPool pool) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (pool.getAvailableCapacity() == 0 && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        assertTrue(pool.getAvailableCapacity() > 0);
    }

    private record PoolFixture(TaskWorkerPool pool, TaskStateMachine stateMachine) {
    }
}
