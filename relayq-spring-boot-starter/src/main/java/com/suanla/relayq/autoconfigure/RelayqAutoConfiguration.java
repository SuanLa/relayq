package com.suanla.relayq.autoconfigure;

import com.baomidou.mybatisplus.autoconfigure.MybatisPlusAutoConfiguration;
import com.baomidou.mybatisplus.autoconfigure.MybatisPlusPropertiesCustomizer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.suanla.relayq.core.config.RelayqProperties;
import com.suanla.relayq.core.executor.RequeueRejectedHandler;
import com.suanla.relayq.core.executor.TaskDispatcher;
import com.suanla.relayq.core.executor.TaskWorkerPool;
import com.suanla.relayq.core.handler.HandlerRegistry;
import com.suanla.relayq.core.handler.RelayqHandler;
import com.suanla.relayq.core.handler.TaskHandler;
import com.suanla.relayq.core.mapper.TaskExecuteLogMapper;
import com.suanla.relayq.core.mapper.TaskInfoMapper;
import com.suanla.relayq.core.mapper.TaskSnapshotMapper;
import com.suanla.relayq.core.metrics.RelayqMetrics;
import com.suanla.relayq.core.retry.BackoffPolicy;
import com.suanla.relayq.core.retry.ExponentialJitterBackoff;
import com.suanla.relayq.core.retry.RetryDecider;
import com.suanla.relayq.core.scheduler.CoalescingTaskAvailabilitySignal;
import com.suanla.relayq.core.scheduler.LeaseReaper;
import com.suanla.relayq.core.scheduler.LeaseRenewer;
import com.suanla.relayq.core.scheduler.TaskAvailabilitySignal;
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
import io.micrometer.core.instrument.MeterRegistry;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.util.Arrays;
import java.util.Map;
import java.util.stream.Stream;

@AutoConfiguration(after = {
        DataSourceAutoConfiguration.class,
        MybatisPlusAutoConfiguration.class
}, afterName = {
        "org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration",
        "org.springframework.boot.actuate.autoconfigure.metrics.CompositeMeterRegistryAutoConfiguration"
})
@ConditionalOnBean(DataSource.class)
@ConditionalOnProperty(prefix = "relayq", name = "enabled", matchIfMissing = true)
@MapperScan("com.suanla.relayq.core.mapper")
public class RelayqAutoConfiguration {

    private static final String DEFAULT_MAPPER_LOCATION = "classpath*:/mapper/**/*.xml";
    private static final int SNOWFLAKE_WORKER_BITS = 10;
    private static final int ERROR_STACK_MAX_BYTES = 64 * 1024;

    @Bean
    @ConfigurationProperties(prefix = "relayq")
    @ConditionalOnMissingBean
    public RelayqProperties relayqProperties() {
        return new RelayqProperties();
    }

    @Bean
    @ConditionalOnMissingBean(name = "relayqMybatisPlusPropertiesCustomizer")
    public MybatisPlusPropertiesCustomizer relayqMybatisPlusPropertiesCustomizer() {
        return properties -> {
            String[] configured = properties.getMapperLocations();
            if (configured != null
                    && Arrays.asList(configured).contains(DEFAULT_MAPPER_LOCATION)) {
                return;
            }
            /*
             * 宿主一旦配置 mapper-locations 就会覆盖 MyBatis-Plus 默认值，
             * 因此必须把 core XML 的默认通配路径显式追加回来。
             */
            properties.setMapperLocations(Stream.concat(
                            configured == null ? Stream.empty() : Arrays.stream(configured),
                            Stream.of(DEFAULT_MAPPER_LOCATION))
                    .toArray(String[]::new));
        };
    }

    @Bean
    @ConditionalOnMissingBean
    public WorkerIdentity workerIdentity(RelayqProperties properties) {
        return new WorkerIdentity(properties);
    }

    @Bean
    @ConditionalOnMissingBean
    public SnowflakeIdGenerator snowflakeIdGenerator(WorkerIdentity workerIdentity) {
        /*
         * core 只暴露 10 bit workerId，而实例身份是字符串；散列后截到 10 bit
         * 能让默认多实例部署避免固定使用同一个 workerId，严格唯一场景仍可覆盖此 Bean。
         */
        long workerId = Math.floorMod(
                workerIdentity.value().hashCode(),
                1 << SNOWFLAKE_WORKER_BITS);
        return new SnowflakeIdGenerator(workerId);
    }

