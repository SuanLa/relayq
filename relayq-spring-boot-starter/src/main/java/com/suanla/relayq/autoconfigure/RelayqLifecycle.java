package com.suanla.relayq.autoconfigure;

import com.suanla.relayq.core.executor.RequeueRejectedHandler;
import com.suanla.relayq.core.executor.TaskWorkerPool;
import com.suanla.relayq.core.scheduler.LeaseReaper;
import com.suanla.relayq.core.scheduler.LeaseRenewer;
import com.suanla.relayq.core.scheduler.TaskPuller;
import com.suanla.relayq.core.snapshot.SnapshotAdmission;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
public class RelayqLifecycle implements SmartLifecycle {

    static final int PHASE = Integer.MAX_VALUE;

    private final TaskPuller taskPuller;
    private final TaskWorkerPool taskWorkerPool;
    private final RequeueRejectedHandler requeueRejectedHandler;
    private final LeaseReaper leaseReaper;
    private final LeaseRenewer leaseRenewer;
    private final SnapshotAdmission snapshotAdmission;
    private final AtomicBoolean running = new AtomicBoolean();

    public RelayqLifecycle(
            TaskPuller taskPuller,
            TaskWorkerPool taskWorkerPool,
            RequeueRejectedHandler requeueRejectedHandler,
            LeaseReaper leaseReaper,
            LeaseRenewer leaseRenewer,
            SnapshotAdmission snapshotAdmission) {
        this.taskPuller = Objects.requireNonNull(taskPuller, "taskPuller must not be null");
        this.taskWorkerPool = Objects.requireNonNull(
                taskWorkerPool, "taskWorkerPool must not be null");
        this.requeueRejectedHandler = Objects.requireNonNull(
                requeueRejectedHandler, "requeueRejectedHandler must not be null");
        this.leaseReaper = Objects.requireNonNull(leaseReaper, "leaseReaper must not be null");
        this.leaseRenewer = Objects.requireNonNull(leaseRenewer, "leaseRenewer must not be null");
        this.snapshotAdmission = Objects.requireNonNull(
                snapshotAdmission, "snapshotAdmission must not be null");
    }

    @Override
    public void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        try {
            /*
             * worker 池在构造后即可接收任务；先启动租约维护，最后开放拉取，
             * 避免任务进入执行链路时续租与回收能力尚未就绪。
             */
            leaseReaper.start();
            leaseRenewer.start();
            taskPuller.start();
            log.info("RelayQ scheduler started: phase={}", PHASE);
        } catch (RuntimeException error) {
            stop();
            throw error;
        }
    }

    @Override
    public void stop() {
        if (!running.compareAndSet(true, false)) {
            return;
        }

        stopSafely("puller", taskPuller::stop);
        stopSafely("workerPool", taskWorkerPool::stop);
        /*
         * workerPool 会把 shutdownNow 返回的未执行 taskId 批量交给回置池；
         * 在关闭租约线程前排空回置池，才能把队列中尚未开始执行的任务批量回置 PENDING；已在执行的不强杀，靠租约超时兜底，真正落到数据库。
         */
        stopSafely("requeue", requeueRejectedHandler::stop);
        stopSafely("leaseReaper", leaseReaper::stop);
        stopSafely("leaseRenewer", leaseRenewer::stop);
        stopSafely("snapshot", snapshotAdmission::stop);
        log.info("RelayQ scheduler stopped: phase={}", PHASE);
    }

    @Override
    public void stop(Runnable callback) {
        Objects.requireNonNull(callback, "callback must not be null");
        try {
            stop();
        } finally {
            callback.run();
        }
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    @Override
    public boolean isAutoStartup() {
        return true;
    }

    @Override
    public int getPhase() {
        /*
         * SmartLifecycle 按 phase 降序停止，MAX_VALUE 会最早收到 stop；
         * 单例销毁和 DataSource.close 在 lifecycle phase 全部结束后才发生。
         */
        return PHASE;
    }

    private void stopSafely(String component, Runnable action) {
        try {
            action.run();
        } catch (RuntimeException error) {
            // 单个组件停机失败不能跳过后续组件，否则会留下拉取或线程池。
            log.error("Failed to stop RelayQ component: component={}", component, error);
        }
    }
}
