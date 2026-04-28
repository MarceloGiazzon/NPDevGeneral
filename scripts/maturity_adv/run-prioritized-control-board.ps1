[CmdletBinding()]
param(
    [string]$WorkspaceRoot = "",
    [string]$RunId = "",
    [ValidateSet("B1", "B2", "B3")]
    [string[]]$Buckets = @("B1"),
    [string]$ReportPath = "",
    [switch]$PassThru
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

. (Join-Path $PSScriptRoot "prioritized-control-common.ps1")

$WorkspaceRoot = Resolve-MaturityWorkspaceRoot -WorkspaceRoot $WorkspaceRoot -ScriptRoot $PSScriptRoot
$RunId = Resolve-NPDevRunId $RunId "prioritized-control-board"
$ReportPath = Resolve-PrioritizedControlReportPath -WorkspaceRoot $WorkspaceRoot -ReportPath $ReportPath -DefaultRelativePath "scripts\reports\out\prioritized-control-board-report.json"

$selectedBuckets = @($Buckets | Select-Object -Unique)
$controlDefinitions = [System.Collections.Generic.List[object]]::new()
if ("B1" -in $selectedBuckets) {
    [void]$controlDefinitions.Add([pscustomobject]@{
            bucket = "B1"
            controlId = "B1-PROVENANCE"
            runIdSuffix = "b1-provenance"
            scriptPath = Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\maturity_adv\check-b1-provenance-control.ps1"
            reportPath = Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\reports\out\prioritized-b1-provenance-report.json"
        })
    [void]$controlDefinitions.Add([pscustomobject]@{
            bucket = "B1"
            controlId = "B2-GOVERNANCE-TRUTH-CHAIN"
            runIdSuffix = "b2-governance"
            scriptPath = Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\maturity_adv\check-b2-governance-truth-chain-control.ps1"
            reportPath = Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\reports\out\prioritized-b2-governance-truth-chain-report.json"
        })
    [void]$controlDefinitions.Add([pscustomobject]@{
            bucket = "B1"
            controlId = "B3-EVIDENCE-BUNDLE-DIAGNOSTICS"
            runIdSuffix = "b3-evidence"
            scriptPath = Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\maturity_adv\check-b3-evidence-bundle-diagnostics-control.ps1"
            reportPath = Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\reports\out\prioritized-b3-evidence-bundle-report.json"
        })
    [void]$controlDefinitions.Add([pscustomobject]@{
            bucket = "B1"
            controlId = "B4-PACKAGING-METADATA"
            runIdSuffix = "b4-packaging"
            scriptPath = Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\maturity_adv\check-b4-packaging-metadata-control.ps1"
            reportPath = Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\reports\out\prioritized-b4-packaging-metadata-report.json"
        })
    [void]$controlDefinitions.Add([pscustomobject]@{
            bucket = "B1"
            controlId = "B5-RUNTIMEHOST-GENERATOR-CONTRACT"
            runIdSuffix = "b5-runtimehost-generator"
            scriptPath = Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\maturity_adv\check-b5-runtimehost-generator-contract-control.ps1"
            reportPath = Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\reports\out\prioritized-b5-runtimehost-generator-contract-report.json"
        })
}
if ("B2" -in $selectedBuckets) {
    [void]$controlDefinitions.Add([pscustomobject]@{
            bucket = "B2"
            controlId = "B6-EDITOR-BOUNDARY-ENFORCEMENT"
            runIdSuffix = "b6-editor-boundary"
            scriptPath = Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\maturity_adv\check-b6-editor-boundary-enforcement-control.ps1"
            reportPath = Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\reports\out\prioritized-b6-editor-boundary-enforcement-report.json"
        })
    [void]$controlDefinitions.Add([pscustomobject]@{
            bucket = "B2"
            controlId = "B7-KERNEL-ADAPTER-STRICT-MODE"
            runIdSuffix = "b7-kernel-strict-mode"
            scriptPath = Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\maturity_adv\check-b7-kernel-adapter-strict-mode-control.ps1"
            reportPath = Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\reports\out\prioritized-b7-kernel-adapter-strict-mode-report.json"
        })
    [void]$controlDefinitions.Add([pscustomobject]@{
            bucket = "B2"
            controlId = "B8-OBSERVABILITY-HEALTH-HARDENING"
            runIdSuffix = "b8-observability"
            scriptPath = Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\maturity_adv\check-b8-observability-health-hardening-control.ps1"
            reportPath = Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\reports\out\prioritized-b8-observability-health-hardening-report.json"
        })
    [void]$controlDefinitions.Add([pscustomobject]@{
            bucket = "B2"
            controlId = "B9-SECURITY-CONSISTENCY"
            runIdSuffix = "b9-security"
            scriptPath = Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\maturity_adv\check-b9-security-consistency-control.ps1"
            reportPath = Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\reports\out\prioritized-b9-security-consistency-report.json"
        })
    [void]$controlDefinitions.Add([pscustomobject]@{
            bucket = "B2"
            controlId = "B10-GENERATOR-DETERMINISM-MIGRATION-RISK"
            runIdSuffix = "b10-generator-governance"
            scriptPath = Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\maturity_adv\check-b10-generator-determinism-migration-risk-control.ps1"
            reportPath = Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\reports\out\prioritized-b10-generator-determinism-migration-risk-report.json"
        })
    [void]$controlDefinitions.Add([pscustomobject]@{
            bucket = "B2"
            controlId = "B11-AI-DETERMINISM-BASELINE-GOVERNANCE"
            runIdSuffix = "b11-ai-governance"
            scriptPath = Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\maturity_adv\check-b11-ai-determinism-baseline-governance-control.ps1"
            reportPath = Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\reports\out\prioritized-b11-ai-determinism-baseline-governance-report.json"
        })
}
if ("B3" -in $selectedBuckets) {
    [void]$controlDefinitions.Add([pscustomobject]@{
            bucket = "B3"
            controlId = "B12-SAMPLE-DIAGNOSTICS-ENRICHMENT"
            runIdSuffix = "b12-sample-diagnostics"
            scriptPath = Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\maturity_adv\check-b12-sample-diagnostics-enrichment-control.ps1"
            reportPath = Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\reports\out\prioritized-b12-sample-diagnostics-enrichment-report.json"
        })
    [void]$controlDefinitions.Add([pscustomobject]@{
            bucket = "B3"
            controlId = "B13-CONTRACT-SCHEMA-MIRROR-SIMPLIFICATION"
            runIdSuffix = "b13-contract-schema"
            scriptPath = Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\maturity_adv\check-b13-contract-schema-mirror-simplification-control.ps1"
            reportPath = Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\reports\out\prioritized-b13-contract-schema-mirror-simplification-report.json"
        })
    [void]$controlDefinitions.Add([pscustomobject]@{
            bucket = "B3"
            controlId = "B14-CROSS-PROJECT-VOCABULARY-BUILD-BOUNDARY-POLISH"
            runIdSuffix = "b14-cross-project-boundary"
            scriptPath = Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\maturity_adv\check-b14-cross-project-vocabulary-build-boundary-polish-control.ps1"
            reportPath = Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\reports\out\prioritized-b14-cross-project-vocabulary-build-boundary-polish-report.json"
        })
    [void]$controlDefinitions.Add([pscustomobject]@{
            bucket = "B3"
            controlId = "B15-DOCUMENTATION-DIGEST-GOVERNANCE"
            runIdSuffix = "b15-documentation-digests"
            scriptPath = Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\maturity_adv\check-b15-documentation-digest-governance-control.ps1"
            reportPath = Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\reports\out\prioritized-b15-documentation-digest-governance-report.json"
        })
    [void]$controlDefinitions.Add([pscustomobject]@{
            bucket = "B3"
            controlId = "B16-SCRIPT-AUTOMATION-QUALITY-POLISH"
            runIdSuffix = "b16-script-automation"
            scriptPath = Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\maturity_adv\check-b16-script-automation-quality-polish-control.ps1"
            reportPath = Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\reports\out\prioritized-b16-script-automation-quality-polish-report.json"
        })
}

$controlReports = [System.Collections.Generic.List[object]]::new()
$controlEntries = [System.Collections.Generic.List[object]]::new()
foreach ($control in $controlDefinitions) {
    Write-NPDevInfo ("Running prioritized control " + $control.controlId)
    $controlRunId = $RunId + "-" + $control.runIdSuffix
    $controlReport = & $control.scriptPath `
        -WorkspaceRoot $WorkspaceRoot `
        -RunId $controlRunId `
        -ReportPath $control.reportPath `
        -PassThru
    [void]$controlReports.Add($controlReport)
    [void]$controlEntries.Add([pscustomobject]@{
            bucket = [string]$control.bucket
            controlId = [string]$control.controlId
            status = [string]$controlReport.overallStatus
            reportPath = Get-PrioritizedControlEvidencePath -WorkspaceRoot $WorkspaceRoot -PathValue $control.reportPath
            evidencePaths = @($controlReport.evidencePaths)
            generatedAt = [string]$controlReport.generatedAt
            controlRunId = [string]$controlReport.runId
        })
}

$failedControls = @($controlReports | Where-Object { $_.overallStatus -eq "failed" })
$warningControls = @($controlReports | Where-Object { $_.overallStatus -eq "warning" })
$overallStatus = if ($failedControls.Count -gt 0) {
    "failed"
}
elseif ($warningControls.Count -gt 0) {
    "warning"
}
else {
    "passed"
}

$suiteReport = [pscustomobject]@{
    generatedAt = (Get-Date).ToString("o")
    runId = $RunId
    scriptPath = Get-NPDevWorkspaceRelativePath $WorkspaceRoot $PSCommandPath
    workspaceRoot = $WorkspaceRoot
    overallStatus = $overallStatus
    buckets = $selectedBuckets
    summary = [pscustomobject]@{
        failed = $failedControls.Count
        warnings = $warningControls.Count
        passed = @($controlReports | Where-Object { $_.overallStatus -eq "passed" }).Count
        total = $controlReports.Count
    }
    controls = @($controlEntries)
}
Write-NPDevJsonFile $ReportPath $suiteReport

if ($PassThru) {
    return $suiteReport
}

if ($overallStatus -eq "passed") {
    Write-NPDevOk "Prioritized control board passed."
    return
}

if ($overallStatus -eq "warning") {
    Write-NPDevWarn "Prioritized control board completed with warnings."
    return
}

Write-NPDevWarn "Prioritized control board failed."
throw "Prioritized control board failed."
