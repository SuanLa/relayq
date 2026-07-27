package com.suanla.relayq.example.handler;

import com.suanla.relayq.core.handler.ParamDeserializationException;
import com.suanla.relayq.core.handler.RelayqHandler;
import com.suanla.relayq.core.handler.TaskContext;
import com.suanla.relayq.core.handler.TaskHandler;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
@RelayqHandler("deadlock-handler")
public class DeadlockHandler implements TaskHandler {

    private static final long DEFAULT_BLOCK_MILLIS = 3_000L;
    private static final long MIN_BLOCK_MILLIS = 500L;
    private static final long MAX_BLOCK_MILLIS = 10_000L;

    @Override
    public void execute(TaskContext ctx) throws InterruptedException {
        DeadlockParams params = ctx.param(DeadlockParams.class);
        long blockMillis = params.getBlockMillis() == null
                ? DEFAULT_BLOCK_MILLIS
                : params.getBlockMillis();
        if (blockMillis < MIN_BLOCK_MILLIS || blockMillis > MAX_BLOCK_MILLIS) {
            throw new ParamDeserializationException(
                    DeadlockParams.class,
                    new IllegalArgumentException(
                            "blockMillis must be between 500 and 10000"));
        }

        Object monitor = new Object();
        CountDownLatch monitorAcquired = new CountDownLatch(1);
        /*
         * 不制造无法中断的永久交叉死锁：holder 到时主动释放 monitor，
         * waiter 在这段窗口真实进入 BLOCKED，快照仍能采到线程和锁归属，
         * 但演示结束后两个 daemon 平台线程都会自行退出。
         */
        Thread holder = Thread.ofPlatform()
                .daemon(true)
                .name("relayq-deadlock-holder-" + ctx.getTaskId())
                .unstarted(() -> holdMonitor(monitor, monitorAcquired, blockMillis));
        Thread waiter = Thread.ofPlatform()
                .daemon(true)
                .name("relayq-deadlock-waiter-" + ctx.getTaskId())
                .unstarted(() -> waitForMonitor(monitor));

        holder.start();
        if (!monitorAcquired.await(1, TimeUnit.SECONDS)) {
            throw new IllegalStateException("Timed out waiting for demonstration monitor");
        }
        waiter.start();
        awaitBlocked(waiter);
        log.warn(
                "Recoverable BLOCKED state created for snapshot demonstration: taskId={}, blockMillis={}",
                ctx.getTaskId(),
                blockMillis);
        throw new IllegalStateException(
                "Simulated deadlock incident with a recoverable BLOCKED thread");
    }

    private void holdMonitor(Object monitor, CountDownLatch monitorAcquired, long blockMillis) {
        synchronized (monitor) {
            monitorAcquired.countDown();
            try {
                Thread.sleep(blockMillis);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void waitForMonitor(Object monitor) {
        synchronized (monitor) {
            // 获得锁即说明演示窗口结束，不执行额外工作。
        }
    }

    private void awaitBlocked(Thread waiter) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
        while (System.nanoTime() < deadline) {
            if (waiter.getState() == Thread.State.BLOCKED) {
                return;
            }
            Thread.sleep(5L);
        }
        throw new IllegalStateException("Waiter did not enter BLOCKED state");
    }

    @Data
    public static class DeadlockParams {

        private Long blockMillis;
    }
}
