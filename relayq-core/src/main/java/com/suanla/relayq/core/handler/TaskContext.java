package com.suanla.relayq.core.handler;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalDateTime;
import java.util.Objects;

@Slf4j
public class TaskContext {

    private final long taskId;
    private final String bizKey;
    private final String handlerName;
    private final String rawParams;
    private final String traceId;
    private final int attemptNo;
    private final int retryCount;
    private final int maxRetry;
    private final LocalDateTime scheduledTime;
    private final ObjectMapper objectMapper;

    public TaskContext(
            long taskId,
            String bizKey,
            String handlerName,
            String rawParams,
            String traceId,
            int attemptNo,
            int retryCount,
            int maxRetry,
            LocalDateTime scheduledTime,
            ObjectMapper objectMapper) {
        this.taskId = taskId;
        this.bizKey = bizKey;
        this.handlerName = handlerName;
        this.rawParams = rawParams;
        this.traceId = traceId;
        this.attemptNo = attemptNo;
        this.retryCount = retryCount;
        this.maxRetry = maxRetry;
        this.scheduledTime = scheduledTime;
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
    }

    public <T> T param(Class<T> type) {
        Objects.requireNonNull(type, "type must not be null");
        if (rawParams == null) {
            IllegalArgumentException error = new IllegalArgumentException("rawParams must not be null");
            log.warn(
                    "Task parameter deserialization failed: taskId={}, targetType={}",
                    taskId,
                    type.getName(),
                    error);
            throw new ParamDeserializationException(type, error);
        }
        try {
            return objectMapper.readValue(rawParams, type);
        } catch (JsonProcessingException | IllegalArgumentException ex) {
            log.warn(
                    "Task parameter deserialization failed: taskId={}, targetType={}",
                    taskId,
                    type.getName(),
                    ex);
            throw new ParamDeserializationException(type, ex);
        }
    }

    public long getTaskId() {
        return taskId;
    }

    public String getBizKey() {
        return bizKey;
    }

    public String getHandlerName() {
        return handlerName;
    }

    public String getRawParams() {
        return rawParams;
    }

    public String getTraceId() {
        return traceId;
    }

    public int getAttemptNo() {
        return attemptNo;
    }

    public int getRetryCount() {
        return retryCount;
    }

    public int getMaxRetry() {
        return maxRetry;
    }

    public LocalDateTime getScheduledTime() {
        return scheduledTime;
    }
}