    @Bean
    @ConditionalOnMissingBean
    public HandlerRegistry handlerRegistry(Map<String, TaskHandler> taskHandlers) {
        HandlerRegistry registry = new HandlerRegistry();
        taskHandlers.forEach((beanName, handler) -> {
            /*
             * JDK/CGLIB 代理类可能不保留实现类注解，必须先解析真实目标类型。
             */
            Class<?> targetClass = AopUtils.getTargetClass(handler);
            RelayqHandler annotation =
                    AnnotationUtils.findAnnotation(targetClass, RelayqHandler.class);
            if (annotation == null) {
                throw new IllegalStateException(
                        "TaskHandler bean must declare @RelayqHandler: beanName="
                                + beanName
                                + ", targetClass="
                                + targetClass.getName());
            }
            registry.register(annotation.value(), handler);
        });
        return registry;
    }

    @Bean
    @ConditionalOnMissingBean
    public ObjectMapper objectMapper() {
        /*
         * 纯 JDBC 宿主未必启用 Jackson 自动配置，但参数反序列化与快照写入仍需要基础 mapper。
         */
        return new ObjectMapper();
    }

    @Bean("relayqTransactionTemplate")
    @ConditionalOnMissingBean(name = "relayqTransactionTemplate")
    public TransactionTemplate relayqTransactionTemplate(
            PlatformTransactionManager transactionManager) {
        return new TransactionTemplate(transactionManager);
    }

    @Bean
    @ConditionalOnMissingBean
    public TaskAvailabilitySignal taskAvailabilitySignal() {
        return new AfterCommitTaskAvailabilitySignal(
                new CoalescingTaskAvailabilitySignal());
    }

    @Bean
    @ConditionalOnMissingBean
    public SnapshotCollector snapshotCollector(
            RelayqProperties properties,
            ObjectProvider<RelayqMetrics> metrics) {
        return new SnapshotCollector(
                properties.getSnapshot(),
                metrics.getIfAvailable(RelayqMetrics::noop));
    }

    @Bean
    @ConditionalOnMissingBean
    public SnapshotWriter snapshotWriter(
            TaskSnapshotMapper taskSnapshotMapper,
            SnowflakeIdGenerator idGenerator,
            ObjectMapper objectMapper,
            RelayqProperties properties) {
        return new SnapshotWriter(
                taskSnapshotMapper,
                idGenerator,
                objectMapper,
                properties.getSnapshot());
    }

    @Bean(destroyMethod = "")
    @ConditionalOnMissingBean
    public SnapshotAdmission snapshotAdmission(
            RelayqProperties properties,
            SnapshotCollector collector,
            SnapshotWriter writer,
            ObjectProvider<RelayqMetrics> metrics) {
        return new SnapshotAdmission(
                properties.getSnapshot(),
                collector,
                writer,
                metrics.getIfAvailable(RelayqMetrics::noop));
    }

    @Bean
    @ConditionalOnMissingBean
    public TaskStateMachine taskStateMachine(
            TaskInfoMapper taskInfoMapper,
            TaskExecuteLogMapper taskExecuteLogMapper,
            SnowflakeIdGenerator idGenerator,
            RelayqProperties properties,
            SnapshotAdmission snapshotAdmission,
            ObjectProvider<RelayqMetrics> metrics) {
        RelayqMetrics relayqMetrics = metrics.getIfAvailable(RelayqMetrics::noop);
        return new TaskStateMachine(
                taskInfoMapper,
                taskExecuteLogMapper,
                idGenerator,
                ERROR_STACK_MAX_BYTES,
                ignored -> relayqMetrics.recordLeaseLost(),
                properties.getSnapshot().getFailThreshold(),
                snapshotAdmission);
    }

    @Bean(destroyMethod = "")
    @ConditionalOnMissingBean
    public RequeueRejectedHandler requeueRejectedHandler(
            TaskStateMachine taskStateMachine,
            WorkerIdentity workerIdentity,
            ObjectProvider<RelayqMetrics> metrics) {
        return new RequeueRejectedHandler(
                taskStateMachine,
                workerIdentity.value(),
                16,
                5L,
                metrics.getIfAvailable(RelayqMetrics::noop));
    }

    @Bean(destroyMethod = "")
    @ConditionalOnMissingBean
    public TaskWorkerPool taskWorkerPool(
            RelayqProperties properties,
            RequeueRejectedHandler rejectedHandler,
            ObjectProvider<RelayqMetrics> metrics) {
        return new TaskWorkerPool(
                properties.getWorker(),
                rejectedHandler,
                metrics.getIfAvailable(RelayqMetrics::noop));
    }

    @Bean
    @ConditionalOnMissingBean
    public BackoffPolicy backoffPolicy(RelayqProperties properties) {
        return new ExponentialJitterBackoff(properties.getRetry());
    }

    @Bean
    @ConditionalOnMissingBean
    public RetryDecider retryDecider(BackoffPolicy backoffPolicy) {
        return new RetryDecider(backoffPolicy);
    }

