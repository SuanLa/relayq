package com.suanla.relayq.core.service;

import com.suanla.relayq.core.config.RelayqProperties;
import com.suanla.relayq.core.domain.ExecuteOutcome;
import com.suanla.relayq.core.domain.FailureKind;
import com.suanla.relayq.core.domain.TaskExecuteLog;
import com.suanla.relayq.core.domain.TaskInfo;
import com.suanla.relayq.core.domain.TaskStatus;
import com.suanla.relayq.core.exception.HandlerNotRegisteredException;
import com.suanla.relayq.core.exception.IllegalTaskStateException;
import com.suanla.relayq.core.handler.HandlerRegistry;
import com.suanla.relayq.core.mapper.TaskExecuteLogMapper;
import com.suanla.relayq.core.mapper.TaskInfoMapper;
import com.suanla.relayq.core.mapper.TaskSnapshotMapper;
import com.suanla.relayq.core.support.SnowflakeIdGenerator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/*
 * 使用用户指定的 MySQL 和本次隔离测试表，不修改服务器时区。
 * 调度与租约字段的测试造数一律使用数据库 NOW(3)，避免应用时间掩盖跨时区缺陷。
 */
@org.junit.jupiter.api.condition.EnabledIfSystemProperty(named = "relayq.mysql.tests", matches = "true")
class RelayqServiceIntegrationTest extends com.suanla.relayq.core.MySqlIntegrationSupport {

    private static final SnowflakeIdGenerator ID_GENERATOR = new SnowflakeIdGenerator(21);


    private TaskInfoMapper taskInfoMapper;
    private TaskSubmitService taskSubmitService;
    private TaskStateMachine taskStateMachine;
    private TaskQueryService taskQueryService;
    private DeadLetterService deadLetterService;

    @BeforeEach
    void setUp() {
        taskInfoMapper = new com.suanla.relayq.core.jdbc.JdbcTaskInfoMapper(dataSource);
        TaskExecuteLogMapper taskExecuteLogMapper = new com.suanla.relayq.core.jdbc.JdbcTaskExecuteLogMapper(dataSource);
        TaskSnapshotMapper taskSnapshotMapper = new com.suanla.relayq.core.jdbc.JdbcTaskSnapshotMapper(dataSource);

        HandlerRegistry handlerRegistry = new HandlerRegistry();
        handlerRegistry.register("registered-handler", context -> {
        });
        taskSubmitService = new TaskSubmitService(
                taskInfoMapper,
                handlerRegistry,
                ID_GENERATOR,
                new RelayqProperties());
        taskStateMachine = new TaskStateMachine(taskInfoMapper, taskExecuteLogMapper, ID_GENERATOR);
        taskQueryService = new TaskQueryService(
                taskInfoMapper,
                taskExecuteLogMapper,
                taskSnapshotMapper);
        deadLetterService = new DeadLetterService(taskInfoMapper, taskQueryService);
    }

    @AfterEach
    void tearDown() throws Exception {
        cleanDatabase();
    }

    @Test
    void duplicateBizKeyReturnsExistingTask() throws SQLException {
        String bizKey = uniqueBizKey();
        SubmitCommand command = command(bizKey, "registered-handler");

        SubmitResult first = taskSubmitService.submit(command);
        SubmitResult second = taskSubmitService.submit(command);

        assertFalse(first.alreadyExists());
        assertTrue(second.alreadyExists());
        assertEquals(first.taskId(), second.taskId());
        assertEquals(1L, countTasksByBizKey(bizKey));
    }

    @Test
    void unregisteredHandlerDoesNotInsertTask() throws SQLException {
        SubmitCommand command = command(uniqueBizKey(), "missing-handler");

        assertThrows(HandlerNotRegisteredException.class, () -> taskSubmitService.submit(command));

        assertEquals(0L, countAllTasks());
    }

