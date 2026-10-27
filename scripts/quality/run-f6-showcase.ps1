<#
.SYNOPSIS
  F6: generate, boot and browser-verify the f6-showcase app -- one app, one section per frontend coverage feature.

.DESCRIPTION
  Generates NPDevSamples/f6-showcase into <Build root>\samples\f6-showcase (never inside the repo), boots it on its
  configured port with a FRESH database, and drives five browser routines in order against the real server:

    1. f6-layoutslot-verify-routine   F6-1  panels[].layout.slots   (app-owned components above/below a panel)
    2. f6-dashboard-verify-routine    F6-3  layout.type dashboard   (tiles + bar/donut/line charts)
    3. f6-computed-verify-routine     F6-5  computed[].collection   (live per-row columns + live derived banner)
    4. f6-nesting-verify-routine      F6-4  third nesting level     (section -> band -> sub-band, saved and reloaded)
    5. f6-console-verify-routine      F6-2  layout.type console     (tiles, action, history; creates a ticket)

  The ORDER matters: the nesting routine commits changes and the console routine creates a ticket, so both run after the
  routines that count seeded rows. The database is wiped before boot so every run starts from the seeds.

  Refuses to run if the app's port is already taken (it never kills a process it did not start). The app is always
  stopped afterwards. Needs Java 17+, PowerShell 7 and the ScrapForAI engine (see the verify-in-browser skill).

.PARAMETER InstallBrowsers
  Also run `npx playwright install chromium` in the engine before the routines. Needed on a fresh machine (CI); a developer
  machine normally has the browsers already.

.PARAMETER ReportPath
  Where to write the run's structured JSON report (schemaVersion npdev-f6-showcase-result.v1): per-routine status and an
  overallStatus, written even when the run fails.

.EXAMPLE
  pwsh -NoProfile -File scripts/quality/run-f6-showcase.ps1
#>
param(
    [int]$BootTimeoutSeconds = 420,
    [switch]$SkipGenerate,
    [switch]$KeepRunning,
    [switch]$InstallBrowsers,
    [string]$ReportPath = "scripts/reports/out/f6-showcase-report.json"
)

$ErrorActionPreference = "Stop"
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path

. (Join-Path $repoRoot "scripts\npdev-common.ps1")
. (Join-Path $repoRoot "NPDevSamples\scripts\sample-common.ps1")
. (Join-Path $repoRoot "NPDevSamples\scripts\browser\scrapforai-harness.ps1")

$sampleId = "f6-showcase"
# Read the sample's config directly: Resolve-NPDevSample insists on an in-repo Output folder, which this run never uses.
$configPath = Join-Path $repoRoot "NPDevSamples\$sampleId\Input\config.json"
if (-not (Test-Path -LiteralPath $configPath)) { throw "Sample config not found: $configPath" }
$config = Get-Content -LiteralPath $configPath -Raw | ConvertFrom-Json
$port = [int]$config.runtime.serverPort
$database = [string]$config.database.database
$outputRoot = Join-Path (Join-Path (Get-NPDevBuildRoot $repoRoot) "samples") $sampleId
$appRoot = Join-Path $outputRoot "App"
$baseUrl = "http://127.0.0.1:$port"

# The routines open workbench pages by absolute URL on the port the showcase config pins.
if ($port -ne 8110) { throw "f6-showcase must keep runtime.serverPort 8110: the browser routines address http://127.0.0.1:8110 directly." }

# XPLAT-2a: Get-NetTCPConnection does not exist off Windows; the shared helpers fall back to lsof/ss and a bind test.
$listenerIds = @(Get-NPDevListeningProcessIds $port)
if ($listenerIds.Count -gt 0) { throw "Port $port is already in use (process $($listenerIds[0])); stop it first -- this script never kills a process it did not start." }
if (-not $IsWindows -and -not (Test-NPDevPortFree $port)) { throw "Port $port is already in use (holder unknown); stop it first -- this script never kills a process it did not start." }

$routines = @(
    "f6-layoutslot-verify-routine",
    "f6-dashboard-verify-routine",
    "f6-computed-verify-routine",
    "f6-nesting-verify-routine",
    "f6-console-verify-routine"
)
$routineDir = Join-Path $repoRoot "NPDevSamples\scripts\browser\browser-routines"
foreach ($routine in $routines) {
    if (-not (Test-Path -LiteralPath (Join-Path $routineDir "$routine.json"))) { throw "Missing routine $routine.json" }
}

function Stop-ProcessTree([int]$ProcessId) {
    if ($ProcessId -le 0) { return }
    if ($IsWindows) {
        & taskkill /PID $ProcessId /T /F 2>&1 | Out-Null
        return
    }
    # XPLAT-2a: no taskkill off Windows -- walk the tree via ps and kill leaves first.
    $ids = @(Get-NPDevDescendantProcessIds $ProcessId)
    [array]::Reverse($ids)
    foreach ($id in $ids) { Stop-Process -Id $id -Force -ErrorAction SilentlyContinue }
    Stop-Process -Id $ProcessId -Force -ErrorAction SilentlyContinue
}

$report = [ordered]@{
    schemaVersion = "npdev-f6-showcase-result.v1"
    generatedAt   = (Get-Date).ToUniversalTime().ToString("o")
    runId         = [guid]::NewGuid().ToString()
    scriptPath    = "scripts/quality/run-f6-showcase.ps1"
    workspaceRoot = $repoRoot
    sampleId      = $sampleId
    port          = $port
    routines      = @()
    overallStatus = "failed"
}

