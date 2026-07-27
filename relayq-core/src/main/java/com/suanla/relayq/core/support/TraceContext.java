package com.suanla.relayq.core.support;

import org.slf4j.MDC;

import java.util.Objects;
import java.util.UUID;

public final class TraceContext {

    public static final String TRACE_ID_KEY = "traceId";

    private TraceContext() {
    }

    public static String generateTraceId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    public static void put(String traceId) {
        MDC.put(TRACE_ID_KEY, Objects.requireNonNull(traceId, "traceId must not be null"));
    }

    public static void clear() {
        MDC.remove(TRACE_ID_KEY);
    }

    public static Scope withTraceId(String traceId) {
        String previousTraceId = MDC.get(TRACE_ID_KEY);
        put(traceId);
        return new Scope(previousTraceId);
    }

    public static final class Scope implements AutoCloseable {

        private final String previousTraceId;
        private boolean closed;

        private Scope(String previousTraceId) {
            this.previousTraceId = previousTraceId;
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            if (previousTraceId == null) {
                clear();
            } else {
                MDC.put(TRACE_ID_KEY, previousTraceId);
            }
        }
    }
}
