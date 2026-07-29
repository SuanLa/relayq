package com.suanla.relayq.core.snapshot;

import com.suanla.relayq.core.config.RelayqProperties;
import com.suanla.relayq.core.executor.NamedThreadFactory;
import com.suanla.relayq.core.metrics.PoolMetricsSource;
import com.suanla.relayq.core.metrics.RelayqMetrics;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

@Slf4j
public class SnapshotAdmission
        implements SnapshotTriggerHook, PoolMetricsSource, AutoCloseable {

    private static final int MAX_TRACKED_ENTRIES = 10_000;

    @Getter
    private final boolean enabled;
    private final SnapshotCollector collector;
    private final SnapshotWriter writer;
    private final RelayqMetrics metrics;
    private final long cooldownNanos;
    private final LongSupplier nanoTime;
    private final ThreadPoolExecutor executor;
    private final TokenBucket tokenBucket;
    private final Map<SnapshotKey, Boolean> admittedKeys;
    private final Map<Long, Long> lastSnapshotByTask;

    public SnapshotAdmission(
            RelayqProperties.Snapshot properties,
            SnapshotCollector collector,
            SnapshotWriter writer) {
        this(properties, collector, writer, RelayqMetrics.noop());
    }

    public SnapshotAdmission(
            RelayqProperties.Snapshot properties,
            SnapshotCollector collector,
            SnapshotWriter writer,
            RelayqMetrics metrics) {
        this(properties, collector, writer, metrics, System::nanoTime);
    }

    SnapshotAdmission(
            RelayqProperties.Snapshot properties,
            SnapshotCollector collector,
            SnapshotWriter writer,
            RelayqMetrics metrics,
            LongSupplier nanoTime) {
        Objects.requireNonNull(properties, "snapshot properties must not be null");
        this.enabled = properties.isEnabled();
        this.metrics = metrics == null ? RelayqMetrics.noop() : metrics;
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime must not be null");
        if (!enabled) {
            this.collector = null;
            this.writer = null;
            this.cooldownNanos = 0L;
            this.executor = null;
            this.tokenBucket = null;
            this.admittedKeys = Map.of();
            this.lastSnapshotByTask = Map.of();
            return;
        }

        validate(properties);
        this.collector = Objects.requireNonNull(collector, "collector must not be null");
        this.writer = Objects.requireNonNull(writer, "writer must not be null");
        this.cooldownNanos = TimeUnit.SECONDS.toNanos(properties.getCooldownSeconds());
        this.tokenBucket = new TokenBucket(properties.getRatePerMinute(), nanoTime);
        /*
         * 两张表都只记录真正获准的现场，并以固定上限 LRU 淘汰。
         * 令牌桶让新增速度很低，10k 足以覆盖长冷却窗口，同时从结构上杜绝任务量带来的无界增长。
         */
        this.admittedKeys = boundedLruMap();
        this.lastSnapshotByTask = boundedLruMap();
        this.executor = new ThreadPoolExecutor(
                1,
                1,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(properties.getQueueCapacity()),
                new NamedThreadFactory("relayq-snapshot-"),
                new SnapshotDiscardPolicy());
        this.metrics.bindPool("snapshot", this);
    }

    @Override
    public void trigger(SnapshotTrigger trigger) {
        admit(trigger);
    }

    public void admit(SnapshotTrigger trigger) {
        if (!enabled) {
            return;
        }
        Objects.requireNonNull(trigger, "trigger must not be null");
        executor.execute(new AdmissionCommand(trigger));
    }

    public void triggerManual(Long taskId, Integer attemptNo) {
        admit(SnapshotTrigger.manual(taskId, attemptNo));
    }

    @Override
    public int getActiveCount() {
        return executor == null ? 0 : executor.getActiveCount();
    }

    @Override
    public int getQueueSize() {
        return executor == null ? 0 : executor.getQueue().size();
    }

    @Override
    public int getQueueRemainingCapacity() {
        return executor == null ? 0 : executor.getQueue().remainingCapacity();
    }

    @Override
    public long getCompletedTaskCount() {
        return executor == null ? 0L : executor.getCompletedTaskCount();
    }

    public void stop() {
        if (executor == null) {
            return;
        }
        List<Runnable> dropped = executor.shutdownNow();
        for (int index = 0; index < dropped.size(); index++) {
            metrics.recordSnapshot(RelayqMetrics.SnapshotOutcome.DROPPED);
        }
    }

    @Override
    public void close() {
        stop();
    }

    private void runPipeline(SnapshotTrigger trigger) {
        long now = nanoTime.getAsLong();
        SnapshotKey key = new SnapshotKey(trigger.taskId(), trigger.attemptNo());
        if (admittedKeys.containsKey(key)) {
            metrics.recordSnapshot(RelayqMetrics.SnapshotOutcome.THROTTLED);
            return;
        }
        if (isCoolingDown(trigger.taskId(), now)) {
            metrics.recordSnapshot(RelayqMetrics.SnapshotOutcome.THROTTLED);
            return;
        }
        if (!tokenBucket.tryAcquire()) {
            metrics.recordSnapshot(RelayqMetrics.SnapshotOutcome.THROTTLED);
            return;
        }

        admittedKeys.put(key, Boolean.TRUE);
        if (trigger.taskId() != null) {
            lastSnapshotByTask.put(trigger.taskId(), now);
        }
        try {
            SnapshotCapture capture = collector.collect(trigger);
            RelayqMetrics.SnapshotOutcome outcome = writer.write(capture)
                    ? RelayqMetrics.SnapshotOutcome.CAPTURED
                    : RelayqMetrics.SnapshotOutcome.FAILED;
            metrics.recordSnapshot(outcome);
        } catch (RuntimeException error) {
            metrics.recordSnapshot(RelayqMetrics.SnapshotOutcome.FAILED);
            log.error(
                    "Snapshot collection failed: taskId={}, attemptNo={}, triggerType={}",
                    trigger.taskId(),
                    trigger.attemptNo(),
                    trigger.triggerType(),
                    error);
        }
    }

    private boolean isCoolingDown(Long taskId, long now) {
        if (taskId == null || cooldownNanos == 0L) {
            return false;
        }
        Long previous = lastSnapshotByTask.get(taskId);
        return previous != null && now - previous < cooldownNanos;
    }

    private <K, V> Map<K, V> boundedLruMap() {
        return new LinkedHashMap<>(128, 0.75F, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
                return size() > MAX_TRACKED_ENTRIES;
            }
        };
    }

    private void validate(RelayqProperties.Snapshot properties) {
        if (properties.getQueueCapacity() < 1) {
            throw new IllegalArgumentException("snapshot queueCapacity must be at least 1");
        }
        if (properties.getCooldownSeconds() < 0L) {
            throw new IllegalArgumentException(
                    "snapshot cooldownSeconds must not be negative");
        }
        if (properties.getRatePerMinute() < 1) {
            throw new IllegalArgumentException("snapshot ratePerMinute must be at least 1");
        }
    }

    private record SnapshotKey(Long taskId, Integer attemptNo) {
    }

    private final class AdmissionCommand implements Runnable {

        private final SnapshotTrigger trigger;

        private AdmissionCommand(SnapshotTrigger trigger) {
            this.trigger = trigger;
        }

        @Override
        public void run() {
            runPipeline(trigger);
        }
    }

    private final class SnapshotDiscardPolicy extends ThreadPoolExecutor.DiscardPolicy {

        @Override
        public void rejectedExecution(Runnable command, ThreadPoolExecutor executor) {
            super.rejectedExecution(command, executor);
            metrics.recordSnapshot(RelayqMetrics.SnapshotOutcome.DROPPED);
        }
    }

    private static final class TokenBucket {

        private static final long ONE_MINUTE_NANOS = TimeUnit.MINUTES.toNanos(1L);

        private final int capacity;
        private final double tokensPerNano;
        private final LongSupplier nanoTime;

        private double tokens;
        private long lastRefillNanos;

        private TokenBucket(int ratePerMinute, LongSupplier nanoTime) {
            this.capacity = ratePerMinute;
            this.tokensPerNano = (double) ratePerMinute / ONE_MINUTE_NANOS;
            this.nanoTime = nanoTime;
            this.tokens = ratePerMinute;
            this.lastRefillNanos = nanoTime.getAsLong();
        }

        private boolean tryAcquire() {
            long now = nanoTime.getAsLong();
            long elapsed = Math.max(0L, now - lastRefillNanos);
            if (elapsed > 0L) {
                tokens = Math.min(capacity, tokens + elapsed * tokensPerNano);
                lastRefillNanos = now;
            }
            if (tokens < 1.0D) {
                return false;
            }
            tokens -= 1.0D;
            return true;
        }
    }
}
