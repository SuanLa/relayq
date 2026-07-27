package com.suanla.relayq.core.service;

import java.time.LocalDateTime;

/**
 * @param scheduledTime 调用方应用时钟明确指定的绝对墙钟时刻；非空时优先于 delaySeconds，并按数据库时区解释
 * @param delaySeconds 相对数据库当前时间的延迟秒数；为空表示立即执行
 */
public record SubmitCommand(
        String bizKey,
        String handlerName,
        String params,
        LocalDateTime scheduledTime,
        Long delaySeconds,
        Integer maxRetry) {
}
