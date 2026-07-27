package com.suanla.relayq.autoconfigure;

import com.baomidou.mybatisplus.autoconfigure.MybatisPlusProperties;
import com.baomidou.mybatisplus.autoconfigure.MybatisPlusAutoConfiguration;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.suanla.relayq.core.config.RelayqProperties;
import com.suanla.relayq.core.executor.RequeueRejectedHandler;
import com.suanla.relayq.core.executor.TaskDispatcher;
import com.suanla.relayq.core.executor.TaskWorkerPool;
import com.suanla.relayq.core.handler.HandlerRegistry;
import com.suanla.relayq.core.handler.RelayqHandler;
import com.suanla.relayq.core.handler.TaskContext;
import com.suanla.relayq.core.handler.TaskHandler;
import com.suanla.relayq.core.mapper.TaskExecuteLogMapper;
import com.suanla.relayq.core.mapper.TaskInfoMapper;
import com.suanla.relayq.core.mapper.TaskSnapshotMapper;
import com.suanla.relayq.core.metrics.RelayqMetrics;
import com.suanla.relayq.core.retry.BackoffPolicy;
import com.suanla.relayq.core.retry.ExponentialJitterBackoff;
import com.suanla.relayq.core.retry.RetryDecider;
import com.suanla.relayq.core.scheduler.TaskAvailabilitySignal;
import com.suanla.relayq.core.scheduler.LeaseReaper;
import com.suanla.relayq.core.scheduler.LeaseRenewer;
import com.suanla.relayq.core.scheduler.TaskPuller;
import com.suanla.relayq.core.scheduler.WorkerIdentity;
import com.suanla.relayq.core.service.DeadLetterService;
import com.suanla.relayq.core.service.TaskQueryService;
import com.suanla.relayq.core.service.TaskStateMachine;
import com.suanla.relayq.core.service.TaskSubmitService;
import com.suanla.relayq.core.snapshot.SnapshotAdmission;
import com.suanla.relayq.core.snapshot.SnapshotCollector;
import com.suanla.relayq.core.snapshot.SnapshotWriter;
import com.suanla.relayq.core.support.SnowflakeIdGenerator;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.micrometer.metrics.autoconfigure.CompositeMeterRegistryAutoConfiguration;
import org.springframework.boot.micrometer.metrics.autoconfigure.MetricsAutoConfiguration;
import org.springframework.boot.micrometer.metrics.autoconfigure.export.simple.SimpleMetricsExportAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;

