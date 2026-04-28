[CmdletBinding()]
param(
    [string]$WorkspaceRoot = "",
    [string]$RunId = "",
    [string]$ReportPath = "",
    [switch]$PassThru
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

. (Join-Path $PSScriptRoot "maturity-common.ps1")

$WorkspaceRoot = Resolve-MaturityWorkspaceRoot -WorkspaceRoot $WorkspaceRoot -ScriptRoot $PSScriptRoot
$RunId = Resolve-NPDevRunId $RunId "single-source-maturity-reporting"
$ReportPath = Resolve-MaturityReportPath -WorkspaceRoot $WorkspaceRoot -ReportPath $ReportPath -DefaultRelativePath "scripts\reports\out\single-source-maturity-reporting-maturity-report.json"

$checks = @()
$requiredReportProperties = @("generatedAt", "runId", "scriptPath", "workspaceRoot", "maturityItem", "overallStatus", "checks", "summary", "extra")
$categoryScripts = @(
    @{ id = "architecture"; script = "scripts\maturity_adv\check-architecture-maturity.ps1"; report = "scripts\reports\out\architecture-maturity-report.json" },
    @{ id = "engineering-process-release-discipline"; script = "scripts\maturity_adv\check-engineering-process-release-discipline-maturity.ps1"; report = "scripts\reports\out\engineering-process-release-discipline-maturity-report.json" },
    @{ id = "frontend-authoring-surface"; script = "scripts\maturity_adv\check-frontend-authoring-surface-maturity.ps1"; report = "scripts\reports\out\frontend-authoring-surface-maturity-report.json" },
    @{ id = "integrated-runtime-package-self-sufficiency"; script = "scripts\maturity_adv\check-integrated-runtime-package-self-sufficiency-maturity.ps1"; report = "scripts\reports\out\integrated-runtime-package-self-sufficiency-maturity-report.json" },
    @{ id = "single-source-maturity-reporting"; script = "scripts\maturity_adv\check-single-source-maturity-reporting.ps1"; report = "scripts\reports\out\single-source-maturity-reporting-maturity-report.json" }
)
$suiteScript = Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\maturity_adv\run-maturity-adv-suite.ps1"
$suiteReportPath = Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\reports\out\maturity-adv-suite-report.json"
$suite12Script = Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\maturity_adv\run-maturity-12-domain-suite.ps1"
$suite12ReportPath = Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\reports\out\maturity-12-domain-suite-report.json"
$historyRoot = Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\reports\maturity"

$scriptPaths = @($categoryScripts.script + "scripts\maturity_adv\maturity-common.ps1" + "scripts\maturity_adv\run-maturity-adv-suite.ps1" + "scripts\maturity_adv\run-maturity-12-domain-suite.ps1")
$sharedVocabulary = Test-MaturityPaths -WorkspaceRoot $WorkspaceRoot -RelativePaths $scriptPaths -PathType Leaf
$sharedVocabularyStatus = if ($sharedVocabulary.allPresent) { "passed" } else { "failed" }
$checks += New-MaturityCheck `
    -Name "shared-maturity-vocabulary" `
    -Status $sharedVocabularyStatus `
    -Expectation "The maturity control mechanism should define one explicit vocabulary across all maturity dimensions and the suite runner." `
    -Summary $(if ($sharedVocabularyStatus -eq "passed") { "All maturity dimension scripts and the suite runner exist." } else { "One or more maturity dimension scripts are missing." }) `
    -Data @{
        existing = $sharedVocabulary.existing
        missing = $sharedVocabulary.missing
    }

$schemaFindings = [System.Collections.Generic.List[object]]::new()
foreach ($category in $categoryScripts) {
    $schema = Test-MaturityReportSchema -PathValue (Resolve-NPDevWorkspacePath $WorkspaceRoot $category.report) -RequiredProperties $requiredReportProperties
    [void]$schemaFindings.Add([pscustomobject]@{
            id = $category.id
            report = $category.report
            exists = $schema.exists
            valid = $schema.valid
            parseError = $schema.parseError
            missingProperties = $schema.missingProperties
        })
}
$invalidSchemas = @($schemaFindings | Where-Object { -not $_.exists -or -not $_.valid })
$schemaStatus = if (@($invalidSchemas).Count -eq 0) { "passed" } else { "warning" }
$checks += New-MaturityCheck `
    -Name "evidence-normalization" `
    -Status $schemaStatus `
    -Expectation "Each maturity dimension should emit the same report schema so results are comparable and aggregatable." `
    -Summary $(if ($schemaStatus -eq "passed") { "All dimension reports use the expected normalized schema." } else { "One or more dimension reports are missing or do not match the expected normalized schema." }) `
    -Data @{
        findings = $schemaFindings
    }

$suiteSchema = Test-MaturityReportSchema -PathValue $suiteReportPath -RequiredProperties @("generatedAt", "runId", "scriptPath", "workspaceRoot", "overallStatus", "dimensions", "summary", "conditionSummary", "narrative", "archiveRoot", "waiverPath", "waiverState", "waivers")
$suite12Schema = Test-MaturityReportSchema -PathValue $suite12ReportPath -RequiredProperties @("generatedAt", "runId", "scriptPath", "workspaceRoot", "overallStatus", "summary", "conditionSummary", "narrative", "archiveRoot", "waiverPath", "waiverState", "waivers")
$suite12Report = Read-MaturityJsonFile $suite12ReportPath
$suite12HasDimensions = $null -ne $suite12Report -and ($suite12Report.PSObject.Properties.Name -contains "dimensions")
$suite12HasDomains = $null -ne $suite12Report -and ($suite12Report.PSObject.Properties.Name -contains "domains")
$aggregationStatus = if (-not (Test-Path -LiteralPath $suiteScript -PathType Leaf)) {
    "failed"
}
elseif (-not $suiteSchema.exists -or -not $suiteSchema.valid -or -not $suite12Schema.exists -or -not $suite12Schema.valid) {
    "warning"
}
elseif (-not $suite12HasDimensions -or -not $suite12HasDomains) {
    "warning"
}
else {
    "passed"
}
$checks += New-MaturityCheck `
    -Name "aggregation-logic" `
    -Status $aggregationStatus `
    -Expectation "One suite runner should aggregate the dimension reports into a single maturity decision." `
    -Summary $(if ($aggregationStatus -eq "passed") { "Both suite runners and their aggregate maturity reports are present with the normalized schema and alias compatibility." } elseif ($aggregationStatus -eq "warning") { "One or both suite reports are missing normalized fields or the 12-domain alias compatibility fields." } else { "The 5-dimension suite runner is missing." }) `
    -Data @{
        suiteScript = "scripts\maturity_adv\run-maturity-adv-suite.ps1"
        suite12Script = "scripts\maturity_adv\run-maturity-12-domain-suite.ps1"
        suiteReportExists = $suiteSchema.exists
        suiteReportMissingProperties = $suiteSchema.missingProperties
        suiteReportParseError = $suiteSchema.parseError
        suite12ReportExists = $suite12Schema.exists
        suite12ReportMissingProperties = $suite12Schema.missingProperties
        suite12ReportParseError = $suite12Schema.parseError
        suite12HasDimensions = $suite12HasDimensions
        suite12HasDomains = $suite12HasDomains
    }

$historyEntries = if (Test-Path -LiteralPath $historyRoot -PathType Container) {
    @(Get-ChildItem -LiteralPath $historyRoot -Directory -ErrorAction SilentlyContinue)
}
else {
    @()
}
$timeDimensionStatus = if (@($historyEntries).Count -gt 0) { "passed" } else { "warning" }
$checks += New-MaturityCheck `
    -Name "time-dimension" `
    -Status $timeDimensionStatus `
    -Expectation "The maturity mechanism should preserve run history so maturity can be trended over time." `
    -Summary $(if ($timeDimensionStatus -eq "passed") { "Archived maturity run history exists." } else { "No archived maturity run history exists yet." }) `
    -Data @{
        historyRoot = "scripts\reports\maturity"
        runDirectoryCount = @($historyEntries).Count
        recentRunDirectories = @($historyEntries | Sort-Object LastWriteTime -Descending | Select-Object -First 5 | ForEach-Object { $_.Name })
    }

$freshnessChecks = [System.Collections.Generic.List[object]]::new()
foreach ($category in $categoryScripts) {
    $freshness = Get-MaturityFreshness -PathValue (Resolve-NPDevWorkspacePath $WorkspaceRoot $category.report) -MaxAgeDays 14
    [void]$freshnessChecks.Add([pscustomobject]@{
            id = $category.id
            isFresh = $freshness.isFresh
            ageDays = $freshness.ageDays
            runId = $freshness.metadata.runId
        })
}
$suiteFreshness = Get-MaturityFreshness -PathValue $suiteReportPath -MaxAgeDays 14
$staleEntries = @($freshnessChecks | Where-Object { -not $_.isFresh })
$freshnessStatus = if (-not $suiteFreshness.isFresh) {
    "warning"
}
elseif (@($staleEntries).Count -gt 0) {
    "warning"
}
else {
    "passed"
}
$checks += New-MaturityCheck `
    -Name "ownership-and-freshness" `
    -Status $freshnessStatus `
    -Expectation "Maturity reports should be fresh enough to support decision-making and aligned to recent suite runs." `
    -Summary $(if ($freshnessStatus -eq "passed") { "Suite and dimension reports are fresh." } else { "The suite or one or more dimension reports are stale or missing freshness data." }) `
    -Data @{
        suiteAgeDays = $suiteFreshness.ageDays
        dimensionFreshness = $freshnessChecks
    }

$suiteReport = Read-MaturityJsonFile $suiteReportPath
$narrativeStatus = if ($null -ne $suiteReport -and `
        $suiteReport.PSObject.Properties.Name -contains "narrative" -and `
        -not [string]::IsNullOrWhiteSpace([string]$suiteReport.narrative)) {
    "passed"
}
else {
    "warning"
}
$checks += New-MaturityCheck `
    -Name "human-readable-decision-narrative" `
    -Status $narrativeStatus `
    -Expectation "The aggregate maturity decision should include a concise narrative, not only machine-oriented status fields." `
    -Summary $(if ($narrativeStatus -eq "passed") { "The suite report contains a human-readable narrative." } else { "The suite report is missing a human-readable narrative." }) `
    -Data @{
        suiteReportExists = ($null -ne $suiteReport)
        narrative = if ($null -ne $suiteReport) { [string]$suiteReport.narrative } else { $null }
    }

$waiverPath = Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\policy\maturity-waivers.json"
$waiverStatus = if (Test-Path -LiteralPath $waiverPath -PathType Leaf) { "passed" } else { "warning" }
$checks += New-MaturityCheck `
    -Name "exception-handling" `
    -Status $waiverStatus `
    -Expectation "A maturity control system should have an explicit place to record waivers or temporary exceptions." `
    -Summary $(if ($waiverStatus -eq "passed") { "A maturity waiver file exists." } else { "No maturity waiver file exists yet, so exceptions would have to be handled informally." }) `
    -Data @{
        waiverPath = "scripts\policy\maturity-waivers.json"
        exists = (Test-Path -LiteralPath $waiverPath -PathType Leaf)
    }

$report = Write-MaturityReport `
    -WorkspaceRoot $WorkspaceRoot `
    -RunId $RunId `
    -ScriptPath $PSCommandPath `
    -MaturityItem "single-source-maturity-reporting" `
    -ReportPath $ReportPath `
    -Checks $checks `
    -Extra @{
        suiteScript = "scripts\maturity_adv\run-maturity-adv-suite.ps1"
        suiteReport = "scripts\reports\out\maturity-adv-suite-report.json"
        historyRoot = "scripts\reports\maturity"
    }

Complete-MaturityScript -Report $report -PassThru:$PassThru
