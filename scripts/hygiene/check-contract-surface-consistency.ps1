[CmdletBinding()]
param(
    [string]$WorkspaceRoot = "",
    [switch]$PassThru,
    [string]$ReportPath = ""
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

. (Join-Path $PSScriptRoot "..\npdev-common.ps1")

if ([string]::IsNullOrWhiteSpace($WorkspaceRoot)) {
    $WorkspaceRoot = Get-NPDevWorkspaceRoot $PSScriptRoot
}
$WorkspaceRoot = Normalize-NPDevPath $WorkspaceRoot

if ([string]::IsNullOrWhiteSpace($ReportPath)) {
    $ReportPath = Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\reports\out\contract-surface-consistency-report.json"
}
else {
    $ReportPath = Normalize-NPDevPath $ReportPath
}

function Read-Text([string]$RelativePath) {
    $path = Resolve-NPDevWorkspacePath $WorkspaceRoot $RelativePath
    Ensure-NPDevFile $path $RelativePath
    return Get-Content -LiteralPath $path -Raw
}

function Test-Contains([string]$Text, [string]$Needle) {
    return $Text.Contains($Needle)
}

$authoringSchema = Read-Text "NPDevContract\schemas\authoring\model.schema.json"
$compiledSchema = Read-Text "NPDevContract\schemas\generator\compiled-model.schema.json"
$modelAst = Read-Text "NPDevContract\dsl\src\main\java\com\npdev\dsl\v1\ast\ModelAst.java"
$compiledModel = Read-Text "NPDevContract\dsl\src\main\java\com\npdev\dsl\v1\compiled\CompiledModel.java"
$parser = Read-Text "NPDevContract\dsl\src\main\java\com\npdev\dsl\v1\parser\JsonModelParser.java"
$compiler = Read-Text "NPDevContract\dsl\src\main\java\com\npdev\dsl\v1\compiler\ModelCompiler.java"
$canonicalWriter = Read-Text "NPDevContract\dsl\src\main\java\com\npdev\dsl\v1\compiled\CompiledModelCanonicalJson.java"
$canonicalReader = Read-Text "NPDevContract\dsl\src\main\java\com\npdev\dsl\v1\compiled\CompiledModelCanonicalJsonReader.java"
$validator = Read-Text "NPDevContract\dsl\src\main\java\com\npdev\dsl\v1\validation\SemanticValidator.java"

$surfaceSpecs = @(
    @{
        name = "queries"
        astType = "QueryAst"
        compiledType = "CompiledQuery"
        parserToken = "parseQueries"
        compilerToken = "getQueries"
        validatorToken = "validateQueries"
    },
    @{
        name = "ruleProfiles"
        astType = "RuleProfileAst"
        compiledType = "CompiledRuleProfile"
        parserToken = "parseRuleProfiles"
        compilerToken = "getRuleProfiles"
        validatorToken = "validateRuleProfiles"
    },
    @{
        name = "procedures"
        astType = "ProcedureAst"
        compiledType = "CompiledProcedure"
        parserToken = "parseProcedures"
        compilerToken = "getProcedures"
        validatorToken = "validateProcedures"
    },
    @{
        name = "panels"
        astType = "PanelAst"
        compiledType = "CompiledPanel"
        parserToken = "parsePanels"
        compilerToken = "getPanels"
        validatorToken = "validatePanels"
    }
)

$findings = @()
foreach ($surface in $surfaceSpecs) {
    $name = [string]$surface.name
    $schemaExposes = Test-Contains $authoringSchema ('"' + $name + '"')
    if (-not $schemaExposes) {
        continue
    }

    $checks = @(
        @{ area = "compiled-schema"; passed = (Test-Contains $compiledSchema ('"' + $name + '"')); expected = "Compiled schema exposes $name." },
        @{ area = "model-ast"; passed = (Test-Contains $modelAst ([string]$surface.astType)); expected = "ModelAst carries $($surface.astType)." },
        @{ area = "compiled-model"; passed = (Test-Contains $compiledModel ([string]$surface.compiledType)); expected = "CompiledModel carries $($surface.compiledType)." },
        @{ area = "parser"; passed = (Test-Contains $parser ([string]$surface.parserToken)); expected = "JsonModelParser parses $name." },
        @{ area = "compiler"; passed = (Test-Contains $compiler ([string]$surface.compilerToken)) -and (Test-Contains $compiler ([string]$surface.compiledType)); expected = "ModelCompiler compiles $name." },
        @{ area = "canonical-writer"; passed = (Test-Contains $canonicalWriter ('"' + $name + '"')) -and (Test-Contains $canonicalWriter ([string]$surface.compiledType)); expected = "Canonical writer emits $name." },
        @{ area = "canonical-reader"; passed = (Test-Contains $canonicalReader ('"' + $name + '"')) -and (Test-Contains $canonicalReader ([string]$surface.compiledType)); expected = "Canonical reader imports $name." },
        @{ area = "semantic-validator"; passed = (Test-Contains $validator ([string]$surface.validatorToken)); expected = "Semantic validator validates $name." }
    )

    foreach ($check in $checks) {
        if (-not [bool]$check.passed) {
            $findings += [pscustomobject]@{
                surface = $name
                area = [string]$check.area
                expected = [string]$check.expected
            }
        }
    }
}

$conceptsCanonical = (Test-Contains $authoringSchema '"concepts"') -and
    (Test-Contains $canonicalWriter 'root.set("concepts"') -and
    (-not (Test-Contains $canonicalWriter 'root.set("entities"'))
if (-not $conceptsCanonical) {
    $findings += [pscustomobject]@{
        surface = "concepts"
        area = "canonical-writer"
        expected = "Canonical writer emits concepts and not entities."
    }
}

$status = if ($findings.Count -eq 0) { "passed" } else { "failed" }
$result = New-NPDevCheckResult "contract-surface-consistency" $status `
    $(if ($status -eq "passed") { "Contract surface consistency is closed." } else { "Contract surface consistency has gaps." }) `
    @{ findings = $findings }

Write-NPDevJsonFile $ReportPath ([pscustomobject]@{
        generatedAt = (Get-Date).ToString("o")
        workspaceRoot = $WorkspaceRoot
        overallStatus = $result.status
        result = $result
    })

if ($PassThru) {
    return $result
}
if ($result.status -eq "passed") {
    Write-NPDevOk $result.summary
    return
}

Write-NPDevWarn $result.summary
throw $result.summary
