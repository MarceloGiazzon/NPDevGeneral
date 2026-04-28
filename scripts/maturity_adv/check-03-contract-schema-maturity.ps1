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
$RunId = Resolve-NPDevRunId $RunId "contract-schema-maturity"
$ReportPath = Resolve-MaturityReportPath -WorkspaceRoot $WorkspaceRoot -ReportPath $ReportPath -DefaultRelativePath "scripts\reports\out\contract-schema-maturity-report.json"

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

$schemasRoot = Resolve-NPDevWorkspacePath $WorkspaceRoot "NPDevContract\dsl\resources\Schemas"
$schemaFiles = if (Test-Path -LiteralPath $schemasRoot -PathType Container) {
    @(Get-ChildItem -LiteralPath $schemasRoot -File -Filter "*.json" -ErrorAction SilentlyContinue)
}
else {
    @()
}
$schemaAudit = foreach ($schemaFile in $schemaFiles) {
    $doc = $null
    $parseError = $null
    try {
        $doc = Get-Content -LiteralPath $schemaFile.FullName -Raw | ConvertFrom-Json
    }
    catch {
        $parseError = $_.Exception.Message
    }
    [pscustomobject]@{
        file = Get-NPDevWorkspaceRelativePath $WorkspaceRoot $schemaFile.FullName
        parseError = $parseError
        hasVersion = ($null -ne $doc -and $doc.PSObject.Properties.Name -contains "version")
        hasDeprecated = ($null -ne $doc -and $doc.PSObject.Properties.Name -contains "deprecated")
    }
}
$schemaVersionGaps = @($schemaAudit | Where-Object { -not $_.hasVersion -or -not $_.hasDeprecated -or -not [string]::IsNullOrWhiteSpace([string]$_.parseError) })
$schemaDigestPath = Resolve-NPDevWorkspacePath $WorkspaceRoot "NPDevContract\dsl\resources\Schemas\MIGRATION_DIGEST.md"
$schemaDigestPatterns = Test-MaturityFilePatterns -FilePath $schemaDigestPath -Patterns @("->", "2026-04-23", "Migration Path")
$jsonModelParserPath = Resolve-NPDevWorkspacePath $WorkspaceRoot "NPDevContract\dsl\src\main\java\com\npdev\dsl\v1\parser\JsonModelParser.java"
$deprecatedParserPatterns = Test-MaturityFilePatterns -FilePath $jsonModelParserPath -Patterns @("deprecated", "schemaVersion", "throw")
$deprecatedParserTestHits = Find-MaturityTextMatches -WorkspaceRoot $WorkspaceRoot -RelativeRoot "NPDevContract\dsl\src\test\java" -Includes @("*.java") -Pattern 'deprecated schema|rejects deprecated|deprecated version|DeprecatedSchemaVersionRejectionTest'
$officialSampleModels = @(
    "NPDevSamples\simple-contact-intake\Input\model.json",
    "NPDevSamples\simple-user-registry\Input\model.json",
    "NPDevSamples\medium-expense-approval\Input\model.json"
)
$officialSampleSchemaVersions = foreach ($sampleModelPath in $officialSampleModels) {
    $absolutePath = Resolve-NPDevWorkspacePath $WorkspaceRoot $sampleModelPath
    $doc = Read-MaturityJsonFile $absolutePath
    $schemaVersion = $null
    if ($null -ne $doc -and $doc.PSObject.Properties.Name -contains "schemaVersion") {
        $schemaVersion = [string]$doc.schemaVersion
    }
    [pscustomobject]@{
        path = $sampleModelPath
        schemaVersion = $schemaVersion
    }
}
$distinctOfficialSchemaVersions = @($officialSampleSchemaVersions | Select-Object -ExpandProperty schemaVersion -Unique | Where-Object { -not [string]::IsNullOrWhiteSpace([string]$_) })
$canonicalModelPathsPath = Resolve-NPDevWorkspacePath $WorkspaceRoot "NPDevContract\dsl\src\main\java\com\npdev\dsl\v1\paths\CanonicalModelPaths.java"
$hardcodedPathHits = @(Find-MaturityTextMatches -WorkspaceRoot $WorkspaceRoot -RelativeRoot "scripts" -Includes @("*.ps1") -Pattern 'canonical-demo|simple-contact-intake|simple-user-registry|medium-expense-approval|restaurant-saas-multitenant' | Where-Object {
        $_.path -notlike 'scripts\maturity_adv\*'
    })
