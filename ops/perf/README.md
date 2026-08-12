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

先以较低速率验证，再逐级增加到系统出现明显排队或 P99 超过目标：

```powershell
k6 run `
  -e BASE_URLS=http://localhost:8081,http://localhost:8082 `
  -e RATE=1000 `
  -e DURATION=2m `
  -e PRE_ALLOCATED_VUS=100 `
  -e MAX_VUS=1000 `
  -e P99_MS=1000 `
  .\ops\perf\k6\submit-load.js
```

脚本使用 `constant-arrival-rate`，在两个实例间轮询提交，默认阈值是失败率低于 1%、
检查成功率高于 99%、HTTP P99 小于 1 秒。每轮可以显式指定不同的 `RUN_ID`，避免
`biz_key` 与历史轮次冲突。

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
