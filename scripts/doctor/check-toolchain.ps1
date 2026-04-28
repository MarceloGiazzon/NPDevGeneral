[CmdletBinding()]
param(
    [string]$WorkspaceRoot = "",
    [switch]$PassThru
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

. (Join-Path $PSScriptRoot "..\npdev-common.ps1")

function Get-JavaMajorVersion([string[]]$OutputLines) {
    $joined = $OutputLines -join "`n"
    $match = [regex]::Match($joined, '(version|openjdk) "?(?<version>[0-9]+)(\.[0-9._]+)?')
    if ($match.Success) {
        return [int]$match.Groups["version"].Value
    }
    return $null
}

if ([string]::IsNullOrWhiteSpace($WorkspaceRoot)) {
    $WorkspaceRoot = Get-NPDevWorkspaceRoot $PSScriptRoot
}
$WorkspaceRoot = Normalize-NPDevPath $WorkspaceRoot

$checks = @()
$status = "passed"

$javaResult = if (Test-NPDevCommandAvailable "java") {
    Invoke-NPDevCommandCapture -WorkingDirectory $WorkspaceRoot -Executable "java" -Arguments @("-version")
}
else {
    $null
}

if ($null -eq $javaResult) {
    $checks += [pscustomobject]@{
        tool = "java"
        available = $false
        version = $null
        output = @()
    }
    $status = "failed"
}
else {
    $javaMajor = Get-JavaMajorVersion $javaResult.Output
    $checks += [pscustomobject]@{
        tool = "java"
        available = $true
        version = $javaMajor
        output = $javaResult.Output
    }
    if ($javaResult.ExitCode -ne 0 -or $null -eq $javaMajor -or $javaMajor -lt 17) {
        $status = "failed"
    }
}

$pwshAvailable = Test-NPDevCommandAvailable "pwsh"
$powershellAvailable = Test-NPDevCommandAvailable "powershell"
$checks += [pscustomobject]@{
    tool = "pwsh"
    available = $pwshAvailable
}
$checks += [pscustomobject]@{
    tool = "powershell"
    available = $powershellAvailable
}

if (-not $powershellAvailable) {
    $status = "failed"
}
elseif (-not $pwshAvailable -and $status -eq "passed") {
    $status = "warning"
}

$summary = switch ($status) {
    "passed" { "PowerShell and Java toolchain checks passed." }
    "warning" { "Java is available, but pwsh is not currently on PATH." }
    default { "Required PowerShell or Java toolchain components are missing or incompatible." }
}

$result = New-NPDevCheckResult "toolchain" $status $summary @{
    checks = $checks
}

if ($PassThru) {
    return $result
}

if ($result.status -eq "passed") {
    Write-NPDevOk $result.summary
    return
}

Write-NPDevWarn $result.summary
if ($result.status -eq "failed") {
    throw $result.summary
}

