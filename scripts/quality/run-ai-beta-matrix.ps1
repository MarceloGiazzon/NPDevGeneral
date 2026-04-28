[CmdletBinding()]
param(
    [string]$WorkspaceRoot = "",
    [string[]]$ScenarioIds = @(),
    [string]$RunId = "",
    [string]$OutputRoot = "",
    [string]$MatrixDefinitionPath = "",
    [string]$ReportPath = ""
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

. (Join-Path $PSScriptRoot "..\ai\ai-beta-common.ps1")

$WorkspaceRoot = Initialize-AiBetaWorkspace $WorkspaceRoot
$RunId = Resolve-NPDevRunId $RunId "ai-beta-matrix"

if ([string]::IsNullOrWhiteSpace($OutputRoot)) {
    $OutputRoot = Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\reports\out\ai-beta\matrix"
}
else {
    $OutputRoot = Normalize-NPDevPath $OutputRoot
}

if ([string]::IsNullOrWhiteSpace($MatrixDefinitionPath)) {
    $MatrixDefinitionPath = Join-Path $OutputRoot "matrix-definition.json"
}
else {
    $MatrixDefinitionPath = Normalize-NPDevPath $MatrixDefinitionPath
}

if ([string]::IsNullOrWhiteSpace($ReportPath)) {
    $ReportPath = Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\reports\out\ai-beta-matrix-report.json"
}
else {
    $ReportPath = Normalize-NPDevPath $ReportPath
}

$runtimeSurfaceEvidenceScript = Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\quality\run-runtime-surface-evidence.ps1"
$baselineGovernanceScript = Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\quality\run-ai-baseline-governance.ps1"
$scenarioCatalogRoot = Resolve-NPDevWorkspacePath $WorkspaceRoot "golden-ai-scenarios"

Ensure-NPDevFile $runtimeSurfaceEvidenceScript "Runtime surface evidence script"
Ensure-NPDevFile $baselineGovernanceScript "AI baseline governance script"
Ensure-NPDevDirectory $scenarioCatalogRoot "Golden AI scenarios root"

Write-NPDevInfo "Refreshing RuntimeHost surface evidence for AI beta scenarios"
& $runtimeSurfaceEvidenceScript -WorkspaceRoot $WorkspaceRoot

$requestedScenarioIds = @($ScenarioIds | ForEach-Object {
        $text = [string]$_
        if (-not [string]::IsNullOrWhiteSpace($text)) {
            $text.Trim()
        }
    } | Where-Object { -not [string]::IsNullOrWhiteSpace($_) } | Select-Object -Unique)

$cases = @()
foreach ($scenarioDirectory in @(Get-ChildItem -LiteralPath $scenarioCatalogRoot -Directory | Sort-Object Name)) {
    $manifestPath = Join-Path $scenarioDirectory.FullName "scenario.manifest.json"
    if (-not (Test-Path -LiteralPath $manifestPath -PathType Leaf)) {
        continue
    }

    $manifest = Read-AiJsonFile $manifestPath "AI scenario manifest"
    $scenarioId = [string](Get-AiProperty $manifest "scenarioId" $scenarioDirectory.Name)
    if ($requestedScenarioIds.Count -gt 0 -and $scenarioId -notin $requestedScenarioIds) {
        continue
    }

    $expectedOutcome = Get-AiProperty $manifest "expectedOutcome" $null
    $expectedClass = [string](Get-AiProperty $expectedOutcome "class" "")
    if ([string]::IsNullOrWhiteSpace($expectedClass)) {
        throw ("AI scenario manifest is missing expectedOutcome.class: " + $manifestPath)
    }

    $mismatchClass = if ($expectedClass.StartsWith("FAIL_")) {
        $expectedClass
    }
    else {
        "FAIL_BEHAVIOR_MISMATCH"
    }

    $cases += [pscustomobject]@{
        id = $scenarioId
        scenarioRoot = Get-NPDevWorkspaceRelativePath $WorkspaceRoot $scenarioDirectory.FullName
        expectedClass = $expectedClass
        mismatchClass = $mismatchClass
        kind = [string](Get-AiProperty $manifest "kind" "")
        mustPass = [bool](Get-AiProperty $expectedOutcome "mustPass" $false)
    }
}

if ($requestedScenarioIds.Count -gt 0) {
    $availableScenarioIds = @($cases | Select-Object -ExpandProperty id)
    $missingScenarioIds = @($requestedScenarioIds | Where-Object { $_ -notin $availableScenarioIds })
    if ($missingScenarioIds.Count -gt 0) {
        throw ("Unknown AI beta scenario ids: " + ($missingScenarioIds -join ", "))
    }
}

if ($cases.Count -eq 0) {
    throw "No AI beta scenarios were selected."
}

$matrixDefinition = [pscustomobject]@{
    schemaVersion = "ai-beta-matrix.v1"
    generatedAt = (Get-Date).ToString("o")
    workspaceRoot = $WorkspaceRoot
    matrixId = if ($requestedScenarioIds.Count -eq 0) { "golden-ai-beta" } else { "golden-ai-beta-selection" }
    caseCount = $cases.Count
    cases = $cases
}
Write-NPDevJsonFile $MatrixDefinitionPath $matrixDefinition

Write-NPDevInfo ("Running AI beta matrix for " + $cases.Count + " scenario(s)")
$matrixReport = Invoke-AiMatrix -WorkspaceRoot $WorkspaceRoot -MatrixPath $MatrixDefinitionPath -OutputRoot $OutputRoot

$initialReport = [pscustomobject]@{
    generatedAt = (Get-Date).ToString("o")
    runId = $RunId
    scriptPath = Get-NPDevWorkspaceRelativePath $WorkspaceRoot $PSCommandPath
    workspaceRoot = $WorkspaceRoot
    overallStatus = $matrixReport.overallStatus
    matrixId = $matrixReport.matrixId
    caseCount = $matrixReport.caseCount
    passedCases = $matrixReport.passedCases
    failedCases = $matrixReport.failedCases
    matrixDefinitionPath = Get-NPDevWorkspaceRelativePath $WorkspaceRoot $MatrixDefinitionPath
    outputRoot = Get-NPDevWorkspaceRelativePath $WorkspaceRoot $OutputRoot
    cases = $matrixReport.cases
}
Write-NPDevJsonFile $ReportPath $initialReport

$baselineGovernanceReport = & $baselineGovernanceScript `
    -WorkspaceRoot $WorkspaceRoot `
    -RunId ($RunId + "-baseline-governance") `
    -OutputRoot $OutputRoot `
    -MatrixReportPath $ReportPath `
    -PassThru

$caseGovernanceById = @{}
foreach ($caseGovernance in @($baselineGovernanceReport.cases)) {
    $caseGovernanceById[[string]$caseGovernance.scenarioId] = $caseGovernance
}

$enrichedCases = foreach ($case in @($matrixReport.cases)) {
    $governanceCase = if ($caseGovernanceById.ContainsKey([string]$case.id)) {
        $caseGovernanceById[[string]$case.id]
    }
    else {
        $null
    }

    [pscustomobject]@{
        id = [string]$case.id
        scenarioRoot = [string]$case.scenarioRoot
        expectedClass = [string]$case.expectedClass
        mismatchClass = [string]$case.mismatchClass
        actualClass = [string]$case.actualClass
        status = [string]$case.status
        resultStatus = [string]$case.resultStatus
        modelIdentifier = if ($null -eq $governanceCase) { "npdev-ai-beta-harness" } else { [string]$governanceCase.modelIdentifier }
        modelVersion = if ($null -eq $governanceCase) { "v1" } else { [string]$governanceCase.modelVersion }
        temperature = if ($null -eq $governanceCase) { 0 } else { $governanceCase.temperature }
        seed = if ($null -eq $governanceCase) { 20260423 } else { $governanceCase.seed }
        determinism = if ($null -eq $governanceCase) { $null } else { $governanceCase.determinism }
        baselines = if ($null -eq $governanceCase) { @() } else { @($governanceCase.baselines) }
        reviewMetadata = if ($null -eq $governanceCase) { $null } else { $governanceCase.reviewMetadata }
    }
}

$overallStatus = if ([string]$matrixReport.overallStatus -eq "failed" -or [string]$baselineGovernanceReport.overallStatus -eq "failed") {
    "failed"
}
elseif ([string]$baselineGovernanceReport.overallStatus -eq "warning") {
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
    overallStatus = $overallStatus
    matrixId = $matrixReport.matrixId
    caseCount = $matrixReport.caseCount
    passedCases = $matrixReport.passedCases
    failedCases = $matrixReport.failedCases
    matrixDefinitionPath = Get-NPDevWorkspaceRelativePath $WorkspaceRoot $MatrixDefinitionPath
    outputRoot = Get-NPDevWorkspaceRelativePath $WorkspaceRoot $OutputRoot
    determinismContract = $baselineGovernanceReport.determinismContract
    baselineGovernanceReportPath = Get-NPDevWorkspaceRelativePath $WorkspaceRoot (Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\reports\out\ai-baseline-governance-report.json")
    baselineGovernance = [pscustomobject]@{
        overallStatus = [string]$baselineGovernanceReport.overallStatus
        warnings = $baselineGovernanceReport.summary.warnings
        failed = $baselineGovernanceReport.summary.failed
    }
    cases = @($enrichedCases)
}
Write-NPDevJsonFile $ReportPath $report

if ($report.overallStatus -eq "passed") {
    Write-NPDevOk ("AI beta matrix passed for " + $report.caseCount + " scenario(s).")
    return
}

if ($report.overallStatus -eq "warning") {
    Write-NPDevWarn "AI beta matrix completed with governance warnings."
    return
}

Write-NPDevWarn "AI beta matrix failed."
throw "AI beta matrix failed."
