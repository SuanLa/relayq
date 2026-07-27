package com.suanla.relayq.example;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.MethodOrderer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.client.EntityExchangeResult;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.time.Duration;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/*
 * Boot 4 拆分模块后，TestRestTemplate 依赖本项目未引入的 spring-boot-restclient。
 * RestTestClient 所需依赖均已具备，因此使用它进行真实端口的 HTTP 端到端测试。
 */
@AutoConfigureRestTestClient
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "relayq.instance-id=relayq-e2e",
                "relayq.pull.interval-ms=50",
                "relayq.pull.jitter-ratio=0",
                "relayq.pull.empty-backoff-max-ms=50",
                "relayq.worker.core-size=2",
                "relayq.worker.max-size=4",
                "relayq.worker.queue-capacity=20",
                "relayq.retry.base-delay-ms=50",
                "relayq.retry.multiplier=1",
                "relayq.retry.max-delay-ms=50",
                "relayq.retry.jitter-ratio=0",
                "relayq.handler.timeout-ms=800",
                "relayq.snapshot.cooldown-seconds=0",
                "relayq.snapshot.rate-per-minute=100",
                "relayq.snapshot.collect-timeout-ms=1000",
                "relayq.metrics.backlog-cache-seconds=1"
        })
@Testcontainers(disabledWithoutDocker = true)
@TestMethodOrder(MethodOrderer.MethodName.class)
// Web 端到端测试必须使用 Boot 4 MVC 采用的 tools.jackson，core 内部仍保留 com.fasterxml.jackson。
/*
 * 与上下文测试一致，不给容器设置 TZ，保留 UTC 数据库 / CST JVM 的回归场景。
 * withInitScript 直接消费 core jar 中唯一的 db/schema.sql。
 */
class RelayqEndToEndTests {

    private static final Duration DEFAULT_WAIT = Duration.ofSeconds(8);

    @Container
    @ServiceConnection
    private static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4")
            .withDatabaseName("relayq_e2e_test")
            .withUsername("relayq")
            .withPassword("relayq")
            .withInitScript("db/schema.sql");

