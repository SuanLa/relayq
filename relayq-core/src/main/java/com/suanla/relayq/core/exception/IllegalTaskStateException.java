package com.suanla.relayq.core.exception;

import com.suanla.relayq.core.domain.TaskStatus;

public class IllegalTaskStateException extends RelayqException {

    private final long taskId;
    private final TaskStatus actualStatus;

    public IllegalTaskStateException(long taskId, TaskStatus actualStatus, String operation) {
        super("task " + taskId + " is in status " + actualStatus + ", cannot " + operation);
        this.taskId = taskId;
        this.actualStatus = actualStatus;
    }

    public long getTaskId() {
        return taskId;
    }

    public TaskStatus getActualStatus() {
        return actualStatus;
    }
}
