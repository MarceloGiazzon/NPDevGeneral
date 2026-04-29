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
$RunId = Resolve-NPDevRunId $RunId "frontend-authoring-maturity"
$ReportPath = Resolve-MaturityReportPath -WorkspaceRoot $WorkspaceRoot -ReportPath $ReportPath -DefaultRelativePath "scripts\reports\out\frontend-authoring-surface-maturity-report.json"

$checks = @()

$workflowPaths = Test-MaturityPaths -WorkspaceRoot $WorkspaceRoot -RelativePaths @(
    "NPDevEditor\ui-react\src\authoring\app\AuthoringApp.tsx",
    "NPDevEditor\ui-react\src\authoring\models\chooser\ModelChooserScreen.tsx",
    "NPDevEditor\ui-react\src\authoring\io\ImportExportWorkspace.tsx",
    "NPDevEditor\ui-react\src\authoring\preview\PreviewWorkspace.tsx",
    "NPDevEditor\ui-react\src\authoring\pipeline\pipelineHandoff.ts",
    "NPDevEditor\ui-react\src\authoring\editors\FormBasedModelEditor.tsx",
    "NPDevEditor\ui-react\src\authoring\editors\config\FormBasedConfigEditor.tsx"
) -PathType Leaf
$workflowStatus = if ($workflowPaths.allPresent) { "passed" } else { "failed" }
$checks += New-MaturityCheck `
    -Name "workflow-cohesion" `
    -Status $workflowStatus `
    -Expectation "The authoring surface should guide users from start state through edit, validate, preview, and handoff." `
    -Summary $(if ($workflowStatus -eq "passed") { "The main authoring workflow surfaces are all present." } else { "The main authoring workflow is missing one or more key surfaces." }) `
    -Data @{
        existing = $workflowPaths.existing
        missing = $workflowPaths.missing
    }

$integrityPaths = Test-MaturityPaths -WorkspaceRoot $WorkspaceRoot -RelativePaths @(
    "NPDevEditor\ui-react\src\authoring\json\useSynchronizedJsonEditor.ts",
    "NPDevEditor\ui-react\src\authoring\json\synchronizedJsonState.ts",
    "NPDevEditor\ui-react\src\authoring\services\modelDocumentService.ts",
    "NPDevEditor\ui-react\src\authoring\io\bundleIoService.ts",
    "NPDevEditor\ui-react\src\authoring\editorRoundTripAndUx.test.ts",
    "NPDevEditor\ui-react\src\authoring\editorEvolutionSafety.test.ts"
) -PathType Leaf
$integrityStatus = if ($integrityPaths.allPresent) { "passed" } else { "failed" }
$checks += New-MaturityCheck `
    -Name "data-integrity" `
    -Status $integrityStatus `
    -Expectation "Form, JSON, import/export, and persistence views should remain synchronized without data loss." `
    -Summary $(if ($integrityStatus -eq "passed") { "Synchronization and round-trip integrity assets are present." } else { "Synchronization or round-trip integrity assets are missing." }) `
    -Data @{
        existing = $integrityPaths.existing
        missing = $integrityPaths.missing
    }

$validationPaths = Test-MaturityPaths -WorkspaceRoot $WorkspaceRoot -RelativePaths @(
    "NPDevEditor\ui-react\src\authoring\editors\modelValidation.ts",
    "NPDevEditor\ui-react\src\authoring\config\configValidation.ts",
    "NPDevEditor\ui-react\src\authoring\validation\authoringValidation.ts",
    "NPDevEditor\ui-react\src\authoring\validation\ValidationWorkspace.tsx",
    "NPDevEditor\ui-react\src\authoring\diagnostics\DiagnosticLinkPanel.tsx",
    "NPDevEditor\ui-react\src\authoring\diagnostics\diagnosticLinking.ts",
    "NPDevEditor\ui-react\src\authoring\validation\useServerValidation.ts"
) -PathType Leaf
$validationStatus = if ($validationPaths.allPresent) { "passed" } else { "warning" }
$checks += New-MaturityCheck `
    -Name "validation-quality" `
    -Status $validationStatus `
    -Expectation "Diagnostics should be explainable, actionable, and available in both local and server-assisted flows." `
    -Summary $(if ($validationStatus -eq "passed") { "Validation and diagnostic-linking assets are present." } else { "Validation assets exist in part but are missing some diagnostic or server-validation pieces." }) `
    -Data @{
        existing = $validationPaths.existing
        missing = $validationPaths.missing
    }

$explainabilityPaths = Test-MaturityPaths -WorkspaceRoot $WorkspaceRoot -RelativePaths @(
    "NPDevEditor\ui-react\src\authoring\explainability\interactionExplainability.ts",
    "NPDevEditor\ui-react\src\authoring\explainability\modelExplainability.ts",
    "NPDevEditor\ui-react\src\authoring\graph\SemanticGraphPanel.tsx",
    "NPDevEditor\ui-react\src\authoring\help\ExplainabilityTooltip.tsx",
    "NPDevEditor\ui-react\src\authoring\preview\PreviewWorkspace.tsx"
) -PathType Leaf
$explainabilityStatus = if ($explainabilityPaths.allPresent) { "passed" } else { "warning" }
$checks += New-MaturityCheck `
    -Name "explainability-for-users" `
    -Status $explainabilityStatus `
    -Expectation "The authoring surface should help users understand semantic meaning and runtime implications in plain language." `
    -Summary $(if ($explainabilityStatus -eq "passed") { "Explainability surfaces are present across help, graph, and preview paths." } else { "Explainability support is present in part but incomplete." }) `
    -Data @{
        existing = $explainabilityPaths.existing
        missing = $explainabilityPaths.missing
    }

