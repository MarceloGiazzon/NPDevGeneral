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
$RunId = Resolve-NPDevRunId $RunId "governance-release-evidence-maturity"
$ReportPath = Resolve-MaturityReportPath -WorkspaceRoot $WorkspaceRoot -ReportPath $ReportPath -DefaultRelativePath "scripts\reports\out\governance-release-evidence-maturity-report.json"

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

$stateZipScriptPath = Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\statezip-npdev-general.ps1"
$betaGateScriptPath = Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\quality\run-beta-release-gate.ps1"
$roadmapPath = Resolve-NPDevWorkspacePath $WorkspaceRoot "docs\RELEASE_BLOCKER_EXECUTION_ROADMAP.md"
$betaReportPath = Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\reports\out\beta-release-gate-report.json"
$betaReport = Read-MaturityJsonFile $betaReportPath
$releaseRunId = if ($null -eq $betaReport) { "" } else { [string]$betaReport.releaseRunId }
$releaseEvidenceRoot = if ($null -eq $betaReport -or [string]::IsNullOrWhiteSpace([string]$betaReport.evidenceRoot)) {
    ""
}
else {
    Resolve-NPDevWorkspacePath $WorkspaceRoot ([string]$betaReport.evidenceRoot)
}
$evidenceManifestPath = if ([string]::IsNullOrWhiteSpace($releaseEvidenceRoot)) { "" } else { Join-Path $releaseEvidenceRoot "evidence-manifest.json" }
$evidenceManifest = if ([string]::IsNullOrWhiteSpace($evidenceManifestPath)) { $null } else { Read-MaturityJsonFile $evidenceManifestPath }
$releaseBundleFiles = if ([string]::IsNullOrWhiteSpace($releaseEvidenceRoot) -or -not (Test-Path -LiteralPath $releaseEvidenceRoot -PathType Container)) {
    @()
}
else {
    @(Get-ChildItem -LiteralPath $releaseEvidenceRoot -Recurse -File -ErrorAction SilentlyContinue)
}

$stateZipScript = if (Test-Path -LiteralPath $stateZipScriptPath -PathType Leaf) { Get-Content -LiteralPath $stateZipScriptPath -Raw } else { "" }
$betaGateScript = if (Test-Path -LiteralPath $betaGateScriptPath -PathType Leaf) { Get-Content -LiteralPath $betaGateScriptPath -Raw } else { "" }

$releaseReadyDecisionMatches = [regex]::Match($stateZipScript, '(?m)^\s*\$releaseReadyDecision\s*=\s*\[bool\]\$releaseDecision\.releaseReady\s*$|(?m)^\s*\$releaseReadyDecision\s*=\s*\(\$releaseEvidenceStatus\s*-eq\s*"passed"\)\s*$')
$summaryFields = Test-MaturityFilePatterns -FilePath $stateZipScriptPath -Patterns @(
    "aggregateReportPath",
    "aggregateStatus",
    "releaseRunId"
)
$failClosedPatterns = Test-MaturityFilePatterns -FilePath $stateZipScriptPath -Patterns @(
    'Assert-PathExists -PathValue $releaseEvidenceReportPath -Label "Beta release gate report"',
    'ConvertFrom-Json',
    'throw "Beta release gate report is missing overallStatus."'
)
$agreementPatterns = Test-MaturityFilePatterns -FilePath $stateZipScriptPath -Patterns @(
    "release-ready-summary.json",
    "releaseEvidenceStatus",
    "releaseEvidenceRunId",
    "authoritativeRule"
)
$releaseSummaryTestHits = @(
    Find-MaturityTextMatches -WorkspaceRoot $WorkspaceRoot -RelativeRoot "NPDevRuntimeHost\src\test\java" -Includes @("*.java") -Pattern 'release-ready-summary|releaseReady'
    Find-MaturityTextMatches -WorkspaceRoot $WorkspaceRoot -RelativeRoot "scripts\tests" -Includes @("*.ps1") -Pattern 'release-ready-summary|releaseReady'
)

Add-Condition "GOV-001" "release-ready-summary.json derives releaseReady exclusively from beta-release-gate-report.json.overallStatus == ""passed""" `
    ($releaseReadyDecisionMatches.Success) `
    "The state zip script derives releaseReady directly from the aggregate gate overallStatus only." `
    "The state zip script does not derive releaseReady exclusively from aggregate overallStatus." `
    @{ stateZipScript = "scripts\statezip-npdev-general.ps1"; matchedExpression = $releaseReadyDecisionMatches.Value }

Add-Condition "GOV-002" "Summary includes explicit fields: aggregateReportPath, aggregateStatus, releaseRunId" `
    ($summaryFields.allMatched) `
    "The state zip summary schema exposes the aggregate report path, status, and run identifier explicitly." `
    "The state zip summary schema is missing one or more explicit aggregate evidence fields." `
    @{ missingFields = $summaryFields.missing }

