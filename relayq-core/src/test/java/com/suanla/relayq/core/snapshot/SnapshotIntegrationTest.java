package com.suanla.relayq.core.snapshot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.suanla.relayq.core.config.RelayqProperties;
import com.suanla.relayq.core.domain.FailureKind;
import com.suanla.relayq.core.domain.TaskInfo;
import com.suanla.relayq.core.domain.TaskSnapshot;
import com.suanla.relayq.core.domain.TaskStatus;
import com.suanla.relayq.core.domain.TriggerType;
import com.suanla.relayq.core.mapper.TaskExecuteLogMapper;
import com.suanla.relayq.core.mapper.TaskInfoMapper;
import com.suanla.relayq.core.mapper.TaskSnapshotMapper;
import com.suanla.relayq.core.metrics.RelayqMetrics;
import com.suanla.relayq.core.service.TaskStateMachine;
import com.suanla.relayq.core.support.SnowflakeIdGenerator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/*
 * 不修改 MySQL 服务器时区；本类新增的 created_at 是纯审计时间，
 * 所有会和 NOW(3) 比较的任务与租约字段仍由数据库生成。
 */
@org.junit.jupiter.api.condition.EnabledIfSystemProperty(named = "relayq.mysql.tests", matches = "true")
class SnapshotIntegrationTest extends com.suanla.relayq.core.MySqlIntegrationSupport {

    private static final SnowflakeIdGenerator ID_GENERATOR = new SnowflakeIdGenerator(23);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();


    private TaskInfoMapper serviceTaskMapper;
    private TaskExecuteLogMapper serviceLogMapper;
    private TaskSnapshotMapper serviceSnapshotMapper;
    private TaskInfoMapper snapshotTaskMapper;
    private TaskSnapshotMapper snapshotWriterMapper;
    private SnapshotAdmission admission;

    @BeforeEach
    void setUp() {
        serviceTaskMapper = new com.suanla.relayq.core.jdbc.JdbcTaskInfoMapper(dataSource);
        serviceLogMapper = new com.suanla.relayq.core.jdbc.JdbcTaskExecuteLogMapper(dataSource);
        serviceSnapshotMapper = new com.suanla.relayq.core.jdbc.JdbcTaskSnapshotMapper(dataSource);
        snapshotTaskMapper = new com.suanla.relayq.core.jdbc.JdbcTaskInfoMapper(dataSource);
        snapshotWriterMapper = new com.suanla.relayq.core.jdbc.JdbcTaskSnapshotMapper(dataSource);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (admission != null) {
            admission.close();
        }
        cleanDatabase();
    }

    @Test
    void capturedSnapshotPersistsThreadDumpAndJsonStates() throws Exception {
        RelayqProperties.Snapshot properties = new RelayqProperties.Snapshot();
        RelayqMetrics metrics = metrics();
        admission = admission(properties, new SnapshotCollector(properties, metrics), metrics);
        TaskInfo task = insertTask(TaskStatus.PENDING, null, 0, 3, 2);

        admission.triggerManual(task.getId(), task.getCurrentAttemptNo());

        TaskSnapshot snapshot = awaitOneSnapshot(task.getId());
        assertFalse(snapshot.getThreadDump().isBlank());
        assertNotNull(OBJECT_MAPPER.readTree(snapshot.getPoolState()));
        assertNotNull(OBJECT_MAPPER.readTree(snapshot.getHeapState()));
        assertEquals(TriggerType.MANUAL, snapshot.getTriggerType());
        assertNull(snapshot.getThreadDumpOriginalLen());
    }

    @Test
    void oversizedDumpIsTruncatedOnUtf8BoundaryAndRecordsOriginalBytes() {
        RelayqProperties.Snapshot properties = new RelayqProperties.Snapshot();
        properties.setThreadDumpMaxBytes(10);
        RelayqMetrics metrics = metrics();
        String oversized = "故障现场".repeat(100);
        SnapshotCollector collector = new SnapshotCollector(properties, metrics) {
            @Override
            public SnapshotCapture collect(SnapshotTrigger trigger) {
                return new SnapshotCapture(
                        trigger,
                        oversized,
                        true,
                        Map.of("worker", Map.of("active", 1)),
                        Map.of("used", 1L),
                        0L);
            }
        };
        admission = admission(properties, collector, metrics);
        TaskInfo task = insertTask(TaskStatus.PENDING, null, 0, 3, 3);

        admission.triggerManual(task.getId(), task.getCurrentAttemptNo());

        TaskSnapshot snapshot = awaitOneSnapshot(task.getId());
        byte[] truncatedBytes = snapshot.getThreadDump().getBytes(StandardCharsets.UTF_8);
        assertTrue(truncatedBytes.length <= properties.getThreadDumpMaxBytes());
        assertEquals(
                oversized.getBytes(StandardCharsets.UTF_8).length,
                snapshot.getThreadDumpOriginalLen());
        assertEquals(
                snapshot.getThreadDump(),
                new String(truncatedBytes, StandardCharsets.UTF_8));
        assertFalse(snapshot.getThreadDump().contains("\uFFFD"));
    }

