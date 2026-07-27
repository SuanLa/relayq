package com.suanla.relayq.core.executor;

import com.suanla.relayq.core.service.TaskStateMachine;
import com.suanla.relayq.core.metrics.PoolMetricsSource;
import com.suanla.relayq.core.metrics.RelayqMetrics;
import lombok.extern.slf4j.Slf4j;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

@Slf4j
public class RequeueRejectedHandler
        implements RejectedExecutionHandler, PoolMetricsSource, AutoCloseable {

    private static final int DEFAULT_QUEUE_CAPACITY = 16;
    private static final long DEFAULT_SHUTDOWN_GRACE_SECONDS = 5L;

    private final TaskStateMachine taskStateMachine;
    private final String owner;
    private final ThreadPoolExecutor requeueExecutor;
    private final long shutdownGraceSeconds;
    private final RelayqMetrics metrics;

    public RequeueRejectedHandler(TaskStateMachine taskStateMachine, String owner) {
        this(
                taskStateMachine,
                owner,
                DEFAULT_QUEUE_CAPACITY,
                DEFAULT_SHUTDOWN_GRACE_SECONDS,
                RelayqMetrics.noop());
    }

    public RequeueRejectedHandler(
            TaskStateMachine taskStateMachine,
            String owner,
            int queueCapacity,
            long shutdownGraceSeconds) {
        this(
                taskStateMachine,
                owner,
                queueCapacity,
                shutdownGraceSeconds,
                RelayqMetrics.noop());
    }

    public RequeueRejectedHandler(
            TaskStateMachine taskStateMachine,
            String owner,
            int queueCapacity,
            long shutdownGraceSeconds,
            RelayqMetrics metrics) {
        this.taskStateMachine = Objects.requireNonNull(
                taskStateMachine, "taskStateMachine must not be null");
        if (owner == null || owner.isBlank()) {
            throw new IllegalArgumentException("owner must not be blank");
        }
        if (queueCapacity < 1) {
            throw new IllegalArgumentException("queueCapacity must be at least 1");
        }
        if (shutdownGraceSeconds < 0L) {
            throw new IllegalArgumentException("shutdownGraceSeconds must not be negative");
        }
        this.owner = owner;
        this.shutdownGraceSeconds = shutdownGraceSeconds;
        this.metrics = metrics == null ? RelayqMetrics.noop() : metrics;
        this.requeueExecutor = new ThreadPoolExecutor(
                1,
                1,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(queueCapacity),
                new NamedThreadFactory("relayq-requeue-"),
                (command, executor) -> log.warn(
                        "Rejected asynchronous task requeue; lease reaper will recover it: owner={}",
                        owner));
        this.metrics.bindPool("requeue", this);
    }

    @Override
    public void rejectedExecution(Runnable runnable, ThreadPoolExecutor executor) {
        metrics.recordTaskRejected();
        try {
            if (runnable instanceof RejectionAwareRunnable awareRunnable) {
                awareRunnable.onRejected(this);
                return;
            }
            if (runnable instanceof TaskIdentifiedRunnable taskRunnable) {
                requeueAsync(List.of(taskRunnable.getTaskId()));
                return;
            }
            log.warn(
                    "Rejected worker command cannot be mapped to a task; lease reaper will recover it: owner={}",
                    owner);
        } catch (RuntimeException error) {
            // reject 发生在 puller 线程，任何兜底异常都不能反向卡死轮询。
            log.warn(
                    "Failed to schedule rejected task requeue; lease reaper will recover it: owner={}",
                    owner,
                    error);
        }
    }

    public void requeueAsync(Collection<Long> taskIds) {
        Objects.requireNonNull(taskIds, "taskIds must not be null");
        if (taskIds.isEmpty()) {
            return;
        }
        List<Long> stableIds = List.copyOf(new LinkedHashSet<>(taskIds));
        requeueExecutor.execute(() -> {
            try {
                int affected = taskStateMachine.requeueRejected(stableIds, owner);
                log.debug(
                        "Rejected tasks requeued: requested={}, affected={}, owner={}",
                        stableIds.size(),
                        affected,
                        owner);
            } catch (RuntimeException error) {
                // 短租约和 reaper 是最终兜底，回置失败不能污染 puller 或该小池的后续任务。
                log.warn(
                        "Failed to requeue rejected tasks; lease reaper will recover them: count={}, owner={}",
                        stableIds.size(),
                        owner,
                        error);
            }
        });
    }

    public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
        return requeueExecutor.awaitTermination(timeout, unit);
    }

    @Override
    public int getActiveCount() {
        return requeueExecutor.getActiveCount();
    }

    @Override
    public int getQueueSize() {
        return requeueExecutor.getQueue().size();
    }

    @Override
    public int getQueueRemainingCapacity() {
        return requeueExecutor.getQueue().remainingCapacity();
    }

    @Override
    public long getCompletedTaskCount() {
        return requeueExecutor.getCompletedTaskCount();
    }

    public void stop() {
        requeueExecutor.shutdown();
        try {
            if (!requeueExecutor.awaitTermination(shutdownGraceSeconds, TimeUnit.SECONDS)) {
                List<Runnable> dropped = requeueExecutor.shutdownNow();
                if (!dropped.isEmpty()) {
                    log.warn(
                            "Dropped asynchronous requeue commands during shutdown: count={}, owner={}",
                            dropped.size(),
                            owner);
                }
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            List<Runnable> dropped = requeueExecutor.shutdownNow();
            log.warn(
                    "Interrupted while stopping requeue executor: dropped={}, owner={}",
                    dropped.size(),
                    owner,
                    error);
        }
    }

    @Override
    public void close() {
        stop();
    }

    interface RejectionAwareRunnable {

        void onRejected(RequeueRejectedHandler handler);
    }
}
