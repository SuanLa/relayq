# RelayQ 千万级数据与压测

这套工具把两个容易混淆的目标分开：

1. `seed.ps1` 直接向主库分批写入基线数据，用于构造一千万行的表和索引体量。
2. `submit-load.js` 通过 HTTP 提交真实任务，用于测提交 QPS、延迟和应用端开销。
3. `seed/activate-tasks.ps1` 分批激活预置的未来任务，用于测调度与执行吞吐。

所有写入只访问 `mysql-master`。Compose 使用 ROW binlog 和 GTID 将变更复制到
`mysql-slave`，`verify-replication.ps1` 会等待指定 GTID 集执行完成后再比较数据。

## 前置检查

- JDK 21、Maven 3.9+、Docker Compose。
- 执行 HTTP 压测时需要安装 k6。
- 一千万行会同时占用主库数据、二级索引、binlog、从库数据和 relay log。
  每个数据库节点建议至少预留 30 GiB，较稳妥的实验配置是 50 GiB。
- `ops/k8s/mysql/mysql-cluster.yaml` 当前 PVC 只有 5 GiB，不能直接承载本次数据规模；
  在 Kubernetes 执行前必须先扩容，并确认 StorageClass 支持扩容。
- 当前应用的读写连接都指向主库。从库用于验证复制和观察复制延迟，并不承担应用查询。

不要在包含重要数据的数据库上运行这些脚本。种子数据使用固定 ID 区间
`[7000000000000000000, 7000000000100000000)` 和 `perf-seed-` 业务键前缀。

## 1. 启动并验证真实主从

```powershell
docker compose up -d mysql-master mysql-slave mysql-replication-init
docker compose logs mysql-replication-init
```

初始化容器会创建复制账号，执行 `CHANGE REPLICATION SOURCE TO`，然后等待
`Replica_IO_Running` 和 `Replica_SQL_Running` 都变为 `Yes`。

如果使用的是旧版 Compose 创建的已有卷，应先执行校验。不要为了修复复制而直接清卷；
只有确认本地数据可以丢弃时，才可以通过删除 Compose volumes 重建全新实验环境。

## 2. 构造一千万行基线

为避免应用执行与造数争抢资源，先停止应用实例：

```powershell
docker compose stop relayq-app-1 relayq-app-2
.\ops\perf\seed\seed.ps1
```

默认数据分布：

| 状态 | 比例 | 一千万行对应数量 | 用途 |
| --- | ---: | ---: | --- |
| `SUCCESS` | 90% | 9,000,000 | 历史数据和索引体量 |
| `DEAD` | 5% | 500,000 | 死信分页、状态统计 |
| `PENDING` | 5% | 500,000 | 未来一年到期，不会被立即执行 |

脚本默认每 10,000 行提交一次，避免单个千万行事务撑爆 undo、redo 和复制队列。
种子插入使用 `INSERT IGNORE`，相同固定 ID 可以断点后重跑。

可以先用十万行验证环境：

```powershell
.\ops\perf\seed\seed.ps1 -TotalRows 100000 -BatchSize 10000
```

也可以调整状态比例：

```powershell
.\ops\perf\seed\seed.ps1 `
  -TotalRows 10000000 `
  -BatchSize 10000 `
  -PendingPercent 10 `
  -DeadPercent 5
```

造数完成后等待从库追平并比较主从：

```powershell
.\ops\perf\verify\verify-replication.ps1 -TimeoutSeconds 7200
```

校验通过需要满足：

- 主从各状态数量一致。
- `Replica_IO_Running`、`Replica_SQL_Running` 都是 `Yes`。
- `Last_IO_Error`、`Last_SQL_Error` 为空。
- `WAIT_FOR_EXECUTED_GTID_SET` 返回 `0`。

### 写入 Kubernetes 中的 MySQL

先确保本机 `kubectl` 已经加载目标集群的 kubeconfig，并核对：

