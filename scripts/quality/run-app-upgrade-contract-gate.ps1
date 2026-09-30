param(
    [string]$RunId = "",
    [string]$ReportPath = "scripts/reports/out/app-upgrade-contract-gate-report.json",
    [string]$SampleId = "simple-user-registry"
)

# docs/architecture/APP_UPGRADE_CONTRACT.md DoD: a FinalApp generated on version N upgrades to N+1
# with its author-owned web/ customizations intact. Generates the sample, then makes the output look
# like an older platform's (a drifted platform-owned file, a stray generated file) plus live operator
# state in the spared data/logs/secrets directories, regenerates over it, and asserts the platform
# layer was replaced while everything the author/operator owns survived byte-identical.

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

. (Join-Path $PSScriptRoot "..\npdev-common.ps1")

$workspaceRoot = Get-NPDevWorkspaceRoot $PSScriptRoot
if ([string]::IsNullOrWhiteSpace($RunId)) {
    $RunId = "app-upgrade-contract-" + (Get-Date).ToUniversalTime().ToString("yyyyMMdd-HHmmssfff")
}
if (-not [System.IO.Path]::IsPathRooted($ReportPath)) {
    $ReportPath = Join-Path $workspaceRoot $ReportPath
}

$failures = @()
function Assert-Condition([bool]$Condition, [string]$Name, [string]$Message) {
    if (-not $Condition) {
        $script:failures += [pscustomobject]@{ name = $Name; message = $Message }
    }
}

function Get-FileHashes([string]$Root) {
    $map = [ordered]@{}
    if (Test-Path -LiteralPath $Root) {
        Get-ChildItem -LiteralPath $Root -Recurse -File | Sort-Object FullName | ForEach-Object {
            $relative = [System.IO.Path]::GetRelativePath($Root, $_.FullName).Replace('\', '/')
            $map[$relative] = (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash
        }
    }
    return $map
}

function Invoke-Generation([string]$Label) {
    Write-NPDevInfo ("Generation " + $Label + " for " + $SampleId)
    & pwsh -NoProfile -File $generateScript -SampleId $SampleId -OutputRoot $outputRoot -RunId ($RunId + "-" + $Label) | Out-Host
    if ($LASTEXITCODE -ne 0) {
        throw ("Generation " + $Label + " failed with exit code " + $LASTEXITCODE)
    }
}

$generateScript = Join-Path $workspaceRoot "NPDevSamples\scripts\generate-sample-app.ps1"
$webSource = Join-Path $workspaceRoot ("NPDevSamples\" + $SampleId + "\Input\web")
$runRoot = Join-Path (Get-NPDevBuildRoot $workspaceRoot) ("app-upgrade-contract\" + $RunId)
$outputRoot = Join-Path $runRoot "Output"
$appRoot = Join-Path $outputRoot "App"
$staticRoot = Join-Path $appRoot "src\main\resources\static"
$generatedRoot = Join-Path $appRoot "npdev-generated"
$sparedDirectories = @("data", "logs", "secrets")
$strayRelative = "stale-file-from-previous-platform-version.txt"
$errorMessage = $null
$driftedRelative = $null

try {
    $webFiles = Get-FileHashes $webSource
    if ($webFiles.Count -eq 0) {
        throw ("Sample " + $SampleId + " has no files under Input/web -- nothing to prove.")
    }

    Invoke-Generation "version-n"
    $mountedAfterFirst = Get-FileHashes $staticRoot
    foreach ($relative in $webFiles.Keys) {
        Assert-Condition ($mountedAfterFirst[$relative] -eq $webFiles[$relative]) "web-customization-mounted" ("web/" + $relative + " is not mounted byte-identical into static/ after the first generation.")
    }
    $platformBefore = Get-FileHashes $generatedRoot
    if ($platformBefore.Count -eq 0) {
        throw "First generation produced no npdev-generated files."
    }

    $driftedRelative = @($platformBefore.Keys)[0]
    Add-Content -LiteralPath (Join-Path $generatedRoot $driftedRelative) -Value "drifted-by-previous-platform-version"
    Set-Content -LiteralPath (Join-Path $generatedRoot $strayRelative) -Value "left behind by version N" -Encoding UTF8
    $operatorState = [ordered]@{}
    foreach ($directory in $sparedDirectories) {
        $path = Join-Path (Join-Path $appRoot $directory) "upgrade-contract-probe.txt"
        New-Item -ItemType Directory -Force -Path (Split-Path -Parent $path) | Out-Null
        Set-Content -LiteralPath $path -Value ("operator state in " + $directory + " -- " + $RunId) -Encoding UTF8
        $operatorState[$directory] = (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash
    }

    Invoke-Generation "version-n-plus-1"
    $mountedAfterSecond = Get-FileHashes $staticRoot
    foreach ($relative in $webFiles.Keys) {
        Assert-Condition ($mountedAfterSecond[$relative] -eq $webFiles[$relative]) "web-customization-survives-regeneration" ("web/" + $relative + " did not survive regeneration byte-identical.")
    }
    foreach ($directory in $sparedDirectories) {
        $path = Join-Path (Join-Path $appRoot $directory) "upgrade-contract-probe.txt"
        $survived = (Test-Path -LiteralPath $path) -and ((Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash -eq $operatorState[$directory])
        Assert-Condition $survived "operator-state-survives-regeneration" ($directory + "/ operator state was lost or changed by regeneration.")
    }
    $platformAfter = Get-FileHashes $generatedRoot
    Assert-Condition ($platformAfter[$driftedRelative] -eq $platformBefore[$driftedRelative]) "platform-owned-file-replaced" ("Drifted platform file " + $driftedRelative + " was not regenerated.")
    Assert-Condition (-not $platformAfter.Contains($strayRelative)) "stale-platform-file-removed" "A stray file in npdev-generated/ survived regeneration."
}
catch {
    $errorMessage = $_.Exception.Message
    $failures += [pscustomobject]@{ name = "gate-execution"; message = $errorMessage }
}

$overallStatus = if ($failures.Count -eq 0) { "passed" } else { "failed" }
if ($overallStatus -eq "passed" -and (Test-Path -LiteralPath $runRoot)) {
    Remove-Item -LiteralPath $runRoot -Recurse -Force
}

$report = [pscustomobject]@{
    schemaVersion = "npdev-app-upgrade-contract-gate-report.v1"
    runId = $RunId
    generatedAt = (Get-Date).ToUniversalTime().ToString("o")
    scriptPath = "scripts/quality/run-app-upgrade-contract-gate.ps1"
    sampleId = $SampleId
    overallStatus = $overallStatus
    evidenceRoot = if ($overallStatus -eq "passed") { $null } else { $runRoot }
    assertions = [pscustomobject]@{
        failed = $failures.Count
        names = @(
            "web-customization-mounted",
            "web-customization-survives-regeneration",
            "operator-state-survives-regeneration",
            "platform-owned-file-replaced",
            "stale-platform-file-removed"
        )
    }
    failures = @($failures)
}
New-Item -ItemType Directory -Force -Path (Split-Path -Parent $ReportPath) | Out-Null
$report | ConvertTo-Json -Depth 20 | Set-Content -LiteralPath $ReportPath -Encoding UTF8

if ($overallStatus -eq "passed") {
    Write-NPDevOk ("App upgrade contract gate passed. Report: " + $ReportPath)
    exit 0
}
Write-Error ("App upgrade contract gate failed. Report: " + $ReportPath)
exit 1