$process = $null
$scrapCtx = $null
try {
    if (-not $SkipGenerate) {
        Write-Host "== Generating $sampleId into $outputRoot ==" -ForegroundColor Cyan
        & (Join-Path $repoRoot "NPDevSamples\scripts\generate-sample-app.ps1") -SampleId $sampleId -OutputRoot $outputRoot
        if (-not $?) { throw "generate-sample-app.ps1 failed" }
    }
    if (-not (Test-Path -LiteralPath $appRoot)) { throw "Generated app not found at $appRoot (run without -SkipGenerate once)." }

    # A fresh database every run: a reboot does NOT reset H2, and the routines count seeded rows.
    $dataDir = Join-Path $appRoot "data"
    if (Test-Path -LiteralPath $dataDir) {
        Get-ChildItem -LiteralPath $dataDir -Filter "$database.*.db" -ErrorAction SilentlyContinue | Remove-Item -Force
    }

    Write-Host "== Booting $sampleId on port $port ==" -ForegroundColor Cyan
    $gradlew = Get-NPDevGradleWrapperExecutable $appRoot
    $stdout = Join-Path $appRoot "f6-showcase-boot.stdout.log"
    $stderr = Join-Path $appRoot "f6-showcase-boot.stderr.log"
    $process = Start-Process -FilePath $gradlew -ArgumentList @("--no-daemon", "bootRun", ('--args="--server.port=' + $port + '"')) `
        -WorkingDirectory $appRoot -NoNewWindow -PassThru -RedirectStandardOutput $stdout -RedirectStandardError $stderr

    $deadline = (Get-Date).AddSeconds($BootTimeoutSeconds)
    $healthy = $false
    $lastError = ""
    while ((Get-Date) -lt $deadline) {
        if ($process.HasExited) { $lastError = "process exited with code $($process.ExitCode) before the health check passed"; break }
        try {
            $health = Invoke-WebRequest -Uri "$baseUrl/actuator/health" -TimeoutSec 5 -SkipHttpErrorCheck
            # The actuator's media type makes Invoke-WebRequest hand back raw bytes, not a string.
            $body = if ($health.Content -is [byte[]]) { [System.Text.Encoding]::UTF8.GetString($health.Content) } else { [string]$health.Content }
            if ([int]$health.StatusCode -eq 200 -and $body -match '"status"\s*:\s*"UP"') { $healthy = $true; break }
            $lastError = "health returned $([int]$health.StatusCode)"
        } catch { $lastError = $_.Exception.Message }
        Start-Sleep -Seconds 3
    }
    if (-not $healthy) { throw "f6-showcase did not become healthy: $lastError (boot log: $stdout)" }

    $creds = @{ apiKey = (Get-NpdevLiveApiKey -AppRoot $appRoot) }
    Initialize-ScrapForAI -InstallBrowsers:$InstallBrowsers | Out-Null
    $scrapCtx = Start-ScrapForAI -AppBaseUrl $baseUrl -AllowEvaluate

    $failed = @()
    foreach ($routine in $routines) {
        Write-Host "== $routine ==" -ForegroundColor Cyan
        try {
            $result = Invoke-ScrapRoutine -Context $scrapCtx -RoutinePath (Join-Path $routineDir "$routine.json") -Credentials $creds
            # Benign by design: pre-auth 401s, the app-less theme.css and favicon 404s.
            Assert-RoutineGreen -Result $result -Label $routine -AllowConsoleErrorSubstrings @("theme.css", "status of 401", "status of 404")
            $report.routines += [ordered]@{ name = $routine; status = "passed"; message = "" }
        } catch {
            Write-Host ("FAIL  " + $routine + ": " + $_.Exception.Message) -ForegroundColor Red
            $failed += $routine
            $report.routines += [ordered]@{ name = $routine; status = "failed"; message = $_.Exception.Message }
        }
    }
    if ($failed.Count -gt 0) { throw ("F6 showcase routines failed: " + ($failed -join ", ")) }
    $report.overallStatus = "passed"
    Write-Host ("OK    F6 showcase: all " + $routines.Count + " browser routines green against a fresh database.") -ForegroundColor Green
}
finally {
    $reportDirectory = Split-Path -Parent $ReportPath
    if (-not [string]::IsNullOrWhiteSpace($reportDirectory)) { New-Item -ItemType Directory -Force -Path $reportDirectory | Out-Null }
    $report | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath $ReportPath -Encoding UTF8
    # Record the run in the verification cadence like run-journeys / run-shell-scenarios do; without
    # this `f6-showcase-browser` stayed "never-run" however often the showcase passed.
    $py = if (Get-Command python -ErrorAction SilentlyContinue) { "python" } elseif (Get-Command python3 -ErrorAction SilentlyContinue) { "python3" } else { "py" }
    & $py (Join-Path $repoRoot "scripts\quality\cadence_state.py") record --id "f6-showcase-browser" --tier T1 --result ($(if ($report.overallStatus -eq "passed") { "passed" } else { "failed" })) 2>&1 | Out-Null
    if ($scrapCtx) { Stop-ScrapForAI -Context $scrapCtx }
    if ($process -and -not $KeepRunning) { Stop-ProcessTree $process.Id }
}
