# Spring JDBC 存储迁移（未发布）

[English](spring-jdbc.en.md) · [返回 README](../../README.md) · [Issue #1](https://github.com/SuanLa/relayq/issues/1)

## 状态与范围

此文档描述当前源码，不修改 Maven Central 已发布的 `0.1.0`，也不表示已发布新版本。发布前需要确定新版本号，不能覆盖 `0.1.0`。

`relayq-core` 与 `relayq-spring-boot-starter` 的生产依赖已移除 MyBatis / MyBatis-Plus。任务、审计、快照由 `NamedParameterJdbcTemplate` 实现；不再注册 RelayQ 的 `@MapperScan`、Mapper XML 或 MyBatis-Plus 自定义器，不修改宿主的 Mapper locations。

保持不变：

- `TaskSubmitService`、Handler 与管理服务的业务接口及 `biz_key` 提交去重语义。
- 三张表及索引；不需要因本次迁移执行表结构变更。
- MySQL 8 的 `FOR UPDATE SKIP LOCKED`、数据库时间、租约续期/回收、owner/status fencing、重试、取消、死信重投和积压统计口径。
- 状态更新与执行审计的原有事务边界；提交后的调度唤醒，回滚不唤醒。
- 业务副作用幂等仍由 Handler 实现；本次迁移不提供扣款等业务的 exactly-once 保证。

本次没有调整 Pod 资源、Hikari 大小、监控缓存时长、MySQL 刷盘策略或调度算法，也没有复跑容量测试。

## 接入与升级注意事项

1. 继续提供 MySQL JDBC 驱动、`spring.datasource.*` 和与该 DataSource 对应的 Spring 事务管理器。普通单数据源 Boot 应用使用 JDBC Starter 的自动配置即可。RelayQ 复用该数据源及连接池，不创建私有池。
2. 使用 MyBatis / MyBatis-Plus 的业务项目须自行声明自己的 Starter、版本和扫描配置。不要因为升级 RelayQ 就删除业务 ORM；也不要同时混装两套互不兼容的 ORM Starter。RelayQ 不再强制 ORM 版本，但无法修复宿主自身的依赖冲突。
3. 宿主业务 Mapper 与 RelayQ 使用同一个 DataSource、同一个 Spring 事务时，可共同提交或回滚。多个 DataSource / 多个事务管理器必须明确选择并确保对应关系；本次不新增多数据源自动路由或跨库事务支持。
4. 删除**仅用于 RelayQ** 的 Mapper 扫描、XML locations 与 MyBatis-Plus 配置；保留业务 Mapper 的配置。不要把 RelayQ 的普通存储接口包纳入宿主 ORM 扫描。
5. 三个 `core.mapper.*Mapper` 名称保留，但已成为普通存储接口，不再继承 `BaseMapper`。继承来的 `insert`、`updateById`、Wrapper 查询等通用 API 不再存在（审计和快照保留显式 `insert`）。实体也不再带 ORM 注解。依赖这些内部 API、实体 ORM 映射或旧 XML 的自定义扩展需要适配并重新编译；这不是内部 API 的二进制兼容替换。
6. 自定义存储 Bean 仍可通过相应接口覆盖默认实现，但须自行遵守事务、fencing、唯一键及返回值约定。单行查询无结果返回 `null`；空 ID 集合不执行 SQL；枚举按名称绑定；审计/快照插入的空字段省略以使用数据库默认值。ID 继续由服务层生成。

## 可重复验证

要求 JDK 21 和 Maven。自 2026-09-15 起已移除 Testcontainers。默认构建不连接 MySQL，集成测试明确显示为跳过。显式开启前，须配置连接并取得在指定服务器上创建/读写/修改/删除隔离测试表的授权。

```powershell
mvn clean verify

# 两个 profile 分开执行；仅增加 test-scope 的宿主 ORM 依赖。
mvn -pl relayq-spring-boot-starter -am -Pcompat-mybatis '-Drelayq.mysql.tests=true' '-Dtest=JdbcStarterIntegrationTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
mvn -pl relayq-spring-boot-starter -am -Pcompat-mybatis-plus '-Drelayq.mysql.tests=true' '-Dtest=JdbcStarterIntegrationTest' '-Dsurefire.failIfNoSpecifiedTests=false' test

# 不启用兼容性 profile 时，生产依赖树不应包含以下组件。
mvn -pl relayq-core,relayq-spring-boot-starter -am dependency:tree '-Dincludes=org.mybatis:*,org.mybatis.spring.boot:*,com.baomidou:*'
```

覆盖内容：真实 SQL 的参数/枚举/NULL/时间映射、唯一键异常、批量 ID、分页、任务提交去重、抢占事务及并发跳锁、过期候选二次校验、续租与迟到回写、重试和死信、快照、状态与审计原子回滚、提交后唤醒，以及示例 HTTP 端到端流程。

兼容性测试分别加载原生 MyBatis Starter `3.0.3` 和 MyBatis-Plus Starter `3.5.17` 的真实自动配置与业务 XML Mapper，验证业务数据和 RelayQ 任务在同一事务中共同提交/回滚；不只是检查应用能否启动。它们不是所有宿主 ORM 版本的兼容性承诺。

每次测试报告位于各模块 `target/surefire-reports/`。历史 README 中 250 QPS 的报告保持原口径，不能作为 JDBC 迁移后的性能验收。

## 历史验证记录（2026-09-14）

以下结果来自替换前的容器测试方案，不是 2026-09-15 外部 MySQL 方案的复测结果。本次替换不宣称数据库集成测试已重新通过。

JDK `21.0.7`、Spring Boot `3.2.9`、MySQL `8.0.36`、Testcontainers `1.21.4`，JVM 时区 `Asia/Shanghai`。全量 `mvn verify` 构建成功，95 项测试全部通过、无失败/错误/跳过（core 67、starter 15、example 13）。此前已执行 clean 清除旧 Mapper XML 产物。

两个兼容性 profile 分别通过 3 项测试。构建另增加 Enforcer 规则，禁止生产依赖重新引入 MyBatis / MyBatis-Plus，兼容测试的 test-scope 依赖除外。

端到端回归同步修正了旧测试夹具：移除固定数据库/profile 配置，重复重投断言 HTTP 409，手动快照提供任务 ID；未改变相应业务规则。本次未提交、推送或发布制品，未对现有 K8s/数据库执行操作。
