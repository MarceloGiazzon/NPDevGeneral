[CmdletBinding()]
param(
    [string]$WorkspaceRoot = "",
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
    $ReportPath = Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\reports\out\safe-path-rewrites-report.json"
}
else {
    $ReportPath = Normalize-NPDevPath $ReportPath
}

$replacements = [ordered]@{
    "D:\WorkSpace\NPDev\Project\NP\resources\Models" = (Resolve-NPDevWorkspacePath $WorkspaceRoot "NPDevSamples")
    "D:/WorkSpace/NPDev/Project/NP/resources/Models" = ((Resolve-NPDevWorkspacePath $WorkspaceRoot "NPDevSamples") -replace "\\", "/")
    "D:\WorkSpace\NPDev\Project\NP" = $WorkspaceRoot
    "D:/WorkSpace/NPDev/Project/NP" = ($WorkspaceRoot -replace "\\", "/")
    "D:\WorkSpace\NPDev\scratch" = (Resolve-NPDevWorkspacePath $WorkspaceRoot "NPDevSamples")
    "D:/WorkSpace/NPDev/scratch" = ((Resolve-NPDevWorkspacePath $WorkspaceRoot "NPDevSamples") -replace "\\", "/")
    "D:\WorkSpace\NP_Samples\v0.1\Scenarios" = (Resolve-NPDevWorkspacePath $WorkspaceRoot "NPDevSamples")
    "D:/WorkSpace/NP_Samples/v0.1/Scenarios" = ((Resolve-NPDevWorkspacePath $WorkspaceRoot "NPDevSamples") -replace "\\", "/")
    "Project\BootstrapExec" = "NPDevRuntimeHost"
    "Project/BootstrapExec" = "NPDevRuntimeHost"
    "BootstrapExec" = "NPDevRuntimeHost"
    "Project\NP\resources\Models" = "NPDevSamples"
    "Project/NP/resources/Models" = "NPDevSamples"
}

$include = @("*.json", "*.md", "*.ps1", "*.properties", "*.yml", "*.yaml", "*.gradle")
$files = Get-ChildItem -LiteralPath $WorkspaceRoot -Recurse -File -Force -Include $include | Where-Object {
    $_.FullName -notmatch "\\build\\" -and
    $_.FullName -notmatch "\\node_modules\\" -and
    $_.FullName -notmatch "\\.gradle\\" -and
    $_.FullName -notmatch "\\Output\\" -and
    $_.FullName -notmatch "\\scripts\\reports\\out\\" -and
    $_.FullName -notmatch "\\scripts\\hygiene\\fix-safe-path-rewrites\.ps1$"
}

$changes = @()
foreach ($file in $files) {
    $original = Get-Content -LiteralPath $file.FullName -Raw
    $updated = $original
    foreach ($source in $replacements.Keys) {
        $updated = $updated.Replace($source, [string]$replacements[$source])
    }

    if ($updated -ne $original) {
        Set-Content -LiteralPath $file.FullName -Value $updated -Encoding UTF8
        $changes += [pscustomobject]@{
            path = (Get-NPDevWorkspaceRelativePath $WorkspaceRoot $file.FullName)
        }
    }
}

$report = [pscustomobject]@{
    generatedAt = (Get-Date).ToString("o")
    workspaceRoot = $WorkspaceRoot
    overallStatus = "passed"
    changedFiles = $changes
}
Write-NPDevJsonFile $ReportPath $report
Write-NPDevOk ("Safe path rewrites completed. Changed files: " + $changes.Count)
