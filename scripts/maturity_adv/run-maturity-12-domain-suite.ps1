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
$RunId = Resolve-NPDevRunId $RunId "maturity-12-domain"
$ReportPath = Resolve-MaturityReportPath -WorkspaceRoot $WorkspaceRoot -ReportPath $ReportPath -DefaultRelativePath "scripts\reports\out\maturity-12-domain-suite-report.json"
if ([string]::IsNullOrWhiteSpace($ArchiveRoot)) {
    $ArchiveRoot = Resolve-NPDevWorkspacePath $WorkspaceRoot ("scripts\reports\maturity-12\" + $RunId)
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

$domainDefinitions = @(
    @{ id = "governance-release-evidence-maturity"; script = Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\maturity_adv\check-01-governance-release-evidence-maturity.ps1"; report = Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\reports\out\governance-release-evidence-maturity-report.json" },
    @{ id = "runtimehost-sample-matrix-maturity"; script = Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\maturity_adv\check-02-runtimehost-sample-matrix-maturity.ps1"; report = Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\reports\out\runtimehost-sample-matrix-maturity-report.json" },
    @{ id = "contract-schema-maturity"; script = Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\maturity_adv\check-03-contract-schema-maturity.ps1"; report = Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\reports\out\contract-schema-maturity-report.json" },
    @{ id = "kernel-runtime-maturity"; script = Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\maturity_adv\check-04-kernel-runtime-maturity.ps1"; report = Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\reports\out\kernel-runtime-maturity-report.json" },
    @{ id = "generator-maturity"; script = Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\maturity_adv\check-05-generator-maturity.ps1"; report = Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\reports\out\generator-maturity-report.json" },
    @{ id = "editor-frontend-maturity"; script = Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\maturity_adv\check-06-editor-frontend-maturity.ps1"; report = Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\reports\out\editor-frontend-maturity-report.json" },
    @{ id = "samples-documentation-maturity"; script = Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\maturity_adv\check-07-samples-documentation-maturity.ps1"; report = Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\reports\out\samples-documentation-maturity-report.json" },
    @{ id = "script-automation-maturity"; script = Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\maturity_adv\check-08-script-automation-maturity.ps1"; report = Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\reports\out\script-automation-maturity-report.json" },
    @{ id = "security-hardening-maturity"; script = Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\maturity_adv\check-09-security-hardening-maturity.ps1"; report = Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\reports\out\security-hardening-maturity-report.json" },
    @{ id = "performance-scalability-maturity"; script = Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\maturity_adv\check-10-performance-scalability-maturity.ps1"; report = Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\reports\out\performance-scalability-maturity-report.json" },
    @{ id = "observability-debuggability-maturity"; script = Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\maturity_adv\check-11-observability-debuggability-maturity.ps1"; report = Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\reports\out\observability-debuggability-maturity-report.json" },
    @{ id = "ai-ml-pipeline-maturity"; script = Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\maturity_adv\check-12-ai-ml-pipeline-maturity.ps1"; report = Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\reports\out\ai-ml-pipeline-maturity-report.json" }
)

$domainReports = [System.Collections.Generic.List[object]]::new()
foreach ($domain in $domainDefinitions) {
    Write-NPDevInfo ("Running 12-domain maturity check: " + $domain.id)
    $report = & $domain.script -WorkspaceRoot $WorkspaceRoot -RunId $RunId -ReportPath $domain.report -PassThru
    [void]$domainReports.Add([pscustomobject]@{
            id = $domain.id
            reportPath = Get-NPDevWorkspaceRelativePath $WorkspaceRoot $domain.report
            overallStatus = $report.overallStatus
            summary = $report.summary
            generatedAt = $report.generatedAt
            runId = $report.runId
        })
    if (Test-Path -LiteralPath $domain.report -PathType Leaf) {
        Copy-Item -LiteralPath $domain.report -Destination (Join-Path $ArchiveRoot ([System.IO.Path]::GetFileName($domain.report))) -Force
    }
}

$failedDomains = @($domainReports | Where-Object { $_.overallStatus -eq "failed" })
$warningDomains = @($domainReports | Where-Object { $_.overallStatus -eq "warning" })
$overallStatus = if (@($failedDomains).Count -gt 0) {
    "failed"
}
elseif (@($warningDomains).Count -gt 0) {
    "warning"
}
else {
    "passed"
}

$conditionFailed = (@($domainReports | ForEach-Object { [int]$_.summary.failed } | Measure-Object -Sum).Sum)
$conditionWarnings = (@($domainReports | ForEach-Object { [int]$_.summary.warnings } | Measure-Object -Sum).Sum)
$conditionPassed = (@($domainReports | ForEach-Object { [int]$_.summary.passed } | Measure-Object -Sum).Sum)
$conditionTotal = (@($domainReports | ForEach-Object { [int]$_.summary.total } | Measure-Object -Sum).Sum)
$waiverDocument = Get-MaturityWaiverDocument -WaiverPath $WaiverPath

$narrative = switch ($overallStatus) {
    "passed" { "All 12 maturity domains are currently green against the explicit done-condition controls." }
    "warning" { "The 12-domain maturity suite found no hard failures, but some domains are still only partially evidenced." }
    default { "The 12-domain maturity suite found explicit unmet done conditions in one or more domains." }
}

$suiteReport = [pscustomobject]@{
    generatedAt = (Get-Date).ToString("o")
    runId = $RunId
    scriptPath = Get-NPDevWorkspaceRelativePath $WorkspaceRoot $PSCommandPath
    workspaceRoot = $WorkspaceRoot
    overallStatus = $overallStatus
    narrative = $narrative
    summary = [pscustomobject]@{
        failed = @($failedDomains).Count
        warnings = @($warningDomains).Count
        passed = @($domainReports | Where-Object { $_.overallStatus -eq "passed" }).Count
        total = @($domainReports).Count
    }
    conditionSummary = [pscustomobject]@{
        failed = $conditionFailed
        warnings = $conditionWarnings
        passed = $conditionPassed
        total = $conditionTotal
    }
    dimensions = $domainReports
    domains = $domainReports
    archiveRoot = Get-NPDevWorkspaceRelativePath $WorkspaceRoot $ArchiveRoot
    waiverPath = Get-NPDevWorkspaceRelativePath $WorkspaceRoot $WaiverPath
    waiverState = $waiverDocument.state
    waivers = $waiverDocument.waivers
}
Write-NPDevJsonFile $ReportPath $suiteReport
Copy-Item -LiteralPath $ReportPath -Destination (Join-Path $ArchiveRoot ([System.IO.Path]::GetFileName($ReportPath))) -Force

if ($PassThru) {
    return $suiteReport
}

if ($overallStatus -eq "passed") {
    Write-NPDevOk ("12-domain maturity suite passed. Archive: " + $ArchiveRoot)
    return
}

if ($overallStatus -eq "warning") {
    Write-NPDevWarn ("12-domain maturity suite completed with warnings. Archive: " + $ArchiveRoot)
    return
}

Write-NPDevWarn ("12-domain maturity suite failed. Archive: " + $ArchiveRoot)
throw "12-domain maturity suite failed."
