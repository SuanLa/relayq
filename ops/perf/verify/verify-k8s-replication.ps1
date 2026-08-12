[CmdletBinding()]
param(
    [string]$Context = "",

    [ValidatePattern("^[a-z0-9]([-a-z0-9]*[a-z0-9])?$")]
    [string]$Namespace = "mysql",

    [string]$MasterPod = "mysql-master-0",

    [string]$SlavePod = "mysql-slave-0",

    [string]$Container = "mysql",

    [ValidatePattern("^[A-Za-z0-9_]+$")]
    [string]$Database = "relayq",

    [ValidateRange(1, 86400)]
    [int]$TimeoutSeconds = 7200
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
        [string]$Sql
    )

    $remoteMysql = 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql' +
        ' --user=root' +
        " --database=$Database" +
        ' --batch --skip-column-names'

    $output = $Sql |
        kubectl @contextArgs exec -i `
            --namespace $Namespace `
            $Pod `
            --container $Container `
            -- sh -c $remoteMysql

    if ($LASTEXITCODE -ne 0) {
        throw "MySQL command failed in $Namespace/$Pod."
    }
    return ($output -join "`n").Trim()
}

$gtid = Invoke-KubernetesMySql `
    -Pod $MasterPod `
    -Sql "SELECT @@GLOBAL.gtid_executed;"
if ([string]::IsNullOrWhiteSpace($gtid)) {
    throw "The master returned an empty GTID set."
}

Write-Host "Waiting for $Namespace/$SlavePod to execute the master's GTID set."
$waitResult = Invoke-KubernetesMySql `
    -Pod $SlavePod `
    -Sql "SELECT WAIT_FOR_EXECUTED_GTID_SET('$gtid', $TimeoutSeconds);"
if ($waitResult -ne "0") {
    throw "Replica did not catch up within $TimeoutSeconds seconds."
}

$summarySql = @"
SELECT status, COUNT(*)
FROM task_info
WHERE id >= 7000000000000000000
  AND id < 7000000000100000000
GROUP BY status
ORDER BY status;
"@

Write-Host "`nMASTER"
Write-Host (Invoke-KubernetesMySql -Pod $MasterPod -Sql $summarySql)

Write-Host "`nSLAVE"
Write-Host (Invoke-KubernetesMySql -Pod $SlavePod -Sql $summarySql)

Write-Host "`nREPLICATION"
$remoteStatusMysql = 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql' +
    ' --user=root' +
    " --database=$Database"
$replicaStatus = "SHOW REPLICA STATUS\G" |
    kubectl @contextArgs exec -i `
        --namespace $Namespace `
        $SlavePod `
        --container $Container `
        -- sh -c $remoteStatusMysql
if ($LASTEXITCODE -ne 0) {
    throw "Failed to read replication status from $Namespace/$SlavePod."
}
$replicaStatus -split "`n" |
    Select-String -Pattern `
        "Replica_IO_Running|Replica_SQL_Running|Slave_IO_Running|Slave_SQL_Running|Seconds_Behind_(Source|Master)|Last_IO_Error|Last_SQL_Error"
