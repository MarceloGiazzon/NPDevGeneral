[CmdletBinding()]
param(
    [string]$WorkspaceRoot = "",
    [string]$SampleId = "",
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
if ([string]::IsNullOrWhiteSpace($SampleId)) {
    $SampleId = Get-NPDevDefaultSampleId $WorkspaceRoot
}
$RunId = Resolve-NPDevRunId $RunId "hygiene-gate"

if ([string]::IsNullOrWhiteSpace($ReportPath)) {
    $ReportPath = Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\reports\out\hygiene-gate-report.json"
}
else {
    $ReportPath = Normalize-NPDevPath $ReportPath
}

function Invoke-HygienePassThruCheck([string]$Label, [string]$ScriptPath) {
    Ensure-NPDevFile $ScriptPath ($Label + " script")
    Write-NPDevInfo ("Running hygiene check: " + $Label)
    return & $ScriptPath -WorkspaceRoot $WorkspaceRoot -PassThru
}

function Invoke-HygieneCommandCheck(
    [string]$Label,
    [string]$ScriptPath,
    [string]$CommandReportPath,
    [hashtable]$Parameters = @{}
) {
    Ensure-NPDevFile $ScriptPath ($Label + " script")
    Write-NPDevInfo ("Running hygiene check: " + $Label)

    $status = "passed"
    $summary = ($Label + " passed.")
    $data = $null
    try {
        & $ScriptPath @Parameters
        if (Test-Path -LiteralPath $CommandReportPath -PathType Leaf) {
            $commandReport = Get-Content -LiteralPath $CommandReportPath -Raw | ConvertFrom-Json
            $status = [string]$commandReport.overallStatus
            $summary = if ($status -eq "passed") {
                $summary
            }
            else {
                ($Label + " finished with status " + $status + ".")
            }
            $data = $commandReport
        }
    }
    catch {
        $status = "failed"
        $summary = $_.Exception.Message
        if (Test-Path -LiteralPath $CommandReportPath -PathType Leaf) {
            try {
                $data = Get-Content -LiteralPath $CommandReportPath -Raw | ConvertFrom-Json
            }
            catch {
                $data = $null
            }
        }
    }

    return New-NPDevCheckResult $Label $status $summary $data
}

$contractSurfaceScript = Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\hygiene\check-contract-surface-consistency.ps1"
$entityCanonicalScript = Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\hygiene\check-no-entity-canonical-surface.ps1"
$domainLeakScript = Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\hygiene\check-no-domain-leaks.ps1"
$rootBuildCouplingScript = Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\hygiene\check-no-root-build-coupling.ps1"
$deterministicGenerationScript = Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\hygiene\check-deterministic-generation.ps1"
$samplePresentationLabelsScript = Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\hygiene\check-sample-presentation-labels.ps1"
$stateZipDeterminismScript = Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\hygiene\check-statezip-determinism.ps1"
$sampleMirrorScript = Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\samples\sync-mirrored-samples.ps1"
$crossProjectBoundaryScript = Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\quality\run-cross-project-boundary-audit.ps1"
$documentationDigestGovernanceScript = Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\quality\run-documentation-digest-governance.ps1"
$scriptAutomationQualityScript = Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\quality\run-script-automation-quality.ps1"
$deterministicGenerationReportPath = Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\reports\out\deterministic-generation-report.json"
$samplePresentationLabelReportPath = Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\reports\out\sample-presentation-label-report.json"
$stateZipDeterminismReportPath = Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\reports\out\statezip-determinism-report.json"
$sampleMirrorReportPath = Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\reports\out\mirrored-sample-sync-report.json"
$crossProjectBoundaryReportPath = Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\reports\out\cross-project-boundary-report.json"
$documentationDigestGovernanceReportPath = Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\reports\out\documentation-digest-governance-report.json"
$scriptAutomationQualityReportPath = Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\reports\out\script-automation-quality-report.json"

# Documentation freshness coverage:
# README and PROJECT_DIGEST documentation checks are tracked through the hygiene gate so
# documentation drift can be evaluated alongside deterministic generation and sample evidence.

$results = @(
    (Invoke-HygienePassThruCheck "root-build-coupling" $rootBuildCouplingScript),
    (Invoke-HygienePassThruCheck "contract-surface-consistency" $contractSurfaceScript),
    (Invoke-HygienePassThruCheck "entity-canonical-surface" $entityCanonicalScript),
    (Invoke-HygienePassThruCheck "domain-leaks" $domainLeakScript),
    (Invoke-HygieneCommandCheck `
            -Label "deterministic-generation" `
            -ScriptPath $deterministicGenerationScript `
            -CommandReportPath $deterministicGenerationReportPath `
            -Parameters @{
                WorkspaceRoot = $WorkspaceRoot
                SampleId = $SampleId
                ReportPath = $deterministicGenerationReportPath
            }),
    (Invoke-HygieneCommandCheck `
            -Label "sample-presentation-labels" `
            -ScriptPath $samplePresentationLabelsScript `
            -CommandReportPath $samplePresentationLabelReportPath `
            -Parameters @{
                WorkspaceRoot = $WorkspaceRoot
                ReportPath = $samplePresentationLabelReportPath
            }),
    (Invoke-HygieneCommandCheck `
            -Label "cross-project-boundary" `
            -ScriptPath $crossProjectBoundaryScript `
            -CommandReportPath $crossProjectBoundaryReportPath `
            -Parameters @{
                WorkspaceRoot = $WorkspaceRoot
                ReportPath = $crossProjectBoundaryReportPath
            }),
    (Invoke-HygieneCommandCheck `
            -Label "documentation-digest-governance" `
            -ScriptPath $documentationDigestGovernanceScript `
            -CommandReportPath $documentationDigestGovernanceReportPath `
            -Parameters @{
                WorkspaceRoot = $WorkspaceRoot
                ReportPath = $documentationDigestGovernanceReportPath
            }),
    (Invoke-HygieneCommandCheck `
            -Label "script-automation-quality" `
            -ScriptPath $scriptAutomationQualityScript `
            -CommandReportPath $scriptAutomationQualityReportPath `
            -Parameters @{
                WorkspaceRoot = $WorkspaceRoot
                ReportPath = $scriptAutomationQualityReportPath
            }),
    (Invoke-HygieneCommandCheck `
            -Label "statezip-determinism" `
            -ScriptPath $stateZipDeterminismScript `
            -CommandReportPath $stateZipDeterminismReportPath `
            -Parameters @{
                WorkspaceRoot = $WorkspaceRoot
                ReportPath = $stateZipDeterminismReportPath
            }),
    (Invoke-HygieneCommandCheck `
            -Label "sample-mirror-drift" `
            -ScriptPath $sampleMirrorScript `
            -CommandReportPath $sampleMirrorReportPath `
            -Parameters @{
                WorkspaceRoot = $WorkspaceRoot
                CheckOnly = $true
                ReportPath = $sampleMirrorReportPath
            })
)

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
    sampleId = $SampleId
    overallStatus = $overallStatus
    checks = $results
    summary = [pscustomobject]@{
        failed = $failedChecks.Count
        warnings = $warningChecks.Count
        passed = @($results | Where-Object { $_.status -eq "passed" }).Count
    }
}
Write-NPDevJsonFile $ReportPath $report

if ($overallStatus -eq "passed") {
    Write-NPDevOk "Hygiene gate passed."
    return
}

if ($overallStatus -eq "warning") {
    Write-NPDevWarn "Hygiene gate completed with warnings."
    return
}

Write-NPDevWarn "Hygiene gate failed."
throw "Hygiene gate failed."
