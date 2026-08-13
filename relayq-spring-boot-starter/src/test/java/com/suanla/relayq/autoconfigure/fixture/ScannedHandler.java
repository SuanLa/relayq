package com.suanla.relayq.autoconfigure.fixture;

import com.suanla.relayq.core.handler.RelayqHandler;
import com.suanla.relayq.core.handler.TaskContext;
import com.suanla.relayq.core.handler.TaskHandler;

@RelayqHandler("scanned-handler")
public class ScannedHandler implements TaskHandler {

    @Override
    public void execute(TaskContext ctx) {
    }
}
