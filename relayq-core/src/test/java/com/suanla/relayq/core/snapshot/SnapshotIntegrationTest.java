package com.suanla.relayq.core.snapshot;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
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

@SpringBootTest
/*
 * 不给容器设置 TZ；本类新增的 created_at 是纯审计时间，
 * 所有会和 NOW(3) 比较的任务与租约字段仍由数据库生成。
 */
class SnapshotIntegrationTest {

    private static final String JDBC_URL = System.getProperty(
            "relayq.test.jdbc-url",
            "jdbc:mysql://192.168.0.105:3307/relayq_scheduler_test");
    private static final String USERNAME = System.getProperty("relayq.test.username", "relayq");
    private static final String PASSWORD = System.getProperty("relayq.test.password", "relayq");

    private static final SnowflakeIdGenerator ID_GENERATOR = new SnowflakeIdGenerator(23);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private static SqlSessionFactory sqlSessionFactory;

    private SqlSession serviceSession;
    private SqlSession snapshotSession;
    private TaskInfoMapper serviceTaskMapper;
    private TaskExecuteLogMapper serviceLogMapper;
    private TaskSnapshotMapper serviceSnapshotMapper;
    private TaskInfoMapper snapshotTaskMapper;
    private TaskSnapshotMapper snapshotWriterMapper;
    private SnapshotAdmission admission;

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
                "snapshot-testcontainers",
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
        serviceSession = sqlSessionFactory.openSession(true);
        snapshotSession = sqlSessionFactory.openSession(true);
        serviceTaskMapper = serviceSession.getMapper(TaskInfoMapper.class);
        serviceLogMapper = serviceSession.getMapper(TaskExecuteLogMapper.class);
        serviceSnapshotMapper = serviceSession.getMapper(TaskSnapshotMapper.class);
        snapshotTaskMapper = snapshotSession.getMapper(TaskInfoMapper.class);
        snapshotWriterMapper = snapshotSession.getMapper(TaskSnapshotMapper.class);
    }

    @AfterEach
    void tearDown() throws SQLException {
        if (admission != null) {
            admission.close();
        }
        if (serviceSession != null) {
            try (Statement statement = serviceSession.getConnection().createStatement()) {
                statement.executeUpdate("DELETE FROM task_snapshot");
                statement.executeUpdate("DELETE FROM task_execute_log");
                statement.executeUpdate("DELETE FROM task_info");
            } finally {
                serviceSession.close();
            }
        }
        if (snapshotSession != null) {
            snapshotSession.close();
        }
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
        try (var statement = serviceSession.getConnection().prepareStatement(sql)) {
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
        serviceSession.clearCache();
        return serviceTaskMapper.selectById(taskId);
    }

    private TaskSnapshot awaitOneSnapshot(long taskId) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        List<TaskSnapshot> snapshots;
        do {
            // 后台 writer 使用另一 SqlSession，读取前必须清理当前 session 的一级缓存。
            serviceSession.clearCache();
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

    private static void parseMapper(MybatisConfiguration configuration, String resource)
            throws IOException {
        try (Reader reader = Resources.getResourceAsReader(resource)) {
            new XMLMapperBuilder(
                    reader,
                    configuration,
                    resource,
                    configuration.getSqlFragments())
                    .parse();
        }
    }
}
