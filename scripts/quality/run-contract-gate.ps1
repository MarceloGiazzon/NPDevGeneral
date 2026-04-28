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
$RunId = Resolve-NPDevRunId $RunId "contract-gate"

if ([string]::IsNullOrWhiteSpace($ReportPath)) {
    $ReportPath = Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\reports\out\contract-gate-report.json"
}
else {
    $ReportPath = Normalize-NPDevPath $ReportPath
}

$projectRoot = Resolve-NPDevWorkspacePath $WorkspaceRoot "NPDevContract\dsl"
$gradleWrapperPath = Join-Path $projectRoot "gradlew.bat"
$contractSchemaGovernanceScript = Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\quality\run-contract-schema-governance.ps1"
$contractSchemaGovernanceReportPath = Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\reports\out\contract-schema-governance-report.json"
Write-NPDevInfo "Running NPDevContract gate"
Ensure-NPDevFile $contractSchemaGovernanceScript "Contract schema governance script"

Invoke-NPDevReportedCommand `
    -WorkspaceRoot $WorkspaceRoot `
    -ScriptPath $PSCommandPath `
    -RunId $RunId `
    -ReportPath $ReportPath `
    -GateName "contract" `
    -WorkingDirectory $projectRoot `
    -Executable $gradleWrapperPath `
    -Arguments @("test", "--no-daemon", "--console=plain") | Out-Null

$gateReport = Get-Content -LiteralPath $ReportPath -Raw | ConvertFrom-Json
$contractSchemaGovernance = $null
$governanceError = $null
try {
    $contractSchemaGovernance = & $contractSchemaGovernanceScript `
        -WorkspaceRoot $WorkspaceRoot `
        -RunId ($RunId + "-contract-schema-governance") `
        -ContractGateReportPath $ReportPath `
        -ReportPath $contractSchemaGovernanceReportPath `
        -PassThru
}
catch {
    $governanceError = $_.Exception.Message
    if (Test-Path -LiteralPath $contractSchemaGovernanceReportPath -PathType Leaf) {
        try {
            $contractSchemaGovernance = Get-Content -LiteralPath $contractSchemaGovernanceReportPath -Raw | ConvertFrom-Json
        }
        catch {
            $contractSchemaGovernance = $null
        }
    }
}

$contractSchemaGovernanceSummary = if ($null -eq $contractSchemaGovernance) {
    $null
}
else {
    [pscustomobject]@{
        overallStatus = [string]$contractSchemaGovernance.overallStatus
        reportPath = Get-NPDevWorkspaceRelativePath $WorkspaceRoot $contractSchemaGovernanceReportPath
    }
}
Add-Member -InputObject $gateReport -NotePropertyName "contractSchemaGovernance" -NotePropertyValue $contractSchemaGovernanceSummary -Force

if (-not [string]::IsNullOrWhiteSpace($governanceError) -or ($null -ne $contractSchemaGovernance -and [string]$contractSchemaGovernance.overallStatus -ne "passed")) {
    $gateReport.overallStatus = "failed"
    $gateReport.failureReasons = @(
        @($gateReport.failureReasons) +
        @(
            if (-not [string]::IsNullOrWhiteSpace($governanceError)) {
                $governanceError
            }
            elseif ($null -ne $contractSchemaGovernance) {
                "Contract schema governance report returned status " + [string]$contractSchemaGovernance.overallStatus + "."
            }
        )
    )
}

Write-NPDevJsonFile $ReportPath $gateReport

if ([string]$gateReport.overallStatus -eq "passed") {
    Write-NPDevOk "NPDevContract gate passed."
    return
}

Write-NPDevWarn "NPDevContract gate failed."
throw "NPDevContract gate failed."
