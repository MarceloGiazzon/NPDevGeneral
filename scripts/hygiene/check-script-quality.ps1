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
$RunId = Resolve-NPDevRunId $RunId "script-quality"

if ([string]::IsNullOrWhiteSpace($ReportPath)) {
    $ReportPath = Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\reports\out\script-quality-report.json"
}
else {
    $ReportPath = Normalize-NPDevPath $ReportPath
}

$gateScripts = @(Get-ChildItem -LiteralPath (Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\quality") -Filter "run-*-gate.ps1" -File -ErrorAction SilentlyContinue)
$audit = foreach ($gateScript in $gateScripts) {
    $content = Get-Content -LiteralPath $gateScript.FullName -Raw
    $usesReportedCommand = ($content -match 'Invoke-NPDevReportedCommand')
    $writesStructuredReport = (($content -match 'Write-NPDevJsonFile') -or $usesReportedCommand)
    [pscustomobject]@{
        script = Get-NPDevWorkspaceRelativePath $WorkspaceRoot $gateScript.FullName
        usesReportedCommand = $usesReportedCommand
        writesStructuredReport = $writesStructuredReport
        writesReportsOut = ($content -match 'scripts\\reports\\out\\')
        hasStandardFields = (($content -match 'generatedAt') -and ($content -match 'runId') -and ($content -match 'scriptPath') -and ($content -match 'workspaceRoot') -and ($content -match 'overallStatus')) -or $usesReportedCommand
    }
}

$failures = [System.Collections.Generic.List[string]]::new()
if (@($audit | Where-Object { -not $_.usesReportedCommand -and -not ($_.writesStructuredReport -and $_.hasStandardFields) }).Count -gt 0) {
    [void]$failures.Add("One or more gate scripts do not use the shared structured-report command path.")
}
if (@($audit | Where-Object { -not $_.writesStructuredReport -or -not $_.writesReportsOut }).Count -gt 0) {
    [void]$failures.Add("One or more gate scripts do not visibly emit a JSON report under scripts\\reports\\out.")
}
if (@($audit | Where-Object { -not $_.hasStandardFields }).Count -gt 0) {
    [void]$failures.Add("One or more gate scripts do not visibly emit the standard report fields.")
}

$report = [pscustomobject]@{
    generatedAt = (Get-Date).ToString("o")
    runId = $RunId
    scriptPath = Get-NPDevWorkspaceRelativePath $WorkspaceRoot $PSCommandPath
    workspaceRoot = $WorkspaceRoot
    overallStatus = if ($failures.Count -eq 0) { "passed" } else { "failed" }
    checks = @(
        New-NPDevCheckResult "script-quality" $(if ($failures.Count -eq 0) { "passed" } else { "failed" }) $(if ($failures.Count -eq 0) { "All gate scripts follow the structured reporting contract." } else { $failures -join " " }) @{
            gateScripts = $audit
        }
    )
    summary = [pscustomobject]@{
        failed = if ($failures.Count -eq 0) { 0 } else { 1 }
        warnings = 0
        passed = if ($failures.Count -eq 0) { 1 } else { 0 }
        total = 1
    }
}
Write-NPDevJsonFile $ReportPath $report

if ($report.overallStatus -eq "passed") {
    Write-NPDevOk "Script quality check passed."
    return
}

Write-NPDevWarn "Script quality check failed."
throw "Script quality check failed."