    @Test
    void enteringDeadAutomaticallyTriggersSnapshot() {
        RelayqProperties.Snapshot properties = new RelayqProperties.Snapshot();
        RelayqMetrics metrics = metrics();
        admission = admission(properties, new SnapshotCollector(properties, metrics), metrics);
        TaskInfo task = insertTask(TaskStatus.RUNNING, "dead-owner", 3, 3, 7);
        TaskStateMachine stateMachine = new TaskStateMachine(
                serviceTaskMapper,
                serviceLogMapper,
                ID_GENERATOR,
                64 * 1_024,
                taskId -> metrics.recordLeaseLost(),
                100,
                admission);

        assertTrue(stateMachine.fail(
                task,
                "dead-owner",
                FailureKind.BUSINESS_ERROR,
                new IllegalStateException("terminal failure"),
                LocalDateTime.now().minusSeconds(1),
                null));

        TaskSnapshot snapshot = awaitOneSnapshot(task.getId());
        assertEquals(TaskStatus.DEAD, readFreshTask(task.getId()).getStatus());
        assertEquals(TriggerType.DEAD, snapshot.getTriggerType());
        assertEquals(task.getCurrentAttemptNo(), snapshot.getAttemptNo());
        assertNotNull(snapshot.getExecuteLogId());
    }

    private RelayqMetrics metrics() {
        return new RelayqMetrics(
                null,
                snapshotTaskMapper,
                new RelayqProperties.Metrics());
    }

    private SnapshotAdmission admission(
            RelayqProperties.Snapshot properties,
            SnapshotCollector collector,
            RelayqMetrics metrics) {
        SnapshotWriter writer = new SnapshotWriter(
                snapshotWriterMapper,
                ID_GENERATOR,
                OBJECT_MAPPER,
                properties);
        return new SnapshotAdmission(properties, collector, writer, metrics);
    }

    private TaskInfo insertTask(
            TaskStatus status,
            String owner,
            int retryCount,
            int maxRetry,
            int attemptNo) {
        long taskId = ID_GENERATOR.nextId();
        String sql = """
                INSERT INTO task_info (
                    id, biz_key, handler_name, params, status, scheduled_time,
                    retry_count, max_retry, current_attempt_no, trace_id,
                    lease_owner, lease_expire_time
                )
                VALUES (
                    ?, ?, ?, ?, ?, DATE_SUB(NOW(3), INTERVAL 1 SECOND),
                    ?, ?, ?, ?, ?, DATE_ADD(NOW(3), INTERVAL 30 SECOND)
                )
                """;
        try (Connection connection = dataSource.getConnection();
             var statement = connection.prepareStatement(sql)) {
            statement.setLong(1, taskId);
            statement.setString(2, "snapshot-" + UUID.randomUUID());
            statement.setString(3, "snapshot-handler");
            statement.setString(4, "{}");
            statement.setString(5, status.name());
            statement.setInt(6, retryCount);
            statement.setInt(7, maxRetry);
            statement.setInt(8, attemptNo);
            statement.setString(9, UUID.randomUUID().toString().replace("-", ""));
            statement.setString(10, owner);
            assertEquals(1, statement.executeUpdate());
        } catch (SQLException error) {
            throw new IllegalStateException("failed to insert snapshot test task", error);
        }
        return readFreshTask(taskId);
    }

    private TaskInfo readFreshTask(long taskId) {
        return serviceTaskMapper.selectById(taskId);
    }

    private TaskSnapshot awaitOneSnapshot(long taskId) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        List<TaskSnapshot> snapshots;
        do {
            snapshots = serviceSnapshotMapper.selectByTaskId(taskId);
            if (!snapshots.isEmpty()) {
                assertEquals(1, snapshots.size());
                return snapshots.getFirst();
            }
            Thread.onSpinWait();
        } while (System.nanoTime() < deadline);
        assertFalse(snapshots.isEmpty());
        return snapshots.getFirst();
    }


}
