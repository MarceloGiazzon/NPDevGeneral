[CmdletBinding()]
param(
    [string]$WorkspaceRoot = "",
    [string]$RunId = "",
    [string]$ReportPath = "",
    [string]$RuntimeSecurityConsistencyReportPath = "",
    [switch]$PassThru
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

. (Join-Path $PSScriptRoot "prioritized-control-common.ps1")

$WorkspaceRoot = Resolve-MaturityWorkspaceRoot -WorkspaceRoot $WorkspaceRoot -ScriptRoot $PSScriptRoot
$RunId = Resolve-NPDevRunId $RunId "b9-security-consistency-control"
$ReportPath = Resolve-PrioritizedControlReportPath -WorkspaceRoot $WorkspaceRoot -ReportPath $ReportPath -DefaultRelativePath "scripts\reports\out\prioritized-b9-security-consistency-report.json"
$RuntimeSecurityConsistencyReportPath = if ([string]::IsNullOrWhiteSpace($RuntimeSecurityConsistencyReportPath)) {
    Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\reports\out\runtime-security-consistency-report.json"
}
else {
    Normalize-NPDevPath $RuntimeSecurityConsistencyReportPath
}

$schema = Test-MaturityReportSchema -PathValue $RuntimeSecurityConsistencyReportPath -RequiredProperties @(
    "generatedAt",
    "runId",
    "overallStatus",
    "policyPath",
    "controllerSecurity",
    "experimentalSurfaceProof",
    "redactionCoverage",
    "checks",
    "summary"
)
$reportDoc = if ($schema.exists -and [string]::IsNullOrWhiteSpace([string]$schema.parseError)) { Read-MaturityJsonFile $RuntimeSecurityConsistencyReportPath } else { $null }
$controllerSecurity = if ($null -eq $reportDoc) { $null } else { $reportDoc.controllerSecurity }
$experimentalSurfaceProof = if ($null -eq $reportDoc) { $null } else { $reportDoc.experimentalSurfaceProof }
$redactionCoverage = if ($null -eq $reportDoc) { $null } else { $reportDoc.redactionCoverage }

$checks = @(
    (New-MaturityCheck -Name "runtime-security-consistency-report" -Status $(if ($schema.valid) { "passed" } else { "failed" }) -Expectation "The runtime security consistency report must exist and expose the exact Bucket 2 fields." -Summary $(if ($schema.valid) { "The runtime security consistency report is readable and exposes the expected fields." } else { "The runtime security consistency report is missing or does not expose the expected fields." }) -Data @{ path = Get-PrioritizedControlEvidencePath -WorkspaceRoot $WorkspaceRoot -PathValue $RuntimeSecurityConsistencyReportPath; missingProperties = $schema.missingProperties; parseError = $schema.parseError })
    (New-MaturityCheck -Name "runtime-security-consistency-current" -Status $(if ($null -ne $reportDoc -and [string]$reportDoc.overallStatus -eq "passed") { "passed" } else { "failed" }) -Expectation "The official runtime security consistency report must currently pass." -Summary $(if ($null -ne $reportDoc -and [string]$reportDoc.overallStatus -eq "passed") { "The runtime security consistency report is green." } else { "The runtime security consistency report is missing or failing." }) -Data @{ overallStatus = if ($null -eq $reportDoc) { $null } else { [string]$reportDoc.overallStatus } })
    (New-MaturityCheck -Name "governed-auth-model" -Status $(if ($null -ne $controllerSecurity -and [string]$controllerSecurity.authMode -eq "jwt" -and [string]$controllerSecurity.authEnabled -eq "true") { "passed" } else { "failed" }) -Expectation "The governed external-beta profile must keep the JWT auth path active for runtime controllers." -Summary $(if ($null -ne $controllerSecurity -and [string]$controllerSecurity.authMode -eq "jwt" -and [string]$controllerSecurity.authEnabled -eq "true") { "The governed JWT auth path remains active." } else { "The governed JWT auth path is missing or inconsistent." }) -Data @{ controllerSecurity = $controllerSecurity })
    (New-MaturityCheck -Name "experimental-surfaces-disabled" -Status $(if ($null -ne $experimentalSurfaceProof -and [string]$experimentalSurfaceProof.status -eq "passed") { "passed" } else { "failed" }) -Expectation "Supported-surface packaging evidence must keep experimental controllers disabled in the supported profile." -Summary $(if ($null -ne $experimentalSurfaceProof -and [string]$experimentalSurfaceProof.status -eq "passed") { "Experimental controllers remain disabled in the supported profile." } else { "Supported-surface packaging evidence is missing or failing." }) -Data @{ proof = $experimentalSurfaceProof })
    (New-MaturityCheck -Name "redaction-coverage" -Status $(if ($null -ne $redactionCoverage -and @($redactionCoverage.missingFields).Count -eq 0) { "passed" } else { "failed" }) -Expectation "The sensitive-field inventory must be covered by trace, event, and execution redaction evidence." -Summary $(if ($null -ne $redactionCoverage -and @($redactionCoverage.missingFields).Count -eq 0) { "The sensitive-field inventory is fully covered by redaction evidence." } else { "One or more sensitive fields are missing from redaction coverage." }) -Data @{ redactionCoverage = $redactionCoverage })
)

$report = Write-PrioritizedControlReport `
    -WorkspaceRoot $WorkspaceRoot `
    -RunId $RunId `
    -ScriptPath $PSCommandPath `
    -Bucket "B2" `
    -ControlId "B9-SECURITY-CONSISTENCY" `
    -ReportPath $ReportPath `
    -EvidencePaths @(Get-PrioritizedControlEvidencePath -WorkspaceRoot $WorkspaceRoot -PathValue $RuntimeSecurityConsistencyReportPath) `
    -Checks $checks `
    -Extra @{ runtimeSecurityConsistencyReportPath = Get-PrioritizedControlEvidencePath -WorkspaceRoot $WorkspaceRoot -PathValue $RuntimeSecurityConsistencyReportPath }

Complete-PrioritizedControlScript -Report $report -PassThru:$PassThru
