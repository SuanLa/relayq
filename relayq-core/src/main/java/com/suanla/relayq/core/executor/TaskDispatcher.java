package com.suanla.relayq.core.executor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.suanla.relayq.core.config.RelayqProperties;
import com.suanla.relayq.core.domain.FailureKind;
import com.suanla.relayq.core.domain.TaskInfo;
import com.suanla.relayq.core.handler.HandlerRegistry;
import com.suanla.relayq.core.handler.ParamDeserializationException;
import com.suanla.relayq.core.handler.TaskContext;
import com.suanla.relayq.core.handler.TaskHandler;
import com.suanla.relayq.core.metrics.RelayqMetrics;
import com.suanla.relayq.core.retry.RetryDecider;
import com.suanla.relayq.core.retry.RetryDecision;
import com.suanla.relayq.core.scheduler.LeaseRenewer;
import com.suanla.relayq.core.scheduler.WorkerIdentity;
import com.suanla.relayq.core.service.TaskStateMachine;
import com.suanla.relayq.core.support.TraceContext;
import lombok.extern.slf4j.Slf4j;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Slf4j
public class TaskDispatcher {

    private final HandlerRegistry handlerRegistry;
    private final TaskStateMachine taskStateMachine;
    private final LeaseRenewer leaseRenewer;
    private final RetryDecider retryDecider;
    private final ObjectMapper objectMapper;
    private final String owner;
    private final long handlerTimeoutMs;
    private final Clock clock;
    private final RelayqMetrics metrics;

    public TaskDispatcher(
            HandlerRegistry handlerRegistry,
            TaskStateMachine taskStateMachine,
            LeaseRenewer leaseRenewer,
            RetryDecider retryDecider,
            ObjectMapper objectMapper,
            WorkerIdentity workerIdentity,
            RelayqProperties.Handler properties) {
        this(
                handlerRegistry,
                taskStateMachine,
                leaseRenewer,
                retryDecider,
                objectMapper,
                Objects.requireNonNull(workerIdentity, "workerIdentity must not be null").value(),
                properties,
                Clock.systemDefaultZone(),
                RelayqMetrics.noop());
    }

    public TaskDispatcher(
            HandlerRegistry handlerRegistry,
            TaskStateMachine taskStateMachine,
            LeaseRenewer leaseRenewer,
            RetryDecider retryDecider,
            ObjectMapper objectMapper,
            String owner,
            RelayqProperties.Handler properties,
            Clock clock) {
        this(
                handlerRegistry,
                taskStateMachine,
                leaseRenewer,
                retryDecider,
                objectMapper,
                owner,
                properties,
                clock,
                RelayqMetrics.noop());
    }

