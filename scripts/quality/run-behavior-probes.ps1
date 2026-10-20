<#
.SYNOPSIS
    ENF-1: "accepted implies enforced" -- boots NPDevSamples/dsl-conformance-max on a wiped database and
    runs every table-driven behaviour probe in its behavior-probes/ folder against the live REST API.

.DESCRIPTION
    check-dsl-coverage.py proves each DSL feature appears in some model; nothing proved the runtime
    OBEYS it. Nested onFailure and two-clause query filters were accepted and ignored for months. A
    probe is {request, expected status and body}; scripts/quality/behavior_probes/run_probes.py runs
    them and reports one result per probe, and scripts/policy/dsl-behavior-witness.json maps schema
    keywords to the probes that witness them (checked statically by check-dsl-behavior-witness.py).

    Boots a real app, so this is T1/T2 (`enf-behavior-probes` in verification-cadence.json), never the
    static aiKnowledge gate. Refuses to start when its port is taken and only ever stops processes it
    started.

.PARAMETER Port
    App port. Defaults to 8120 -- NOT the fixture's own 8099, which the shell-scenario harness owns.

.PARAMETER ReuseBuild
    Skip generate + build and boot what is already under the sample's Output\App.

.PARAMETER Explore
    Boot the app, print its URL and API key, then hold it up until the file named by -StopFile appears.
    For discovering real REST shapes while authoring probes; runs no probes.

.PARAMETER Only
    Run only probe files whose name contains this text.
#>
param(
    [string]$SampleId = "dsl-conformance-max",
    [int]$Port = 8120,
    [int]$BootTimeoutSeconds = 300,
    [switch]$ReuseBuild,
    [switch]$Explore,
    [string]$StopFile = "",
    [string]$Only = "",
    [string]$ReportPath = "scripts/reports/out/behavior-probes-report.json"
)

$ErrorActionPreference = "Stop"

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
Push-Location $repoRoot
try {

. (Join-Path $repoRoot "scripts\npdev-common.ps1")
Set-StrictMode -Off
. (Join-Path $repoRoot "NPDevSamples\scripts\sample-common.ps1")

function Write-Section { param([string]$m) Write-Host "== $m ==" -ForegroundColor Cyan }

function Get-DescendantProcessIds {
    param([int]$RootProcessId)
    $allProcesses = @(Get-CimInstance Win32_Process)
    $pending = [System.Collections.Generic.Queue[int]]::new()
    $descendants = [System.Collections.Generic.List[int]]::new()
    $pending.Enqueue($RootProcessId)
    while ($pending.Count -gt 0) {
        $parentId = $pending.Dequeue()
        foreach ($child in @($allProcesses | Where-Object { $_.ParentProcessId -eq $parentId })) {
            $descendants.Add([int]$child.ProcessId) | Out-Null
            $pending.Enqueue([int]$child.ProcessId)
        }
    }
    return @($descendants)
}

function Stop-ProcessTree {
    param([int]$RootProcessId)
    $ids = @((Get-DescendantProcessIds -RootProcessId $RootProcessId) | Select-Object -Unique)
    [array]::Reverse($ids)
    foreach ($id in $ids) {
        if ($id -ne $PID) { Stop-Process -Id $id -Force -ErrorAction SilentlyContinue }
    }
    if ($RootProcessId -ne $PID) { Stop-Process -Id $RootProcessId -Force -ErrorAction SilentlyContinue }
}

function Test-PortFree {
    param([int]$PortNumber)
    $listener = $null
    try {
        $listener = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback, $PortNumber)
        $listener.Start()
        return $true
    } catch { return $false } finally { if ($null -ne $listener) { $listener.Stop() } }
}