```powershell
kubectl config current-context
kubectl cluster-info
kubectl get pods,pvc -n mysql -o wide
```

建议先用十万行验证。脚本会打印 context、API server、namespace 和 Pod，并且只有
显式传入 `-ConfirmTarget` 才会开始写：

```powershell
.\ops\perf\seed\seed-k8s.ps1 `
  -Context <目标-context> `
  -Namespace mysql `
  -MasterPod mysql-master-0 `
  -TotalRows 100000 `
  -ConfirmTarget
```

确认主库空间、数据分布和复制都正确后，再执行一千万行：

```powershell
.\ops\perf\seed\seed-k8s.ps1 `
  -Context <目标-context> `
  -Namespace mysql `
  -MasterPod mysql-master-0 `
  -TotalRows 10000000 `
  -BatchSize 10000 `
  -ExecutionChunkRows 250000 `
  -ConfirmTarget

.\ops\perf\verify\verify-k8s-replication.ps1 `
  -Context <目标-context> `
  -Namespace mysql `
  -MasterPod mysql-master-0 `
  -SlavePod mysql-slave-0
```

脚本通过 `kubectl exec` 在主库 Pod 内运行 MySQL 客户端，密码取自 Pod 已有的
`MYSQL_ROOT_PASSWORD` 环境变量，不会把密码读取或打印到本机。数据仍然只写主库。
Kubernetes 版本每 250,000 行主动结束并重建一次 exec 连接，避免千万级造数期间
单条 WebSocket 长时间保持后被桌面 Kubernetes 或本机网络中断。

## 3. 调度与执行吞吐

先激活一批未来 `PENDING` 任务，再启动应用，能获得清晰的测试起点：

```powershell
docker compose stop relayq-app-1 relayq-app-2
.\ops\perf\seed\activate-tasks.ps1 -Count 100000 -BatchSize 10000

$env:SPRING_PROFILES_ACTIVE = "perf"
docker compose up -d --build relayq-app-1 relayq-app-2
```

`perf` Profile 会关闭事故快照并把 RelayQ 日志降到 `WARN`，避免逐任务日志吞吐量
反过来成为瓶颈。预置任务使用无日志、无参数解析、无休眠的
`load-test-handler`。

直接更新数据库不会触发进程内提交唤醒信号。如果在应用运行时激活，最坏需要等待
空拉退避上限后才开始消费；需要精确起点时应采用上面的“先激活、再启动”顺序。

建议分级压测，而不是第一次就释放全部 500,000 个任务：

```text
10,000 → 100,000 → 500,000
```

每一级记录完成耗时、平均吞吐、P99、worker 队列水位、租约回收数和复制追平时间。

## 4. HTTP 提交压测

### 从另一台开发机访问 Docker Desktop Kubernetes

Docker Desktop 的 Kind 节点地址（例如 `172.18.0.2`）可能只能在 Docker 内部网络访问。
此时开发机即使能连接 Windows 端口，也无法直接经 Windows `portproxy` 到达 NodePort。
项目提供一个宿主机 Nginx 入口脚本：代理容器加入控制平面所在 Docker 网络，再把
`18080` 发布到 Kubernetes 所在 Windows 机器。

把 `start-relayq-lan-proxy.ps1` 和 `relayq-lan-proxy.conf.template` 放在同一目录，在
Kubernetes 机器的管理员 PowerShell 执行：

```powershell
.\ops\perf\network\start-relayq-lan-proxy.ps1 -OpenFirewall
```

脚本会自动识别 `desktop-control-plane` 的 Docker 网络、按需拉取 `nginx:alpine`、创建
私有网络入站防火墙规则，并启动 `relayq-lan-proxy`。重复执行只会重建这个代理容器，
不会修改 Kubernetes、PVC 或数据库。验证：

