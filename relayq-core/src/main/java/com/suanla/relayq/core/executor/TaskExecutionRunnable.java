package com.suanla.relayq.core.executor;

import java.util.Objects;

public final class TaskExecutionRunnable implements TaskIdentifiedRunnable {

    private final long taskId;
    private final Runnable delegate;

    public TaskExecutionRunnable(long taskId, Runnable delegate) {
        this.taskId = taskId;
        this.delegate = Objects.requireNonNull(delegate, "delegate must not be null");
    }

    @Override
    public long getTaskId() {
        return taskId;
    }

    @Override
    public void run() {
        delegate.run();
    }
}
