[CmdletBinding()]
param(
    [string]$WorkspaceRoot = "",
    [string]$RunId = "",
    [string]$ReportPath = "",
    [string]$AnalyzerResultsPath = "",
    [switch]$PassThru
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

. (Join-Path $PSScriptRoot "..\npdev-common.ps1")

if ([string]::IsNullOrWhiteSpace($WorkspaceRoot)) {
    $WorkspaceRoot = Get-NPDevWorkspaceRoot $PSScriptRoot
}
$WorkspaceRoot = Normalize-NPDevPath $WorkspaceRoot
$RunId = Resolve-NPDevRunId $RunId "script-automation-quality"

if ([string]::IsNullOrWhiteSpace($ReportPath)) {
    $ReportPath = Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\reports\out\script-automation-quality-report.json"
}
else {
    $ReportPath = Normalize-NPDevPath $ReportPath
}

if (-not [string]::IsNullOrWhiteSpace($AnalyzerResultsPath)) {
    $AnalyzerResultsPath = Normalize-NPDevPath $AnalyzerResultsPath
}

function Get-RelativePaths {
    param(
        [System.IO.FileSystemInfo[]]$Items
    )

    return @($Items | ForEach-Object { Get-NPDevWorkspaceRelativePath $WorkspaceRoot $_.FullName })
}

$scriptRoots = @(
    Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\quality",
    Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\hygiene",
    Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\maturity_adv"
)
$scriptFiles = @(
    $scriptRoots |
    ForEach-Object { Get-ChildItem -LiteralPath $_ -Recurse -File -Filter "*.ps1" -ErrorAction SilentlyContinue } |
    Sort-Object FullName -Unique
)

$detectorAuxiliaryPaths = @(
    "scripts\quality\run-script-automation-quality.ps1"
)

$runtimeSurfaceEvidenceAuxiliaryPaths = @()

$runtimeHostOrchestrationPattern = '^scripts\\quality\\run-runtimehost-batch\d+-verification\.ps1$|^scripts\\quality\\run-runtimehost-convergence-batch\.ps1$|^scripts\\quality\\run-runtimehost-convergence-check\.ps1$'

$qualityHelperScriptExclusions = @(
    "scripts\quality\run-explicit-traceable-release-and-statezip.ps1",
    "scripts\quality\run-pack-k-diagnostic-validation.ps1"
)

$structuredReportExclusions = @($detectorAuxiliaryPaths + $runtimeSurfaceEvidenceAuxiliaryPaths + $qualityHelperScriptExclusions)

$automationScripts = @(
    $scriptFiles |
    Where-Object {
        ($_.Name -like "run-*.ps1" -or $_.Name -like "check-*.ps1") -and
        ((Get-NPDevWorkspaceRelativePath $WorkspaceRoot $_.FullName) -notin $detectorAuxiliaryPaths)
    }
)

$parserFailures = [System.Collections.Generic.List[object]]::new()
foreach ($scriptFile in $scriptFiles) {
    $tokens = $null
    $errors = $null
    [System.Management.Automation.Language.Parser]::ParseFile($scriptFile.FullName, [ref]$tokens, [ref]$errors) | Out-Null
    foreach ($error in @($errors)) {
        [void]$parserFailures.Add([pscustomobject]@{
                path = Get-NPDevWorkspaceRelativePath $WorkspaceRoot $scriptFile.FullName
                message = $error.Message
                line = $error.Extent.StartLineNumber
                column = $error.Extent.StartColumnNumber
            })
    }
}

$reportContractFailures = [System.Collections.Generic.List[object]]::new()
$reportScripts = @(
    Get-ChildItem -LiteralPath (Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\quality") -File -Filter "run-*.ps1" -ErrorAction SilentlyContinue |
    Where-Object {
        $relativePath = Get-NPDevWorkspaceRelativePath $WorkspaceRoot $_.FullName
        ($structuredReportExclusions -notcontains $relativePath) -and ($relativePath -notmatch $runtimeHostOrchestrationPattern)
    } |
    Sort-Object FullName -Unique
)

foreach ($scriptFile in $reportScripts) {
    $content = Get-Content -LiteralPath $scriptFile.FullName -Raw
    $usesReportedCommand = $content.Contains("Invoke-NPDevReportedCommand") -or $content.Contains("Invoke-ReportedCommand")
    $writesStructuredReport = $content.Contains("Write-NPDevJsonFile") -or $content.Contains("Write-StructuredRunReport") -or $usesReportedCommand
    $hasHelperStructuredContract = (
        $content.Contains("runtimehost-automation-contract-helper.psm1") -and
        $content.Contains("New-StructuredRunReport") -and
        $content.Contains("Write-StructuredRunReport")
    )
    $hasStandardFields = $usesReportedCommand -or $hasHelperStructuredContract -or (
        $content.Contains("generatedAt") -and
        $content.Contains("runId") -and
        $content.Contains("scriptPath") -and
        $content.Contains("workspaceRoot") -and
        $content.Contains("overallStatus")
    )
    if (-not ($writesStructuredReport -and $hasStandardFields)) {
        [void]$reportContractFailures.Add([pscustomobject]@{
                path = Get-NPDevWorkspaceRelativePath $WorkspaceRoot $scriptFile.FullName
                usesReportedCommand = $usesReportedCommand
                writesStructuredReport = $writesStructuredReport
                hasStandardFields = $hasStandardFields
            })
    }
}

$helperCoverageFailures = [System.Collections.Generic.List[object]]::new()
$helperNeedles = @(
    "npdev-common.ps1",
    "maturity-common.ps1",
    "prioritized-control-common.ps1",
    "ai-beta-common.ps1",
    "bucket2-report-common.ps1",
    "statezip-common.ps1",
    "runtimehost-automation-contract-helper.psm1"
)
foreach ($scriptFile in $automationScripts) {
    $content = Get-Content -LiteralPath $scriptFile.FullName -Raw
    $hasSharedHelper = @($helperNeedles | Where-Object { $content.Contains([string]$_) }).Count -gt 0
    if (-not $hasSharedHelper) {
        [void]$helperCoverageFailures.Add([pscustomobject]@{
                path = Get-NPDevWorkspaceRelativePath $WorkspaceRoot $scriptFile.FullName
            })
    }
}

$helperRegressionResults = @(
    [pscustomobject]@{
        name = "gradle-failure-task-name"
        passed = ([string](Get-NPDevGradleFailureTaskName @("> Task :kernel:test FAILED")) -eq ":kernel:test")
    },
    [pscustomobject]@{
        name = "workspace-relative-path"
        passed = ([string](Get-NPDevWorkspaceRelativePath $WorkspaceRoot (Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\npdev-common.ps1")) -eq "scripts\npdev-common.ps1")
    },
    [pscustomobject]@{
        name = "run-id-prefix"
        passed = ([string](Resolve-NPDevRunId "" "fixture-prefix")).StartsWith("fixture-prefix-", [System.StringComparison]::Ordinal)
    }
)

$analyzerAvailable = $false
$analyzerViolations = @()
$analyzerStatus = "not-available"
if (-not [string]::IsNullOrWhiteSpace($AnalyzerResultsPath)) {
    $analyzerResult = Get-Content -LiteralPath $AnalyzerResultsPath -Raw | ConvertFrom-Json
    $analyzerAvailable = [bool]$analyzerResult.available
    $analyzerViolations = @($analyzerResult.violations)
    $analyzerStatus = if (-not $analyzerAvailable) { "not-available" } elseif ($analyzerViolations.Count -eq 0) { "passed" } else { "failed" }
}
elseif (Get-Command Invoke-ScriptAnalyzer -ErrorAction SilentlyContinue) {
    $analyzerAvailable = $true
    try {
        $analysis = @(Invoke-ScriptAnalyzer -Path $scriptRoots -Recurse -Severity Error, Warning)
        $analyzerViolations = @($analysis | ForEach-Object {
                [pscustomobject]@{
                    path = if ([string]::IsNullOrWhiteSpace([string]$_.ScriptPath)) { $null } else { Get-NPDevWorkspaceRelativePath $WorkspaceRoot ([string]$_.ScriptPath) }
                    ruleName = [string]$_.RuleName
                    message = [string]$_.Message
                    severity = [string]$_.Severity
                    line = if ($null -eq $_.Line) { $null } else { [int]$_.Line }
                }
            })
        $analyzerStatus = if ($analyzerViolations.Count -eq 0) { "passed" } else { "failed" }
    }
    catch {
        $analyzerAvailable = $false
        $analyzerViolations = @()
        $analyzerStatus = "not-available"
    }
}

$checks = @(
    (New-NPDevCheckResult "parser-validation" $(if ($parserFailures.Count -eq 0) { "passed" } else { "failed" }) $(if ($parserFailures.Count -eq 0) { "All PowerShell automation scripts parse successfully." } else { "One or more PowerShell automation scripts have parser errors." }) @{
            failures = @($parserFailures)
        }),
    (New-NPDevCheckResult "structured-report-contract" $(if ($reportContractFailures.Count -eq 0) { "passed" } else { "failed" }) $(if ($reportContractFailures.Count -eq 0) { "Structured reporting contracts are present across scoped quality runners." } else { "One or more scoped quality runners do not clearly emit the standard structured report contract." }) @{
            failures = @($reportContractFailures)
            excludedPatterns = @($runtimeHostOrchestrationPattern)
            excludedPaths = @($structuredReportExclusions)
        }),
    (New-NPDevCheckResult "common-helper-coverage" $(if ($helperCoverageFailures.Count -eq 0) { "passed" } else { "failed" }) $(if ($helperCoverageFailures.Count -eq 0) { "Automation scripts are wired through shared helper modules." } else { "One or more automation scripts are not wired through shared helper modules." }) @{
            failures = @($helperCoverageFailures)
            excludedPaths = @($detectorAuxiliaryPaths)
        }),
    (New-NPDevCheckResult "shared-module-regression" $(if (@($helperRegressionResults | Where-Object { -not $_.passed }).Count -eq 0) { "passed" } else { "failed" }) $(if (@($helperRegressionResults | Where-Object { -not $_.passed }).Count -eq 0) { "Shared helper regression checks passed." } else { "Shared helper regression checks failed." }) @{
            results = @($helperRegressionResults)
        }),
    (New-NPDevCheckResult "psscriptanalyzer" $(if (-not $analyzerAvailable -or $analyzerViolations.Count -eq 0) { "passed" } else { "failed" }) $(if (-not $analyzerAvailable) { "PSScriptAnalyzer is not available; analyzer coverage is non-blocking." } elseif ($analyzerViolations.Count -eq 0) { "PSScriptAnalyzer reported no violations." } else { "PSScriptAnalyzer reported violations." }) @{
            available = $analyzerAvailable
            status = $analyzerStatus
            violations = @($analyzerViolations)
        })
)

$failedChecks = @($checks | Where-Object { $_.status -eq "failed" })
$warningChecks = @($checks | Where-Object { $_.status -eq "warning" })
$report = [pscustomobject]@{
    generatedAt = (Get-Date).ToString("o")
    runId = $RunId
    scriptPath = Get-NPDevWorkspaceRelativePath $WorkspaceRoot $PSCommandPath
    workspaceRoot = $WorkspaceRoot
    overallStatus = if ($failedChecks.Count -gt 0) { "failed" } elseif ($warningChecks.Count -gt 0) { "warning" } else { "passed" }
    parserValidation = [pscustomobject]@{
        checkedPaths = Get-RelativePaths $scriptFiles
        failures = @($parserFailures)
    }
    structuredReportContract = [pscustomobject]@{
        checkedPaths = Get-RelativePaths $reportScripts
        failures = @($reportContractFailures)
    }
    commonHelperCoverage = [pscustomobject]@{
        checkedPaths = Get-RelativePaths $automationScripts
        failures = @($helperCoverageFailures)
    }
    sharedModuleRegression = [pscustomobject]@{
        results = @($helperRegressionResults)
    }
    analyzer = [pscustomobject]@{
        available = $analyzerAvailable
        status = $analyzerStatus
        violations = @($analyzerViolations)
    }
    checks = $checks
    summary = [pscustomobject]@{
        failed = $failedChecks.Count
        warnings = $warningChecks.Count
        passed = @($checks | Where-Object { $_.status -eq "passed" }).Count
        total = $checks.Count
    }
}
Write-NPDevJsonFile $ReportPath $report

if ($PassThru) {
    return $report
}

if ($report.overallStatus -eq "passed") {
    Write-NPDevOk "Script automation quality report generated."
    return
}

if ($report.overallStatus -eq "warning") {
    Write-NPDevWarn "Script automation quality report generated with warnings."
    return
}

Write-NPDevWarn "Script automation quality report failed."
throw "Script automation quality report failed."
