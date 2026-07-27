package com.suanla.relayq.example.handler;

import com.suanla.relayq.core.handler.RelayqHandler;
import com.suanla.relayq.core.handler.TaskContext;
import com.suanla.relayq.core.handler.TaskHandler;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RelayqHandler("echo-handler")
public class EchoHandler implements TaskHandler {

    @Override
    public void execute(TaskContext ctx) {
        EchoParams params = ctx.param(EchoParams.class);
        log.info(
                "Echo task executed: taskId={}, message={}",
                ctx.getTaskId(),
                params.getMessage());
    }

    @Data
    public static class EchoParams {

        private String message;
    }
}
