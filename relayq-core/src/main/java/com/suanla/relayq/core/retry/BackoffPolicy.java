package com.suanla.relayq.core.retry;

import java.time.Duration;

@FunctionalInterface
public interface BackoffPolicy {

    /**
     * retryNumber 从 1 开始，避免把“已有失败次数”和“本次重试序号”混在一起。
     */
    Duration nextDelay(int retryNumber);
}
