package com.suanla.relayq.core.service;

import com.suanla.relayq.core.config.RelayqProperties;
import com.suanla.relayq.core.domain.TaskInfo;
import com.suanla.relayq.core.domain.TaskStatus;
import com.suanla.relayq.core.exception.HandlerNotRegisteredException;
import com.suanla.relayq.core.exception.RelayqException;
import com.suanla.relayq.core.handler.HandlerRegistry;
import com.suanla.relayq.core.mapper.TaskInfoMapper;
import com.suanla.relayq.core.scheduler.TaskAvailabilitySignal;
import com.suanla.relayq.core.support.SnowflakeIdGenerator;
import com.suanla.relayq.core.support.TraceContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;

import java.util.Objects;
import java.util.concurrent.locks.LockSupport;

@Slf4j
public class TaskSubmitService {

    private static final int BIZ_KEY_MAX_LENGTH = 128;
    private static final int HANDLER_NAME_MAX_LENGTH = 128;
    private static final int DUPLICATE_LOOKUP_ATTEMPTS = 5;
    private static final long DUPLICATE_LOOKUP_WAIT_NANOS = 10_000_000L;

    private final TaskInfoMapper taskInfoMapper;
    private final HandlerRegistry handlerRegistry;
    private final SnowflakeIdGenerator idGenerator;
    private final RelayqProperties properties;
    private final TaskAvailabilitySignal taskAvailabilitySignal;

    public TaskSubmitService(
            TaskInfoMapper taskInfoMapper,
            HandlerRegistry handlerRegistry,
            SnowflakeIdGenerator idGenerator,
            RelayqProperties properties) {
        this(
                taskInfoMapper,
                handlerRegistry,
                idGenerator,
                properties,
                TaskAvailabilitySignal.noop());
    }

    public TaskSubmitService(
            TaskInfoMapper taskInfoMapper,
            HandlerRegistry handlerRegistry,
            SnowflakeIdGenerator idGenerator,
            RelayqProperties properties,
            TaskAvailabilitySignal taskAvailabilitySignal) {
        this.taskInfoMapper = Objects.requireNonNull(taskInfoMapper, "taskInfoMapper must not be null");
        this.handlerRegistry = Objects.requireNonNull(handlerRegistry, "handlerRegistry must not be null");
        this.idGenerator = Objects.requireNonNull(idGenerator, "idGenerator must not be null");
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.taskAvailabilitySignal = Objects.requireNonNull(
                taskAvailabilitySignal, "taskAvailabilitySignal must not be null");
    }

    public SubmitResult submit(SubmitCommand cmd) {
        Objects.requireNonNull(cmd, "submit command must not be null");
        if (!handlerRegistry.contains(cmd.handlerName())) {
            throw new HandlerNotRegisteredException(cmd.handlerName());
        }
        validate(cmd);

        TaskInfo task = buildTask(cmd);
        try {
            /*
             * 时间源原则：任何会与 NOW(3) 比较的时间列都必须由数据库生成，不能使用应用时钟。
             * scheduled_time 的立即和相对延迟路径因此都基于数据库时间；显式绝对时间是唯一例外，
             * 调用方已通过应用时钟选择墙钟时刻，该值按数据库时区原样解释。
             */
            int affected = taskInfoMapper.insertForSubmission(
                    task, cmd.scheduledTime(), cmd.delaySeconds());
            if (affected != 1) {
                throw new RelayqException("task submission failed: INSERT affected rows=" + affected);
            }
            TaskInfo persisted = taskInfoMapper.selectById(task.getId());
            if (persisted == null) {
                throw new RelayqException(
                        "submitted task not found after INSERT: taskId=" + task.getId());
            }
            log.debug(
                    "Task submitted: taskId={}, status={}, handlerName={}",
                    persisted.getId(),
                    persisted.getStatus(),
                    persisted.getHandlerName());
            if (isAvailableOnInsert(persisted)) {
                taskAvailabilitySignal.signal();
            }
            return toResult(persisted, false);
        } catch (DuplicateKeyException error) {
            return findExistingAfterDuplicate(cmd.bizKey(), error);
        } catch (RuntimeException error) {
            // 纯 MyBatis 场景没有 Spring 异常翻译，集成测试和非 Spring 使用方仍需识别 MySQL 1062。
            if (DatabaseExceptionClassifier.isDuplicateKey(error)) {
                return findExistingAfterDuplicate(cmd.bizKey(), error);
            }
            throw error;
        }
    }