import javax.sql.DataSource;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RelayqAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    MybatisPlusAutoConfiguration.class,
                    RelayqAutoConfiguration.class))
            .withUserConfiguration(InfrastructureConfiguration.class)
            .withPropertyValues("relayq.pull.interval-ms=30000");

    @Test
    void assemblesAllCoreBeansWithoutMeterRegistry() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(RelayqProperties.class);
            assertThat(context).hasSingleBean(SnowflakeIdGenerator.class);
            assertThat(context).hasSingleBean(HandlerRegistry.class);
            assertThat(context).hasSingleBean(TaskSubmitService.class);
            assertThat(context).hasSingleBean(TaskQueryService.class);
            assertThat(context).hasSingleBean(TaskStateMachine.class);
            assertThat(context).hasSingleBean(DeadLetterService.class);
            assertThat(context).hasSingleBean(BackoffPolicy.class);
            assertThat(context.getBean(BackoffPolicy.class))
                    .isInstanceOf(ExponentialJitterBackoff.class);
            assertThat(context).hasSingleBean(RetryDecider.class);
            assertThat(context).hasSingleBean(TaskWorkerPool.class);
            assertThat(context).hasSingleBean(RequeueRejectedHandler.class);
            assertThat(context).hasSingleBean(TaskDispatcher.class);
            assertThat(context).hasSingleBean(WorkerIdentity.class);
            assertThat(context).hasSingleBean(TaskAvailabilitySignal.class);
            assertThat(context).hasSingleBean(TaskPuller.class);
            assertThat(context).hasSingleBean(LeaseReaper.class);
            assertThat(context).hasSingleBean(LeaseRenewer.class);
            assertThat(context).hasSingleBean(SnapshotAdmission.class);
            assertThat(context).hasSingleBean(SnapshotCollector.class);
            assertThat(context).hasSingleBean(SnapshotWriter.class);
            assertThat(context).hasSingleBean(RelayqLifecycle.class);
            assertThat(context).hasSingleBean(ObjectMapper.class);
            assertThat(context).hasSingleBean(RelayqMetrics.class);
            assertThat(context.getBean(RelayqMetrics.class))
                    .isSameAs(RelayqMetrics.noop());
        });
    }

    @Test
    void disablesTheWholeScheduler() {
        contextRunner
                .withPropertyValues("relayq.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(RelayqProperties.class);
                    assertThat(context).doesNotHaveBean(SnowflakeIdGenerator.class);
                    assertThat(context).doesNotHaveBean(HandlerRegistry.class);
                    assertThat(context).doesNotHaveBean(TaskWorkerPool.class);
                    assertThat(context).doesNotHaveBean(TaskPuller.class);
                    assertThat(context).doesNotHaveBean(LeaseReaper.class);
                    assertThat(context).doesNotHaveBean(LeaseRenewer.class);
                    assertThat(context).doesNotHaveBean(SnapshotAdmission.class);
                    assertThat(context).doesNotHaveBean(RelayqLifecycle.class);
                    assertThat(context).doesNotHaveBean(RelayqMetrics.class);
                });
    }

    @Test
    void usesHostBackoffPolicy() {
        contextRunner
                .withUserConfiguration(CustomBackoffConfiguration.class)
                .run(context -> assertThat(context.getBean(BackoffPolicy.class))
                        .isSameAs(context.getBean("customBackoffPolicy")));
    }

    @Test
    void bindsKebabCaseProperties() {
        contextRunner
                .withPropertyValues(
                        "relayq.worker.core-size=3",
                        "relayq.pull.batch-size=7")
                .run(context -> {
                    RelayqProperties properties = context.getBean(RelayqProperties.class);
                    assertThat(properties.getWorker().getCoreSize()).isEqualTo(3);
                    assertThat(properties.getPull().getBatchSize()).isEqualTo(7);
                });
    }

    @Test
    void registersDirectAndJdkProxiedHandlersByAnnotation() {
        contextRunner
                .withUserConfiguration(HandlerConfiguration.class)
                .run(context -> {
                    HandlerRegistry registry = context.getBean(HandlerRegistry.class);
                    assertThat(registry.find("direct"))
                            .containsSame(context.getBean("directHandler", TaskHandler.class));
                    assertThat(registry.find("proxied"))
                            .containsSame(context.getBean("proxiedHandler", TaskHandler.class));
                });
    }

    @Test
    void createsMetricsOnlyWhenMeterRegistryExists() {
        contextRunner
                .withUserConfiguration(MetricsConfiguration.class)
                .run(context -> assertThat(context).hasSingleBean(RelayqMetrics.class));
    }

    @Test
    void createsMetricsAfterBootMeterRegistryAutoConfiguration() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        MybatisPlusAutoConfiguration.class,
                        MetricsAutoConfiguration.class,
                        SimpleMetricsExportAutoConfiguration.class,
                        CompositeMeterRegistryAutoConfiguration.class,
                        RelayqAutoConfiguration.class))
                .withUserConfiguration(InfrastructureConfiguration.class)
                .withPropertyValues("relayq.pull.interval-ms=30000")
                .run(context -> {
                    assertThat(context).hasSingleBean(RelayqMetrics.class);
                    assertThat(context.getBean(io.micrometer.core.instrument.MeterRegistry.class)
                                    .find("relayq.task.backlog")
                                    .gauges())
                            .isNotEmpty();
                });
    }

    @Test
    void rejectsHandlerWithoutRelayqHandlerAnnotation() {
        contextRunner
                .withUserConfiguration(UnannotatedHandlerConfiguration.class)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseMessage(
                                    "TaskHandler bean must declare @RelayqHandler: "
                                            + "beanName=unannotatedHandler, targetClass="
                                            + UnannotatedHandler.class.getName());
                });
    }

    @Test
    void appendsCoreMapperLocationWhenHostOverridesDefaults() {
        MybatisPlusProperties properties = new MybatisPlusProperties()
                .setMapperLocations(new String[]{"classpath*:/host-mapper/**/*.xml"});

        new RelayqAutoConfiguration()
                .relayqMybatisPlusPropertiesCustomizer()
                .customize(properties);

        assertThat(properties.getMapperLocations()).containsExactly(
                "classpath*:/host-mapper/**/*.xml",
                "classpath*:/mapper/**/*.xml");
    }

    @Configuration(proxyBeanMethods = false)
    static class InfrastructureConfiguration {

        @Bean
        DataSource dataSource() {
            return mock(DataSource.class);
        }

        @Bean
        PlatformTransactionManager transactionManager() {
            PlatformTransactionManager transactionManager =
                    mock(PlatformTransactionManager.class);
            when(transactionManager.getTransaction(any(TransactionDefinition.class)))
                    .thenReturn(new SimpleTransactionStatus());
            return transactionManager;
        }

        @Bean
        @Primary
        TaskInfoMapper testTaskInfoMapper() {
            TaskInfoMapper mapper = mock(TaskInfoMapper.class);
            when(mapper.selectDueIdsForUpdateSkipLocked(any(Integer.class)))
                    .thenReturn(List.of());
            return mapper;
        }

        @Bean
        @Primary
        TaskExecuteLogMapper testTaskExecuteLogMapper() {
            return mock(TaskExecuteLogMapper.class);
        }

        @Bean
        @Primary
        TaskSnapshotMapper testTaskSnapshotMapper() {
            return mock(TaskSnapshotMapper.class);
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class CustomBackoffConfiguration {

        @Bean
        BackoffPolicy customBackoffPolicy() {
            return retryNumber -> Duration.ZERO;
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class MetricsConfiguration {

        @Bean
        SimpleMeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class HandlerConfiguration {

        @Bean
        DirectHandler directHandler() {
            return new DirectHandler();
        }

        @Bean
        TaskHandler proxiedHandler() {
            ProxyFactory proxyFactory = new ProxyFactory(new ProxiedHandler());
            proxyFactory.setInterfaces(TaskHandler.class);
            return (TaskHandler) proxyFactory.getProxy();
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class UnannotatedHandlerConfiguration {

        @Bean
        TaskHandler unannotatedHandler() {
            return new UnannotatedHandler();
        }
    }

    @RelayqHandler("direct")
    static class DirectHandler implements TaskHandler {

        @Override
        public void execute(TaskContext ctx) {
        }
    }

    @RelayqHandler("proxied")
    static class ProxiedHandler implements TaskHandler {

        @Override
        public void execute(TaskContext ctx) {
        }
    }

    static class UnannotatedHandler implements TaskHandler {

        @Override
        public void execute(TaskContext ctx) {
        }
    }
}
