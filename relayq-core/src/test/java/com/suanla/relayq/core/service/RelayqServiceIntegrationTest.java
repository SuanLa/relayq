package com.suanla.relayq.core.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.suanla.relayq.core.TestDatabaseProperties;
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
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.datasource.pooled.PooledDataSource;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.jdbc.ScriptRunner;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.IOException;
import java.io.Reader;
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
 * 这里刻意不给 MySQL 容器设置 TZ，让裸容器 UTC 与开发机 JVM 时区保持错位。
 * 调度与租约字段的测试造数一律使用数据库 NOW(3)，避免应用时间掩盖跨时区缺陷。
 */
class RelayqServiceIntegrationTest {

    private static final String JDBC_URL = TestDatabaseProperties.jdbcUrl("relayq_test");
    private static final String USERNAME = TestDatabaseProperties.get("relayq.test.username", "root");
    private static final String PASSWORD = TestDatabaseProperties.get("relayq.test.password", "root123456");

    private static final SnowflakeIdGenerator ID_GENERATOR = new SnowflakeIdGenerator(21);

    private static SqlSessionFactory sqlSessionFactory;

    private SqlSession sqlSession;
    private TaskInfoMapper taskInfoMapper;
    private TaskSubmitService taskSubmitService;
    private TaskStateMachine taskStateMachine;
    private TaskQueryService taskQueryService;
    private DeadLetterService deadLetterService;

    @BeforeAll
    static void createSqlSessionFactory() throws IOException {
        PooledDataSource dataSource = new PooledDataSource(
                "com.mysql.cj.jdbc.Driver", JDBC_URL, USERNAME, PASSWORD);
        try (Connection conn = dataSource.getConnection();
             Reader reader = Resources.getResourceAsReader("db/schema.sql")) {
            ScriptRunner runner = new ScriptRunner(conn);
            runner.setLogWriter(null);
            runner.setErrorLogWriter(null);
            runner.runScript(reader);
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }

        Environment environment = new Environment(
                "testcontainers",
                new JdbcTransactionFactory(),
                dataSource);
        MybatisConfiguration configuration = new MybatisConfiguration(environment);
        configuration.addMapper(TaskInfoMapper.class);
        configuration.addMapper(TaskExecuteLogMapper.class);
        configuration.addMapper(TaskSnapshotMapper.class);
        parseMapper(configuration, "mapper/TaskInfoMapper.xml");
        parseMapper(configuration, "mapper/TaskExecuteLogMapper.xml");
        parseMapper(configuration, "mapper/TaskSnapshotMapper.xml");
        sqlSessionFactory = new MybatisSqlSessionFactoryBuilder().build(configuration);
    }

    @BeforeEach
    void setUp() {
        sqlSession = sqlSessionFactory.openSession(true);
        taskInfoMapper = sqlSession.getMapper(TaskInfoMapper.class);
        TaskExecuteLogMapper taskExecuteLogMapper = sqlSession.getMapper(TaskExecuteLogMapper.class);
        TaskSnapshotMapper taskSnapshotMapper = sqlSession.getMapper(TaskSnapshotMapper.class);

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
    void tearDown() throws SQLException {
        if (sqlSession == null) {
            return;
        }
        try (Statement statement = sqlSession.getConnection().createStatement()) {
            statement.executeUpdate("DELETE FROM task_snapshot");
            statement.executeUpdate("DELETE FROM task_execute_log");
            statement.executeUpdate("DELETE FROM task_info");
        } finally {
            sqlSession.close();
        }
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

    private static void parseMapper(MybatisConfiguration configuration, String resource) throws IOException {
        try (Reader reader = Resources.getResourceAsReader(resource)) {
            new XMLMapperBuilder(
                    reader,
                    configuration,
                    resource,
                    configuration.getSqlFragments())
                    .parse();
        }
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
        try (var statement = sqlSession.getConnection().prepareStatement(sql)) {
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

    /*
     * 测试会长期复用同一个 SqlSession，其他 session 或裸 JDBC 的变更不会让 MyBatis 一级缓存失效。
     * 验证数据库当前状态前必须显式清理缓存，避免断言读到之前查询留下的旧对象。
     */
    private TaskInfo readFreshTask(long taskId) {
        sqlSession.clearCache();
        return taskQueryService.getById(taskId);
    }

    // 执行日志 Mapper 与任务 Mapper 共用 sqlSession，验证读取也必须清理同一份一级缓存。
    private List<TaskExecuteLog> readFreshExecuteLogs(long taskId) {
        sqlSession.clearCache();
        return taskQueryService.listExecuteLogs(taskId);
    }

    private long countTasksByBizKey(String bizKey) throws SQLException {
        try (var statement = sqlSession.getConnection().prepareStatement(
                "SELECT COUNT(*) FROM task_info WHERE biz_key = ?")) {
            statement.setString(1, bizKey);
            try (var resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getLong(1);
            }
        }
    }

    private long countAllTasks() throws SQLException {
        try (var statement = sqlSession.getConnection().createStatement();
             var resultSet = statement.executeQuery("SELECT COUNT(*) FROM task_info")) {
            resultSet.next();
            return resultSet.getLong(1);
        }
    }

    private String uniqueBizKey() {
        return "biz-" + UUID.randomUUID();
    }
}
