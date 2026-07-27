package com.suanla.relayq.core.snapshot;

import com.suanla.relayq.core.domain.FailureKind;
import com.suanla.relayq.core.domain.TriggerType;

import java.util.Objects;

public record SnapshotTrigger(
        Long taskId,
        Long executeLogId,
        Integer attemptNo,
        TriggerType triggerType,
        String handlerName,
        Integer retryCount,
        Integer maxRetry,
        FailureKind failureKind,
        String errorMessage) {

    public SnapshotTrigger {
        Objects.requireNonNull(triggerType, "triggerType must not be null");
    }

    public static SnapshotTrigger manual(Long taskId, Integer attemptNo) {
        return new SnapshotTrigger(
                taskId,
                null,
                attemptNo,
                TriggerType.MANUAL,
                null,
                null,
                null,
                null,
                null);
    }
}