    public TaskDispatcher(
            HandlerRegistry handlerRegistry,
            TaskStateMachine taskStateMachine,
            LeaseRenewer leaseRenewer,
            RetryDecider retryDecider,
            ObjectMapper objectMapper,
            String owner,
            RelayqProperties.Handler properties,
            Clock clock,
            RelayqMetrics metrics) {
        this.handlerRegistry = Objects.requireNonNull(
                handlerRegistry, "handlerRegistry must not be null");
        this.taskStateMachine = Objects.requireNonNull(
                taskStateMachine, "taskStateMachine must not be null");
        this.leaseRenewer = Objects.requireNonNull(leaseRenewer, "leaseRenewer must not be null");
        this.retryDecider = Objects.requireNonNull(retryDecider, "retryDecider must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
        if (owner == null || owner.isBlank()) {
            throw new IllegalArgumentException("owner must not be blank");
        }
        Objects.requireNonNull(properties, "handler properties must not be null");
        if (properties.getTimeoutMs() < 1L) {
            throw new IllegalArgumentException("handler timeoutMs must be at least 1");
        }
        this.owner = owner;
        this.handlerTimeoutMs = properties.getTimeoutMs();
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.metrics = metrics == null ? RelayqMetrics.noop() : metrics;
    }

    public void dispatch(TaskInfo task) {
        Objects.requireNonNull(task, "task must not be null");
        Objects.requireNonNull(task.getId(), "task.id must not be null");
        Objects.requireNonNull(
                task.getCurrentAttemptNo(), "task.currentAttemptNo must not be null");
        Objects.requireNonNull(task.getRetryCount(), "task.retryCount must not be null");
        Objects.requireNonNull(task.getMaxRetry(), "task.maxRetry must not be null");
        String traceId = task.getTraceId();
        if (traceId == null || traceId.isBlank()) {
            traceId = TraceContext.generateTraceId();
        }
        LocalDateTime startTime = LocalDateTime.now(clock);
        long startedAtNanos = System.nanoTime();
        String executeOutcome = "FAILED";
        leaseRenewer.register(task.getId());
        TraceContext.put(traceId);
        try {
            try {
                Failure failure = executeHandler(task, traceId);
                if (failure == null) {
                    executeOutcome = taskStateMachine.succeed(task, owner, startTime)
                            ? "SUCCESS"
                            : "LEASE_LOST";
                    return;
                }
                RetryDecision decision = retryDecider.decide(
                        failure.kind(), task.getRetryCount(), task.getMaxRetry());
                executeOutcome = taskStateMachine.fail(
                        task,
                        owner,
                        failure.kind(),
                        failure.error(),
                        startTime,
                        decision.shouldRetry() ? decision.delayMillis() : null)
                        ? "FAILED"
                        : "LEASE_LOST";
            } catch (InterruptedException error) {
                /*
                 * 优雅停机的 shutdownNow 只中断等待结果的 worker，不取消仍在执行的 handler，
                 * 也不写 BUSINESS_ERROR；主表保持 RUNNING，最终由短租约和 reaper 回收。
                 */
                Thread.currentThread().interrupt();
                executeOutcome = "INTERRUPTED";
                log.info(
                        "Task result wait interrupted; execution state left for lease recovery: taskId={}, handlerName={}, attemptNo={}, owner={}",
                        task.getId(),
                        task.getHandlerName(),
                        task.getCurrentAttemptNo(),
                        owner);
            }
        } finally {
            // worker 线程会复用，MDC 和续租集合都必须在所有异常路径上清理。
            TraceContext.clear();
            leaseRenewer.unregister(task.getId());
            metrics.recordTaskExecution(
                    task.getHandlerName(),
                    executeOutcome,
                    System.nanoTime() - startedAtNanos);
        }
    }

    private Failure executeHandler(TaskInfo task, String traceId) throws InterruptedException {
        TaskHandler handler = handlerRegistry.find(task.getHandlerName()).orElse(null);
        if (handler == null) {
            return new Failure(
                    FailureKind.HANDLER_NOT_FOUND,
                    new IllegalStateException(
                            "task handler not found: " + task.getHandlerName()));
        }

        TaskContext context = new TaskContext(
                task.getId(),
                task.getBizKey(),
                task.getHandlerName(),
                task.getParams(),
                traceId,
                task.getCurrentAttemptNo(),
                task.getRetryCount(),
                task.getMaxRetry(),
                task.getScheduledTime(),
                objectMapper);
        try {
            invokeWithTimeout(task, handler, context, traceId);
            return null;
        } catch (TaskHandlerTimeoutException error) {
            log.warn(
                    "Task handler timed out: taskId={}, handlerName={}, attemptNo={}, owner={}",
                    task.getId(),
                    task.getHandlerName(),
                    task.getCurrentAttemptNo(),
                    owner,
                    error);
            return new Failure(FailureKind.HANDLER_TIMEOUT, error);
        } catch (ParamDeserializationException error) {
            return new Failure(FailureKind.PARAM_DESERIALIZE_FAILED, error);
        } catch (InterruptedException error) {
            throw error;
        } catch (Exception error) {
            return new Failure(FailureKind.BUSINESS_ERROR, error);
        }
    }

    private void invokeWithTimeout(
            TaskInfo task,
            TaskHandler handler,
            TaskContext context,
            String traceId) throws Exception {
        FutureTask<Void> invocation = new FutureTask<>(() -> {
            TraceContext.put(traceId);
            try {
                handler.execute(context);
                return null;
            } finally {
                TraceContext.clear();
            }
        });
        Thread handlerThread = Thread.ofVirtual()
                .name("relayq-handler-" + task.getId())
                .unstarted(invocation);
        handlerThread.start();
        boolean waitInterrupted = false;
        try {
            invocation.get(handlerTimeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException error) {
            invocation.cancel(true);
            throw new TaskHandlerTimeoutException(handlerTimeoutMs, error);
        } catch (InterruptedException error) {
            waitInterrupted = true;
            throw error;
        } catch (ExecutionException error) {
            Throwable cause = error.getCause();
            if (cause instanceof Exception exception) {
                throw exception;
            }
            if (cause instanceof Error fatalError) {
                throw fatalError;
            }
            throw new IllegalStateException("task handler failed with an unknown throwable", cause);
        } finally {
            if (!waitInterrupted && !invocation.isDone()) {
                invocation.cancel(true);
            }
        }
    }

    private record Failure(FailureKind kind, Throwable error) {
    }

    private static final class TaskHandlerTimeoutException extends Exception {

        private TaskHandlerTimeoutException(long timeoutMs, Throwable cause) {
            super("task handler timed out after " + timeoutMs + " ms", cause);
        }
    }
}
