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
$RunId = Resolve-NPDevRunId $RunId "kernel-gate"

if ([string]::IsNullOrWhiteSpace($ReportPath)) {
    $ReportPath = Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\reports\out\kernel-gate-report.json"
}
else {
    $ReportPath = Normalize-NPDevPath $ReportPath
}

$projectRoot = Resolve-NPDevWorkspacePath $WorkspaceRoot "NPDevKernel"
$gradleWrapperPath = Join-Path $projectRoot "gradlew.bat"
$kernelRuntimeProofScript = Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\quality\run-kernel-runtime-proof.ps1"
$kernelRuntimeProofReportPath = Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\reports\out\kernel-runtime-proof-report.json"
Write-NPDevInfo "Running NPDevKernel gate"

Invoke-NPDevReportedCommand `
    -WorkspaceRoot $WorkspaceRoot `
    -ScriptPath $PSCommandPath `
    -RunId $RunId `
    -ReportPath $ReportPath `
    -GateName "kernel" `
    -WorkingDirectory $projectRoot `
    -Executable $gradleWrapperPath `
    -Arguments @("kernelQualityGate", "--no-daemon", "--console=plain") | Out-Null

$gateReport = Get-Content -LiteralPath $ReportPath -Raw | ConvertFrom-Json
$kernelRuntimeProof = $null
$proofError = $null
try {
    $kernelRuntimeProof = & $kernelRuntimeProofScript `
        -WorkspaceRoot $WorkspaceRoot `
        -RunId ($RunId + "-kernel-runtime-proof") `
        -ReportPath $kernelRuntimeProofReportPath `
        -PassThru
}
catch {
    $proofError = $_.Exception.Message
    if (Test-Path -LiteralPath $kernelRuntimeProofReportPath -PathType Leaf) {
        try {
            $kernelRuntimeProof = Get-Content -LiteralPath $kernelRuntimeProofReportPath -Raw | ConvertFrom-Json
        }
        catch {
            $kernelRuntimeProof = $null
        }
    }
}

$gateReport | Add-Member -NotePropertyName kernelRuntimeProofReportPath -NotePropertyValue (Get-NPDevWorkspaceRelativePath $WorkspaceRoot $kernelRuntimeProofReportPath) -Force
$gateReport | Add-Member -NotePropertyName kernelRuntimeProof -NotePropertyValue $(if ($null -eq $kernelRuntimeProof) {
    $null
}
else {
    [pscustomobject]@{
        overallStatus = [string]$kernelRuntimeProof.overallStatus
        reportPath = Get-NPDevWorkspaceRelativePath $WorkspaceRoot $kernelRuntimeProofReportPath
    }
}) -Force

if (-not [string]::IsNullOrWhiteSpace($proofError) -or ($null -ne $kernelRuntimeProof -and [string]$kernelRuntimeProof.overallStatus -ne "passed")) {
    $gateReport.overallStatus = "failed"
    $gateReport.failureReasons = @(
        @($gateReport.failureReasons) +
        @(
            if (-not [string]::IsNullOrWhiteSpace($proofError)) {
                $proofError
            }
            elseif ($null -ne $kernelRuntimeProof) {
                "Kernel runtime proof report returned status " + [string]$kernelRuntimeProof.overallStatus + "."
            }
        )
    )
}

Write-NPDevJsonFile $ReportPath $gateReport

if ([string]$gateReport.overallStatus -eq "passed") {
    Write-NPDevOk "NPDevKernel gate passed."
    return
}

Write-NPDevWarn "NPDevKernel gate failed."
throw "NPDevKernel gate failed."
