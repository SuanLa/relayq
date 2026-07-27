package com.suanla.relayq.core.executor;

import com.suanla.relayq.core.config.RelayqProperties;
import com.suanla.relayq.core.metrics.PoolMetricsSource;
import com.suanla.relayq.core.metrics.RelayqMetrics;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
public class TaskWorkerPool implements PoolMetricsSource, AutoCloseable {

    private final ThreadPoolExecutor executor;
    private final RequeueRejectedHandler rejectedHandler;
    private final int totalCapacity;
    private final long shutdownGraceSeconds;
    private final AtomicInteger reserved = new AtomicInteger();
    private final AtomicBoolean stopped = new AtomicBoolean();

    public TaskWorkerPool(
            RelayqProperties.Worker properties,
            RequeueRejectedHandler rejectedHandler) {
        this(properties, rejectedHandler, RelayqMetrics.noop());
    }

    public TaskWorkerPool(
            RelayqProperties.Worker properties,
            RequeueRejectedHandler rejectedHandler,
            RelayqMetrics metrics) {
        Objects.requireNonNull(properties, "worker properties must not be null");
        validate(properties);
        this.rejectedHandler = Objects.requireNonNull(
                rejectedHandler, "rejectedHandler must not be null");
        this.totalCapacity = Math.addExact(properties.getMaxSize(), properties.getQueueCapacity());
        this.shutdownGraceSeconds = properties.getShutdownGraceSeconds();
        this.executor = new ThreadPoolExecutor(
                properties.getCoreSize(),
                properties.getMaxSize(),
                properties.getKeepAliveSeconds(),
                TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(properties.getQueueCapacity()),
                new NamedThreadFactory("relayq-worker-"),
                rejectedHandler);
        (metrics == null ? RelayqMetrics.noop() : metrics).bindPool("worker", this);
    }

    /**
     * 许可覆盖“正在执行 + 已入队”两类任务，并用 CAS 一次占住本轮额度。
     * 完成、拒绝、停机移出队列都只走各自的一次归还，避免超发与永久饿死。
     */
    public int tryReserve(int requested) {
        if (requested <= 0 || executor.isShutdown()) {
            return 0;
        }
        while (true) {
            int current = reserved.get();
            int unreserved = totalCapacity - current;
            /*
             * 已存在的空闲 worker 也要保守处理：ThreadPoolExecutor 会先 offer 队列，
             * 不会把新任务直接交给空闲线程；瞬时 burst 最多可靠接收
             * queue.remaining + (maxSize - poolSize)，否则队列先满仍会误 reject。
             */
            int executorAcceptance = executor.getQueue().remainingCapacity()
                    + (executor.getMaximumPoolSize() - executor.getPoolSize());
            int available = Math.min(unreserved, executorAcceptance);
            if (available <= 0) {
                return 0;
            }
            int granted = Math.min(requested, available);
            if (reserved.compareAndSet(current, current + granted)) {
                return granted;
            }
        }
    }

    public int submitReservedBatch(List<? extends TaskIdentifiedRunnable> tasks) {
        Objects.requireNonNull(tasks, "tasks must not be null");
        if (tasks.isEmpty()) {
            return 0;
        }
        List<TaskIdentifiedRunnable> stableTasks = List.copyOf(tasks);
        BatchReservation batch = new BatchReservation(stableTasks);
        int accepted = 0;
        for (int index = 0; index < stableTasks.size(); index++) {
            if (batch.isRejected()) {
                break;
            }
            ReservedRunnable command = new ReservedRunnable(
                    stableTasks.get(index), batch, index);
            executor.execute(command);
            if (batch.isRejected()) {
                break;
            }
            accepted++;
        }
        return accepted;
    }

    public boolean submit(TaskIdentifiedRunnable task) {
        Objects.requireNonNull(task, "task must not be null");
        if (tryReserve(1) == 0) {
            return false;
        }
        return submitReservedBatch(List.of(task)) == 1;
    }

    public void releaseReservations(int count) {
        if (count <= 0) {
            return;
        }
        while (true) {
            int current = reserved.get();
            int next = current - count;
            if (next < 0) {
                log.error(
                        "Worker reservation accounting underflow: reserved={}, release={}",
                        current,
                        count);
                next = 0;
            }
            if (reserved.compareAndSet(current, next)) {
                return;
            }
        }
    }

    public int getReservedCount() {
        return reserved.get();
    }

    public int getAvailableCapacity() {
        int unreserved = Math.max(0, totalCapacity - reserved.get());
        int executorAcceptance = executor.getQueue().remainingCapacity()
                + (executor.getMaximumPoolSize() - executor.getPoolSize());
        return Math.max(0, Math.min(unreserved, executorAcceptance));
    }

    @Override
    public int getActiveCount() {
        return executor.getActiveCount();
    }

