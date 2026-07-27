package com.suanla.relayq.core.exception;

public class TaskNotFoundException extends RelayqException {

    private final long taskId;

    public TaskNotFoundException(long taskId) {
        super("task not found: " + taskId);
        this.taskId = taskId;
    }

    public long getTaskId() {
        return taskId;
    }
}
