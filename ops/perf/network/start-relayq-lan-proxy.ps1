[CmdletBinding()]
param(
    [ValidatePattern("^[A-Za-z0-9][A-Za-z0-9_.-]*$")]
    [string]$NodeContainer = "desktop-control-plane",

    [ValidatePattern("^[A-Za-z0-9][A-Za-z0-9_.-]*$")]
    [string]$ProxyContainer = "relayq-lan-proxy",

    [string]$DockerNetwork = "",

    [ValidateRange(1, 65535)]
    [int]$NodePort = 30080,

    [ValidateRange(1, 65535)]
    [int]$HostPort = 18080,

    [ValidatePattern("^(0\.0\.0\.0|(?:\d{1,3}\.){3}\d{1,3})$")]
    [string]$ListenAddress = "0.0.0.0",

    [ValidatePattern("^[A-Za-z0-9][A-Za-z0-9._/-]*(?::[A-Za-z0-9._-]+)?$")]
    [string]$Image = "nginx:alpine",

    [ValidatePattern("^/.*$")]
    [string]$HealthPath = "/actuator/health",

    [ValidateSet("Private", "Domain", "Any")]
    [string]$FirewallProfile = "Private",

    [switch]$OpenFirewall
)

$ErrorActionPreference = "Stop"
$templatePath = Join-Path $PSScriptRoot "relayq-lan-proxy.conf.template"
$firewallRuleName = "RelayQ LAN proxy TCP $HostPort"

function Invoke-DockerCommand {
    param(
        [Parameter(Mandatory = $true)]
        [string[]]$Arguments,

        [switch]$Capture
    )

    if ($Capture) {
        $output = & docker @Arguments 2>&1
        if ($LASTEXITCODE -ne 0) {
            throw "docker $($Arguments -join ' ') failed:`n$($output -join "`n")"
        }
        return $output
    }

    & docker @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "docker $($Arguments -join ' ') failed with exit code $LASTEXITCODE."
    }
}

if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
    throw "docker was not found in PATH. Run this script on the Docker Desktop Kubernetes machine."
}

if (-not (Test-Path -LiteralPath $templatePath -PathType Leaf)) {
    throw "Nginx template does not exist: $templatePath"
}

$nodeJson = Invoke-DockerCommand `
    -Arguments @("container", "inspect", $NodeContainer) `
    -Capture
$node = $nodeJson | ConvertFrom-Json
if (-not $node[0].State.Running) {
    throw "Docker Desktop Kubernetes node container is not running: $NodeContainer"
}

$networkNames = @(
    $node[0].NetworkSettings.Networks.PSObject.Properties |
        ForEach-Object { $_.Name }
)
if ($networkNames.Count -eq 0) {
    throw "Node container '$NodeContainer' is not attached to a Docker network."
}

if ([string]::IsNullOrWhiteSpace($DockerNetwork)) {
    if ($networkNames -contains "kind") {
        $DockerNetwork = "kind"
    }
    elseif ($networkNames.Count -eq 1) {
        $DockerNetwork = $networkNames[0]
    }
    else {
        throw "Node container has multiple Docker networks ($($networkNames -join ', ')). Supply -DockerNetwork explicitly."
    }
}
elseif ($networkNames -notcontains $DockerNetwork) {
    throw "Node container '$NodeContainer' is not attached to Docker network '$DockerNetwork'. Available networks: $($networkNames -join ', ')."
}

$imageIds = Invoke-DockerCommand `
    -Arguments @("image", "ls", "--quiet", "--no-trunc", $Image) `
    -Capture
if ([string]::IsNullOrWhiteSpace(($imageIds -join ""))) {
    Write-Host "Pulling proxy image: $Image"
    Invoke-DockerCommand -Arguments @("pull", $Image)
}

if ($OpenFirewall) {
    if (-not $IsWindows -and $PSVersionTable.PSEdition -eq "Core") {
        throw "-OpenFirewall is supported only on Windows."
    }

    $identity = [Security.Principal.WindowsIdentity]::GetCurrent()
    $principal = New-Object Security.Principal.WindowsPrincipal($identity)
    $isAdministrator = $principal.IsInRole(
        [Security.Principal.WindowsBuiltInRole]::Administrator
    )
    if (-not $isAdministrator) {
        throw "-OpenFirewall requires an Administrator PowerShell window."
    }

    $existingRule = Get-NetFirewallRule `
        -DisplayName $firewallRuleName `
        -ErrorAction SilentlyContinue
    if (-not $existingRule) {
        New-NetFirewallRule `
            -DisplayName $firewallRuleName `
            -Direction Inbound `
            -Action Allow `
            -Protocol TCP `
            -LocalPort $HostPort `
            -Profile $FirewallProfile |
            Out-Null
        Write-Host "Created Windows Firewall rule: $firewallRuleName ($FirewallProfile)"
    }
    else {
        Write-Host "Windows Firewall rule already exists: $firewallRuleName"
    }
}

$existingContainerIds = Invoke-DockerCommand `
    -Arguments @(
        "container", "ls", "--all", "--quiet",
        "--filter", "name=^/$ProxyContainer$"
    ) `
    -Capture
if (-not [string]::IsNullOrWhiteSpace(($existingContainerIds -join ""))) {
    Write-Host "Recreating existing proxy container: $ProxyContainer"
    Invoke-DockerCommand -Arguments @("container", "rm", "--force", $ProxyContainer)
}

$publish = "${ListenAddress}:${HostPort}:8080"
$mount = "type=bind,source=$templatePath,target=/etc/nginx/templates/default.conf.template,readonly"

Write-Host "Kubernetes node   : $NodeContainer"
Write-Host "Docker network    : $DockerNetwork"
Write-Host "NodePort backend  : ${NodeContainer}:$NodePort"
Write-Host "Windows listener  : ${ListenAddress}:$HostPort"
Write-Host "Proxy container   : $ProxyContainer"

$containerId = Invoke-DockerCommand `
    -Arguments @(
        "run", "--detach",
        "--name", $ProxyContainer,
        "--restart", "unless-stopped",
        "--network", $DockerNetwork,
        "--publish", $publish,
        "--env", "RELAYQ_NODE_CONTAINER=$NodeContainer",
        "--env", "RELAYQ_NODE_PORT=$NodePort",
        "--mount", $mount,
        $Image
    ) `
    -Capture
Write-Host "Started proxy container: $($containerId -join '')"

$healthHost = if ($ListenAddress -eq "0.0.0.0") {
    "127.0.0.1"
}
else {
    $ListenAddress
}
$healthUri = "http://${healthHost}:${HostPort}${HealthPath}"
$deadline = (Get-Date).AddSeconds(30)
$lastError = ""

do {
    try {
        $response = Invoke-WebRequest `
            -Uri $healthUri `
            -UseBasicParsing `
            -TimeoutSec 3
        if ($response.StatusCode -eq 200) {
            Write-Host "RelayQ LAN proxy is ready: $healthUri"
            Write-Host "Development-machine URL: http://<K8S_WINDOWS_LAN_IP>:$HostPort"
            return
        }
        $lastError = "HTTP $($response.StatusCode)"
    }
    catch {
        $lastError = $_.Exception.Message
    }
    Start-Sleep -Seconds 1
} while ((Get-Date) -lt $deadline)

Write-Host "Proxy container logs:"
& docker logs --tail 100 $ProxyContainer
throw "Proxy started but RelayQ health check did not pass within 30 seconds: $healthUri ($lastError)"
