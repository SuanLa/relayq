package com.suanla.relayq.core.scheduler;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.suanla.relayq.core.config.RelayqProperties;
import com.suanla.relayq.core.domain.TaskInfo;
import com.suanla.relayq.core.domain.TaskStatus;
import com.suanla.relayq.core.executor.RequeueRejectedHandler;
import com.suanla.relayq.core.executor.TaskDispatcher;
import com.suanla.relayq.core.executor.TaskExecutionRunnable;
import com.suanla.relayq.core.executor.TaskIdentifiedRunnable;
import com.suanla.relayq.core.executor.TaskWorkerPool;
import com.suanla.relayq.core.handler.HandlerRegistry;
import com.suanla.relayq.core.handler.TaskHandler;
import com.suanla.relayq.core.mapper.TaskExecuteLogMapper;
import com.suanla.relayq.core.mapper.TaskInfoMapper;
import com.suanla.relayq.core.mapper.TaskSnapshotMapper;
import com.suanla.relayq.core.metrics.RelayqMetrics;
import com.suanla.relayq.core.retry.ExponentialJitterBackoff;
import com.suanla.relayq.core.retry.RetryDecider;
import com.suanla.relayq.core.service.SubmitCommand;
import com.suanla.relayq.core.service.SubmitResult;
import com.suanla.relayq.core.service.TaskSubmitService;
import com.suanla.relayq.core.service.TaskStateMachine;
import com.suanla.relayq.core.support.SnowflakeIdGenerator;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.datasource.pooled.PooledDataSource;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import java.io.IOException;
import java.io.Reader;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

@Testcontainers(disabledWithoutDocker = true)
/*
 * 这里刻意不给 MySQL 容器设置 TZ，让裸容器 UTC 与开发机 JVM 时区保持错位。
 * 所有参与调度和租约比较的测试数据都基于数据库 NOW(3) 生成，使本类同时承担时钟漂移回归测试。
 */
class SchedulerIntegrationTest {

    @Container
    private static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4")
            .withDatabaseName("relayq_scheduler_test")
            .withUsername("relayq")
            .withPassword("relayq")
            .withInitScript("db/schema.sql");

    private static final SnowflakeIdGenerator ID_GENERATOR = new SnowflakeIdGenerator(22);

    private static SqlSessionFactory sqlSessionFactory;

    private final List<AutoCloseable> closeables = new ArrayList<>();

    private SqlSession pullSession;
    private SqlSession serviceSession;
    private TaskInfoMapper pullMapper;
    private TaskInfoMapper serviceMapper;
    private TaskExecuteLogMapper executeLogMapper;