    private boolean isAvailableOnInsert(TaskInfo task) {
        /*
         * scheduled_time 与 created_at 都来自同一条 INSERT 使用的数据库时钟。
         * 用两者判断提交瞬间是否到期，既不引入应用时钟，也不会为未来任务无条件唤醒。
         */
        return task.getScheduledTime() != null
                && task.getCreatedAt() != null
                && !task.getScheduledTime().isAfter(task.getCreatedAt());
    }

    private void validate(SubmitCommand cmd) {
        if (cmd.bizKey() == null || cmd.bizKey().isBlank()) {
            throw new IllegalArgumentException("bizKey must not be blank");
        }
        if (cmd.bizKey().length() > BIZ_KEY_MAX_LENGTH) {
            throw new IllegalArgumentException(
                    "bizKey length must not exceed " + BIZ_KEY_MAX_LENGTH + ": " + cmd.bizKey().length());
        }
        if (cmd.handlerName().length() > HANDLER_NAME_MAX_LENGTH) {
            throw new IllegalArgumentException(
                    "handlerName length must not exceed "
                            + HANDLER_NAME_MAX_LENGTH
                            + ": "
                            + cmd.handlerName().length());
        }
        if (cmd.delaySeconds() != null && cmd.delaySeconds() < 0) {
            throw new IllegalArgumentException(
                    "delaySeconds must not be negative: " + cmd.delaySeconds());
        }
        if (cmd.maxRetry() != null && cmd.maxRetry() < 0) {
            throw new IllegalArgumentException("maxRetry must not be negative: " + cmd.maxRetry());
        }
    }

    private TaskInfo buildTask(SubmitCommand cmd) {
        TaskInfo task = new TaskInfo();
        task.setId(idGenerator.nextId());
        task.setBizKey(cmd.bizKey());
        task.setHandlerName(cmd.handlerName());
        task.setParams(cmd.params());
        task.setStatus(TaskStatus.PENDING);
        task.setRetryCount(0);
        task.setMaxRetry(cmd.maxRetry() == null
                ? properties.getRetry().getDefaultMaxRetry()
                : cmd.maxRetry());
        task.setCurrentAttemptNo(0);
        task.setTraceId(TraceContext.generateTraceId());
        return task;
    }

    private SubmitResult findExistingAfterDuplicate(String bizKey, RuntimeException duplicateError) {
        for (int attempt = 1; attempt <= DUPLICATE_LOOKUP_ATTEMPTS; attempt++) {
            TaskInfo existing = taskInfoMapper.selectByBizKey(bizKey);
            if (existing != null) {
                log.info(
                        "Task submission resolved bizKey idempotency conflict with existing task: bizKey={}, taskId={}, status={}",
                        bizKey,
                        existing.getId(),
                        existing.getStatus());
                return toResult(existing, true);
            }
            if (attempt < DUPLICATE_LOOKUP_ATTEMPTS) {
                LockSupport.parkNanos(DUPLICATE_LOOKUP_WAIT_NANOS);
            }
        }
        throw new RelayqException(
                "existing task not found after bizKey idempotency conflict: " + bizKey,
                duplicateError);
    }

    private SubmitResult toResult(TaskInfo task, boolean alreadyExists) {
        return new SubmitResult(
                task.getId(),
                task.getBizKey(),
                task.getStatus(),
                task.getScheduledTime(),
                alreadyExists);
    }
}
