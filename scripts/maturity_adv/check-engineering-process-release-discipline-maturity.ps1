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
$RunId = Resolve-NPDevRunId $RunId "engineering-process-maturity"
$ReportPath = Resolve-MaturityReportPath -WorkspaceRoot $WorkspaceRoot -ReportPath $ReportPath -DefaultRelativePath "scripts\reports\out\engineering-process-release-discipline-maturity-report.json"

$checks = @()

$readmeCheck = Test-MaturityFilePatterns -FilePath (Resolve-NPDevWorkspacePath $WorkspaceRoot "README.md") -Patterns @(
    "Current root automation entrypoints",
    "Release readiness has one source of truth",
    "docs/RELEASE_EVIDENCE_SOURCE_OF_TRUTH.md"
)
$gateHierarchyPaths = Test-MaturityPaths -WorkspaceRoot $WorkspaceRoot -RelativePaths @(
    "docs\RELEASE_EVIDENCE_SOURCE_OF_TRUTH.md",
    "scripts\quality\run-beta-release-gate.ps1"
) -PathType Leaf
$gateHierarchyStatus = if ($readmeCheck.allMatched -and $gateHierarchyPaths.allPresent) { "passed" } else { "failed" }
$checks += New-MaturityCheck `
    -Name "gate-hierarchy" `
    -Status $gateHierarchyStatus `
    -Expectation "Focused gates should subordinate their decisions to one aggregate release source of truth." `
    -Summary $(if ($gateHierarchyStatus -eq "passed") { "The release gate hierarchy is explicit in docs and in the aggregate gate script." } else { "The repo is missing either the release source-of-truth doc or the aggregate gate entrypoint." }) `
    -Data @{
        missingReadmePatterns = $readmeCheck.missing
        missingPaths = $gateHierarchyPaths.missing
    }

$reproducibilityPaths = Test-MaturityPaths -WorkspaceRoot $WorkspaceRoot -RelativePaths @(
    "docs\FRONTEND_GATE_REPRODUCIBILITY.md",
    "scripts\policy\sample-matrix-policy.json",
    "scripts\policy\frontend-npm-audit-policy.json",
    "NPDevEditor\ui-react\package-lock.json",
    "NPDevEditor\gradlew",
    "NPDevEditor\gradlew.bat",
    "NPDevGenerator\gradlew",
    "NPDevGenerator\gradlew.bat",
    "NPDevContract\dsl\gradlew",
    "NPDevContract\dsl\gradlew.bat",
    "NPDevRuntimeHost\gradlew",
    "NPDevRuntimeHost\gradlew.bat"
) -PathType Leaf
$reproducibilityStatus = if ($reproducibilityPaths.allPresent) { "passed" } else { "warning" }
$checks += New-MaturityCheck `
    -Name "reproducibility" `
    -Status $reproducibilityStatus `
    -Expectation "Local and CI evidence should be reproducible through pinned toolchains, wrappers, and policy files." `
    -Summary $(if ($reproducibilityStatus -eq "passed") { "Key reproducibility assets are present across frontend and Gradle-based projects." } else { "Some reproducibility assets are missing, which weakens local-vs-CI consistency." }) `
    -Data @{
        existing = $reproducibilityPaths.existing
        missing = $reproducibilityPaths.missing
    }

