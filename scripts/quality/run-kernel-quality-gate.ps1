[CmdletBinding()]
param(
    [string]$WorkspaceRoot = "",
    [string]$RunId = "",
    [string]$ReportPath = ""
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

. (Join-Path $PSScriptRoot "..\npdev-common.ps1")

# W3.2 (2026-08-25 remediation plan / QUAL-32, COV-RATCHET): NPDevKernel/kernel's coverage floor is
# an AGGREGATE across :kernel and its 36+ :adapters:* subprojects, but nothing ever ran the Gradle
# task that produces the full set (`kernelQualityGate`, registered in NPDevKernel/build.gradle,
# invoked by no run-*.ps1 and no CI workflow before this file existed). Consequence, measured
# 2026-08-23: the recorded floor was 73.0968 (from a partial run on 2026-08-20) while a full local
# aggregate over 42 reports measured 62.6906 -- the ratchet was red against a number that had never
# been true of the whole module. This script is the missing gate: run kernelQualityGate for real,
# then check-coverage-ratchet.py right after, so the floor is measured by the same thing that
# enforces it. See .github/workflows/kernel-quality-gate.yml for the CI half.

if ([string]::IsNullOrWhiteSpace($WorkspaceRoot)) {
    $WorkspaceRoot = Get-NPDevWorkspaceRoot $PSScriptRoot
}
$WorkspaceRoot = Normalize-NPDevPath $WorkspaceRoot
$RunId = Resolve-NPDevRunId $RunId "kernel-quality-gate"

if ([string]::IsNullOrWhiteSpace($ReportPath)) {
    $ReportPath = Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\reports\out\kernel-quality-gate-report.json"
}

$kernelRoot = Resolve-NPDevWorkspacePath $WorkspaceRoot "NPDevKernel"
Ensure-NPDevDirectory $kernelRoot "NPDevKernel root"
$kernelGradleWrapper = Get-NPDevGradleWrapperExecutable $kernelRoot

Write-NPDevInfo "Running kernelQualityGate (:kernel:test + all :adapters:*:test)"
$kernelLogPath = Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\reports\out\kernel-quality-gate-build.log"
$kernelCommand = Invoke-NPDevCommandEvidence `
    -WorkspaceRoot $WorkspaceRoot `
    -WorkingDirectory $kernelRoot `
    -Executable $kernelGradleWrapper `
    -Arguments @("--no-daemon", "--console=plain", "kernelQualityGate") `
    -LogPath $kernelLogPath

$coverageRatchetEvidence = $null
if ([string]$kernelCommand.status -eq "passed") {
    Write-NPDevInfo "Checking kernel+adapters aggregate coverage against its recorded floor"
    $pyExe = (Get-Command python -ErrorAction Stop).Source
    $coverageRatchetOutput = & $pyExe "scripts/quality/check-coverage-ratchet.py" 2>&1 | ForEach-Object { $_.ToString() }
    $coverageRatchetExitCode = $LASTEXITCODE
    $coverageRatchetEvidence = [pscustomobject]@{
        overallStatus = if ($coverageRatchetExitCode -eq 0) { "passed" } else { "failed" }
        exitCode = $coverageRatchetExitCode
        output = @($coverageRatchetOutput | Select-Object -Last 30)
    }
}

$status = if ([string]$kernelCommand.status -eq "passed" -and $null -ne $coverageRatchetEvidence -and [string]$coverageRatchetEvidence.overallStatus -eq "passed") {
    "passed"
}
else {
    "failed"
}

$report = [pscustomobject]@{
    generatedAt = (Get-Date).ToString("o")
    runId = $RunId
    scriptPath = Get-NPDevWorkspaceRelativePath $WorkspaceRoot $PSCommandPath
    workspaceRoot = $WorkspaceRoot
    overallStatus = $status
    kernelRoot = $kernelRoot
    kernelCommand = $kernelCommand
    coverageRatchet = $coverageRatchetEvidence
}
Write-NPDevJsonFile $ReportPath $report

if ($status -eq "passed") {
    Write-NPDevOk "NPDevKernel quality gate passed."
    return
}

Write-NPDevWarn "NPDevKernel quality gate failed."
if ([string]$kernelCommand.status -ne "passed") {
    throw "kernelQualityGate failed -- see $kernelLogPath"
}
throw "Coverage ratchet failed for kernel/adapters aggregate -- see scripts/quality/check-coverage-ratchet.py output above."