$report = [ordered]@{
    schemaVersion = "npdev-behavior-probes-result.v1"
    generatedAt   = (Get-Date).ToUniversalTime().ToString("o")
    sampleId      = $SampleId
    stages        = [ordered]@{}
    probes        = $null
    status        = "failed"
}
function Write-JsonReport {
    $dir = Split-Path -Parent $ReportPath
    if (-not [string]::IsNullOrWhiteSpace($dir)) { New-Item -ItemType Directory -Force -Path $dir | Out-Null }
    $report | ConvertTo-Json -Depth 30 | Set-Content -LiteralPath $ReportPath -Encoding UTF8
}

$samplesRoot = Get-NPDevSamplesRoot -ScriptRoot (Join-Path $repoRoot "NPDevSamples\scripts")
New-Item -ItemType Directory -Force -Path (Join-Path $samplesRoot "$SampleId\Output") | Out-Null
$sample = Resolve-NPDevSample -SamplesRoot $samplesRoot -SampleId $SampleId
$appRoot = $sample.AppRoot

if (-not (Test-PortFree -PortNumber $Port)) {
    throw "Port $Port is already in use; refusing to start (this runner never kills a process it did not start)."
}

if (-not $ReuseBuild) {
    Write-Section "Generating $SampleId"
    & pwsh -NoProfile -File (Join-Path $repoRoot "NPDevSamples\scripts\generate-sample-app.ps1") -SampleId $SampleId | Out-Host
    if ($LASTEXITCODE -ne 0) { throw "Generation failed for $SampleId." }
    $report.stages.generate = "passed"

    Ensure-NpdevSampleApiKey -AppRoot $appRoot
    $gradlew = Get-NPDevGradleWrapperExecutable $appRoot
    Ensure-File -PathValue $gradlew -Label "Generated app Gradle wrapper"

    Write-Section "Building $SampleId"
    $buildOut = Join-Path $appRoot "behavior-probes-build.stdout.log"
    $buildErr = Join-Path $appRoot "behavior-probes-build.stderr.log"
    $build = Start-Process -FilePath $gradlew -ArgumentList @("--no-daemon", "build", "-x", "test", "--console=plain") -WorkingDirectory $appRoot -NoNewWindow -Wait -PassThru -RedirectStandardOutput $buildOut -RedirectStandardError $buildErr
    $report.stages.build = if ($build.ExitCode -eq 0) { "passed" } else { "failed (exit $($build.ExitCode); see $buildOut)" }
    Write-JsonReport
    if ($build.ExitCode -ne 0) { throw "Build failed for $SampleId -- see $buildOut." }
}
else {
    $gradlew = Get-NPDevGradleWrapperExecutable $appRoot
    Ensure-File -PathValue $gradlew -Label "Generated app Gradle wrapper (run once without -ReuseBuild)"
}

$evidenceDir = Join-Path $sample.RunOutputRoot "behavior-probes"
New-Item -ItemType Directory -Force -Path $evidenceDir | Out-Null
$py = if (Get-Command python -ErrorAction SilentlyContinue) { "python" } else { "py" }

