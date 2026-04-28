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
    $ReportPath = Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\reports\out\legacy-entity-surface-report.json"
}
else {
    $ReportPath = Normalize-NPDevPath $ReportPath
}

$findings = @()

function Add-LegacyFinding([string]$Area, [string]$Path, [string]$Issue, [Nullable[int]]$LineNumber = $null, [string]$Line = "") {
    $finding = [ordered]@{
        area = $Area
        path = $Path
        issue = $Issue
    }
    if ($null -ne $LineNumber) {
        $finding.lineNumber = $LineNumber
    }
    if (-not [string]::IsNullOrWhiteSpace($Line)) {
        $finding.line = $Line.Trim()
    }
    $script:findings += [pscustomobject]$finding
}

function Get-CanonicalFiles([string[]]$RelativeRoots, [string[]]$Includes) {
    $out = @()
    foreach ($relativeRoot in $RelativeRoots) {
        $root = Resolve-NPDevWorkspacePath $WorkspaceRoot $relativeRoot
        if (Test-Path -LiteralPath $root -PathType Leaf) {
            $out += Get-Item -LiteralPath $root
            continue
        }
        if (-not (Test-Path -LiteralPath $root -PathType Container)) {
            continue
        }
        $out += Get-ChildItem -LiteralPath $root -Recurse -File -ErrorAction SilentlyContinue | Where-Object {
            $file = $_
            @($Includes | Where-Object { $file.Name -like $_ }).Count -gt 0 -and
                $file.FullName -notmatch "\\Output\\" -and
                $file.FullName -notmatch "\\build\\" -and
                $file.FullName -notmatch "\\node_modules\\" -and
                $file.FullName -notmatch "\\dist\\"
        }
    }
    return @($out | Sort-Object FullName -Unique)
}

$canonicalJsonRoots = @(
    "NPDevSamples",
    "NPDevContract\examples",
    "NPDevGenerator\resources\Models",
    "NPDevContract\dsl\resources\Models"
)

$canonicalJsonFiles = Get-CanonicalFiles $canonicalJsonRoots @("*.json", "*.model.json")
foreach ($file in $canonicalJsonFiles) {
    $raw = Get-Content -LiteralPath $file.FullName -Raw
    $relative = Get-NPDevWorkspaceRelativePath $WorkspaceRoot $file.FullName
    if ($raw -match '"entities"\s*:') {
        Add-LegacyFinding "canonical-json" $relative "Canonical JSON must write top-level concepts, not entities."
    }
    if ($raw -match '"type"\s*:\s*"[^"]*Entity"') {
        Add-LegacyFinding "canonical-json" $relative "Canonical workflow/action types must use Concept language, not Entity."
    }
    if ($raw -match '"actionType"\s*:\s*"[^"]*Entity"') {
        Add-LegacyFinding "canonical-json" $relative "Canonical metadata action types must use Concept language, not Entity."
    }
}

$canonicalDocs = Get-CanonicalFiles @("NPDevSamples", "NPDevContract\examples", "NPDevContract\docs") @("*.md")
foreach ($file in $canonicalDocs) {
    foreach ($hit in (Select-String -LiteralPath $file.FullName -Pattern '(?i)(?<![A-Za-z])(entities|entity)(?![A-Za-z])' -ErrorAction SilentlyContinue)) {
        $line = $hit.Line.Trim()
        if ($line -match "legacy|compat|alias|EntityAst|CompiledEntity|JPA|HttpEntity") {
            continue
        }
        $findings += [pscustomobject]@{
            area = "canonical-doc"
            path = Get-NPDevWorkspaceRelativePath $WorkspaceRoot $hit.Path
            lineNumber = $hit.LineNumber
            issue = "Canonical docs/samples must not use entity as product language."
            line = $line
        }
    }
}

$compiledModelPath = Resolve-NPDevWorkspacePath $WorkspaceRoot "NPDevContract\dsl\src\main\java\com\npdev\dsl\v1\compiled\CompiledModel.java"
Ensure-NPDevFile $compiledModelPath "CompiledModel"
$compiledModel = Get-Content -LiteralPath $compiledModelPath -Raw
foreach ($token in @("getConcepts()", "findConcept(String name)", "getEntities()", "findEntity(String name)", "@Deprecated")) {
    if (-not $compiledModel.Contains($token)) {
        Add-LegacyFinding "compiled-api" (Get-NPDevWorkspaceRelativePath $WorkspaceRoot $compiledModelPath) "CompiledModel must expose concept-first APIs and keep entity APIs only as deprecated compatibility shims: missing $token."
    }
}

$conceptAstPath = Resolve-NPDevWorkspacePath $WorkspaceRoot "NPDevContract\dsl\src\main\java\com\npdev\dsl\v1\ast\ConceptAst.java"
Ensure-NPDevFile $conceptAstPath "ConceptAst"
$conceptAst = Get-Content -LiteralPath $conceptAstPath -Raw
if (-not $conceptAst.Contains("class ConceptAst extends EntityAst")) {
    Add-LegacyFinding "compiled-api" (Get-NPDevWorkspaceRelativePath $WorkspaceRoot $conceptAstPath) "ConceptAst must be the canonical AST business-object type."
}