$uxPaths = Test-MaturityPaths -WorkspaceRoot $WorkspaceRoot -RelativePaths @(
    "NPDevEditor\ui-react\src\authoring\onboarding\ConceptCreationWizard.tsx",
    "NPDevEditor\ui-react\src\authoring\onboarding\FlowCreationWizard.tsx",
    "NPDevEditor\ui-react\src\authoring\onboarding\ReferenceWizard.tsx",
    "NPDevEditor\ui-react\src\authoring\onboarding\ContextualHelpPanel.tsx",
    "NPDevEditor\ui-react\src\authoring\sync\ModelSyncStatusBanner.tsx",
    "NPDevEditor\ui-react\src\authoring\step48UxFixes.test.ts"
) -PathType Leaf
$uxStatus = if ($uxPaths.allPresent) { "passed" } else { "warning" }
$checks += New-MaturityCheck `
    -Name "ux-polish-for-repeated-use" `
    -Status $uxStatus `
    -Expectation "Frequent authoring workflows should get faster through wizards, help, sync feedback, and regression coverage for likely confusion points." `
    -Summary $(if ($uxStatus -eq "passed") { "Wizard/help/sync UX assets are present." } else { "Repeated-use UX assets are only partially present." }) `
    -Data @{
        existing = $uxPaths.existing
        missing = $uxPaths.missing
    }

$a11yEvidencePaths = Test-MaturityPaths -WorkspaceRoot $WorkspaceRoot -RelativePaths @(
    "NPDevEditor\ui-react\playwright.config.ts",
    "NPDevEditor\ui-react\package.json"
) -PathType Leaf
$axeMatches = Find-MaturityTextMatches -WorkspaceRoot $WorkspaceRoot -RelativeRoot "NPDevEditor\ui-react" -Includes @("*.ts", "*.tsx", "*.json") -Pattern 'axe|accessibility'
$e2eFiles = Get-MaturityWorkspaceFiles -WorkspaceRoot $WorkspaceRoot -RelativeRoot "NPDevEditor\ui-react\e2e" -Includes @("*.ts", "*.tsx")
$a11yStatus = if (-not $a11yEvidencePaths.allPresent) {
    "failed"
}
elseif (@($axeMatches).Count -eq 0) {
    "warning"
}
else {
    "passed"
}
$checks += New-MaturityCheck `
    -Name "accessibility-and-scale-behavior" `
    -Status $a11yStatus `
    -Expectation "The frontend should have explicit evidence for accessibility and scaling behavior, not just generic e2e coverage." `
    -Summary $(if ($a11yStatus -eq "passed") { "Accessibility-specific evidence was found alongside automated browser coverage." } elseif ($a11yStatus -eq "warning") { "Browser automation exists, but explicit accessibility evidence was not found." } else { "Core browser automation assets are missing." }) `
    -Data @{
        existing = $a11yEvidencePaths.existing
        missing = $a11yEvidencePaths.missing
        e2eFileCount = @($e2eFiles).Count
        accessibilityMatches = $axeMatches
    }

$boundaryDigestCheck = Test-MaturityFilePatterns -FilePath (Resolve-NPDevWorkspacePath $WorkspaceRoot "NPDevEditor\.npdev-root") -Patterns @(
    "The Editor should not mutate generated files directly."
)
$forbiddenEditorImports = Find-MaturityTextMatches -WorkspaceRoot $WorkspaceRoot -RelativeRoot "NPDevEditor\ui-react\src" -Includes @("*.ts", "*.tsx") -Pattern '^\s*import\s+.+from\s+[''"][^''"]*(NPDevRuntimeHost|NPDevGenerator|com/finalexec|com/npdev/generated)[^''"]*[''"]'
$boundaryPaths = Test-MaturityPaths -WorkspaceRoot $WorkspaceRoot -RelativePaths @(
    "NPDevEditor\ui-react\src\authoring\pipeline\pipelineHandoff.ts",
    "NPDevEditor\ui-react\src\authoring\io\ImportExportWorkspace.tsx"
) -PathType Leaf
$boundaryStatus = if (-not $boundaryDigestCheck.allMatched -or -not $boundaryPaths.allPresent) {
    "failed"
}
elseif (@($forbiddenEditorImports).Count -gt 0) {
    "failed"
}
else {
    "passed"
}
$checks += New-MaturityCheck `
    -Name "boundary-discipline-with-generated-outputs" `
    -Status $boundaryStatus `
    -Expectation "The editor should remain a contract-authoring surface and avoid direct coupling to runtime or generated-app internals." `
    -Summary $(if ($boundaryStatus -eq "passed") { "The editor boundary is documented and no forbidden direct imports were found." } else { "The editor boundary is either undocumented, structurally incomplete, or directly coupled to forbidden runtime/generated sources." }) `
    -Data @{
        missingDigestPatterns = $boundaryDigestCheck.missing
        existing = $boundaryPaths.existing
        missing = $boundaryPaths.missing
        forbiddenImports = $forbiddenEditorImports
    }

$report = Write-MaturityReport `
    -WorkspaceRoot $WorkspaceRoot `
    -RunId $RunId `
    -ScriptPath $PSCommandPath `
    -MaturityItem "frontend-authoring-surface" `
    -ReportPath $ReportPath `
    -Checks $checks `
    -Extra @{
        checkedAreas = @(
            "workflow-cohesion",
            "data-integrity",
            "validation-quality",
            "explainability-for-users",
            "ux-polish-for-repeated-use",
            "accessibility-and-scale-behavior",
            "boundary-discipline-with-generated-outputs"
        )
    }

Complete-MaturityScript -Report $report -PassThru:$PassThru

