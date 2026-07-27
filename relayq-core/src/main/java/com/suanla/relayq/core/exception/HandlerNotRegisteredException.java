package com.suanla.relayq.core.exception;

public class HandlerNotRegisteredException extends RelayqException {

    private final String handlerName;

    public HandlerNotRegisteredException(String handlerName) {
        super("handler not registered: " + handlerName);
        this.handlerName = handlerName;
    }

    public String getHandlerName() {
        return handlerName;
    }
}
