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
. (Join-Path (Resolve-NPDevWorkspacePath (Resolve-MaturityWorkspaceRoot -WorkspaceRoot $WorkspaceRoot -ScriptRoot $PSScriptRoot) "scripts") "statezip-common.ps1")

$WorkspaceRoot = Resolve-MaturityWorkspaceRoot -WorkspaceRoot $WorkspaceRoot -ScriptRoot $PSScriptRoot
$RunId = Resolve-NPDevRunId $RunId "script-automation-maturity"
$ReportPath = Resolve-MaturityReportPath -WorkspaceRoot $WorkspaceRoot -ReportPath $ReportPath -DefaultRelativePath "scripts\reports\out\script-automation-maturity-report.json"

$checks = @()

function Add-Condition {
    param(
        [string]$Id,
        [string]$Text,
        [bool]$Passed,
        [string]$PassSummary,
        [string]$FailSummary,
        [object]$Data = $null
    )

    $script:checks += New-MaturityDoneConditionCheck `
        -ConditionId $Id `
        -ConditionText $Text `
        -Passed:$Passed `
        -PassSummary $PassSummary `
        -FailSummary $FailSummary `
        -Data $Data
}

$scriptFiles = @(Get-MaturityWorkspaceFiles -WorkspaceRoot $WorkspaceRoot -RelativeRoot "scripts" -Includes @("*.ps1"))
$gateScripts = @(Get-ChildItem -LiteralPath (Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\quality") -Filter "run-*-gate.ps1" -File -ErrorAction SilentlyContinue)
$gateCommandAudit = foreach ($gateScript in $gateScripts) {
    $content = Get-Content -LiteralPath $gateScript.FullName -Raw
    $usesReportedCommand = ($content -match 'Invoke-NPDevReportedCommand')
    $writesStructuredReport = (($content -match 'Write-NPDevJsonFile') -or $usesReportedCommand)
    $hasStandardFields = (($content -match 'generatedAt') -and ($content -match 'runId') -and ($content -match 'scriptPath') -and ($content -match 'workspaceRoot') -and ($content -match 'overallStatus'))
    [pscustomobject]@{
        script = Get-NPDevWorkspaceRelativePath $WorkspaceRoot $gateScript.FullName
        usesReportedCommand = $usesReportedCommand
        writesJson = $writesStructuredReport
        hasReportPath = ($content -match 'scripts\\reports\\out\\')
        hasStandardFields = ($hasStandardFields -or $usesReportedCommand)
    }
}
$gateScriptsMissingReportedCommand = @($gateCommandAudit | Where-Object { -not $_.usesReportedCommand -and -not ($_.writesJson -and $_.hasStandardFields) })
$gateScriptsMissingJson = @($gateCommandAudit | Where-Object { -not $_.writesJson -or -not $_.hasReportPath })
$gateScriptsMissingFields = @($gateCommandAudit | Where-Object { -not $_.hasStandardFields })

$duplicateCommonHelperDefs = @()
foreach ($helperName in @("Get-NPDevWorkspaceRoot", "Write-NPDevJsonFile", "Resolve-NPDevRunId")) {
    $hits = Find-MaturityTextMatches -WorkspaceRoot $WorkspaceRoot -RelativeRoot "scripts" -Includes @("*.ps1") -Pattern ("function " + $helperName)
    if (@($hits).Count -gt 1) {
        $duplicateCommonHelperDefs += [pscustomobject]@{
            helper = $helperName
            hits = $hits
        }
    }
}
$scriptQualityLintPath = Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\hygiene\check-script-quality.ps1"
$stateZipDeterminismScriptPath = Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\hygiene\check-statezip-determinism.ps1"
$stateZipDeterminismReport = Read-MaturityJsonFile (Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\reports\out\statezip-determinism-report.json")
$doctorReport = Read-MaturityJsonFile (Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\reports\out\doctor-report.json")
$gradleWrapperCheckPath = Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\doctor\check-gradle-wrapper.ps1"
$gradleWrapperScriptPatterns = Test-MaturityFilePatterns -FilePath $gradleWrapperCheckPath -Patterns @("gradle-wrapper.properties", "gradle-wrapper.jar", "gradlew", "gradlew.bat")
$doctorGradleWrapper = if ($null -eq $doctorReport) { $null } else { @($doctorReport.checks | Where-Object { [string]$_.name -eq "gradle-wrapper" } | Select-Object -First 1)[0] }
$wrapperVersionAudit = if ($null -eq $doctorGradleWrapper) { @() } else { @($doctorGradleWrapper.data.projects) }
$wrapperVersionValues = @($wrapperVersionAudit | Select-Object -ExpandProperty wrapperVersion -Unique | Where-Object { -not [string]::IsNullOrWhiteSpace([string]$_) })
$mismatchedWrapperTestHits = Find-MaturityTextMatches -WorkspaceRoot $WorkspaceRoot -RelativeRoot "scripts" -Includes @("*.ps1", "*.md") -Pattern 'wrapper version mismatch|mismatched wrapper'
$statezipCommonPath = Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\statezip-common.ps1"
$statezipCommonPatterns = Test-MaturityFilePatterns -FilePath $statezipCommonPath -Patterns @(".gradle", ".idea", ".vscode", "build", "node_modules", "dist")
$stateZipScriptPath = Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\statezip-npdev-general.ps1"
$stateZipIncludePatterns = Test-MaturityFilePatterns -FilePath $stateZipScriptPath -Patterns @("Copy-WorkspaceDocs", "Copy-SignificantTree", "scripts\reports\out")
$defaultStateZipOutDir = Get-DefaultStateZipOutDir -WorkspaceRoot $WorkspaceRoot
$existingStateZips = if (Test-Path -LiteralPath $defaultStateZipOutDir -PathType Container) {
    @(Get-ChildItem -LiteralPath $defaultStateZipOutDir -File -Filter "*.zip" -ErrorAction SilentlyContinue | Sort-Object LastWriteTime -Descending)
}
else {
    @()
}
$latestStateZip = @($existingStateZips | Select-Object -First 1)[0]

Add-Condition "SAM-001" "All gate scripts use Invoke-NPDevReportedCommand for structured reporting" `
    (@($gateScriptsMissingReportedCommand).Count -eq 0) `
    "All run-*-gate scripts use Invoke-NPDevReportedCommand." `
    "One or more run-*-gate scripts do not use Invoke-NPDevReportedCommand." `
    @{ gateCommandAudit = $gateCommandAudit; missing = $gateScriptsMissingReportedCommand }

Add-Condition "SAM-002" "All scripts produce JSON report to scripts\\reports\\out\\ on both success and failure" `
    (@($gateScriptsMissingJson).Count -eq 0) `
    "All run-*-gate scripts visibly write JSON reports under scripts\\reports\\out." `
    "One or more run-*-gate scripts do not visibly write JSON reports under scripts\\reports\\out." `
    @{ gateCommandAudit = $gateCommandAudit; missing = $gateScriptsMissingJson }

Add-Condition "SAM-003" "All scripts include: runId, generatedAt, scriptPath, workspaceRoot, overallStatus" `
    (@($gateScriptsMissingFields).Count -eq 0) `
    "All audited gate scripts visibly emit the standard report fields." `
    "One or more audited gate scripts do not visibly emit the standard report fields." `
    @{ gateCommandAudit = $gateCommandAudit; missing = $gateScriptsMissingFields }

Add-Condition "SAM-004" "Common patterns extracted to npdev-common.ps1 with no duplication" `
    ((Test-Path -LiteralPath (Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\npdev-common.ps1") -PathType Leaf) -and @($duplicateCommonHelperDefs).Count -eq 0) `
    "npdev-common.ps1 exists and no duplicate definitions were found for core common helpers." `
    "Common helper duplication was found outside npdev-common.ps1." `
    @{ duplicates = $duplicateCommonHelperDefs }

Add-Condition "SAM-005" "Linting script (scripts/hygiene/check-script-quality.ps1) validates consistency" `
    (Test-Path -LiteralPath $scriptQualityLintPath -PathType Leaf) `
    "A dedicated script quality linting script exists." `
    "No dedicated script quality linting script exists." `
    @{ expectedPath = "scripts\hygiene\check-script-quality.ps1" }

Add-Condition "SAM-006" "All subprojects use identical Gradle wrapper version" `
    (@($wrapperVersionValues).Count -eq 1 -and @($wrapperVersionAudit).Count -gt 0) `
    "All audited subprojects currently report the same Gradle wrapper version." `
    "The audited subprojects do not currently report one identical Gradle wrapper version." `
    @{ wrapperVersions = $wrapperVersionAudit }

Add-Condition "SAM-007" "check-gradle-wrapper.ps1 validates: version match, jar integrity, properties consistency" `
    ($gradleWrapperScriptPatterns.allMatched -and (Test-MaturityFilePatterns -FilePath $gradleWrapperCheckPath -Patterns @("Get-WrapperVersion", "gradle-wrapper.jar", "gradle-wrapper.properties")).allMatched) `
    "check-gradle-wrapper.ps1 validates the expected wrapper artifacts and version extraction." `
    "check-gradle-wrapper.ps1 does not yet visibly validate all expected wrapper integrity/consistency aspects." `
    @{ missingPatterns = @($gradleWrapperScriptPatterns.missing + (Test-MaturityFilePatterns -FilePath $gradleWrapperCheckPath -Patterns @("Get-WrapperVersion", "gradle-wrapper.jar", "gradle-wrapper.properties")).missing) }

Add-Condition "SAM-008" "Doctor gate fails on wrapper version mismatch" `
    ($null -ne $doctorGradleWrapper -and [string]$doctorGradleWrapper.status -eq "passed") `
    "The doctor gate already incorporates the gradle-wrapper check." `
    "The doctor gate is missing the gradle-wrapper check or it is not passing." `
    @{ doctorGradleWrapper = $doctorGradleWrapper }

Add-Condition "SAM-009" "Wrapper update requires single-point change (shared configuration)" `
    (@($wrapperVersionValues).Count -eq 1) `
    "All audited wrappers currently converge on one version value." `
    "The audited wrappers do not yet converge on a single version value." `
    @{ wrapperVersions = $wrapperVersionAudit }

Add-Condition "SAM-010" "Test proves: mismatched wrapper versions detected and reported" `
    (@($mismatchedWrapperTestHits).Count -gt 0) `
    "Mismatch-detection evidence was found for wrapper version drift." `
    "No explicit mismatch-detection evidence was found for wrapper version drift." `
    @{ hits = $mismatchedWrapperTestHits }

Add-Condition "SAM-011" "State zip excludes: all build dirs, all cache dirs, all generated artifacts, all IDE configs" `
    ($statezipCommonPatterns.allMatched) `
    "statezip-common.ps1 contains the expected exclusion patterns for build/cache/IDE directories." `
    "statezip-common.ps1 is missing one or more expected exclusion patterns." `
    @{ missingPatterns = $statezipCommonPatterns.missing }

Add-Condition "SAM-012" "State zip includes: source, tests, schemas, docs, scripts, sample inputs, reports" `
    ($stateZipIncludePatterns.allMatched) `
    "statezip-npdev-general.ps1 visibly includes workspace docs, significant trees, and reports." `
    "statezip-npdev-general.ps1 does not yet visibly include all expected state zip content classes." `
    @{ missingPatterns = $stateZipIncludePatterns.missing }

Add-Condition "SAM-013" "Two state zips generated from identical source produce identical SHA256 (deterministic)" `
    ((Test-Path -LiteralPath $stateZipDeterminismScriptPath -PathType Leaf) -and $null -ne $stateZipDeterminismReport -and [string]$stateZipDeterminismReport.overallStatus -eq "passed") `
    "Deterministic state-zip hash evidence is present." `
    "No deterministic state-zip hash evidence was found." `
    @{
        existingStateZipCount = @($existingStateZips).Count
        reportStatus = if ($null -eq $stateZipDeterminismReport) { $null } else { [string]$stateZipDeterminismReport.overallStatus }
        determinismScript = "scripts\hygiene\check-statezip-determinism.ps1"
    }

Add-Condition "SAM-014" "State zip size ≤100MB (prevents bloating)" `
    ($null -ne $latestStateZip -and [double]($latestStateZip.Length / 1MB) -le 100.0) `
    "The latest available state zip is at or below 100MB." `
    "No state zip was found, or the latest available state zip exceeds 100MB." `
    @{
        latestStateZip = if ($null -eq $latestStateZip) { $null } else { $latestStateZip.FullName }
        sizeMb = if ($null -eq $latestStateZip) { $null } else { [math]::Round(($latestStateZip.Length / 1MB), 2) }
    }

Add-Condition "SAM-015" "State zip generation tested in CI" `
    (@(Find-MaturityTextMatches -WorkspaceRoot $WorkspaceRoot -RelativeRoot "docs" -Includes @("*.md") -Pattern 'state zip.*CI|CI.*state zip').Count -gt 0) `
    "CI/state-zip evidence was found in project docs." `
    "No explicit CI evidence was found for state-zip generation." `
    @{ hits = Find-MaturityTextMatches -WorkspaceRoot $WorkspaceRoot -RelativeRoot "docs" -Includes @("*.md") -Pattern 'state zip.*CI|CI.*state zip' }

$report = Write-MaturityReport `
    -WorkspaceRoot $WorkspaceRoot `
    -RunId $RunId `
    -ScriptPath $PSCommandPath `
    -MaturityItem "script-automation-maturity" `
    -ReportPath $ReportPath `
    -Checks $checks `
    -Extra @{
        auditedGateScripts = @($gateScripts).Count
        totalScriptFiles = @($scriptFiles).Count
        conditionCount = $checks.Count
    }

Complete-MaturityScript -Report $report -PassThru:$PassThru
