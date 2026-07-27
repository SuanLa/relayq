package com.suanla.relayq.core.handler;

import com.suanla.relayq.core.domain.FailureKind;

public class ParamDeserializationException extends RuntimeException {

    public ParamDeserializationException(Class<?> targetType, Throwable cause) {
        super("task parameters cannot be deserialized to " + targetType.getName(), cause);
    }

    public FailureKind getFailureKind() {
        return FailureKind.PARAM_DESERIALIZE_FAILED;
    }
}
