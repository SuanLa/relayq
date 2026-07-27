package com.suanla.relayq.autoconfigure;

import com.suanla.relayq.core.scheduler.CoalescingTaskAvailabilitySignal;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AfterCommitTaskAvailabilitySignalTest {

    @Test
    void defersSignalUntilTransactionCommit() {
        CoalescingTaskAvailabilitySignal delegate = new CoalescingTaskAvailabilitySignal();
        AfterCommitTaskAvailabilitySignal signal =
                new AfterCommitTaskAvailabilitySignal(delegate);
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.initSynchronization();
        try {
            signal.signal();
            signal.signal();

            assertEquals(0L, delegate.generation());
            assertEquals(
                    1,
                    TransactionSynchronizationManager.getSynchronizations().size());
            TransactionSynchronizationManager.getSynchronizations()
                    .forEach(TransactionSynchronization::afterCommit);
            assertEquals(1L, delegate.generation());
        } finally {
            TransactionSynchronizationManager.clear();
        }
    }
}
