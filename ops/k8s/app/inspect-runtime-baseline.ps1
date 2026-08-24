[CmdletBinding()]
param(
    [string]$Context = "",

    [ValidatePattern("^[a-z0-9]([-a-z0-9]*[a-z0-9])?$")]
    [string]$Namespace = "relayq",

    [ValidatePattern("^[a-z0-9]([-a-z0-9]*[a-z0-9])?$")]
    [string]$Deployment = "relayq-app",

    [ValidatePattern("^[a-z0-9]([-a-z0-9]*[a-z0-9])?$")]
    [string]$Container = "relayq-app",

    [ValidatePattern("^[a-z0-9]([-a-z0-9]*[a-z0-9])?$")]
    [string]$MysqlNamespace = "mysql",

    [string]$MysqlMasterPod = "mysql-master-0",

    [string]$MysqlSlavePod = "mysql-slave-0",

    [string]$MysqlContainer = "mysql",

    [ValidatePattern("^[a-z0-9]([-a-z0-9]*[a-z0-9])?$")]
    [string]$MonitoringNamespace = "monitoring"
)

$ErrorActionPreference = "Stop"
$contextArgs = @()
if (-not [string]::IsNullOrWhiteSpace($Context)) {
    $contextArgs = @("--context", $Context)
}

function Invoke-KubectlJson {
    param(
        [Parameter(Mandatory = $true)]
        [string[]]$Arguments,

        [Parameter(Mandatory = $true)]
        [string]$Description
    )

    $output = @(& kubectl @contextArgs @Arguments -o json)
    if ($LASTEXITCODE -ne 0) {
        throw "$Description failed with exit code $LASTEXITCODE."
    }
    return (($output -join "`n") | ConvertFrom-Json)
}

function Show-KubectlTable {
    param(
        [Parameter(Mandatory = $true)]
        [string[]]$Arguments,

        [Parameter(Mandatory = $true)]
        [string]$Description
    )

    & kubectl @contextArgs @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "$Description failed with exit code $LASTEXITCODE."
    }
}

function Test-PodReady {
    param(
        [Parameter(Mandatory = $true)]
        [object]$Pod
    )

    $statuses = @($Pod.status.containerStatuses)
    return $Pod.status.phase -eq "Running" `
        -and $statuses.Count -gt 0 `
        -and @($statuses | Where-Object { -not $_.ready }).Count -eq 0
}

function Get-ReplicaStatusValue {
    param(
        [Parameter(Mandatory = $true)]
        [string[]]$Lines,

        [Parameter(Mandatory = $true)]
        [string[]]$Names
    )

    foreach ($name in $Names) {
        $match = $Lines |
            Select-String -Pattern "^\s*$([regex]::Escape($name)):\s*(.*)$" |
            Select-Object -First 1
        if ($null -ne $match) {
            return $match.Matches[0].Groups[1].Value.Trim()
        }
    }
    return $null
}

if (-not (Get-Command kubectl -ErrorAction SilentlyContinue)) {
    throw "Required command was not found in PATH: kubectl"
}

