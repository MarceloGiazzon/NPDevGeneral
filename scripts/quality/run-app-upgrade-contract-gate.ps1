[CmdletBinding()]
param(
    [string]$AppGenRoot = "D:\WorkSpace\NPDev\AppGen",
    [string]$AppId = "simple-user-registry-inmemory",
    [string]$ProductRepo = "",
    [string]$ReportPath = ""
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

. (Join-Path $PSScriptRoot "..\npdev-common.ps1")

if ([string]::IsNullOrWhiteSpace($ProductRepo)) {
    $ProductRepo = Get-NPDevWorkspaceRoot $PSScriptRoot
}
$ProductRepo = Normalize-NPDevPath $ProductRepo

if ([string]::IsNullOrWhiteSpace($ReportPath)) {
    $ReportPath = Resolve-NPDevWorkspacePath $ProductRepo "scripts\reports\out\app-upgrade-contract-gate-report.json"
}

<#
LNCH-21 DoD: "A FinalApp generated on version N upgrades to N+1 with local `web/` customizations
intact, proven in the release gate." docs/architecture/APP_UPGRADE_CONTRACT.md documented that
this is true BY CONSTRUCTION (app-owned web/ lives outside the wiped-and-regenerated output tree
and gets re-mounted fresh each build) but was not yet proven by an automated test -- this script
is that test: run Build-NpdevApp.ps1 twice against the same app definition with a web/
customization present, assert the customization file is byte-identical in the output both times.

Uses a real, already-verified AppGen sample (simple-user-registry-inmemory) rather than a
throwaway scratch app, adding a small marker file under its web/ directory -- additive only, does
not touch the app's model/config, so this does not change that sample's own behavior.
#>

$AppFolder = Join-Path $AppGenRoot "apps\$AppId"
$WebDir = Join-Path $AppFolder "web"
$MarkerRelativePath = "upgrade-contract-gate-marker.txt"
$MarkerPath = Join-Path $WebDir $MarkerRelativePath
$BuildScript = Resolve-NPDevWorkspacePath $ProductRepo "scripts\appgen\Build-NpdevApp.ps1"

Ensure-NPDevDirectory $AppFolder "AppGen app folder ($AppId)"
Ensure-NPDevFile $BuildScript "Build-NpdevApp.ps1"

$status = "passed"
$errorMessage = $null
$firstContent = $null
$secondContent = $null
$markerCreatedByThisRun = $false

try {
    if (-not (Test-Path -LiteralPath $WebDir)) {
        New-Item -ItemType Directory -Force -Path $WebDir | Out-Null
    }
    if (-not (Test-Path -LiteralPath $MarkerPath)) {
        $markerCreatedByThisRun = $true
        "upgrade-contract-gate marker -- created $(Get-Date -Format o)" | Set-Content -LiteralPath $MarkerPath -Encoding UTF8
    }
    $expectedContent = Get-Content -LiteralPath $MarkerPath -Raw

    Write-NPDevInfo "First generation run for $AppId"
    # Invoked as a separate pwsh process, not dot-sourced/called in this runspace -- Build-NpdevApp.ps1
    # does not itself set Set-StrictMode, and this script's own strict mode (from npdev-common.ps1)
    # otherwise leaks into it via PowerShell's dynamic scoping, breaking its `$cfg.console` optional-
    # property checks on any app definition (like this one) that doesn't declare a "console" section.
    & pwsh -NoProfile -File $BuildScript -AppFolder $AppFolder -ProductRepo $ProductRepo -GenerateOnly
    if ($LASTEXITCODE -ne 0) { throw "First Build-NpdevApp.ps1 run failed with exit code $LASTEXITCODE" }

    $cfg = Get-Content -LiteralPath (Join-Path $AppFolder "definition\config.json") -Raw | ConvertFrom-Json
    $outAppRoot = Join-Path (Join-Path "D:\WorkSpace\NPDev\Build\generated-finalapps" $cfg.scenario.name) "App"
    $mountedMarkerPath = Join-Path $outAppRoot "src\main\resources\static\$MarkerRelativePath"
    Ensure-NPDevFile $mountedMarkerPath "web/ marker mounted into first-run output"
    $firstContent = Get-Content -LiteralPath $mountedMarkerPath -Raw

    Write-NPDevInfo "Second generation run for $AppId (proving regeneration doesn't lose it)"
    & pwsh -NoProfile -File $BuildScript -AppFolder $AppFolder -ProductRepo $ProductRepo -GenerateOnly
    if ($LASTEXITCODE -ne 0) { throw "Second Build-NpdevApp.ps1 run failed with exit code $LASTEXITCODE" }

    Ensure-NPDevFile $mountedMarkerPath "web/ marker mounted into second-run output"
    $secondContent = Get-Content -LiteralPath $mountedMarkerPath -Raw

    if ($expectedContent -ne $firstContent -or $expectedContent -ne $secondContent) {
        throw "Marker content mismatch across regenerations -- expected='$expectedContent' first='$firstContent' second='$secondContent'"
    }

    Write-NPDevOk "web/ customization survived two regenerations byte-identical."
} catch {
    $status = "failed"
    $errorMessage = $_.Exception.Message
} finally {
    if ($markerCreatedByThisRun -and (Test-Path -LiteralPath $MarkerPath)) {
        Remove-Item -LiteralPath $MarkerPath -Force
    }
}

$report = [pscustomobject]@{
    schemaVersion  = "npdev-app-upgrade-contract-gate-report.v1"
    generatedAt    = (Get-Date).ToUniversalTime().ToString("o")
    appId          = $AppId
    overallStatus  = $status
    error          = $errorMessage
}
$reportDir = Split-Path -Parent $ReportPath
if (-not (Test-Path -LiteralPath $reportDir)) {
    New-Item -ItemType Directory -Path $reportDir -Force | Out-Null
}
$report | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath $ReportPath -Encoding UTF8

if ($status -eq "passed") {
    Write-NPDevOk "App-upgrade-contract gate passed. Report: $ReportPath"
    exit 0
} else {
    Write-NPDevWarn "App-upgrade-contract gate failed: $errorMessage"
    exit 1
}
