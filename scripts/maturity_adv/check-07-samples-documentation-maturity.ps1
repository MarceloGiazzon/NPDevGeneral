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
$RunId = Resolve-NPDevRunId $RunId "samples-documentation-maturity"
$ReportPath = Resolve-MaturityReportPath -WorkspaceRoot $WorkspaceRoot -ReportPath $ReportPath -DefaultRelativePath "scripts\reports\out\samples-documentation-maturity-report.json"

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

$releaseSamples = @("canonical-demo", "simple-contact-intake", "simple-user-registry", "medium-expense-approval", "restaurant-saas-multitenant")
$sampleFileAudit = foreach ($sampleId in $releaseSamples) {
    [pscustomobject]@{
        sampleId = $sampleId
        manifest = Test-Path -LiteralPath (Resolve-NPDevWorkspacePath $WorkspaceRoot ("NPDevSamples\" + $sampleId + "\Input\manifest.json")) -PathType Leaf
        expectedBehavior = Test-Path -LiteralPath (Resolve-NPDevWorkspacePath $WorkspaceRoot ("NPDevSamples\" + $sampleId + "\Input\expected-behavior.md")) -PathType Leaf
        expectedDiagnostics = Test-Path -LiteralPath (Resolve-NPDevWorkspacePath $WorkspaceRoot ("NPDevSamples\" + $sampleId + "\Input\expected-diagnostics.md")) -PathType Leaf
        expectedEndpoints = Test-Path -LiteralPath (Resolve-NPDevWorkspacePath $WorkspaceRoot ("NPDevSamples\" + $sampleId + "\Input\expected-endpoints.md")) -PathType Leaf
    }
}
$samplesMissingCoreFiles = @($sampleFileAudit | Where-Object { -not $_.manifest -or -not $_.expectedBehavior -or -not $_.expectedDiagnostics -or -not $_.expectedEndpoints })
$manifestSchemaPaths = Test-MaturityPaths -WorkspaceRoot $WorkspaceRoot -RelativePaths @(
    "NPDevSamples\manifest.schema.json",
    "NPDevContract\schemas\manifest.schema.json"
) -PathType Leaf
$behaviorJourneyAudit = foreach ($sampleId in $releaseSamples) {
    $pathValue = Resolve-NPDevWorkspacePath $WorkspaceRoot ("NPDevSamples\" + $sampleId + "\Input\expected-behavior.md")
    $content = if (Test-Path -LiteralPath $pathValue -PathType Leaf) { Get-Content -LiteralPath $pathValue } else { @() }
    $journeyLines = @($content | Where-Object { $_ -match '^\s*[-*]\s+' -or $_ -match '^\s*\d+\.' })
    [pscustomobject]@{
        sampleId = $sampleId
        journeyCount = @($journeyLines).Count
        path = Get-NPDevWorkspaceRelativePath $WorkspaceRoot $pathValue
    }
}
$behaviorJourneyGaps = @($behaviorJourneyAudit | Where-Object { $_.journeyCount -lt 3 })
$diagnosticAudit = foreach ($sampleId in $releaseSamples) {
    $pathValue = Resolve-NPDevWorkspacePath $WorkspaceRoot ("NPDevSamples\" + $sampleId + "\Input\expected-diagnostics.md")
    $patternCheck = Test-MaturityFilePatterns -FilePath $pathValue -Patterns @("warning", "error")
    [pscustomobject]@{
        sampleId = $sampleId
        path = Get-NPDevWorkspaceRelativePath $WorkspaceRoot $pathValue
        allMatched = $patternCheck.allMatched
        missingPatterns = $patternCheck.missing
    }
}
$endpointAudit = foreach ($sampleId in $releaseSamples) {
    $pathValue = Resolve-NPDevWorkspacePath $WorkspaceRoot ("NPDevSamples\" + $sampleId + "\Input\expected-endpoints.md")
    $patternCheck = Test-MaturityFilePatterns -FilePath $pathValue -Patterns @("GET ", "/api/")
    [pscustomobject]@{
        sampleId = $sampleId
        path = Get-NPDevWorkspaceRelativePath $WorkspaceRoot $pathValue
        allMatched = $patternCheck.allMatched
        missingPatterns = $patternCheck.missing
    }
}
$requestFiles = @(Get-ChildItem -LiteralPath (Resolve-NPDevWorkspacePath $WorkspaceRoot "NPDevSamples") -Recurse -File -ErrorAction SilentlyContinue | Where-Object { $_.DirectoryName -match 'Input\\Requests$' -and $_.Extension -eq ".json" })
$requestValidationHits = Find-MaturityTextMatches -WorkspaceRoot $WorkspaceRoot -RelativeRoot "scripts\quality" -Includes @("*.ps1") -Pattern 'Requests|request schema|Input\\Requests'
$invalidRequestHits = Find-MaturityTextMatches -WorkspaceRoot $WorkspaceRoot -RelativeRoot "scripts\quality" -Includes @("*.ps1") -Pattern 'invalid request|boundary test'
$invalidRequestFiles = @($requestFiles | Where-Object { $_.Name -match 'invalid|boundary' })
$subprojectReadmes = @(
    "NPDevContract\README.md",
    "NPDevEditor\README.md",
    "NPDevGenerator\README.md",
    "NPDevKernel\README.md",
    "NPDevRuntimeHost\README.md",
    "NPDevSamples\README.md"
)
$subprojectReadmeAudit = foreach ($relativePath in $subprojectReadmes) {
    $absolutePath = Resolve-NPDevWorkspacePath $WorkspaceRoot $relativePath
    $patternCheck = Test-MaturityFilePatterns -FilePath $absolutePath -Patterns @("purpose", "build", "test", "architecture")
    [pscustomobject]@{
        path = $relativePath
        exists = $patternCheck.exists
        allMatched = $patternCheck.allMatched
        missingPatterns = $patternCheck.missing
    }
}
$sampleReadmeAudit = foreach ($sampleId in $releaseSamples) {
    $relativePath = "NPDevSamples\" + $sampleId + "\Input\README.md"
    $absolutePath = Resolve-NPDevWorkspacePath $WorkspaceRoot $relativePath
    $patternCheck = Test-MaturityFilePatterns -FilePath $absolutePath -Patterns @("model", "run", "output")
    [pscustomobject]@{
        sampleId = $sampleId
        path = $relativePath
        exists = $patternCheck.exists
        allMatched = $patternCheck.allMatched
        missingPatterns = $patternCheck.missing
    }
}
$readmeFiles = @(Get-ChildItem -LiteralPath $WorkspaceRoot -Recurse -File -Filter "README.md" -ErrorAction SilentlyContinue)
$brokenReadmeLinks = foreach ($readmeFile in $readmeFiles) {
    foreach ($issue in (Get-MaturityMarkdownBrokenLinks $readmeFile.FullName)) {
        [pscustomobject]@{
            readme = Get-NPDevWorkspaceRelativePath $WorkspaceRoot $readmeFile.FullName
            target = $issue.target
            resolvedPath = $issue.resolvedPath
        }
    }
}
$projectDigestPaths = @(
    "PROJECT_DIGEST.md",
    "NPDevContract\PROJECT_DIGEST.md",
    "NPDevEditor\PROJECT_DIGEST.md",
    "NPDevGenerator\PROJECT_DIGEST.md",
    "NPDevKernel\PROJECT_DIGEST.md",
    "NPDevRuntimeHost\PROJECT_DIGEST.md",
    "NPDevSamples\PROJECT_DIGEST.md"
)
$projectDigestAudit = foreach ($relativePath in $projectDigestPaths) {
    $absolutePath = Resolve-NPDevWorkspacePath $WorkspaceRoot $relativePath
    [pscustomobject]@{
        path = $relativePath
        exists = (Test-Path -LiteralPath $absolutePath -PathType Leaf)
        lineCount = Get-MaturityFileLineCount $absolutePath
    }
}
$digestLineGaps = @($projectDigestAudit | Where-Object { -not $_.exists -or $_.lineCount -gt 100 })
$hygieneDocHits = Find-MaturityTextMatches -WorkspaceRoot $WorkspaceRoot -RelativeRoot "scripts\quality" -Includes @("run-hygiene-gate.ps1") -Pattern 'README|PROJECT_DIGEST|documentation'

Add-Condition "SDM-001" "Every release sample has: manifest.json, expected-behavior.md, expected-diagnostics.md, expected-endpoints.md" `
    (@($samplesMissingCoreFiles).Count -eq 0) `
    "Every release sample currently has the expected manifest/behavior/diagnostics/endpoints docs." `
    "One or more release samples are missing manifest/behavior/diagnostics/endpoints docs." `
    @{ sampleFileAudit = $sampleFileAudit; missing = $samplesMissingCoreFiles }

Add-Condition "SDM-002" "manifest.json schema validated against manifest.schema.json (create if missing)" `
    ($manifestSchemaPaths.allPresent) `
    "A sample manifest schema file is present." `
    "No sample manifest schema file was found." `
    @{ missingManifestSchemaPaths = $manifestSchemaPaths.missing }

Add-Condition "SDM-003" "expected-behavior.md describes at least 3 user journeys per sample" `
    (@($behaviorJourneyGaps).Count -eq 0) `
    "Every release sample expected-behavior.md currently shows at least three journey bullets/steps." `
    "One or more release sample expected-behavior.md files show fewer than three journey bullets/steps." `
    @{ behaviorJourneyAudit = $behaviorJourneyAudit }

Add-Condition "SDM-004" "expected-diagnostics.md lists all expected validation warnings/errors" `
    (@($diagnosticAudit | Where-Object { -not $_.allMatched }).Count -eq 0) `
    "Every release sample expected-diagnostics.md contains warning/error signals." `
    "One or more release sample expected-diagnostics.md files do not visibly enumerate warning/error expectations." `
    @{ diagnosticAudit = $diagnosticAudit }

Add-Condition "SDM-005" "expected-endpoints.md lists all generated REST endpoints with methods and paths" `
    (@($endpointAudit | Where-Object { -not $_.allMatched }).Count -eq 0) `
    "Every release sample expected-endpoints.md contains HTTP method/path signals." `
    "One or more release sample expected-endpoints.md files do not visibly enumerate REST methods/paths." `
    @{ endpointAudit = $endpointAudit }

Add-Condition "SDM-006" "Every .json in Input/Requests/ validated against generated request schema" `
    (@($requestFiles).Count -gt 0 -and @($requestValidationHits).Count -gt 0) `
    "Request example files exist and the quality scripts contain request-validation signals." `
    "Request example files exist, but no explicit request-schema validation signals were found in the sample-matrix quality path." `
    @{ requestFiles = @($requestFiles | ForEach-Object { Get-NPDevWorkspaceRelativePath $WorkspaceRoot $_.FullName }); hits = $requestValidationHits }

Add-Condition "SDM-007" "Validation runs as part of sample matrix gate" `
    (@($requestValidationHits).Count -gt 0) `
    "The sample-matrix gate contains request-validation signals." `
    "No explicit request-validation signals were found in the sample-matrix gate." `
    @{ hits = $requestValidationHits }

Add-Condition "SDM-008" "Invalid request causes sample matrix failure" `
    (@($invalidRequestHits).Count -gt 0) `
    "The sample-matrix quality path contains invalid-request failure signals." `
    "No explicit invalid-request failure signals were found in the sample-matrix quality path." `
    @{ hits = $invalidRequestHits }

Add-Condition "SDM-009" "Request files updated when model changes" `
    (@($requestValidationHits).Count -gt 0 -and @($requestFiles).Count -gt 0) `
    "Request files exist and are at least connected to validation flow." `
    "There is not yet enough explicit evidence to prove request files are kept in lockstep with model changes." `
    @{ requestFiles = @($requestFiles | ForEach-Object { Get-NPDevWorkspaceRelativePath $WorkspaceRoot $_.FullName }) }

Add-Condition "SDM-010" "Request examples cover: valid payload, invalid payload (boundary tests)" `
    (@($requestFiles).Count -gt 0 -and @($invalidRequestFiles).Count -gt 0) `
    "Request examples include both normal and invalid/boundary payload files." `
    "Request examples do not yet include explicit invalid/boundary payload files." `
    @{
        requestFiles = @($requestFiles | ForEach-Object { $_.Name })
        invalidRequestFiles = @($invalidRequestFiles | ForEach-Object { $_.Name })
    }

Add-Condition "SDM-011" "Every subproject README includes: purpose, build instructions, test instructions, architecture overview" `
    (@($subprojectReadmeAudit | Where-Object { -not $_.exists -or -not $_.allMatched }).Count -eq 0) `
    "Every core subproject README exists and contains purpose/build/test/architecture signals." `
    "One or more core subproject READMEs are missing or incomplete." `
    @{ subprojectReadmeAudit = $subprojectReadmeAudit }

Add-Condition "SDM-012" "Every sample README includes: domain description, model overview, how to run, expected output" `
    (@($sampleReadmeAudit | Where-Object { -not $_.exists -or -not $_.allMatched }).Count -eq 0) `
    "Every release sample README exists and contains model/run/output signals." `
    "One or more release sample READMEs are missing or incomplete." `
    @{ sampleReadmeAudit = $sampleReadmeAudit }

Add-Condition "SDM-013" "README files tested for: non-empty, contains required sections, no broken internal links" `
    (@($readmeFiles).Count -gt 0 -and @($brokenReadmeLinks).Count -eq 0) `
    "README files are present and no broken internal links were found." `
    "One or more README files contain broken internal links or there is no explicit README verification evidence." `
    @{ brokenReadmeLinks = $brokenReadmeLinks | Select-Object -First 40 }

Add-Condition "SDM-014" "PROJECT_DIGEST.md in each subproject is ≤100 lines and accurately reflects current state" `
    (@($digestLineGaps).Count -eq 0) `
    "Every checked PROJECT_DIGEST.md exists and is at or below 100 lines." `
    "One or more PROJECT_DIGEST.md files are missing or exceed 100 lines." `
    @{ projectDigestAudit = $projectDigestAudit }

Add-Condition "SDM-015" "Documentation freshness checked in hygiene gate" `
    (@($hygieneDocHits).Count -gt 0) `
    "The hygiene gate contains documentation-freshness signals." `
    "No explicit documentation-freshness signals were found in the hygiene gate." `
    @{ hits = $hygieneDocHits }

$report = Write-MaturityReport `
    -WorkspaceRoot $WorkspaceRoot `
    -RunId $RunId `
    -ScriptPath $PSCommandPath `
    -MaturityItem "samples-documentation-maturity" `
    -ReportPath $ReportPath `
    -Checks $checks `
    -Extra @{
        releaseSamples = $releaseSamples
        conditionCount = $checks.Count
    }

Complete-MaturityScript -Report $report -PassThru:$PassThru
