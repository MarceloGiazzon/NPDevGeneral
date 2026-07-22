param(
    [string]$WorkspaceRoot = "",
    [string]$ReportPath = "",
    [string]$RunId = ""
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

. (Join-Path $PSScriptRoot "..\npdev-common.ps1")

if ([string]::IsNullOrWhiteSpace($WorkspaceRoot)) {
    $WorkspaceRoot = Get-NPDevWorkspaceRoot $PSScriptRoot
}
$WorkspaceRoot = Normalize-NPDevPath $WorkspaceRoot

if ([string]::IsNullOrWhiteSpace($ReportPath)) {
    $ReportPath = Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\reports\out\gradle-wrapper-consistency-report.json"
}
else {
    $ReportPath = Normalize-NPDevPath $ReportPath
}

$RunId = Resolve-NPDevRunId $RunId "gradle-wrapper-consistency"

function Get-RepoRelativePath([string]$PathValue) {
    return (Get-NPDevWorkspaceRelativePath $WorkspaceRoot $PathValue).Replace("/", "\")
}

$excludedSegments = @(".git", ".gradle", "build", "dist", "node_modules", "out", "target")
$wrapperFiles = @(Get-ChildItem -LiteralPath $WorkspaceRoot -Recurse -Force -File -Filter "gradle-wrapper.properties" -ErrorAction SilentlyContinue |
        Where-Object {
            $relative = Get-RepoRelativePath $_.FullName
            $segments = @($relative -split "\\")
            @($segments | Where-Object { $excludedSegments -contains $_ }).Count -eq 0
        } |
        Sort-Object FullName)

$wrappers = @()
$failures = [System.Collections.Generic.List[string]]::new()

foreach ($wrapperFile in $wrapperFiles) {
    $distributionLines = @(Get-Content -LiteralPath $wrapperFile.FullName | Where-Object { $_ -match "^distributionUrl=" })
    $distributionUrl = if ($distributionLines.Count -eq 1) { ($distributionLines[0] -replace "^distributionUrl=", "").Trim() } else { "" }
    if ([string]::IsNullOrWhiteSpace($distributionUrl)) {
        [void]$failures.Add("Missing distributionUrl in " + (Get-RepoRelativePath $wrapperFile.FullName))
    }
    elseif ($distributionLines.Count -gt 1) {
        [void]$failures.Add("Multiple distributionUrl entries in " + (Get-RepoRelativePath $wrapperFile.FullName))
    }
    $wrappers += [pscustomobject]@{
        path = Get-RepoRelativePath $wrapperFile.FullName
        distributionUrl = $distributionUrl
    }
}

$uniqueDistributionUrls = @($wrappers | ForEach-Object { [string]$_.distributionUrl } | Where-Object { -not [string]::IsNullOrWhiteSpace($_) } | Sort-Object -Unique)
if ($wrapperFiles.Count -eq 0) {
    [void]$failures.Add("No gradle-wrapper.properties files were found.")
}
if ($uniqueDistributionUrls.Count -gt 1) {
    [void]$failures.Add("Gradle wrapper distributionUrl values differ: " + ($uniqueDistributionUrls -join ", "))
}

$status = if ($failures.Count -eq 0) { "passed" } else { "failed" }
$report = [pscustomobject]@{
    schemaVersion = "npdev-gradle-wrapper-consistency-report.v1"
    runId = $RunId
    generatedAt = (Get-Date).ToUniversalTime().ToString("o")
    scriptPath = "scripts/hygiene/Test-GradleWrapperConsistency.ps1"
    workspaceRoot = $WorkspaceRoot
    overallStatus = $status
    wrapperCount = $wrapperFiles.Count
    distributionUrl = if ($uniqueDistributionUrls.Count -eq 1) { $uniqueDistributionUrls[0] } else { $null }
    uniqueDistributionUrls = $uniqueDistributionUrls
    wrappers = $wrappers
    failures = @($failures)
}

Write-NPDevJsonFile $ReportPath $report

if ($status -eq "passed") {
    Write-NPDevOk ("Gradle wrapper consistency passed across " + $wrapperFiles.Count + " wrapper file(s). Report: " + $ReportPath)
    exit 0
}

Write-NPDevWarn ("Gradle wrapper consistency failed with " + $failures.Count + " failure(s). Report: " + $ReportPath)
exit 1
