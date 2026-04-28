[CmdletBinding()]
param(
    [string]$WorkspaceRoot = "",
    [string]$RunId = "",
    [string]$ReportPath = ""
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

. (Join-Path $PSScriptRoot "..\npdev-common.ps1")

if ([string]::IsNullOrWhiteSpace($WorkspaceRoot)) {
    $WorkspaceRoot = Get-NPDevWorkspaceRoot $PSScriptRoot
}
$WorkspaceRoot = Normalize-NPDevPath $WorkspaceRoot
$RunId = Resolve-NPDevRunId $RunId "doctor"

if ([string]::IsNullOrWhiteSpace($ReportPath)) {
    $ReportPath = Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\reports\out\doctor-report.json"
}
else {
    $ReportPath = Normalize-NPDevPath $ReportPath
}

$checks = @(
    @{ label = "root-boundaries"; script = Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\doctor\check-root-boundaries.ps1" },
    @{ label = "gradle-wrapper"; script = Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\doctor\check-gradle-wrapper.ps1" },
    @{ label = "toolchain"; script = Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\doctor\check-toolchain.ps1" },
    @{ label = "node-toolchain"; script = Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\doctor\check-node-toolchain.ps1" },
    @{ label = "canonical-sample-layout"; script = Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\doctor\check-canonical-sample-layout.ps1" },
    @{ label = "output-cleanliness"; script = Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\doctor\check-output-cleanliness.ps1" }
)

$results = @()
foreach ($check in $checks) {
    Write-NPDevInfo ("Running doctor check: " + $check.label)
    $results += & $check.script -WorkspaceRoot $WorkspaceRoot -PassThru
}

$libsDir = Resolve-NPDevWorkspacePath $WorkspaceRoot "NPDevRuntimeHost\libs"
$libs = if (Test-Path -LiteralPath $libsDir -PathType Container) {
    @(Get-ChildItem -LiteralPath $libsDir -Filter *.jar -File)
}
else {
    @()
}
$requiredLibPrefixes = @("dsl-", "kernel-", "generator-")
$missingPrefixes = @()
foreach ($prefix in $requiredLibPrefixes) {
    if (-not ($libs.Name | Where-Object { $_ -like ($prefix + "*") })) {
        $missingPrefixes += $prefix
    }
}

$libsStatus = if (-not (Test-Path -LiteralPath $libsDir -PathType Container) -or $missingPrefixes.Count -gt 0) { "failed" } else { "passed" }
$libsSummary = if ($libsStatus -eq "passed") {
    "RuntimeHost libs staging looks populated."
}
else {
    "RuntimeHost libs staging is incomplete."
}
$results += New-NPDevCheckResult "runtimehost-libs-staging" $libsStatus $libsSummary @{
    libsDir = $libsDir
    jarCount = $libs.Count
    missingPrefixes = $missingPrefixes
}

$failedChecks = @($results | Where-Object { $_.status -eq "failed" })
$warningChecks = @($results | Where-Object { $_.status -eq "warning" })
$overallStatus = if ($failedChecks.Count -gt 0) {
    "failed"
}
elseif ($warningChecks.Count -gt 0) {
    "warning"
}
else {
    "passed"
}

$report = [pscustomobject]@{
    generatedAt = (Get-Date).ToString("o")
    runId = $RunId
    scriptPath = Get-NPDevWorkspaceRelativePath $WorkspaceRoot $PSCommandPath
    workspaceRoot = $WorkspaceRoot
    overallStatus = $overallStatus
    checks = $results
    summary = [pscustomobject]@{
        failed = $failedChecks.Count
        warnings = $warningChecks.Count
        passed = @($results | Where-Object { $_.status -eq "passed" }).Count
    }
}

Write-NPDevJsonFile $ReportPath $report
Write-NPDevInfo ("Doctor report written to " + $ReportPath)

if ($overallStatus -eq "passed") {
    Write-NPDevOk "NPDev doctor passed."
    return
}

if ($overallStatus -eq "warning") {
    Write-NPDevWarn "NPDev doctor completed with warnings."
    return
}

Write-NPDevWarn "NPDev doctor failed."
throw "NPDev doctor failed."