```powershell
curl.exe -i http://127.0.0.1:18080/actuator/health

# 在开发机执行，IP 替换为 Kubernetes Windows 机器的局域网地址
curl.exe -i --noproxy "*" http://192.168.0.107:18080/actuator/health
```

如需自定义监听端口或控制平面容器名：

```powershell
.\ops\perf\network\start-relayq-lan-proxy.ps1 `
  -NodeContainer desktop-control-plane `
  -NodePort 30080 `
  -HostPort 18080 `
  -OpenFirewall
```

此 Nginx 只负责跨越 Windows 与 Docker 内部网络，实际两个应用 Pod 仍由
`relayq-app` Kubernetes Service 负载均衡。正式压测的 `BASE_URLS` 使用
`http://<K8s-Windows-LAN-IP>:18080`。模板将每条上游 keepalive 连接限制为 50 个请求，
避免低 QPS、单活跃 VU 的冒烟测试长期固定在同一个 Pod；Pod 间不要求严格 50/50，
但压测期间两个 Pod 的请求速率都应大于零。

开发机使用 Docker 运行 k6。包装脚本会自动生成并打印 `RUN_ID`，预分配全部 VU，
避免测试过程中动态增加 VU，并把参数、UTC 起止时间和完整控制台输出保存在
`ops/perf/results/`：

```powershell
.\ops\perf\k6\run-docker.ps1 `
  -BaseUrls http://192.168.0.107:18080 `
  -Rate 150 `
  -Duration 5m `
  -PreAllocatedVUs 300 `
  -MaxVUs 300 `
  -P99Millis 1000
```

脚本使用 `constant-arrival-rate`，在两个实例间轮询提交，默认阈值是失败率低于 1%、
检查成功率高于 99%、HTTP P99 小于 1 秒。每轮可以显式指定不同的 `RUN_ID`，避免
`biz_key` 与历史轮次冲突。

每轮结束后不要手工拼接多行 SQL。使用只读验收脚本按 `RUN_ID` 同时检查任务终态、
重试次数、attempt、执行审计，并等待从库追平后比较主从汇总：

```powershell
Set-ExecutionPolicy -Scope Process -ExecutionPolicy Bypass -Force

.\ops\perf\verify\verify-load-run-k8s.ps1 `
  -Context docker-desktop `
  -RunId warm150-20260813-010222 `
  -ExpectedRows 45000 `
  -TimeoutSeconds 600
```

脚本只执行查询，不会修改或清空任务。只有全部任务为 `SUCCESS`、`retry_count=0`、
`current_attempt_no=1`，每条任务恰好存在一条 `SUCCESS/attempt 1` 审计，并且从库追平
后的汇总与主库一致时才返回 `VERDICT: PASSED`。Prometheus 中的
`relayq.lease.reclaimed`、`relayq.lease.lost` 和 `relayq.task.rejected` 增量仍需同时为零。

### 保留 MySQL 死锁与应用日志现场

容量复测不能只保留 k6 汇总。先在 Kubernetes 部署机启动只读观测脚本，并让它覆盖
完整压测窗口以及任务租约过期后的观察时间：

```powershell
.\ops\perf\observe\capture-deadlock-evidence-k8s.ps1 `
  -Context docker-desktop `
  -RunId baseline150-20260823-120000 `
  -DurationSeconds 480 `
  -PollSeconds 5
```

看到 `Start k6 now` 后，立即在开发机以同一个 `RUN_ID` 启动 k6：

```powershell
.\ops\perf\k6\run-docker.ps1 `
  -BaseUrls http://192.168.0.107:18080 `
  -Rate 150 `
  -Duration 5m `
  -PreAllocatedVUs 300 `
  -MaxVUs 300 `
  -P99Millis 1000 `
  -RunId baseline150-20260823-120000
```

观测脚本不会修改任务、租约或 MySQL 配置。证据保存在
`ops/perf/results/<RUN_ID>-evidence/`，包括：

