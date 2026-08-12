[CmdletBinding()]
param(
    [ValidateRange(1, 86400)]
    [int]$TimeoutSeconds = 3600
)

$ErrorActionPreference = "Stop"
$projectRoot = Resolve-Path (Join-Path $PSScriptRoot "..\..\..")

function Invoke-RelayqMySql {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Service,

        [Parameter(Mandatory = $true)]
        [string]$Sql
    )

    $output = docker compose exec -T `
        -e MYSQL_PWD=root123456 `
        $Service `
        mysql `
        --user=root `
        --database=relayq `
        --batch `
        --skip-column-names `
        --execute=$Sql

    if ($LASTEXITCODE -ne 0) {
        throw "MySQL command failed for $Service with exit code $LASTEXITCODE."
    }
    return ($output -join "`n").Trim()
}

$summarySql = @"
SELECT status, COUNT(*)
FROM task_info
WHERE id >= 7000000000000000000
  AND id < 7000000000100000000
GROUP BY status
ORDER BY status;
SELECT
    table_name,
    table_rows,
    ROUND(data_length / 1024 / 1024, 1) AS data_mb,
    ROUND(index_length / 1024 / 1024, 1) AS index_mb
FROM information_schema.tables
WHERE table_schema = 'relayq'
ORDER BY table_name;
"@

Push-Location $projectRoot
try {
    $gtid = Invoke-RelayqMySql `
        -Service "mysql-master" `
        -Sql "SELECT @@GLOBAL.gtid_executed;"

    if ([string]::IsNullOrWhiteSpace($gtid)) {
        throw "The master returned an empty GTID set; replication is not ready."
    }

    Write-Host "Waiting for mysql-slave to execute the master's GTID set."
    $waitResult = Invoke-RelayqMySql `
        -Service "mysql-slave" `
        -Sql "SELECT WAIT_FOR_EXECUTED_GTID_SET('$gtid', $TimeoutSeconds);"

    if ($waitResult -ne "0") {
        throw "Replica did not catch up within $TimeoutSeconds seconds."
    }

    Write-Host "`nMASTER"
    Write-Host (Invoke-RelayqMySql -Service "mysql-master" -Sql $summarySql)

    Write-Host "`nSLAVE"
    Write-Host (Invoke-RelayqMySql -Service "mysql-slave" -Sql $summarySql)

    Write-Host "`nREPLICATION"
    $replicaStatus = docker compose exec -T `
        -e MYSQL_PWD=root123456 `
        mysql-slave `
        mysql `
        --user=root `
        --execute="SHOW REPLICA STATUS\G"

    if ($LASTEXITCODE -ne 0) {
        throw "Failed to read replica status."
    }
    $replicaStatus |
        Select-String -Pattern `
            "Replica_IO_Running|Replica_SQL_Running|Seconds_Behind_Source|Last_IO_Error|Last_SQL_Error"
}
finally {
    Pop-Location
}
