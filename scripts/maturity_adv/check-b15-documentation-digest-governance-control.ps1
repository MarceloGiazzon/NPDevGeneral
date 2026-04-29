[CmdletBinding()]
param(
    [string]$WorkspaceRoot = "",
    [string]$RunId = "",
    [string]$ReportPath = "",
    [string]$DocumentationDigestGovernanceReportPath = "",
    [switch]$PassThru
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

. (Join-Path $PSScriptRoot "prioritized-control-common.ps1")

$WorkspaceRoot = Resolve-MaturityWorkspaceRoot -WorkspaceRoot $WorkspaceRoot -ScriptRoot $PSScriptRoot
$RunId = Resolve-NPDevRunId $RunId "b15-documentation-digest-governance-control"
$ReportPath = Resolve-PrioritizedControlReportPath -WorkspaceRoot $WorkspaceRoot -ReportPath $ReportPath -DefaultRelativePath "scripts\reports\out\prioritized-b15-documentation-digest-governance-report.json"
$DocumentationDigestGovernanceReportPath = if ([string]::IsNullOrWhiteSpace($DocumentationDigestGovernanceReportPath)) {
    Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\reports\out\documentation-digest-governance-report.json"
}
else {
    Normalize-NPDevPath $DocumentationDigestGovernanceReportPath
}

$reportSchema = Test-MaturityReportSchema -PathValue $DocumentationDigestGovernanceReportPath -RequiredProperties @(
    "generatedAt",
    "runId",
    "overallStatus",
    "digests",
    "checks",
    "summary"
)
$governanceReport = if ($reportSchema.valid) { Read-MaturityJsonFile $DocumentationDigestGovernanceReportPath } else { $null }

function Get-ControlCheckByName {
    param(
        [AllowNull()][object]$Report,
        [string]$Name
    )

    if ($null -eq $Report) {
        return $null
    }

    return ($Report.checks | Where-Object { [string]$_.name -eq $Name } | Select-Object -First 1)
}

$requiredProjectDigestsCheck = Get-ControlCheckByName -Report $governanceReport -Name "required-project-digests"
$generatedMigrationDigestsCheck = Get-ControlCheckByName -Report $governanceReport -Name "generated-sample-migration-digests"
$referencedPathIntegrityCheck = Get-ControlCheckByName -Report $governanceReport -Name "referenced-path-integrity"
$freshnessMarkerCheck = Get-ControlCheckByName -Report $governanceReport -Name "freshness-markers"

$governanceCurrentStatus = if ($null -eq $governanceReport) {
    "failed"
}
elseif ([string]$governanceReport.overallStatus -eq "warning") {
    "warning"
}
elseif ([string]$governanceReport.overallStatus -eq "passed") {
    "passed"
}
else {
    "failed"
}

$checks = @(
    (New-MaturityCheck -Name "documentation-digest-governance-report" -Status $(if ($reportSchema.valid) { "passed" } else { "failed" }) -Expectation "The documentation digest governance report must exist and expose digest coverage and freshness results." -Summary $(if ($reportSchema.valid) { "The documentation digest governance report is readable." } else { "The documentation digest governance report is missing or invalid." }) -Data @{ path = Get-PrioritizedControlEvidencePath -WorkspaceRoot $WorkspaceRoot -PathValue $DocumentationDigestGovernanceReportPath; missingProperties = $reportSchema.missingProperties; parseError = $reportSchema.parseError })
    (New-MaturityCheck -Name "documentation-governance-current" -Status $governanceCurrentStatus -Expectation "Documentation digest governance should pass, with explicit stale freshness markers remaining warning-only." -Summary $(if ($governanceCurrentStatus -eq "passed") { "Documentation digest governance is green." } elseif ($governanceCurrentStatus -eq "warning") { "Documentation digest governance is warning-only due to explicit freshness markers." } else { "Documentation digest governance is missing or failing." }) -Data @{ overallStatus = if ($null -eq $governanceReport) { $null } else { [string]$governanceReport.overallStatus } })
    (New-MaturityCheck -Name "required-project-digests-current" -Status $(if ($null -ne $requiredProjectDigestsCheck -and [string]$requiredProjectDigestsCheck.status -eq "passed") { "passed" } else { "failed" }) -Expectation "Required .npdev-root coverage must remain intact." -Summary $(if ($null -ne $requiredProjectDigestsCheck -and [string]$requiredProjectDigestsCheck.status -eq "passed") { "Required project digest coverage remains intact." } else { "One or more required .npdev-root files are missing." }) -Data @{ requiredProjectDigests = $requiredProjectDigestsCheck })
    (New-MaturityCheck -Name "generated-sample-migration-digests-current" -Status $(if ($null -ne $generatedMigrationDigestsCheck -and [string]$generatedMigrationDigestsCheck.status -eq "passed") { "passed" } else { "failed" }) -Expectation "Generated release samples must retain MIGRATION_DIGEST.md." -Summary $(if ($null -ne $generatedMigrationDigestsCheck -and [string]$generatedMigrationDigestsCheck.status -eq "passed") { "Generated release sample migration digests remain present." } else { "One or more generated release sample migration digests are missing." }) -Data @{ generatedMigrationDigests = $generatedMigrationDigestsCheck })
    (New-MaturityCheck -Name "referenced-path-integrity-current" -Status $(if ($null -ne $referencedPathIntegrityCheck -and [string]$referencedPathIntegrityCheck.status -eq "passed") { "passed" } else { "failed" }) -Expectation "Digest references must resolve to existing repo paths and entrypoints." -Summary $(if ($null -ne $referencedPathIntegrityCheck -and [string]$referencedPathIntegrityCheck.status -eq "passed") { "Digest references resolve to existing repo paths." } else { "One or more digest references resolve to missing repo paths." }) -Data @{ referencedPathIntegrity = $referencedPathIntegrityCheck })
    (New-MaturityCheck -Name "freshness-markers-warning-only" -Status $(if ($null -eq $freshnessMarkerCheck) { "failed" } elseif ([string]$freshnessMarkerCheck.status -eq "warning") { "warning" } elseif ([string]$freshnessMarkerCheck.status -eq "passed") { "passed" } else { "failed" }) -Expectation "Explicit stale freshness markers may warn, but they must not become hard failures in this bucket." -Summary $(if ($null -eq $freshnessMarkerCheck) { "Freshness marker evidence is missing." } elseif ([string]$freshnessMarkerCheck.status -eq "warning") { "Freshness markers are warning-only as intended." } elseif ([string]$freshnessMarkerCheck.status -eq "passed") { "Freshness markers are current or not declared." } else { "Freshness marker handling regressed." }) -Data @{ freshnessMarkers = $freshnessMarkerCheck })
)

$report = Write-PrioritizedControlReport `
    -WorkspaceRoot $WorkspaceRoot `
    -RunId $RunId `
    -ScriptPath $PSCommandPath `
    -Bucket "B3" `
    -ControlId "B15-DOCUMENTATION-DIGEST-GOVERNANCE" `
    -ReportPath $ReportPath `
    -EvidencePaths @(
        Get-PrioritizedControlEvidencePath -WorkspaceRoot $WorkspaceRoot -PathValue $DocumentationDigestGovernanceReportPath
    ) `
    -Checks $checks `
    -Extra @{
        documentationDigestGovernanceReportPath = Get-PrioritizedControlEvidencePath -WorkspaceRoot $WorkspaceRoot -PathValue $DocumentationDigestGovernanceReportPath
    }

Complete-PrioritizedControlScript -Report $report -PassThru:$PassThru

