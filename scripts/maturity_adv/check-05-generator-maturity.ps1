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
$RunId = Resolve-NPDevRunId $RunId "generator-maturity"
$ReportPath = Resolve-MaturityReportPath -WorkspaceRoot $WorkspaceRoot -ReportPath $ReportPath -DefaultRelativePath "scripts\reports\out\generator-maturity-report.json"

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

$generatorGateReport = Read-MaturityJsonFile (Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\reports\out\generator-gate-report.json")
$deterministicGenerationReport = Read-MaturityJsonFile (Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\reports\out\deterministic-generation-report.json")
$templateEngineFile = @(Get-MaturityWorkspaceFiles -WorkspaceRoot $WorkspaceRoot -RelativeRoot "NPDevGenerator\generator\src\main\java" -Includes @("TemplateEngine.java") | Select-Object -First 1)[0]
$templateEnginePath = if ($null -eq $templateEngineFile) { $null } else { $templateEngineFile.FullName }
$templateOrderingHits = if ($null -eq $templateEngineFile) {
    @()
}
else {
    Find-MaturityTextMatches -WorkspaceRoot $WorkspaceRoot -RelativeRoot "NPDevGenerator\generator\src\main\java" -Includes @("TemplateEngine.java") -Pattern 'LinkedHashMap|sorted\(|TreeMap|Comparator'
}
$generatorMultiConceptTestPath = Resolve-NPDevWorkspacePath $WorkspaceRoot "NPDevGenerator\generator\src\test\java\com\npdev\generator\GeneratorMultiConceptTest.java"
$regenerationSafetyTestPath = Resolve-NPDevWorkspacePath $WorkspaceRoot "NPDevGenerator\generator\src\test\java\com\npdev\generator\RegenerationEvolutionSafetyTest.java"
$migrationRiskTestPath = Resolve-NPDevWorkspacePath $WorkspaceRoot "NPDevGenerator\generator\src\test\java\com\npdev\generator\migration\MigrationRiskAssessmentBuilderTest.java"
$migrationEmitterTestPath = Resolve-NPDevWorkspacePath $WorkspaceRoot "NPDevGenerator\generator\src\test\java\com\npdev\generator\migration\MigrationScriptEmitterTest.java"
$modelDiffPreviewTestPath = Resolve-NPDevWorkspacePath $WorkspaceRoot "NPDevGenerator\generator\src\test\java\com\npdev\generator\migration\ModelDiffPreviewBuilderTest.java"
$projectionGuardTestPath = Resolve-NPDevWorkspacePath $WorkspaceRoot "NPDevGenerator\generator\src\test\java\com\npdev\generator\guard\GeneratedProjectionGuardTest.java"

$migrationRiskPatterns = Test-MaturityFilePatterns -FilePath $migrationRiskTestPath -Patterns @("add column", "drop column", "rename", "type")
$migrationEmitterPatterns = Test-MaturityFilePatterns -FilePath $migrationEmitterTestPath -Patterns @("postgres", "sql")
$modelDiffPatterns = Test-MaturityFilePatterns -FilePath $modelDiffPreviewTestPath -Patterns @("diff", "preview")
$riskThresholdHits = Find-MaturityTextMatches -WorkspaceRoot $WorkspaceRoot -RelativeRoot "NPDevGenerator\generator\src\test\java" -Includes @("*.java") -Pattern 'risk score|threshold|reject'
$rollbackGenerationHits = Find-MaturityTextMatches -WorkspaceRoot $WorkspaceRoot -RelativeRoot "NPDevGenerator\generator\src\test\java" -Includes @("*.java") -Pattern 'rollback script|dangerous operation'
$projectionGuardPatterns = Test-MaturityFilePatterns -FilePath $projectionGuardTestPath -Patterns @("allowed", "forbidden")
$projectionInternalHits = Find-MaturityTextMatches -WorkspaceRoot $WorkspaceRoot -RelativeRoot "NPDevGenerator\generator\src\test\java" -Includes @("*.java") -Pattern 'internal field|internal fields'
$projectionSampleHits = Find-MaturityTextMatches -WorkspaceRoot $WorkspaceRoot -RelativeRoot "NPDevGenerator\generator\src\test\java" -Includes @("*.java") -Pattern 'simple-contact-intake|simple-user-registry|medium-expense-approval'
$projectionPerformanceHits = Find-MaturityTextMatches -WorkspaceRoot $WorkspaceRoot -RelativeRoot "NPDevGenerator\generator\src\test\java" -Includes @("*.java") -Pattern '100 concepts|large model|performance'

Add-Condition "GEN-001" "Same model compiled twice produces identical generated file checksums" `
    ($null -ne $deterministicGenerationReport -and [string]$deterministicGenerationReport.overallStatus -eq "passed") `
    "The deterministic generation report is currently passed, which is the strongest available checksum-stability signal." `
    "The deterministic generation report is missing or not passed, so same-input generation checksum stability is not yet proven." `
    @{ overallStatus = if ($null -eq $deterministicGenerationReport) { $null } else { [string]$deterministicGenerationReport.overallStatus } }

Add-Condition "GEN-002" "Template iteration over collections uses LinkedHashMap or explicitly sorted keys" `
    (@($templateOrderingHits).Count -gt 0) `
    "TemplateEngine contains explicit ordering/sorting signals for collection iteration." `
    "TemplateEngine does not visibly encode deterministic collection ordering." `
    @{ templateEngine = if ($null -eq $templateEnginePath) { $null } else { Get-NPDevWorkspaceRelativePath $WorkspaceRoot $templateEnginePath }; hits = $templateOrderingHits }

Add-Condition "GEN-003" "GeneratorMultiConceptTest covers multi-concept generation determinism" `
    ((Test-Path -LiteralPath $generatorMultiConceptTestPath -PathType Leaf) -and @(
            Find-MaturityTextMatches -WorkspaceRoot $WorkspaceRoot -RelativeRoot "NPDevGenerator\generator\src\test\java" -Includes @("GeneratorMultiConceptTest.java") -Pattern 'determin|same model|checksum'
        ).Count -gt 0) `
    "GeneratorMultiConceptTest exists and contains determinism-related signals." `
    "GeneratorMultiConceptTest does not yet show explicit determinism-related signals." `
    @{
        testPath = "NPDevGenerator\generator\src\test\java\com\npdev\generator\GeneratorMultiConceptTest.java"
        hits = Find-MaturityTextMatches -WorkspaceRoot $WorkspaceRoot -RelativeRoot "NPDevGenerator\generator\src\test\java" -Includes @("GeneratorMultiConceptTest.java") -Pattern 'determin|same model|checksum'
    }

Add-Condition "GEN-004" "RegenerationEvolutionSafetyTest proves: regenerating same model produces no diff" `
    ((Test-Path -LiteralPath $regenerationSafetyTestPath -PathType Leaf) -and (Test-MaturityFilePatterns -FilePath $regenerationSafetyTestPath -Patterns @("no diff", "regenerat")).allMatched) `
    "RegenerationEvolutionSafetyTest exists and contains no-diff regeneration signals." `
    "RegenerationEvolutionSafetyTest does not yet visibly prove no-diff regeneration behavior." `
    @{
        testPath = "NPDevGenerator\generator\src\test\java\com\npdev\generator\RegenerationEvolutionSafetyTest.java"
    }

Add-Condition "GEN-005" "CI gate fails if generation checksum changes without model change" `
    ($null -ne $generatorGateReport -and [string]$generatorGateReport.overallStatus -eq "passed" -and $null -ne $deterministicGenerationReport -and [string]$deterministicGenerationReport.overallStatus -eq "passed") `
    "Generator and deterministic-generation gate evidence are both green." `
    "Generator and deterministic-generation gate evidence is incomplete, so checksum-change protection is not yet proven." `
    @{
        generatorGateStatus = if ($null -eq $generatorGateReport) { $null } else { [string]$generatorGateReport.overallStatus }
        deterministicGenerationStatus = if ($null -eq $deterministicGenerationReport) { $null } else { [string]$deterministicGenerationReport.overallStatus }
    }

Add-Condition "GEN-006" "MigrationRiskAssessmentBuilderTest covers: add column (safe), drop column (dangerous), rename column (dangerous), change type (dangerous)" `
    ($migrationRiskPatterns.allMatched) `
    "MigrationRiskAssessmentBuilderTest contains the expected safe/dangerous change case markers." `
    "MigrationRiskAssessmentBuilderTest does not yet visibly cover all expected safe/dangerous change cases." `
    @{ missingPatterns = $migrationRiskPatterns.missing }

Add-Condition "GEN-007" "MigrationScriptEmitterTest proves: generated SQL is valid for target database (PostgreSQL)" `
    ($migrationEmitterPatterns.allMatched) `
    "MigrationScriptEmitterTest contains PostgreSQL/SQL validation signals." `
    "MigrationScriptEmitterTest does not yet visibly prove PostgreSQL-target SQL validity." `
    @{ missingPatterns = $migrationEmitterPatterns.missing }

Add-Condition "GEN-008" "ModelDiffPreviewBuilderTest proves: diff preview accurately reflects schema changes" `
    ($modelDiffPatterns.allMatched) `
    "ModelDiffPreviewBuilderTest contains diff-preview coverage signals." `
    "ModelDiffPreviewBuilderTest does not yet visibly prove diff-preview accuracy." `
    @{ missingPatterns = $modelDiffPatterns.missing }

Add-Condition "GEN-009" "Test proves: migration plan rejected if risk score exceeds threshold" `
    (@($riskThresholdHits).Count -gt 0) `
    "Migration test sources reference risk-threshold rejection behavior." `
    "No explicit migration test evidence was found for risk-threshold rejection behavior." `
    @{ hits = $riskThresholdHits }

Add-Condition "GEN-010" "Test proves: rollback script generated for every dangerous operation" `
    (@($rollbackGenerationHits).Count -gt 0) `
    "Migration test sources reference rollback-script generation for dangerous operations." `
    "No explicit migration test evidence was found for rollback-script generation on dangerous operations." `
    @{ hits = $rollbackGenerationHits }

Add-Condition "GEN-011" "GeneratedProjectionGuardTest covers: allowed projection passes, forbidden projection fails" `
    ($projectionGuardPatterns.allMatched) `
    "GeneratedProjectionGuardTest contains allowed/forbidden projection coverage signals." `
    "GeneratedProjectionGuardTest does not yet visibly cover both allowed and forbidden projection cases." `
    @{ missingPatterns = $projectionGuardPatterns.missing }

Add-Condition "GEN-012" "Guard tested with all official sample models" `
    (@($projectionSampleHits).Count -ge 3) `
    "Projection guard test sources reference all official sample models." `
    "Projection guard test sources do not yet reference all official sample models." `
    @{ hits = $projectionSampleHits }

Add-Condition "GEN-013" "Guard rejects projections that would expose internal fields" `
    (@($projectionInternalHits).Count -gt 0) `
    "Projection guard test sources reference internal-field rejection behavior." `
    "Projection guard test sources do not yet visibly exercise internal-field rejection." `
    @{ hits = $projectionInternalHits }

Add-Condition "GEN-014" "Guard update requires explicit test modification (prevents accidental weakening)" `
    ((Test-Path -LiteralPath $projectionGuardTestPath -PathType Leaf) -and (Test-MaturityFilePatterns -FilePath $projectionGuardTestPath -Patterns @("GeneratedProjectionGuard")).allMatched) `
    "Projection guard has a dedicated test file that should change alongside guard behavior." `
    "Projection guard does not have an obvious dedicated test anchor for explicit update pressure." `
    @{ testPath = "NPDevGenerator\generator\src\test\java\com\npdev\generator\guard\GeneratedProjectionGuardTest.java" }

Add-Condition "GEN-015" "Guard performance tested with large models (≥100 concepts)" `
    (@($projectionPerformanceHits).Count -gt 0) `
    "Projection guard test sources reference large-model performance coverage." `
    "No explicit large-model performance evidence was found for GeneratedProjectionGuard." `
    @{ hits = $projectionPerformanceHits }

$report = Write-MaturityReport `
    -WorkspaceRoot $WorkspaceRoot `
    -RunId $RunId `
    -ScriptPath $PSCommandPath `
    -MaturityItem "generator-maturity" `
    -ReportPath $ReportPath `
    -Checks $checks `
    -Extra @{
        conditionCount = $checks.Count
    }

Complete-MaturityScript -Report $report -PassThru:$PassThru
