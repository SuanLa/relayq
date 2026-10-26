package com.suanla.relayq.core.domain;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class TaskExecuteLog {
    private Long id;
    private Long taskId;
    private Integer attemptNo;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private ExecuteOutcome outcome;
    private FailureKind failureKind;
    private String errorStack;
    private Integer errorStackOriginalLen;
    private String workerInstance;
    private String traceId;
    private LocalDateTime createdAt;
}
