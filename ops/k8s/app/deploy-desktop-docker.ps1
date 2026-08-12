[CmdletBinding()]
param(
    [string]$Context = "",

    [ValidatePattern("^[a-z0-9]([-a-z0-9]*[a-z0-9])?$")]
    [string]$Namespace = "relayq",

    [ValidatePattern("^[a-z0-9]([-a-z0-9]*[a-z0-9])?$")]
    [string]$Deployment = "relayq-app",

    [ValidatePattern("^[a-z0-9]([-a-z0-9]*[a-z0-9])?$")]
    [string]$Container = "relayq-app",

    [ValidatePattern("^[A-Za-z0-9_.-]+$")]
    [string]$NodeContainer = "desktop-control-plane",

    [ValidatePattern("^[a-z0-9][a-z0-9._/-]*$")]
    [string]$ImageRepository = "docker.io/library/relayq-example",

    [string]$Tag = "",

    [ValidateRange(60, 3600)]
    [int]$TimeoutSeconds = 600,

    [switch]$NoCache,

    [switch]$ConfirmTarget
)

$ErrorActionPreference = "Stop"

function Invoke-CheckedCommand {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Description,

        [Parameter(Mandatory = $true)]
        [scriptblock]$Command
    )

    & $Command
    if ($LASTEXITCODE -ne 0) {
        throw "$Description failed with exit code $LASTEXITCODE."
    }
}

function Invoke-NativeCapture {
    param(
        [Parameter(Mandatory = $true)]
        [scriptblock]$Command
    )

    $previousErrorActionPreference = $ErrorActionPreference
    try {
        # Windows PowerShell can promote native stderr to NativeCommandError when
        # ErrorActionPreference is Stop. Probe commands must return their exit code
        # so this script can provide a useful diagnostic instead.
        $ErrorActionPreference = "Continue"
        $output = @(& $Command 2>$null)
        $exitCode = $LASTEXITCODE
    }
    finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }

    return [pscustomobject]@{
        Output = @($output)
        ExitCode = $exitCode
    }
}

foreach ($commandName in @("docker", "kubectl")) {
    if (-not (Get-Command $commandName -ErrorAction SilentlyContinue)) {
        throw "Required command was not found in PATH: $commandName"
    }
}

if ([string]::IsNullOrWhiteSpace($Tag)) {
    $timestamp = Get-Date -Format "yyyyMMdd-HHmmss"
    $suffix = [Guid]::NewGuid().ToString("N").Substring(0, 8)
    $Tag = "dev-$timestamp-$suffix"
}
if ($Tag -notmatch "^[A-Za-z0-9_][A-Za-z0-9_.-]{0,127}$") {
    throw "Invalid Docker image tag: $Tag"
}

$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..\..")).Path
$dockerfile = Join-Path $projectRoot "relayq-example\Dockerfile"
$imageReference = "${ImageRepository}:$Tag"

$contextListResult = Invoke-NativeCapture {
    kubectl config get-contexts -o name
}
$availableContexts = @(
    $contextListResult.Output |
        ForEach-Object { $_.Trim() } |
        Where-Object { -not [string]::IsNullOrWhiteSpace($_) }
)

if ([string]::IsNullOrWhiteSpace($Context)) {
    $currentContextResult = Invoke-NativeCapture {
        kubectl config current-context
    }
    $targetContext = ($currentContextResult.Output -join "").Trim()
    if ($currentContextResult.ExitCode -ne 0 -or [string]::IsNullOrWhiteSpace($targetContext)) {
        $availableText = if ($availableContexts.Count -gt 0) {
            $availableContexts -join ", "
        }
        else {
            "none"
        }
        throw "kubectl has no current context. This script must run on the Windows machine hosting Docker Desktop Kubernetes. Available contexts: $availableText"
    }
}
else {
    $targetContext = $Context.Trim()
    if ($contextListResult.ExitCode -ne 0 -or $availableContexts -notcontains $targetContext) {
        $availableText = if ($availableContexts.Count -gt 0) {
            $availableContexts -join ", "
        }
        else {
            "none"
        }
        throw "Kubernetes context '$targetContext' was not found. Available contexts: $availableText"
    }
}
$contextArgs = @("--context", $targetContext)

