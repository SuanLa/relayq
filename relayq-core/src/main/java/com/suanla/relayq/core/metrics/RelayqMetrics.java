package com.suanla.relayq.core.metrics;

import com.suanla.relayq.core.config.RelayqProperties;
import com.suanla.relayq.core.domain.TaskStatus;
import com.suanla.relayq.core.mapper.TaskInfoMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;

import java.lang.ref.WeakReference;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.LongSupplier;

@Slf4j
public class RelayqMetrics {

    private static final RelayqMetrics NOOP = new RelayqMetrics();

    private final MeterRegistry registry;
    private final BacklogProvider backlogProvider;
    private final long backlogCacheNanos;
    private final LongSupplier nanoTime;
    private final Object backlogLock = new Object();
    private final List<BacklogGaugeState> backlogGaugeStates;
    private final ConcurrentMap<String, PoolGaugeState> poolStates = new ConcurrentHashMap<>();
    private final ConcurrentMap<ExecutionKey, Timer> executionTimers = new ConcurrentHashMap<>();
    private final PullRatioState pullRatioState = new PullRatioState();
    private final Timer pullDuration;
    private final DistributionSummary pullBatchSize;
    private final Counter taskRejected;
    private final Counter leaseReclaimed;
    private final Counter leaseLost;
    private final Map<SnapshotOutcome, Counter> snapshotCounters;

    private volatile BacklogSnapshot backlogSnapshot;

    public RelayqMetrics() {
        this.registry = null;
        this.backlogProvider = () -> Map.of();
        this.backlogCacheNanos = TimeUnit.SECONDS.toNanos(1L);
        this.nanoTime = System::nanoTime;
        this.backlogGaugeStates = List.of();
        this.pullDuration = null;
        this.pullBatchSize = null;
        this.taskRejected = null;
        this.leaseReclaimed = null;
        this.leaseLost = null;
        this.snapshotCounters = Map.of();
    }

    public RelayqMetrics(
            MeterRegistry registry,
            TaskInfoMapper taskInfoMapper,
            RelayqProperties.Metrics properties) {
        this(
                registry,
                mapperBacklogProvider(taskInfoMapper),
                properties,
                System::nanoTime);
    }

    public RelayqMetrics(
            MeterRegistry registry,
            BacklogProvider backlogProvider,
            RelayqProperties.Metrics properties) {
        this(registry, backlogProvider, properties, System::nanoTime);
    }

    RelayqMetrics(
            MeterRegistry registry,
            BacklogProvider backlogProvider,
            RelayqProperties.Metrics properties,
            LongSupplier nanoTime) {
        this.registry = registry;
        this.backlogProvider = Objects.requireNonNull(
                backlogProvider, "backlogProvider must not be null");
        Objects.requireNonNull(properties, "metrics properties must not be null");
        if (properties.getBacklogCacheSeconds() < 1L) {
            throw new IllegalArgumentException("metrics backlogCacheSeconds must be at least 1");
        }
        this.backlogCacheNanos = TimeUnit.SECONDS.toNanos(
                properties.getBacklogCacheSeconds());
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime must not be null");

        if (registry == null) {
            this.backlogGaugeStates = List.of();
            this.pullDuration = null;
            this.pullBatchSize = null;
            this.taskRejected = null;
            this.leaseReclaimed = null;
            this.leaseLost = null;
            this.snapshotCounters = Map.of();
            return;
        }

        this.backlogGaugeStates = registerBacklogGauges(registry);
        this.pullDuration = Timer.builder("relayq.pull.duration").register(registry);
        this.pullBatchSize = DistributionSummary.builder("relayq.pull.batch.size")
                .register(registry);
        Gauge.builder(
                        "relayq.pull.empty.ratio",
                        pullRatioState,
                        PullRatioState::ratio)
                .register(registry);
        this.taskRejected = Counter.builder("relayq.task.rejected").register(registry);
        this.leaseReclaimed = Counter.builder("relayq.lease.reclaimed").register(registry);
        this.leaseLost = Counter.builder("relayq.lease.lost").register(registry);
        EnumMap<SnapshotOutcome, Counter> counters = new EnumMap<>(SnapshotOutcome.class);
        for (SnapshotOutcome outcome : SnapshotOutcome.values()) {
            counters.put(
                    outcome,
                    Counter.builder("relayq.snapshot")
                            .tag("outcome", outcome.tagValue())
                            .register(registry));
        }
        this.snapshotCounters = Map.copyOf(counters);
    }

