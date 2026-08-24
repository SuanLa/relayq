[CmdletBinding()]
param(
    [string]$Context = "",

    [ValidatePattern("^[a-z0-9]([-a-z0-9]*[a-z0-9])?$")]
    [string]$Namespace = "mysql",

    [string]$MasterPod = "mysql-master-0",

    [string]$SlavePod = "mysql-slave-0",

    [string]$Container = "mysql",

    [ValidatePattern("^[A-Za-z0-9][A-Za-z0-9.-]*$")]
    [string]$Database = "relayq",

    [Parameter(Mandatory = $true)]
    [ValidatePattern("^[A-Za-z0-9][A-Za-z0-9.-]*$")]
    [string]$RunId,

    [ValidateRange(0, 2147483647)]
    [long]$ExpectedRows = 0,

    [ValidateRange(1, 86400)]
    [int]$TimeoutSeconds = 600
)

$ErrorActionPreference = "Stop"
$contextArgs = @()
if (-not [string]::IsNullOrWhiteSpace($Context)) {
    $contextArgs = @("--context", $Context)
}

function Invoke-KubernetesMySql {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Pod,

        [Parameter(Mandatory = $true)]
        [string]$Sql,

        [switch]$IncludeColumnNames
    )

    $remoteMysql = 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql' +
        ' --user=root' +
        " --database=$Database" +
        ' --batch'
    if (-not $IncludeColumnNames) {
        $remoteMysql += ' --skip-column-names'
    }

    $output = $Sql |
        kubectl @contextArgs exec -i `
            --namespace $Namespace `
            $Pod `
            --container $Container `
            -- sh -c $remoteMysql

    if ($LASTEXITCODE -ne 0) {
        throw "MySQL command failed in $Namespace/$Pod."
    }
    return @($output)
}

function Convert-SummaryRow {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Row
    )

    $values = $Row -split "`t"
    if ($values.Count -ne 11) {
        throw "Unexpected verification result: $Row"
    }
    return [pscustomobject]@{
        TaskTotal = [long]$values[0]
        TaskSuccess = [long]$values[1]
        TaskNonSuccess = [long]$values[2]
        RetryNonZero = [long]$values[3]
        AttemptNotOne = [long]$values[4]
        MaxAttempt = [long]$values[5]
        AuditTotal = [long]$values[6]
        AuditSuccess = [long]$values[7]
        AuditNonSuccess = [long]$values[8]
        AuditAttemptNotOne = [long]$values[9]
        AuditOpen = [long]$values[10]
    }
}

foreach ($commandName in @("kubectl")) {
    if (-not (Get-Command $commandName -ErrorAction SilentlyContinue)) {
        throw "Required command was not found in PATH: $commandName"
    }
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

Write-Host "Kubernetes context : $targetContext"
Write-Host "API server         : $apiServer"
Write-Host "MySQL target       : $Namespace/$MasterPod -> $SlavePod"
Write-Host "Database           : $Database"
Write-Host "RUN_ID             : $RunId"
if ($ExpectedRows -gt 0) {
    Write-Host "Expected rows      : $ExpectedRows"
}

$runPattern = "$RunId-%"
$summarySql = @"
SELECT
    COUNT(DISTINCT t.id) AS task_total,
    COUNT(DISTINCT CASE WHEN t.status = 'SUCCESS' THEN t.id END) AS task_success,
    COUNT(DISTINCT CASE WHEN t.status <> 'SUCCESS' THEN t.id END) AS task_non_success,
    COUNT(DISTINCT CASE WHEN t.retry_count <> 0 THEN t.id END) AS retry_non_zero,
    COUNT(DISTINCT CASE WHEN t.current_attempt_no <> 1 THEN t.id END) AS attempt_not_one,
    COALESCE(MAX(t.current_attempt_no), 0) AS max_attempt,
    COUNT(l.id) AS audit_total,
    COALESCE(SUM(l.outcome = 'SUCCESS'), 0) AS audit_success,
    COALESCE(SUM(l.id IS NOT NULL AND l.outcome <> 'SUCCESS'), 0) AS audit_non_success,
    COALESCE(SUM(l.id IS NOT NULL AND l.attempt_no <> 1), 0) AS audit_attempt_not_one,
    COALESCE(SUM(l.id IS NOT NULL AND (l.outcome IS NULL OR l.end_time IS NULL)), 0) AS audit_open
FROM task_info t
LEFT JOIN task_execute_log l ON l.task_id = t.id
WHERE t.biz_key LIKE '$runPattern';
"@

$detailsSql = @"
SELECT
    t.status,
    t.retry_count,
    t.current_attempt_no,
    COALESCE(l.outcome, '<NO_AUDIT>') AS audit_outcome,
    COALESCE(l.failure_kind, '<NONE>') AS failure_kind,
    COUNT(*) AS row_count
FROM task_info t
LEFT JOIN task_execute_log l ON l.task_id = t.id
WHERE t.biz_key LIKE '$runPattern'
GROUP BY
    t.status,
    t.retry_count,
    t.current_attempt_no,
    l.outcome,
    l.failure_kind
ORDER BY
    t.status,
    t.retry_count,
    t.current_attempt_no,
    l.outcome,
    l.failure_kind;
"@

$masterSummaryRow = (
    Invoke-KubernetesMySql -Pod $MasterPod -Sql $summarySql |
        Select-Object -First 1
)
$masterSummary = Convert-SummaryRow -Row $masterSummaryRow

Write-Host "`nMASTER SUMMARY"
$masterSummary | Format-List | Out-Host
Write-Host "MASTER DETAILS"
Invoke-KubernetesMySql `
    -Pod $MasterPod `
    -Sql $detailsSql `
    -IncludeColumnNames |
    ForEach-Object { Write-Host $_ }

$gtid = (
    Invoke-KubernetesMySql `
        -Pod $MasterPod `
        -Sql "SELECT @@GLOBAL.gtid_executed;" |
        Select-Object -First 1
).Trim()
if ([string]::IsNullOrWhiteSpace($gtid)) {
    throw "The master returned an empty GTID set."
}

Write-Host "`nWaiting for $Namespace/$SlavePod to execute the master's GTID set."
$waitResult = (
    Invoke-KubernetesMySql `
        -Pod $SlavePod `
        -Sql "SELECT WAIT_FOR_EXECUTED_GTID_SET('$gtid', $TimeoutSeconds);" |
        Select-Object -First 1
).Trim()
if ($waitResult -ne "0") {
    throw "Replica did not catch up within $TimeoutSeconds seconds."
}

$slaveSummaryRow = (
    Invoke-KubernetesMySql -Pod $SlavePod -Sql $summarySql |
        Select-Object -First 1
)
$slaveSummary = Convert-SummaryRow -Row $slaveSummaryRow

Write-Host "`nSLAVE SUMMARY"
$slaveSummary | Format-List | Out-Host

$replicaStatusLines = Invoke-KubernetesMySql `
    -Pod $SlavePod `
    -Sql "SHOW REPLICA STATUS;" `
    -IncludeColumnNames
$replicaStatus = (
    $replicaStatusLines -join "`n" |
        ConvertFrom-Csv -Delimiter "`t" |
        Select-Object -First 1
)
if ($null -eq $replicaStatus) {
    throw "SHOW REPLICA STATUS returned no row on $Namespace/$SlavePod."
}
$ioRunning = if ($null -ne $replicaStatus.Replica_IO_Running) {
    $replicaStatus.Replica_IO_Running
}
else {
    $replicaStatus.Slave_IO_Running
}
$sqlRunning = if ($null -ne $replicaStatus.Replica_SQL_Running) {
    $replicaStatus.Replica_SQL_Running
}
else {
    $replicaStatus.Slave_SQL_Running
}
$secondsBehind = if ($null -ne $replicaStatus.Seconds_Behind_Source) {
    $replicaStatus.Seconds_Behind_Source
}
else {
    $replicaStatus.Seconds_Behind_Master
}

