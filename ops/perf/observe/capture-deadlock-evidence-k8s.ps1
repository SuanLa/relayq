[CmdletBinding()]
param(
    [string]$Context = "",

    [Parameter(Mandatory = $true)]
    [ValidatePattern("^[A-Za-z0-9][A-Za-z0-9.-]*$")]
    [string]$RunId,

    [ValidateRange(10, 86400)]
    [int]$DurationSeconds = 480,

    [ValidateRange(1, 60)]
    [int]$PollSeconds = 5,

    [ValidatePattern("^[a-z0-9]([-a-z0-9]*[a-z0-9])?$")]
    [string]$ApplicationNamespace = "relayq",

    [ValidatePattern("^[a-z0-9]([-a-z0-9]*[a-z0-9])?$")]
    [string]$ApplicationDeployment = "relayq-app",

    [ValidatePattern("^[a-z0-9]([-a-z0-9]*[a-z0-9])?$")]
    [string]$ApplicationContainer = "relayq-app",

    [ValidatePattern("^[a-z0-9]([-a-z0-9]*[a-z0-9])?$")]
    [string]$MysqlNamespace = "mysql",

    [string]$MysqlMasterPod = "mysql-master-0",

    [string]$MysqlContainer = "mysql",

    [string]$ResultsDirectory = ""
)

$ErrorActionPreference = "Stop"
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
        # Windows PowerShell can promote native stderr to NativeCommandError.
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
    return ($output -join "`n")
}

function Invoke-MysqlQuery {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Sql,

        [Parameter(Mandatory = $true)]
        [string]$Description
    )

    # Do not stream SQL through kubectl stdin here. Windows PowerShell can
    # complete the native pipeline successfully while kubectl receives an
    # empty stdin stream. Base64 keeps the SQL intact across PowerShell,
    # kubectl, and the remote shell without adding SQL-specific escaping.
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
    return ($output -join "`n")
}

function Get-InnodbDeadlockCount {
    $row = Invoke-MysqlQuery `
        -Sql (
            "SELECT COALESCE(" +
            "(SELECT VARIABLE_VALUE FROM performance_schema.global_status " +
            "WHERE VARIABLE_NAME = 'INNODB_DEADLOCKS')," +
            "(SELECT COUNT FROM information_schema.INNODB_METRICS " +
            "WHERE NAME = 'lock_deadlocks'),0);"
        ) `
        -Description "InnoDB deadlock counter query"
    $value = $row.Trim()
    $deadlockCount = 0L
    if (-not [long]::TryParse($value, [ref]$deadlockCount)) {
        throw "Unexpected Innodb_deadlocks result: $row"
    }
    return $deadlockCount
}

function Save-InnodbStatus {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Path
    )

    Invoke-MysqlQuery `
        -Sql "SHOW ENGINE INNODB STATUS\G" `
        -Description "InnoDB status query" |
        Set-Content -LiteralPath $Path -Encoding utf8
}

