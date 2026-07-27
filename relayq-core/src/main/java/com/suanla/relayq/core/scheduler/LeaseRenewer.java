package com.suanla.relayq.core.scheduler;

import com.suanla.relayq.core.config.RelayqProperties;
import com.suanla.relayq.core.executor.NamedThreadFactory;
import com.suanla.relayq.core.mapper.TaskInfoMapper;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
public class LeaseRenewer implements AutoCloseable {

    private static final long STOP_WAIT_SECONDS = 5L;

    private final TaskInfoMapper taskInfoMapper;
    private final String owner;
    private final long leaseSeconds;
    private final long renewIntervalMs;
    private final Set<Long> runningTaskIds = ConcurrentHashMap.newKeySet();
    private final ScheduledThreadPoolExecutor executor;
    private final AtomicBoolean started = new AtomicBoolean();

    public LeaseRenewer(
            TaskInfoMapper taskInfoMapper,
            WorkerIdentity workerIdentity,
            RelayqProperties.Lease properties) {
        this(
                taskInfoMapper,
                Objects.requireNonNull(workerIdentity, "workerIdentity must not be null").value(),
                properties);
    }

    public LeaseRenewer(
            TaskInfoMapper taskInfoMapper,
            String owner,
            RelayqProperties.Lease properties) {
        this.taskInfoMapper = Objects.requireNonNull(
                taskInfoMapper, "taskInfoMapper must not be null");
        if (owner == null || owner.isBlank()) {
            throw new IllegalArgumentException("owner must not be blank");
        }
        Objects.requireNonNull(properties, "lease properties must not be null");
        if (properties.getTtlSeconds() < 1L) {
            throw new IllegalArgumentException("lease ttlSeconds must be at least 1");
        }
        if (properties.getRenewIntervalDivisor() < 1) {
            throw new IllegalArgumentException("lease renewIntervalDivisor must be at least 1");
        }
        this.owner = owner;
        this.leaseSeconds = properties.getTtlSeconds();
        this.renewIntervalMs = Math.max(
                1L,
                TimeUnit.SECONDS.toMillis(leaseSeconds)
                        / properties.getRenewIntervalDivisor());
        this.executor = new ScheduledThreadPoolExecutor(
                1,
                new NamedThreadFactory("relayq-lease-renewer-"));
        this.executor.setRemoveOnCancelPolicy(true);
    }

    public void register(long taskId) {
        runningTaskIds.add(taskId);
    }

    public void unregister(long taskId) {
        runningTaskIds.remove(taskId);
    }

    public int getRegisteredTaskCount() {
        return runningTaskIds.size();
    }

    public void start() {
        if (!started.compareAndSet(false, true)) {
            return;
        }
        executor.scheduleWithFixedDelay(
                this::renewSafely,
                renewIntervalMs,
                renewIntervalMs,
                TimeUnit.MILLISECONDS);
    }

    public int renewOnce() {
        List<Long> taskIds = List.copyOf(runningTaskIds);
        if (taskIds.isEmpty()) {
            return 0;
        }
        int affected = taskInfoMapper.renewLease(taskIds, owner, leaseSeconds);
        if (affected < taskIds.size()) {
            log.warn(
                    "Lease renewal affected fewer tasks than requested: requested={}, affected={}, owner={}",
                    taskIds.size(),
                    affected,
                    owner);
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
            log.warn("Interrupted while stopping lease renewer: owner={}", owner, error);
        }
    }

    @Override
    public void close() {
        stop();
    }

    private void renewSafely() {
        try {
            renewOnce();
        } catch (RuntimeException error) {
            // 定时任务抛出会让后续调度永久取消，因此必须在边界内吞掉并记录。
            log.warn("Lease renewal failed; the renewer will continue: owner={}", owner, error);
        }
    }
}
