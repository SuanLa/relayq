package com.suanla.relayq.example.exception;

public class ApiResourceNotFoundException extends RuntimeException {

    public ApiResourceNotFoundException(String message) {
        super(message);
    }
}
