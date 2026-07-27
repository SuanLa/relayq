package com.suanla.relayq.core.scheduler;

import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 基于 generation 的单实例任务可用信号。
 *
 * <p>信号只推进计数而不排队，因此连续提交不会积累无界事件；等待方用上次观察到的
 * generation 判断变化，即使 signal 先于 await 发生也不会丢失唤醒。
 */
public class CoalescingTaskAvailabilitySignal implements TaskAvailabilitySignal {

    private final ReentrantLock lock = new ReentrantLock();
    private final Condition changed = lock.newCondition();

    private volatile long generation;

    @Override
    public void signal() {
        lock.lock();
        try {
            generation++;
            changed.signal();
        } finally {
            lock.unlock();
        }
    }

    @Override
    public long generation() {
        return generation;
    }

    @Override
    public long awaitChange(
            long observedGeneration,
            long timeout,
            TimeUnit timeUnit) throws InterruptedException {
        Objects.requireNonNull(timeUnit, "timeUnit must not be null");
        if (timeout < 0L) {
            throw new IllegalArgumentException("timeout must not be negative");
        }
        long remainingNanos = timeUnit.toNanos(timeout);
        lock.lockInterruptibly();
        try {
            while (generation == observedGeneration && remainingNanos > 0L) {
                remainingNanos = changed.awaitNanos(remainingNanos);
            }
            return generation;
        } finally {
            lock.unlock();
        }
    }
}
