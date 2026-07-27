package com.suanla.relayq.core.scheduler;

import com.suanla.relayq.core.config.RelayqProperties;
import com.suanla.relayq.core.domain.TaskInfo;
import com.suanla.relayq.core.domain.TaskStatus;
import com.suanla.relayq.core.executor.NamedThreadFactory;
import com.suanla.relayq.core.executor.TaskDispatcher;
import com.suanla.relayq.core.executor.TaskExecutionRunnable;
import com.suanla.relayq.core.executor.TaskIdentifiedRunnable;
import com.suanla.relayq.core.executor.TaskWorkerPool;
import com.suanla.relayq.core.mapper.TaskInfoMapper;
import com.suanla.relayq.core.metrics.RelayqMetrics;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
public class TaskPuller implements AutoCloseable {

    private static final long STOP_JOIN_MS = 5_000L;

    private final TaskInfoMapper taskInfoMapper;
    private final TransactionTemplate pullTransaction;
    private final TaskWorkerPool workerPool;
    private final TaskDispatcher dispatcher;
    private final String owner;
    private final RelayqProperties.Pull pullProperties;
    private final long leaseSeconds;
    private final AtomicBoolean running = new AtomicBoolean();
    private final NamedThreadFactory threadFactory = new NamedThreadFactory("relayq-puller-");
    private final RelayqMetrics metrics;
    private final TaskAvailabilitySignal taskAvailabilitySignal;

    private volatile Thread pollingThread;

    public TaskPuller(
            TaskInfoMapper taskInfoMapper,
            TransactionTemplate transactionTemplate,
            TaskWorkerPool workerPool,
            TaskDispatcher dispatcher,
            WorkerIdentity workerIdentity,
            RelayqProperties properties) {
        this(
                taskInfoMapper,
                transactionTemplate,
                workerPool,
                dispatcher,
                Objects.requireNonNull(workerIdentity, "workerIdentity must not be null").value(),
                properties,
                RelayqMetrics.noop(),
                TaskAvailabilitySignal.noop());
    }

    public TaskPuller(
            TaskInfoMapper taskInfoMapper,
            TransactionTemplate transactionTemplate,
            TaskWorkerPool workerPool,
            TaskDispatcher dispatcher,
            String owner,
            RelayqProperties properties) {
        this(
                taskInfoMapper,
                transactionTemplate,
                workerPool,
                dispatcher,
                owner,
                properties,
                RelayqMetrics.noop(),
                TaskAvailabilitySignal.noop());
    }

    public TaskPuller(
            TaskInfoMapper taskInfoMapper,
            TransactionTemplate transactionTemplate,
            TaskWorkerPool workerPool,
            TaskDispatcher dispatcher,
            String owner,
            RelayqProperties properties,
            RelayqMetrics metrics) {
        this(
                taskInfoMapper,
                transactionTemplate,
                workerPool,
                dispatcher,
                owner,
                properties,
                metrics,
                TaskAvailabilitySignal.noop());
    }

    public TaskPuller(
            TaskInfoMapper taskInfoMapper,
            TransactionTemplate transactionTemplate,
            TaskWorkerPool workerPool,
            TaskDispatcher dispatcher,
            String owner,
            RelayqProperties properties,
            RelayqMetrics metrics,
            TaskAvailabilitySignal taskAvailabilitySignal) {
        this.taskInfoMapper = Objects.requireNonNull(
                taskInfoMapper, "taskInfoMapper must not be null");
        Objects.requireNonNull(transactionTemplate, "transactionTemplate must not be null");
        if (transactionTemplate.getTransactionManager() == null) {
            throw new IllegalArgumentException("transactionTemplate must have a transaction manager");
        }
        this.pullTransaction = new TransactionTemplate(
                transactionTemplate.getTransactionManager());
        /*
         * REQUIRES_NEW 防止调用方已有 RR 事务吞掉这里的 RC 声明；否则隔离级别只像装饰，
         * 非唯一范围扫描仍会拿 next-key lock。
         */
        this.pullTransaction.setPropagationBehavior(
                TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.pullTransaction.setIsolationLevel(
                TransactionDefinition.ISOLATION_READ_COMMITTED);
        this.workerPool = Objects.requireNonNull(workerPool, "workerPool must not be null");
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher must not be null");
        if (owner == null || owner.isBlank()) {
            throw new IllegalArgumentException("owner must not be blank");
        }
        Objects.requireNonNull(properties, "properties must not be null");
        validate(properties);
        this.owner = owner;
        this.pullProperties = properties.getPull();
        this.leaseSeconds = properties.getLease().getTtlSeconds();
        this.metrics = metrics == null ? RelayqMetrics.noop() : metrics;
        this.taskAvailabilitySignal = Objects.requireNonNull(
                taskAvailabilitySignal, "taskAvailabilitySignal must not be null");
    }

    public void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        Thread thread = threadFactory.newThread(this::pollLoop);
        pollingThread = thread;
        thread.start();
    }

