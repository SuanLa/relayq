package com.suanla.relayq.core.executor;

public interface TaskIdentifiedRunnable extends Runnable {

    long getTaskId();
}
