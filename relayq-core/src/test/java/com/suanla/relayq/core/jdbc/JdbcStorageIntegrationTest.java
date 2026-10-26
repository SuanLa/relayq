package com.suanla.relayq.core.jdbc;

import com.suanla.relayq.core.MySqlIntegrationSupport;
import com.suanla.relayq.core.domain.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

@org.junit.jupiter.api.condition.EnabledIfSystemProperty(named = "relayq.mysql.tests", matches = "true")
class JdbcStorageIntegrationTest extends MySqlIntegrationSupport {
    private JdbcTaskInfoMapper tasks;
    private JdbcTaskExecuteLogMapper logs;
    private JdbcTaskSnapshotMapper snapshots;
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        tasks = new JdbcTaskInfoMapper(dataSource);
        logs = new JdbcTaskExecuteLogMapper(dataSource);
        snapshots = new JdbcTaskSnapshotMapper(dataSource);
        jdbc = new JdbcTemplate(dataSource);
    }

    @AfterEach
    void tearDown() {
        cleanDatabase();
    }

    @Test
    void roundTripsAuditAndGlobalSnapshotIncludingDefaultsAndNulls() {
        submit(1);
        TaskExecuteLog log = audit(1, 1);
        log.setFailureKind(FailureKind.BUSINESS_ERROR);
        log.setErrorStack("错误堆栈");
        log.setErrorStackOriginalLen(123);
        assertEquals(1, logs.insert(log));
        TaskExecuteLog saved = logs.selectByTaskAndAttempt(1, 1);
        assertEquals(ExecuteOutcome.SUCCESS, saved.getOutcome());
        assertEquals(FailureKind.BUSINESS_ERROR, saved.getFailureKind());
        assertEquals(log.getStartTime(), saved.getStartTime());
        assertEquals("错误堆栈", saved.getErrorStack());
        assertNotNull(saved.getCreatedAt());
        assertEquals(1, logs.selectByTaskId(1).size());
        assertThrows(DuplicateKeyException.class, () -> logs.insert(audit(2, 1)));

        TaskSnapshot snapshot = new TaskSnapshot();
        snapshot.setId(3L);
        snapshot.setTriggerType(TriggerType.MANUAL);
        snapshot.setPoolState("{\"active\":1}");
        snapshots.insert(snapshot);
        assertEquals(1, jdbc.queryForObject("SELECT lock_info_included FROM task_snapshot WHERE id=3", Integer.class));
        assertNull(jdbc.queryForObject("SELECT task_id FROM task_snapshot WHERE id=3", Long.class));
        snapshot.setId(4L);
        snapshot.setTaskId(1L);
        snapshot.setLockInfoIncluded(false);
        snapshots.insert(snapshot);
        TaskSnapshot read = snapshots.selectByTaskId(1).getFirst();
        assertFalse(read.getLockInfoIncluded());
        assertEquals(TriggerType.MANUAL, read.getTriggerType());
        assertNotNull(read.getCreatedAt());
        assertNull(read.getExecuteLogId());
    }

    @Test
    void delayedSchedulesRetryRenewalAndFencingRetainDatabaseSemantics() {
        submit(1);
        assertEquals(1, tasks.markRunning(List.of(1L), "old-owner", 30));
        assertEquals(0, tasks.renewLease(List.of(1L), "wrong-owner", 60));
        assertEquals(1, tasks.renewLease(List.of(1L), "old-owner", 60));
        assertEquals(1, tasks.requeueForRetry(1, 1500, "failure", FailureKind.BUSINESS_ERROR, "old-owner"));
        TaskInfo pending = tasks.selectById(1);
        assertEquals(1, pending.getRetryCount());
        assertEquals(pending.getScheduledTime(), pending.getNextRetryTime());
        assertEquals(1500, Duration.between(pending.getUpdatedAt(), pending.getScheduledTime()).toMillis());
        assertNull(pending.getLeaseOwner());
        assertEquals(1, tasks.markRunning(List.of(1L), "new-owner", 30));
        assertEquals(2, tasks.selectById(1).getCurrentAttemptNo());
        assertEquals(0, tasks.finishWithFencing(1, TaskStatus.SUCCESS, null, null, "old-owner"));
        assertEquals(1, tasks.finishWithFencing(1, TaskStatus.SUCCESS, null, null, "new-owner"));
        TaskInfo success = tasks.selectById(1);
        assertNull(success.getErrorMsg());
        assertNull(success.getLastFailureKind());
        assertNull(success.getNextRetryTime());
        assertNull(success.getLeaseExpireTime());
    }

    @Test
    void expiredCandidateIsRecheckedBeforeReclaim() {
        submit(1);
        tasks.markRunning(List.of(1L), "owner", 30);
        jdbc.update("UPDATE task_info SET lease_expire_time=DATE_SUB(NOW(3), INTERVAL 1 SECOND) WHERE id=1");
        assertEquals(List.of(1L), tasks.selectExpiredLeaseIds(10));
        tasks.renewLease(List.of(1L), "owner", 60);
        assertEquals(0, tasks.reclaimExpiredLeasesByIds(List.of(1L)));
        assertEquals(TaskStatus.RUNNING, tasks.selectById(1).getStatus());
    }

    @Test
    void queriesPaginationAndActionableCountsKeepTheirMeaning() {
        submit(1);
        submit(2);
        submit(3);
        jdbc.update("UPDATE task_info SET scheduled_time=DATE_ADD(NOW(3), INTERVAL 1 DAY) WHERE id=2");
        tasks.markRunning(List.of(3L), "owner", 30);
        var counts = tasks.countActionableBacklog().stream().collect(
                java.util.stream.Collectors.toMap(com.suanla.relayq.core.metrics.TaskStatusCount::getStatus,
                        com.suanla.relayq.core.metrics.TaskStatusCount::getCount));
        assertEquals(1L, counts.get(TaskStatus.PENDING));
        assertEquals(1L, counts.get(TaskStatus.RUNNING));
        assertEquals(0L, counts.get(TaskStatus.DEAD));
        assertEquals(2, tasks.countByStatus(TaskStatus.PENDING));
        assertEquals(2L, tasks.selectPageByStatus(TaskStatus.PENDING, 0, 1).getFirst().getId());
        assertEquals(1L, tasks.selectPageByStatus(TaskStatus.PENDING, 1, 1).getFirst().getId());
        assertEquals(1L, tasks.selectByBizKey("jdbc-1").getId());
        assertEquals(2, tasks.selectByIdList(List.of(1L, 3L)).size());
        assertNull(tasks.selectById(999));
        assertNull(tasks.selectByBizKey("absent"));
    }

    @Test
    void rollbackUndoesClaimAndAuditAndSkipLockedUsesIndependentTransactions() throws Exception {
        submit(1);
        submit(2);
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            Future<List<Long>> first = executor.submit(() -> transaction().execute(status -> {
                List<Long> ids = tasks.selectDueIdsForUpdateSkipLocked(1);
                tasks.markRunning(ids, "owner-a", 30);
                locked.countDown();
                try {
                    if (!release.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("second transaction did not finish");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
                status.setRollbackOnly();
                return ids;
            }));
            try {
                assertTrue(locked.await(5, TimeUnit.SECONDS));
                List<Long> other = transaction().execute(status -> {
                    List<Long> ids = tasks.selectDueIdsForUpdateSkipLocked(10);
                    assertEquals(1, ids.size());
                    tasks.markRunning(ids, "owner-b", 30);
                    return ids;
                });
                release.countDown();
                List<Long> rolledBack = first.get(5, TimeUnit.SECONDS);
                assertNotEquals(rolledBack, other);
                assertEquals(TaskStatus.PENDING, tasks.selectById(rolledBack.getFirst()).getStatus());
                assertEquals(0, tasks.selectById(rolledBack.getFirst()).getCurrentAttemptNo());
                assertEquals(TaskStatus.RUNNING, tasks.selectById(other.getFirst()).getStatus());
            } finally {
                release.countDown();
            }
        }
        transaction().executeWithoutResult(status -> {
            tasks.cancelPending(1);
            logs.insert(audit(3, 1));
            status.setRollbackOnly();
        });
        assertNull(logs.selectByTaskAndAttempt(1, 1));
    }

    private TransactionTemplate transaction() {
        TransactionTemplate tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        return tx;
    }

    private void submit(long id) {
        TaskInfo task = new TaskInfo();
        task.setId(id);
        task.setBizKey("jdbc-" + id);
        task.setHandlerName("test");
        task.setStatus(TaskStatus.PENDING);
        task.setRetryCount(0);
        task.setMaxRetry(3);
        task.setCurrentAttemptNo(0);
        tasks.insertForSubmission(task, null, null);
    }

    private TaskExecuteLog audit(long id, long taskId) {
        TaskExecuteLog log = new TaskExecuteLog();
        log.setId(id);
        log.setTaskId(taskId);
        log.setAttemptNo(1);
        log.setOutcome(ExecuteOutcome.SUCCESS);
        log.setWorkerInstance("owner");
        log.setStartTime(LocalDateTime.of(2026, 9, 14, 12, 0, 0, 123000000));
        return log;
    }
}
