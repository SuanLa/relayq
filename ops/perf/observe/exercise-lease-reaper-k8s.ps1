[CmdletBinding()]
param(
    [string]$Context = "",

    [Parameter(Mandatory = $true)]
    [ValidatePattern("^reaperprobe-[A-Za-z0-9.-]+$")]
    [string]$RunId,

    [ValidateNotNullOrEmpty()]
    [string]$BaseUrl = "http://127.0.0.1:18080",

    [ValidateRange(1, 500)]
    [int]$TaskCount = 64,

    [ValidateRange(500, 10000)]
    [int]$SleepMillis = 10000,

    [ValidateRange(5, 120)]
    [int]$ForceDurationSeconds = 15,

    [ValidateRange(100, 5000)]
    [int]$ForceIntervalMilliseconds = 250,

    [ValidatePattern("^[a-z0-9]([-a-z0-9]*[a-z0-9])?$")]
    [string]$ApplicationNamespace = "relayq",

    [ValidatePattern("^[a-z0-9]([-a-z0-9]*[a-z0-9])?$")]
    [string]$ApplicationDeployment = "relayq-app",

    [ValidatePattern("^[a-z0-9]([-a-z0-9]*[a-z0-9])?$")]
    [string]$MysqlNamespace = "mysql",

    [string]$MysqlMasterPod = "mysql-master-0",

    [string]$MysqlContainer = "mysql",

    [ValidatePattern("^[A-Za-z0-9_]+$")]
    [string]$Database = "relayq",

    [switch]$ConfirmTarget
)

$ErrorActionPreference = "Stop"

try {
    Add-Type -AssemblyName System.Net.Http -ErrorAction Stop
}
catch {
    throw "Required .NET assembly System.Net.Http could not be loaded: $($_.Exception.Message)"
}

$contextArgs = @()
if (-not [string]::IsNullOrWhiteSpace($Context)) {
    $contextArgs = @("--context", $Context)
}

function Invoke-KubectlText {
    param(
        [Parameter(Mandatory = $true)]
        [string[]]$Arguments,

        [Parameter(Mandatory = $true)]
        [string]$Description
    )

    $previousErrorActionPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = "Continue"
        $output = @(& kubectl @contextArgs @Arguments 2>&1)
        $exitCode = $LASTEXITCODE
    }
    finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }
    if ($exitCode -ne 0) {
        throw "$Description failed with exit code ${exitCode}: $($output -join [Environment]::NewLine)"
    }
    return ($output -join "`n").Trim()
}

function Invoke-MysqlQuery {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Sql,

        [Parameter(Mandatory = $true)]
        [string]$Description
    )

    # Base64 avoids Windows PowerShell losing or reinterpreting kubectl stdin.
    $encodedSql = [Convert]::ToBase64String(
        [Text.Encoding]::UTF8.GetBytes($Sql)
    )
    $remoteMysql = (
        'printf "%s" "{0}" | base64 -d | ' +
        'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql' +
        ' --user=root --batch --skip-column-names'
    ) -f $encodedSql
    $previousErrorActionPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = "Continue"
        $output = @(
            & kubectl @contextArgs exec `
                --namespace $MysqlNamespace `
                $MysqlMasterPod `
                --container $MysqlContainer `
                -- sh -c $remoteMysql 2>&1
        )
        $exitCode = $LASTEXITCODE
    }
    finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }
    if ($exitCode -ne 0) {
        throw "$Description failed with exit code ${exitCode}: $($output -join [Environment]::NewLine)"
    }
    return ($output -join "`n").Trim()
}

function Convert-ToLong {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Value,

        [Parameter(Mandatory = $true)]
        [string]$Description
    )

    $parsed = 0L
    if (-not [long]::TryParse($Value.Trim(), [ref]$parsed)) {
        throw "Unexpected $Description result: $Value"
    }
    return $parsed
}

