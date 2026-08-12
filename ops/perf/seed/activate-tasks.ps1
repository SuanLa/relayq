[CmdletBinding()]
param(
    [ValidateRange(1, 10000000)]
    [int]$Count = 100000,

    [ValidateRange(1, 100000)]
    [int]$BatchSize = 10000
)

$ErrorActionPreference = "Stop"
$projectRoot = Resolve-Path (Join-Path $PSScriptRoot "..\..\..")
$remaining = $Count
$activated = 0

Push-Location $projectRoot
try {
    while ($remaining -gt 0) {
        $currentBatch = [Math]::Min($remaining, $BatchSize)
        $sql = @"
UPDATE task_info
SET scheduled_time = NOW(3),
    updated_at = NOW(3)
WHERE id >= 7000000000000000000
  AND id < 7000000000100000000
  AND status = 'PENDING'
  AND scheduled_time > NOW(3)
ORDER BY id
LIMIT $currentBatch;
SELECT ROW_COUNT();
"@

        $output = docker compose exec -T `
            -e MYSQL_PWD=root123456 `
            mysql-master `
            mysql `
            --user=root `
            --database=relayq `
            --batch `
            --skip-column-names `
            --execute=$sql

        if ($LASTEXITCODE -ne 0) {
            throw "Task activation failed with exit code $LASTEXITCODE."
        }

        $changed = [int](($output | Select-Object -Last 1).Trim())
        $activated += $changed
        $remaining -= $changed
        Write-Host "Activated $activated / $Count tasks."

        if ($changed -lt $currentBatch) {
            break
        }
    }
}
finally {
    Pop-Location
}

Write-Host "Activation completed. Activated rows: $activated."
