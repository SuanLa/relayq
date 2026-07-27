package com.suanla.relayq.core.metrics;

import com.suanla.relayq.core.domain.TaskStatus;
import lombok.Data;

@Data
public class TaskStatusCount {

    private TaskStatus status;
    private Long count;
}
