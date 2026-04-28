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
    $ReportPath = Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\reports\out\entity-canonical-surface-report.json"
}
else {
    $ReportPath = Normalize-NPDevPath $ReportPath
}

function Resolve-RelativePath([string]$RelativePath) {
    return Resolve-NPDevWorkspacePath $WorkspaceRoot $RelativePath
}

function Get-JsonProperty([object]$Object, [string]$Name, [object]$DefaultValue = $null) {
    if ($null -eq $Object) {
        return $DefaultValue
    }
    if (@($Object.PSObject.Properties.Name) -contains $Name) {
        return $Object.$Name
    }
    return $DefaultValue
}

function New-Check {
    param(
        [string]$Id,
        [string]$Name,
        [bool]$Passed,
        [string]$Evidence,
        [string]$Blocker
    )

    return [pscustomobject]@{
        id = $Id
        name = $Name
        status = if ($Passed) { "passed" } else { "failed" }
        evidence = $Evidence
        blocker = if ($Passed) { $null } else { $Blocker }
    }
}

function Get-CanonicalJsonFiles([string[]]$RelativeRoots) {
    $files = @()
    foreach ($relativeRoot in $RelativeRoots) {
        $root = Resolve-RelativePath $relativeRoot
        if (-not (Test-Path -LiteralPath $root)) {
            continue
        }
        $files += Get-ChildItem -LiteralPath $root -Recurse -File -Force -Include "*.json" | Where-Object {
            $_.FullName -notmatch "\\Output\\" -and
            $_.FullName -notmatch "\\build\\" -and
            $_.FullName -notmatch "\\node_modules\\"
        }
    }
    return @($files | Sort-Object FullName -Unique)
}

function Find-TextMatches([string[]]$RelativeRoots, [string[]]$Includes, [string]$Pattern) {
    $hits = @()
    foreach ($relativeRoot in $RelativeRoots) {
        $root = Resolve-RelativePath $relativeRoot
        if (-not (Test-Path -LiteralPath $root -PathType Container)) {
            continue
        }
        $files = Get-ChildItem -LiteralPath $root -Recurse -File -Force -Include $Includes | Where-Object {
            $_.FullName -notmatch "\\build\\" -and
            $_.FullName -notmatch "\\node_modules\\" -and
            $_.FullName -notmatch "\\dist\\" -and
            $_.FullName -notmatch "\\Output\\" -and
            $_.FullName -notmatch "\\src\\test\\"
        }
        foreach ($hit in ($files | Select-String -Pattern $Pattern -CaseSensitive -ErrorAction SilentlyContinue)) {
            $hits += [pscustomobject]@{
                path = Get-NPDevWorkspaceRelativePath $WorkspaceRoot $hit.Path
                lineNumber = $hit.LineNumber
                line = $hit.Line.Trim()
            }
        }
    }
    return @($hits | Sort-Object path, lineNumber -Unique)
}

$legacyScript = Resolve-RelativePath "scripts\hygiene\check-no-legacy-entity-surface.ps1"
Ensure-NPDevFile $legacyScript "Legacy entity surface gate"
$legacyResult = & $legacyScript -WorkspaceRoot $WorkspaceRoot -PassThru

$canonicalJsonFindings = @()
$canonicalJsonFiles = Get-CanonicalJsonFiles @(
    "NPDevSamples",
    "NPDevContract\examples",
    "NPDevGenerator\resources\Models",
    "NPDevContract\dsl\resources\Models"
)
foreach ($file in $canonicalJsonFiles) {
    $raw = Get-Content -LiteralPath $file.FullName -Raw
    if ($raw -match '"entities"\s*:') {
        $canonicalJsonFindings += [pscustomobject]@{
            path = Get-NPDevWorkspaceRelativePath $WorkspaceRoot $file.FullName
            issue = "Canonical JSON must emit top-level concepts only."
        }
    }
}

