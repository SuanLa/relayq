package com.suanla.relayq.core.domain;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class TaskSnapshot {
    private Long id;
    private Long taskId;
    private Long executeLogId;
    private Integer attemptNo;
    private TriggerType triggerType;
    private String threadDump;
    private Integer threadDumpOriginalLen;
    private Boolean lockInfoIncluded;
    private String poolState;
    private String heapState;
    private Long backlogCount;
    private LocalDateTime createdAt;
}
