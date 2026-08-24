package com.suanla.relayq.core.executor;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

public final class TaskExecutionRunnable implements TaskIdentifiedRunnable {

    private final long taskId;
    private final Runnable delegate;
    private final Runnable discardAction;
    private final AtomicBoolean discarded = new AtomicBoolean();

    public TaskExecutionRunnable(long taskId, Runnable delegate) {
        this(taskId, delegate, () -> {
        });
    }

    public TaskExecutionRunnable(
            long taskId,
            Runnable delegate,
            Runnable discardAction) {
        this.taskId = taskId;
        this.delegate = Objects.requireNonNull(delegate, "delegate must not be null");
        this.discardAction = Objects.requireNonNull(
                discardAction, "discardAction must not be null");
    }

    @Override
    public long getTaskId() {
        return taskId;
    }

    @Override
    public void run() {
        delegate.run();
    }

    @Override
    public void discard() {
        if (discarded.compareAndSet(false, true)) {
            discardAction.run();
        }
    }
}
