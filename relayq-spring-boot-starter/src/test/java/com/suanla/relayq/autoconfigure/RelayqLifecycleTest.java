package com.suanla.relayq.autoconfigure;

import com.suanla.relayq.core.executor.RequeueRejectedHandler;
import com.suanla.relayq.core.executor.TaskWorkerPool;
import com.suanla.relayq.core.scheduler.LeaseReaper;
import com.suanla.relayq.core.scheduler.LeaseRenewer;
import com.suanla.relayq.core.scheduler.TaskPuller;
import com.suanla.relayq.core.snapshot.SnapshotAdmission;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;

class RelayqLifecycleTest {

    @Test
    void startsAndStopsInTheRequiredOrder() {
        TaskPuller puller = mock(TaskPuller.class);
        TaskWorkerPool workerPool = mock(TaskWorkerPool.class);
        RequeueRejectedHandler requeue = mock(RequeueRejectedHandler.class);
        LeaseReaper reaper = mock(LeaseReaper.class);
        LeaseRenewer renewer = mock(LeaseRenewer.class);
        SnapshotAdmission snapshot = mock(SnapshotAdmission.class);
        RelayqLifecycle lifecycle = new RelayqLifecycle(
                puller,
                workerPool,
                requeue,
                reaper,
                renewer,
                snapshot);
        InOrder order = inOrder(
                reaper,
                renewer,
                puller,
                workerPool,
                requeue,
                snapshot);

        lifecycle.start();

        order.verify(reaper).start();
        order.verify(renewer).start();
        order.verify(puller).start();
        assertThat(lifecycle.isRunning()).isTrue();

        lifecycle.stop();

        order.verify(puller).stop();
        order.verify(workerPool).stop();
        order.verify(requeue).stop();
        order.verify(reaper).stop();
        order.verify(renewer).stop();
        order.verify(snapshot).stop();
        assertThat(lifecycle.isRunning()).isFalse();
        assertThat(lifecycle.getPhase()).isEqualTo(Integer.MAX_VALUE);
    }
}
