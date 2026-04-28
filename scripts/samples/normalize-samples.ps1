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
    $ReportPath = Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\reports\out\sample-normalize-report.json"
}
else {
    $ReportPath = Normalize-NPDevPath $ReportPath
}

$samples = Get-NPDevSampleEntries $WorkspaceRoot
$results = @()
$issues = @()

foreach ($sample in $samples) {
    $sampleRoot = Resolve-NPDevWorkspacePath $WorkspaceRoot ("NPDevSamples\" + $sample.id)
    $inputRoot = Join-Path $sampleRoot "Input"
    $outputRoot = Join-Path $sampleRoot "Output"
    $reportsRoot = Join-Path $outputRoot "Reports"

    foreach ($dir in @($sampleRoot, $inputRoot, $outputRoot, $reportsRoot)) {
        if (-not (Test-Path -LiteralPath $dir -PathType Container)) {
            New-Item -ItemType Directory -Force -Path $dir | Out-Null
        }
    }

    $sampleIssues = @()
    $requiredFiles = @("model.json")
    if ($sample.kind -ne "test-model") {
        $requiredFiles += @("config.json", "README.md")
    }
    if ($sample.kind -eq "official-sample") {
        $requiredFiles += @("manifest.json", "expected-behavior.md", "expected-diagnostics.md", "expected-endpoints.md")
    }

    foreach ($requiredFile in $requiredFiles) {
        $filePath = Join-Path $inputRoot $requiredFile
        if (-not (Test-Path -LiteralPath $filePath -PathType Leaf)) {
            $sampleIssues += ("Missing " + (Get-NPDevWorkspaceRelativePath $WorkspaceRoot $filePath))
        }
    }

    if ($sampleIssues.Count -gt 0) {
        $issues += $sampleIssues
    }

    $results += [pscustomobject]@{
        sampleId = $sample.id
        kind = $sample.kind
        inputRoot = $inputRoot
        outputRoot = $outputRoot
        issues = $sampleIssues
    }
}

$report = [pscustomobject]@{
    generatedAt = (Get-Date).ToString("o")
    workspaceRoot = $WorkspaceRoot
    overallStatus = if ($issues.Count -eq 0) { "passed" } else { "failed" }
    results = $results
}
Write-NPDevJsonFile $ReportPath $report

if ($issues.Count -eq 0) {
    Write-NPDevOk "Sample normalization check passed."
    return
}

Write-NPDevWarn "Sample normalization found missing canonical artifacts."
throw "Sample normalization failed."
