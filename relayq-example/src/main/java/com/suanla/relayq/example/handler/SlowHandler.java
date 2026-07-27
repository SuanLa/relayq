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
@RelayqHandler("slow-handler")
public class SlowHandler implements TaskHandler {

    @Override
    public void execute(TaskContext ctx) throws InterruptedException {
        SlowParams params = ctx.param(SlowParams.class);
        if (params.getSleepMillis() == null || params.getSleepMillis() < 0L) {
            throw invalidParams("sleepMillis must not be negative");
        }
        log.info(
                "Slow task started: taskId={}, sleepMillis={}",
                ctx.getTaskId(),
                params.getSleepMillis());
        Thread.sleep(params.getSleepMillis());
        log.info("Slow task completed: taskId={}", ctx.getTaskId());
    }

    private ParamDeserializationException invalidParams(String message) {
        return new ParamDeserializationException(
                SlowParams.class,
                new IllegalArgumentException(message));
    }

    @Data
    public static class SlowParams {

        private Long sleepMillis;
    }
}