$provenanceCheck = Test-MaturityFilePatterns -FilePath (Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\quality\run-beta-release-gate.ps1") -Patterns @(
    "commitIdentity",
    "provenanceGrade",
    "traceabilitySatisfied",
    "evidence-manifest.json",
    "sourceOfTruth"
)
$betaReleaseReport = Get-MaturityReportMetadata (Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\reports\out\beta-release-gate-report.json")
$provenanceStatus = if (-not $provenanceCheck.allMatched) {
    "failed"
}
elseif (-not $betaReleaseReport.exists -or -not [string]::IsNullOrWhiteSpace([string]$betaReleaseReport.parseError)) {
    "warning"
}
else {
    "passed"
}
$checks += New-MaturityCheck `
    -Name "evidence-provenance" `
    -Status $provenanceStatus `
    -Expectation "Readiness evidence should be attributable to commits, environments, and run metadata." `
    -Summary $(if ($provenanceStatus -eq "passed") { "Release provenance hooks exist and current aggregate evidence is readable." } elseif ($provenanceStatus -eq "warning") { "The provenance model is encoded in the gate script, but current aggregate evidence is missing or unreadable." } else { "The aggregate gate script does not encode the expected provenance controls." }) `
    -Data @{
        missingPatterns = $provenanceCheck.missing
        reportExists = $betaReleaseReport.exists
        reportStatus = $betaReleaseReport.overallStatus
        reportParseError = $betaReleaseReport.parseError
    }

$disciplinePaths = Test-MaturityPaths -WorkspaceRoot $WorkspaceRoot -RelativePaths @(
    "docs\RELEASE_BLOCKER_EXECUTION_ROADMAP.md",
    "docs\SAMPLE_MATRIX_RELEASE_POLICY.md",
    "scripts\policy\beta-scope.json",
    "scripts\quality\run-beta-scope-check.ps1"
) -PathType Leaf
$disciplineStatus = if ($disciplinePaths.allPresent -and $readmeCheck.allMatched) { "passed" } else { "warning" }
$checks += New-MaturityCheck `
    -Name "release-decision-discipline" `
    -Status $disciplineStatus `
    -Expectation "Release decisions should be made against explicit blocker roadmaps, scope rules, and policy documents." `
    -Summary $(if ($disciplineStatus -eq "passed") { "Roadmap, scope, and release policy assets are all present." } else { "Release decision assets exist in part but are not fully complete." }) `
    -Data @{
        existing = $disciplinePaths.existing
        missing = $disciplinePaths.missing
    }

$rollbackPaths = Test-MaturityPaths -WorkspaceRoot $WorkspaceRoot -RelativePaths @(
    "NPDevEditor\ui-react\src\SemanticRollbackPanel.tsx",
    "NPDevEditor\ui-react\src\HumanReadableAuditExportPanel.tsx",
    "NPDevRuntimeHost\src\test\java\com\finalexec\PublicationRollbackE2EIT.java",
    "NPDevRuntimeHost\src\main\java\com\finalexec\npdev\service\internal\RollbackExecutionService.java",
    "NPDevRuntimeHost\src\main\java\com\finalexec\npdev\service\internal\SourceRollbackExecutorService.java"
) -PathType Leaf
$rollbackStatus = if ($rollbackPaths.allPresent) { "passed" } else { "warning" }
$checks += New-MaturityCheck `
    -Name "recovery-and-rollback-readiness" `
    -Status $rollbackStatus `
    -Expectation "Release discipline should include recovery, rollback, and external audit export readiness." `
    -Summary $(if ($rollbackStatus -eq "passed") { "Rollback and audit-export surfaces are represented in UI, runtime, and tests." } else { "Rollback or audit-export readiness is only partially represented." }) `
    -Data @{
        existing = $rollbackPaths.existing
        missing = $rollbackPaths.missing
    }

$decisionDocs = @(
    "docs\RELEASE_BLOCKER_EXECUTION_ROADMAP.md",
    "docs\RELEASE_EVIDENCE_SOURCE_OF_TRUTH.md",
    "docs\FRONTEND_GATE_REPRODUCIBILITY.md",
    "docs\SAMPLE_MATRIX_RELEASE_POLICY.md",
    "NPDevContract\docs\VERSIONING.md"
)
$decisionDocsCheck = Test-MaturityPaths -WorkspaceRoot $WorkspaceRoot -RelativePaths $decisionDocs -PathType Leaf
$adrRoot = Resolve-NPDevWorkspacePath $WorkspaceRoot "docs\adr"
$adrFiles = if (Test-Path -LiteralPath $adrRoot -PathType Container) {
    @(Get-ChildItem -LiteralPath $adrRoot -File -ErrorAction SilentlyContinue)
}
else {
    @()
}
$decisionMemoryStatus = if (-not $decisionDocsCheck.allPresent) {
    "failed"
}
elseif (@($adrFiles).Count -eq 0) {
    "warning"
}
else {
    "passed"
}
$checks += New-MaturityCheck `
    -Name "decision-memory" `
    -Status $decisionMemoryStatus `
    -Expectation "Important engineering and release decisions should leave durable written memory, ideally including ADR-style records." `
    -Summary $(if ($decisionMemoryStatus -eq "passed") { "Decision memory includes both release docs and ADR-style records." } elseif ($decisionMemoryStatus -eq "warning") { "Release docs are present, but ADR-style decision memory is missing or empty." } else { "Core release/process documents are missing." }) `
    -Data @{
        existing = $decisionDocsCheck.existing
        missing = $decisionDocsCheck.missing
        adrRoot = "docs\adr"
        adrFileCount = @($adrFiles).Count
    }

$report = Write-MaturityReport `
    -WorkspaceRoot $WorkspaceRoot `
    -RunId $RunId `
    -ScriptPath $PSCommandPath `
    -MaturityItem "engineering-process-release-discipline" `
    -ReportPath $ReportPath `
    -Checks $checks `
    -Extra @{
        checkedAreas = @(
            "gate-hierarchy",
            "reproducibility",
            "evidence-provenance",
            "release-decision-discipline",
            "recovery-and-rollback-readiness",
            "decision-memory"
        )
    }

Complete-MaturityScript -Report $report -PassThru:$PassThru
