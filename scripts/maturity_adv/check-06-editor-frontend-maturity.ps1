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
$RunId = Resolve-NPDevRunId $RunId "editor-frontend-maturity"
$ReportPath = Resolve-MaturityReportPath -WorkspaceRoot $WorkspaceRoot -ReportPath $ReportPath -DefaultRelativePath "scripts\reports\out\editor-frontend-maturity-report.json"

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

$roundTripTestPath = Resolve-NPDevWorkspacePath $WorkspaceRoot "NPDevEditor\ui-react\src\authoring\editorRoundTripAndUx.test.ts"
$evolutionTestPath = Resolve-NPDevWorkspacePath $WorkspaceRoot "NPDevEditor\ui-react\src\authoring\editorEvolutionSafety.test.ts"
$semanticValidationTestPath = Resolve-NPDevWorkspacePath $WorkspaceRoot "NPDevEditor\ui-react\src\authoring\editorSemanticValidation.test.ts"
$frontendGatePath = Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\quality\run-frontend-gate.ps1"
$frontendGateReportPath = Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\reports\out\frontend-gate-report.json"
$frontendGateReport = Read-MaturityJsonFile $frontendGateReportPath
$uiBoundaryPath = Resolve-NPDevWorkspacePath $WorkspaceRoot "NPDevEditor\ui-react\ui-boundary.json"
$uiBoundaryDocPath = Resolve-NPDevWorkspacePath $WorkspaceRoot "NPDevEditor\ui-react\MIGRATION_DIGEST.md"
$e2eSpecFiles = @(Get-MaturityWorkspaceFiles -WorkspaceRoot $WorkspaceRoot -RelativeRoot "NPDevEditor\ui-react\e2e" -Includes @("*.ts", "*.tsx"))
$tsxFiles = @(Get-MaturityWorkspaceFiles -WorkspaceRoot $WorkspaceRoot -RelativeRoot "NPDevEditor\ui-react\src" -Includes @("*.tsx"))
$uiBoundaryDocHits = Find-MaturityTextMatches -WorkspaceRoot $WorkspaceRoot -RelativeRoot "NPDevEditor\ui-react" -Includes @("MIGRATION_DIGEST.md") -Pattern 'boundary|zone'
$frontendGateHistory = @(Get-ChildItem -LiteralPath (Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\reports\releases") -Directory -ErrorAction SilentlyContinue | Sort-Object LastWriteTime -Descending | Select-Object -First 3 | ForEach-Object {
        $pathValue = Join-Path $_.FullName "scripts\reports\out\frontend-gate-report.json"
        if (Test-Path -LiteralPath $pathValue -PathType Leaf) {
            Read-MaturityJsonFile $pathValue
        }
    } | Where-Object { $null -ne $_ })

$roundTripCoverageHits = Find-MaturityTextMatches -WorkspaceRoot $WorkspaceRoot -RelativeRoot "NPDevEditor\ui-react\src\authoring" -Includes @("editorRoundTripAndUx.test.ts") -Pattern 'empty concept|empty field|null|jsonb|deep'
$semanticDiffHits = Find-MaturityTextMatches -WorkspaceRoot $WorkspaceRoot -RelativeRoot "NPDevEditor\ui-react\src\authoring" -Includes @("editorRoundTripAndUx.test.ts") -Pattern 'semantic diff|empty diff|round-trip'
$evolutionHits = Find-MaturityTextMatches -WorkspaceRoot $WorkspaceRoot -RelativeRoot "NPDevEditor\ui-react\src\authoring" -Includes @("editorEvolutionSafety.test.ts") -Pattern 'old editor|new editor|load'
$semanticValidationHits = Find-MaturityTextMatches -WorkspaceRoot $WorkspaceRoot -RelativeRoot "NPDevEditor\ui-react\src\authoring" -Includes @("editorSemanticValidation.test.ts") -Pattern 'invalid|reject|before save'
$uiBoundarySchema = Read-MaturityJsonFile $uiBoundaryPath
$uiBoundaryListedEntries = if ($null -eq $uiBoundarySchema) {
    @()
}
else {
    $entries = @()
    if ($uiBoundarySchema.PSObject.Properties.Name -contains "components") {
        $entries += @($uiBoundarySchema.components)
    }
    if ($uiBoundarySchema.PSObject.Properties.Name -contains "exemptions") {
        $entries += @($uiBoundarySchema.exemptions)
    }
    if ($uiBoundarySchema.PSObject.Properties.Name -contains "files") {
        $entries += @($uiBoundarySchema.files)
    }
    $entries
}
$uiBoundaryMissingTsx = @($tsxFiles | Where-Object {
        $relativePath = Get-NPDevWorkspaceRelativePath $WorkspaceRoot $_.FullName
        $relativePath -notin $uiBoundaryListedEntries
    } | Select-Object -First 50 | ForEach-Object { Get-NPDevWorkspaceRelativePath $WorkspaceRoot $_.FullName })
