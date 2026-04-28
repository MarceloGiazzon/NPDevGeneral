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

$allowedOutputChildren = @("App", "ArtifactNP", "Reports", "RunOutput")
$samples = Get-NPDevSampleEntries $WorkspaceRoot
$issues = @()
$details = @()

foreach ($sample in $samples) {
    $sampleRoot = Resolve-NPDevWorkspacePath $WorkspaceRoot ("NPDevSamples\" + $sample.id)
    $inputRoot = Join-Path $sampleRoot "Input"
    $outputRoot = Join-Path $sampleRoot "Output"

    $sampleIssues = @()
    foreach ($requiredDir in @($sampleRoot, $inputRoot, $outputRoot)) {
        if (-not (Test-Path -LiteralPath $requiredDir -PathType Container)) {
            $sampleIssues += ("Missing directory: " + (Get-NPDevWorkspaceRelativePath $WorkspaceRoot $requiredDir))
        }
    }

    $requiredFiles = @("model.json")
    if ($sample.kind -ne "test-model") {
        $requiredFiles += @("config.json", "README.md")
    }

    foreach ($requiredFile in $requiredFiles) {
        $filePath = Join-Path $inputRoot $requiredFile
        if (-not (Test-Path -LiteralPath $filePath -PathType Leaf)) {
            $sampleIssues += ("Missing file: " + (Get-NPDevWorkspaceRelativePath $WorkspaceRoot $filePath))
        }
    }

    if ($sample.kind -eq "official-sample") {
        foreach ($expectedFile in @("manifest.json", "expected-behavior.md", "expected-diagnostics.md", "expected-endpoints.md")) {
            $filePath = Join-Path $inputRoot $expectedFile
            if (-not (Test-Path -LiteralPath $filePath -PathType Leaf)) {
                $sampleIssues += ("Missing official-sample file: " + (Get-NPDevWorkspaceRelativePath $WorkspaceRoot $filePath))
            }
        }
    }

    if (Test-Path -LiteralPath $outputRoot -PathType Container) {
        $unexpectedChildren = @(Get-ChildItem -LiteralPath $outputRoot -Force | Where-Object {
                $_.Name -notin $allowedOutputChildren
            } | Select-Object -ExpandProperty Name)
        if ($unexpectedChildren.Count -gt 0) {
            $sampleIssues += ("Unexpected Output child entries: " + ($unexpectedChildren -join ", "))
        }
    }

    $details += [pscustomobject]@{
        sampleId = $sample.id
        kind = $sample.kind
        issues = $sampleIssues
    }

    if ($sampleIssues.Count -gt 0) {
        $issues += $sampleIssues
    }
}

$result = if ($issues.Count -eq 0) {
    New-NPDevCheckResult "canonical-sample-layout" "passed" "Canonical sample layout checks passed." @{
        samples = $details
    }
}
else {
    New-NPDevCheckResult "canonical-sample-layout" "failed" "Canonical sample layout drift was detected." @{
        samples = $details
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