    @Override
    public int getQueueSize() {
        return executor.getQueue().size();
    }

    @Override
    public int getQueueRemainingCapacity() {
        return executor.getQueue().remainingCapacity();
    }

    @Override
    public long getCompletedTaskCount() {
        return executor.getCompletedTaskCount();
    }

    public boolean isShutdown() {
        return executor.isShutdown();
    }

    /**
     * grace 到期后用 shutdownNow 取回尚未开始的包装任务；taskId 在包装层保留，
     * 因而可以一次批量回置，而不是等每条租约自然过期。
     */
    public List<Long> stop() {
        if (!stopped.compareAndSet(false, true)) {
            return List.of();
        }
        executor.shutdown();
        List<Runnable> queued = List.of();
        try {
            if (!executor.awaitTermination(shutdownGraceSeconds, TimeUnit.SECONDS)) {
                queued = executor.shutdownNow();
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            queued = executor.shutdownNow();
            log.warn("Interrupted while stopping worker executor", error);
        }

        List<Long> queuedTaskIds = releaseQueuedReservations(queued);
        if (!queuedTaskIds.isEmpty()) {
            rejectedHandler.requeueAsync(queuedTaskIds);
        }
        return queuedTaskIds;
    }

    @Override
    public void close() {
        stop();
    }

    private List<Long> releaseQueuedReservations(Collection<Runnable> queued) {
        if (queued.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<Long> taskIds = new LinkedHashSet<>();
        for (Runnable runnable : queued) {
            if (runnable instanceof ReservedRunnable reservedRunnable) {
                reservedRunnable.releaseWithoutRunning();
                taskIds.add(reservedRunnable.getTaskId());
            } else if (runnable instanceof TaskIdentifiedRunnable taskRunnable) {
                taskIds.add(taskRunnable.getTaskId());
            }
        }
        return List.copyOf(taskIds);
    }

    private void validate(RelayqProperties.Worker properties) {
        if (properties.getCoreSize() < 1) {
            throw new IllegalArgumentException("worker coreSize must be at least 1");
        }
        if (properties.getMaxSize() < properties.getCoreSize()) {
            throw new IllegalArgumentException("worker maxSize must be at least coreSize");
        }
        if (properties.getQueueCapacity() < 1) {
            throw new IllegalArgumentException("worker queueCapacity must be at least 1");
        }
        if (properties.getKeepAliveSeconds() < 0L) {
            throw new IllegalArgumentException("worker keepAliveSeconds must not be negative");
        }
        if (properties.getShutdownGraceSeconds() < 0L) {
            throw new IllegalArgumentException("worker shutdownGraceSeconds must not be negative");
        }
        Math.addExact(properties.getMaxSize(), properties.getQueueCapacity());
    }

    private final class BatchReservation {

        private final List<TaskIdentifiedRunnable> tasks;
        private final AtomicBoolean rejected = new AtomicBoolean();

        private BatchReservation(List<TaskIdentifiedRunnable> tasks) {
            this.tasks = tasks;
        }

        private boolean isRejected() {
            return rejected.get();
        }

        private void rejectFrom(int index, RequeueRejectedHandler handler) {
            if (!rejected.compareAndSet(false, true)) {
                return;
            }
            List<Long> rejectedIds = new ArrayList<>(tasks.size() - index);
            for (int current = index; current < tasks.size(); current++) {
                rejectedIds.add(tasks.get(current).getTaskId());
            }
            // 当前被拒任务与尚未提交任务的许可在同一处归还，保证首拒即熔断整批。
            releaseReservations(rejectedIds.size());
            handler.requeueAsync(rejectedIds);
        }
    }

    private final class ReservedRunnable
            implements TaskIdentifiedRunnable, RequeueRejectedHandler.RejectionAwareRunnable {

        private final TaskIdentifiedRunnable delegate;
        private final BatchReservation batch;
        private final int batchIndex;
        private final AtomicBoolean reservationReleased = new AtomicBoolean();

        private ReservedRunnable(
                TaskIdentifiedRunnable delegate,
                BatchReservation batch,
                int batchIndex) {
            this.delegate = delegate;
            this.batch = batch;
            this.batchIndex = batchIndex;
        }

        @Override
        public long getTaskId() {
            return delegate.getTaskId();
        }

        @Override
        public void run() {
            try {
                delegate.run();
            } finally {
                releaseOne();
            }
        }

        @Override
        public void onRejected(RequeueRejectedHandler handler) {
            reservationReleased.set(true);
            batch.rejectFrom(batchIndex, handler);
        }

        private void releaseWithoutRunning() {
            releaseOne();
        }

        private void releaseOne() {
            if (reservationReleased.compareAndSet(false, true)) {
                releaseReservations(1);
            }
        }
    }
}