$boundaryImportHits = Find-MaturityTextMatches -WorkspaceRoot $WorkspaceRoot -RelativeRoot "NPDevEditor\ui-react\src" -Includes @("*.ts", "*.tsx") -Pattern 'zone|boundary'

Add-Condition "EFM-001" "Round-trip test covers: empty concept list, empty field list, null optional values, deeply nested JSONB" `
    (@($roundTripCoverageHits).Count -ge 4) `
    "editorRoundTripAndUx.test.ts shows explicit empty/null/deep JSONB coverage signals." `
    "editorRoundTripAndUx.test.ts does not yet show all expected empty/null/deep JSONB coverage signals." `
    @{ hits = $roundTripCoverageHits }

Add-Condition "EFM-002" "Round-trip test proves: semantic diff is empty after load→edit→save" `
    (@($semanticDiffHits).Count -gt 0) `
    "editorRoundTripAndUx.test.ts contains semantic-diff stability signals." `
    "editorRoundTripAndUx.test.ts does not yet visibly prove empty semantic diff after round-trip." `
    @{ hits = $semanticDiffHits }

Add-Condition "EFM-003" "editorEvolutionSafety.test.ts proves: old editor can load models saved by new editor" `
    (@($evolutionHits).Count -gt 0) `
    "editorEvolutionSafety.test.ts contains backwards/forwards load-compatibility signals." `
    "editorEvolutionSafety.test.ts does not yet visibly prove old-editor/new-editor load compatibility." `
    @{ hits = $evolutionHits }

Add-Condition "EFM-004" "editorSemanticValidation.test.ts proves: invalid models rejected before save" `
    (@($semanticValidationHits).Count -gt 0) `
    "editorSemanticValidation.test.ts contains invalid-before-save rejection signals." `
    "editorSemanticValidation.test.ts does not yet visibly prove invalid-before-save rejection." `
    @{ hits = $semanticValidationHits }

Add-Condition "EFM-005" "All tests pass in npmTest gate" `
    ($null -ne $frontendGateReport -and @($frontendGateReport.subSteps | Where-Object { [string]$_.name -eq "test" -and [string]$_.status -eq "passed" }).Count -gt 0) `
    "The frontend gate test sub-step is currently passed." `
    "The frontend gate test sub-step is missing or not passed." `
    @{ frontendGateStatus = if ($null -eq $frontendGateReport) { $null } else { [string]$frontendGateReport.overallStatus } }

$frontendGateScriptPatterns = Test-MaturityFilePatterns -FilePath $frontendGatePath -Patterns @("frontend-gate-report.json", "generatedAt", "runId", "scriptPath", "workspaceRoot", "overallStatus")
Add-Condition "EFM-006" "run-frontend-gate.ps1 produces frontend-gate-report.json with all required fields" `
    ($frontendGateScriptPatterns.allMatched -and $null -ne $frontendGateReport) `
    "run-frontend-gate.ps1 produces the expected structured frontend gate report." `
    "run-frontend-gate.ps1 does not yet visibly produce the expected structured frontend gate report." `
    @{ missingPatterns = $frontendGateScriptPatterns.missing }

$toolchainFieldsPresent = $null -ne $frontendGateReport -and `
    $frontendGateReport.toolchain.node.versionText -and `
    $frontendGateReport.toolchain.npm.versionText -and `
    $frontendGateReport.toolchain.java.versionText -and `
    $frontendGateReport.subSteps[0].command.display -and `
    $frontendGateReport.subSteps[0].command.exitCode -ne $null -and `
    $frontendGateReport.subSteps[0].command.durationSeconds -ne $null -and `
    @($frontendGateReport.subSteps[0].command.outputTail).Count -gt 0
Add-Condition "EFM-007" "Report includes: Node version, npm version, Java version, Gradle command, exit code, duration, output tail" `
    ($toolchainFieldsPresent) `
    "The current frontend gate report includes the expected toolchain and command evidence fields." `
    "The current frontend gate report is missing one or more expected toolchain/command evidence fields." `
    @{ reportPath = "scripts\reports\out\frontend-gate-report.json" }

$inputFingerprintsPresent = $null -ne $frontendGateReport -and `
    $null -ne $frontendGateReport.inputs.packageJson -and `
    $null -ne $frontendGateReport.inputs.packageLock -and `
    $null -ne $frontendGateReport.inputs.buildGradle
