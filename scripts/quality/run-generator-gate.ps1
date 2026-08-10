[CmdletBinding()]
param(
    [string]$WorkspaceRoot = "",
    [string]$RunId = "",
    [string]$ReportPath = ""
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

. (Join-Path $PSScriptRoot "..\npdev-common.ps1")

if ([string]::IsNullOrWhiteSpace($WorkspaceRoot)) {
    $WorkspaceRoot = Get-NPDevWorkspaceRoot $PSScriptRoot
}
$WorkspaceRoot = Normalize-NPDevPath $WorkspaceRoot
$RunId = Resolve-NPDevRunId $RunId "generator-gate"

if ([string]::IsNullOrWhiteSpace($ReportPath)) {
    $ReportPath = Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\reports\out\generator-gate-report.json"
}
else {
    $ReportPath = Normalize-NPDevPath $ReportPath
}

$projectRoot = Resolve-NPDevWorkspacePath $WorkspaceRoot "NPDevGenerator"
# REG-11: resolve the OS-appropriate wrapper via the shared helper so this gate runs on Linux CI.
$gradleWrapperPath = Get-NPDevGradleWrapperExecutable $projectRoot
$deterministicGenerationScript = Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\hygiene\check-deterministic-generation.ps1"
$deterministicGenerationReportPath = Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\reports\out\deterministic-generation-report.json"
$generatorGovernanceScript = Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\quality\run-generator-governance.ps1"
$generatorGovernanceReportPath = Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\reports\out\generator-governance-report.json"
Write-NPDevInfo "Running NPDevGenerator gate"

Invoke-NPDevReportedCommand `
    -WorkspaceRoot $WorkspaceRoot `
    -ScriptPath $PSCommandPath `
    -RunId $RunId `
    -ReportPath $ReportPath `
    -GateName "generator" `
    -WorkingDirectory $projectRoot `
    -Executable $gradleWrapperPath `
    -Arguments @(":dsl:clean", "generatorQualityGate", "--no-daemon", "--console=plain") | Out-Null

$gateReport = Get-Content -LiteralPath $ReportPath -Raw | ConvertFrom-Json
$deterministicGenerationError = $null
$generatorGovernanceError = $null
$deterministicGenerationReport = $null
$generatorGovernanceReport = $null

try {
    & $deterministicGenerationScript `
        -WorkspaceRoot $WorkspaceRoot `
        -ReportPath $deterministicGenerationReportPath | Out-Null
    if (Test-Path -LiteralPath $deterministicGenerationReportPath -PathType Leaf) {
        $deterministicGenerationReport = Get-Content -LiteralPath $deterministicGenerationReportPath -Raw | ConvertFrom-Json
    }
}
catch {
    $deterministicGenerationError = $_.Exception.Message
    if (Test-Path -LiteralPath $deterministicGenerationReportPath -PathType Leaf) {
        try {
            $deterministicGenerationReport = Get-Content -LiteralPath $deterministicGenerationReportPath -Raw | ConvertFrom-Json
        }
        catch {
            $deterministicGenerationReport = $null
        }
    }
}

try {
    $generatorGovernanceReport = & $generatorGovernanceScript `
        -WorkspaceRoot $WorkspaceRoot `
        -RunId ($RunId + "-governance") `
        -ReportPath $generatorGovernanceReportPath `
        -DeterministicGenerationReportPath $deterministicGenerationReportPath `
        -PassThru
}
catch {
    $generatorGovernanceError = $_.Exception.Message
    if (Test-Path -LiteralPath $generatorGovernanceReportPath -PathType Leaf) {
        try {
            $generatorGovernanceReport = Get-Content -LiteralPath $generatorGovernanceReportPath -Raw | ConvertFrom-Json
        }
        catch {
            $generatorGovernanceReport = $null
        }
    }
}

# THIRD_PERSON_TRIAL_ANALYSIS_2026-08-10.md: every gate this repo has verifies THE REPO; none
# verified the experience of generating from somewhere else, which is where F1/F2/F7/F8 lived --
# four defects a fully green gate run had missed, all of them absolute paths from the author's
# machine emitted into output a stranger runs. This generates one sample OUT of both the workspace
# and AppGen ancestry and fails on any NEW leak (the ten already present are baselined as PORT-1,
# with reasons, so the ratchet can only tighten). Lives here rather than in the AI-knowledge gate
# because it generates, and that gate is contractually static.
#
# PORT-2 added a second rule to the same run: the generated tree is COPIED to a disjoint directory
# and scanned for any reference to where it was generated. Rule 1 was blind to that half by
# construction -- it requires the output root to be token-free, so a file hardcoding the output root
# carried no forbidden token and reported clean. Four files did exactly that while this gate stayed
# green.
$outOfTreeScript = Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\hygiene\check-out-of-tree-generation.ps1"
$outOfTreeReportPath = Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\reports\out\out-of-tree-generation-report.json"
$outOfTreeError = $null
$outOfTreeReport = $null
try {
    & $outOfTreeScript -WorkspaceRoot $WorkspaceRoot -ReportPath $outOfTreeReportPath | Out-Null
    if ($LASTEXITCODE -ne 0) {
        # Two rules can fail here, and the difference matters to whoever reads this line. Rule 1: a
        # NEW absolute path from THIS machine reached emitted output. Rule 2 (PORT-2): the tree was
        # copied somewhere disjoint and a file in the copy still names where it was generated -- a
        # moved app that silently builds and runs the original. The check's own output names which.
        $outOfTreeError = "Out-of-tree generation check failed (exit $LASTEXITCODE) -- emitted output carries either a NEW absolute path from this machine, or a reference to the directory it was generated in."
    }
}
catch {
    $outOfTreeError = $_.Exception.Message
}
if (Test-Path -LiteralPath $outOfTreeReportPath -PathType Leaf) {
    try { $outOfTreeReport = Get-Content -LiteralPath $outOfTreeReportPath -Raw | ConvertFrom-Json } catch { $outOfTreeReport = $null }
}
$outOfTreeEvidence = [pscustomobject]@{
    overallStatus     = if ($null -eq $outOfTreeReport) { "failed" } else { [string]$outOfTreeReport.status }
    filesScanned      = if ($null -eq $outOfTreeReport) { 0 } else { $outOfTreeReport.filesScanned }
    knownDefects      = if ($null -eq $outOfTreeReport) { 0 } else { @($outOfTreeReport.knownDefects).Count }
    # PORT-2's rule, recorded separately: a run where the copy did not happen is NOT the same
    # evidence as one where it happened and found nothing, and a single overall status cannot tell
    # them apart. copyComplete is the guard that says the birthplace rule actually ran.
    copyComplete      = if ($null -eq $outOfTreeReport) { $false } else { [bool]$outOfTreeReport.copyComplete }
    birthplaceScanned = if ($null -eq $outOfTreeReport) { 0 } else { $outOfTreeReport.birthplaceScanned }
    knownBirthDefects = if ($null -eq $outOfTreeReport) { 0 } else { @($outOfTreeReport.knownBirthDefects).Count }
    reportPath        = Get-NPDevWorkspaceRelativePath $WorkspaceRoot $outOfTreeReportPath
    error             = $outOfTreeError
}

$deterministicGenerationEvidence = [pscustomobject]@{
    overallStatus = if ($null -eq $deterministicGenerationReport) { "failed" } else { [string]$deterministicGenerationReport.overallStatus }
    reportPath = Get-NPDevWorkspaceRelativePath $WorkspaceRoot $deterministicGenerationReportPath
    error = $deterministicGenerationError
}
$generatorGovernanceEvidence = [pscustomobject]@{
    overallStatus = if ($null -eq $generatorGovernanceReport) { "failed" } else { [string]$generatorGovernanceReport.overallStatus }
    reportPath = Get-NPDevWorkspaceRelativePath $WorkspaceRoot $generatorGovernanceReportPath
    error = $generatorGovernanceError
}
$gateReport | Add-Member -NotePropertyName deterministicGeneration -NotePropertyValue $deterministicGenerationEvidence -Force
$gateReport | Add-Member -NotePropertyName generatorGovernance -NotePropertyValue $generatorGovernanceEvidence -Force

# LNCH-1 P8 (task 8.2): docs/DSL_REFERENCE.md is generated from model.schema.json +
# FieldWidgetDefaults.java (scripts/docs/generate_dsl_reference.py), not hand-written -- this drift
# gate was written but never wired into any gate (confirmed by grep at the time). Runs --check
# (read-only, never mutates the committed doc) so a schema change that should have regenerated the
# reference fails the gate loudly instead of silently rotting.
$dslReferenceScript = Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\docs\generate_dsl_reference.py"
$dslReferenceError = $null
$dslReferenceExitCode = $null
$dslReferenceOutput = @()
try {
    $dslReferenceOutput = & python $dslReferenceScript "--check" 2>&1 | ForEach-Object { $_.ToString() }
    $dslReferenceExitCode = $LASTEXITCODE
}
catch {
    $dslReferenceError = $_.Exception.Message
}
$dslReferencePassed = ($null -eq $dslReferenceError) -and ($dslReferenceExitCode -eq 0)
$dslReferenceEvidence = [pscustomobject]@{
    overallStatus = if ($dslReferencePassed) { "passed" } else { "failed" }
    exitCode = $dslReferenceExitCode
    output = @($dslReferenceOutput | Select-Object -Last 20)
    error = $dslReferenceError
}
$gateReport | Add-Member -NotePropertyName dslReferenceDrift -NotePropertyValue $dslReferenceEvidence -Force

# REG-133: the drift check above only catches "not regenerated" -- 8cd9860 regenerated AND
# re-committed the doc in the SAME commit that broke it, so the drift check compared the freshly
# (badly) regenerated content against the freshly (badly) committed content and passed. This is
# the companion gate: an absolute floor on the rendered CONTENT itself (no discriminated root
# field renders as the bare "any" fallback; flowStep/procedureStep/concept breadth floors met),
# independent of whether it matches history.
$dslReferenceFloorScript = Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\quality\check-dsl-reference-output-floor.py"
$dslReferenceFloorError = $null
$dslReferenceFloorExitCode = $null
$dslReferenceFloorOutput = @()
try {
    $dslReferenceFloorOutput = & python $dslReferenceFloorScript 2>&1 | ForEach-Object { $_.ToString() }
    $dslReferenceFloorExitCode = $LASTEXITCODE
}
catch {
    $dslReferenceFloorError = $_.Exception.Message
}
$dslReferenceFloorPassed = ($null -eq $dslReferenceFloorError) -and ($dslReferenceFloorExitCode -eq 0)
$dslReferenceFloorEvidence = [pscustomobject]@{
    overallStatus = if ($dslReferenceFloorPassed) { "passed" } else { "failed" }
    exitCode = $dslReferenceFloorExitCode
    output = @($dslReferenceFloorOutput | Select-Object -Last 20)
    error = $dslReferenceFloorError
}
$gateReport | Add-Member -NotePropertyName dslReferenceOutputFloor -NotePropertyValue $dslReferenceFloorEvidence -Force

# Move 8 D1 (item G3, docs/MOVE8_CLOSE_TABLE_SPEC.md): ReleaseGateValidator.validatePromotion is
# fully built and unit-tested (R81, ledger/items/REG-81.yml) but was invoked by nothing except its
# own test -- truth-level promotion gating was dormant. Wires the smallest real check: run
# ModelValidatorMain's --releaseGate/--targetTruthLevel=T2 pass (via the existing :NPDevContract:
# dsl:validateModel Gradle task) against NPDevSamples\dsl-conformance-max\Input\model.json. T2 is
# a deliberately low bar meant to prove the gate fires, not a real release-bar product decision --
# but the bar is not lowered further just to make this pass (see below).
$releaseGateModelPath = Resolve-NPDevWorkspacePath $WorkspaceRoot "NPDevSamples\dsl-conformance-max\Input\model.json"
$releaseGateReportPath = Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\reports\out\release-gate-t2-report.json"
$rootGradleWrapperPath = Get-NPDevGradleWrapperExecutable $WorkspaceRoot
$releaseGateError = $null
$releaseGateReport = $null
try {
    & $rootGradleWrapperPath ":NPDevContract:dsl:validateModel" `
        "-PmodelPath=$releaseGateModelPath" `
        "-PreleaseGate" `
        "-PtargetTruthLevel=T2" `
        "-PreportOut=$releaseGateReportPath" `
        "--console=plain" | Out-Null
    if (Test-Path -LiteralPath $releaseGateReportPath -PathType Leaf) {
        $releaseGateReport = Get-Content -LiteralPath $releaseGateReportPath -Raw | ConvertFrom-Json
    }
    else {
        $releaseGateError = "validateModel did not produce a report at $releaseGateReportPath"
    }
}
catch {
    $releaseGateError = $_.Exception.Message
    if (Test-Path -LiteralPath $releaseGateReportPath -PathType Leaf) {
        try {
            $releaseGateReport = Get-Content -LiteralPath $releaseGateReportPath -Raw | ConvertFrom-Json
        }
        catch {
            $releaseGateReport = $null
        }
    }
}
$releaseGateStatus = if ($null -eq $releaseGateReport) { "failed" } else { [string]$releaseGateReport.status }
# Deliberately NOT lowering targetTruthLevel to make this pass if it fails (Move 8 H.2 prohibition):
# a failure here means the release gate found something real -- report it, do not mask it.
$releaseGatePassed = ($null -eq $releaseGateError) -and ($releaseGateStatus -ne "failed")
$releaseGateEvidence = [pscustomobject]@{
    overallStatus = if ($releaseGatePassed) { "passed" } else { "failed" }
    modelPath = Get-NPDevWorkspaceRelativePath $WorkspaceRoot $releaseGateModelPath
    targetTruthLevel = "T2"
    reportPath = Get-NPDevWorkspaceRelativePath $WorkspaceRoot $releaseGateReportPath
    validationStatus = $releaseGateStatus
    error = $releaseGateError
}
$gateReport | Add-Member -NotePropertyName releaseGateT2 -NotePropertyValue $releaseGateEvidence -Force
$gateReport | Add-Member -NotePropertyName outOfTreeGeneration -NotePropertyValue $outOfTreeEvidence -Force

if (
    -not [string]::IsNullOrWhiteSpace($outOfTreeError) -or
    -not [string]::IsNullOrWhiteSpace($deterministicGenerationError) -or
    -not [string]::IsNullOrWhiteSpace($generatorGovernanceError) -or
    ($null -eq $deterministicGenerationReport) -or
    ([string]$deterministicGenerationReport.overallStatus -ne "passed") -or
    ($null -eq $generatorGovernanceReport) -or
    ([string]$generatorGovernanceReport.overallStatus -ne "passed") -or
    (-not $dslReferencePassed) -or
    (-not $dslReferenceFloorPassed) -or
    (-not $releaseGatePassed)
) {
    $gateReport.overallStatus = "failed"
    $gateReport.failureReasons = @(
        @($gateReport.failureReasons) +
        @(
            if (-not [string]::IsNullOrWhiteSpace($outOfTreeError)) {
                $outOfTreeError
            }
            if (-not [string]::IsNullOrWhiteSpace($deterministicGenerationError)) {
                $deterministicGenerationError
            }
            elseif ($null -ne $deterministicGenerationReport -and [string]$deterministicGenerationReport.overallStatus -ne "passed") {
                "Deterministic generation report returned status " + [string]$deterministicGenerationReport.overallStatus + "."
            }
            if (-not [string]::IsNullOrWhiteSpace($generatorGovernanceError)) {
                $generatorGovernanceError
            }
            elseif ($null -ne $generatorGovernanceReport -and [string]$generatorGovernanceReport.overallStatus -ne "passed") {
                "Generator governance report returned status " + [string]$generatorGovernanceReport.overallStatus + "."
            }
            if (-not $dslReferencePassed) {
                "docs/DSL_REFERENCE.md is stale -- run 'python scripts/docs/generate_dsl_reference.py' and commit the result."
            }
            if (-not $dslReferenceFloorPassed) {
                "docs/DSL_REFERENCE.md failed its output floor (REG-133) -- the generator ran but silently produced degenerate content; see scripts/quality/check-dsl-reference-output-floor.py's own output."
            }
            if (-not $releaseGatePassed) {
                if (-not [string]::IsNullOrWhiteSpace($releaseGateError)) {
                    "Release gate (T2, dsl-conformance-max) invocation error: " + $releaseGateError
                }
                else {
                    "Release gate (T2, dsl-conformance-max) returned status '" + $releaseGateStatus + "' -- see " + (Get-NPDevWorkspaceRelativePath $WorkspaceRoot $releaseGateReportPath) + "."
                }
            }
        )
    )
}

Write-NPDevJsonFile $ReportPath $gateReport

if ([string]$gateReport.overallStatus -eq "passed") {
    Write-NPDevOk "NPDevGenerator gate passed."
    return
}

Write-NPDevWarn "NPDevGenerator gate failed."
throw "NPDevGenerator gate failed."
