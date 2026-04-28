[CmdletBinding()]
param(
    [string]$WorkspaceRoot = "",
    [string]$RunId = "",
    [string]$ReportPath = "",
    [string]$ArchiveRoot = "",
    [string]$WaiverPath = "",
    [switch]$PassThru
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

. (Join-Path $PSScriptRoot "maturity-common.ps1")

$WorkspaceRoot = Resolve-MaturityWorkspaceRoot -WorkspaceRoot $WorkspaceRoot -ScriptRoot $PSScriptRoot
$RunId = Resolve-NPDevRunId $RunId "maturity-adv"
$ReportPath = Resolve-MaturityReportPath -WorkspaceRoot $WorkspaceRoot -ReportPath $ReportPath -DefaultRelativePath "scripts\reports\out\maturity-adv-suite-report.json"
if ([string]::IsNullOrWhiteSpace($ArchiveRoot)) {
    $ArchiveRoot = Resolve-NPDevWorkspacePath $WorkspaceRoot ("scripts\reports\maturity\" + $RunId)
}
else {
    $ArchiveRoot = Normalize-NPDevPath $ArchiveRoot
}
if ([string]::IsNullOrWhiteSpace($WaiverPath)) {
    $WaiverPath = Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\policy\maturity-waivers.json"
}
else {
    $WaiverPath = Normalize-NPDevPath $WaiverPath
}
New-Item -ItemType Directory -Force -Path $ArchiveRoot | Out-Null

$dimensionDefinitions = @(
    @{ id = "architecture"; script = Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\maturity_adv\check-architecture-maturity.ps1"; report = Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\reports\out\architecture-maturity-report.json" },
    @{ id = "engineering-process-release-discipline"; script = Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\maturity_adv\check-engineering-process-release-discipline-maturity.ps1"; report = Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\reports\out\engineering-process-release-discipline-maturity-report.json" },
    @{ id = "frontend-authoring-surface"; script = Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\maturity_adv\check-frontend-authoring-surface-maturity.ps1"; report = Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\reports\out\frontend-authoring-surface-maturity-report.json" },
    @{ id = "integrated-runtime-package-self-sufficiency"; script = Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\maturity_adv\check-integrated-runtime-package-self-sufficiency-maturity.ps1"; report = Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\reports\out\integrated-runtime-package-self-sufficiency-maturity-report.json" },
    @{ id = "single-source-maturity-reporting"; script = Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\maturity_adv\check-single-source-maturity-reporting.ps1"; report = Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\reports\out\single-source-maturity-reporting-maturity-report.json" }
)

$dimensionReports = [System.Collections.Generic.List[object]]::new()
foreach ($dimension in $dimensionDefinitions | Where-Object { $_.id -ne "single-source-maturity-reporting" }) {
    Write-NPDevInfo ("Running maturity dimension: " + $dimension.id)
    $report = & $dimension.script -WorkspaceRoot $WorkspaceRoot -RunId $RunId -ReportPath $dimension.report -PassThru
    [void]$dimensionReports.Add([pscustomobject]@{
            id = $dimension.id
            reportPath = Get-NPDevWorkspaceRelativePath $WorkspaceRoot $dimension.report
            overallStatus = $report.overallStatus
            summary = $report.summary
            generatedAt = $report.generatedAt
            runId = $report.runId
        })
}

$waiverDocument = Get-MaturityWaiverDocument -WaiverPath $WaiverPath
$waiverState = $waiverDocument.state
$waivers = $waiverDocument.waivers

$failedDimensions = @($dimensionReports | Where-Object { $_.overallStatus -eq "failed" })
$warningDimensions = @($dimensionReports | Where-Object { $_.overallStatus -eq "warning" })
$overallStatus = if ($failedDimensions.Count -gt 0) {
    "failed"
}
elseif ($warningDimensions.Count -gt 0) {
    "warning"
}
else {
    "passed"
}
$conditionFailed = (@($dimensionReports | ForEach-Object { [int]$_.summary.failed } | Measure-Object -Sum).Sum)
$conditionWarnings = (@($dimensionReports | ForEach-Object { [int]$_.summary.warnings } | Measure-Object -Sum).Sum)
$conditionPassed = (@($dimensionReports | ForEach-Object { [int]$_.summary.passed } | Measure-Object -Sum).Sum)
$conditionTotal = (@($dimensionReports | ForEach-Object { [int]$_.summary.total } | Measure-Object -Sum).Sum)

$narrative = switch ($overallStatus) {
    "passed" { "All maturity dimensions currently satisfy the scripted control expectations." }
    "warning" { "The maturity controls found partial evidence and open gaps. The platform is advancing, but not every expectation is fully evidenced yet." }
    default { "The maturity controls found foundational gaps that should be addressed before calling the workspace highly mature." }
}

$suiteReport = [pscustomobject]@{
    generatedAt = (Get-Date).ToString("o")
    runId = $RunId
    scriptPath = Get-NPDevWorkspaceRelativePath $WorkspaceRoot $PSCommandPath
    workspaceRoot = $WorkspaceRoot
    overallStatus = $overallStatus
    narrative = $narrative
    summary = [pscustomobject]@{
        failed = $failedDimensions.Count
        warnings = $warningDimensions.Count
        passed = @($dimensionReports | Where-Object { $_.overallStatus -eq "passed" }).Count
        total = $dimensionReports.Count
    }
    conditionSummary = [pscustomobject]@{
        failed = $conditionFailed
        warnings = $conditionWarnings
        passed = $conditionPassed
        total = $conditionTotal
    }
    dimensions = $dimensionReports
    archiveRoot = Get-NPDevWorkspaceRelativePath $WorkspaceRoot $ArchiveRoot
    waiverPath = Get-NPDevWorkspaceRelativePath $WorkspaceRoot $WaiverPath
    waiverState = $waiverState
    waivers = $waivers
}
Write-NPDevJsonFile $ReportPath $suiteReport

foreach ($dimension in $dimensionDefinitions | Where-Object { $_.id -ne "single-source-maturity-reporting" }) {
    if (Test-Path -LiteralPath $dimension.report -PathType Leaf) {
        Copy-Item -LiteralPath $dimension.report -Destination (Join-Path $ArchiveRoot ([System.IO.Path]::GetFileName($dimension.report))) -Force
    }
}
Copy-Item -LiteralPath $ReportPath -Destination (Join-Path $ArchiveRoot ([System.IO.Path]::GetFileName($ReportPath))) -Force

$singleSourceScript = Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\maturity_adv\check-single-source-maturity-reporting.ps1"
$singleSourceReportPath = Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\reports\out\single-source-maturity-reporting-maturity-report.json"
$singleSourceReport = & $singleSourceScript -WorkspaceRoot $WorkspaceRoot -RunId $RunId -ReportPath $singleSourceReportPath -PassThru
Copy-Item -LiteralPath $singleSourceReportPath -Destination (Join-Path $ArchiveRoot ([System.IO.Path]::GetFileName($singleSourceReportPath))) -Force

$dimensionReports.Add([pscustomobject]@{
        id = "single-source-maturity-reporting"
        reportPath = Get-NPDevWorkspaceRelativePath $WorkspaceRoot $singleSourceReportPath
        overallStatus = $singleSourceReport.overallStatus
        summary = $singleSourceReport.summary
        generatedAt = $singleSourceReport.generatedAt
        runId = $singleSourceReport.runId
    }) | Out-Null

$failedDimensions = @($dimensionReports | Where-Object { $_.overallStatus -eq "failed" })
$warningDimensions = @($dimensionReports | Where-Object { $_.overallStatus -eq "warning" })
$overallStatus = if ($failedDimensions.Count -gt 0) {
    "failed"
}
elseif ($warningDimensions.Count -gt 0) {
    "warning"
}
else {
    "passed"
}
$conditionFailed = (@($dimensionReports | ForEach-Object { [int]$_.summary.failed } | Measure-Object -Sum).Sum)
$conditionWarnings = (@($dimensionReports | ForEach-Object { [int]$_.summary.warnings } | Measure-Object -Sum).Sum)
$conditionPassed = (@($dimensionReports | ForEach-Object { [int]$_.summary.passed } | Measure-Object -Sum).Sum)
$conditionTotal = (@($dimensionReports | ForEach-Object { [int]$_.summary.total } | Measure-Object -Sum).Sum)
$narrative = switch ($overallStatus) {
    "passed" { "All maturity dimensions, including the reporting controls themselves, currently satisfy the scripted expectations." }
    "warning" { "The maturity controls are working, but the reports still show some dimensions with incomplete or aging evidence." }
    default { "The maturity controls found foundational gaps, including gaps in the reporting/control layer itself." }
}
$suiteReport.generatedAt = (Get-Date).ToString("o")
$suiteReport.overallStatus = $overallStatus
$suiteReport.narrative = $narrative
$suiteReport.summary = [pscustomobject]@{
    failed = $failedDimensions.Count
    warnings = $warningDimensions.Count
    passed = @($dimensionReports | Where-Object { $_.overallStatus -eq "passed" }).Count
    total = $dimensionReports.Count
}
$suiteReport.conditionSummary = [pscustomobject]@{
    failed = $conditionFailed
    warnings = $conditionWarnings
    passed = $conditionPassed
    total = $conditionTotal
}
$suiteReport.dimensions = $dimensionReports
Write-NPDevJsonFile $ReportPath $suiteReport
Copy-Item -LiteralPath $ReportPath -Destination (Join-Path $ArchiveRoot ([System.IO.Path]::GetFileName($ReportPath))) -Force

if ($PassThru) {
    return $suiteReport
}

if ($overallStatus -eq "passed") {
    Write-NPDevOk ("Maturity suite passed. Archive: " + $ArchiveRoot)
    return
}

if ($overallStatus -eq "warning") {
    Write-NPDevWarn ("Maturity suite completed with warnings. Archive: " + $ArchiveRoot)
    return
}

Write-NPDevWarn ("Maturity suite failed. Archive: " + $ArchiveRoot)
throw "Maturity suite failed."