    public int pullOnce() {
        return pullAndDispatch().claimedCount();
    }

    public void stop() {
        running.set(false);
        Thread thread = pollingThread;
        if (thread == null) {
            return;
        }
        thread.interrupt();
        try {
            thread.join(STOP_JOIN_MS);
            if (thread.isAlive()) {
                log.warn("Puller thread did not stop within the join timeout: owner={}", owner);
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            log.warn("Interrupted while stopping task puller: owner={}", owner, error);
        }
    }

    @Override
    public void close() {
        stop();
    }

    private PullAttempt pullAndDispatch() {
        long startedAt = System.nanoTime();
        PullAttempt attempt = null;
        try {
            attempt = doPullAndDispatch();
            return attempt;
        } finally {
            metrics.recordPull(
                    System.nanoTime() - startedAt,
                    attempt == null ? 0 : attempt.claimedCount(),
                    attempt != null && attempt.queriedDatabase());
        }
    }

    private PullAttempt doPullAndDispatch() {
        int reserved = workerPool.tryReserve(pullProperties.getBatchSize());
        if (reserved == 0) {
            return PullAttempt.noCapacity();
        }

        List<TaskInfo> tasks;
        try {
            tasks = pullTransaction.execute(status -> claimTasks(reserved));
            if (tasks == null) {
                tasks = List.of();
            }
        } catch (RuntimeException error) {
            workerPool.releaseReservations(reserved);
            throw error;
        }

        int unusedReservations = reserved - tasks.size();
        if (unusedReservations > 0) {
            workerPool.releaseReservations(unusedReservations);
        }
        if (tasks.isEmpty()) {
            return PullAttempt.empty();
        }

        List<TaskIdentifiedRunnable> commands = new ArrayList<>(tasks.size());
        for (TaskInfo task : tasks) {
            commands.add(new TaskExecutionRunnable(
                    task.getId(),
                    () -> dispatcher.dispatch(task)));
        }
        workerPool.submitReservedBatch(commands);
        return PullAttempt.claimed(tasks.size());
    }

    private List<TaskInfo> claimTasks(int batchSize) {
        List<Long> ids = taskInfoMapper.selectDueIdsForUpdateSkipLocked(batchSize);
        if (ids.isEmpty()) {
            return List.of();
        }
        int affected = taskInfoMapper.markRunning(ids, owner, leaseSeconds);
        List<TaskInfo> rows = taskInfoMapper.selectByIdList(ids);
        List<TaskInfo> claimed = rows.stream()
                .filter(task -> task.getStatus() == TaskStatus.RUNNING)
                .filter(task -> owner.equals(task.getLeaseOwner()))
                .toList();
        if (affected != ids.size() || claimed.size() != affected) {
            log.warn(
                    "Claimed task count mismatch: selected={}, updated={}, loaded={}, owner={}",
                    ids.size(),
                    affected,
                    claimed.size(),
                    owner);
        }
        return claimed;
    }

    private void pollLoop() {
        int consecutiveEmptyPulls = 0;
        long observedGeneration = taskAvailabilitySignal.generation();
        while (running.get()) {
            long delayMs = pullProperties.getIntervalMs();
            try {
                PullAttempt attempt = pullAndDispatch();
                if (!attempt.queriedDatabase()) {
                    consecutiveEmptyPulls = 0;
                } else if (attempt.claimedCount() == 0) {
                    consecutiveEmptyPulls = Math.min(
                            Integer.MAX_VALUE, consecutiveEmptyPulls + 1);
                    delayMs = emptyBackoff(consecutiveEmptyPulls);
                } else {
                    consecutiveEmptyPulls = 0;
                }
            } catch (RuntimeException error) {
                // 单轮数据库或下发异常不能杀掉唯一 puller 线程。
                log.error("Task pull failed; polling will continue: owner={}", owner, error);
                consecutiveEmptyPulls = 0;
            }

            try {
                long currentGeneration = taskAvailabilitySignal.awaitChange(
                        observedGeneration,
                        jitter(delayMs),
                        TimeUnit.MILLISECONDS);
                if (currentGeneration != observedGeneration) {
                    // 提交信号会抢占当前退避；下一轮从空拉计数零开始并立即访问数据库。
                    consecutiveEmptyPulls = 0;
                }
                observedGeneration = currentGeneration;
            } catch (InterruptedException error) {
                if (!running.get()) {
                    Thread.currentThread().interrupt();
                    return;
                }
                log.warn("Task puller wait interrupted; polling will continue: owner={}", owner);
            }
        }
    }

    private long emptyBackoff(int consecutiveEmptyPulls) {
        long base = pullProperties.getIntervalMs();
        long max = pullProperties.getEmptyBackoffMaxMs();
        if (base >= max || pullProperties.getEmptyBackoffMultiplier() == 1.0D) {
            return Math.min(base, max);
        }
        double exponentAtCap = Math.log((double) max / base)
                / Math.log(pullProperties.getEmptyBackoffMultiplier());
        double exponent = consecutiveEmptyPulls - 1.0D;
        if (exponent >= exponentAtCap) {
            return max;
        }
        return Math.min(
                Math.round(base * Math.pow(
                        pullProperties.getEmptyBackoffMultiplier(), exponent)),
                max);
    }

    private long jitter(long delayMs) {
        double ratio = pullProperties.getJitterRatio();
        if (ratio == 0.0D) {
            return Math.max(1L, Math.min(delayMs, pullProperties.getEmptyBackoffMaxMs()));
        }
        double factor = 1.0D + ThreadLocalRandom.current().nextDouble(-ratio, ratio);
        return Math.max(
                1L,
                Math.min(Math.round(delayMs * factor), pullProperties.getEmptyBackoffMaxMs()));
    }

    private void validate(RelayqProperties properties) {
        RelayqProperties.Pull pull = Objects.requireNonNull(
                properties.getPull(), "pull properties must not be null");
        RelayqProperties.Lease lease = Objects.requireNonNull(
                properties.getLease(), "lease properties must not be null");
        if (pull.getIntervalMs() < 1L) {
            throw new IllegalArgumentException("pull intervalMs must be at least 1");
        }
        if (pull.getBatchSize() < 1) {
            throw new IllegalArgumentException("pull batchSize must be at least 1");
        }
        if (pull.getEmptyBackoffMaxMs() < pull.getIntervalMs()) {
            throw new IllegalArgumentException(
                    "pull emptyBackoffMaxMs must be at least intervalMs");
        }
        if (!Double.isFinite(pull.getEmptyBackoffMultiplier())
                || pull.getEmptyBackoffMultiplier() < 1.0D) {
            throw new IllegalArgumentException(
                    "pull emptyBackoffMultiplier must be finite and at least 1");
        }
        if (!Double.isFinite(pull.getJitterRatio())
                || pull.getJitterRatio() < 0.0D
                || pull.getJitterRatio() > 1.0D) {
            throw new IllegalArgumentException("pull jitterRatio must be between 0 and 1");
        }
        if (lease.getTtlSeconds() < 1L) {
            throw new IllegalArgumentException("lease ttlSeconds must be at least 1");
        }
    }

    private record PullAttempt(int claimedCount, boolean queriedDatabase) {

        private static PullAttempt claimed(int count) {
            return new PullAttempt(count, true);
        }

        private static PullAttempt empty() {
            return new PullAttempt(0, true);
        }

        private static PullAttempt noCapacity() {
            return new PullAttempt(0, false);
        }
    }
}
