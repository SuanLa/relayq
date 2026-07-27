package com.suanla.relayq.core.service;

import com.suanla.relayq.core.domain.ExecuteOutcome;
import com.suanla.relayq.core.domain.FailureKind;
import com.suanla.relayq.core.domain.TaskExecuteLog;
import com.suanla.relayq.core.domain.TaskInfo;
import com.suanla.relayq.core.domain.TaskStatus;
import com.suanla.relayq.core.domain.TriggerType;
import com.suanla.relayq.core.exception.IllegalTaskStateException;
import com.suanla.relayq.core.exception.TaskNotFoundException;
import com.suanla.relayq.core.mapper.TaskExecuteLogMapper;
import com.suanla.relayq.core.mapper.TaskInfoMapper;
import com.suanla.relayq.core.snapshot.SnapshotTrigger;
import com.suanla.relayq.core.snapshot.SnapshotTriggerHook;
import com.suanla.relayq.core.support.ByteTruncator;
import com.suanla.relayq.core.support.SnowflakeIdGenerator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.annotation.Transactional;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.function.LongConsumer;

@Slf4j
public class TaskStateMachine {

    private static final int DEFAULT_ERROR_STACK_MAX_BYTES = 64 * 1024;
    private static final int DEFAULT_SNAPSHOT_FAIL_THRESHOLD = 3;
    private static final int ERROR_MESSAGE_MAX_CODE_POINTS = 1_024;
    private static final LongConsumer NOOP_LEASE_LOST_HOOK = taskId -> {
    };

    private final TaskInfoMapper taskInfoMapper;
    private final TaskExecuteLogMapper taskExecuteLogMapper;
    private final SnowflakeIdGenerator idGenerator;
    private final int errorStackMaxBytes;
    private final LongConsumer leaseLostHook;
    private final int snapshotFailThreshold;
    private final SnapshotTriggerHook snapshotTriggerHook;
    private final Clock clock;

    public TaskStateMachine(
            TaskInfoMapper taskInfoMapper,
            TaskExecuteLogMapper taskExecuteLogMapper,
            SnowflakeIdGenerator idGenerator) {
        this(
                taskInfoMapper,
                taskExecuteLogMapper,
                idGenerator,
                DEFAULT_ERROR_STACK_MAX_BYTES,
                NOOP_LEASE_LOST_HOOK,
                DEFAULT_SNAPSHOT_FAIL_THRESHOLD,
                SnapshotTriggerHook.NOOP,
                Clock.systemDefaultZone());
    }

    public TaskStateMachine(
            TaskInfoMapper taskInfoMapper,
            TaskExecuteLogMapper taskExecuteLogMapper,
            SnowflakeIdGenerator idGenerator,
            int errorStackMaxBytes,
            LongConsumer leaseLostHook) {
        this(
                taskInfoMapper,
                taskExecuteLogMapper,
                idGenerator,
                errorStackMaxBytes,
                leaseLostHook,
                DEFAULT_SNAPSHOT_FAIL_THRESHOLD,
                SnapshotTriggerHook.NOOP,
                Clock.systemDefaultZone());
    }

    public TaskStateMachine(
            TaskInfoMapper taskInfoMapper,
            TaskExecuteLogMapper taskExecuteLogMapper,
            SnowflakeIdGenerator idGenerator,
            int errorStackMaxBytes,
            LongConsumer leaseLostHook,
            Clock clock) {
        this(
                taskInfoMapper,
                taskExecuteLogMapper,
                idGenerator,
                errorStackMaxBytes,
                leaseLostHook,
                DEFAULT_SNAPSHOT_FAIL_THRESHOLD,
                SnapshotTriggerHook.NOOP,
                clock);
    }

    public TaskStateMachine(
            TaskInfoMapper taskInfoMapper,
            TaskExecuteLogMapper taskExecuteLogMapper,
            SnowflakeIdGenerator idGenerator,
            int errorStackMaxBytes,
            LongConsumer leaseLostHook,
            int snapshotFailThreshold,
            SnapshotTriggerHook snapshotTriggerHook) {
        this(
                taskInfoMapper,
                taskExecuteLogMapper,
                idGenerator,
                errorStackMaxBytes,
                leaseLostHook,
                snapshotFailThreshold,
                snapshotTriggerHook,
                Clock.systemDefaultZone());
    }

