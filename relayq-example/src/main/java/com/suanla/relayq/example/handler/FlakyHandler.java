package com.suanla.relayq.example.handler;

import com.suanla.relayq.core.handler.ParamDeserializationException;
import com.suanla.relayq.core.handler.RelayqHandler;
import com.suanla.relayq.core.handler.TaskContext;
import com.suanla.relayq.core.handler.TaskHandler;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RelayqHandler("flaky-handler")
public class FlakyHandler implements TaskHandler {

    @Override
    public void execute(TaskContext ctx) {
        FlakyParams params = ctx.param(FlakyParams.class);
        if (params.getFailTimes() == null || params.getFailTimes() < 0) {
            throw invalidParams("failTimes must not be negative");
        }
        if (ctx.getRetryCount() < params.getFailTimes()) {
            log.warn(
                    "Flaky task failed as configured: taskId={}, retryCount={}, failTimes={}",
                    ctx.getTaskId(),
                    ctx.getRetryCount(),
                    params.getFailTimes());
            throw new IllegalStateException(
                    "Simulated flaky failure at retryCount=" + ctx.getRetryCount());
        }
        log.info(
                "Flaky task recovered: taskId={}, retryCount={}, failTimes={}",
                ctx.getTaskId(),
                ctx.getRetryCount(),
                params.getFailTimes());
    }

    private ParamDeserializationException invalidParams(String message) {
        return new ParamDeserializationException(
                FlakyParams.class,
                new IllegalArgumentException(message));
    }

    @Data
    public static class FlakyParams {

        private Integer failTimes;
    }
}
