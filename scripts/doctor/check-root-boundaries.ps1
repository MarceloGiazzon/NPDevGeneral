[CmdletBinding()]
param(
    [string]$WorkspaceRoot = "",
    [switch]$PassThru
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

. (Join-Path $PSScriptRoot "..\npdev-common.ps1")

if ([string]::IsNullOrWhiteSpace($WorkspaceRoot)) {
    $WorkspaceRoot = Get-NPDevWorkspaceRoot $PSScriptRoot
}
$WorkspaceRoot = Normalize-NPDevPath $WorkspaceRoot

$violations = @()
foreach ($name in @("build.gradle", "settings.gradle", "package.json", "pnpm-workspace.yaml", ".gradle")) {
    $candidate = Join-Path $WorkspaceRoot $name
    if (Test-Path -LiteralPath $candidate) {
        $violations += (Get-NPDevWorkspaceRelativePath $WorkspaceRoot $candidate)
    }
}

$result = if ($violations.Count -eq 0) {
    New-NPDevCheckResult "root-boundaries" "passed" "Root workspace boundaries are intact." @{
        violations = @()
    }
}
else {
    New-NPDevCheckResult "root-boundaries" "failed" "Root workspace contains forbidden coupling files." @{
        violations = $violations
    }
}

if ($PassThru) {
    return $result
}

if ($result.status -eq "passed") {
    Write-NPDevOk $result.summary
    return
}

Write-NPDevWarn ($result.summary + " " + (($result.data.violations) -join ", "))
throw $result.summary

