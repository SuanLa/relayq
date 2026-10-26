package com.suanla.relayq.core.mapper;

import com.suanla.relayq.core.domain.TaskExecuteLog;
import java.util.List;

/** Internal storage contract; no ORM dependency. Single-row lookups return null when absent. */
public interface TaskExecuteLogMapper {

    int insert(TaskExecuteLog record);

    TaskExecuteLog selectByTaskAndAttempt(
            long taskId,
            int attemptNo);

    List<TaskExecuteLog> selectByTaskId(long taskId);
}
