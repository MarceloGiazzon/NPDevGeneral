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
$RunId = Resolve-NPDevRunId $RunId "invariant-gate"

if ([string]::IsNullOrWhiteSpace($ReportPath)) {
    $ReportPath = Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\reports\out\invariant-gate-report.json"
}

$projectRoot = Resolve-NPDevWorkspacePath $WorkspaceRoot "NPDevKernel"
$gradleWrapperPath = Join-Path $projectRoot "gradlew.bat"
$status = "passed"
$errorMessage = $null
$commandEvidence = $null
try {
    Write-NPDevInfo "Running invariant gate through NPDevKernel quality gate"
    $commandEvidence = Invoke-NPDevCommandEvidence `
        -WorkspaceRoot $WorkspaceRoot `
        -WorkingDirectory $projectRoot `
        -Executable $gradleWrapperPath `
        -Arguments @("kernelQualityGate", "--no-daemon", "--console=plain")
    if ([string]$commandEvidence.status -ne "passed") {
        throw "Invariant gate command failed."
    }
}
catch {
    $status = "failed"
    $errorMessage = $_.Exception.Message
}

$report = [pscustomobject]@{
    generatedAt = (Get-Date).ToString("o")
    runId = $RunId
    scriptPath = Get-NPDevWorkspaceRelativePath $WorkspaceRoot $PSCommandPath
    workspaceRoot = $WorkspaceRoot
    overallStatus = $status
    workingDirectory = Get-NPDevWorkspaceRelativePath $WorkspaceRoot $projectRoot
    projectRoot = $projectRoot
    command = $commandEvidence
    error = $errorMessage
}
Write-NPDevJsonFile $ReportPath $report

if ($status -eq "passed") {
    Write-NPDevOk "Invariant gate passed."
    return
}

Write-NPDevWarn "Invariant gate failed."
throw $errorMessage
