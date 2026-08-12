[CmdletBinding()]
param(
    [string]$Context = "",

    [ValidatePattern("^[a-z0-9]([-a-z0-9]*[a-z0-9])?$")]
    [string]$Namespace = "mysql",

    [ValidatePattern("^[a-z0-9]([-a-z0-9]*[a-z0-9])?$")]
    [string]$MasterPod = "mysql-master-0",

    [ValidatePattern("^[a-z0-9]([-a-z0-9]*[a-z0-9])?$")]
    [string]$Container = "mysql",

    [ValidatePattern("^[A-Za-z0-9_]+$")]
    [string]$Database = "relayq",

    [ValidateRange(1, 100000000)]
    [long]$TotalRows = 10000000,

    [ValidateRange(1, 100000)]
    [int]$BatchSize = 10000,

    [ValidateRange(10000, 5000000)]
    [int]$ExecutionChunkRows = 250000,

    [ValidateRange(0, 100)]
    [int]$PendingPercent = 5,

    [ValidateRange(0, 100)]
    [int]$DeadPercent = 5,

    [switch]$ConfirmTarget
)

$ErrorActionPreference = "Stop"

if ($PendingPercent + $DeadPercent -gt 100) {
    throw "PendingPercent + DeadPercent must not exceed 100."
}

$contextArgs = @()
if (-not [string]::IsNullOrWhiteSpace($Context)) {
    $contextArgs = @("--context", $Context)
    $targetContext = $Context
}
else {
    $targetContext = (kubectl config current-context).Trim()
    if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($targetContext)) {
        throw "kubectl has no current context. Supply a kubeconfig/context first."
    }
}

$server = (
    kubectl @contextArgs config view --minify `
        -o "jsonpath={.clusters[0].cluster.server}"
).Trim()
if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($server)) {
    throw "Unable to resolve the Kubernetes API server for context '$targetContext'."
}

Write-Host "Kubernetes context : $targetContext"
Write-Host "API server         : $server"
Write-Host "Target             : $Namespace/$MasterPod ($Container)"
Write-Host "Database           : $Database"
Write-Host "Rows               : $TotalRows"
Write-Host "Rows per exec       : $ExecutionChunkRows"

if (-not $ConfirmTarget) {
    throw "Target not confirmed. Review the values above and rerun with -ConfirmTarget."
}

$phase = (
    kubectl @contextArgs get pod `
        --namespace $Namespace `
        $MasterPod `
        -o "jsonpath={.status.phase}"
).Trim()
if ($LASTEXITCODE -ne 0 -or $phase -ne "Running") {
    throw "Target pod is not Running: $Namespace/$MasterPod (phase='$phase')."
}

$remoteMysql = 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql' +
    ' --user=root' +
    " --database=$Database" +
    ' --default-character-set=utf8mb4' +
    ' --batch --skip-column-names'

$tableCheckSql = @"
SELECT COUNT(*)
FROM information_schema.tables
WHERE table_schema = '$Database'
  AND table_name = 'task_info';
"@
$tableCount = (
    $tableCheckSql |
        kubectl @contextArgs exec -i `
            --namespace $Namespace `
            $MasterPod `
            --container $Container `
            -- sh -c $remoteMysql
).Trim()
if ($LASTEXITCODE -ne 0 -or $tableCount -ne "1") {
    throw "Required table $Database.task_info was not found on the target master."
}

Write-Host "MySQL data filesystem:"
kubectl @contextArgs exec `
    --namespace $Namespace `
    $MasterPod `
    --container $Container `
    -- df -h /var/lib/mysql
if ($LASTEXITCODE -ne 0) {
    throw "Unable to inspect MySQL filesystem capacity."
}

$resumeSql = @"
SELECT
    COUNT(*) AS seeded_rows,
    COALESCE(MAX(id) - 7000000000000000000 + 1, 0) AS contiguous_span
FROM task_info
WHERE id >= 7000000000000000000
  AND id < 7000000000100000000;
"@
$resumeOutput = @(
    $resumeSql |
        kubectl @contextArgs exec -i `
            --namespace $Namespace `
            $MasterPod `
            --container $Container `
            -- sh -c $remoteMysql
)
if ($LASTEXITCODE -ne 0 -or $resumeOutput.Count -eq 0) {
    throw "Unable to inspect the existing seed range."
}
$resumeParts = ($resumeOutput | Select-Object -Last 1).Trim() -split "\s+"
$seededRows = [long]$resumeParts[0]
$contiguousSpan = [long]$resumeParts[1]
$resumeOffset = 0L
if ($seededRows -eq $contiguousSpan) {
    $resumeOffset = [Math]::Min($seededRows, $TotalRows)
    Write-Host "Existing contiguous seed prefix: $seededRows rows; resuming at $resumeOffset."
}
else {
    Write-Warning "Seed IDs contain gaps; scanning from row zero so INSERT IGNORE can repair them."
}

$procedurePath = Join-Path $PSScriptRoot "seed-procedure.sql"
$procedureSql = Get-Content -Raw -Encoding utf8 -LiteralPath $procedurePath
Write-Host "Installing the seed procedure on the Kubernetes MySQL master."
$procedureSql |
    kubectl @contextArgs exec -i `
        --namespace $Namespace `
        $MasterPod `
        --container $Container `
        -- sh -c $remoteMysql

if ($LASTEXITCODE -ne 0) {
    throw "Failed to install the Kubernetes MySQL seed procedure."
}

$offset = $resumeOffset
while ($offset -lt $TotalRows) {
    $chunkRows = [Math]::Min([long]$ExecutionChunkRows, $TotalRows - $offset)
    $chunkEnd = $offset + $chunkRows
    $chunkSql = @"
CALL relayq_seed_tasks(
    $offset,
    $chunkRows,
    $BatchSize,
    $PendingPercent,
    $DeadPercent
);
"@

    Write-Host "Writing row range [$offset, $chunkEnd)."
    $chunkSql |
        kubectl @contextArgs exec -i `
            --namespace $Namespace `
            $MasterPod `
            --container $Container `
            -- sh -c $remoteMysql

    if ($LASTEXITCODE -ne 0) {
        throw "Kubernetes MySQL seed chunk [$offset, $chunkEnd) failed with exit code $LASTEXITCODE. Rerunning the script is safe."
    }
    $offset = $chunkEnd
}

$finishSql = @"
DROP PROCEDURE IF EXISTS relayq_seed_tasks;
SELECT status, COUNT(*) AS row_count
FROM task_info
WHERE id >= 7000000000000000000
  AND id < 7000000000100000000
GROUP BY status
ORDER BY status;
"@
$finishSql |
    kubectl @contextArgs exec -i `
        --namespace $Namespace `
        $MasterPod `
        --container $Container `
        -- sh -c $remoteMysql
if ($LASTEXITCODE -ne 0) {
    throw "Failed to read the final seed summary."
}
