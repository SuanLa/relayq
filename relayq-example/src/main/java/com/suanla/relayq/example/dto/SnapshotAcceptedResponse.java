package com.suanla.relayq.example.dto;

import lombok.Data;

@Data
public class SnapshotAcceptedResponse {

    private boolean accepted;
    private Long taskId;
    private Integer attemptNo;
}
