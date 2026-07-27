package com.suanla.relayq.core.scheduler;

import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * 提交侧与本实例 puller 之间的本地可用任务信号。
 */
public interface TaskAvailabilitySignal {

    TaskAvailabilitySignal NOOP = new TaskAvailabilitySignal() {

        @Override
        public void signal() {
        }

        @Override
        public long generation() {
            return 0L;
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
            timeUnit.sleep(timeout);
            return 0L;
        }
    };

    void signal();

    long generation();

    /**
     * 等待 generation 变化或超时，并返回当前 generation。
     */
    long awaitChange(
            long observedGeneration,
            long timeout,
            TimeUnit timeUnit) throws InterruptedException;

    static TaskAvailabilitySignal noop() {
        return NOOP;
    }
}