Write-Host "REPLICATION"
[pscustomobject]@{
    IOThread = $ioRunning
    SQLThread = $sqlRunning
    SecondsBehind = $secondsBehind
    LastIOError = $replicaStatus.Last_IO_Error
    LastSQLError = $replicaStatus.Last_SQL_Error
} | Format-List | Out-Host

$failures = [System.Collections.Generic.List[string]]::new()
if ($masterSummary.TaskTotal -eq 0) {
    $failures.Add("No task matched RUN_ID '$RunId'.")
}
if ($ExpectedRows -gt 0 -and $masterSummary.TaskTotal -ne $ExpectedRows) {
    $failures.Add(
        "Expected $ExpectedRows tasks, found $($masterSummary.TaskTotal).")
}
if ($masterSummary.TaskNonSuccess -ne 0) {
    $failures.Add("Non-SUCCESS tasks: $($masterSummary.TaskNonSuccess).")
}
if ($masterSummary.RetryNonZero -ne 0) {
    $failures.Add("Tasks with retry_count != 0: $($masterSummary.RetryNonZero).")
}
if ($masterSummary.AttemptNotOne -ne 0) {
    $failures.Add("Tasks with current_attempt_no != 1: $($masterSummary.AttemptNotOne).")
}
if ($masterSummary.AuditTotal -ne $masterSummary.TaskTotal) {
    $failures.Add(
        "Audit count $($masterSummary.AuditTotal) does not equal task count $($masterSummary.TaskTotal).")
}
if ($masterSummary.AuditSuccess -ne $masterSummary.TaskTotal) {
    $failures.Add(
        "SUCCESS audit count $($masterSummary.AuditSuccess) does not equal task count $($masterSummary.TaskTotal).")
}
if ($masterSummary.AuditNonSuccess -ne 0) {
    $failures.Add("Non-SUCCESS audits: $($masterSummary.AuditNonSuccess).")
}
if ($masterSummary.AuditAttemptNotOne -ne 0) {
    $failures.Add("Audits with attempt_no != 1: $($masterSummary.AuditAttemptNotOne).")
}
if ($masterSummary.AuditOpen -ne 0) {
    $failures.Add("Audits without a terminal outcome/end_time: $($masterSummary.AuditOpen).")
}
if ($slaveSummaryRow -ne $masterSummaryRow) {
    $failures.Add("Master and slave summaries differ after GTID catch-up.")
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
if (-not [string]::IsNullOrWhiteSpace($replicaStatus.Last_IO_Error)) {
    $failures.Add("Replica Last_IO_Error is not empty: $($replicaStatus.Last_IO_Error)")
}
if (-not [string]::IsNullOrWhiteSpace($replicaStatus.Last_SQL_Error)) {
    $failures.Add("Replica Last_SQL_Error is not empty: $($replicaStatus.Last_SQL_Error)")
}

if ($failures.Count -gt 0) {
    Write-Host "`nVERDICT: FAILED" -ForegroundColor Red
    foreach ($failure in $failures) {
        Write-Host "- $failure" -ForegroundColor Red
    }
    throw "Load-run correctness verification failed."
}

Write-Host "`nVERDICT: PASSED" -ForegroundColor Green
Write-Host "All tasks completed once, all audits are SUCCESS attempt 1, and the replica matches the master."
