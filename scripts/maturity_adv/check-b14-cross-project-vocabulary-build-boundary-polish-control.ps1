[CmdletBinding()]
param(
    [string]$WorkspaceRoot = "",
    [string]$RunId = "",
    [string]$ReportPath = "",
    [string]$CrossProjectBoundaryReportPath = "",
    [switch]$PassThru
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

. (Join-Path $PSScriptRoot "prioritized-control-common.ps1")

$WorkspaceRoot = Resolve-MaturityWorkspaceRoot -WorkspaceRoot $WorkspaceRoot -ScriptRoot $PSScriptRoot
$RunId = Resolve-NPDevRunId $RunId "b14-cross-project-vocabulary-build-boundary-polish-control"
$ReportPath = Resolve-PrioritizedControlReportPath -WorkspaceRoot $WorkspaceRoot -ReportPath $ReportPath -DefaultRelativePath "scripts\reports\out\prioritized-b14-cross-project-vocabulary-build-boundary-polish-report.json"
$CrossProjectBoundaryReportPath = if ([string]::IsNullOrWhiteSpace($CrossProjectBoundaryReportPath)) {
    Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\reports\out\cross-project-boundary-report.json"
}
else {
    Normalize-NPDevPath $CrossProjectBoundaryReportPath
}

$reportSchema = Test-MaturityReportSchema -PathValue $CrossProjectBoundaryReportPath -RequiredProperties @(
    "generatedAt",
    "runId",
    "overallStatus",
    "gateAudits",
    "checks",
    "summary"
)
$boundaryReport = if ($reportSchema.valid) { Read-MaturityJsonFile $CrossProjectBoundaryReportPath } else { $null }
$gateAuditFailures = @(
    if ($null -ne $boundaryReport) {
        $boundaryReport.gateAudits | Where-Object { -not [bool]$_.passed }
    }
)

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

$vocabularyCheck = Get-ControlCheckByName -Report $boundaryReport -Name "vocabulary-checks"
$surfaceCheck = Get-ControlCheckByName -Report $boundaryReport -Name "canonical-legacy-surface-checks"
$rootBuildCheck = Get-ControlCheckByName -Report $boundaryReport -Name "root-build-aggregator-only"
$executionCheck = Get-ControlCheckByName -Report $boundaryReport -Name "subproject-local-gate-execution"

$checks = @(
    (New-MaturityCheck -Name "cross-project-boundary-report" -Status $(if ($reportSchema.valid) { "passed" } else { "failed" }) -Expectation "The cross-project boundary report must exist and expose exact gate audit evidence." -Summary $(if ($reportSchema.valid) { "The cross-project boundary report is readable." } else { "The cross-project boundary report is missing or invalid." }) -Data @{ path = Get-PrioritizedControlEvidencePath -WorkspaceRoot $WorkspaceRoot -PathValue $CrossProjectBoundaryReportPath; missingProperties = $reportSchema.missingProperties; parseError = $reportSchema.parseError })
    (New-MaturityCheck -Name "vocabulary-checks-current" -Status $(if ($null -ne $vocabularyCheck -and [string]$vocabularyCheck.status -eq "passed") { "passed" } else { "failed" }) -Expectation "Policy-backed vocabulary checks must remain green." -Summary $(if ($null -ne $vocabularyCheck -and [string]$vocabularyCheck.status -eq "passed") { "Vocabulary checks remain green." } else { "Vocabulary checks are missing or failing." }) -Data @{ vocabularyCheck = $vocabularyCheck })
    (New-MaturityCheck -Name "canonical-legacy-surface-current" -Status $(if ($null -ne $surfaceCheck -and [string]$surfaceCheck.status -eq "passed") { "passed" } else { "failed" }) -Expectation "Canonical and legacy contract surface checks must remain green." -Summary $(if ($null -ne $surfaceCheck -and [string]$surfaceCheck.status -eq "passed") { "Canonical and legacy surface checks remain green." } else { "Canonical and legacy surface checks are missing or failing." }) -Data @{ surfaceCheck = $surfaceCheck })
    (New-MaturityCheck -Name "root-build-boundary-current" -Status $(if ($null -ne $rootBuildCheck -and [string]$rootBuildCheck.status -eq "passed") { "passed" } else { "failed" }) -Expectation "The workspace root must remain aggregator-only." -Summary $(if ($null -ne $rootBuildCheck -and [string]$rootBuildCheck.status -eq "passed") { "The workspace root remains aggregator-only." } else { "Root build coupling evidence is missing or failing." }) -Data @{ rootBuildCheck = $rootBuildCheck })
    (New-MaturityCheck -Name "subproject-local-execution-evidence" -Status $(if ($null -ne $executionCheck -and [string]$executionCheck.status -eq "passed" -and $gateAuditFailures.Count -eq 0) { "passed" } else { "failed" }) -Expectation "Official gate reports must expose exact subproject-local working-directory and executable evidence." -Summary $(if ($null -ne $executionCheck -and [string]$executionCheck.status -eq "passed" -and $gateAuditFailures.Count -eq 0) { "Gate reports expose exact subproject-local execution evidence." } else { "One or more gate reports are missing exact subproject-local execution evidence." }) -Data @{ failedGateAudits = $gateAuditFailures })
)

$report = Write-PrioritizedControlReport `
    -WorkspaceRoot $WorkspaceRoot `
    -RunId $RunId `
    -ScriptPath $PSCommandPath `
    -Bucket "B3" `
    -ControlId "B14-CROSS-PROJECT-VOCABULARY-BUILD-BOUNDARY-POLISH" `
    -ReportPath $ReportPath `
    -EvidencePaths @(
        Get-PrioritizedControlEvidencePath -WorkspaceRoot $WorkspaceRoot -PathValue $CrossProjectBoundaryReportPath
    ) `
    -Checks $checks `
    -Extra @{
        crossProjectBoundaryReportPath = Get-PrioritizedControlEvidencePath -WorkspaceRoot $WorkspaceRoot -PathValue $CrossProjectBoundaryReportPath
    }

Complete-PrioritizedControlScript -Report $report -PassThru:$PassThru
