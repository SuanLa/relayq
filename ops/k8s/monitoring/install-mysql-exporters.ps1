[CmdletBinding()]
param(
    [string]$Context = "",

    [ValidatePattern("^[a-z0-9]([-a-z0-9]*[a-z0-9])?$")]
    [string]$MysqlNamespace = "mysql",

    [ValidatePattern("^[a-z0-9]([-a-z0-9]*[a-z0-9])?$")]
    [string]$MonitoringNamespace = "monitoring",

    [ValidatePattern("^[a-z0-9]([-a-z0-9]*[a-z0-9])?$")]
    [string]$MasterPod = "mysql-master-0",

    [ValidatePattern("^[a-z0-9]([-a-z0-9]*[a-z0-9])?$")]
    [string]$SlavePod = "mysql-slave-0",

    [ValidatePattern("^[a-z0-9]([-a-z0-9]*[a-z0-9])?$")]
    [string]$Container = "mysql",

    [ValidatePattern("^[A-Za-z0-9_.%-]+$")]
    [string]$MysqlClientHostPattern = "10.244.%",

    [ValidateRange(1, 3600)]
    [int]$ReplicationTimeoutSeconds = 120,

    [switch]$ConfirmTarget
)

$ErrorActionPreference = "Stop"
$exporterUser = "relayq_exporter"
$contextArgs = @()
if (-not [string]::IsNullOrWhiteSpace($Context)) {
    $contextArgs = @("--context", $Context)
    $targetContext = $Context
}
else {
    $targetContext = (kubectl config current-context).Trim()
    if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($targetContext)) {
        throw "kubectl has no current context. Supply -Context explicitly."
    }
}

$server = (
    kubectl @contextArgs config view --minify `
        -o "jsonpath={.clusters[0].cluster.server}"
).Trim()
if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($server)) {
    throw "Unable to resolve the API server for context '$targetContext'."
}

Write-Host "Kubernetes context : $targetContext"
Write-Host "API server         : $server"
Write-Host "MySQL master       : $MysqlNamespace/$MasterPod ($Container)"
Write-Host "MySQL slave        : $MysqlNamespace/$SlavePod ($Container)"
Write-Host "Exporter namespace : $MonitoringNamespace"
Write-Host "MySQL account      : '$exporterUser'@'$MysqlClientHostPattern'"

if (-not $ConfirmTarget) {
    throw "Target not confirmed. Review the values and rerun with -ConfirmTarget."
}

kubectl @contextArgs get namespace $MonitoringNamespace -o name | Out-Null
if ($LASTEXITCODE -ne 0) {
    throw "Namespace '$MonitoringNamespace' does not exist. Install kube-prometheus-stack first."
}

foreach ($pod in @($MasterPod, $SlavePod)) {
    $phase = (
        kubectl @contextArgs get pod `
            --namespace $MysqlNamespace `
            $pod `
            -o "jsonpath={.status.phase}"
    ).Trim()
    if ($LASTEXITCODE -ne 0 -or $phase -ne "Running") {
        throw "Target pod is not Running: $MysqlNamespace/$pod (phase='$phase')."
    }
}

function Invoke-KubernetesMySql {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Pod,

        [Parameter(Mandatory = $true)]
        [string]$Sql
    )

    $remoteMysql = 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql' +
        ' --user=root --batch --skip-column-names'

    $output = $Sql |
        kubectl @contextArgs exec -i `
            --namespace $MysqlNamespace `
            $Pod `
            --container $Container `
            -- sh -c $remoteMysql

    if ($LASTEXITCODE -ne 0) {
        throw "MySQL command failed in $MysqlNamespace/$Pod."
    }
    return ($output -join "`n").Trim()
}

$randomBytes = New-Object byte[] 24
$random = [Security.Cryptography.RandomNumberGenerator]::Create()
try {
    $random.GetBytes($randomBytes)
}
finally {
    $random.Dispose()
}
$exporterPassword = ([Convert]::ToBase64String($randomBytes)).TrimEnd("=").Replace("+", "A").Replace("/", "B")

$accountSql = @"
CREATE USER IF NOT EXISTS '$exporterUser'@'$MysqlClientHostPattern'
  IDENTIFIED BY '$exporterPassword'
  WITH MAX_USER_CONNECTIONS 3;
ALTER USER '$exporterUser'@'$MysqlClientHostPattern'
  IDENTIFIED BY '$exporterPassword'
  WITH MAX_USER_CONNECTIONS 3;
GRANT PROCESS, REPLICATION CLIENT, SELECT ON *.*
  TO '$exporterUser'@'$MysqlClientHostPattern';
"@

Write-Host "Creating the least-privilege exporter account on the master."
Invoke-KubernetesMySql -Pod $MasterPod -Sql $accountSql | Out-Null

$gtid = Invoke-KubernetesMySql `
    -Pod $MasterPod `
    -Sql "SELECT @@GLOBAL.gtid_executed;"
if ([string]::IsNullOrWhiteSpace($gtid)) {
    throw "The master returned an empty GTID set."
}

Write-Host "Waiting for the account grant to replicate to $SlavePod."
$waitResult = Invoke-KubernetesMySql `
    -Pod $SlavePod `
    -Sql "SELECT WAIT_FOR_EXECUTED_GTID_SET('$gtid', $ReplicationTimeoutSeconds);"
if ($waitResult -ne "0") {
    throw "The slave did not apply the exporter account grant within $ReplicationTimeoutSeconds seconds."
}

$secretYaml = kubectl @contextArgs create secret generic mysql-exporter-credentials `
    --namespace $MonitoringNamespace `
    --from-literal="password=$exporterPassword" `
    --dry-run=client `
    -o yaml
if ($LASTEXITCODE -ne 0) {
    throw "Failed to render the exporter credential Secret."
}

$secretYaml | kubectl @contextArgs apply -f -
if ($LASTEXITCODE -ne 0) {
    throw "Failed to apply the exporter credential Secret."
}

$manifestPath = Join-Path $PSScriptRoot "mysql-exporters.yaml"
$dashboardPath = Join-Path $PSScriptRoot "dashboards\mysql-dashboard.yaml"
kubectl @contextArgs apply -f $manifestPath
if ($LASTEXITCODE -ne 0) {
    throw "Failed to apply $manifestPath."
}
kubectl @contextArgs apply -f $dashboardPath
if ($LASTEXITCODE -ne 0) {
    throw "Failed to apply $dashboardPath."
}

foreach ($deployment in @("mysqld-exporter-master", "mysqld-exporter-slave")) {
    kubectl @contextArgs rollout status `
        --namespace $MonitoringNamespace `
        "deployment/$deployment" `
        --timeout=5m
    if ($LASTEXITCODE -ne 0) {
        throw "Deployment did not become ready: $MonitoringNamespace/$deployment."
    }
}

Write-Host "MySQL exporters are ready. The generated password was stored only in Kubernetes Secret mysql-exporter-credentials."
kubectl @contextArgs get pods,svc `
    --namespace $MonitoringNamespace `
    --selector app=mysqld-exporter `
    -o wide
