[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$BaseUrls,

    [ValidateRange(1, 1000000)]
    [int]$Rate = 100,

    [ValidatePattern("^[1-9][0-9]*(s|m|h)$")]
    [string]$Duration = "5m",

    [ValidateRange(0, 1000000)]
    [int]$PreAllocatedVUs = 0,

    [ValidateRange(0, 1000000)]
    [int]$MaxVUs = 0,

    [ValidateRange(1, 3600000)]
    [int]$P99Millis = 1000,

    [ValidatePattern("^[A-Za-z0-9][A-Za-z0-9.-]*$")]
    [string]$RunId = "",

    [ValidatePattern("^[A-Za-z0-9][A-Za-z0-9._/-]*$")]
    [string]$Image = "grafana/k6:latest",

    [string]$ResultsDirectory = ""
)

$ErrorActionPreference = "Stop"

if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
    throw "Required command was not found in PATH: docker"
}

$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..\..")).Path
$loadScript = Join-Path $projectRoot "ops\perf\k6\submit-load.js"
if (-not (Test-Path -LiteralPath $loadScript -PathType Leaf)) {
    throw "k6 load script was not found: $loadScript"
}

if ($PreAllocatedVUs -eq 0) {
    $calculatedVUs = [long]$Rate * 2L
    if ($calculatedVUs -gt 1000000L) {
        throw "The default preallocated VU count exceeds 1000000; set PreAllocatedVUs explicitly."
    }
    $PreAllocatedVUs = [int]$calculatedVUs
}
if ($MaxVUs -eq 0) {
    # Preallocate the full ceiling so k6 does not add VUs during the measured run.
    $MaxVUs = $PreAllocatedVUs
}
if ($MaxVUs -lt $PreAllocatedVUs) {
    throw "MaxVUs must be greater than or equal to PreAllocatedVUs."
}

if ([string]::IsNullOrWhiteSpace($RunId)) {
    $RunId = "capacity$Rate-$(Get-Date -Format 'yyyyMMdd-HHmmss')"
}

$durationValue = [long]($Duration.Substring(0, $Duration.Length - 1))
$durationUnit = $Duration.Substring($Duration.Length - 1, 1)
$durationFactor = switch ($durationUnit) {
    "s" { 1L }
    "m" { 60L }
    "h" { 3600L }
    default { throw "Unsupported duration unit: $durationUnit" }
}
if ($durationValue -gt [long]::MaxValue / $durationFactor) {
    throw "Duration is too large: $Duration"
}
$durationSeconds = $durationValue * $durationFactor
if ($durationSeconds -gt [long]::MaxValue / $Rate) {
    throw "Rate and duration produce an iteration count larger than Int64."
}
$expectedIterations = [long]$Rate * $durationSeconds

if ([string]::IsNullOrWhiteSpace($ResultsDirectory)) {
    $ResultsDirectory = Join-Path $projectRoot "ops\perf\results"
}
else {
    $ResultsDirectory = [IO.Path]::GetFullPath($ResultsDirectory)
}
New-Item -ItemType Directory -Path $ResultsDirectory -Force | Out-Null

$metadataPath = Join-Path $ResultsDirectory "$RunId.json"
$logPath = Join-Path $ResultsDirectory "$RunId.log"
foreach ($path in @($metadataPath, $logPath)) {
    if (Test-Path -LiteralPath $path) {
        throw "Result file already exists for RUN_ID '$RunId': $path"
    }
}

$startedAt = (Get-Date).ToUniversalTime()
$metadata = [ordered]@{
    runId = $RunId
    startedAtUtc = $startedAt.ToString("o")
    endedAtUtc = $null
    baseUrls = $BaseUrls
    rate = $Rate
    duration = $Duration
    durationSeconds = $durationSeconds
    expectedIterations = $expectedIterations
    preAllocatedVUs = $PreAllocatedVUs
    maxVUs = $MaxVUs
    p99Millis = $P99Millis
    image = $Image
    exitCode = $null
}
$metadata | ConvertTo-Json -Depth 4 |
    Set-Content -LiteralPath $metadataPath -Encoding utf8

Write-Host "RUN_ID             : $RunId"
Write-Host "Started at (UTC)   : $($metadata.startedAtUtc)"
Write-Host "Target             : $BaseUrls"
Write-Host "Rate / duration    : $Rate QPS / $Duration"
Write-Host "Expected iterations: $expectedIterations"
Write-Host "VUs                : $PreAllocatedVUs preallocated / $MaxVUs max"
Write-Host "P99 threshold      : $P99Millis ms"
Write-Host "Metadata           : $metadataPath"
Write-Host "Console log        : $logPath"

$dockerArguments = @(
    "run", "--rm",
    "--volume", "${projectRoot}:/work",
    "--workdir", "/work",
    $Image,
    "run",
    "-e", "BASE_URLS=$BaseUrls",
    "-e", "RATE=$Rate",
    "-e", "DURATION=$Duration",
    "-e", "PRE_ALLOCATED_VUS=$PreAllocatedVUs",
    "-e", "MAX_VUS=$MaxVUs",
    "-e", "P99_MS=$P99Millis",
    "-e", "RUN_ID=$RunId",
    "/work/ops/perf/k6/submit-load.js"
)

$previousErrorActionPreference = $ErrorActionPreference
$exitCode = 1
try {
    # Windows PowerShell can promote native stderr to NativeCommandError.
    $ErrorActionPreference = "Continue"
    & docker @dockerArguments 2>&1 |
        Tee-Object -LiteralPath $logPath
    $exitCode = $LASTEXITCODE
}
finally {
    $ErrorActionPreference = $previousErrorActionPreference
    $metadata.endedAtUtc = (Get-Date).ToUniversalTime().ToString("o")
    $metadata.exitCode = $exitCode
    $metadata | ConvertTo-Json -Depth 4 |
        Set-Content -LiteralPath $metadataPath -Encoding utf8
}

Write-Host "`nEnded at (UTC)     : $($metadata.endedAtUtc)"
Write-Host "Docker/k6 exit code: $exitCode"
Write-Host "Verify this RUN_ID :"
Write-Host ".\ops\perf\verify\verify-load-run-k8s.ps1 -Context docker-desktop -RunId $RunId -ExpectedRows $expectedIterations"

if ($exitCode -ne 0) {
    throw "k6 thresholds failed or the Docker run exited with code $exitCode."
}