    @Autowired
    private RestTestClient restTestClient;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.update("DELETE FROM task_snapshot");
        jdbcTemplate.update("DELETE FROM task_execute_log");
        jdbcTemplate.update("DELETE FROM task_info");
    }

    @Test
    void a01_submitExecutesSuccessfullyAndWritesAudit() {
        EntityExchangeResult<JsonNode> response = submit(
                uniqueBizKey("success"),
                "echo-handler",
                objectMapper.createObjectNode().put("message", "hello"),
                null,
                0)
                .expectStatus().isCreated()
                .expectBody(JsonNode.class)
                .returnResult();

        long taskId = requiredBody(response).path("task_id").asLong();
        assertEquals(
                jdbcTemplate.queryForObject(
                        "SELECT trace_id FROM task_info WHERE id = ?",
                        String.class,
                        taskId),
                response.getResponseHeaders().getFirst("X-Trace-Id"));
        awaitStatus(taskId, "SUCCESS");
        assertEquals(
                1,
                count(
                        "SELECT COUNT(*) FROM task_execute_log"
                                + " WHERE task_id = ? AND outcome = 'SUCCESS'",
                        taskId));
    }

    @Test
    void a02_duplicateBizKeyIsNormalIdempotentResponse() {
        String bizKey = uniqueBizKey("duplicate");
        JsonNode params = objectMapper.createObjectNode().put("message", "same");

        EntityExchangeResult<JsonNode> first = submit(
                bizKey, "echo-handler", params, 30L, 0)
                .expectStatus().isCreated()
                .expectBody(JsonNode.class)
                .returnResult();
        EntityExchangeResult<JsonNode> second = submit(
                bizKey, "echo-handler", params, 30L, 0)
                .expectStatus().isOk()
                .expectBody(JsonNode.class)
                .returnResult();

        assertTrue(requiredBody(second).path("already_exists").asBoolean());
        assertEquals(
                requiredBody(first).path("task_id").asLong(),
                requiredBody(second).path("task_id").asLong());
        assertEquals(
                1,
                count("SELECT COUNT(*) FROM task_info WHERE biz_key = ?", bizKey));
    }

    @Test
    void a03_unregisteredHandlerReturnsBadRequestWithoutInsert() {
        String bizKey = uniqueBizKey("missing-handler");

        EntityExchangeResult<JsonNode> response = submit(
                bizKey,
                "missing-handler",
                objectMapper.createObjectNode(),
                null,
                0)
                .expectStatus().isBadRequest()
                .expectBody(JsonNode.class)
                .returnResult();

        JsonNode body = requiredBody(response);
        assertFalse(body.path("trace_id").asText().isBlank());
        assertEquals(
                0,
                count("SELECT COUNT(*) FROM task_info WHERE biz_key = ?", bizKey));
    }

    @Test
    void a04_delayedTaskDoesNotRunBeforeDatabaseDueTime() throws InterruptedException {
        EntityExchangeResult<JsonNode> response = submit(
                uniqueBizKey("delayed"),
                "echo-handler",
                objectMapper.createObjectNode().put("message", "later"),
                2L,
                0)
                .expectBody(JsonNode.class)
                .returnResult();
        long taskId = requiredBody(response).path("task_id").asLong();

        Long millisUntilDue = jdbcTemplate.queryForObject(
                "SELECT TIMESTAMPDIFF(MICROSECOND, NOW(3), scheduled_time) / 1000"
                        + " FROM task_info WHERE id = ?",
                Long.class,
                taskId);
        assertNotNull(millisUntilDue);
        assertTrue(millisUntilDue > 1_000L);
        Thread.sleep(700L);
        assertEquals("PENDING", status(taskId));
        assertEquals(
                0,
                count("SELECT COUNT(*) FROM task_execute_log WHERE task_id = ?", taskId));

        awaitStatus(taskId, "SUCCESS");
    }

    @Test
    void a05_flakyHandlerRetriesExpectedNumberOfTimes() {
        EntityExchangeResult<JsonNode> response = submit(
                uniqueBizKey("flaky"),
                "flaky-handler",
                objectMapper.createObjectNode().put("fail_times", 2),
                null,
                3)
                .expectBody(JsonNode.class)
                .returnResult();
        long taskId = requiredBody(response).path("task_id").asLong();

        awaitStatus(taskId, "SUCCESS");
        assertEquals(
                2,
                value("SELECT retry_count FROM task_info WHERE id = ?", taskId));
        assertEquals(
                3,
                count("SELECT COUNT(*) FROM task_execute_log WHERE task_id = ?", taskId));
    }

    @Test
    void a06_poisonHandlerDiesOnceAndCapturesSnapshot() {
        ObjectNode poison = objectMapper.createObjectNode();
        poison.put("required_number", "not-a-number");
        EntityExchangeResult<JsonNode> response = submit(
                uniqueBizKey("poison"),
                "poison-handler",
                poison,
                null,
                5)
                .expectBody(JsonNode.class)
                .returnResult();
        long taskId = requiredBody(response).path("task_id").asLong();

        awaitStatus(taskId, "DEAD");
        assertEquals(
                0,
                value("SELECT retry_count FROM task_info WHERE id = ?", taskId));
        assertEquals(
                1,
                count("SELECT COUNT(*) FROM task_execute_log WHERE task_id = ?", taskId));
        assertEquals(
                1,
                count(
                        "SELECT COUNT(*) FROM task_execute_log"
                                + " WHERE task_id = ? AND failure_kind = 'PARAM_DESERIALIZE_FAILED'",
                        taskId));
        awaitCondition(
                () -> count(
                        "SELECT COUNT(*) FROM task_snapshot WHERE task_id = ?",
                        taskId) == 1,
                DEFAULT_WAIT,
                "automatic poison-task snapshot");
    }

    @Test
    void a07_cancelPendingSucceedsAndCancelRunningConflicts() {
        EntityExchangeResult<JsonNode> pendingResponse = submit(
                uniqueBizKey("cancel-pending"),
                "echo-handler",
                objectMapper.createObjectNode().put("message", "cancel"),
                30L,
                0)
                .expectBody(JsonNode.class)
                .returnResult();
        long pendingId = requiredBody(pendingResponse).path("task_id").asLong();

        EntityExchangeResult<JsonNode> cancelled = post(
                "/api/tasks/" + pendingId + "/cancel",
                null)
                .expectStatus().isOk()
                .expectBody(JsonNode.class)
                .returnResult();
        assertEquals("CANCELLED", requiredBody(cancelled).path("status").asText());

        EntityExchangeResult<JsonNode> runningResponse = submit(
                uniqueBizKey("cancel-running"),
                "slow-handler",
                objectMapper.createObjectNode().put("sleep_millis", 400),
                null,
                0)
                .expectBody(JsonNode.class)
                .returnResult();
        long runningId = requiredBody(runningResponse).path("task_id").asLong();
        awaitStatus(runningId, "RUNNING");

        post(
                "/api/tasks/" + runningId + "/cancel",
                null)
                .expectStatus().isEqualTo(HttpStatus.CONFLICT);
        awaitStatus(runningId, "SUCCESS");
    }

    @Test
    void a08_deadLetterRedriveAcceptsOnceAndSecondClickConflicts() {
        EntityExchangeResult<JsonNode> response = submit(
                uniqueBizKey("redrive"),
                "slow-handler",
                objectMapper.createObjectNode().put("sleep_millis", 2_000),
                null,
                0)
                .expectBody(JsonNode.class)
                .returnResult();
        long taskId = requiredBody(response).path("task_id").asLong();
        awaitStatus(taskId, "DEAD");

        ObjectNode request = objectMapper.createObjectNode()
                .put("redrive_by", "e2e-operator")
                .put("redrive_reason", "Retry after incident review");
        EntityExchangeResult<JsonNode> first = post(
                "/api/dead-letters/" + taskId + "/redrive",
                request)
                .expectStatus().isOk()
                .expectBody(JsonNode.class)
                .returnResult();
        RestTestClient.ResponseSpec second = post(
                "/api/dead-letters/" + taskId + "/redrive",
                request);

        assertNotNull(requiredBody(first).path("status").textValue());
        assertEquals(
                1,
                count(
                        "SELECT COUNT(*) FROM task_info"
                                + " WHERE id = ? AND redrive_by = 'e2e-operator'"
                                + " AND redrive_at IS NOT NULL",
                        taskId));
        second.expectStatus().isEqualTo(HttpStatus.CONFLICT);
        awaitStatus(taskId, "DEAD");
    }

    @Test
    void a09_prometheusEndpointExposesRelayqMetrics() {
        EntityExchangeResult<String> response = restTestClient.get()
                .uri("/actuator/prometheus")
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class)
                .returnResult();

        assertNotNull(response.getResponseBody());
        assertTrue(response.getResponseBody().contains("relayq_"));
    }

    @Test
    void a10_manualSnapshotIsAcceptedThroughPublicApi() {
        EntityExchangeResult<JsonNode> response = post(
                "/api/snapshots/manual",
                objectMapper.createObjectNode())
                .expectStatus().isAccepted()
                .expectBody(JsonNode.class)
                .returnResult();

        assertTrue(requiredBody(response).path("accepted").asBoolean());
        awaitCondition(
                () -> count(
                        "SELECT COUNT(*) FROM task_snapshot"
                                + " WHERE trigger_type = 'MANUAL'") == 1,
                DEFAULT_WAIT,
                "manual snapshot");
    }

    private RestTestClient.ResponseSpec submit(
            String bizKey,
            String handlerName,
            JsonNode params,
            Long delaySeconds,
            Integer maxRetry) {
        ObjectNode request = objectMapper.createObjectNode();
        request.put("biz_key", bizKey);
        request.put("handler_name", handlerName);
        request.set("params", params);
        if (delaySeconds != null) {
            request.put("delay_seconds", delaySeconds);
        }
        if (maxRetry != null) {
            request.put("max_retry", maxRetry);
        }
        return post("/api/tasks", request);
    }

    private RestTestClient.ResponseSpec post(String path, JsonNode request) {
        RestTestClient.RequestBodySpec requestSpec = restTestClient.post()
                .uri(path)
                .contentType(MediaType.APPLICATION_JSON);
        if (request == null) {
            return requestSpec.exchange();
        }
        return requestSpec.body(request).exchange();
    }

    private JsonNode requiredBody(EntityExchangeResult<JsonNode> response) {
        return Objects.requireNonNull(
                response.getResponseBody(),
                "response body must not be null");
    }

    private String uniqueBizKey(String prefix) {
        return prefix + "-" + UUID.randomUUID();
    }

    private void awaitStatus(long taskId, String expectedStatus) {
        awaitCondition(
                () -> expectedStatus.equals(status(taskId)),
                DEFAULT_WAIT,
                "task " + taskId + " status " + expectedStatus);
    }

    private String status(long taskId) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM task_info WHERE id = ?",
                String.class,
                taskId);
    }

    private int value(String sql, Object... args) {
        Integer result = jdbcTemplate.queryForObject(sql, Integer.class, args);
        return Objects.requireNonNull(result, "query result must not be null");
    }

    private int count(String sql, Object... args) {
        return value(sql, args);
    }

    private void awaitCondition(
            BooleanSupplier condition,
            Duration timeout,
            String description) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                TimeUnit.MILLISECONDS.sleep(20L);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(
                        "Interrupted while waiting for " + description,
                        error);
            }
        }
        assertTrue(condition.getAsBoolean(), "Timed out waiting for " + description);
    }
}
