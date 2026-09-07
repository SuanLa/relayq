<div align="center">

# RelayQ

### 基于 MySQL 的轻量级持久化任务队列与调度内核

为 Spring Boot 应用提供延迟执行、失败重试、死信重投、多实例抢占与事故快照能力。

<p>
  <img alt="Java 21" src="https://img.shields.io/badge/Java-21-ED8B00?logo=openjdk&logoColor=white">
  <img alt="Spring Boot 3.2.9" src="https://img.shields.io/badge/Spring%20Boot-3.2.9-6DB33F?logo=springboot&logoColor=white">
  <img alt="MySQL 8+" src="https://img.shields.io/badge/MySQL-8%2B-4479A1?logo=mysql&logoColor=white">
  <img alt="License Apache 2.0" src="https://img.shields.io/badge/License-Apache%202.0-D22128">
</p>

**只需 MySQL · 支持水平扩展 · 开箱即用的 Spring Boot Starter**

[快速开始](#-快速开始) · [接入指南](#-接入-spring-boot) · [配置参考](#-常用配置)

</div>

---

> [!IMPORTANT]
> RelayQ 提供 **at-least-once（至少一次）** 执行语义。租约、续租与 fencing 可防止失去租约的旧 Worker 覆盖平台状态，但无法完全消除业务副作用被重复执行的窗口，因此任务 Handler 必须具备幂等性。

## ✨ 核心能力

| | 能力 | 说明 |
| :---: | --- | --- |
| 🪶 | **轻量依赖** | 仅依赖 MySQL 8，无需额外部署 Redis 或消息中间件 |
| ⚡ | **并发抢占** | 基于 `SELECT ... FOR UPDATE SKIP LOCKED`，支持多实例安全消费 |
| 🕒 | **灵活调度** | 支持立即执行、指定时间执行与相对延迟执行 |
| 🛡️ | **可靠执行** | 提交幂等、租约续期、过期回收与终态 fencing |
| 🔁 | **失败治理** | 指数退避、随机抖动、错误分类、死信与人工重投 |
| 🚦 | **流量保护** | 有界 Worker 队列、批量回置与优雅停机 |
| 📸 | **事故快照** | 自动或手动采集线程 Dump、线程池水位、堆内存与积压量 |
| 📈 | **可观测性** | Micrometer 指标、Prometheus 暴露与全链路 `traceId` |

## 🧭 目录

- [工作原理](#-工作原理)
- [快速开始](#-快速开始)
- [接入 Spring Boot](#-接入-spring-boot)
- [示例管理 API](#-示例管理-api)
- [常用配置](#-常用配置)
- [可观测性](#-可观测性)
- [项目结构](#-项目结构)
- [构建与测试](#-构建与测试)
- [容量测试报告](#-容量测试报告)
- [语义边界与已知限制](#-语义边界与已知限制)

## 🏗️ 工作原理

```mermaid
flowchart LR
    APP["业务应用"] -->|"提交任务"| SUBMIT["TaskSubmitService"]
    SUBMIT --> DB[(MySQL)]
    DB -->|"SKIP LOCKED 抢占"| PULLER["TaskPuller"]
    PULLER --> POOL["Worker Pool"]
    POOL --> HANDLER["TaskHandler"]
    HANDLER -->|"成功 / 重试 / 死信"| DB
    REAPER["LeaseReaper"] -->|"回收过期租约"| DB
    SNAPSHOT["SnapshotAdmission"] -->|"受限采集"| DB
```

### 任务状态流转

```mermaid
stateDiagram-v2
    direction LR
    [*] --> PENDING: 提交
    PENDING --> RUNNING: 抢占
    RUNNING --> SUCCESS: 执行成功
    RUNNING --> PENDING: 重试 / 租约过期 / 执行池拒绝
    RUNNING --> DEAD: 不可重试 / 超过重试上限
    PENDING --> CANCELLED: 取消
    DEAD --> PENDING: 人工重投
    SUCCESS --> [*]
    CANCELLED --> [*]
```

## 🚀 快速开始

### 环境要求

- Docker 与 Docker Compose
- 本地构建需要 JDK 21 与 Maven 3.9+

### 1. 启动双实例示例

仓库内的 Compose 配置会启动 MySQL 8.0 GTID 主从，以及两个共享主库的 RelayQ 实例：

```bash
docker compose up --build -d
docker compose ps
```

| 服务 | 地址 | 说明 |
| --- | --- | --- |
| `relayq-app-1` | <http://localhost:8081> | 示例应用实例 1 |
| `relayq-app-2` | <http://localhost:8082> | 示例应用实例 2 |
| `mysql-master` | `localhost:3306` | 主库 `relayq`，用户名与密码均为 `relayq` |
| `mysql-slave` | `localhost:3307` | ROW binlog + GTID 只读从库 |

### 2. 检查应用状态

```bash
curl http://localhost:8081/actuator/health
curl http://localhost:8082/actuator/health
```

### 3. 提交一个 Echo 任务

```bash
curl -i -X POST http://localhost:8081/api/tasks \
  -H "Content-Type: application/json" \
  -d '{
    "biz_key": "readme-echo-001",
    "handler_name": "echo-handler",
    "params": {
      "message": "Hello, RelayQ!"
    }
  }'
```

### 4. 查询并观察任务执行

```bash
curl http://localhost:8081/api/tasks/by-biz-key/readme-echo-001
docker compose logs -f relayq-app-1 relayq-app-2
```

使用相同的 `biz_key` 再次提交时，RelayQ 会返回已有任务，不会创建重复记录。

<details>
<summary><strong>停止或重置本地环境</strong></summary>

停止服务并保留 MySQL 数据：

```bash
docker compose down
```

停止服务并清空本地任务数据：

```bash
docker compose down -v
```

</details>

## 🔌 接入 Spring Boot

当前项目 发布 `0.1.0`。在发布到制品仓库前，先在源码根目录执行：

```bash
mvn clean install
```

### 1. 引入 Starter

```xml
<dependency>
    <groupId>io.github.suanla</groupId>
    <artifactId>relayq-spring-boot-starter</artifactId>
    <version>0.1.0</version>
</dependency>
```

### 2. 初始化数据库

在目标 MySQL 8 数据库执行 [`schema.sql`](relayq-core/src/main/resources/db/schema.sql)。该文件是项目表结构的唯一权威定义，Starter 不会自动建表。

### 3. 配置数据源与 RelayQ

```yaml
spring:
  datasource:
    url: jdbc:mysql://localhost:3306/relayq?connectionTimeZone=%2B08:00&forceConnectionTimeZoneToSession=true
    username: relayq
    password: relayq
    hikari:
      maximum-pool-size: 40

relayq:
  enabled: true
  instance-id: ${HOSTNAME:relayq-local}
  pull:
    interval-ms: 1000
    batch-size: 100
  worker:
    core-size: 8
    max-size: 32
    queue-capacity: 1000
  lease:
    ttl-seconds: 30
  retry:
    default-max-retry: 3
  handler:
    timeout-ms: 30000
```

> [!TIP]
> 多实例部署时，每个实例的 `relayq.instance-id` 必须不同。Worker 并发会占用数据库连接，连接池容量应同时覆盖 Worker 写入、拉取、租约回收、快照落库和管理请求。

### 4. 注册 Handler

实现 `TaskHandler`，并通过 `@RelayqHandler` 同时将其注册为 Spring Bean、声明唯一名称：

```java
import com.suanla.relayq.core.handler.RelayqHandler;
import com.suanla.relayq.core.handler.TaskContext;
import com.suanla.relayq.core.handler.TaskHandler;
@RelayqHandler("send-email")
public class SendEmailHandler implements TaskHandler {

    @Override
    public void execute(TaskContext context) {
        SendEmailParams params = context.param(SendEmailParams.class);

        // 使用 bizKey 或业务唯一键保证下游操作幂等
        sendEmailIdempotently(context.getBizKey(), params);
    }
}
```

`TaskContext` 提供任务 ID、业务键、Handler 名称、参数、`traceId`、尝试次数、重试次数和计划执行时间等上下文。

### 5. 提交任务

注入 `TaskSubmitService` 后即可提交任务：

```java
SubmitResult result = taskSubmitService.submit(new SubmitCommand(
        "order-20260728-confirm",
        "send-email",
        "{\"recipient\":\"user@example.com\"}",
        null,  // scheduledTime：绝对执行时间
        30L,   // delaySeconds：相对延迟；不能与 scheduledTime 同时设置
        3      // maxRetry；为 null 时使用全局默认值
));
```

提交时会校验 Handler 是否已注册。`bizKey` 是提交幂等键，但不等同于 Handler 业务副作用幂等。

## 🧰 示例管理 API

以下 HTTP 接口由 `relayq-example` 提供，不属于 Starter 的自动配置接口：

| 方法 | 路径 | 用途 |
| :---: | --- | --- |
| `POST` | `/api/tasks` | 提交任务 |
| `GET` | `/api/tasks/{id}` | 查询任务详情 |
| `GET` | `/api/tasks?status=PENDING&page=1&size=20` | 按状态分页查询 |
| `GET` | `/api/tasks/by-biz-key/{bizKey}` | 按业务键查询 |
| `POST` | `/api/tasks/{id}/cancel` | 取消待执行任务 |
| `GET` | `/api/tasks/{id}/logs` | 查询执行记录 |
| `GET` | `/api/tasks/{id}/snapshots` | 查询事故快照 |
| `GET` | `/api/dead-letters` | 查询死信任务 |
| `POST` | `/api/dead-letters/{id}/redrive` | 人工重投死信 |
| `POST` | `/api/snapshots/manual` | 手动触发快照 |

提交接口支持 `scheduled_time` 或 `delay_seconds`，两者不能同时设置。示例应用统一使用 snake_case JSON 字段。

## ⚙️ 常用配置

| 配置项 | 默认值 | 说明 |
| --- | ---: | --- |
| `relayq.enabled` | `true` | 自动配置总开关 |
| `relayq.instance-id` | 自动生成 | 租约持有者标识 |
| `relayq.pull.interval-ms` | `1000` | 基础轮询间隔 |
| `relayq.pull.batch-size` | `100` | 单次最大抢占量 |
| `relayq.pull.empty-backoff-max-ms` | `30000` | 连续空拉最大退避时间 |
| `relayq.worker.core-size` | `8` | 常驻 Worker 数 |
| `relayq.worker.max-size` | `32` | 最大 Worker 数 |
| `relayq.worker.queue-capacity` | `1000` | Worker 有界队列容量 |
| `relayq.lease.ttl-seconds` | `30` | 任务租约有效期 |
| `relayq.retry.default-max-retry` | `3` | 默认最大重试次数 |
| `relayq.handler.timeout-ms` | `30000` | Handler 超时时间 |
| `relayq.snapshot.enabled` | `true` | 是否启用事故快照 |
| `relayq.snapshot.rate-per-minute` | `5` | 单实例每分钟快照上限 |

完整配置及注释见 [`application.yaml`](relayq-example/src/main/resources/application.yaml)。

## 📊 可观测性

引入 Spring Boot Actuator 与对应的 Micrometer Registry 后，RelayQ 会注册以下核心指标：

| 类别 | 指标 |
| --- | --- |
| 任务 | `relayq.task.backlog`、`relayq.task.execute`、`relayq.task.rejected` |
| 拉取 | `relayq.pull.duration`、`relayq.pull.batch.size`、`relayq.pull.empty.ratio` |
| 线程池 | `relayq.pool.active`、`relayq.pool.queue.size`、`relayq.pool.queue.remaining` |
| 租约 | `relayq.lease.reclaimed`、`relayq.lease.lost` |
| 快照 | `relayq.snapshot` |

示例应用通过 <http://localhost:8081/actuator/prometheus> 暴露 Prometheus 格式指标。

## 📦 项目结构

```text
relayq
├── relayq-core                 # 状态机、抢占执行、租约、重试、死信、快照与指标
├── relayq-spring-boot-starter  # 自动配置、属性绑定、Bean 装配与生命周期管理
├── relayq-example              # 可运行示例、管理 API 与示例 Handler
└── ops                         # Kubernetes 配置、MySQL 初始化脚本与示例数据
```

## 🧪 构建与测试

运行完整验证：

```bash
mvn clean verify
```

只构建示例及其依赖：

```bash
mvn -pl relayq-example -am package
```

本地运行示例：

```bash
docker compose up -d mysql-master mysql-slave mysql-replication-init
java -jar relayq-example/target/relayq-example-0.1.0.jar
```

## 📋 容量测试报告

2026-09-07 完成热态 **250 QPS × 15 分钟**测试：225001 次提交全部成功、零丢弃，
HTTP P99 为 **141.95 ms**。测试后按 RunId 验证，所有任务及执行审计均为首次成功，
主从校验一致；观察窗口内死锁增量为 0，应用无新增重启。

### 测试环境与口径

| 项目 | 本轮配置 |
| --- | --- |
| 正式 RunId | `soak-250-20260907-01` |
| 部署环境 | Docker Desktop Kubernetes，压测机与 K8s 机器分离 |
| 应用 | 2 个 Pod；每个 limit 为 **2 CPU / 1 GiB**，request 为 250m CPU / 512 MiB |
| 数据库连接池 | 每个应用实例 Hikari maximum-pool-size = **48** |
| backlog 指标缓存 | `RELAYQ_METRICS_BACKLOG_CACHE_SECONDS=3600` |
| MySQL 主库 | 按本阶段已确认配置：limit 4 CPU / 4 GiB，request 2 CPU / 1 GiB；配置 GTID 从库 |
| 数据持久化 | 按本阶段已确认配置：`innodb_flush_log_at_trx_commit=1`、`sync_binlog=1` |
| 测试入口 | K8s 主机局域网地址的 `18080` 端口；本轮未使用 LAN `30080` |
| 压测模型 | k6 `constant-arrival-rate`，250 iterations/s，每次迭代提交一个任务 |
| VU 配置 | 预分配 500，最大 500；正式轮实际活跃 VU 峰值 61 |
| 任务负载 | `POST /api/tasks`，唯一 `biz_key`，`handler_name=load-test-handler`，`params=null`，`max_retry=0` |
| Handler 行为 | no-op，不包含外部业务调用、休眠或业务处理耗时 |

资源配置为本阶段人工核对的运行配置，不代表仓库清单的默认值。应用镜像 digest、
MySQL 精确版本及全量数据规模未在本报告中独立核验；跨版本复测应另行归档这些信息。
k6 元数据记录的镜像标签为 `grafana/k6:latest`，不能视为固定版本标识。

### 测试流程

1. 先以 250 QPS 运行 5 分钟全量预热，RunId 为 `warm-soak-250-20260907-01`。
   该轮 75000 次请求全部成功、零丢弃，P99 为 150.25 ms。
2. 保持应用配置与 Pod 不变，启动证据采集，再执行 15 分钟正式轮。
   包装脚本记录的正式轮起止时间为 **2026-09-07 15:34:10—15:49:11 UTC**
   （北京时间 23:34:10—23:49:11），其中 k6 发压时长为 15 分钟。
3. 使用 `verify-load-run-k8s.ps1` 按实际请求数 **225001** 验证任务、审计及主从结果。
   脚本提示的 225000 是目标速率乘时长得到的计划值，验收以实际记录为准。
4. 死锁采集覆盖 **15:33:16—15:51:18 UTC**，配置窗口 1080 秒、每 5 秒轮询一次。

预热与正式结果分别保存。五分钟预热是本次执行步骤，不保证所有环境都能在固定时间内
进入稳定状态；后续复测仍需观察预热后段的延迟、吞吐和任务排空情况。

### 正式轮结果

| 指标 | 实测结果 |
| --- | ---: |
| HTTP 请求 / 接收成功数 | 225001 / 225001 |
| 实际 HTTP 吞吐 | 250.003452 req/s |
| HTTP 失败 / 检查失败 | 0 / 0 |
| 丢弃迭代 / 中断迭代 | 0 / 0 |
| HTTP 平均 / 中位延迟 | 43.55 ms / 36.72 ms |
| HTTP P90 / P95 / P99 | 67.09 ms / 83.32 ms / **141.95 ms** |
| HTTP 最大延迟 | 2.05 s |
| k6 退出码 | 0 |

脚本门槛为检查成功率 > 99%、HTTP 失败率 < 1%、HTTP P99 < 1000 ms、丢弃迭代数 = 0；
本轮全部通过，且实际请求失败数为零。最大延迟超过 1 秒不违反 P99 门槛。

| 正确性与运行状态 | 验收结果 |
| --- | --- |
| 主库任务 | 225001 条，全部 SUCCESS；retry_count = 0、current_attempt_no = 1 |
| 主库执行审计 | 225001 条，全部 SUCCESS、attempt 1，无未闭合审计 |
| 从库验证 | 对应 RunId 的任务与审计验收结果与主库一致，脚本判定 PASSED |
| 复制状态 | 校验时 IO / SQL 线程均运行，SecondsBehind = 0，无复制错误 |
| 死锁 | 起始 0、结束 0、增量 0，捕获事件 0 |
| 证据采集 | pollFailures 为空 |
| 应用状态 | 两个 Pod 均 1/1 Running，重启计数均保持 4，无新增重启 |

### 证据与结论边界

报告的 HTTP 数据已核对压测机本地 `ops/perf/results/` 下的正式轮 `.json`、`.log`
及预热轮记录。任务、复制、死锁与 Pod 状态来自本轮操作人员提供的验收输出；
K8s 机器上的证据目录为 `results/soak-250-20260907-01-evidence/`。
这些运行产物不作为 README 中必须可访问的链接，归档时应一并保留。
压测、采集与验收脚本的使用说明见 [容量测试指南](ops/perf/README.md)。

- **已验证的是当前环境下，两个 2C1G 应用实例对 no-op 任务负载的热态 250 QPS、15 分钟基线。**
  该结果不代表原先 1C1G 规格的验收成绩，也不是框架容量上限或生产容量承诺。
- HTTP P99 衡量提交接口响应时间，不是提交至异步任务完成的端到端 P99。
  测试后全部完成及复制追平，不代表已证明测试全过程无积压、无复制延迟。
- 本轮无故障注入，首次成功与单条审计是本次观测结果；RelayQ 的语义仍为 at-least-once。
  死锁为零也不能替代租约过期回收、节点故障和重复业务副作用的专项验证。
- backlog 缓存 TTL 为 3600 秒，因此不能使用该缓存图单独证明十五分钟内实时积压为零。
  变更采集频率、资源、镜像、入口或任务逻辑后，应重新预热并复测。

## ⚠️ 语义边界与已知限制

- 平台保证 **at-least-once**，不保证 exactly-once；Handler 必须幂等。
- `biz_key` 只避免重复创建任务，不能替代下游业务幂等。
- 快照限流目前是单进程级，多实例总量会随实例数增长。
- 延迟精度受数据库轮询间隔影响，不适合亚秒级定时。
- 当前数据模型未分片，长期运行时需要规划归档或冷热分离。
- 项目暂不包含管理控制台，`relayq-example` 仅提供 REST API。

## 📄 License

本项目基于 [Apache License 2.0](LICENSE) 开源。

---

<div align="center">

如果 RelayQ 对你有帮助，欢迎提交 Issue、贡献代码，或点亮一个 ⭐

</div>
