[CmdletBinding()]
param(
    [string]$WorkspaceRoot = "",
    [string]$RunId = "",
    [string]$ReportPath = "",
    [string]$PolicyPath = "",
    [string]$GeneratorGateReportPath = "",
    [string]$DeterministicGenerationReportPath = "",
    [string]$SampleId = "",
    [switch]$PassThru
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

. (Join-Path $PSScriptRoot "bucket2-report-common.ps1")

$WorkspaceRoot = Initialize-Bucket2Workspace -WorkspaceRoot $WorkspaceRoot -ScriptRoot $PSScriptRoot
$RunId = Resolve-NPDevRunId $RunId "generator-governance"
$ReportPath = if ([string]::IsNullOrWhiteSpace($ReportPath)) { Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\reports\out\generator-governance-report.json" } else { Normalize-NPDevPath $ReportPath }
$PolicyPath = if ([string]::IsNullOrWhiteSpace($PolicyPath)) { Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\policy\generator-determinism-policy.json" } else { Normalize-NPDevPath $PolicyPath }
$GeneratorGateReportPath = if ([string]::IsNullOrWhiteSpace($GeneratorGateReportPath)) { Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\reports\out\generator-gate-report.json" } else { Normalize-NPDevPath $GeneratorGateReportPath }
$DeterministicGenerationReportPath = if ([string]::IsNullOrWhiteSpace($DeterministicGenerationReportPath)) { Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\reports\out\deterministic-generation-report.json" } else { Normalize-NPDevPath $DeterministicGenerationReportPath }

$policy = Read-Bucket2JsonFile $PolicyPath
if ([string]::IsNullOrWhiteSpace($SampleId) -and $null -ne $policy -and $null -ne $policy.determinism) {
    $SampleId = [string]$policy.determinism.requiredSampleId
}
if ([string]::IsNullOrWhiteSpace($SampleId)) {
    $SampleId = "simple-contact-intake"
}

$generatorGateReport = Read-Bucket2JsonFile $GeneratorGateReportPath
$deterministicGenerationReport = Read-Bucket2JsonFile $DeterministicGenerationReportPath
$sampleOutputRoot = Resolve-NPDevWorkspacePath $WorkspaceRoot ("NPDevSamples\" + $SampleId + "\Output")
$generationMarkerPath = Join-Path $sampleOutputRoot "Reports\generation-run.json"
$artifactSupportRoot = Join-Path $sampleOutputRoot "ArtifactNP\src\main\resources\npdev\support"
$appGeneratedSupportRoot = Join-Path $sampleOutputRoot "App\npdev-generated\src\main\resources\npdev\support"
$appCanonicalSupportRoot = Join-Path $sampleOutputRoot "App\src\main\resources\npdev\support"
$baselinePath = Join-Path $sampleOutputRoot "App\src\main\resources\npdev\model-diff-baseline.json"
$canonicalRelativePath = [string]$policy.migrationRisk.canonicalRelativePath

$migrationRiskReport = [pscustomobject]@{
    generatedAt = (Get-Date).ToString("o")
    sampleId = $SampleId
    baselinePath = if (Test-Path -LiteralPath $baselinePath -PathType Leaf) { Get-Bucket2RelativePath $WorkspaceRoot $baselinePath } else { $null }
    comparisonAvailable = (Test-Path -LiteralPath $baselinePath -PathType Leaf)
    migrationRiskStatus = if (Test-Path -LiteralPath $baselinePath -PathType Leaf) { "baseline-present" } else { "not-applicable" }
    canonicalRelativePath = $canonicalRelativePath
}

foreach ($supportRoot in @($artifactSupportRoot, $appGeneratedSupportRoot, $appCanonicalSupportRoot) | Select-Object -Unique) {
    if (-not (Test-Path -LiteralPath $supportRoot -PathType Container)) {
        continue
    }
    Write-NPDevJsonFile (Join-Path $supportRoot "migration-risk-report.json") $migrationRiskReport
}

$migrationRiskPaths = @(
    if (Test-Path -LiteralPath (Join-Path $artifactSupportRoot "migration-risk-report.json") -PathType Leaf) {
        Get-Bucket2RelativePath $WorkspaceRoot (Join-Path $artifactSupportRoot "migration-risk-report.json")
    }
    if (Test-Path -LiteralPath (Join-Path $appGeneratedSupportRoot "migration-risk-report.json") -PathType Leaf) {
        Get-Bucket2RelativePath $WorkspaceRoot (Join-Path $appGeneratedSupportRoot "migration-risk-report.json")
    }
    if (Test-Path -LiteralPath (Join-Path $appCanonicalSupportRoot "migration-risk-report.json") -PathType Leaf) {
        Get-Bucket2RelativePath $WorkspaceRoot (Join-Path $appCanonicalSupportRoot "migration-risk-report.json")
    }
)

$invalidExclusions = @(
    if ($null -ne $policy) {
        @($policy.exclusions | Where-Object {
                [string]::IsNullOrWhiteSpace([string]$_.id) -or
                [string]::IsNullOrWhiteSpace([string]$_.justification)
            })
    }
)

$checks = @(
    (New-NPDevCheckResult -Name "generator-determinism-policy" -Status $(if ($null -ne $policy -and $null -ne $policy.determinism -and $null -ne $policy.migrationRisk) { "passed" } else { "failed" }) -Summary $(if ($null -ne $policy -and $null -ne $policy.determinism -and $null -ne $policy.migrationRisk) { "Generator determinism policy is readable." } else { "Generator determinism policy is missing or invalid." }) -Data ([pscustomobject]@{ policyPath = Get-Bucket2RelativePath $WorkspaceRoot $PolicyPath }))
    (New-NPDevCheckResult -Name "generator-gate-current" -Status $(if ($null -ne $generatorGateReport -and [string]$generatorGateReport.overallStatus -eq "passed") { "passed" } else { "failed" }) -Summary $(if ($null -ne $generatorGateReport -and [string]$generatorGateReport.overallStatus -eq "passed") { "Generator gate is currently green." } else { "Generator gate evidence is missing or failing." }) -Data ([pscustomobject]@{ reportPath = Get-Bucket2RelativePath $WorkspaceRoot $GeneratorGateReportPath; overallStatus = if ($null -eq $generatorGateReport) { $null } else { [string]$generatorGateReport.overallStatus } }))
    (New-NPDevCheckResult -Name "determinism-evidence-current" -Status $(if ($null -ne $deterministicGenerationReport -and [string]$deterministicGenerationReport.overallStatus -eq "passed") { "passed" } else { "failed" }) -Summary $(if ($null -ne $deterministicGenerationReport -and [string]$deterministicGenerationReport.overallStatus -eq "passed") { "Deterministic generation evidence is current and green." } else { "Deterministic generation evidence is missing or failing." }) -Data ([pscustomobject]@{ reportPath = Get-Bucket2RelativePath $WorkspaceRoot $DeterministicGenerationReportPath; overallStatus = if ($null -eq $deterministicGenerationReport) { $null } else { [string]$deterministicGenerationReport.overallStatus } }))
    (New-NPDevCheckResult -Name "determinism-exclusions-governed" -Status $(if ($invalidExclusions.Count -eq 0) { "passed" } else { "failed" }) -Summary $(if ($invalidExclusions.Count -eq 0) { "Generator determinism exclusions are empty or fully justified." } else { "Generator determinism exclusions are missing ids or justifications." }) -Data ([pscustomobject]@{ exclusions = if ($null -eq $policy) { @() } else { @($policy.exclusions) }; invalidExclusions = @($invalidExclusions) }))
    (New-NPDevCheckResult -Name "migration-risk-output-wired" -Status $(if ($migrationRiskPaths.Count -gt 0) { "passed" } else { "failed" }) -Summary $(if ($migrationRiskPaths.Count -gt 0) { "Migration-risk output is present in generated support assets." } else { "Migration-risk output is missing from generated support assets." }) -Data ([pscustomobject]@{ generationMarkerPath = Get-Bucket2RelativePath $WorkspaceRoot $generationMarkerPath; migrationRiskPaths = @($migrationRiskPaths); baselineExists = (Test-Path -LiteralPath $baselinePath -PathType Leaf) }))
)

$report = [pscustomobject]@{
    generatedAt = (Get-Date).ToString("o")
    runId = $RunId
    scriptPath = Get-NPDevWorkspaceRelativePath $WorkspaceRoot $PSCommandPath
    workspaceRoot = $WorkspaceRoot
    overallStatus = Get-Bucket2OverallStatus $checks
    policyPath = Get-Bucket2RelativePath $WorkspaceRoot $PolicyPath
    sampleId = $SampleId
    determinism = [pscustomobject]@{
        reportPath = Get-Bucket2RelativePath $WorkspaceRoot $DeterministicGenerationReportPath
        status = if ($null -eq $deterministicGenerationReport) { "failed" } else { [string]$deterministicGenerationReport.overallStatus }
        exclusionCount = if ($null -eq $policy) { 0 } else { @($policy.exclusions).Count }
    }
    migrationRisk = [pscustomobject]@{
        generationMarkerPath = Get-Bucket2RelativePath $WorkspaceRoot $generationMarkerPath
        baselinePath = if (Test-Path -LiteralPath $baselinePath -PathType Leaf) { Get-Bucket2RelativePath $WorkspaceRoot $baselinePath } else { $null }
        reportPaths = @($migrationRiskPaths)
        canonicalRelativePath = $canonicalRelativePath
        comparisonAvailable = (Test-Path -LiteralPath $baselinePath -PathType Leaf)
        status = [string]$migrationRiskReport.migrationRiskStatus
        canonicalOutput = if (Test-Path -LiteralPath (Join-Path $appCanonicalSupportRoot "migration-risk-report.json") -PathType Leaf) {
            Get-Bucket2RelativePath $WorkspaceRoot (Join-Path $appCanonicalSupportRoot "migration-risk-report.json")
        } elseif ($migrationRiskPaths.Count -gt 0) {
            $migrationRiskPaths[0]
        } else {
            $null
        }
    }
    checks = $checks
    summary = Get-Bucket2Summary $checks
}
Write-NPDevJsonFile $ReportPath $report

if ($PassThru) {
    return $report
}

if ($report.overallStatus -eq "passed") {
    Write-NPDevOk "Generator governance report generated."
    return
}

Write-NPDevWarn "Generator governance report failed."
throw "Generator governance report failed."