$entityAstPath = Resolve-NPDevWorkspacePath $WorkspaceRoot "NPDevContract\dsl\src\main\java\com\npdev\dsl\v1\ast\EntityAst.java"
Ensure-NPDevFile $entityAstPath "EntityAst"
$entityAst = Get-Content -LiteralPath $entityAstPath -Raw
if (-not $entityAst.Contains("@Deprecated(forRemoval = false)") -or $entityAst.Contains("public final class EntityAst")) {
    Add-LegacyFinding "compiled-api" (Get-NPDevWorkspaceRelativePath $WorkspaceRoot $entityAstPath) "EntityAst must remain only as a deprecated compatibility adapter."
}

$modelAstPath = Resolve-NPDevWorkspacePath $WorkspaceRoot "NPDevContract\dsl\src\main\java\com\npdev\dsl\v1\ast\ModelAst.java"
Ensure-NPDevFile $modelAstPath "ModelAst"
$modelAst = Get-Content -LiteralPath $modelAstPath -Raw
foreach ($token in @("private final List<ConceptAst> concepts", "getConcepts()", "ConceptAst.fromLegacyEntity", "getEntities()", "@Deprecated")) {
    if (-not $modelAst.Contains($token)) {
        Add-LegacyFinding "compiled-api" (Get-NPDevWorkspaceRelativePath $WorkspaceRoot $modelAstPath) "ModelAst must store concepts canonically and keep entity access only as a compatibility shim: missing $token."
    }
}

$parserPath = Resolve-NPDevWorkspacePath $WorkspaceRoot "NPDevContract\dsl\src\main\java\com\npdev\dsl\v1\parser\JsonModelParser.java"
Ensure-NPDevFile $parserPath "JsonModelParser"
$parser = Get-Content -LiteralPath $parserPath -Raw
foreach ($token in @("List<ConceptAst> concepts", "new ConceptAst(")) {
    if (-not $parser.Contains($token)) {
        Add-LegacyFinding "compiled-api" (Get-NPDevWorkspaceRelativePath $WorkspaceRoot $parserPath) "Parser must canonicalize authored/imported business objects into ConceptAst: missing $token."
    }
}

$compilerPath = Resolve-NPDevWorkspacePath $WorkspaceRoot "NPDevContract\dsl\src\main\java\com\npdev\dsl\v1\compiler\ModelCompiler.java"
Ensure-NPDevFile $compilerPath "ModelCompiler"
$compiler = Get-Content -LiteralPath $compilerPath -Raw
if (-not $compiler.Contains("modelAst.getConcepts()") -or $compiler.Contains("modelAst.getEntities()")) {
    Add-LegacyFinding "compiled-api" (Get-NPDevWorkspaceRelativePath $WorkspaceRoot $compilerPath) "ModelCompiler must consume concept-centered AST collections, not legacy entity collections."
}

$resolverPath = Resolve-NPDevWorkspacePath $WorkspaceRoot "NPDevContract\dsl\src\main\java\com\npdev\dsl\v1\resolution\ModelResolver.java"
Ensure-NPDevFile $resolverPath "ModelResolver"
$resolver = Get-Content -LiteralPath $resolverPath -Raw
if (-not $resolver.Contains("source.getConcepts()") -or -not $resolver.Contains("Map<String, ConceptAst> conceptsByName")) {
    Add-LegacyFinding "compiled-api" (Get-NPDevWorkspaceRelativePath $WorkspaceRoot $resolverPath) "ModelResolver must resolve ConceptAst collections as the primary canonical flow."
}

$bundleIoPath = Resolve-NPDevWorkspacePath $WorkspaceRoot "NPDevEditor\ui-react\src\authoring\io\bundleIoService.ts"
Ensure-NPDevFile $bundleIoPath "Editor bundle IO service"
$bundleIo = Get-Content -LiteralPath $bundleIoPath -Raw
if (-not $bundleIo.Contains("toCanonicalModelDocument(bundle.model)")) {
    $findings += [pscustomobject]@{
        area = "editor-export"
        path = Get-NPDevWorkspaceRelativePath $WorkspaceRoot $bundleIoPath
        issue = "Editor export must canonicalize internal entities to top-level concepts."
    }
}
if (-not $bundleIo.Contains("toInternalModelDocument")) {
    $findings += [pscustomobject]@{
        area = "editor-import"
        path = Get-NPDevWorkspaceRelativePath $WorkspaceRoot $bundleIoPath
        issue = "Editor import must keep legacy entity compatibility explicit."
    }
}

$status = if ($findings.Count -eq 0) { "passed" } else { "failed" }
$result = New-NPDevCheckResult "legacy-entity-surface" $status `
    $(if ($status -eq "passed") { "No canonical legacy entity write surface was detected." } else { "Legacy entity write surface was detected." }) `
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
