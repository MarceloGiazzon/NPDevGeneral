[CmdletBinding()]
param(
    [string]$WorkspaceRoot = "",
    [string]$RunId = "",
    [string]$ReportPath = "",
    [string]$KernelRuntimeProofReportPath = "",
    [switch]$PassThru
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

. (Join-Path $PSScriptRoot "prioritized-control-common.ps1")

$WorkspaceRoot = Resolve-MaturityWorkspaceRoot -WorkspaceRoot $WorkspaceRoot -ScriptRoot $PSScriptRoot
$RunId = Resolve-NPDevRunId $RunId "b7-kernel-adapter-strict-mode-control"
$ReportPath = Resolve-PrioritizedControlReportPath -WorkspaceRoot $WorkspaceRoot -ReportPath $ReportPath -DefaultRelativePath "scripts\reports\out\prioritized-b7-kernel-adapter-strict-mode-report.json"
$KernelRuntimeProofReportPath = if ([string]::IsNullOrWhiteSpace($KernelRuntimeProofReportPath)) {
    Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\reports\out\kernel-runtime-proof-report.json"
}
else {
    Normalize-NPDevPath $KernelRuntimeProofReportPath
}

$schema = Test-MaturityReportSchema -PathValue $KernelRuntimeProofReportPath -RequiredProperties @(
    "generatedAt",
    "runId",
    "overallStatus",
    "policyPath",
    "mixedAdapterProof",
    "strictDefaultProof",
    "startupFailureProof",
    "compatibilityMatrix",
    "checks",
    "summary"
)
$reportDoc = if ($schema.exists -and [string]::IsNullOrWhiteSpace([string]$schema.parseError)) { Read-MaturityJsonFile $KernelRuntimeProofReportPath } else { $null }
$mixedAdapterProof = if ($null -eq $reportDoc) { $null } else { $reportDoc.mixedAdapterProof }
$strictDefaultProof = if ($null -eq $reportDoc) { $null } else { $reportDoc.strictDefaultProof }
$startupFailureProof = if ($null -eq $reportDoc) { $null } else { $reportDoc.startupFailureProof }
$compatibilityMatrix = if ($null -eq $reportDoc) { @() } else { @($reportDoc.compatibilityMatrix) }

$checks = @(
    (New-MaturityCheck -Name "kernel-runtime-proof-report" -Status $(if ($schema.valid) { "passed" } else { "failed" }) -Expectation "The kernel/runtime proof report must exist and expose the exact Bucket 2 fields." -Summary $(if ($schema.valid) { "The kernel/runtime proof report is readable and exposes the expected fields." } else { "The kernel/runtime proof report is missing or does not expose the expected fields." }) -Data @{ path = Get-PrioritizedControlEvidencePath -WorkspaceRoot $WorkspaceRoot -PathValue $KernelRuntimeProofReportPath; missingProperties = $schema.missingProperties; parseError = $schema.parseError })
    (New-MaturityCheck -Name "kernel-runtime-proof-current" -Status $(if ($null -ne $reportDoc -and [string]$reportDoc.overallStatus -eq "passed") { "passed" } else { "failed" }) -Expectation "The official kernel/runtime proof report must currently pass." -Summary $(if ($null -ne $reportDoc -and [string]$reportDoc.overallStatus -eq "passed") { "The kernel/runtime proof report is green." } else { "The kernel/runtime proof report is missing or failing." }) -Data @{ overallStatus = if ($null -eq $reportDoc) { $null } else { [string]$reportDoc.overallStatus } })
    (New-MaturityCheck -Name "mixed-adapter-proof" -Status $(if ($null -ne $mixedAdapterProof -and [string]$mixedAdapterProof.status -eq "passed" -and $mixedAdapterProof.junit.exists -and $mixedAdapterProof.junit.passed) { "passed" } else { "failed" }) -Expectation "The heterogeneous adapter proof case must pass with the policy-mapped adapter stack." -Summary $(if ($null -ne $mixedAdapterProof -and [string]$mixedAdapterProof.status -eq "passed" -and $mixedAdapterProof.junit.exists -and $mixedAdapterProof.junit.passed) { "The heterogeneous adapter proof case passed." } else { "The heterogeneous adapter proof case is missing or failing." }) -Data @{ proof = $mixedAdapterProof })
    (New-MaturityCheck -Name "strict-default-proof" -Status $(if ($null -ne $strictDefaultProof -and [string]$strictDefaultProof.status -eq "passed") { "passed" } else { "failed" }) -Expectation "RuntimeHost defaults must keep strict execution enabled on supported-core." -Summary $(if ($null -ne $strictDefaultProof -and [string]$strictDefaultProof.status -eq "passed") { "RuntimeHost defaults still prove governed strict execution." } else { "RuntimeHost defaults no longer prove governed strict execution." }) -Data @{ proof = $strictDefaultProof })
    (New-MaturityCheck -Name "startup-failure-proof" -Status $(if ($null -ne $startupFailureProof -and [string]$startupFailureProof.status -eq "passed" -and $startupFailureProof.junit.exists -and $startupFailureProof.junit.passed) { "passed" } else { "failed" }) -Expectation "Strict execution must prove the governed startup failure scenarios." -Summary $(if ($null -ne $startupFailureProof -and [string]$startupFailureProof.status -eq "passed" -and $startupFailureProof.junit.exists -and $startupFailureProof.junit.passed) { "Governed startup failure scenarios remain covered." } else { "Governed startup failure coverage is missing or failing." }) -Data @{ proof = $startupFailureProof })
    (New-MaturityCheck -Name "compatibility-matrix-present" -Status $(if ($compatibilityMatrix.Count -gt 0) { "passed" } else { "failed" }) -Expectation "The kernel/runtime proof must publish an explicit adapter compatibility matrix." -Summary $(if ($compatibilityMatrix.Count -gt 0) { "The adapter compatibility matrix is present." } else { "The adapter compatibility matrix is missing." }) -Data @{ compatibilityMatrix = $compatibilityMatrix })
)

$report = Write-PrioritizedControlReport `
    -WorkspaceRoot $WorkspaceRoot `
    -RunId $RunId `
    -ScriptPath $PSCommandPath `
    -Bucket "B2" `
    -ControlId "B7-KERNEL-ADAPTER-STRICT-MODE" `
    -ReportPath $ReportPath `
    -EvidencePaths @(Get-PrioritizedControlEvidencePath -WorkspaceRoot $WorkspaceRoot -PathValue $KernelRuntimeProofReportPath) `
    -Checks $checks `
    -Extra @{ kernelRuntimeProofReportPath = Get-PrioritizedControlEvidencePath -WorkspaceRoot $WorkspaceRoot -PathValue $KernelRuntimeProofReportPath }

Complete-PrioritizedControlScript -Report $report -PassThru:$PassThru