function Save-KubectlOutput {
    param(
        [Parameter(Mandatory = $true)]
        [string[]]$Arguments,

        [Parameter(Mandatory = $true)]
        [string]$Description,

        [Parameter(Mandatory = $true)]
        [string]$Path
    )

    Invoke-KubectlText -Arguments $Arguments -Description $Description |
        Set-Content -LiteralPath $Path -Encoding utf8
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

if ([string]::IsNullOrWhiteSpace($ResultsDirectory)) {
    $projectRootCandidate = (Resolve-Path (Join-Path $PSScriptRoot "..\..\..")).Path
    $repositoryPerfDirectory = Join-Path $projectRootCandidate "ops\perf"
    if (Test-Path -LiteralPath $repositoryPerfDirectory -PathType Container) {
        $ResultsDirectory = Join-Path $repositoryPerfDirectory "results"
    }
    else {
        # Support copying this single script to a deployment-machine folder.
        $ResultsDirectory = Join-Path $PSScriptRoot "results"
    }
}
else {
    $ResultsDirectory = [IO.Path]::GetFullPath($ResultsDirectory)
}
$evidenceDirectory = Join-Path $ResultsDirectory "$RunId-evidence"
if (Test-Path -LiteralPath $evidenceDirectory) {
    throw "Evidence directory already exists for RUN_ID '$RunId': $evidenceDirectory"
}
New-Item -ItemType Directory -Path $evidenceDirectory -Force | Out-Null

$startedAt = (Get-Date).ToUniversalTime()
$endedAt = $null
$initialDeadlocks = Get-InnodbDeadlockCount
$lastDeadlocks = $initialDeadlocks
$observedDeadlockEvents = 0
$pollFailures = [System.Collections.Generic.List[string]]::new()

$metadata = [ordered]@{
    runId = $RunId
    context = $targetContext
    apiServer = $apiServer
    startedAtUtc = $startedAt.ToString("o")
    endedAtUtc = $null
    durationSeconds = $DurationSeconds
    pollSeconds = $PollSeconds
    initialInnodbDeadlocks = $initialDeadlocks
    finalInnodbDeadlocks = $null
    deadlockDelta = $null
    capturedDeadlockEvents = 0
    pollFailures = @()
}

$metadataPath = Join-Path $evidenceDirectory "metadata.json"
$counterPath = Join-Path $evidenceDirectory "innodb-deadlocks.csv"
$metadata | ConvertTo-Json -Depth 5 |
    Set-Content -LiteralPath $metadataPath -Encoding utf8
"timestamp_utc,innodb_deadlocks" |
    Set-Content -LiteralPath $counterPath -Encoding ascii
"$($startedAt.ToString('o')),$initialDeadlocks" |
    Add-Content -LiteralPath $counterPath -Encoding ascii

Save-InnodbStatus `
    -Path (Join-Path $evidenceDirectory "innodb-status-start.log")

Save-KubectlOutput `
    -Arguments @(
        "get", "deployment", $ApplicationDeployment,
        "--namespace", $ApplicationNamespace,
        "--output", "yaml"
    ) `
    -Description "Application Deployment snapshot" `
    -Path (Join-Path $evidenceDirectory "deployment-start.yaml")
Save-KubectlOutput `
    -Arguments @(
        "get", "replicaset",
        "--namespace", $ApplicationNamespace,
        "--selector", "app=$ApplicationDeployment",
        "--output", "wide"
    ) `
    -Description "Application ReplicaSet snapshot" `
    -Path (Join-Path $evidenceDirectory "replicasets-start.log")
Save-KubectlOutput `
    -Arguments @(
        "get", "pods",
        "--namespace", $ApplicationNamespace,
        "--selector", "app=$ApplicationDeployment",
        "--output", "json"
    ) `
    -Description "Application Pod snapshot" `
    -Path (Join-Path $evidenceDirectory "pods-start.json")

Write-Host "Kubernetes context : $targetContext"
Write-Host "API server         : $apiServer"
Write-Host "RUN_ID             : $RunId"
Write-Host "Observation window : $DurationSeconds seconds"
Write-Host "Poll interval      : $PollSeconds seconds"
Write-Host "Initial deadlocks  : $initialDeadlocks"
Write-Host "Evidence directory : $evidenceDirectory"
Write-Host "Start k6 now. This script only reads Kubernetes and MySQL state."

$deadline = $startedAt.AddSeconds($DurationSeconds)
try {
    while ((Get-Date).ToUniversalTime() -lt $deadline) {
        Start-Sleep -Seconds $PollSeconds
        try {
            $currentDeadlocks = Get-InnodbDeadlockCount
            $polledAt = (Get-Date).ToUniversalTime()
            "$($polledAt.ToString('o')),$currentDeadlocks" |
                Add-Content -LiteralPath $counterPath -Encoding ascii
            if ($currentDeadlocks -gt $lastDeadlocks) {
                $observedDeadlockEvents++
                $capturedAt = $polledAt
                $suffix = $capturedAt.ToString("yyyyMMdd-HHmmss.fff")
                $statusPath = Join-Path `
                    $evidenceDirectory `
                    "innodb-status-deadlock-$observedDeadlockEvents-$suffix.log"
                Save-InnodbStatus -Path $statusPath
                Write-Warning (
                    "InnoDB deadlocks increased from $lastDeadlocks to " +
                    "$currentDeadlocks; captured $statusPath")
            }
            $lastDeadlocks = $currentDeadlocks
        }
        catch {
            $failureAt = (Get-Date).ToUniversalTime().ToString("o")
            $failure = "$failureAt $($_.Exception.Message)"
            $pollFailures.Add($failure)
            Write-Warning $failure
        }
    }
}
finally {
    $endedAt = (Get-Date).ToUniversalTime()
    try {
        $lastDeadlocks = Get-InnodbDeadlockCount
    }
    catch {
        $failure = "$($endedAt.ToString('o')) final counter: $($_.Exception.Message)"
        $pollFailures.Add($failure)
        Write-Warning $failure
    }

    try {
        Save-InnodbStatus `
            -Path (Join-Path $evidenceDirectory "innodb-status-final.log")
    }
    catch {
        $failure = "$($endedAt.ToString('o')) final InnoDB status: $($_.Exception.Message)"
        $pollFailures.Add($failure)
        Write-Warning $failure
    }

    try {
        $podJson = Invoke-KubectlText `
            -Arguments @(
                "get", "pods",
                "--namespace", $ApplicationNamespace,
                "--selector", "app=$ApplicationDeployment",
                "--output", "json"
            ) `
            -Description "Final application Pod snapshot"
        $podJson |
            Set-Content `
                -LiteralPath (Join-Path $evidenceDirectory "pods-final.json") `
                -Encoding utf8
        $podObject = $podJson | ConvertFrom-Json
        foreach ($pod in @($podObject.items)) {
            $podName = $pod.metadata.name
            $logPath = Join-Path $evidenceDirectory "$podName.log"
            try {
                Save-KubectlOutput `
                    -Arguments @(
                        "logs", $podName,
                        "--namespace", $ApplicationNamespace,
                        "--container", $ApplicationContainer,
                        "--since-time", $startedAt.ToString("o"),
                        "--timestamps"
                    ) `
                    -Description "Application log collection for $podName" `
                    -Path $logPath
            }
            catch {
                $failure = "$($endedAt.ToString('o')) logs for ${podName}: $($_.Exception.Message)"
                $pollFailures.Add($failure)
                Write-Warning $failure
            }
        }
    }
    catch {
        $failure = "$($endedAt.ToString('o')) final Pod snapshot: $($_.Exception.Message)"
        $pollFailures.Add($failure)
        Write-Warning $failure
    }

    try {
        Save-KubectlOutput `
            -Arguments @(
                "get", "events",
                "--namespace", $ApplicationNamespace,
                "--sort-by=.metadata.creationTimestamp"
            ) `
            -Description "Application event collection" `
            -Path (Join-Path $evidenceDirectory "events-final.log")
    }
    catch {
        $failure = "$($endedAt.ToString('o')) events: $($_.Exception.Message)"
        $pollFailures.Add($failure)
        Write-Warning $failure
    }

    $metadata.endedAtUtc = $endedAt.ToString("o")
    $metadata.finalInnodbDeadlocks = $lastDeadlocks
    $metadata.deadlockDelta = $lastDeadlocks - $initialDeadlocks
    $metadata.capturedDeadlockEvents = $observedDeadlockEvents
    $metadata.pollFailures = @($pollFailures)
    $metadata | ConvertTo-Json -Depth 5 |
        Set-Content -LiteralPath $metadataPath -Encoding utf8
}

Write-Host "`nEVIDENCE CAPTURE COMPLETE"
[pscustomobject]@{
    StartedAtUtc = $metadata.startedAtUtc
    EndedAtUtc = $metadata.endedAtUtc
    InitialDeadlocks = $metadata.initialInnodbDeadlocks
    FinalDeadlocks = $metadata.finalInnodbDeadlocks
    DeadlockDelta = $metadata.deadlockDelta
    CapturedDeadlockEvents = $metadata.capturedDeadlockEvents
    PollFailures = @($metadata.pollFailures).Count
    Directory = $evidenceDirectory
} | Format-List | Out-Host

if ($metadata.deadlockDelta -gt 0) {
    Write-Warning "The run observed $($metadata.deadlockDelta) new InnoDB deadlock(s)."
}
if (@($metadata.pollFailures).Count -gt 0) {
    throw "Evidence capture completed with $(@($metadata.pollFailures).Count) collection failure(s)."
}
