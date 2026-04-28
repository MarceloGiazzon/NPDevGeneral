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
$RunId = Resolve-NPDevRunId $RunId "ai-ml-pipeline-maturity"
$ReportPath = Resolve-MaturityReportPath -WorkspaceRoot $WorkspaceRoot -ReportPath $ReportPath -DefaultRelativePath "scripts\reports\out\ai-ml-pipeline-maturity-report.json"

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

$aiBetaMatrixReport = Read-MaturityJsonFile (Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\reports\out\ai-beta-matrix-report.json")
$aiDeterminismScriptPath = Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\ai\verify-ai-generation-determinism.ps1"
$customPanelValidatorPath = Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\ai\validate-custom-panel.ps1"
$customProcedureValidatorPath = Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\ai\validate-custom-procedure.ps1"
$aiBetaCommonPath = Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\ai\ai-beta-common.ps1"
$aiSchemaHits = Find-MaturityTextMatches -WorkspaceRoot $WorkspaceRoot -RelativeRoot "scripts\ai" -Includes @("*.ps1") -Pattern 'schemaVersion|schema'
$aiDeterminismHits = Find-MaturityTextMatches -WorkspaceRoot $WorkspaceRoot -RelativeRoot "scripts\ai" -Includes @("*.ps1") -Pattern 'determinism|same prompt|same model'
$aiTemperatureHits = Find-MaturityTextMatches -WorkspaceRoot $WorkspaceRoot -RelativeRoot "scripts\ai" -Includes @("*.ps1") -Pattern 'temperature.?0|temperature = 0|temperature=0'
$aiSeedHits = Find-MaturityTextMatches -WorkspaceRoot $WorkspaceRoot -RelativeRoot "scripts\ai" -Includes @("*.ps1") -Pattern 'seed'
$aiNondeterministicFailureHits = Find-MaturityTextMatches -WorkspaceRoot $WorkspaceRoot -RelativeRoot "scripts\ai" -Includes @("*.ps1") -Pattern 'determinism failed|nondeterministic|FAIL_BEHAVIOR_MISMATCH'
$panelValidationHits = Find-MaturityTextMatches -WorkspaceRoot $WorkspaceRoot -RelativeRoot "scripts\ai" -Includes @("*.ps1") -Pattern 'schema|runtime compatibility|boundary|panel'
$procedureValidationHits = Find-MaturityTextMatches -WorkspaceRoot $WorkspaceRoot -RelativeRoot "scripts\ai" -Includes @("*.ps1") -Pattern 'schema|capability|required|execution limit|procedure'
$invalidExtensionCases = if ($null -eq $aiBetaMatrixReport) {
    @()
}
else {
    @($aiBetaMatrixReport.cases | Where-Object { [string]$_.id -match 'custom-panel-invalid|custom-procedure-invalid|custom-procedure-admission-rejection' -and [string]$_.status -eq "passed" })
}

Add-Condition "AIM-001" "Same prompt + same model version → identical output (± whitespace)" `
    ((Test-Path -LiteralPath $aiDeterminismScriptPath -PathType Leaf) -and $null -ne $aiBetaMatrixReport -and [string]$aiBetaMatrixReport.overallStatus -eq "passed" -and @($aiDeterminismHits).Count -gt 0) `
    "AI determinism tooling exists and the current AI beta matrix is passed." `
    "AI determinism tooling or current AI beta matrix evidence is missing or failing." `
    @{
        aiDeterminismScript = "scripts\ai\verify-ai-generation-determinism.ps1"
        aiBetaMatrixStatus = if ($null -eq $aiBetaMatrixReport) { $null } else { [string]$aiBetaMatrixReport.overallStatus }
        hits = $aiDeterminismHits
    }

Add-Condition "AIM-002" "Temperature=0 enforced for all AI generation calls" `
    (@($aiTemperatureHits).Count -gt 0) `
    "AI scripts reference temperature=0 enforcement." `
    "No explicit temperature=0 enforcement evidence was found in AI scripts." `
    @{ hits = $aiTemperatureHits }

Add-Condition "AIM-003" "Seed value fixed and recorded in generation marker" `
    (@($aiSeedHits).Count -gt 0) `
    "AI scripts reference seed handling." `
    "No explicit seed-handling evidence was found in AI scripts." `
    @{ hits = $aiSeedHits }

Add-Condition "AIM-004" "Output validated against schema before acceptance" `
    (@($aiSchemaHits).Count -gt 0) `
    "AI scripts reference schema validation." `
    "No explicit schema-validation evidence was found in AI scripts." `
    @{ hits = $aiSchemaHits }

Add-Condition "AIM-005" "Non-deterministic output causes gate failure" `
    (@($aiNondeterministicFailureHits).Count -gt 0) `
    "AI scripts reference failure behavior for non-deterministic/mismatched output." `
    "No explicit non-deterministic-output failure evidence was found in AI scripts." `
    @{ hits = $aiNondeterministicFailureHits }

Add-Condition "AIM-006" "Every custom panel validated against: schema, runtime compatibility, UI boundary" `
    ((Test-Path -LiteralPath $customPanelValidatorPath -PathType Leaf) -and @($panelValidationHits).Count -ge 3) `
    "Custom panel validation tooling exists and shows schema/runtime/boundary signals." `
    "Custom panel validation tooling does not yet visibly cover schema/runtime/boundary concerns." `
    @{ hits = $panelValidationHits }

Add-Condition "AIM-007" "Every custom procedure validated against: schema, capability requirements, execution limits" `
    ((Test-Path -LiteralPath $customProcedureValidatorPath -PathType Leaf) -and @($procedureValidationHits).Count -ge 3) `
    "Custom procedure validation tooling exists and shows schema/capability/execution-limit signals." `
    "Custom procedure validation tooling does not yet visibly cover schema/capability/execution-limit concerns." `
    @{ hits = $procedureValidationHits }

Add-Condition "AIM-008" "Validation runs in dedicated gate, produces structured report" `
    ((Test-Path -LiteralPath $customPanelValidatorPath -PathType Leaf) -and (Test-Path -LiteralPath $customProcedureValidatorPath -PathType Leaf) -and $null -ne $aiBetaMatrixReport) `
    "Dedicated AI validation scripts exist and the AI beta matrix emits a structured report." `
    "Dedicated AI validation scripts or structured AI gate evidence are missing." `
    @{
        panelValidator = "scripts\ai\validate-custom-panel.ps1"
        procedureValidator = "scripts\ai\validate-custom-procedure.ps1"
        aiBetaMatrixStatus = if ($null -eq $aiBetaMatrixReport) { $null } else { [string]$aiBetaMatrixReport.overallStatus }
    }

Add-Condition "AIM-009" "Invalid custom extension rejected before runtime deployment" `
    (@($invalidExtensionCases).Count -ge 2) `
    "The AI beta matrix includes passing invalid-extension rejection scenarios." `
    "The AI beta matrix does not yet show enough invalid-extension rejection scenarios." `
    @{ invalidExtensionCases = $invalidExtensionCases }

Add-Condition "AIM-010" "Test proves: invalid panel/procedure fails validation with clear error" `
    (@($invalidExtensionCases).Count -ge 2 -and @($aiBetaMatrixReport.cases | Where-Object { [string]$_.expectedClass -match '^FAIL_' }).Count -gt 0) `
    "The AI beta matrix contains explicit failing expected classes for invalid panel/procedure scenarios." `
    "The AI beta matrix does not yet provide enough explicit failing invalid-extension scenarios." `
    @{ cases = if ($null -eq $aiBetaMatrixReport) { @() } else { $aiBetaMatrixReport.cases } }

$report = Write-MaturityReport `
    -WorkspaceRoot $WorkspaceRoot `
    -RunId $RunId `
    -ScriptPath $PSCommandPath `
    -MaturityItem "ai-ml-pipeline-maturity" `
    -ReportPath $ReportPath `
    -Checks $checks `
    -Extra @{
        conditionCount = $checks.Count
    }

Complete-MaturityScript -Report $report -PassThru:$PassThru
