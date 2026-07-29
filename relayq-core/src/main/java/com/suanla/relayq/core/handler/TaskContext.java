package com.suanla.relayq.core.handler;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalDateTime;
import java.util.Objects;

@Slf4j
public class TaskContext {

    @Getter
    private final long taskId;
    @Getter
    private final String bizKey;
    @Getter
    private final String handlerName;
    @Getter
    private final String rawParams;
    @Getter
    private final String traceId;
    @Getter
    private final int attemptNo;
    @Getter
    private final int retryCount;
    @Getter
    private final int maxRetry;
    @Getter
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
}
