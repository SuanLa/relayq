package com.suanla.relayq.autoconfigure;

import com.suanla.relayq.core.scheduler.TaskAvailabilitySignal;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * 把提交侧通知推迟到事务提交成功，同时把等待能力委托给同一个本地信号。
 */
final class AfterCommitTaskAvailabilitySignal implements TaskAvailabilitySignal {

    private final TaskAvailabilitySignal delegate;
    private final TransactionSynchronization afterCommitSynchronization;

    AfterCommitTaskAvailabilitySignal(TaskAvailabilitySignal delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate must not be null");
        this.afterCommitSynchronization = new TransactionSynchronization() {

            @Override
            public void afterCommit() {
                AfterCommitTaskAvailabilitySignal.this.delegate.signal();
            }
        };
    }

    @Override
    public void signal() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.isSynchronizationActive()) {
            delegate.signal();
            return;
        }
        /*
         * 当前事务只挂一个无状态回调；事务同步列表在挂起/恢复时也会随事务切换，
         * 因而嵌套 REQUIRES_NEW 不会被外层事务的已注册状态误抑制。
         */
        if (!TransactionSynchronizationManager.getSynchronizations()
                .contains(afterCommitSynchronization)) {
            TransactionSynchronizationManager.registerSynchronization(
                    afterCommitSynchronization);
        }
    }

    @Override
    public long generation() {
        return delegate.generation();
    }

    @Override
    public long awaitChange(
            long observedGeneration,
            long timeout,
            TimeUnit timeUnit) throws InterruptedException {
        return delegate.awaitChange(observedGeneration, timeout, timeUnit);
    }
}