    @BeforeAll
    static void createSqlSessionFactory() throws IOException {
        PooledDataSource dataSource = new PooledDataSource(
                MYSQL.getDriverClassName(),
                MYSQL.getJdbcUrl(),
                MYSQL.getUsername(),
                MYSQL.getPassword());
        Environment environment = new Environment(
                "scheduler-testcontainers",
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
        // puller 独占一个非自动提交 session，测试事务管理器才能精确控制同一连接的隔离级别。
        pullSession = sqlSessionFactory.openSession(false);
        serviceSession = sqlSessionFactory.openSession(true);
        pullMapper = pullSession.getMapper(TaskInfoMapper.class);
        serviceMapper = serviceSession.getMapper(TaskInfoMapper.class);
        executeLogMapper = serviceSession.getMapper(TaskExecuteLogMapper.class);
    }

    @AfterEach
    void tearDown() throws Exception {
        for (int index = closeables.size() - 1; index >= 0; index--) {
            closeables.get(index).close();
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
        if (pullSession != null) {
            pullSession.close();
        }
    }

    @Test
    void pullTransactionUsesReadCommittedAgainstRepeatableReadServer() throws SQLException {
        AtomicReference<String> observedIsolation = new AtomicReference<>();
        TaskInfoMapper probingMapper = isolationProbingMapper(observedIsolation);
        RelayqProperties properties = properties("isolation-owner", 1, 1, 1, 10);
        KernelFixture kernel = kernel(properties, context -> {
        }, null);
        TaskPuller puller = puller(probingMapper, kernel, properties);

        assertEquals(0, puller.pullOnce());

        assertEquals("READ-COMMITTED", observedIsolation.get());
        try (Statement statement = serviceSession.getConnection().createStatement();
             var resultSet = statement.executeQuery("SELECT @@transaction_isolation")) {
            assertTrue(resultSet.next());
            assertEquals("REPEATABLE-READ", resultSet.getString(1));
        }
    }

    @Test
    void dueTasksAreClaimedWithOwnerAndNewAttemptIdentity() throws Exception {
        int taskCount = 5;
        CountDownLatch handlerEntered = new CountDownLatch(1);
        CountDownLatch releaseHandler = new CountDownLatch(1);
        RelayqProperties properties = properties("claim-owner", 1, 1, 8, taskCount);
        KernelFixture kernel = kernel(properties, context -> {
            handlerEntered.countDown();
            releaseHandler.await(5, TimeUnit.SECONDS);
        }, null);
        List<TaskInfo> inserted = new ArrayList<>();
        for (int index = 0; index < taskCount; index++) {
            inserted.add(insertTask(
                    TaskStatus.PENDING,
                    -1L,
                    null,
                    0,
                    index));
        }
        TaskPuller puller = puller(pullMapper, kernel, properties);

        assertEquals(taskCount, puller.pullOnce());
        assertTrue(handlerEntered.await(5, TimeUnit.SECONDS));

        for (TaskInfo original : inserted) {
            TaskInfo claimed = readFreshTask(original.getId());
            assertEquals(TaskStatus.RUNNING, claimed.getStatus());
            assertEquals("claim-owner", claimed.getLeaseOwner());
            assertEquals(original.getCurrentAttemptNo() + 1, claimed.getCurrentAttemptNo());
        }
        releaseHandler.countDown();
    }

    @Test
    void futureTaskIsNotClaimed() {
        RelayqProperties properties = properties("future-owner", 1, 1, 1, 10);
        KernelFixture kernel = kernel(properties, context -> {
        }, null);
        TaskInfo future = insertTask(
                TaskStatus.PENDING,
                300L,
                null,
                0,
                0);
        TaskPuller puller = puller(pullMapper, kernel, properties);

        assertEquals(0, puller.pullOnce());

        TaskInfo unchanged = readFreshTask(future.getId());
        assertEquals(TaskStatus.PENDING, unchanged.getStatus());
        assertNull(unchanged.getLeaseOwner());
        assertEquals(0, unchanged.getCurrentAttemptNo());
    }

    @Test
    void fullWorkerPoolRequeuesRemainingTasksInOneBatch() throws Exception {
        RelayqProperties properties = properties("reject-owner", 1, 1, 1, 10);
        TaskStateMachine stateMachine = spy(new TaskStateMachine(
                serviceMapper,
                executeLogMapper,
                ID_GENERATOR));
        KernelFixture kernel = kernel(properties, context -> {
        }, stateMachine);
        TaskInfo first = insertTask(
                TaskStatus.RUNNING,
                -1L,
                "reject-owner",
                2,
                1);
        TaskInfo second = insertTask(
                TaskStatus.RUNNING,
                -1L,
                "reject-owner",
                2,
                1);
        assertEquals(2, kernel.workerPool().tryReserve(2));
        CountDownLatch releaseFillers = saturateReservedWorkerPool(kernel.workerPool());
        List<TaskIdentifiedRunnable> tasks = List.of(
                new TaskExecutionRunnable(first.getId(), () -> {
                }),
                new TaskExecutionRunnable(second.getId(), () -> {
                }));

        assertEquals(0, kernel.workerPool().submitReservedBatch(tasks));
        releaseFillers.countDown();

        verify(stateMachine, timeout(2_000).times(1)).requeueRejected(
                argThat(ids -> ids.size() == 2
                        && ids.contains(first.getId())
                        && ids.contains(second.getId())),
                eq("reject-owner"));
        awaitStatus(first.getId(), TaskStatus.PENDING);
        awaitStatus(second.getId(), TaskStatus.PENDING);
        assertEquals(2, readFreshTask(first.getId()).getRetryCount());
        assertEquals(2, readFreshTask(second.getId()).getRetryCount());
    }

    @Test
    void reaperReclaimsExpiredLeaseWithoutChangingRetryCount() {
        RelayqProperties properties = properties("reaper-owner", 1, 1, 1, 10);
        TaskInfo expired = insertTask(
                TaskStatus.RUNNING,
                -1L,
                "dead-worker",
                -10L,
                4,
                9);
        LeaseReaper reaper = new LeaseReaper(serviceMapper, properties.getLease());
        closeables.add(reaper);

        assertEquals(1, reaper.reapOnce());

        TaskInfo reclaimed = readFreshTask(expired.getId());
        assertEquals(TaskStatus.PENDING, reclaimed.getStatus());
        assertNull(reclaimed.getLeaseOwner());
        assertNull(reclaimed.getLeaseExpireTime());
        assertEquals(4, reclaimed.getRetryCount());
    }

    @Test
    void dbTimeControlsClaimAndReapingWhenJvmAndContainerTimeZonesDiffer() throws Exception {
        LocalDateTime databaseNow = selectDatabaseNow();
        LocalDateTime applicationNow = LocalDateTime.now();
        long clockDifferenceMillis = Math.abs(
                Duration.between(databaseNow, applicationNow).toMillis());
        assumeTrue(
                clockDifferenceMillis > Duration.ofMinutes(1).toMillis(),
                "DB and JVM wall clocks are aligned; timezone-drift scenario is unavailable");
        assertTrue(clockDifferenceMillis > Duration.ofMinutes(1).toMillis());

        RelayqProperties properties = properties("drift-owner", 1, 1, 2, 10);
        HandlerRegistry submitRegistry = new HandlerRegistry();
        submitRegistry.register("integration-handler", context -> {
        });
        TaskSubmitService submitService = new TaskSubmitService(
                serviceMapper,
                submitRegistry,
                ID_GENERATOR,
                properties);
        CountDownLatch handlerEntered = new CountDownLatch(1);
        CountDownLatch releaseHandler = new CountDownLatch(1);
        KernelFixture kernel = kernel(properties, context -> {
            handlerEntered.countDown();
            releaseHandler.await(5, TimeUnit.SECONDS);
        }, null);
        TaskPuller puller = puller(pullMapper, kernel, properties);
        SubmitResult submitted = submitService.submit(new SubmitCommand(
                "drift-submit-" + UUID.randomUUID(),
                "integration-handler",
                "{}",
                null,
                null,
                null));

        assertEquals(1, puller.pullOnce());
        assertTrue(handlerEntered.await(5, TimeUnit.SECONDS));
        assertEquals(TaskStatus.RUNNING, readFreshTask(submitted.taskId()).getStatus());

        TaskInfo expired = insertTask(
                TaskStatus.RUNNING,
                -1L,
                "expired-drift-owner",
                -10L,
                7,
                11);
        LeaseReaper reaper = new LeaseReaper(serviceMapper, properties.getLease());
        closeables.add(reaper);
        assertEquals(1, reaper.reapOnce());
        TaskInfo reclaimed = readFreshTask(expired.getId());
        assertEquals(TaskStatus.PENDING, reclaimed.getStatus());
        assertEquals(7, reclaimed.getRetryCount());
        releaseHandler.countDown();
    }

    @Test
    void immediateSubmissionInterruptsLongEmptyBackoff() throws Exception {
        RelayqProperties properties = properties("signal-owner", 1, 1, 2, 10);
        properties.getPull().setIntervalMs(30_000L);
        properties.getPull().setEmptyBackoffMaxMs(30_000L);
        CoalescingTaskAvailabilitySignal signal = new CoalescingTaskAvailabilitySignal();
        CountDownLatch emptyPullObserved = new CountDownLatch(1);
        CountDownLatch handlerExecuted = new CountDownLatch(1);
        KernelFixture kernel = kernel(
                properties,
                context -> handlerExecuted.countDown(),
                null);
        TaskPuller puller = puller(
                emptyPullObservingMapper(emptyPullObserved),
                kernel,
                properties,
                signal);
        closeables.add(puller);
        puller.start();
        assertTrue(emptyPullObserved.await(5L, TimeUnit.SECONDS));

        HandlerRegistry submitRegistry = new HandlerRegistry();
        submitRegistry.register("integration-handler", context -> {
        });
        TaskSubmitService submitService = new TaskSubmitService(
                serviceMapper,
                submitRegistry,
                ID_GENERATOR,
                properties,
                signal);
        long submittedAt = System.nanoTime();
        submitService.submit(new SubmitCommand(
                "signal-submit-" + UUID.randomUUID(),
                "integration-handler",
                "{}",
                null,
                null,
                null));

        assertTrue(handlerExecuted.await(5L, TimeUnit.SECONDS));
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(
                System.nanoTime() - submittedAt);
        assertTrue(
                elapsedMillis < 5_000L,
                "Task should preempt the 30-second empty backoff: elapsedMillis="
                        + elapsedMillis);
    }

    private TaskPuller puller(
            TaskInfoMapper mapper,
            KernelFixture kernel,
            RelayqProperties properties) {
        return puller(
                mapper,
                kernel,
                properties,
                TaskAvailabilitySignal.noop());
    }

    private TaskPuller puller(
            TaskInfoMapper mapper,
            KernelFixture kernel,
            RelayqProperties properties,
            TaskAvailabilitySignal taskAvailabilitySignal) {
        TransactionTemplate transactionTemplate = new TransactionTemplate(
                new SingleSessionTransactionManager(pullSession));
        return new TaskPuller(
                mapper,
                transactionTemplate,
                kernel.workerPool(),
                kernel.dispatcher(),
                properties.getInstanceId(),
                properties,
                RelayqMetrics.noop(),
                taskAvailabilitySignal);
    }

    private KernelFixture kernel(
            RelayqProperties properties,
            TaskHandler handler,
            TaskStateMachine providedStateMachine) {
        HandlerRegistry handlerRegistry = new HandlerRegistry();
        handlerRegistry.register("integration-handler", handler);
        TaskStateMachine stateMachine = providedStateMachine == null
                ? new TaskStateMachine(serviceMapper, executeLogMapper, ID_GENERATOR)
                : providedStateMachine;
        LeaseRenewer leaseRenewer = new LeaseRenewer(
                serviceMapper, properties.getInstanceId(), properties.getLease());
        RetryDecider retryDecider = new RetryDecider(
                new ExponentialJitterBackoff(properties.getRetry()));
        TaskDispatcher dispatcher = new TaskDispatcher(
                handlerRegistry,
                stateMachine,
                leaseRenewer,
                retryDecider,
                new ObjectMapper(),
                properties.getInstanceId(),
                properties.getHandler(),
                java.time.Clock.systemDefaultZone());
        RequeueRejectedHandler rejectedHandler = new RequeueRejectedHandler(
                stateMachine, properties.getInstanceId(), 4, 2L);
        TaskWorkerPool workerPool = new TaskWorkerPool(
                properties.getWorker(), rejectedHandler);
        closeables.add(rejectedHandler);
        closeables.add(leaseRenewer);
        closeables.add(workerPool);
        return new KernelFixture(workerPool, dispatcher);
    }

    private TaskInfoMapper isolationProbingMapper(AtomicReference<String> observedIsolation) {
        return (TaskInfoMapper) Proxy.newProxyInstance(
                TaskInfoMapper.class.getClassLoader(),
                new Class<?>[]{TaskInfoMapper.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("selectDueIdsForUpdateSkipLocked")) {
                        try (Statement statement = pullSession.getConnection().createStatement();
                             var resultSet = statement.executeQuery(
                                     "SELECT @@transaction_isolation")) {
                            resultSet.next();
                            observedIsolation.set(resultSet.getString(1));
                        }
                    }
                    try {
                        return method.invoke(pullMapper, args);
                    } catch (InvocationTargetException error) {
                        throw error.getCause();
                    }
                });
    }

    private TaskInfoMapper emptyPullObservingMapper(CountDownLatch emptyPullObserved) {
        return (TaskInfoMapper) Proxy.newProxyInstance(
                TaskInfoMapper.class.getClassLoader(),
                new Class<?>[]{TaskInfoMapper.class},
                (proxy, method, args) -> {
                    try {
                        Object result = method.invoke(pullMapper, args);
                        if (method.getName().equals("selectDueIdsForUpdateSkipLocked")
                                && result instanceof List<?> ids
                                && ids.isEmpty()) {
                            emptyPullObserved.countDown();
                        }
                        return result;
                    } catch (InvocationTargetException error) {
                        throw error.getCause();
                    }
                });
    }

    private CountDownLatch saturateReservedWorkerPool(TaskWorkerPool workerPool)
            throws Exception {
        /*
         * 生产路径不会绕过配额；这里故意从测试侧塞满真实 executor，
         * 模拟“预留后、提交前容量被外部异常占用”的罕见兜底场景。
         */
        Field executorField = TaskWorkerPool.class.getDeclaredField("executor");
        executorField.setAccessible(true);
        ThreadPoolExecutor executor = (ThreadPoolExecutor) executorField.get(workerPool);
        CountDownLatch release = new CountDownLatch(1);
        executor.execute(() -> awaitLatch(release));
        executor.execute(() -> awaitLatch(release));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while ((executor.getActiveCount() != 1 || executor.getQueue().size() != 1)
                && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        assertEquals(1, executor.getActiveCount());
        assertEquals(1, executor.getQueue().size());
        return release;
    }

    private TaskInfo insertTask(
            TaskStatus status,
            long scheduledOffsetSeconds,
            String leaseOwner,
            int retryCount,
            int currentAttemptNo) {
        return insertTask(
                status,
                scheduledOffsetSeconds,
                leaseOwner,
                leaseOwner == null ? null : 30L,
                retryCount,
                currentAttemptNo);
    }

    private TaskInfo insertTask(
            TaskStatus status,
            long scheduledOffsetSeconds,
            String leaseOwner,
            Long leaseExpireOffsetSeconds,
            int retryCount,
            int currentAttemptNo) {
        long taskId = ID_GENERATOR.nextId();
        String sql = """
                INSERT INTO task_info (
                    id, biz_key, handler_name, params, status, scheduled_time,
                    retry_count, max_retry, current_attempt_no, trace_id,
                    lease_owner, lease_expire_time
                )
                VALUES (
                    ?, ?, ?, ?, ?, DATE_ADD(NOW(3), INTERVAL ? SECOND),
                    ?, ?, ?, ?, ?, DATE_ADD(NOW(3), INTERVAL ? SECOND)
                )
                """;
        try (var statement = serviceSession.getConnection().prepareStatement(sql)) {
            statement.setLong(1, taskId);
            statement.setString(2, "scheduler-" + UUID.randomUUID());
            statement.setString(3, "integration-handler");
            statement.setString(4, "{}");
            statement.setString(5, status.name());
            statement.setLong(6, scheduledOffsetSeconds);
            statement.setInt(7, retryCount);
            statement.setInt(8, 10);
            statement.setInt(9, currentAttemptNo);
            statement.setString(10, UUID.randomUUID().toString().replace("-", ""));
            statement.setString(11, leaseOwner);
            if (leaseExpireOffsetSeconds == null) {
                statement.setNull(12, java.sql.Types.BIGINT);
            } else {
                statement.setLong(12, leaseExpireOffsetSeconds);
            }
            assertEquals(1, statement.executeUpdate());
        } catch (SQLException error) {
            throw new IllegalStateException("failed to insert scheduler test task", error);
        }
        return readFreshTask(taskId);
    }

    /*
     * 测试会长期复用 serviceSession，其他 session 或裸 JDBC 的变更不会让 MyBatis 一级缓存失效。
     * 验证数据库当前状态前必须显式清理缓存，避免断言读到之前查询留下的旧对象。
     */
    private TaskInfo readFreshTask(long taskId) {
        serviceSession.clearCache();
        return serviceMapper.selectById(taskId);
    }

    private LocalDateTime selectDatabaseNow() throws SQLException {
        try (Statement statement = serviceSession.getConnection().createStatement();
             var resultSet = statement.executeQuery("SELECT NOW(3)")) {
            assertTrue(resultSet.next());
            return resultSet.getObject(1, LocalDateTime.class);
        }
    }

    private void awaitStatus(long taskId, TaskStatus expected) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        TaskStatus actual = null;
        while (System.nanoTime() < deadline) {
            actual = readFreshTask(taskId).getStatus();
            if (actual == expected) {
                break;
            }
            Thread.onSpinWait();
        }
        assertEquals(expected, actual);
    }

    private static void awaitLatch(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        }
    }

