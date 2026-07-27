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
@RelayqHandler("poison-handler")
public class PoisonHandler implements TaskHandler {

    @Override
    public void execute(TaskContext ctx) {
        /*
         * 示例故意要求整数参数。传入字符串或缺失字段会统一转成
         * PARAM_DESERIALIZE_FAILED，证明毒任务不会无意义地打满重试。
         */
        PoisonParams params = ctx.param(PoisonParams.class);
        if (params.getRequiredNumber() == null) {
            throw new ParamDeserializationException(
                    PoisonParams.class,
                    new IllegalArgumentException("requiredNumber must not be null"));
        }
        log.info(
                "Poison handler received a valid payload: taskId={}, requiredNumber={}",
                ctx.getTaskId(),
                params.getRequiredNumber());
    }

    @Data
    public static class PoisonParams {

        private Integer requiredNumber;
    }
}