$targetContext = $Context.Trim()
if ([string]::IsNullOrWhiteSpace($targetContext)) {
    $targetContext = (kubectl config current-context).Trim()
    if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($targetContext)) {
        throw "kubectl has no usable current context."
    }
}
$apiServer = (
    kubectl @contextArgs config view --minify `
        -o "jsonpath={.clusters[0].cluster.server}"
).Trim()
if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($apiServer)) {
    throw "Unable to resolve the Kubernetes API server."
}

$deploymentObject = Invoke-KubectlJson `
    -Arguments @("get", "deployment", $Deployment, "--namespace", $Namespace) `
    -Description "Deployment inspection"
$deploymentContainer = @($deploymentObject.spec.template.spec.containers) |
    Where-Object { $_.name -eq $Container } |
    Select-Object -First 1
if ($null -eq $deploymentContainer) {
    throw "Container '$Container' does not exist in $Namespace/$Deployment."
}
$activeProfile = @($deploymentContainer.env) |
    Where-Object { $_.name -eq "SPRING_PROFILES_ACTIVE" } |
    Select-Object -ExpandProperty value -First 1
if ([string]::IsNullOrWhiteSpace($activeProfile)) {
    $activeProfile = "<not set>"
}

$desiredReplicas = [int]$deploymentObject.spec.replicas
$updatedReplicas = [int]$deploymentObject.status.updatedReplicas
$readyReplicas = [int]$deploymentObject.status.readyReplicas
$availableReplicas = [int]$deploymentObject.status.availableReplicas

Write-Host "Kubernetes context : $targetContext"
Write-Host "API server         : $apiServer"
Write-Host "`nAPPLICATION DEPLOYMENT"
[pscustomobject]@{
    Namespace = $Namespace
    Deployment = $Deployment
    DesiredReplicas = $desiredReplicas
    UpdatedReplicas = $updatedReplicas
    ReadyReplicas = $readyReplicas
    AvailableReplicas = $availableReplicas
    Strategy = $deploymentObject.spec.strategy.type
    Image = $deploymentContainer.image
    ImagePullPolicy = $deploymentContainer.imagePullPolicy
    ActiveProfile = $activeProfile
    CpuRequest = $deploymentContainer.resources.requests.cpu
    CpuLimit = $deploymentContainer.resources.limits.cpu
    MemoryRequest = $deploymentContainer.resources.requests.memory
    MemoryLimit = $deploymentContainer.resources.limits.memory
} | Format-List | Out-Host

$applicationPods = Invoke-KubectlJson `
    -Arguments @(
        "get", "pods",
        "--namespace", $Namespace,
        "--selector", "app=$Deployment"
    ) `
    -Description "Application Pod inspection"

Write-Host "APPLICATION PODS"
@($applicationPods.items) | ForEach-Object {
    $pod = $_
    $containerStatus = @($pod.status.containerStatuses) |
        Where-Object { $_.name -eq $Container } |
        Select-Object -First 1
    [pscustomobject]@{
        Name = $pod.metadata.name
        Node = $pod.spec.nodeName
        PodIP = $pod.status.podIP
        Phase = $pod.status.phase
        Ready = $containerStatus.ready
        Restarts = $containerStatus.restartCount
        Image = $containerStatus.image
        ImageID = $containerStatus.imageID
    }
} | Format-Table -AutoSize -Wrap | Out-Host

Write-Host "APPLICATION SERVICES"
Show-KubectlTable `
    -Arguments @("get", "service", "--namespace", $Namespace, "-o", "wide") `
    -Description "Application Service inspection"

$mysqlPods = Invoke-KubectlJson `
    -Arguments @("get", "pods", "--namespace", $MysqlNamespace) `
    -Description "MySQL Pod inspection"
Write-Host "`nMYSQL PODS / SERVICES / PVC"
Show-KubectlTable `
    -Arguments @(
        "get", "pods,service,persistentvolumeclaim",
        "--namespace", $MysqlNamespace,
        "-o", "wide"
    ) `
    -Description "MySQL resource inspection"

$monitoringPods = Invoke-KubectlJson `
    -Arguments @("get", "pods", "--namespace", $MonitoringNamespace) `
    -Description "Monitoring Pod inspection"
Write-Host "`nMONITORING PODS / SERVICES / PVC / SERVICE MONITORS"
Show-KubectlTable `
    -Arguments @(
        "get", "pods,service,persistentvolumeclaim,servicemonitor",
        "--namespace", $MonitoringNamespace,
        "-o", "wide"
    ) `
    -Description "Monitoring resource inspection"

$remoteMysql = 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql --user=root'
$replicaStatusLines = @(
    "SHOW REPLICA STATUS\G" |
        kubectl @contextArgs exec -i `
            --namespace $MysqlNamespace `
            $MysqlSlavePod `
            --container $MysqlContainer `
            -- sh -c $remoteMysql
)
if ($LASTEXITCODE -ne 0) {
    throw "Replica status inspection failed in $MysqlNamespace/$MysqlSlavePod."
}

$ioRunning = Get-ReplicaStatusValue `
    -Lines $replicaStatusLines `
    -Names @("Replica_IO_Running", "Slave_IO_Running")
$sqlRunning = Get-ReplicaStatusValue `
    -Lines $replicaStatusLines `
    -Names @("Replica_SQL_Running", "Slave_SQL_Running")
$secondsBehind = Get-ReplicaStatusValue `
    -Lines $replicaStatusLines `
    -Names @("Seconds_Behind_Source", "Seconds_Behind_Master")
$lastIoError = Get-ReplicaStatusValue `
    -Lines $replicaStatusLines `
    -Names @("Last_IO_Error")
$lastSqlError = Get-ReplicaStatusValue `
    -Lines $replicaStatusLines `
    -Names @("Last_SQL_Error")

Write-Host "`nREPLICATION"
[pscustomobject]@{
    IOThread = $ioRunning
    SQLThread = $sqlRunning
    SecondsBehind = $secondsBehind
    LastIOError = $lastIoError
    LastSQLError = $lastSqlError
} | Format-List | Out-Host

$failures = [System.Collections.Generic.List[string]]::new()
if ($desiredReplicas -le 0 `
        -or $updatedReplicas -ne $desiredReplicas `
        -or $readyReplicas -ne $desiredReplicas `
        -or $availableReplicas -ne $desiredReplicas) {
    $failures.Add(
        "Application rollout is incomplete: desired=$desiredReplicas, updated=$updatedReplicas, ready=$readyReplicas, available=$availableReplicas.")
}
$unreadyApplicationPods = @($applicationPods.items | Where-Object { -not (Test-PodReady $_) })
if (@($applicationPods.items).Count -ne $desiredReplicas) {
    $failures.Add(
        "Application Pod count $(@($applicationPods.items).Count) does not equal desired replicas $desiredReplicas.")
}
if ($unreadyApplicationPods.Count -gt 0) {
    $failures.Add("Unready application Pods: $($unreadyApplicationPods.metadata.name -join ', ').")
}
$mysqlPodNames = @($mysqlPods.items | ForEach-Object { $_.metadata.name })
foreach ($requiredMysqlPod in @($MysqlMasterPod, $MysqlSlavePod)) {
    if ($mysqlPodNames -notcontains $requiredMysqlPod) {
        $failures.Add("Required MySQL Pod is missing: $requiredMysqlPod.")
    }
}
$unreadyMysqlPods = @($mysqlPods.items | Where-Object { -not (Test-PodReady $_) })
if ($unreadyMysqlPods.Count -gt 0) {
    $failures.Add("Unready MySQL Pods: $($unreadyMysqlPods.metadata.name -join ', ').")
}
$unreadyMonitoringPods = @($monitoringPods.items | Where-Object { -not (Test-PodReady $_) })
if (@($monitoringPods.items).Count -eq 0) {
    $failures.Add("Monitoring namespace contains no Pods.")
}
if ($unreadyMonitoringPods.Count -gt 0) {
    $failures.Add("Unready monitoring Pods: $($unreadyMonitoringPods.metadata.name -join ', ').")
}
if ($ioRunning -ne "Yes") {
    $failures.Add("Replica IO thread is not running: '$ioRunning'.")
}
if ($sqlRunning -ne "Yes") {
    $failures.Add("Replica SQL thread is not running: '$sqlRunning'.")
}
if ($secondsBehind -ne "0") {
    $failures.Add("Replica lag is not zero: '$secondsBehind'.")
}
if (-not [string]::IsNullOrWhiteSpace($lastIoError)) {
    $failures.Add("Replica Last_IO_Error is not empty: $lastIoError")
}
if (-not [string]::IsNullOrWhiteSpace($lastSqlError)) {
    $failures.Add("Replica Last_SQL_Error is not empty: $lastSqlError")
}

if ($failures.Count -gt 0) {
    Write-Host "`nBASELINE: FAILED" -ForegroundColor Red
    foreach ($failure in $failures) {
        Write-Host "- $failure" -ForegroundColor Red
    }
    throw "Runtime baseline verification failed."
}

Write-Host "`nBASELINE: PASSED" -ForegroundColor Green
Write-Host "Application, MySQL, monitoring, and replication are ready for a controlled rollout."
