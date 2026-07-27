package com.suanla.relayq.core.handler;

@FunctionalInterface
public interface TaskHandler {

    void execute(TaskContext ctx) throws Exception;
}
