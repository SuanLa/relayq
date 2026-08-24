package com.suanla.relayq.core.scheduler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.suanla.relayq.core.config.RelayqProperties;
import com.suanla.relayq.core.domain.TaskInfo;
import com.suanla.relayq.core.domain.TaskStatus;
import com.suanla.relayq.core.executor.RequeueRejectedHandler;
import com.suanla.relayq.core.executor.TaskDispatcher;
import com.suanla.relayq.core.executor.TaskWorkerPool;
import com.suanla.relayq.core.handler.HandlerRegistry;
import com.suanla.relayq.core.mapper.TaskInfoMapper;
import com.suanla.relayq.core.retry.ExponentialJitterBackoff;
import com.suanla.relayq.core.retry.RetryDecider;
import com.suanla.relayq.core.service.TaskStateMachine;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TaskPullerLeaseRegistrationTest {

    private final List<AutoCloseable> closeables = new ArrayList<>();

    @AfterEach
    void tearDown() throws Exception {
        for (int index = closeables.size() - 1; index >= 0; index--) {
            closeables.get(index).close();
        }
    }

    @Test
    void everyClaimedTaskIsRegisteredBeforeItWaitsInTheWorkerQueue() throws Exception {
        RelayqProperties properties = properties();
        String owner = properties.getInstanceId();
        TaskInfo first = runningTask(101L, owner, 1);
        TaskInfo queued = runningTask(102L, owner, 1);

        TaskInfoMapper mapper = mock(TaskInfoMapper.class);
        when(mapper.selectDueIdsForUpdateSkipLocked(2)).thenReturn(List.of(101L, 102L));
        when(mapper.markRunning(List.of(101L, 102L), owner, 30L)).thenReturn(2);
        when(mapper.selectByIdList(List.of(101L, 102L))).thenReturn(List.of(first, queued));

        CountDownLatch firstHandlerEntered = new CountDownLatch(1);
        CountDownLatch releaseFirstHandler = new CountDownLatch(1);
        HandlerRegistry handlerRegistry = new HandlerRegistry();
        handlerRegistry.register("lease-test-handler", context -> {
            if (context.getTaskId() == first.getId()) {
                firstHandlerEntered.countDown();
                releaseFirstHandler.await(5, TimeUnit.SECONDS);
            }
        });

        TaskStateMachine stateMachine = mock(TaskStateMachine.class);
        when(stateMachine.requeueRejected(anyCollection(), eq(owner)))
                .thenAnswer(invocation -> invocation.<List<Long>>getArgument(0).size());
        LeaseRenewer leaseRenewer = mock(LeaseRenewer.class);
        RetryDecider retryDecider = new RetryDecider(
                new ExponentialJitterBackoff(properties.getRetry()));
        TaskDispatcher dispatcher = new TaskDispatcher(
                handlerRegistry,
                stateMachine,
                leaseRenewer,
                retryDecider,
                new ObjectMapper(),
                owner,
                properties.getHandler(),
                java.time.Clock.systemDefaultZone());
        RequeueRejectedHandler rejectedHandler = new RequeueRejectedHandler(
                stateMachine, owner, 1, 1L);
        TaskWorkerPool workerPool = new TaskWorkerPool(
                properties.getWorker(), rejectedHandler);
        closeables.add(rejectedHandler);
        closeables.add(workerPool);

        TaskPuller puller = new TaskPuller(
                mapper,
                transactionTemplate(),
                workerPool,
                dispatcher,
                owner,
                properties);

        assertEquals(2, puller.pullOnce());
        assertTrue(firstHandlerEntered.await(2, TimeUnit.SECONDS));
        assertEquals(1, workerPool.getQueueSize());

        verify(leaseRenewer, timeout(1_000)).register(first.getId());
        verify(leaseRenewer, timeout(1_000)).register(queued.getId());

        releaseFirstHandler.countDown();
    }

    private static TransactionTemplate transactionTemplate() {
        PlatformTransactionManager manager = new PlatformTransactionManager() {
            @Override
            public TransactionStatus getTransaction(TransactionDefinition definition) {
                return new SimpleTransactionStatus();
            }

            @Override
            public void commit(TransactionStatus status) {
            }

            @Override
            public void rollback(TransactionStatus status) {
            }
        };
        return new TransactionTemplate(manager);
    }

    private static RelayqProperties properties() {
        RelayqProperties properties = new RelayqProperties();
        properties.setInstanceId("lease-registration-owner");
        properties.getPull().setBatchSize(2);
        properties.getWorker().setCoreSize(1);
        properties.getWorker().setMaxSize(1);
        properties.getWorker().setQueueCapacity(1);
        properties.getWorker().setShutdownGraceSeconds(1L);
        properties.getLease().setTtlSeconds(30L);
        return properties;
    }

    private static TaskInfo runningTask(long id, String owner, int attemptNo) {
        TaskInfo task = new TaskInfo();
        task.setId(id);
        task.setBizKey("lease-registration-" + id);
        task.setHandlerName("lease-test-handler");
        task.setParams("{}");
        task.setStatus(TaskStatus.RUNNING);
        task.setScheduledTime(LocalDateTime.now());
        task.setRetryCount(0);
        task.setMaxRetry(0);
        task.setCurrentAttemptNo(attemptNo);
        task.setTraceId("trace-" + id);
        task.setLeaseOwner(owner);
        task.setLeaseExpireTime(LocalDateTime.now().plusSeconds(30L));
        return task;
    }
}