$app = $null
try {
    # Probes create rows and read them back, so every run starts from an empty database (regeneration
    # spares <App>/data, see CLAUDE.md).
    $dataDir = Join-Path $appRoot "data"
    if ((Test-Path -LiteralPath $dataDir) -and $dataDir.StartsWith($sample.OutputRoot, [System.StringComparison]::OrdinalIgnoreCase)) {
        Remove-Item -LiteralPath $dataDir -Recurse -Force
    }

    Write-Section "Booting $SampleId on port $Port"
    $bootOut = Join-Path $appRoot "behavior-probes-boot.stdout.log"
    $bootErr = Join-Path $appRoot "behavior-probes-boot.stderr.log"
    $app = Start-Process -FilePath $gradlew -ArgumentList @("--no-daemon", "bootRun", ('--args="--server.port=' + $Port + '"')) -WorkingDirectory $appRoot -NoNewWindow -PassThru -RedirectStandardOutput $bootOut -RedirectStandardError $bootErr

    $baseUrl = "http://127.0.0.1:$Port"
    $deadline = (Get-Date).AddSeconds($BootTimeoutSeconds)
    $healthy = $false
    $lastError = ""
    while ((Get-Date) -lt $deadline) {
        if ($app.HasExited) { $lastError = "Process exited before health check passed with code $($app.ExitCode)."; break }
        try {
            $health = Invoke-WebRequest -Uri "$baseUrl/actuator/health" -TimeoutSec 5 -SkipHttpErrorCheck
            $healthBody = if ($health.Content -is [byte[]]) { [System.Text.Encoding]::UTF8.GetString($health.Content) } else { [string]$health.Content }
            if ([int]$health.StatusCode -eq 200 -and $healthBody -match '"status"\s*:\s*"UP"') { $healthy = $true; break }
            $lastError = "Health returned $($health.StatusCode)"
        } catch { $lastError = $_.Exception.Message }
        Start-Sleep -Seconds 3
    }
    $report.stages.boot = if ($healthy) { "passed" } else { "failed ($lastError; see $bootOut)" }
    Write-JsonReport
    if (-not $healthy) { throw "Generated app did not become healthy: $lastError" }

    $apiKey = Get-NpdevLiveApiKey -AppRoot $appRoot

    if ($Explore) {
        if ([string]::IsNullOrWhiteSpace($StopFile)) { $StopFile = Join-Path $evidenceDir "stop-explore.flag" }
        Remove-Item -LiteralPath $StopFile -Force -ErrorAction SilentlyContinue
        Write-Host "EXPLORE: $baseUrl up; api key in env NPDEV_PROBE_API_KEY file $evidenceDir\api-key.txt; create $StopFile to stop." -ForegroundColor Yellow
        Set-Content -LiteralPath (Join-Path $evidenceDir "api-key.txt") -Value $apiKey -Encoding ASCII
        while (-not (Test-Path -LiteralPath $StopFile) -and -not $app.HasExited) { Start-Sleep -Seconds 2 }
        $report.status = "explore"
        exit 0
    }

    Write-Section "Behaviour probes"
    $probesDir = Join-Path $sample.SampleRoot "behavior-probes"
    $probeReport = Join-Path $evidenceDir "probe-report.json"
    $probeArgs = @((Join-Path $repoRoot "scripts\quality\behavior_probes\run_probes.py"), "--base-url", $baseUrl, "--probes-dir", $probesDir, "--report", $probeReport)
    if (-not [string]::IsNullOrWhiteSpace($Only)) { $probeArgs += @("--only", $Only) }
    $env:NPDEV_PROBE_API_KEY = $apiKey
    try { & $py @probeArgs | Out-Host } finally { Remove-Item Env:\NPDEV_PROBE_API_KEY -ErrorAction SilentlyContinue }
    $probeExit = $LASTEXITCODE
    if (Test-Path -LiteralPath $probeReport) { $report.probes = Get-Content -Raw -LiteralPath $probeReport | ConvertFrom-Json }

    $allGreen = ($probeExit -eq 0)
    $report.status = if ($allGreen) { "passed" } else { "failed" }
    Write-JsonReport

    if ([string]::IsNullOrWhiteSpace($Only)) {
        & $py (Join-Path $repoRoot "scripts\quality\cadence_state.py") record --id "enf-behavior-probes" --tier T1 --result ($(if ($allGreen) { "passed" } else { "failed" })) 2>&1 | Out-Null
    }
    if (-not $allGreen) { throw "One or more behaviour probes failed -- see $ReportPath and $probeReport." }
    Write-Host "All behaviour probes green against $SampleId." -ForegroundColor Green
}
finally {
    if ($null -ne $app -and -not $app.HasExited) { Stop-ProcessTree -RootProcessId $app.Id }
}

if ($report.status -ne "passed") { exit 1 }
exit 0

}
finally {
    Pop-Location
}
