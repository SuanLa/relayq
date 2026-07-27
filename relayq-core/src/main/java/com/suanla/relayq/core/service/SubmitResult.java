package com.suanla.relayq.core.service;

import com.suanla.relayq.core.domain.TaskStatus;

import java.time.LocalDateTime;

public record SubmitResult(
        long taskId,
        String bizKey,
        TaskStatus status,
        LocalDateTime scheduledTime,
        boolean alreadyExists) {
}