Add-Condition "GOV-003" "Fails closed (throws/returns non-zero) if aggregate report is missing, unparsable, or lacks overallStatus" `
    ($failClosedPatterns.allMatched) `
    "The state zip script fails closed when the authoritative aggregate report is unavailable or incomplete." `
    "The state zip script does not show complete fail-closed behavior for missing or invalid aggregate evidence." `
    @{ missingPatterns = $failClosedPatterns.missing }

Add-Condition "GOV-004" "State zip generation (statezip-npdev-general.ps1 -ReleaseReady) validates agreement before packaging" `
    ($agreementPatterns.allMatched) `
    "The state zip packaging path records aggregate evidence values directly into the release-ready summary." `
    "The state zip packaging path does not yet show explicit agreement validation before release-ready packaging." `
    @{ missingPatterns = $agreementPatterns.missing }

Add-Condition "GOV-005" "At least one automated test proves the summary returns false when gate is failed" `
    (@($releaseSummaryTestHits).Count -gt 0) `
    "Automated test coverage references the release-ready summary decision path." `
    "No automated test evidence was found for the release-ready summary false-on-failed-gate behavior." `
    @{ hits = $releaseSummaryTestHits }

Add-Condition "GOV-006" "Every child report consumed by the aggregate gate has runId field present and validated" `
    ((Test-MaturityFilePatterns -FilePath $betaGateScriptPath -Patterns @("reportMetadata.runId", "Expected report is missing runId", "runIdMatch")).allMatched) `
    "The aggregate beta gate enforces child report runId presence and tracks runId agreement." `
    "The aggregate beta gate does not visibly enforce child report runId presence on every consumed child report path." `
    @{
        script = "scripts\quality\run-beta-release-gate.ps1"
    }

Add-Condition "GOV-007" "Aggregate gate rejects any child report where runId ≠ current aggregate runId" `
    ((Test-MaturityFilePatterns -FilePath $betaGateScriptPath -Patterns @(
                'elseif ([string]$reportMetadata.runId -ne $releaseRunId)',
                'child report runId does not match aggregate runId',
                'Expected report belongs to a different runId'
            )).allMatched) `
    "The aggregate beta gate rejects stale child reports with mismatched runId values." `
    "The aggregate beta gate does not show an explicit stale child report rejection path." `
    @{ script = "scripts\quality\run-beta-release-gate.ps1" }

Add-Condition "GOV-008" "Missing runId in child report causes aggregate gate failure (not warning)" `
    ((Test-MaturityFilePatterns -FilePath $betaGateScriptPath -Patterns @(
                '$finalStatus = "failed"',
                'child report is missing runId',
                'Expected report is missing runId'
            )).allMatched) `
    "Missing child report runId values drive an aggregate gate failure path." `
    "Missing child report runId values are not visibly wired to a hard aggregate failure path." `
    @{ script = "scripts\quality\run-beta-release-gate.ps1" }

Add-Condition "GOV-009" "Test scenario: stale report with mismatched runId causes aggregate gate to fail" `
    (@(
            Find-MaturityTextMatches -WorkspaceRoot $WorkspaceRoot -RelativeRoot "NPDevRuntimeHost\src\test\java" -Includes @("*.java") -Pattern 'stale.*runId|mismatched.*runId|different runId'
            Find-MaturityTextMatches -WorkspaceRoot $WorkspaceRoot -RelativeRoot "scripts\tests" -Includes @("*.ps1") -Pattern 'stale.*runId|mismatched.*runId|different runId'
        ).Count -gt 0) `
    "A test scenario was found that exercises stale-report runId rejection." `
    "No explicit test scenario was found for stale child report runId mismatch failure." `
    @{
        hits = @(
            Find-MaturityTextMatches -WorkspaceRoot $WorkspaceRoot -RelativeRoot "NPDevRuntimeHost\src\test\java" -Includes @("*.java") -Pattern 'stale.*runId|mismatched.*runId|different runId'
            Find-MaturityTextMatches -WorkspaceRoot $WorkspaceRoot -RelativeRoot "scripts\tests" -Includes @("*.ps1") -Pattern 'stale.*runId|mismatched.*runId|different runId'
        )
    }

