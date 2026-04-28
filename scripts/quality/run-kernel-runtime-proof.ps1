[CmdletBinding()]
param(
    [string]$WorkspaceRoot = "",
    [string]$RunId = "",
    [string]$ReportPath = "",
    [string]$PolicyPath = "",
    [string]$KernelGateReportPath = "",
    [string]$MixedAdapterTestReportPath = "",
    [string]$StrictValidatorTestReportPath = "",
    [string]$StrictValidatorSourcePath = "",
    [string]$DefaultPropertiesPath = "",
    [switch]$PassThru
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

. (Join-Path $PSScriptRoot "bucket2-report-common.ps1")

$WorkspaceRoot = Initialize-Bucket2Workspace -WorkspaceRoot $WorkspaceRoot -ScriptRoot $PSScriptRoot
$RunId = Resolve-NPDevRunId $RunId "kernel-runtime-proof"
$ReportPath = if ([string]::IsNullOrWhiteSpace($ReportPath)) {
    Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\reports\out\kernel-runtime-proof-report.json"
}
else {
    Normalize-NPDevPath $ReportPath
}
$PolicyPath = if ([string]::IsNullOrWhiteSpace($PolicyPath)) {
    Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\policy\kernel-adapter-compatibility-matrix.json"
}
else {
    Normalize-NPDevPath $PolicyPath
}
$KernelGateReportPath = if ([string]::IsNullOrWhiteSpace($KernelGateReportPath)) {
    Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\reports\out\kernel-gate-report.json"
}
else {
    Normalize-NPDevPath $KernelGateReportPath
}
$MixedAdapterTestReportPath = if ([string]::IsNullOrWhiteSpace($MixedAdapterTestReportPath)) {
    Resolve-NPDevWorkspacePath $WorkspaceRoot "NPDevKernel\kernel\build\test-results\test\TEST-com.npdev.kernel.MixedAdapterExecutionPathTest.xml"
}
else {
    Normalize-NPDevPath $MixedAdapterTestReportPath
}
$StrictValidatorTestReportPath = if ([string]::IsNullOrWhiteSpace($StrictValidatorTestReportPath)) {
    Resolve-NPDevWorkspacePath $WorkspaceRoot "NPDevKernel\adapters\runtime-validation\build\test-results\test\TEST-com.npdev.adapters.runtime.validation.StrictExecutionValidatorTest.xml"
}
else {
    Normalize-NPDevPath $StrictValidatorTestReportPath
}
$StrictValidatorSourcePath = if ([string]::IsNullOrWhiteSpace($StrictValidatorSourcePath)) {
    Resolve-NPDevWorkspacePath $WorkspaceRoot "NPDevKernel\adapters\runtime-validation\src\test\java\com\npdev\adapters\runtime\validation\StrictExecutionValidatorTest.java"
}
else {
    Normalize-NPDevPath $StrictValidatorSourcePath
}
$DefaultPropertiesPath = if ([string]::IsNullOrWhiteSpace($DefaultPropertiesPath)) {
    Resolve-NPDevWorkspacePath $WorkspaceRoot "NPDevRuntimeHost\src\main\resources\application-default.properties"
}
else {
    Normalize-NPDevPath $DefaultPropertiesPath
}

$policy = Read-Bucket2JsonFile $PolicyPath
$kernelGateReport = Read-Bucket2JsonFile $KernelGateReportPath
$mixedAdapterSummary = Get-Bucket2JUnitSummary $MixedAdapterTestReportPath
$strictValidatorSummary = Get-Bucket2JUnitSummary $StrictValidatorTestReportPath
$properties = if (Test-Path -LiteralPath $DefaultPropertiesPath -PathType Leaf) {
    Get-Bucket2PropertiesMap $DefaultPropertiesPath
}
else {
    @{}
}

$strictSourcePatterns = @(
    "failsWhenGovernedModeUsesNonSupportedCoreSurfaceProfile",
    "failsWhenGovernedModeDoesNotEnforceSupportedRuntimeSurface",
    "failsWhenGovernedModeDisablesStrictExecution"
)
$strictSourceMissingPatterns = @(
    if (Test-Path -LiteralPath $StrictValidatorSourcePath -PathType Leaf) {
    Get-Bucket2MissingPatterns -PathValue $StrictValidatorSourcePath -Patterns $strictSourcePatterns
}
else {
    $strictSourcePatterns
}
)

$checks = @(
    (New-NPDevCheckResult -Name "compatibility-policy" -Status $(if ($null -ne $policy -and $null -ne $policy.proofCase -and $null -ne $policy.strictDefaults -and $null -ne $policy.compatibilityMatrix) { "passed" } else { "failed" }) -Summary $(if ($null -ne $policy -and $null -ne $policy.proofCase -and $null -ne $policy.strictDefaults -and $null -ne $policy.compatibilityMatrix) { "Kernel adapter compatibility matrix is readable." } else { "Kernel adapter compatibility matrix is missing or invalid." }) -Data ([pscustomobject]@{ policyPath = Get-Bucket2RelativePath $WorkspaceRoot $PolicyPath }))
    (New-NPDevCheckResult -Name "kernel-gate-current" -Status $(if ($null -ne $kernelGateReport -and [string]$kernelGateReport.overallStatus -eq "passed") { "passed" } else { "failed" }) -Summary $(if ($null -ne $kernelGateReport -and [string]$kernelGateReport.overallStatus -eq "passed") { "Kernel gate is currently green." } else { "Kernel gate evidence is missing or failing." }) -Data ([pscustomobject]@{ reportPath = Get-Bucket2RelativePath $WorkspaceRoot $KernelGateReportPath; overallStatus = if ($null -eq $kernelGateReport) { $null } else { [string]$kernelGateReport.overallStatus } }))
    (New-NPDevCheckResult -Name "mixed-adapter-proof" -Status $(if ($mixedAdapterSummary.passed) { "passed" } else { "failed" }) -Summary $(if ($mixedAdapterSummary.passed) { "The mixed adapter proof case passed with persistence-postgres + events-inproc + tracing-inproc." } else { "The mixed adapter proof case is missing or failing." }) -Data ([pscustomobject]@{ junit = $mixedAdapterSummary; requiredAdapters = if ($null -eq $policy) { $null } else { $policy.proofCase.requiredAdapters } }))
    (New-NPDevCheckResult -Name "strict-defaults" -Status $(if (($properties[[string]$policy.strictDefaults.surfaceProfileProperty] -eq [string]$policy.strictDefaults.surfaceProfileValue) -and ([string]$properties[[string]$policy.strictDefaults.strictExecutionProperty]).Contains([string]$policy.strictDefaults.strictExecutionExpectedFragment)) { "passed" } else { "failed" }) -Summary $(if (($properties[[string]$policy.strictDefaults.surfaceProfileProperty] -eq [string]$policy.strictDefaults.surfaceProfileValue) -and ([string]$properties[[string]$policy.strictDefaults.strictExecutionProperty]).Contains([string]$policy.strictDefaults.strictExecutionExpectedFragment)) { "RuntimeHost defaults keep strict execution enabled on supported-core." } else { "RuntimeHost defaults no longer prove strict execution on supported-core." }) -Data ([pscustomobject]@{ propertiesPath = Get-Bucket2RelativePath $WorkspaceRoot $DefaultPropertiesPath; surfaceProfile = $properties[[string]$policy.strictDefaults.surfaceProfileProperty]; strictExecution = $properties[[string]$policy.strictDefaults.strictExecutionProperty] }))
    (New-NPDevCheckResult -Name "strict-startup-failure-proof" -Status $(if ($strictValidatorSummary.passed -and $strictSourceMissingPatterns.Count -eq 0) { "passed" } else { "failed" }) -Summary $(if ($strictValidatorSummary.passed -and $strictSourceMissingPatterns.Count -eq 0) { "Strict execution validator coverage proves governed startup failure scenarios." } else { "Strict execution validator coverage is missing or incomplete." }) -Data ([pscustomobject]@{ junit = $strictValidatorSummary; sourcePath = Get-Bucket2RelativePath $WorkspaceRoot $StrictValidatorSourcePath; missingPatterns = @($strictSourceMissingPatterns) }))
)

$report = [pscustomobject]@{
    generatedAt = (Get-Date).ToString("o")
    runId = $RunId
    scriptPath = Get-NPDevWorkspaceRelativePath $WorkspaceRoot $PSCommandPath
    workspaceRoot = $WorkspaceRoot
    overallStatus = Get-Bucket2OverallStatus $checks
    policyPath = Get-Bucket2RelativePath $WorkspaceRoot $PolicyPath
    kernelGateReportPath = Get-Bucket2RelativePath $WorkspaceRoot $KernelGateReportPath
    mixedAdapterProof = [pscustomobject]@{
        testReportPath = Get-Bucket2RelativePath $WorkspaceRoot $MixedAdapterTestReportPath
        status = if ($mixedAdapterSummary.passed) { "passed" } else { "failed" }
        requiredAdapters = if ($null -eq $policy) { $null } else { $policy.proofCase.requiredAdapters }
        junit = $mixedAdapterSummary
    }
    strictDefaultProof = [pscustomobject]@{
        propertiesPath = Get-Bucket2RelativePath $WorkspaceRoot $DefaultPropertiesPath
        surfaceProfile = $properties[[string]$policy.strictDefaults.surfaceProfileProperty]
        strictExecution = $properties[[string]$policy.strictDefaults.strictExecutionProperty]
        status = if (($properties[[string]$policy.strictDefaults.surfaceProfileProperty] -eq [string]$policy.strictDefaults.surfaceProfileValue) -and ([string]$properties[[string]$policy.strictDefaults.strictExecutionProperty]).Contains([string]$policy.strictDefaults.strictExecutionExpectedFragment)) { "passed" } else { "failed" }
    }
    startupFailureProof = [pscustomobject]@{
        testReportPath = Get-Bucket2RelativePath $WorkspaceRoot $StrictValidatorTestReportPath
        sourcePath = Get-Bucket2RelativePath $WorkspaceRoot $StrictValidatorSourcePath
        status = if ($strictValidatorSummary.passed -and $strictSourceMissingPatterns.Count -eq 0) { "passed" } else { "failed" }
        junit = $strictValidatorSummary
        missingPatterns = @($strictSourceMissingPatterns)
    }
    compatibilityMatrix = if ($null -eq $policy) { @() } else { @($policy.compatibilityMatrix) }
    checks = $checks
    summary = Get-Bucket2Summary $checks
}
Write-NPDevJsonFile $ReportPath $report

if ($PassThru) {
    return $report
}

if ($report.overallStatus -eq "passed") {
    Write-NPDevOk "Kernel/runtime proof report generated."
    return
}

Write-NPDevWarn "Kernel/runtime proof report failed."
throw "Kernel/runtime proof report failed."