    public TaskStateMachine(
            TaskInfoMapper taskInfoMapper,
            TaskExecuteLogMapper taskExecuteLogMapper,
            SnowflakeIdGenerator idGenerator,
            int errorStackMaxBytes,
            LongConsumer leaseLostHook,
            int snapshotFailThreshold,
            SnapshotTriggerHook snapshotTriggerHook,
            Clock clock) {
        if (errorStackMaxBytes < 0) {
            throw new IllegalArgumentException(
                    "errorStackMaxBytes must not be negative: " + errorStackMaxBytes);
        }
        if (snapshotFailThreshold < 1) {
            throw new IllegalArgumentException("snapshotFailThreshold must be at least 1");
        }
        this.taskInfoMapper = Objects.requireNonNull(taskInfoMapper, "taskInfoMapper must not be null");
        this.taskExecuteLogMapper = Objects.requireNonNull(
                taskExecuteLogMapper, "taskExecuteLogMapper must not be null");
        this.idGenerator = Objects.requireNonNull(idGenerator, "idGenerator must not be null");
        this.errorStackMaxBytes = errorStackMaxBytes;
        this.leaseLostHook = leaseLostHook == null ? NOOP_LEASE_LOST_HOOK : leaseLostHook;
        this.snapshotFailThreshold = snapshotFailThreshold;
        this.snapshotTriggerHook = snapshotTriggerHook == null
                ? SnapshotTriggerHook.NOOP
                : snapshotTriggerHook;
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    @Transactional
    public boolean succeed(TaskInfo task, String owner, LocalDateTime startTime) {
        validateExecution(task, owner, startTime);
        int affected = taskInfoMapper.finishWithFencing(
                task.getId(), TaskStatus.SUCCESS, null, null, owner);
        if (affected == 0) {
            return handleLeaseLost(task, owner, null, null, startTime);
        }
        writeAudit(task, owner, ExecuteOutcome.SUCCESS, null, null, startTime);
        log.debug(
                "Task state transition completed: taskId={}, status={}, attemptNo={}, owner={}",
                task.getId(),
                TaskStatus.SUCCESS,
                task.getCurrentAttemptNo(),
                owner);
        return true;
    }

    @Transactional
    public boolean fail(
            TaskInfo task,
            String owner,
            FailureKind kind,
            Throwable error,
            LocalDateTime startTime,
            Long delayMillis) {
        validateExecution(task, owner, startTime);
        Objects.requireNonNull(kind, "failureKind must not be null");
        if (delayMillis != null && delayMillis < 0L) {
            throw new IllegalArgumentException(
                    "delayMillis must not be negative: " + delayMillis);
        }

        String errorMessage = summarizeError(error);
        boolean shouldRetry = kind.isRetryable()
                && delayMillis != null
                && task.getRetryCount() < task.getMaxRetry();
        int affected = shouldRetry
                ? taskInfoMapper.requeueForRetry(
                        task.getId(), delayMillis, errorMessage, kind, owner)
                : taskInfoMapper.finishWithFencing(
                        task.getId(), TaskStatus.DEAD, errorMessage, kind, owner);
        if (affected == 0) {
            return handleLeaseLost(task, owner, kind, error, startTime);
        }
        Long executeLogId = null;
        try {
            executeLogId = writeAudit(
                    task,
                    owner,
                    ExecuteOutcome.FAILED,
                    kind,
                    error,
                    startTime);
        } finally {
            /*
             * 终态回写与审计由外层事务一起提交；即使审计异常导致事务回滚，
             * 也仍尝试保全 DEAD/阈值现场，executeLogId 为空时用 taskId + attemptNo 去重。
             */
            triggerFailureSnapshots(task, executeLogId, kind, errorMessage, shouldRetry);
        }
        if (shouldRetry) {
            log.debug(
                    "Task failure scheduled for retry: taskId={}, status={}, attemptNo={}, owner={}, failureKind={}",
                    task.getId(),
                    TaskStatus.PENDING,
                    task.getCurrentAttemptNo(),
                    owner,
                    kind);
        } else {
            log.info(
                    "Task state transition completed after failure: taskId={}, status={}, attemptNo={}, owner={}, failureKind={}",
                    task.getId(),
                    TaskStatus.DEAD,
                    task.getCurrentAttemptNo(),
                    owner,
                    kind);
        }
        return true;
    }

    public int requeueRejected(Collection<Long> ids, String owner) {
        Objects.requireNonNull(ids, "ids must not be null");
        Objects.requireNonNull(owner, "owner must not be null");
        if (ids.isEmpty()) {
            return 0;
        }
        List<Long> stableIds = List.copyOf(ids);
        return taskInfoMapper.requeueRejected(stableIds, owner);
    }

    public boolean cancel(long taskId) {
        int affected = taskInfoMapper.cancelPending(taskId);
        if (affected == 0) {
            TaskInfo actual = taskInfoMapper.selectById(taskId);
            if (actual == null) {
                throw new TaskNotFoundException(taskId);
            }
            throw new IllegalTaskStateException(taskId, actual.getStatus(), "cancel");
        }
        log.info("Task cancelled: taskId={}, status={}", taskId, TaskStatus.CANCELLED);
        return true;
    }

    private boolean handleLeaseLost(
            TaskInfo task,
            String owner,
            FailureKind kind,
            Throwable error,
            LocalDateTime startTime) {
        // fencing 已判定旧 worker 失去写资格，审计与指标都不能反过来影响业务线程。
        try {
            writeAudit(task, owner, ExecuteOutcome.LEASE_LOST, kind, error, startTime);
        } catch (RuntimeException auditError) {
            log.error(
                    "Failed to write LEASE_LOST audit; execution result discarded: taskId={}, attemptNo={}",
                    task.getId(),
                    task.getCurrentAttemptNo(),
                    auditError);
        }
        try {
            leaseLostHook.accept(task.getId());
        } catch (RuntimeException hookError) {
            log.warn("LEASE_LOST hook failed: taskId={}", task.getId(), hookError);
        }
        return false;
    }

    private Long writeAudit(
            TaskInfo task,
            String owner,
            ExecuteOutcome outcome,
            FailureKind kind,
            Throwable error,
            LocalDateTime startTime) {
        ByteTruncator.Result truncated = ByteTruncator.truncate(stackTrace(error), errorStackMaxBytes);
        TaskExecuteLog executeLog = new TaskExecuteLog();
        executeLog.setId(idGenerator.nextId());
        executeLog.setTaskId(task.getId());
        // attempt 身份在抢占时已固定，retry_count 无法区分租约回收后的再次执行。
        executeLog.setAttemptNo(task.getCurrentAttemptNo());
        /*
         * start_time、end_time、created_at 只用于执行审计，不参与任何 NOW(3) 比较，
         * 因此可以保留应用时间；租约和调度时间则必须始终使用数据库时间。
         */
        executeLog.setStartTime(startTime);
        executeLog.setEndTime(LocalDateTime.now(clock));
        executeLog.setOutcome(outcome);
        executeLog.setFailureKind(kind);
        executeLog.setErrorStack(truncated.text());
        executeLog.setErrorStackOriginalLen(
                truncated.truncated() ? truncated.originalByteLength() : null);
        executeLog.setWorkerInstance(owner);
        executeLog.setTraceId(task.getTraceId());

        try {
            taskExecuteLogMapper.insert(executeLog);
            return executeLog.getId();
        } catch (DuplicateKeyException duplicateError) {
            warnDuplicateAudit(task, outcome);
            return null;
        } catch (RuntimeException errorInsertingAudit) {
            // 纯 MyBatis 不做 Spring 异常翻译，仍需把 uk_task_attempt 识别为同一种 fencing 结果。
            if (DatabaseExceptionClassifier.isDuplicateKey(errorInsertingAudit)) {
                warnDuplicateAudit(task, outcome);
                return null;
            }
            throw errorInsertingAudit;
        }
    }

    private void triggerSnapshot(
            TaskInfo task,
            Long executeLogId,
            FailureKind failureKind,
            String errorMessage,
            TriggerType triggerType) {
        SnapshotTrigger trigger = new SnapshotTrigger(
                task.getId(),
                executeLogId,
                task.getCurrentAttemptNo(),
                triggerType,
                task.getHandlerName(),
                task.getRetryCount(),
                task.getMaxRetry(),
                failureKind,
                errorMessage);
        try {
            snapshotTriggerHook.trigger(trigger);
        } catch (RuntimeException hookError) {
            // 快照观察者失效不能反向改变已经提交的任务状态。
            log.warn(
                    "Snapshot trigger hook failed: taskId={}, attemptNo={}, triggerType={}",
                    task.getId(),
                    task.getCurrentAttemptNo(),
                    triggerType,
                    hookError);
        }
    }

    private void triggerFailureSnapshots(
            TaskInfo task,
            Long executeLogId,
            FailureKind failureKind,
            String errorMessage,
            boolean shouldRetry) {
        if (!shouldRetry) {
            triggerSnapshot(
                    task,
                    executeLogId,
                    failureKind,
                    errorMessage,
                    TriggerType.DEAD);
        }
        long failureCount = (long) task.getRetryCount() + 1L;
        if (failureCount >= snapshotFailThreshold) {
            triggerSnapshot(
                    task,
                    executeLogId,
                    failureKind,
                    errorMessage,
                    TriggerType.FAIL_THRESHOLD);
        }
    }

    private void warnDuplicateAudit(TaskInfo task, ExecuteOutcome outcome) {
        log.warn(
                "Duplicate execution audit ignored for stale worker: taskId={}, attemptNo={}, outcome={}",
                task.getId(),
                task.getCurrentAttemptNo(),
                outcome);
    }

    private void validateExecution(TaskInfo task, String owner, LocalDateTime startTime) {
        Objects.requireNonNull(task, "task must not be null");
        Objects.requireNonNull(task.getId(), "task.id must not be null");
        Objects.requireNonNull(task.getCurrentAttemptNo(), "task.currentAttemptNo must not be null");
        Objects.requireNonNull(task.getRetryCount(), "task.retryCount must not be null");
        Objects.requireNonNull(task.getMaxRetry(), "task.maxRetry must not be null");
        Objects.requireNonNull(owner, "owner must not be null");
        Objects.requireNonNull(startTime, "startTime must not be null");
    }

    private String summarizeError(Throwable error) {
        if (error == null) {
            return null;
        }
        String message = error.toString();
        int codePointCount = message.codePointCount(0, message.length());
        if (codePointCount <= ERROR_MESSAGE_MAX_CODE_POINTS) {
            return message;
        }
        int endIndex = message.offsetByCodePoints(0, ERROR_MESSAGE_MAX_CODE_POINTS);
        return message.substring(0, endIndex);
    }

    private String stackTrace(Throwable error) {
        if (error == null) {
            return null;
        }
        StringWriter buffer = new StringWriter();
        error.printStackTrace(new PrintWriter(buffer));
        return buffer.toString();
    }
}
