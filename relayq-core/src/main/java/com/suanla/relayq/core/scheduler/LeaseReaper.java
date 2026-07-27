package com.suanla.relayq.core.scheduler;

import com.suanla.relayq.core.config.RelayqProperties;
import com.suanla.relayq.core.executor.NamedThreadFactory;
import com.suanla.relayq.core.mapper.TaskInfoMapper;
import com.suanla.relayq.core.metrics.RelayqMetrics;
import lombok.extern.slf4j.Slf4j;

import java.util.Objects;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
public class LeaseReaper implements AutoCloseable {

    private static final long STOP_WAIT_SECONDS = 5L;

    private final TaskInfoMapper taskInfoMapper;
    private final int batchSize;
    private final long intervalMs;
    private final ScheduledThreadPoolExecutor executor;
    private final AtomicBoolean started = new AtomicBoolean();
    private final RelayqMetrics metrics;

    public LeaseReaper(TaskInfoMapper taskInfoMapper, RelayqProperties.Lease properties) {
        this(taskInfoMapper, properties, RelayqMetrics.noop());
    }

    public LeaseReaper(
            TaskInfoMapper taskInfoMapper,
            RelayqProperties.Lease properties,
            RelayqMetrics metrics) {
        this.taskInfoMapper = Objects.requireNonNull(
                taskInfoMapper, "taskInfoMapper must not be null");
        Objects.requireNonNull(properties, "lease properties must not be null");
        if (properties.getReaperBatchSize() < 1) {
            throw new IllegalArgumentException("lease reaperBatchSize must be at least 1");
        }
        if (properties.getReaperIntervalMs() < 1L) {
            throw new IllegalArgumentException("lease reaperIntervalMs must be at least 1");
        }
        this.batchSize = properties.getReaperBatchSize();
        this.intervalMs = properties.getReaperIntervalMs();
        this.metrics = metrics == null ? RelayqMetrics.noop() : metrics;
        this.executor = new ScheduledThreadPoolExecutor(
                1,
                new NamedThreadFactory("relayq-lease-reaper-"));
        this.executor.setRemoveOnCancelPolicy(true);
    }

    public void start() {
        if (!started.compareAndSet(false, true)) {
            return;
        }
        executor.scheduleWithFixedDelay(
                this::reapSafely,
                intervalMs,
                intervalMs,
                TimeUnit.MILLISECONDS);
    }

    public int reapOnce() {
        int affected = taskInfoMapper.reclaimExpiredLeases(batchSize);
        if (affected > 0) {
            metrics.recordLeaseReclaimed(affected);
            log.info("Expired leases reclaimed: count={}", affected);
        }
        return affected;
    }

    public void stop() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(STOP_WAIT_SECONDS, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
            log.warn("Interrupted while stopping lease reaper", error);
        }
    }

    @Override
    public void close() {
        stop();
    }

    private void reapSafely() {
        try {
            reapOnce();
        } catch (RuntimeException error) {
            // ScheduledExecutorService 会在未捕获异常后停止该周期任务。
            log.warn("Lease reaping failed; the reaper will continue", error);
        }
    }
}