- Deployment、ReplicaSet、Pod、实际镜像及 image ID；
- `Innodb_deadlocks` 的起止值和轮询时间序列；
- 死锁计数增长时立即抓取的 `SHOW ENGINE INNODB STATUS`；
- 观察窗口结束时的 InnoDB 状态、应用 Pod 日志和 Kubernetes 事件。

最后再运行 `verify-load-run-k8s.ps1`。只有 k6、任务/审计正确性、MySQL 死锁增量、
租约异常指标四组证据都齐全，才能判断一次容量测试通过或定位其失败原因。

### 定向验证租约回收锁顺序

正常 no-op 压测中如果没有租约过期，`Innodb_deadlocks` 增量为零并不能证明 Reaper
的新 SQL 路径已经被执行。标准 150 QPS 回归通过后，可在测试环境单独运行受控故障
注入。脚本只创建并修改本轮 `reaperprobe-*` 前缀的 `slow-handler` 任务，不删除任务，
也不修改千万基线数据。

先在 Kubernetes 机器的第一个 PowerShell 窗口启动证据采集：

```powershell
$runId = "reaperprobe-$(Get-Date -Format 'yyyyMMdd-HHmmss')"
$runId

.\ops\perf\observe\capture-deadlock-evidence-k8s.ps1 `
  -Context docker-desktop `
  -RunId $runId `
  -DurationSeconds 120 `
  -PollSeconds 2
```

看到 `Start k6 now` 后，不需要运行 k6；在同一台机器的第二个 PowerShell 窗口设置
完全相同的 `$runId`，再执行：

```powershell
.\ops\perf\observe\exercise-lease-reaper-k8s.ps1 `
  -Context docker-desktop `
  -RunId $runId `
  -BaseUrl http://127.0.0.1:18080 `
  -TaskCount 64 `
  -SleepMillis 10000 `
  -ForceDurationSeconds 15 `
  -ConfirmTarget
```

脚本通过 HTTP 创建慢任务，然后反复按升序主键、仅对本轮仍为 `RUNNING` 的任务把
租约改为过期。这样 Reaper 必须与 worker 终态更新真实并发，同时故障注入本身不会
重新引入“二级索引先于主键”的锁顺序。验收条件：

- `Forced lease rows > 0`，证明故障确实注入；
- `Reaper transitions > 0`，证明至少有任务真正经过回收路径；
- 注入脚本与证据目录中的 `Deadlock delta` 都为 `0`；
- 两个应用 Pod 和 MySQL 复制线程保持运行。

这个场景故意制造租约丢失和再次抢占，因此 `current_attempt_no > 1`、`LEASE_LOST`
以及 `relayq.lease.reclaimed` 增长属于预期现象，不能使用正常 no-op 轮次的
`verify-load-run-k8s.ps1` 恰好一次标准判定它。

一千万条基线数据应使用数据库造数脚本完成。不要把“通过 HTTP 提交一千万次”当成
第一次测试；例如 5,000 QPS 也需要约 33 分钟，而且会同时产生执行日志与复制流量。

## 5. 必须记录的指标

应用：

- `relayq.pull.duration`
- `relayq.pull.batch.size`
- `relayq.task.execute`
- `relayq.task.rejected`
- `relayq.lease.reclaimed`
- `relayq.lease.lost`
- `relayq.pool.active`
- `relayq.pool.queue.size`
- Hikari、JVM GC、CPU 和堆内存

MySQL：

- 主库 TPS、连接数、buffer pool 命中率、redo/binlog 写入
- 慢查询和锁等待
- 从库 `Seconds_Behind_Source`
- 从库 relay log、`Last_IO_Error`、`Last_SQL_Error`
- 主从 GTID 追平耗时

结果至少分成冷缓存和预热后两轮。压测过程中如果
`relayq.lease.reclaimed` 或 `relayq.lease.lost` 在无故障的 no-op 任务场景持续增长，
应先按正确性故障处理，不能只报告吞吐数字。
