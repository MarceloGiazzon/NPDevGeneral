[CmdletBinding()]
param(
    [string]$WorkspaceRoot = "",
    [switch]$PassThru,
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
    $ReportPath = Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\reports\out\root-build-coupling-report.json"
}

$forbidden = @("build.gradle", "settings.gradle", "pom.xml", "package.json", "package-lock.json")
$findings = @()
foreach ($name in $forbidden) {
    $path = Join-Path $WorkspaceRoot $name
    if (Test-Path -LiteralPath $path -PathType Leaf) {
        $findings += $name
    }
}

$result = if ($findings.Count -eq 0) {
    New-NPDevCheckResult "root-build-coupling" "passed" "No root build coupling files were found." @{ matches = @() }
}
else {
    New-NPDevCheckResult "root-build-coupling" "failed" "Root build coupling files were found." @{ matches = $findings }
}

Write-NPDevJsonFile $ReportPath ([pscustomobject]@{
        generatedAt = (Get-Date).ToString("o")
        workspaceRoot = $WorkspaceRoot
        overallStatus = $result.status
        result = $result
    })

if ($PassThru) {
    return $result
}
if ($result.status -eq "passed") {
    Write-NPDevOk $result.summary
    return
}
Write-NPDevWarn $result.summary
throw $result.summary
