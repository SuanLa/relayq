package com.suanla.relayq.core.handler;

import lombok.extern.slf4j.Slf4j;

import java.util.Collection;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Slf4j
public class HandlerRegistry {

    private final ConcurrentMap<String, TaskHandler> handlers = new ConcurrentHashMap<>();

    public HandlerRegistry() {
    }

    public HandlerRegistry(Collection<? extends TaskHandler> handlers) {
        Objects.requireNonNull(handlers, "handlers must not be null").forEach(this::register);
    }

    public void register(String name, TaskHandler handler) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("handler name must not be blank");
        }
        Objects.requireNonNull(handler, "handler must not be null");
        TaskHandler previous = handlers.putIfAbsent(name, handler);
        if (previous != null) {
            throw new IllegalStateException("handler name already registered: " + name);
        }
        log.info(
                "Registered task handler: handlerName={}, implementation={}",
                name,
                handler.getClass().getName());
    }

    public void register(TaskHandler handler) {
        Objects.requireNonNull(handler, "handler must not be null");
        RelayqHandler annotation = handler.getClass().getAnnotation(RelayqHandler.class);
        if (annotation == null) {
            throw new IllegalArgumentException(
                    "handler class is missing @RelayqHandler: " + handler.getClass().getName());
        }
        register(annotation.value(), handler);
    }

    public Optional<TaskHandler> find(String name) {
        if (name == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(handlers.get(name));
    }

    public boolean contains(String name) {
        return name != null && handlers.containsKey(name);
    }
}