    @Bean(destroyMethod = "")
    @ConditionalOnMissingBean
    public LeaseRenewer leaseRenewer(
            TaskInfoMapper taskInfoMapper,
            WorkerIdentity workerIdentity,
            RelayqProperties properties) {
        return new LeaseRenewer(
                taskInfoMapper,
                workerIdentity,
                properties.getLease());
    }

    @Bean
    @ConditionalOnMissingBean
    public TaskDispatcher taskDispatcher(
            HandlerRegistry handlerRegistry,
            TaskStateMachine taskStateMachine,
            LeaseRenewer leaseRenewer,
            RetryDecider retryDecider,
            ObjectMapper objectMapper,
            WorkerIdentity workerIdentity,
            RelayqProperties properties,
            ObjectProvider<RelayqMetrics> metrics) {
        return new TaskDispatcher(
                handlerRegistry,
                taskStateMachine,
                leaseRenewer,
                retryDecider,
                objectMapper,
                workerIdentity.value(),
                properties.getHandler(),
                java.time.Clock.systemDefaultZone(),
                metrics.getIfAvailable(RelayqMetrics::noop));
    }

    @Bean(destroyMethod = "")
    @ConditionalOnMissingBean
    public TaskPuller taskPuller(
            TaskInfoMapper taskInfoMapper,
            @Qualifier("relayqTransactionTemplate") TransactionTemplate transactionTemplate,
            TaskWorkerPool workerPool,
            TaskDispatcher dispatcher,
            WorkerIdentity workerIdentity,
            RelayqProperties properties,
            TaskAvailabilitySignal taskAvailabilitySignal,
            ObjectProvider<RelayqMetrics> metrics) {
        return new TaskPuller(
                taskInfoMapper,
                transactionTemplate,
                workerPool,
                dispatcher,
                workerIdentity.value(),
                properties,
                metrics.getIfAvailable(RelayqMetrics::noop),
                taskAvailabilitySignal);
    }

    @Bean(destroyMethod = "")
    @ConditionalOnMissingBean
    public LeaseReaper leaseReaper(
            TaskInfoMapper taskInfoMapper,
            RelayqProperties properties,
            ObjectProvider<RelayqMetrics> metrics) {
        return new LeaseReaper(
                taskInfoMapper,
                properties.getLease(),
                metrics.getIfAvailable(RelayqMetrics::noop));
    }

    @Bean
    @ConditionalOnMissingBean
    public TaskSubmitService taskSubmitService(
            TaskInfoMapper taskInfoMapper,
            HandlerRegistry handlerRegistry,
            SnowflakeIdGenerator idGenerator,
            RelayqProperties properties,
            TaskAvailabilitySignal taskAvailabilitySignal) {
        return new TaskSubmitService(
                taskInfoMapper,
                handlerRegistry,
                idGenerator,
                properties,
                taskAvailabilitySignal);
    }

    @Bean
    @ConditionalOnMissingBean
    public TaskQueryService taskQueryService(
            TaskInfoMapper taskInfoMapper,
            TaskExecuteLogMapper taskExecuteLogMapper,
            TaskSnapshotMapper taskSnapshotMapper) {
        return new TaskQueryService(
                taskInfoMapper,
                taskExecuteLogMapper,
                taskSnapshotMapper);
    }

    @Bean
    @ConditionalOnMissingBean
    public DeadLetterService deadLetterService(
            TaskInfoMapper taskInfoMapper,
            TaskQueryService taskQueryService) {
        return new DeadLetterService(taskInfoMapper, taskQueryService);
    }

    @Bean
    @ConditionalOnMissingBean
    public RelayqLifecycle relayqLifecycle(
            TaskPuller taskPuller,
            TaskWorkerPool taskWorkerPool,
            RequeueRejectedHandler requeueRejectedHandler,
            LeaseReaper leaseReaper,
            LeaseRenewer leaseRenewer,
            SnapshotAdmission snapshotAdmission) {
        return new RelayqLifecycle(
                taskPuller,
                taskWorkerPool,
                requeueRejectedHandler,
                leaseReaper,
                leaseRenewer,
                snapshotAdmission);
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(MeterRegistry.class)
    static class MetricsConfiguration {

        @Bean
        @ConditionalOnMissingBean
        RelayqMetrics relayqMetrics(
                ObjectProvider<MeterRegistry> meterRegistry,
                TaskInfoMapper taskInfoMapper,
                RelayqProperties properties) {
            MeterRegistry registry = meterRegistry.getIfAvailable();
            if (registry == null) {
                return RelayqMetrics.noop();
            }
            return new RelayqMetrics(
                    registry,
                    taskInfoMapper,
                    properties.getMetrics());
        }
    }
}
