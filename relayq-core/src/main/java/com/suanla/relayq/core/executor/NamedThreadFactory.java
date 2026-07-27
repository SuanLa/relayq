package com.suanla.relayq.core.executor;

import lombok.extern.slf4j.Slf4j;

import java.util.Objects;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
public class NamedThreadFactory implements ThreadFactory {

    private final String prefix;
    private final boolean daemon;
    private final AtomicInteger sequence = new AtomicInteger();

    public NamedThreadFactory(String prefix) {
        this(prefix, false);
    }

    public NamedThreadFactory(String prefix, boolean daemon) {
        if (prefix == null || prefix.isBlank()) {
            throw new IllegalArgumentException("thread name prefix must not be blank");
        }
        this.prefix = prefix;
        this.daemon = daemon;
    }

    @Override
    public Thread newThread(Runnable runnable) {
        Objects.requireNonNull(runnable, "runnable must not be null");
        Thread thread = new Thread(runnable, prefix + sequence.incrementAndGet());
        thread.setDaemon(daemon);
        thread.setUncaughtExceptionHandler((failedThread, error) ->
                log.error(
                        "Uncaught exception terminated executor thread: threadName={}",
                        failedThread.getName(),
                        error));
        return thread;
    }
}