    public static RelayqMetrics noop() {
        return NOOP;
    }

    public long backlogCount(TaskStatus status) {
        Objects.requireNonNull(status, "status must not be null");
        return currentBacklog().getOrDefault(status, 0L);
    }

    public long pendingBacklogCount() {
        return backlogCount(TaskStatus.PENDING);
    }

    public void bindPool(String pool, PoolMetricsSource source) {
        if (pool == null || pool.isBlank()) {
            throw new IllegalArgumentException("pool must not be blank");
        }
        Objects.requireNonNull(source, "pool metrics source must not be null");
        if (this == NOOP) {
            return;
        }
        PoolGaugeState state = new PoolGaugeState(source);
        PoolGaugeState existing = poolStates.putIfAbsent(pool, state);
        if (existing != null || registry == null) {
            return;
        }
        Gauge.builder("relayq.pool.active", state, PoolGaugeState::active)
                .tag("pool", pool)
                .register(registry);
        Gauge.builder("relayq.pool.queue.size", state, PoolGaugeState::queueSize)
                .tag("pool", pool)
                .register(registry);
        Gauge.builder(
                        "relayq.pool.queue.remaining",
                        state,
                        PoolGaugeState::queueRemaining)
                .tag("pool", pool)
                .register(registry);
        Gauge.builder("relayq.pool.completed", state, PoolGaugeState::completed)
                .tag("pool", pool)
                .register(registry);
    }

    public Map<String, PoolState> currentPoolStates() {
        Map<String, PoolState> snapshots = new LinkedHashMap<>();
        poolStates.forEach((name, state) -> {
            PoolState snapshot = state.snapshot();
            if (snapshot != null) {
                snapshots.put(name, snapshot);
            }
        });
        return Map.copyOf(snapshots);
    }

    public void recordTaskExecution(
            String handler,
            String outcome,
            long durationNanos) {
        if (registry == null) {
            return;
        }
        String safeHandler = handler == null || handler.isBlank() ? "unknown" : handler;
        String safeOutcome = outcome == null || outcome.isBlank() ? "unknown" : outcome;
        ExecutionKey key = new ExecutionKey(safeHandler, safeOutcome);
        Timer timer = executionTimers.computeIfAbsent(
                key,
                ignored -> Timer.builder("relayq.task.execute")
                        .tag("handler", safeHandler)
                        .tag("outcome", safeOutcome)
                        .publishPercentileHistogram()
                        .register(registry));
        timer.record(Math.max(0L, durationNanos), TimeUnit.NANOSECONDS);
    }

    public void recordPull(long durationNanos, int batchSize, boolean queriedDatabase) {
        if (pullDuration != null) {
            pullDuration.record(Math.max(0L, durationNanos), TimeUnit.NANOSECONDS);
            pullBatchSize.record(Math.max(0, batchSize));
        }
        if (queriedDatabase) {
            pullRatioState.record(batchSize == 0);
        }
    }

    public void recordTaskRejected() {
        increment(taskRejected, 1L);
    }

    public void recordLeaseReclaimed(int count) {
        increment(leaseReclaimed, count);
    }

    public void recordLeaseLost() {
        increment(leaseLost, 1L);
    }

    public void recordSnapshot(SnapshotOutcome outcome) {
        Objects.requireNonNull(outcome, "snapshot outcome must not be null");
        increment(snapshotCounters.get(outcome), 1L);
    }

    private List<BacklogGaugeState> registerBacklogGauges(MeterRegistry meterRegistry) {
        List<BacklogGaugeState> states = java.util.Arrays.stream(TaskStatus.values())
                .map(status -> new BacklogGaugeState(this, status))
                .toList();
        for (BacklogGaugeState state : states) {
            Gauge.builder(
                            "relayq.task.backlog",
                            state,
                            BacklogGaugeState::value)
                    .tag("status", state.status().name())
                    .register(meterRegistry);
        }
        /*
         * registry 对 Gauge 状态对象只保留弱引用；这里由 RelayqMetrics 的生命周期托住状态，
         * RelayqMetrics 释放后整组 gauge 也可被回收，不会反向钉住业务组件。
         */
        return states;
    }

