package com.suanla.relayq.autoconfigure;

import com.suanla.relayq.autoconfigure.host.HostRecordMapper;
import com.suanla.relayq.core.domain.TaskStatus;
import com.suanla.relayq.core.handler.HandlerRegistry;
import com.suanla.relayq.core.jdbc.JdbcTaskInfoMapper;
import com.suanla.relayq.core.mapper.TaskExecuteLogMapper;
import com.suanla.relayq.core.mapper.TaskInfoMapper;
import com.suanla.relayq.core.scheduler.TaskAvailabilitySignal;
import com.suanla.relayq.core.service.SubmitCommand;
import com.suanla.relayq.core.service.TaskStateMachine;
import com.suanla.relayq.core.service.TaskSubmitService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.autoconfigure.transaction.TransactionAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.ClassUtils;

import javax.sql.DataSource;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

@org.junit.jupiter.api.condition.EnabledIfSystemProperty(named = "relayq.mysql.tests", matches = "true")
class JdbcStarterIntegrationTest extends com.suanla.relayq.core.MySqlIntegrationSupport {
    private DataSource source;
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        source = dataSource;
        jdbc = new JdbcTemplate(source);
    }

    @AfterEach
    void tearDown() {
        jdbc.update("DELETE FROM task_snapshot");
        jdbc.update("DELETE FROM task_execute_log");
        jdbc.update("DELETE FROM task_info");
        jdbc.update("DELETE FROM host_record");
    }

    private ApplicationContextRunner runner() throws ClassNotFoundException {
        List<Class<?>> configurations = new ArrayList<>(List.of(
                DataSourceTransactionManagerAutoConfiguration.class,
                TransactionAutoConfiguration.class, RelayqAutoConfiguration.class));
        String orm = System.getProperty("relayq.compat.autoconfigure");
        if (orm != null) {
            configurations.add(Class.forName(orm));
        }
        String prefix = System.getProperty("relayq.compat.prefix", "unused");
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(configurations.toArray(Class<?>[]::new)))
                .withBean(DataSource.class, () -> source)
                // No background polling: these tests control transaction boundaries themselves.
                .withBean(RelayqLifecycle.class, () -> mock(RelayqLifecycle.class))
                .withPropertyValues(prefix + ".mapper-locations=classpath:host-mapper/HostRecordMapper.xml");
    }

    @Test
    void independentOfHostOrmAndSharesTheHostTransaction() throws Exception {
        runner().run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(TaskInfoMapper.class);
            assertThat(context.getBean(TaskInfoMapper.class)).isInstanceOf(JdbcTaskInfoMapper.class);
            context.getBean(HandlerRegistry.class).register("test", task -> {});
            TaskSubmitService submit = context.getBean(TaskSubmitService.class);
            TransactionTemplate tx = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
            String orm = System.getProperty("relayq.compat.autoconfigure");
            HostRecordMapper host = null;
            if (orm == null) {
                assertThat(ClassUtils.isPresent("org.apache.ibatis.session.SqlSessionFactory", getClass().getClassLoader()))
                        .isFalse();
            } else {
                Object template = context.getBean("sqlSessionTemplate");
                host = (HostRecordMapper) template.getClass().getMethod("getMapper", Class.class)
                        .invoke(template, HostRecordMapper.class);
            }
            HostRecordMapper mapper = host;
            tx.executeWithoutResult(status -> {
                if (mapper == null) {
                    jdbc.update("INSERT INTO host_record VALUES (1, 'rollback')");
                } else {
                    mapper.insert("rollback");
                    assertThat(mapper.selectName()).isEqualTo("rollback");
                }
                submit.submit(command("rollback"));
                status.setRollbackOnly();
            });
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM host_record", Long.class)).isZero();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM task_info", Long.class)).isZero();
            tx.executeWithoutResult(status -> {
                if (mapper == null) {
                    jdbc.update("INSERT INTO host_record VALUES (1, 'committed')");
                } else {
                    mapper.insert("committed");
                }
                submit.submit(command("committed"));
            });
            assertThat(jdbc.queryForObject("SELECT name FROM host_record", String.class)).isEqualTo("committed");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM task_info", Long.class)).isEqualTo(1);
            if (mapper != null) {
                assertThat(mapper.selectName()).isEqualTo("committed");
            }
        });
    }

    @Test
    void failedAuditRollsBackTheFencedStateUpdate() throws Exception {
        runner().run(context -> {
            context.getBean(HandlerRegistry.class).register("test", task -> {});
            var result = context.getBean(TaskSubmitService.class).submit(command("audit-rollback"));
            TaskInfoMapper tasks = context.getBean(TaskInfoMapper.class);
            tasks.markRunning(List.of(result.taskId()), "owner", 30);
            var claimed = tasks.selectById(result.taskId());
            TaskStateMachine machine = context.getBean(TaskStateMachine.class);
            assertThat(AopUtils.isAopProxy(machine)).isTrue();
            // A real MySQL constraint error on audit insert must undo the preceding SUCCESS update.
            jdbc.execute("ALTER TABLE task_execute_log ADD CONSTRAINT reject_success CHECK (outcome <> 'SUCCESS')");
            try {
                assertThrows(RuntimeException.class, () -> machine.succeed(claimed, "owner", LocalDateTime.now()));
                assertThat(tasks.selectById(result.taskId()).getStatus()).isEqualTo(TaskStatus.RUNNING);
                assertThat(context.getBean(TaskExecuteLogMapper.class).selectByTaskId(result.taskId())).isEmpty();
            } finally {
                jdbc.execute("ALTER TABLE task_execute_log DROP CHECK reject_success");
            }
            assertThat(machine.succeed(claimed, "owner", LocalDateTime.now())).isTrue();
            assertThat(tasks.selectById(result.taskId()).getStatus()).isEqualTo(TaskStatus.SUCCESS);
            assertThat(context.getBean(TaskExecuteLogMapper.class).selectByTaskId(result.taskId())).hasSize(1);
        });
    }

    @Test
    void submissionWakesOnlyAfterCommitAndNotAfterRollback() throws Exception {
        AtomicInteger wakeups = new AtomicInteger();
        TaskAvailabilitySignal delegate = mock(TaskAvailabilitySignal.class);
        doAnswer(invocation -> { wakeups.incrementAndGet(); return null; }).when(delegate).signal();
        runner().withBean(TaskAvailabilitySignal.class, () -> new AfterCommitTaskAvailabilitySignal(delegate))
                .run(context -> {
                    context.getBean(HandlerRegistry.class).register("test", task -> {});
                    TransactionTemplate tx = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
                    TaskSubmitService submit = context.getBean(TaskSubmitService.class);
                    tx.executeWithoutResult(status -> {
                        submit.submit(command("no-wakeup"));
                        assertThat(wakeups).hasValue(0);
                        status.setRollbackOnly();
                    });
                    assertThat(wakeups).hasValue(0);
                    tx.executeWithoutResult(status -> {
                        submit.submit(command("wakeup"));
                        assertThat(wakeups).hasValue(0);
                    });
                    assertThat(wakeups).hasValue(1);
                });
    }

    private SubmitCommand command(String key) {
        return new SubmitCommand(key, "test", "{}", null, null, 0);
    }
}