function Get-InnodbDeadlockCount {
    $value = Invoke-MysqlQuery `
        -Sql (
            "SELECT COALESCE(" +
            "(SELECT VARIABLE_VALUE FROM performance_schema.global_status " +
            "WHERE VARIABLE_NAME = 'INNODB_DEADLOCKS')," +
            "(SELECT COUNT FROM information_schema.INNODB_METRICS " +
            "WHERE NAME = 'lock_deadlocks'),0);"
        ) `
        -Description "InnoDB deadlock counter query"
    return Convert-ToLong -Value $value -Description "InnoDB deadlock counter"
}

function Get-RunningTaskIds {
    $rows = Invoke-MysqlQuery `
        -Sql @"
SELECT id
FROM ``$Database``.task_info FORCE INDEX (uk_biz_key)
WHERE biz_key LIKE '$RunId-%'
  AND handler_name = 'slow-handler'
  AND status = 'RUNNING'
ORDER BY id
LIMIT $TaskCount;
"@ `
        -Description "running probe task query"
    if ([string]::IsNullOrWhiteSpace($rows)) {
        return @()
    }

    $ids = @()
    foreach ($row in ($rows -split "`n")) {
        $ids += Convert-ToLong -Value $row -Description "task id"
    }
    return @($ids | Sort-Object)
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
$apiServer = Invoke-KubectlText `
    -Arguments @("config", "view", "--minify", "-o", "jsonpath={.clusters[0].cluster.server}") `
    -Description "Kubernetes API server lookup"
$image = Invoke-KubectlText `
    -Arguments @(
        "get", "deployment", $ApplicationDeployment,
        "--namespace", $ApplicationNamespace,
        "-o", "jsonpath={.spec.template.spec.containers[0].image}"
    ) `
    -Description "application image lookup"

$BaseUrl = $BaseUrl.TrimEnd("/")
$runPattern = "$RunId-%"
$existing = Convert-ToLong `
    -Value (Invoke-MysqlQuery `
        -Sql @"
SELECT COUNT(*)
FROM ``$Database``.task_info FORCE INDEX (uk_biz_key)
WHERE biz_key LIKE '$runPattern';
"@ `
        -Description "existing probe task check") `
    -Description "existing probe task count"
if ($existing -ne 0L) {
    throw "RUN_ID '$RunId' already matches $existing task(s); choose a new RUN_ID."
}

Write-Host "Kubernetes context : $targetContext"
Write-Host "API server         : $apiServer"
Write-Host "Application image  : $image"
Write-Host "Application URL    : $BaseUrl"
Write-Host "MySQL target       : $MysqlNamespace/$MysqlMasterPod ($Database)"
Write-Host "RUN_ID             : $RunId"
Write-Host "Probe tasks        : $TaskCount x ${SleepMillis}ms"
Write-Host "Lease forcing      : ${ForceDurationSeconds}s every ${ForceIntervalMilliseconds}ms"
if (-not $ConfirmTarget) {
    throw "This test intentionally expires leases. Review the target above, then re-run with -ConfirmTarget."
}

$httpHandler = [System.Net.Http.HttpClientHandler]::new()
$httpHandler.UseProxy = $false
$httpClient = [System.Net.Http.HttpClient]::new($httpHandler)
$httpClient.Timeout = [TimeSpan]::FromSeconds(15)
try {
    $health = $httpClient.GetAsync("$BaseUrl/actuator/health").GetAwaiter().GetResult()
    if (-not $health.IsSuccessStatusCode) {
        throw "Application health check returned HTTP $([int]$health.StatusCode)."
    }

    Write-Host "Submitting scoped slow-handler tasks."
    for ($index = 0; $index -lt $TaskCount; $index++) {
        $body = @{
            biz_key = "$RunId-$index"
            handler_name = "slow-handler"
            params = @{sleep_millis = $SleepMillis}
            max_retry = 0
        } | ConvertTo-Json -Depth 5 -Compress
        $content = [System.Net.Http.StringContent]::new(
            $body,
            [Text.Encoding]::UTF8,
            "application/json"
        )
        try {
            $response = $httpClient.PostAsync("$BaseUrl/api/tasks", $content).GetAwaiter().GetResult()
            if ([int]$response.StatusCode -ne 201) {
                $responseBody = $response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
                throw "Task $index returned HTTP $([int]$response.StatusCode): $responseBody"
            }
        }
        finally {
            $content.Dispose()
        }
    }
}
finally {
    $httpClient.Dispose()
    $httpHandler.Dispose()
}

$waitDeadline = (Get-Date).AddSeconds(15)
$runningIds = @()
while ((Get-Date) -lt $waitDeadline) {
    $runningIds = @(Get-RunningTaskIds)
    if ($runningIds.Count -gt 0) {
        break
    }
    Start-Sleep -Milliseconds 100
}
if ($runningIds.Count -eq 0) {
    throw "No scoped task entered RUNNING within 15 seconds."
}

$initialDeadlocks = Get-InnodbDeadlockCount
$forcedRows = 0L
$forceDeadline = (Get-Date).AddSeconds($ForceDurationSeconds)
Write-Host "Forcing only scoped RUNNING leases to expire."
while ((Get-Date) -lt $forceDeadline) {
    $runningIds = @(Get-RunningTaskIds)
    if ($runningIds.Count -gt 0) {
        $idList = $runningIds -join ","
        $affected = Convert-ToLong `
            -Value (Invoke-MysqlQuery `
                -Sql @"
UPDATE ``$Database``.task_info FORCE INDEX (PRIMARY)
SET lease_expire_time = TIMESTAMPADD(SECOND, -1, NOW(3))
WHERE id IN ($idList)
  AND biz_key LIKE '$runPattern'
  AND handler_name = 'slow-handler'
  AND status = 'RUNNING';
SELECT ROW_COUNT();
"@ `
                -Description "scoped lease expiration update") `
            -Description "lease expiration affected-row count"
        $forcedRows += $affected
    }
    Start-Sleep -Milliseconds $ForceIntervalMilliseconds
}

Start-Sleep -Seconds 2
$finalDeadlocks = Get-InnodbDeadlockCount
$deadlockDelta = $finalDeadlocks - $initialDeadlocks
$reaperTransitions = Convert-ToLong `
    -Value (Invoke-MysqlQuery `
        -Sql @"
SELECT COALESCE(SUM(
    current_attempt_no > 1
    OR (
        status = 'PENDING'
        AND current_attempt_no >= 1
        AND lease_owner IS NULL
    )
), 0)
FROM ``$Database``.task_info FORCE INDEX (uk_biz_key)
WHERE biz_key LIKE '$runPattern'
  AND handler_name = 'slow-handler';
"@ `
        -Description "reaper transition evidence query") `
    -Description "reaper transition evidence count"
$summary = Invoke-MysqlQuery `
    -Sql @"
SELECT status, COUNT(*), MIN(current_attempt_no), MAX(current_attempt_no)
FROM ``$Database``.task_info FORCE INDEX (uk_biz_key)
WHERE biz_key LIKE '$runPattern'
GROUP BY status
ORDER BY status;
"@ `
    -Description "probe task summary"

Write-Host ""
Write-Host "PROBE SUMMARY"
Write-Host "Forced lease rows  : $forcedRows"
Write-Host "Reaper transitions : $reaperTransitions"
Write-Host "Initial deadlocks  : $initialDeadlocks"
Write-Host "Final deadlocks    : $finalDeadlocks"
Write-Host "Deadlock delta     : $deadlockDelta"
Write-Host "status / count / min attempt / max attempt"
Write-Host $summary

if ($forcedRows -eq 0L) {
    throw "The probe did not force any RUNNING lease to expire."
}
if ($reaperTransitions -eq 0L) {
    throw "No scoped task shows evidence that the reaper path completed."
}
if ($deadlockDelta -ne 0L) {
    throw "The probe observed $deadlockDelta new InnoDB deadlock(s)."
}

Write-Host "VERDICT: PASSED - the reaper path was exercised without a new InnoDB deadlock."