    @Test
    void submissionUsesDatabaseClockForRelativeSchedulesAndPreservesAbsoluteTime() {
        SubmitResult immediate = taskSubmitService.submit(
                command(uniqueBizKey(), "registered-handler"));
        SubmitResult delayed = taskSubmitService.submit(new SubmitCommand(
                uniqueBizKey(),
                "registered-handler",
                "{}",
                null,
                5L,
                null));
        LocalDateTime absoluteTime = LocalDateTime.of(2030, 2, 3, 4, 5, 6, 123_000_000);
        SubmitResult absolute = taskSubmitService.submit(new SubmitCommand(
                uniqueBizKey(),
                "registered-handler",
                "{}",
                absoluteTime,
                99L,
                null));

        TaskInfo immediateTask = readFreshTask(immediate.taskId());
        TaskInfo delayedTask = readFreshTask(delayed.taskId());
        assertEquals(
                0L,
                Duration.between(
                        immediateTask.getCreatedAt(), immediateTask.getScheduledTime()).toMillis());
        assertEquals(
                5_000L,
                Duration.between(
                        delayedTask.getCreatedAt(), delayedTask.getScheduledTime()).toMillis());
        assertEquals(absoluteTime, absolute.scheduledTime());
    }

    @Test
    void cancelRequiresPendingState() {
        SubmitResult pending = taskSubmitService.submit(command(uniqueBizKey(), "registered-handler"));
        assertTrue(taskStateMachine.cancel(pending.taskId()));
        assertEquals(TaskStatus.CANCELLED, readFreshTask(pending.taskId()).getStatus());

        SubmitResult running = taskSubmitService.submit(command(uniqueBizKey(), "registered-handler"));
        assertEquals(1, taskInfoMapper.markRunning(List.of(running.taskId()), "owner-a", 30));

        IllegalTaskStateException error = assertThrows(
                IllegalTaskStateException.class,
                () -> taskStateMachine.cancel(running.taskId()));
        assertEquals(running.taskId(), error.getTaskId());
        assertEquals(TaskStatus.RUNNING, error.getActualStatus());
    }

    @Test
    void redriveDeadLetterOnlyOnce() {
        TaskInfo dead = insertTask(TaskStatus.DEAD, null, 3, 4, 9);

        deadLetterService.redrive(dead.getId(), "operator-a", "修复参数后重投");

        TaskInfo redriven = readFreshTask(dead.getId());
        assertEquals(TaskStatus.PENDING, redriven.getStatus());
        assertEquals(0, redriven.getRetryCount());
        assertEquals("operator-a", redriven.getRedriveBy());
        assertEquals("修复参数后重投", redriven.getRedriveReason());
        assertTrue(redriven.getRedriveAt() != null);

        IllegalTaskStateException error = assertThrows(
                IllegalTaskStateException.class,
                () -> deadLetterService.redrive(dead.getId(), "operator-a", "重复点击"));
        assertEquals(TaskStatus.PENDING, error.getActualStatus());
    }

    @Test
    void wrongOwnerIsFencedAndWritesLeaseLostAudit() {
        TaskInfo running = insertTask(TaskStatus.RUNNING, "owner-a", 1, 3, 6);

        boolean accepted = taskStateMachine.succeed(
                running,
                "owner-b",
                LocalDateTime.now().minusSeconds(1));

        assertFalse(accepted);
        TaskInfo unchanged = readFreshTask(running.getId());
        assertEquals(TaskStatus.RUNNING, unchanged.getStatus());
        assertEquals("owner-a", unchanged.getLeaseOwner());
        List<TaskExecuteLog> logs = readFreshExecuteLogs(running.getId());
        assertEquals(1, logs.size());
        assertEquals(ExecuteOutcome.LEASE_LOST, logs.getFirst().getOutcome());
        assertEquals("owner-b", logs.getFirst().getWorkerInstance());
    }

    @Test
    void leaseLostUsesClaimedAttemptNumberInsteadOfRetryCount() {
        TaskInfo running = insertTask(TaskStatus.RUNNING, "owner-a", 41, 50, 7);

        assertFalse(taskStateMachine.succeed(
                running,
                "owner-b",
                LocalDateTime.now().minusSeconds(1)));

        TaskExecuteLog log = readFreshExecuteLogs(running.getId()).getFirst();
        assertEquals(7, log.getAttemptNo());
        assertNotEquals(running.getRetryCount() + 1, log.getAttemptNo());
        assertNull(log.getErrorStackOriginalLen());
    }