$semanticValidatorPath = "NPDevContract\dsl\src\main\java\com\npdev\dsl\v1\validation\SemanticValidator.java"
$semanticValidator = Get-Content -LiteralPath (Resolve-RelativePath $semanticValidatorPath) -Raw
$diagnosticPath = "NPDevContract\dsl\src\main\java\com\npdev\dsl\v1\validation\ValidationDiagnostic.java"
$diagnostic = Get-Content -LiteralPath (Resolve-RelativePath $diagnosticPath) -Raw
$normalizerPath = "NPDevContract\dsl\src\main\java\com\npdev\dsl\v1\validation\ValidationDiagnosticNormalizer.java"
$normalizer = Get-Content -LiteralPath (Resolve-RelativePath $normalizerPath) -Raw

$uiStringHits = Find-TextMatches `
    -RelativeRoots @("NPDevEditor\ui-react\src") `
    -Includes @("*.tsx", "*.ts") `
    -Pattern '("[^"]*\bEntity\b[^"]*"|>[^<]*\bEntity\b[^<]*<)'

$uiStringFindings = @($uiStringHits | Where-Object {
        $_.path -notmatch "\\src\\.*\.test\.tsx?$" -and
        $_.line -notmatch "AuthoringEntity|EntityAst|CompiledEntity|entity compatibility|legacy entities|HttpEntity|ResponseEntity|identity"
    })

$checks = @(
    (New-Check "ECS01" "Legacy entity compatibility gate is green" ([string]$legacyResult.status -eq "passed") ("legacy-entity-surface=" + $legacyResult.status) "Keep entity support compatibility-only.")
    (New-Check "ECS02" "Canonical JSON emits concepts only" ($canonicalJsonFindings.Count -eq 0) ("Canonical JSON findings: " + $canonicalJsonFindings.Count) "Rewrite canonical samples/examples/resources to top-level concepts.")
    (New-Check "ECS03" "Semantic validator canonicalizes diagnostic terminology" ($semanticValidator.Contains("canonicalizeConceptTerminology") -and $diagnostic.Contains("canonicalizeConceptTerminology")) "Semantic errors and diagnostics must expose Concept terminology." "Normalize public validation messages to Concept terminology.")
    (New-Check "ECS04" "Diagnostic normalizer accepts concept-first messages" ($normalizer.Contains("CONCEPT_FIELD_PATTERN") -and $normalizer.Contains("CONCEPT_INVARIANT_PATTERN") -and $normalizer.Contains("CONCEPT_PATTERN")) "Diagnostic normalizer must understand Concept messages directly." "Update validation diagnostic parsing to concept-first patterns.")
    (New-Check "ECS05" "Editor user-visible strings are concept-first" ($uiStringFindings.Count -eq 0) ("User-visible entity string findings: " + $uiStringFindings.Count) "Replace Editor user-facing Entity language with Concept language.")
)

$failed = @($checks | Where-Object { $_.status -ne "passed" })
$report = [pscustomobject]@{
    generatedAt = (Get-Date).ToString("o")
    workspaceRoot = $WorkspaceRoot
    overallStatus = if ($failed.Count -eq 0) { "passed" } else { "failed" }
    checks = $checks
    findings = [pscustomobject]@{
        canonicalJson = $canonicalJsonFindings
        editorUserVisibleStrings = $uiStringFindings
    }
    blockers = @($failed | Select-Object id, name, blocker)
}

Write-NPDevJsonFile $ReportPath $report

if ($PassThru) {
    return (New-NPDevCheckResult "entity-canonical-surface" $report.overallStatus `
            $(if ($report.overallStatus -eq "passed") { "Entity canonical surface check passed." } else { "Entity canonical surface check failed." }) `
            @{ report = $report })
}

if ($report.overallStatus -eq "passed") {
    Write-NPDevOk "Entity canonical surface check passed."
    return
}

Write-NPDevWarn ("Entity canonical surface check failed with " + $failed.Count + " blocker(s).")
throw "Entity canonical surface check failed."
