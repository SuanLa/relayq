package com.suanla.relayq.core.support;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SnowflakeIdGeneratorTest {

    @Test
    void shouldBeStrictlyIncreasingInSingleThread() {
        SnowflakeIdGenerator generator = new SnowflakeIdGenerator(1);
        long previous = generator.nextId();

        for (int i = 0; i < 10_000; i++) {
            long current = generator.nextId();
            assertTrue(current > previous);
            previous = current;
        }
    }

    @Test
    void shouldNotGenerateDuplicatesConcurrently() throws Exception {
        int threadCount = 8;
        int idsPerThread = 5_000;
        SnowflakeIdGenerator generator = new SnowflakeIdGenerator(2);
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        List<Callable<List<Long>>> tasks = new ArrayList<>();
        for (int i = 0; i < threadCount; i++) {
            tasks.add(() -> {
                List<Long> ids = new ArrayList<>(idsPerThread);
                for (int j = 0; j < idsPerThread; j++) {
                    ids.add(generator.nextId());
                }
                return ids;
            });
        }

        try {
            Set<Long> uniqueIds = new HashSet<>();
            for (Future<List<Long>> future : executor.invokeAll(tasks)) {
                uniqueIds.addAll(future.get());
            }
            assertEquals(threadCount * idsPerThread, uniqueIds.size());
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void shouldRejectLargeClockRollback() {
        long now = System.currentTimeMillis();
        long[] timestamps = {now, now - 10};
        AtomicInteger index = new AtomicInteger();
        LongSupplier clock = () -> timestamps[Math.min(index.getAndIncrement(), timestamps.length - 1)];
        SnowflakeIdGenerator generator = new SnowflakeIdGenerator(3, 5, clock);

        generator.nextId();

        assertThrows(IllegalStateException.class, generator::nextId);
    }
}