    private static RelayqProperties properties(
            String owner,
            int coreSize,
            int maxSize,
            int queueCapacity,
            int pullBatchSize) {
        RelayqProperties properties = new RelayqProperties();
        properties.setInstanceId(owner);
        properties.getWorker().setCoreSize(coreSize);
        properties.getWorker().setMaxSize(maxSize);
        properties.getWorker().setQueueCapacity(queueCapacity);
        properties.getWorker().setShutdownGraceSeconds(2L);
        properties.getPull().setBatchSize(pullBatchSize);
        properties.getPull().setJitterRatio(0.0D);
        properties.getLease().setTtlSeconds(30L);
        return properties;
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

    private record KernelFixture(TaskWorkerPool workerPool, TaskDispatcher dispatcher) {
    }

    private static final class SingleSessionTransactionManager
            extends AbstractPlatformTransactionManager {

        private final SqlSession sqlSession;

        private SingleSessionTransactionManager(SqlSession sqlSession) {
            this.sqlSession = sqlSession;
        }

        @Override
        protected Object doGetTransaction() {
            return new TransactionState(sqlSession.getConnection());
        }

        /*
         * 测试脚手架会跨多个拉取事务复用同一个 SqlSession，其他 session 已提交的写入不会自动清掉一级缓存。
         * 每个事务开始前必须清理缓存，否则新事务可能继续命中上一个事务缓存的空拉取结果。
         */
        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
            TransactionState state = (TransactionState) transaction;
            sqlSession.clearCache();
            try {
                state.previousIsolation = state.connection.getTransactionIsolation();
                state.previousAutoCommit = state.connection.getAutoCommit();
                if (definition.getIsolationLevel() != TransactionDefinition.ISOLATION_DEFAULT) {
                    state.connection.setTransactionIsolation(definition.getIsolationLevel());
                }
                state.connection.setAutoCommit(false);
            } catch (SQLException error) {
                throw new IllegalStateException("failed to begin pull transaction", error);
            }
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
            try {
                ((TransactionState) status.getTransaction()).connection.commit();
            } catch (SQLException error) {
                throw new IllegalStateException("failed to commit pull transaction", error);
            }
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
            try {
                ((TransactionState) status.getTransaction()).connection.rollback();
            } catch (SQLException error) {
                throw new IllegalStateException("failed to roll back pull transaction", error);
            }
        }

        @Override
        protected void doCleanupAfterCompletion(Object transaction) {
            TransactionState state = (TransactionState) transaction;
            try {
                state.connection.setTransactionIsolation(state.previousIsolation);
                state.connection.setAutoCommit(state.previousAutoCommit);
            } catch (SQLException error) {
                throw new IllegalStateException("failed to restore pull connection state", error);
            }
        }

        private static final class TransactionState {

            private final Connection connection;
            private int previousIsolation;
            private boolean previousAutoCommit;

            private TransactionState(Connection connection) {
                this.connection = connection;
            }
        }
    }
}
