package com.suanla.relayq.core.metrics;

public interface PoolMetricsSource {

    int getActiveCount();

    int getQueueSize();

    int getQueueRemainingCapacity();

    long getCompletedTaskCount();
}
