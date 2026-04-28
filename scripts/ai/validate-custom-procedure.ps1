[CmdletBinding()]
param(
    [string]$WorkspaceRoot = "",
    [string]$ProcedurePath = "golden-ai-scenarios\custom-procedure-panel\custom-procedure.json",
    [string]$ReportPath = ""
)

. (Join-Path $PSScriptRoot "ai-beta-common.ps1")
$WorkspaceRoot = Initialize-AiBetaWorkspace $WorkspaceRoot
$ProcedurePath = Resolve-NPDevWorkspacePath $WorkspaceRoot $ProcedurePath
if ([string]::IsNullOrWhiteSpace($ReportPath)) {
    $ReportPath = Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\reports\out\ai-beta\custom-procedure-validation-report.json"
}
$report = Test-AiCustomProcedureObject (Read-AiJsonFile $ProcedurePath "AI custom procedure")
$report = [pscustomobject]@{
    generatedAt = (Get-Date).ToString("o")
    procedurePath = Get-NPDevWorkspaceRelativePath $WorkspaceRoot $ProcedurePath
    overallStatus = $report.overallStatus
    failureClass = $report.failureClass
    checks = $report.checks
}
Write-NPDevJsonFile $ReportPath $report
if ($report.overallStatus -eq "passed") {
    Write-NPDevOk "AI custom procedure validation passed."
    return
}
Write-NPDevWarn "AI custom procedure validation failed."
throw "AI custom procedure validation failed."
