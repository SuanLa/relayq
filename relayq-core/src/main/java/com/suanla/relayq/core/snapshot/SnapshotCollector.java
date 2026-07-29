package com.suanla.relayq.core.snapshot;

import com.suanla.relayq.core.config.RelayqProperties;
import com.suanla.relayq.core.metrics.RelayqMetrics;

import java.lang.management.LockInfo;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.lang.management.MonitorInfo;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

public class SnapshotCollector {

    private final ThreadMXBean threadMxBean;
    private final MemoryMXBean memoryMxBean;
    private final RelayqMetrics metrics;
    private final int lockInfoThreadLimit;
    private final long collectTimeoutNanos;
    private final LongSupplier nanoTime;

    public SnapshotCollector(
            RelayqProperties.Snapshot properties,
            RelayqMetrics metrics) {
        this(
                ManagementFactory.getThreadMXBean(),
                ManagementFactory.getMemoryMXBean(),
                properties,
                metrics,
                System::nanoTime);
    }

    SnapshotCollector(
            ThreadMXBean threadMxBean,
            MemoryMXBean memoryMxBean,
            RelayqProperties.Snapshot properties,
            RelayqMetrics metrics,
            LongSupplier nanoTime) {
        this.threadMxBean = Objects.requireNonNull(threadMxBean, "threadMxBean must not be null");
        this.memoryMxBean = Objects.requireNonNull(memoryMxBean, "memoryMxBean must not be null");
        Objects.requireNonNull(properties, "snapshot properties must not be null");
        if (properties.getLockInfoThreadLimit() < 0) {
            throw new IllegalArgumentException(
                    "snapshot lockInfoThreadLimit must not be negative");
        }
        if (properties.getCollectTimeoutMs() < 1L) {
            throw new IllegalArgumentException("snapshot collectTimeoutMs must be at least 1");
        }
        this.metrics = metrics == null ? RelayqMetrics.noop() : metrics;
        this.lockInfoThreadLimit = properties.getLockInfoThreadLimit();
        this.collectTimeoutNanos = TimeUnit.MILLISECONDS.toNanos(
                properties.getCollectTimeoutMs());
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime must not be null");
    }

    public SnapshotCapture collect(SnapshotTrigger trigger) {
        Objects.requireNonNull(trigger, "trigger must not be null");
        long startedAt = nanoTime.getAsLong();
        boolean includeLockInfo = threadMxBean.getThreadCount() <= lockInfoThreadLimit;
        ThreadInfo[] threads = threadMxBean.dumpAllThreads(
                includeLockInfo,
                includeLockInfo);
        enforceTimeout(startedAt);

        String threadDump = renderThreadDump(threads);
        enforceTimeout(startedAt);

        Map<String, Object> poolState = new LinkedHashMap<>(metrics.currentPoolStates());
        poolState.put("taskContext", taskContext(trigger));
        enforceTimeout(startedAt);

        MemoryUsage heap = memoryMxBean.getHeapMemoryUsage();
        Map<String, Long> heapState = new LinkedHashMap<>();
        heapState.put("init", heap.getInit());
        heapState.put("used", heap.getUsed());
        heapState.put("committed", heap.getCommitted());
        heapState.put("max", heap.getMax());
        enforceTimeout(startedAt);

        long backlogCount = metrics.pendingBacklogCount();
        enforceTimeout(startedAt);
        return new SnapshotCapture(
                trigger,
                threadDump,
                includeLockInfo,
                Map.copyOf(poolState),
                Map.copyOf(heapState),
                backlogCount);
    }

    private Map<String, Object> taskContext(SnapshotTrigger trigger) {
        Map<String, Object> context = new LinkedHashMap<>();
        putIfNotNull(context, "taskId", trigger.taskId());
        putIfNotNull(context, "executeLogId", trigger.executeLogId());
        putIfNotNull(context, "attemptNo", trigger.attemptNo());
        context.put("triggerType", trigger.triggerType().name());
        putIfNotNull(context, "handlerName", trigger.handlerName());
        putIfNotNull(context, "retryCount", trigger.retryCount());
        putIfNotNull(context, "maxRetry", trigger.maxRetry());
        if (trigger.failureKind() != null) {
            context.put("failureKind", trigger.failureKind().name());
        }
        putIfNotNull(context, "errorMessage", trigger.errorMessage());
        return Map.copyOf(context);
    }

    private void putIfNotNull(Map<String, Object> target, String key, Object value) {
        if (value != null) {
            target.put(key, value);
        }
    }

    private String renderThreadDump(ThreadInfo[] threads) {
        if (threads == null || threads.length == 0) {
            return "";
        }
        StringBuilder dump = new StringBuilder(Math.max(1_024, threads.length * 512));
        for (ThreadInfo thread : threads) {
            if (thread == null) {
                continue;
            }
            dump.append('"')
                    .append(thread.getThreadName())
                    .append("\" Id=")
                    .append(thread.getThreadId())
                    .append(' ')
                    .append(thread.getThreadState())
                    .append('\n');
            LockInfo waitingOn = thread.getLockInfo();
            if (waitingOn != null) {
                dump.append("    waiting on ")
                        .append(waitingOn)
                        .append('\n');
            }

            StackTraceElement[] stack = thread.getStackTrace();
            MonitorInfo[] monitors = thread.getLockedMonitors();
            for (int index = 0; index < stack.length; index++) {
                dump.append("\tat ").append(stack[index]).append('\n');
                for (MonitorInfo monitor : monitors) {
                    if (monitor.getLockedStackDepth() == index) {
                        dump.append("\t- locked ").append(monitor).append('\n');
                    }
                }
            }

            LockInfo[] synchronizers = thread.getLockedSynchronizers();
            if (synchronizers.length > 0) {
                dump.append("\n\tLocked ownable synchronizers:\n");
                for (LockInfo synchronizer : synchronizers) {
                    dump.append("\t- ").append(synchronizer).append('\n');
                }
            }
            dump.append('\n');
        }
        return dump.toString();
    }

    private void enforceTimeout(long startedAt) {
        if (Thread.currentThread().isInterrupted()) {
            throw new SnapshotCollectionTimeoutException("snapshot collection was interrupted");
        }
        if (nanoTime.getAsLong() - startedAt > collectTimeoutNanos) {
            /*
             * ThreadMXBean 的 native dump 无法被安全强杀；超时采用协作式截止线，
             * dump 一返回就中止后续序列化与落库，避免继续放大已经发生的停顿。
             */
            throw new SnapshotCollectionTimeoutException(
                    "snapshot collection exceeded the configured timeout");
        }
    }

    private static final class SnapshotCollectionTimeoutException extends RuntimeException {

        private SnapshotCollectionTimeoutException(String message) {
            super(message);
        }
    }
}