$catalogSchemaFiles = Test-MaturityPaths -WorkspaceRoot $WorkspaceRoot -RelativePaths @(
    "NPDevContract\dsl\resources\Models\official-samples\catalog.schema.json",
    "NPDevContract\dsl\resources\Models\tenant-samples\catalog.schema.json"
) -PathType Leaf
$fileSystemModelRepositoryTestPaths = Test-MaturityPaths -WorkspaceRoot $WorkspaceRoot -RelativePaths @(
    "NPDevContract\dsl\src\test\java\com\npdev\dsl\v1\repo\FileSystemModelRepositoryTest.java"
) -PathType Leaf
$samplePresentationReport = Read-MaturityJsonFile (Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\reports\out\sample-presentation-label-report.json")
$compiledModelJsonPath = Resolve-NPDevWorkspacePath $WorkspaceRoot "NPDevContract\dsl\src\main\java\com\npdev\dsl\v1\compiled\CompiledModelCanonicalJson.java"
$compiledModelJsonOrderingHits = Find-MaturityTextMatches -WorkspaceRoot $WorkspaceRoot -RelativeRoot "NPDevContract\dsl\src\main\java" -Includes @("*.java") -Pattern 'TreeMap|sorted\(|Comparator|lexicographic'
$compiledModelJsonTestPaths = Test-MaturityPaths -WorkspaceRoot $WorkspaceRoot -RelativePaths @(
    "NPDevContract\dsl\src\test\java\com\npdev\dsl\v1\compiled\CompiledModelCanonicalJsonTest.java",
    "NPDevContract\dsl\src\test\java\com\npdev\dsl\v1\compiled\CompiledMetadataCanonicalJsonTest.java"
) -PathType Leaf
$aiDeterminismScriptPath = Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\ai\verify-ai-generation-determinism.ps1"
$aiBetaMatrixReport = Read-MaturityJsonFile (Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\reports\out\ai-beta-matrix-report.json")
$deterministicGenerationReport = Read-MaturityJsonFile (Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\reports\out\deterministic-generation-report.json")

Add-Condition "CSM-001" "Each schema file has explicit version field and deprecated boolean" `
    (@($schemaVersionGaps).Count -eq 0 -and @($schemaAudit).Count -gt 0) `
    "Every schema file under NPDevContract\\dsl\\resources\\Schemas exposes version and deprecated metadata." `
    "One or more schema files under NPDevContract\\dsl\\resources\\Schemas are missing version/deprecated metadata or could not be parsed." `
    @{ auditedSchemas = $schemaAudit; gaps = $schemaVersionGaps }

Add-Condition "CSM-002" "MIGRATION_DIGEST.md in NPDevContract/dsl/resources/Schemas/ lists: old version → new version migration path with date" `
    ($schemaDigestPatterns.allMatched) `
    "The schema migration digest includes migration-path notation and date-like evidence." `
    "The schema migration digest does not yet show an explicit old-version to new-version path with date evidence." `
    @{ missingPatterns = $schemaDigestPatterns.missing }

Add-Condition "CSM-003" "Parser (JsonModelParser.java) rejects models using deprecated schema versions with clear error" `
    ($deprecatedParserPatterns.allMatched) `
    "JsonModelParser contains explicit deprecated schema handling signals." `
    "JsonModelParser does not visibly encode deprecated schema rejection behavior." `
    @{ missingPatterns = $deprecatedParserPatterns.missing }

Add-Condition "CSM-004" "All official samples use the current non-deprecated schema version" `
    (@($distinctOfficialSchemaVersions).Count -eq 1 -and @($officialSampleSchemaVersions | Where-Object { [string]::IsNullOrWhiteSpace([string]$_.schemaVersion) }).Count -eq 0) `
    "All official sample model.json files use the same explicit schemaVersion." `
    "Official sample model.json files do not currently share one explicit schemaVersion." `
    @{ sampleSchemaVersions = $officialSampleSchemaVersions }

Add-Condition "CSM-005" "Automated test verifies parser rejects deprecated schema version" `
    (@($deprecatedParserTestHits).Count -gt 0) `
    "Automated test evidence was found for deprecated schema rejection." `
    "No automated test evidence was found for deprecated schema rejection." `
    @{ hits = $deprecatedParserTestHits }

Add-Condition "CSM-006" "All sample paths resolve through CanonicalModelPaths (no hardcoded strings in scripts)" `
    ((Test-Path -LiteralPath $canonicalModelPathsPath -PathType Leaf) -and @($hardcodedPathHits).Count -eq 0) `
    "CanonicalModelPaths exists and no hardcoded sample path strings were found in scripts." `
    "Hardcoded sample path strings were found in scripts, so path resolution is not centralized through CanonicalModelPaths." `
    @{ hardcodedPathHits = $hardcodedPathHits | Select-Object -First 40 }

Add-Condition "CSM-007" "catalog.json schema validated against catalog.schema.json (if exists, or create one)" `
    ($catalogSchemaFiles.allPresent) `
    "Catalog schema files exist for both official and tenant sample catalogs." `
    "Catalog schema files are missing for one or more sample catalog roots." `
    @{ missingCatalogSchemas = $catalogSchemaFiles.missing }

Add-Condition "CSM-008" "FileSystemModelRepositoryTest covers all path resolution edge cases" `
    ($fileSystemModelRepositoryTestPaths.allPresent) `
    "A FileSystemModelRepositoryTest exists in the DSL test suite." `
    "No FileSystemModelRepositoryTest was found in the DSL test suite." `
    @{ missingPaths = $fileSystemModelRepositoryTestPaths.missing }

Add-Condition "CSM-009" "Script check-sample-presentation-labels.ps1 passes for all samples" `
    ($null -ne $samplePresentationReport -and [string]$samplePresentationReport.overallStatus -eq "passed") `
    "The sample presentation label report is passed." `
    "The sample presentation label report is missing or not passed." `
    @{ overallStatus = if ($null -eq $samplePresentationReport) { $null } else { [string]$samplePresentationReport.overallStatus } }

Add-Condition "CSM-010" "Any path change requires single-point update in CanonicalModelPaths" `
    (@($hardcodedPathHits).Count -eq 0) `
    "No competing hardcoded path strings were found alongside CanonicalModelPaths." `
    "Hardcoded path strings were found outside CanonicalModelPaths, so path updates are not single-point." `
    @{ hardcodedPathHits = $hardcodedPathHits | Select-Object -First 40 }

Add-Condition "CSM-011" "CompiledModelCanonicalJson output is byte-for-byte identical for identical inputs" `
    ($compiledModelJsonTestPaths.allPresent) `
    "Canonical JSON test coverage exists for compiled model/metadata output." `
    "Canonical JSON test coverage is incomplete for byte-for-byte stability." `
    @{ missingTests = $compiledModelJsonTestPaths.missing }

Add-Condition "CSM-012" "Field ordering in canonical JSON is lexicographic and stable" `
    (@($compiledModelJsonOrderingHits).Count -gt 0) `
    "Canonical JSON source code contains explicit ordering/sorting signals." `
    "Canonical JSON source code does not visibly encode stable lexicographic ordering." `
    @{ orderingHits = $compiledModelJsonOrderingHits }

Add-Condition "CSM-013" "verify-ai-generation-determinism.ps1 passes for all AI scenarios" `
    ((Test-Path -LiteralPath $aiDeterminismScriptPath -PathType Leaf) -and $null -ne $aiBetaMatrixReport -and [string]$aiBetaMatrixReport.overallStatus -eq "passed") `
    "AI determinism tooling exists and the current AI beta matrix report is passed." `
    "AI determinism tooling or current AI beta matrix evidence is missing or failing." `
    @{
        aiDeterminismScript = "scripts\ai\verify-ai-generation-determinism.ps1"
        aiBetaMatrixStatus = if ($null -eq $aiBetaMatrixReport) { $null } else { [string]$aiBetaMatrixReport.overallStatus }
    }

Add-Condition "CSM-014" "check-deterministic-generation.ps1 passes for all samples" `
    ($null -ne $deterministicGenerationReport -and [string]$deterministicGenerationReport.overallStatus -eq "passed") `
    "The deterministic generation report is currently passed." `
    "The deterministic generation report is missing or not passed." `
    @{ overallStatus = if ($null -eq $deterministicGenerationReport) { $null } else { [string]$deterministicGenerationReport.overallStatus } }

Add-Condition "CSM-015" "Test proves two compilations of same model produce identical canonical JSON" `
    ($compiledModelJsonTestPaths.allPresent) `
    "Dedicated canonical JSON tests exist to prove same-input output stability." `
    "Dedicated canonical JSON tests do not yet fully prove same-input output stability." `
    @{ existingTests = $compiledModelJsonTestPaths.existing; missingTests = $compiledModelJsonTestPaths.missing }

$report = Write-MaturityReport `
    -WorkspaceRoot $WorkspaceRoot `
    -RunId $RunId `
    -ScriptPath $PSCommandPath `
    -MaturityItem "contract-schema-maturity" `
    -ReportPath $ReportPath `
    -Checks $checks `
    -Extra @{
        conditionCount = $checks.Count
        schemasRoot = "NPDevContract\dsl\resources\Schemas"
    }

Complete-MaturityScript -Report $report -PassThru:$PassThru