    private Map<TaskStatus, Long> currentBacklog() {
        long now = nanoTime.getAsLong();
        BacklogSnapshot current = backlogSnapshot;
        if (current != null && now < current.expiresAtNanos()) {
            return current.counts();
        }
        synchronized (backlogLock) {
            current = backlogSnapshot;
            now = nanoTime.getAsLong();
            if (current != null && now < current.expiresAtNanos()) {
                return current.counts();
            }
            EnumMap<TaskStatus, Long> refreshed = new EnumMap<>(TaskStatus.class);
            for (TaskStatus status : TaskStatus.values()) {
                refreshed.put(status, 0L);
            }
            Map<TaskStatus, Long> loaded = backlogProvider.countByStatus();
            if (loaded != null) {
                loaded.forEach((status, count) -> {
                    if (status != null && count != null) {
                        refreshed.put(status, Math.max(0L, count));
                    }
                });
            }
            Map<TaskStatus, Long> immutable = Map.copyOf(refreshed);
            backlogSnapshot = new BacklogSnapshot(
                    immutable,
                    saturatingAdd(now, backlogCacheNanos));
            return immutable;
        }
    }

    private static BacklogProvider mapperBacklogProvider(TaskInfoMapper taskInfoMapper) {
        Objects.requireNonNull(taskInfoMapper, "taskInfoMapper must not be null");
        return () -> {
            EnumMap<TaskStatus, Long> counts = new EnumMap<>(TaskStatus.class);
            for (TaskStatusCount row : taskInfoMapper.countGroupedByStatus()) {
                if (row != null && row.getStatus() != null && row.getCount() != null) {
                    counts.put(row.getStatus(), row.getCount());
                }
            }
            return counts;
        };
    }

    private static long saturatingAdd(long left, long right) {
        long result = left + right;
        if (((left ^ result) & (right ^ result)) < 0L) {
            return Long.MAX_VALUE;
        }
        return result;
    }

    private static void increment(Counter counter, long amount) {
        if (counter != null && amount > 0L) {
            counter.increment(amount);
        }
    }

    @FunctionalInterface
    public interface BacklogProvider {

        Map<TaskStatus, Long> countByStatus();
    }

    public enum SnapshotOutcome {
        CAPTURED("captured"),
        THROTTLED("throttled"),
        DROPPED("dropped"),
        FAILED("failed");

        private final String tagValue;

        SnapshotOutcome(String tagValue) {
            this.tagValue = tagValue;
        }

        public String tagValue() {
            return tagValue;
        }
    }

    public record PoolState(
            int active,
            int queueSize,
            int queueRemaining,
            long completed) {
    }

    private record BacklogSnapshot(
            Map<TaskStatus, Long> counts,
            long expiresAtNanos) {
    }

    private record BacklogGaugeState(
            RelayqMetrics metrics,
            TaskStatus status) {

        private double value() {
            try {
                return metrics.backlogCount(status);
            } catch (RuntimeException error) {
                log.warn("Failed to refresh task backlog metrics", error);
                return Double.NaN;
            }
        }
    }

    private record ExecutionKey(String handler, String outcome) {
    }

    private static final class PullRatioState {

        private final LongAdder queried = new LongAdder();
        private final LongAdder empty = new LongAdder();

        private void record(boolean emptyPull) {
            queried.increment();
            if (emptyPull) {
                empty.increment();
            }
        }

        private double ratio() {
            long queryCount = queried.sum();
            return queryCount == 0L ? 0.0D : (double) empty.sum() / queryCount;
        }
    }

    private static final class PoolGaugeState {

        private final WeakReference<PoolMetricsSource> source;

        private PoolGaugeState(PoolMetricsSource source) {
            this.source = new WeakReference<>(source);
        }

        private double active() {
            PoolMetricsSource current = source.get();
            return current == null ? Double.NaN : current.getActiveCount();
        }

        private double queueSize() {
            PoolMetricsSource current = source.get();
            return current == null ? Double.NaN : current.getQueueSize();
        }

        private double queueRemaining() {
            PoolMetricsSource current = source.get();
            return current == null ? Double.NaN : current.getQueueRemainingCapacity();
        }

        private double completed() {
            PoolMetricsSource current = source.get();
            return current == null ? Double.NaN : current.getCompletedTaskCount();
        }

        private PoolState snapshot() {
            PoolMetricsSource current = source.get();
            if (current == null) {
                return null;
            }
            return new PoolState(
                    current.getActiveCount(),
                    current.getQueueSize(),
                    current.getQueueRemainingCapacity(),
                    current.getCompletedTaskCount());
        }
    }
}
