[CmdletBinding()]
param(
    [string]$WorkspaceRoot = "",
    [switch]$PassThru
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

. (Join-Path $PSScriptRoot "..\npdev-common.ps1")

function Get-VersionLine([string[]]$OutputLines) {
    if ($OutputLines.Count -eq 0) {
        return $null
    }
    return $OutputLines[0].Trim()
}

function Get-MajorFromVersionText([string]$VersionText) {
    if ([string]::IsNullOrWhiteSpace($VersionText)) {
        return $null
    }
    $match = [regex]::Match($VersionText, "([0-9]+)")
    if ($match.Success) {
        return [int]$match.Groups[1].Value
    }
    return $null
}

if ([string]::IsNullOrWhiteSpace($WorkspaceRoot)) {
    $WorkspaceRoot = Get-NPDevWorkspaceRoot $PSScriptRoot
}
$WorkspaceRoot = Normalize-NPDevPath $WorkspaceRoot

$uiRoot = Resolve-NPDevWorkspacePath $WorkspaceRoot "NPDevEditor\ui-react"
Ensure-NPDevDirectory $uiRoot "NPDevEditor ui-react root"

$packageJson = Join-Path $uiRoot "package.json"
$packageLock = Join-Path $uiRoot "package-lock.json"

$details = @()
$status = "passed"

foreach ($tool in @("node", "npm")) {
    if (-not (Test-NPDevCommandAvailable $tool)) {
        $details += [pscustomobject]@{
            tool = $tool
            available = $false
            versionText = $null
        }
        $status = "failed"
        continue
    }

    $captured = Invoke-NPDevCommandCapture -WorkingDirectory $uiRoot -Executable "cmd.exe" -Arguments @("/d", "/c", $tool, "--version")
    $versionText = Get-VersionLine $captured.Output
    $major = Get-MajorFromVersionText $versionText
    $details += [pscustomobject]@{
        tool = $tool
        available = $true
        versionText = $versionText
        major = $major
    }

    if ($captured.ExitCode -ne 0) {
        $status = "failed"
    }
    elseif ($tool -eq "node" -and ($null -eq $major -or $major -lt 18)) {
        $status = "failed"
    }
}

if (-not (Test-Path -LiteralPath $packageJson -PathType Leaf) -or -not (Test-Path -LiteralPath $packageLock -PathType Leaf)) {
    $status = "failed"
}

$result = if ($status -eq "passed") {
    New-NPDevCheckResult "node-toolchain" "passed" "Node/npm toolchain checks passed for NPDevEditor." @{
        details = $details
        packageJson = (Test-Path -LiteralPath $packageJson)
        packageLock = (Test-Path -LiteralPath $packageLock)
    }
}
else {
    New-NPDevCheckResult "node-toolchain" "failed" "Node/npm toolchain checks failed for NPDevEditor." @{
        details = $details
        packageJson = (Test-Path -LiteralPath $packageJson)
        packageLock = (Test-Path -LiteralPath $packageLock)
    }
}

if ($PassThru) {
    return $result
}

if ($result.status -eq "passed") {
    Write-NPDevOk $result.summary
    return
}

Write-NPDevWarn $result.summary
throw $result.summary
