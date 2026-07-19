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
$gradleWrapperPath = Join-Path $projectRoot "gradlew.bat"
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

if (
    -not [string]::IsNullOrWhiteSpace($deterministicGenerationError) -or
    -not [string]::IsNullOrWhiteSpace($generatorGovernanceError) -or
    ($null -eq $deterministicGenerationReport) -or
    ([string]$deterministicGenerationReport.overallStatus -ne "passed") -or
    ($null -eq $generatorGovernanceReport) -or
    ([string]$generatorGovernanceReport.overallStatus -ne "passed") -or
    (-not $dslReferencePassed)
) {
    $gateReport.overallStatus = "failed"
    $gateReport.failureReasons = @(
        @($gateReport.failureReasons) +
        @(
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
