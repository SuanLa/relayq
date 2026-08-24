# RelayQ 本地 Kubernetes 部署

`deploy-desktop-docker.ps1` 用于当前 Docker Desktop Kubernetes 环境，必须在运行 Docker Desktop
Kubernetes 的 Windows 机器上执行，并且该机器需要有一份当前项目源码。开发机只需提交并推送
代码，Kubernetes 机器拉取代码后运行本脚本，不再手工搬运镜像 tar。

部署前先运行只读基线检查。它会输出应用实际镜像和 imageID、profile、资源、Pod 重启数、
MySQL/监控组件状态，并检查主从复制线程、延迟和错误：

```powershell
Set-ExecutionPolicy -Scope Process -ExecutionPolicy Bypass -Force

.\ops\k8s\app\inspect-runtime-baseline.ps1 `
  -Context docker-desktop
```

只有输出 `BASELINE: PASSED` 才开始更新镜像。该脚本不会修改 Kubernetes 或数据库。

它把原来的手工步骤封装为：

1. 使用 `relayq-example/Dockerfile` 构建镜像。
2. 将镜像临时导出并复制到 `desktop-control-plane`。
3. 导入节点的 `k8s.io` containerd namespace。
4. 使用唯一 tag 和 `imagePullPolicy: Never` 更新现有 Deployment。
5. 等待两个 Pod 完成滚动更新；失败时输出 Pod、Deployment 和事件诊断。
6. 删除本机及控制平面节点中的临时镜像归档。

脚本不会重新应用整份 `relayq-app.yaml`，因此不会覆盖 Deployment 现场已有的环境变量。

从项目根目录执行：

```powershell
.\ops\k8s\app\deploy-desktop-docker.ps1 -ConfirmTarget
```

默认目标：

- Kubernetes context：当前 `kubectl` context（通常是 `docker-desktop`）
- namespace：`relayq`
- Deployment/container：`relayq-app`
- Docker Desktop 节点：`desktop-control-plane`
- 镜像仓库：`docker.io/library/relayq-example`

每次执行自动生成类似 `dev-20260812-153000-a1b2c3d4` 的唯一 tag。也可以显式指定：

```powershell
.\ops\k8s\app\deploy-desktop-docker.ps1 `
  -Tag lease-fix-20260812 `
  -ConfirmTarget
```

如果不想使用当前 context，可以显式指定：

```powershell
.\ops\k8s\app\deploy-desktop-docker.ps1 `
  -Context docker-desktop `
  -ConfirmTarget
```

强制不使用 Docker 构建缓存：

```powershell
.\ops\k8s\app\deploy-desktop-docker.ps1 `
  -NoCache `
  -ConfirmTarget
```

如果 Deployment 尚未创建，先执行一次：

```powershell
kubectl apply -f .\ops\k8s\app\relayq-app.yaml
```

该脚本只适用于镜像需要直接导入 Docker Desktop 控制平面 containerd 的本地测试集群。接入
Docker Hub、GHCR 或私有 Registry 后，应改用 `docker push` 和普通 `kubectl set image` 流程。
