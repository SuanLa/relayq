package com.suanla.relayq.example.handler;

import com.suanla.relayq.core.handler.RelayqHandler;
import com.suanla.relayq.core.handler.TaskContext;
import com.suanla.relayq.core.handler.TaskHandler;
import org.springframework.stereotype.Component;

/**
 * Deliberately performs no logging, sleeping or parameter deserialization so a
 * scheduler throughput test measures RelayQ rather than example-handler work.
 */
@Component
@RelayqHandler("load-test-handler")
public class LoadTestHandler implements TaskHandler {

    @Override
    public void execute(TaskContext context) {
        // No-op by design.
    }
}
