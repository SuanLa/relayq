package com.suanla.relayq.core.domain;

public enum FailureKind {
    HANDLER_NOT_FOUND(false),
    PARAM_DESERIALIZE_FAILED(false),
    VALIDATION_FAILED(false),
    HANDLER_TIMEOUT(true),
    BUSINESS_ERROR(true);

    private final boolean retryable;

    FailureKind(boolean retryable) {
        this.retryable = retryable;
    }

    public boolean isRetryable() {
        return retryable;
    }
}