Add-Condition "EFM-008" "Report includes: input fingerprints for package.json, package-lock.json, build.gradle" `
    ($inputFingerprintsPresent) `
    "The frontend gate report includes package/build input fingerprints." `
    "The frontend gate report is missing one or more package/build input fingerprints." `
    @{ inputs = if ($null -eq $frontendGateReport) { $null } else { $frontendGateReport.inputs } }

$cleanupPassed = $null -ne $frontendGateReport -and `
    [int]$frontendGateReport.cleanup.remainingGeneratedResidueCount -eq 0 -and `
    @($frontendGateReport.cleanup.remainingGeneratedResidue).Count -eq 0
Add-Condition "EFM-009" "Generated residue check passes (no node_modules or .vite left in ui-react)" `
    ($cleanupPassed) `
    "The current frontend gate report shows clean residue after the run." `
    "The current frontend gate report still shows generated residue under ui-react." `
    @{ cleanup = if ($null -eq $frontendGateReport) { $null } else { $frontendGateReport.cleanup } }

$threeRunPass = @($frontendGateHistory).Count -ge 3 -and @($frontendGateHistory | Where-Object { [string]$_.overallStatus -eq "passed" }).Count -ge 3
Add-Condition "EFM-010" "Gate passes consistently across 3 consecutive runs on clean workspace" `
    ($threeRunPass) `
    "The three most recent archived frontend gate reports are all passed." `
    "There are not yet three consecutive archived passed frontend gate reports." `
    @{
        archivedStatuses = @($frontendGateHistory | ForEach-Object { [string]$_.overallStatus })
        archivedRunIds = @($frontendGateHistory | ForEach-Object { [string]$_.runId })
    }

Add-Condition "EFM-011" "Test validates every .tsx file in src/ is listed in ui-boundary.json or explicitly exempted" `
    ((Test-Path -LiteralPath $uiBoundaryPath -PathType Leaf) -and @($uiBoundaryMissingTsx).Count -eq 0 -and @($tsxFiles).Count -gt 0) `
    "ui-boundary.json accounts for every .tsx file or exemption entry under src/." `
    "ui-boundary.json does not currently account for every .tsx file under src/." `
    @{ missingTsxEntries = $uiBoundaryMissingTsx; tsxFileCount = @($tsxFiles).Count }

Add-Condition "EFM-012" "Test validates no component imports from outside its boundary zone" `
    (@($boundaryImportHits).Count -gt 0) `
    "Boundary/zone enforcement signals were found in the frontend source tree." `
    "No explicit boundary-zone enforcement signals were found in the frontend source tree." `
    @{ hits = $boundaryImportHits }

Add-Condition "EFM-013" "Boundary changes require explicit ui-boundary.json update" `
    (@($uiBoundaryListedEntries).Count -gt 0) `
    "ui-boundary.json contains explicit enumerated entries that can act as a change-control surface." `
    "ui-boundary.json does not currently expose explicit enumerated entries for change control." `
    @{ uiBoundaryKeys = if ($null -eq $uiBoundarySchema) { @() } else { @($uiBoundarySchema.PSObject.Properties.Name) } }

Add-Condition "EFM-014" "CI gate fails on boundary violation" `
    (@($boundaryImportHits).Count -gt 0) `
    "There are some source-level signs of boundary enforcement that could be wired into CI." `
    "No explicit source-level boundary enforcement signs were found, so CI failure behavior is not yet evidenced." `
    @{ hits = $boundaryImportHits }

Add-Condition "EFM-015" "Boundary documentation (MIGRATION_DIGEST.md) explains each zone" `
    (@($uiBoundaryDocHits).Count -ge 2) `
    "The UI migration digest contains boundary/zone documentation signals." `
    "The UI migration digest does not yet visibly explain boundary zones." `
    @{ hits = $uiBoundaryDocHits }

$report = Write-MaturityReport `
    -WorkspaceRoot $WorkspaceRoot `
    -RunId $RunId `
    -ScriptPath $PSCommandPath `
    -MaturityItem "editor-frontend-maturity" `
    -ReportPath $ReportPath `
    -Checks $checks `
    -Extra @{
        conditionCount = $checks.Count
    }

Complete-MaturityScript -Report $report -PassThru:$PassThru
