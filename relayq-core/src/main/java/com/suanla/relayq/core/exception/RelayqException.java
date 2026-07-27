package com.suanla.relayq.core.exception;

public class RelayqException extends RuntimeException {

    public RelayqException(String message) {
        super(message);
    }

    public RelayqException(String message, Throwable cause) {
        super(message, cause);
    }
}
