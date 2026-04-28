[CmdletBinding()]
param(
    [string]$WorkspaceRoot = "",
    [string]$RunId = "",
    [string]$OutputRoot = "",
    [string]$MatrixReportPath = "",
    [string]$ReportPath = "",
    [int]$ReviewFreshnessDays = 180,
    [switch]$PassThru
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

. (Join-Path $PSScriptRoot "..\ai\ai-beta-common.ps1")

function Get-AiGovernanceReviewMetadata {
    param(
        [string]$ScenarioRoot,
        [object]$Manifest
    )

    $reviewMetadata = Get-AiProperty $Manifest "reviewMetadata" (Get-AiProperty $Manifest "review" $null)
    if ($null -ne $reviewMetadata) {
        return [pscustomobject]@{
            source = "scenario.manifest.json"
            value = $reviewMetadata
        }
    }

    $files = Get-AiProperty $Manifest "files" $null
    foreach ($fileKey in @("customProcedure", "customPanel")) {
        $relativePath = [string](Get-AiProperty $files $fileKey "")
        if ([string]::IsNullOrWhiteSpace($relativePath)) {
            continue
        }

        $resolvedPath = Resolve-AiScenarioFile $ScenarioRoot $relativePath
        if (-not (Test-Path -LiteralPath $resolvedPath -PathType Leaf)) {
            continue
        }

        $doc = Read-AiJsonFile $resolvedPath ("AI governance " + $fileKey)
        $docReview = Get-AiProperty $doc "reviewMetadata" (Get-AiProperty $doc "review" $null)
        if ($null -ne $docReview) {
            return [pscustomobject]@{
                source = $relativePath
                value = $docReview
            }
        }
    }

    return $null
}

$WorkspaceRoot = Initialize-AiBetaWorkspace $WorkspaceRoot
$RunId = Resolve-NPDevRunId $RunId "ai-baseline-governance"

if ([string]::IsNullOrWhiteSpace($OutputRoot)) {
    $OutputRoot = Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\reports\out\ai-beta\matrix"
}
else {
    $OutputRoot = Normalize-NPDevPath $OutputRoot
}

if ([string]::IsNullOrWhiteSpace($MatrixReportPath)) {
    $MatrixReportPath = Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\reports\out\ai-beta-matrix-report.json"
}
else {
    $MatrixReportPath = Normalize-NPDevPath $MatrixReportPath
}

if ([string]::IsNullOrWhiteSpace($ReportPath)) {
    $ReportPath = Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\reports\out\ai-baseline-governance-report.json"
}
else {
    $ReportPath = Normalize-NPDevPath $ReportPath
}

$matrixReport = Read-AiJsonFile $MatrixReportPath "AI beta matrix report"
$caseAudits = [System.Collections.Generic.List[object]]::new()
$determinismFailures = [System.Collections.Generic.List[object]]::new()
$baselineFailures = [System.Collections.Generic.List[object]]::new()
$evidenceFailures = [System.Collections.Generic.List[object]]::new()
$reviewWarnings = [System.Collections.Generic.List[object]]::new()

foreach ($case in @($matrixReport.cases)) {
    $scenarioId = [string]$case.id
    $expectedClass = [string](Get-AiProperty $case "expectedClass" "")
    $actualClass = [string](Get-AiProperty $case "actualClass" "")
    $caseStatus = [string](Get-AiProperty $case "status" "")
    $resultStatus = [string](Get-AiProperty $case "resultStatus" "")
    $scenarioRoot = Resolve-NPDevWorkspacePath $WorkspaceRoot ([string]$case.scenarioRoot)
    $manifestPath = Join-Path $scenarioRoot "scenario.manifest.json"
    $manifest = Read-AiJsonFile $manifestPath "AI scenario manifest"
    $files = Get-AiProperty $manifest "files" $null
    $configPath = Resolve-AiScenarioFile $ScenarioRoot $([string](Get-AiProperty $files "config" "config.json"))
    $config = Read-AiJsonFile $configPath "AI scenario config"

    $baselineSpecs = @(
        @{ key = "expectedBehavior"; role = "input:expected-behavior.json" }
        @{ key = "expectedProcedureBehavior"; role = "input:expected-procedure-behavior.json" }
        @{ key = "expectedPanelBehavior"; role = "input:expected-panel-behavior.json" }
    )
    $baselineEntries = [System.Collections.Generic.List[object]]::new()
    foreach ($spec in $baselineSpecs) {
        $relativePath = [string](Get-AiProperty $files $spec.key "")
        if ([string]::IsNullOrWhiteSpace($relativePath)) {
            continue
        }

        $resolvedPath = Resolve-AiScenarioFile $scenarioRoot $relativePath
        [void]$baselineEntries.Add([pscustomobject]@{
                key = [string]$spec.key
                role = [string]$spec.role
                relativePath = $relativePath
                checkedIn = (Test-Path -LiteralPath $resolvedPath -PathType Leaf)
                path = if (Test-Path -LiteralPath $resolvedPath -PathType Leaf) { Get-NPDevWorkspaceRelativePath $WorkspaceRoot $resolvedPath } else { $relativePath }
            })
    }

    $auditPackPath = Join-Path (Join-Path $OutputRoot $scenarioId) (Join-Path $scenarioId "audit-pack.json")
    $auditPack = if (Test-Path -LiteralPath $auditPackPath -PathType Leaf) {
        Read-AiJsonFile $auditPackPath "AI beta evidence pack"
    }
    else {
        $null
    }
    $artifactRoles = if ($null -eq $auditPack) { @() } else { @($auditPack.artifacts | ForEach-Object { [string]$_.role }) }
    $missingEvidenceRoles = @($baselineEntries | Where-Object { $_.role -notin $artifactRoles } | Select-Object -ExpandProperty role)

    $determinismReportPath = Join-Path (Join-Path $OutputRoot $scenarioId) (Join-Path "generation" ($scenarioId + "-determinism-report.json"))
    $determinismReport = if (Test-Path -LiteralPath $determinismReportPath -PathType Leaf) {
        Read-AiJsonFile $determinismReportPath "AI determinism report"
    }
    else {
        $null
    }
    $isGovernedNegativeScenario = $caseStatus -eq "passed" `
        -and $resultStatus -eq "failed" `
        -and -not [string]::IsNullOrWhiteSpace($expectedClass) `
        -and $expectedClass.StartsWith("FAIL_") `
        -and $actualClass -eq $expectedClass
    $determinismStatus = if ($null -ne $determinismReport) {
        [string]$determinismReport.overallStatus
    }
    elseif ($isGovernedNegativeScenario) {
        "not-applicable"
    }
    else {
        "failed"
    }

    $reviewRequired = [bool](Get-AiProperty (Get-AiProperty $manifest "expectedOutcome" $null) "mustPass" $false)
    $reviewRecord = Get-AiGovernanceReviewMetadata -ScenarioRoot $scenarioRoot -Manifest $manifest
    $reviewValue = if ($null -eq $reviewRecord) { $null } else { $reviewRecord.value }
    $reviewedAtValue = if ($null -eq $reviewValue) { $null } else { Get-AiProperty $reviewValue "reviewedAt" $null }
    $reviewedAtText = if ($null -eq $reviewedAtValue) {
        ""
    }
    elseif ($reviewedAtValue -is [datetimeoffset]) {
        $reviewedAtValue.ToString("o")
    }
    elseif ($reviewedAtValue -is [datetime]) {
        ([datetimeoffset]$reviewedAtValue).ToString("o")
    }
    else {
        [string]$reviewedAtValue
    }
    $reviewer = if ($null -eq $reviewValue) { "" } else { [string](Get-AiProperty $reviewValue "reviewer" "") }
    $reviewReason = if ($null -eq $reviewValue) { "" } else { [string](Get-AiProperty $reviewValue "reason" "") }
    $reviewedAt = $null
    $reviewAgeDays = $null
    if (-not [string]::IsNullOrWhiteSpace($reviewedAtText)) {
        try {
            $reviewedAt = [datetimeoffset]::Parse($reviewedAtText, [Globalization.CultureInfo]::InvariantCulture)
            $reviewAgeDays = [math]::Round((([datetimeoffset](Get-Date)) - $reviewedAt).TotalDays, 2)
        }
        catch {
            $reviewedAt = $null
            $reviewAgeDays = $null
        }
    }

    $reviewStatus = "passed"
    if ($reviewRequired) {
        if ([string]::IsNullOrWhiteSpace($reviewedAtText) -or [string]::IsNullOrWhiteSpace($reviewer) -or [string]::IsNullOrWhiteSpace($reviewReason)) {
            $reviewStatus = "warning"
        }
        elseif ($null -eq $reviewedAt -or $reviewAgeDays -gt $ReviewFreshnessDays) {
            $reviewStatus = "warning"
        }
    }

    $caseAudit = [pscustomobject]@{
        scenarioId = $scenarioId
        scenarioRoot = Get-NPDevWorkspaceRelativePath $WorkspaceRoot $scenarioRoot
        modelIdentifier = [string](Get-AiProperty (Get-AiProperty $config "generator" $null) "modelIdentifier" "npdev-ai-beta-harness")
        modelVersion = [string](Get-AiProperty (Get-AiProperty $config "generator" $null) "modelVersion" "v1")
        temperature = 0
        seed = 20260423
        determinism = [pscustomobject]@{
            reportPath = if ($null -eq $determinismReport) { Get-NPDevWorkspaceRelativePath $WorkspaceRoot $determinismReportPath } else { Get-NPDevWorkspaceRelativePath $WorkspaceRoot $determinismReportPath }
            status = $determinismStatus
            driftDetected = if ($null -eq $determinismReport) { $null } else { [bool]$determinismReport.driftDetected }
        }
        baselines = @($baselineEntries)
        evidencePack = [pscustomobject]@{
            reportPath = Get-NPDevWorkspaceRelativePath $WorkspaceRoot $auditPackPath
            includesBaselines = ($missingEvidenceRoles.Count -eq 0 -and $null -ne $auditPack)
            missingRoles = @($missingEvidenceRoles)
        }
        reviewMetadata = [pscustomobject]@{
            required = $reviewRequired
            status = $reviewStatus
            source = if ($null -eq $reviewRecord) { $null } else { [string]$reviewRecord.source }
            reviewer = if ([string]::IsNullOrWhiteSpace($reviewer)) { $null } else { $reviewer }
            reviewedAt = if ($null -eq $reviewedAt) { if ([string]::IsNullOrWhiteSpace($reviewedAtText)) { $null } else { $reviewedAtText } } else { $reviewedAt.ToString("o") }
            ageDays = $reviewAgeDays
            reason = if ([string]::IsNullOrWhiteSpace($reviewReason)) { $null } else { $reviewReason }
        }
    }
    [void]$caseAudits.Add($caseAudit)

    if ($determinismStatus -eq "failed") {
        [void]$determinismFailures.Add($caseAudit)
    }
    if (@($baselineEntries | Where-Object { -not $_.checkedIn }).Count -gt 0) {
        [void]$baselineFailures.Add($caseAudit)
    }
    if ($missingEvidenceRoles.Count -gt 0 -or $null -eq $auditPack) {
        [void]$evidenceFailures.Add($caseAudit)
    }
    if ($reviewStatus -eq "warning") {
        [void]$reviewWarnings.Add($caseAudit)
    }
}

$checks = @(
    (New-AiCheck "GOV01" "AI beta matrix report exists" ($null -ne $matrixReport) "ai-beta-matrix-report.json" "FAIL_BASELINE_GOVERNANCE")
    (New-AiCheck "GOV02" "Per-case determinism reports are green" ($determinismFailures.Count -eq 0) ("determinismFailures=" + ($determinismFailures.Count)) "FAIL_GENERATION")
    (New-AiCheck "GOV03" "Scenario baselines are checked in" ($baselineFailures.Count -eq 0) ("baselineFailures=" + ($baselineFailures.Count)) "FAIL_BASELINE_GOVERNANCE")
    (New-AiCheck "GOV04" "Evidence packs include checked-in baselines" ($evidenceFailures.Count -eq 0) ("evidenceFailures=" + ($evidenceFailures.Count)) "FAIL_BASELINE_GOVERNANCE")
    ([pscustomobject]@{
            id = "GOV05"
            name = "Baseline review metadata is present and fresh when required"
            status = if ($reviewWarnings.Count -eq 0) { "passed" } else { "warning" }
            evidence = "reviewWarnings=" + $reviewWarnings.Count
            failureClass = $null
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
    matrixReportPath = Get-NPDevWorkspaceRelativePath $WorkspaceRoot $MatrixReportPath
    outputRoot = Get-NPDevWorkspaceRelativePath $WorkspaceRoot $OutputRoot
    determinismContract = [pscustomobject]@{
        modelIdentifier = "npdev-ai-beta-harness"
        modelVersion = "v1"
        temperature = 0
        seed = 20260423
    }
    reviewFreshnessDays = $ReviewFreshnessDays
    cases = @($caseAudits)
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
    Write-NPDevOk "AI baseline governance report generated."
    return
}

if ($report.overallStatus -eq "warning") {
    Write-NPDevWarn "AI baseline governance report generated with warnings."
    return
}

Write-NPDevWarn "AI baseline governance report failed."
throw "AI baseline governance report failed."
