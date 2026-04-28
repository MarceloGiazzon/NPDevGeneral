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
    $ReportPath = Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\reports\out\ai-beta\normalized-custom-procedure.json"
}
$procedure = Read-AiJsonFile $ProcedurePath "AI custom procedure"
$validation = Test-AiCustomProcedureObject $procedure
$normalized = [pscustomobject]@{
    schemaVersion = "ai-normalized-custom-procedure.v1"
    procedureId = [string](Get-AiProperty $procedure "procedureId" "")
    executionMode = [string](Get-AiProperty $procedure "executionMode" "")
    trust = [string](Get-AiProperty $procedure "trust" "")
    inputCount = @(Get-AiProperty $procedure "inputs" @()).Count
    outputCount = @(Get-AiProperty $procedure "outputs" @()).Count
    stepTypes = @((Get-AiProperty $procedure "steps" @()) | ForEach-Object { [string](Get-AiProperty $_ "type" "") })
    validationStatus = $validation.overallStatus
}
Write-NPDevJsonFile $ReportPath $normalized
if ($validation.overallStatus -eq "passed") {
    Write-NPDevOk "AI custom procedure normalization passed."
    return
}
Write-NPDevWarn "AI custom procedure normalization failed."
throw "AI custom procedure normalization failed."