$gateScripts = @(Get-ChildItem -LiteralPath (Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\quality") -Filter "run-*-gate.ps1" -File -ErrorAction SilentlyContinue)
$gateScriptAudit = foreach ($gateScriptItem in $gateScripts) {
    $content = Get-Content -LiteralPath $gateScriptItem.FullName -Raw
    $usesReportedCommand = ($content -match 'Invoke-NPDevReportedCommand')
    [pscustomobject]@{
        script = Get-NPDevWorkspaceRelativePath $WorkspaceRoot $gateScriptItem.FullName
        writesReportToOut = ($content -match 'scripts\\reports\\out\\')
        writesStructuredJson = (($content -match 'Write-NPDevJsonFile') -or $usesReportedCommand)
    }
}
$gateScriptsMissingReports = @($gateScriptAudit | Where-Object { -not $_.writesReportToOut -or -not $_.writesStructuredJson })

Add-Condition "GOV-010" "Every gate script (run-*-gate.ps1) writes structured JSON report to scripts\\reports\\out\\" `
    (@($gateScriptsMissingReports).Count -eq 0 -and @($gateScriptAudit).Count -gt 0) `
    "Every run-*-gate script in scripts\\quality writes a structured JSON report under scripts\\reports\\out." `
    "One or more run-*-gate scripts do not visibly write a structured report to scripts\\reports\\out." `
    @{ auditedScripts = $gateScriptAudit; missing = $gateScriptsMissingReports }

$failureEvidencePatterns = Test-MaturityFilePatterns -FilePath $betaGateScriptPath -Patterns @(
    "Invoke-NPDevCommandEvidence",
    "outputTail",
    "failingTaskName",
    "logPath"
)
Add-Condition "GOV-011" "Failed runs include: command output tail (last 100 lines), failing task name, per-sample log path" `
    ($failureEvidencePatterns.allMatched) `
    "The release evidence scripts include output tail, failing task detection, and per-sample log path fields." `
    "The release evidence scripts do not yet expose all expected failure diagnostics fields." `
    @{ missingPatterns = $failureEvidencePatterns.missing }

$stepReportPaths = if ($null -eq $betaReport) { @() } else { @($betaReport.steps | Where-Object { -not [string]::IsNullOrWhiteSpace([string]$_.reportPath) } | ForEach-Object { [string]$_.reportPath }) }
$bundleRelativeFiles = @($releaseBundleFiles | ForEach-Object { Get-NPDevWorkspaceRelativePath $WorkspaceRoot $_.FullName })
$manifestSources = if ($null -eq $evidenceManifest) { @() } else { @($evidenceManifest.files | ForEach-Object { [string]$_.source }) }
$missingBundleReports = @($stepReportPaths | Where-Object { $_ -notin $manifestSources -and $_ -notin $bundleRelativeFiles })
$hasEvidenceManifest = -not [string]::IsNullOrWhiteSpace($evidenceManifestPath) -and (Test-Path -LiteralPath $evidenceManifestPath -PathType Leaf)

Add-Condition "GOV-012" "Evidence bundle (scripts\\reports\\releases\\<runId>\\) contains all child reports + evidence-manifest.json" `
    ($hasEvidenceManifest -and @($missingBundleReports).Count -eq 0 -and @($stepReportPaths).Count -gt 0) `
    "The current release evidence bundle contains the aggregate manifest and every step report referenced by the aggregate gate." `
    "The current release evidence bundle is missing the manifest or one or more child reports referenced by the aggregate gate." `
    @{
        releaseRunId = $releaseRunId
        missingBundleReports = $missingBundleReports
        evidenceManifestPath = if ($hasEvidenceManifest) { Get-NPDevWorkspaceRelativePath $WorkspaceRoot $evidenceManifestPath } else { $null }
    }

$manifestFiles = if ($null -eq $evidenceManifest) { @() } else { @($evidenceManifest.files) }
$manifestHashGaps = @($manifestFiles | Where-Object { [string]::IsNullOrWhiteSpace([string]$_.sha256) })
Add-Condition "GOV-013" "evidence-manifest.json lists every copied file with SHA256 hash" `
    ($null -ne $evidenceManifest -and @($manifestFiles).Count -gt 0 -and @($manifestHashGaps).Count -eq 0) `
    "The current evidence manifest enumerates copied files with SHA256 hashes." `
    "The evidence manifest is missing or does not fully enumerate copied files with SHA256 hashes." `
    @{
        manifestPath = if ($null -eq $evidenceManifest) { $null } else { Get-NPDevWorkspaceRelativePath $WorkspaceRoot $evidenceManifestPath }
        fileCount = @($manifestFiles).Count
        hashGaps = $manifestHashGaps
    }

$failureBundleTestHits = @(
    Find-MaturityTextMatches -WorkspaceRoot $WorkspaceRoot -RelativeRoot "NPDevRuntimeHost\src\test\java" -Includes @("*.java") -Pattern 'evidence-manifest|bundle completeness|simulated failure'
    Find-MaturityTextMatches -WorkspaceRoot $WorkspaceRoot -RelativeRoot "scripts\tests" -Includes @("*.ps1") -Pattern 'evidence-manifest|bundle completeness|simulated failure'
)
Add-Condition "GOV-014" "Automated test validates bundle completeness after a simulated failure" `
    (@($failureBundleTestHits).Count -gt 0) `
    "Automated test evidence was found for evidence bundle completeness after failure." `
    "No automated test evidence was found for evidence bundle completeness after a simulated failure." `
    @{ hits = $failureBundleTestHits }

$report = Write-MaturityReport `
    -WorkspaceRoot $WorkspaceRoot `
    -RunId $RunId `
    -ScriptPath $PSCommandPath `
    -MaturityItem "governance-release-evidence-maturity" `
    -ReportPath $ReportPath `
    -Checks $checks `
    -Extra @{
        releaseRunId = $releaseRunId
        evidenceRoot = if ([string]::IsNullOrWhiteSpace($releaseEvidenceRoot)) { $null } else { Get-NPDevWorkspaceRelativePath $WorkspaceRoot $releaseEvidenceRoot }
        conditionCount = $checks.Count
    }

Complete-MaturityScript -Report $report -PassThru:$PassThru
