package com.suanla.relayq.core.executor;

public interface TaskIdentifiedRunnable extends Runnable {

    long getTaskId();

    /**
     * Releases task-scoped resources when this command will never be run.
     * Implementations must make this operation idempotent.
     */
    default void discard() {
    }
}
