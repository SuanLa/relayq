# RelayQ 运维、监控与压测

`ops/` 是项目部署、监控和容量测试的统一入口。所有命令默认从项目根目录执行。

## 目录

- `k8s/app/`：RelayQ 应用 Kubernetes 清单。
- `k8s/mysql/`：MySQL 主从 Kubernetes 清单。
- `k8s/monitoring/`：Prometheus、Grafana、ServiceMonitor、MySQL exporter 和 dashboard。
- `perf/seed/`：基线造数与预置任务激活。
- `perf/k6/`：HTTP 容量压测脚本。
- `perf/verify/`：MySQL 主从复制验证。
- `perf/network/`：开发机访问 Docker Desktop Kubernetes 的局域网代理。

## 推荐顺序

1. 应用 `k8s/mysql/mysql-cluster.yaml` 并验证主从复制。
2. 应用 `k8s/app/relayq-app.yaml` 并等待应用 Ready。
3. 按 `k8s/monitoring/README.md` 安装监控栈和 MySQL exporter。
4. 按 `perf/README.md` 造数、预热、执行 k6 阶梯压测并验证结果。

不要在包含重要数据的数据库上运行 `perf/seed/` 下的脚本。Kubernetes 写入脚本只有显式传入
`-ConfirmTarget` 才会开始写数据。