$serverResult = Invoke-NativeCapture {
    kubectl @contextArgs config view --minify `
        -o "jsonpath={.clusters[0].cluster.server}"
}
$server = ($serverResult.Output -join "").Trim()
if ($serverResult.ExitCode -ne 0 -or [string]::IsNullOrWhiteSpace($server)) {
    throw "Unable to resolve the Kubernetes API server for context '$targetContext'."
}

$nodeIdResult = Invoke-NativeCapture {
    docker inspect --format "{{.Id}}" $NodeContainer
}
$nodeId = ($nodeIdResult.Output -join "").Trim()
if ($nodeIdResult.ExitCode -ne 0 -or [string]::IsNullOrWhiteSpace($nodeId)) {
    throw "Docker Desktop Kubernetes node container was not found: $NodeContainer"
}
$nodeStatusResult = Invoke-NativeCapture {
    docker inspect --format "{{.State.Status}}" $nodeId
}
$nodeStatus = ($nodeStatusResult.Output -join "").Trim()
if ($nodeStatusResult.ExitCode -ne 0 -or $nodeStatus -ne "running") {
    throw "Docker Desktop Kubernetes node is not running: $NodeContainer (status='$nodeStatus')."
}

Write-Host "Project root       : $projectRoot"
Write-Host "Kubernetes context : $targetContext"
Write-Host "API server         : $server"
Write-Host "Deployment target  : $Namespace/$Deployment ($Container)"
Write-Host "Kubernetes node    : $NodeContainer"
Write-Host "Image              : $imageReference"

if (-not $ConfirmTarget) {
    throw "Target not confirmed. Review the values above and rerun with -ConfirmTarget."
}

kubectl @contextArgs get deployment $Deployment --namespace $Namespace *> $null
if ($LASTEXITCODE -ne 0) {
    throw "Deployment $Namespace/$Deployment does not exist. Apply ops/k8s/app/relayq-app.yaml first."
}

$tempDirectory = Join-Path `
    ([IO.Path]::GetTempPath()) `
    ("relayq-deploy-" + [Guid]::NewGuid().ToString("N"))
$archivePath = Join-Path $tempDirectory "relayq-image.tar"
$patchPath = Join-Path $tempDirectory "deployment-patch.json"
$remoteDirectory = "/var/tmp/relayq-import"
$safeTag = $Tag -replace "[^A-Za-z0-9_.-]", "-"
$remoteArchive = "$remoteDirectory/relayq-$safeTag.tar"
$remoteArchiveCopied = $false

New-Item -ItemType Directory -Path $tempDirectory | Out-Null

try {
    $buildArguments = @(
        "build",
        "--file", $dockerfile,
        "--tag", $imageReference
    )
    if ($NoCache) {
        $buildArguments += "--no-cache"
    }
    $buildArguments += $projectRoot

    Write-Host "`n[1/6] Building the application image."
    Invoke-CheckedCommand `
        -Description "Docker image build" `
        -Command { docker @buildArguments }

    Write-Host "`n[2/6] Exporting the image to a temporary archive."
    Invoke-CheckedCommand `
        -Description "Docker image export" `
        -Command { docker image save --output $archivePath $imageReference }

    Write-Host "`n[3/6] Copying the image into the Kubernetes node."
    Invoke-CheckedCommand `
        -Description "Kubernetes node import directory creation" `
        -Command { docker exec $nodeId mkdir -p $remoteDirectory }
    Invoke-CheckedCommand `
        -Description "Image copy to Kubernetes node" `
        -Command { docker cp $archivePath "$($nodeId):$remoteArchive" }
    $remoteArchiveCopied = $true

    Write-Host "`n[4/6] Importing the image into Kubernetes containerd."
    Invoke-CheckedCommand `
        -Description "containerd image import" `
        -Command { docker exec $nodeId ctr -n k8s.io images import $remoteArchive }
    Invoke-CheckedCommand `
        -Description "CRI image verification" `
        -Command { docker exec $nodeId crictl inspecti $imageReference *> $null }

    $patchDocument = @{
        spec = @{
            template = @{
                spec = @{
                    containers = @(
                        @{
                            name = $Container
                            image = $imageReference
                            imagePullPolicy = "Never"
                        }
                    )
                }
            }
        }
    } | ConvertTo-Json -Depth 10 -Compress
    $patchDocument | Set-Content `
        -LiteralPath $patchPath `
        -Encoding ASCII `
        -NoNewline

    Write-Host "`n[5/6] Updating the existing Kubernetes Deployment."
    Invoke-CheckedCommand `
        -Description "Kubernetes Deployment patch" `
        -Command {
            kubectl @contextArgs patch deployment $Deployment `
                --namespace $Namespace `
                --type strategic `
                --patch-file $patchPath
        }

    Write-Host "`n[6/6] Waiting for the rolling update to finish."
    kubectl @contextArgs rollout status deployment/$Deployment `
        --namespace $Namespace `
        --timeout "${TimeoutSeconds}s"
    if ($LASTEXITCODE -ne 0) {
        $rolloutExitCode = $LASTEXITCODE
        Write-Warning "Rollout did not complete. Collecting diagnostics."
        kubectl @contextArgs get pods `
            --namespace $Namespace `
            --selector "app=$Deployment" `
            -o wide
        kubectl @contextArgs describe deployment $Deployment `
            --namespace $Namespace
        kubectl @contextArgs get events `
            --namespace $Namespace `
            --sort-by ".metadata.creationTimestamp"
        throw "Kubernetes rollout failed with exit code $rolloutExitCode."
    }

    Write-Host "`nDeployment completed successfully."
    Write-Host "Running image: $imageReference"
    kubectl @contextArgs get pods `
        --namespace $Namespace `
        --selector "app=$Deployment" `
        -o wide
}
finally {
    if ($remoteArchiveCopied) {
        try {
            docker exec $nodeId rm -f -- $remoteArchive *> $null
            if ($LASTEXITCODE -ne 0) {
                Write-Warning "Unable to remove the temporary node archive: $remoteArchive"
            }
        }
        catch {
            Write-Warning "Unable to remove the temporary node archive: $remoteArchive"
        }
    }
    try {
        foreach ($temporaryFile in @($archivePath, $patchPath)) {
            if (Test-Path -LiteralPath $temporaryFile) {
                Remove-Item -LiteralPath $temporaryFile -Force
            }
        }
        if (Test-Path -LiteralPath $tempDirectory) {
            Remove-Item -LiteralPath $tempDirectory
        }
    }
    catch {
        Write-Warning "Unable to remove the local temporary directory: $tempDirectory"
    }
}
