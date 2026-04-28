[CmdletBinding(DefaultParameterSetName = "ByMetadataPath")]
param(
    [Parameter(ParameterSetName = "ByMetadataPath")]
    [string]$MetadataPath = "",
    [Parameter(ParameterSetName = "Latest")]
    [switch]$Latest,
    [string]$WorkspaceRoot = "",
    [ValidateSet("beta-release", "runtimehost", "sample-matrix", "frontend", "frontend-audit", "editor", "hygiene", "ai-beta-matrix")]
    [string]$Gate = ""
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

. (Join-Path $PSScriptRoot "background-gate-common.ps1")

if ([string]::IsNullOrWhiteSpace($WorkspaceRoot)) {
    $WorkspaceRoot = Get-NPDevWorkspaceRoot $PSScriptRoot
}
$WorkspaceRoot = Normalize-NPDevPath $WorkspaceRoot

if ($PSCmdlet.ParameterSetName -eq "Latest") {
    $metadataRoot = Get-NPDevBackgroundGateMetadataRoot $WorkspaceRoot
    $candidates = @(Get-ChildItem -LiteralPath $metadataRoot -Filter "*.json" | Sort-Object LastWriteTime -Descending)
    if (-not [string]::IsNullOrWhiteSpace($Gate)) {
        $candidates = @($candidates | Where-Object { $_.BaseName -like ($Gate + "-background-*") })
    }
    if ($candidates.Count -eq 0) {
        throw "No background gate metadata files were found."
    }
    $MetadataPath = $candidates[0].FullName
}

$metadata = Read-NPDevBackgroundGateMetadata $MetadataPath
if (-not ($metadata.PSObject.Properties.Name -contains "processId") -or $null -eq $metadata.processId) {
    throw "Background gate metadata does not contain a processId."
}

$stopped = $false
try {
    Stop-Process -Id ([int]$metadata.processId) -Force -ErrorAction Stop
    $stopped = $true
}
catch {
    $stopped = $false
}

$updatedMetadata = Write-NPDevBackgroundGateMetadata -MetadataPath $MetadataPath -Updates @{
    status = if ($stopped) { "stopped" } else { [string]$metadata.status }
    stopRequestedAt = (Get-Date).ToString("o")
    stopSucceeded = $stopped
}

if ($stopped) {
    Write-NPDevWarn ("Stopped background gate '" + [string]$metadata.gate + "' (PID " + [string]$metadata.processId + ").")
}
else {
    Write-NPDevWarn ("Background gate '" + [string]$metadata.gate + "' was not running when stop was requested.")
}

return [pscustomobject]@{
    jobId = [string]$updatedMetadata.jobId
    gate = [string]$updatedMetadata.gate
    processId = $updatedMetadata.processId
    status = [string]$updatedMetadata.status
    stopSucceeded = [bool]$updatedMetadata.stopSucceeded
    metadataPath = $MetadataPath
}
