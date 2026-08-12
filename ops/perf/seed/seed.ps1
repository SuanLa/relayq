[CmdletBinding()]
param(
    [ValidateRange(1, 100000000)]
    [long]$TotalRows = 10000000,

    [ValidateRange(1, 100000)]
    [int]$BatchSize = 10000,

    [ValidateRange(0, 100)]
    [int]$PendingPercent = 5,

    [ValidateRange(0, 100)]
    [int]$DeadPercent = 5
)

$ErrorActionPreference = "Stop"

if ($PendingPercent + $DeadPercent -gt 100) {
    throw "PendingPercent + DeadPercent must not exceed 100."
}

$projectRoot = Resolve-Path (Join-Path $PSScriptRoot "..\..\..")
$procedurePath = Join-Path $PSScriptRoot "seed-procedure.sql"
$procedureSql = Get-Content -Raw -Encoding utf8 -LiteralPath $procedurePath
$callSql = @"
$procedureSql
CALL relayq_seed_tasks(
    0,
    $TotalRows,
    $BatchSize,
    $PendingPercent,
    $DeadPercent
);
DROP PROCEDURE IF EXISTS relayq_seed_tasks;

SELECT status, COUNT(*) AS row_count
FROM task_info
WHERE id >= 7000000000000000000
  AND id < 7000000000100000000
GROUP BY status
ORDER BY status;
"@

Write-Host "Seeding $TotalRows rows into mysql-master in batches of $BatchSize."
Write-Host "Rows are idempotent and use IDs starting at 7000000000000000000."

Push-Location $projectRoot
try {
    $callSql |
        docker compose exec -T `
            -e MYSQL_PWD=root123456 `
            mysql-master `
            mysql `
            --user=root `
            --database=relayq `
            --default-character-set=utf8mb4

    if ($LASTEXITCODE -ne 0) {
        throw "MySQL seed command failed with exit code $LASTEXITCODE."
    }
}
finally {
    Pop-Location
}
