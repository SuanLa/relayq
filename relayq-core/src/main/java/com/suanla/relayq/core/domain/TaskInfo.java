package com.suanla.relayq.core.domain;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class TaskInfo {
    private Long id;
    private String bizKey;
    private String handlerName;
    private String params;
    private TaskStatus status;
    private LocalDateTime scheduledTime;
    private Integer retryCount;
    private Integer maxRetry;
    private LocalDateTime nextRetryTime;
    private String leaseOwner;
    private LocalDateTime leaseExpireTime;
    private Integer currentAttemptNo;
    private String traceId;
    private FailureKind lastFailureKind;
    private String redriveBy;
    private String redriveReason;
    private LocalDateTime redriveAt;
    private String errorMsg;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
