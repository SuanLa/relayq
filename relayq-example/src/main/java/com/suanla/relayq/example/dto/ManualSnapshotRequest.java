package com.suanla.relayq.example.dto;

import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import lombok.Data;

@Data
public class ManualSnapshotRequest {

    @Positive(message = "taskId must be positive")
    private Long taskId;

    @PositiveOrZero(message = "attemptNo must not be negative")
    private Integer attemptNo;
}
