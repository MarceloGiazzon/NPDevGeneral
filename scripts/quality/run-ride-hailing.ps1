<#
.SYNOPSIS
    Z1: generate + boot NPDevSamples/ride-hailing (jwt auth, wiped database), start its three mocks (payment
    provider, push gateway, OSRM route service) and drive the REST acceptance script drive_s2.py.

.DESCRIPTION
    Boots a real app, so this is T1/T2 (`ride-hailing-s2` in verification-cadence.json), never the static
    aiKnowledge gate. Refuses to start when one of its ports is taken (8150 app, 8701 payments, 8702 push,
    8703 routes) and only ever stops processes it started. The long checks are real waits (an ignored driver
    offer is 20 s, a missing webhook 120 s), so a full run takes several minutes.

.PARAMETER Stages
    drive_s2.py stages (comma list, or `all`). `setup` always runs first.

.PARAMETER ReuseBuild
    Skip generate + build; boot what is already under the sample's Output\App.

.PARAMETER ModelFile
    Optional model.json to copy over the sample's Input\model.json first (iterating on a staged model; the
    committed model is what the daily run uses).
#>
param(
    [string]$SampleId = "ride-hailing",
    [string]$Stages = "all",
    [int]$Port = 0,
    [int]$PspPort = 8701,
    [int]$PushPort = 8702,
    [int]$RoutePort = 8703,
    [int]$BootTimeoutSeconds = 300,
    [switch]$ReuseBuild,
    [string]$ModelFile = "",
    [string]$ReportPath = "scripts/reports/out/ride-hailing-report.json"
)