    @Test
    void nonRetryableFailureGoesDeadAndWritesFailedAudit() {
        TaskInfo running = insertTask(TaskStatus.RUNNING, "owner-a", 0, 5, 1);
        IllegalArgumentException failure =
                new IllegalArgumentException("Invalid task parameters");

        assertTrue(taskStateMachine.fail(
                running,
                "owner-a",
                FailureKind.PARAM_DESERIALIZE_FAILED,
                failure,
                LocalDateTime.now().minusSeconds(1),
                null));

        TaskInfo dead = readFreshTask(running.getId());
        assertEquals(TaskStatus.DEAD, dead.getStatus());
        assertEquals(0, dead.getRetryCount());
        List<TaskExecuteLog> logs = readFreshExecuteLogs(running.getId());
        assertEquals(1, logs.size());
        assertEquals(ExecuteOutcome.FAILED, logs.getFirst().getOutcome());
        assertEquals(
                FailureKind.PARAM_DESERIALIZE_FAILED,
                logs.getFirst().getFailureKind());
        assertEquals(1, logs.getFirst().getAttemptNo());
    }

    private SubmitCommand command(String bizKey, String handlerName) {
        return new SubmitCommand(bizKey, handlerName, "{}", null, null, null);
    }

    private TaskInfo insertTask(
            TaskStatus status,
            String leaseOwner,
            int retryCount,
            int maxRetry,
            int currentAttemptNo) {
        long taskId = ID_GENERATOR.nextId();
        String sql = """
                INSERT INTO task_info (
                    id, biz_key, handler_name, params, status, scheduled_time,
                    retry_count, max_retry, current_attempt_no, trace_id,
                    lease_owner, lease_expire_time
                )
                VALUES (
                    ?, ?, ?, ?, ?, DATE_SUB(NOW(3), INTERVAL 1 SECOND),
                    ?, ?, ?, ?, ?, DATE_ADD(NOW(3), INTERVAL ? SECOND)
                )
                """;
        try (Connection connection = dataSource.getConnection();
             var statement = connection.prepareStatement(sql)) {
            statement.setLong(1, taskId);
            statement.setString(2, uniqueBizKey());
            statement.setString(3, "registered-handler");
            statement.setString(4, "{}");
            statement.setString(5, status.name());
            statement.setInt(6, retryCount);
            statement.setInt(7, maxRetry);
            statement.setInt(8, currentAttemptNo);
            statement.setString(9, UUID.randomUUID().toString().replace("-", ""));
            statement.setString(10, leaseOwner);
            if (leaseOwner == null) {
                statement.setNull(11, java.sql.Types.BIGINT);
            } else {
                statement.setLong(11, 30L);
            }
            assertEquals(1, statement.executeUpdate());
        } catch (SQLException error) {
            throw new IllegalStateException("failed to insert service test task", error);
        }
        return readFreshTask(taskId);
    }

    private TaskInfo readFreshTask(long taskId) {
        return taskQueryService.getById(taskId);
    }

    private List<TaskExecuteLog> readFreshExecuteLogs(long taskId) {
        return taskQueryService.listExecuteLogs(taskId);
    }

    private long countTasksByBizKey(String bizKey) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             var statement = connection.prepareStatement(
                "SELECT COUNT(*) FROM task_info WHERE biz_key = ?")) {
            statement.setString(1, bizKey);
            try (var resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getLong(1);
            }
        }
    }

    private long countAllTasks() throws SQLException {
        try (Connection connection = dataSource.getConnection();
             var statement = connection.createStatement();
             var resultSet = statement.executeQuery("SELECT COUNT(*) FROM task_info")) {
            resultSet.next();
            return resultSet.getLong(1);
        }
    }

    private String uniqueBizKey() {
        return "biz-" + UUID.randomUUID();
    }
}