$ErrorActionPreference = "Stop"

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
Push-Location $repoRoot
try {

. (Join-Path $repoRoot "scripts\npdev-common.ps1")
Set-StrictMode -Off
. (Join-Path $repoRoot "NPDevSamples\scripts\sample-common.ps1")

function Write-Section { param([string]$m) Write-Host "== $m ==" -ForegroundColor Cyan }

function Stop-ProcessTree {
    param([int]$RootProcessId)
    $ids = @((Get-NPDevDescendantProcessIds -RootProcessId $RootProcessId) | Select-Object -Unique)
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
    schemaVersion = "npdev-ride-hailing-result.v1"
    generatedAt   = (Get-Date).ToUniversalTime().ToString("o")
    sampleId      = $SampleId
    stages        = [ordered]@{}
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
$config = Read-SampleConfig -Sample $sample
if ($Port -le 0) { $Port = Get-ConfigInt -Config $config -Path @("runtime", "serverPort") -Fallback 8150 }
$appRoot = $sample.AppRoot
$runMarker = New-NPDevRunMarker "ridehailing"
$profiles = Get-ConfigString -Config $config -Path @("runtime", "springProfile") -Fallback "dev,trial"

foreach ($needed in @($Port, $PspPort, $PushPort, $RoutePort)) {
    if (-not (Test-PortFree -PortNumber $needed)) {
        throw "Port $needed is already in use; refusing to start (this runner never kills a process it did not start)."
    }
}

if (-not [string]::IsNullOrWhiteSpace($ModelFile)) {
    Copy-Item -LiteralPath $ModelFile -Destination (Join-Path $samplesRoot "$SampleId\Input\model.json") -Force
}

if (-not $ReuseBuild) {
    Write-Section "Generating $SampleId"
    & pwsh -NoProfile -File (Join-Path $repoRoot "NPDevSamples\scripts\generate-sample-app.ps1") -SampleId $SampleId | Out-Host
    if ($LASTEXITCODE -ne 0) { throw "Generation failed for $SampleId." }
    $report.stages.generate = "passed"
    $gradlew = Get-NPDevGradleWrapperExecutable $appRoot
    Ensure-File -PathValue $gradlew -Label "Generated app Gradle wrapper"
    Write-Section "Building $SampleId"
    $buildOut = Join-Path $appRoot "ride-hailing-build.stdout.log"
    $buildErr = Join-Path $appRoot "ride-hailing-build.stderr.log"
    $build = Start-Process -FilePath $gradlew -ArgumentList (@("--no-daemon") + (New-NPDevGradleRunMarkerArgs -AppRoot $appRoot -Marker $runMarker) + @("build", "-x", "test", "--console=plain")) -WorkingDirectory $appRoot -NoNewWindow -Wait -PassThru -RedirectStandardOutput $buildOut -RedirectStandardError $buildErr
    $report.stages.build = if ($build.ExitCode -eq 0) { "passed" } else { "failed (exit $($build.ExitCode); see $buildOut)" }
    Write-JsonReport
    if ($build.ExitCode -ne 0) { throw "Build failed for $SampleId -- see $buildOut." }
}
else {
    $gradlew = Get-NPDevGradleWrapperExecutable $appRoot
    Ensure-File -PathValue $gradlew -Label "Generated app Gradle wrapper (run once without -ReuseBuild)"
}

$evidenceDir = Join-Path $sample.RunOutputRoot "ride-hailing"
New-Item -ItemType Directory -Force -Path $evidenceDir | Out-Null
$keysDir = Join-Path $evidenceDir "keys"
New-Item -ItemType Directory -Force -Path $keysDir | Out-Null
$py = if (Get-Command python -ErrorAction SilentlyContinue) { "python" } elseif (Get-Command python3 -ErrorAction SilentlyContinue) { "python3" } else { "py" }
$mocksDir = Join-Path $samplesRoot "$SampleId\mocks"

$app = $null
$mocks = @()
$savedEnv = @{}
# Secrets are environment variables, never model or config values (the mock keys below are public test values).
$envToSet = @{
    "NPDEV_AUTH_MODE"                  = "jwt"
    "NPDEV_AUTH_JWT_ISSUER"            = "ride-hailing"
    "NPDEV_AUTH_JWT_AUDIENCE"          = "ride-hailing-api"
    "NPDEV_AUTH_JWT_PUBLIC_KEY_PATH"   = (Join-Path $keysDir "jwt-public.pem")
    "NPDEV_AUTH_JWT_PRIVATE_KEY_PATH"  = (Join-Path $keysDir "jwt-private-pkcs8.pem")
    "NPDEV_AUTH_JWT_EXPIRY_SECONDS"    = "3600"
    "PSP_API_KEY"                      = "sk_test_mock"
    "NPDEV_WEBHOOK_PSP_SECRET"         = "whsec_test"
    "PUSH_API_KEY"                     = "push_test_mock"
    "NPDEV_NOTIFY_PUSH_URL"            = "http://127.0.0.1:$PushPort/v1/push"
    "NPDEV_NOTIFY_PUSH_KEY_ENV"        = "PUSH_API_KEY"
}
try {
    foreach ($key in $envToSet.Keys) {
        $savedEnv[$key] = [Environment]::GetEnvironmentVariable($key)
        [Environment]::SetEnvironmentVariable($key, $envToSet[$key])
    }
    Write-Section "Starting the mocks (payments $PspPort, push $PushPort, routes $RoutePort)"
    $mocks += Start-Process -FilePath $py -ArgumentList @("-I", (Join-Path $mocksDir "mock_psp.py"), "--port", "$PspPort", "--webhook-url", "http://127.0.0.1:$Port/api/hooks/psp", "--secret", "whsec_test") -NoNewWindow -PassThru -RedirectStandardOutput (Join-Path $evidenceDir "psp.stdout.log") -RedirectStandardError (Join-Path $evidenceDir "psp.stderr.log")
    $mocks += Start-Process -FilePath $py -ArgumentList @("-I", (Join-Path $mocksDir "mock_push.py"), "--port", "$PushPort") -NoNewWindow -PassThru -RedirectStandardOutput (Join-Path $evidenceDir "push.stdout.log") -RedirectStandardError (Join-Path $evidenceDir "push.stderr.log")
    $mocks += Start-Process -FilePath $py -ArgumentList @("-I", (Join-Path $mocksDir "mock_osrm.py"), "--port", "$RoutePort") -NoNewWindow -PassThru -RedirectStandardOutput (Join-Path $evidenceDir "osrm.stdout.log") -RedirectStandardError (Join-Path $evidenceDir "osrm.stderr.log")
    Start-Sleep -Seconds 2
    foreach ($m in $mocks) { if ($m.HasExited) { throw "A mock exited immediately; see $evidenceDir\*.stderr.log." } }

    # Regeneration spares <App>/data, and bootstrap-admin refuses a tenant that already has a user.
    $dataDir = Join-Path $appRoot "data"
    if ((Test-Path -LiteralPath $dataDir) -and $dataDir.StartsWith($sample.OutputRoot, [System.StringComparison]::OrdinalIgnoreCase)) {
        Remove-Item -LiteralPath $dataDir -Recurse -Force
    }

    Write-Section "Booting $SampleId on port $Port"
    $bootOut = Join-Path $appRoot "ride-hailing-boot.stdout.log"
    $bootErr = Join-Path $appRoot "ride-hailing-boot.stderr.log"
    $app = Start-Process -FilePath $gradlew -ArgumentList (@("--no-daemon") + (New-NPDevGradleRunMarkerArgs -AppRoot $appRoot -Marker $runMarker) + @("bootRun", ('--args="--spring.profiles.active=' + $profiles + ' --server.port=' + $Port + '"'))) -WorkingDirectory $appRoot -NoNewWindow -PassThru -RedirectStandardOutput $bootOut -RedirectStandardError $bootErr
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
    if (-not $healthy) { throw "The app did not become healthy: $lastError (see $bootOut)." }

    Write-Section "Driving the acceptance script (stages: $Stages)"
    $driveOut = Join-Path $evidenceDir "drive.txt"
    & $py -I (Join-Path $samplesRoot "$SampleId\drive_s2.py") --base $baseUrl --stages $Stages --psp "http://127.0.0.1:$PspPort" --push "http://127.0.0.1:$PushPort" 2>&1 | Tee-Object -FilePath $driveOut | Out-Host
    $driveExit = $LASTEXITCODE
    $report.stages.drive = if ($driveExit -eq 0) { "passed" } else { "failed (see $driveOut)" }
    $report.status = if ($driveExit -eq 0) { "passed" } else { "failed" }
    Write-JsonReport
    if ($Stages -eq "all") {
        & $py (Join-Path $repoRoot "scripts\quality\cadence_state.py") record --id "ride-hailing-s2" --tier T1 --result $report.status 2>&1 | Out-Null
    }
    if ($driveExit -ne 0) { throw "The ride-hailing acceptance run failed -- see $driveOut." }
    Write-Host "Ride-hailing acceptance green." -ForegroundColor Green
}
finally {
    foreach ($key in $savedEnv.Keys) { [Environment]::SetEnvironmentVariable($key, $savedEnv[$key]) }
    if ($null -ne $app -and -not $app.HasExited) { Stop-ProcessTree -RootProcessId $app.Id }
    foreach ($m in $mocks) { if ($null -ne $m -and -not $m.HasExited) { Stop-ProcessTree -RootProcessId $m.Id } }
    Stop-NPDevMarkedProcesses -Marker $runMarker
}

if ($report.status -ne "passed") { exit 1 }
exit 0

}
finally {
    Pop-Location
}
