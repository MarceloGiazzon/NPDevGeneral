[CmdletBinding()]
param(
    [string]$WorkspaceRoot = "",
    [string]$SampleId = "",
    [string]$ReportPath = ""
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

. (Join-Path $PSScriptRoot "..\npdev-common.ps1")

if ([string]::IsNullOrWhiteSpace($WorkspaceRoot)) {
    $WorkspaceRoot = Get-NPDevWorkspaceRoot $PSScriptRoot
}
$WorkspaceRoot = Normalize-NPDevPath $WorkspaceRoot
if ([string]::IsNullOrWhiteSpace($SampleId)) {
    $SampleId = Get-NPDevDefaultSampleId $WorkspaceRoot
}

if ([string]::IsNullOrWhiteSpace($ReportPath)) {
    $ReportPath = Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\reports\out\deterministic-generation-report.json"
}

$generateScript = Resolve-NPDevWorkspacePath $WorkspaceRoot "NPDevSamples\scripts\generate-sample-app.ps1"
$cleanScript = Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\samples\clean-sample-output.ps1"
Ensure-NPDevFile $generateScript "Canonical sample generation script"
Ensure-NPDevFile $cleanScript "Sample cleanup script"

function Get-ArtifactFingerprint([string]$RootPath) {
    $files = @(Get-ChildItem -LiteralPath $RootPath -Recurse -File -Force | Where-Object {
            $_.FullName -notmatch "\\.gradle\\" -and
            $_.FullName -notmatch "\\build\\" -and
            # Intentional, non-reproducible provenance emitted at final-app assembly time (commit,
            # branch, wall-clock generation timestamp) -- BuildInfoEmitter deliberately keeps it OUT
            # of the generator's deterministic artifact tree, so it must not fail a determinism check.
            $_.Name -ne "npdev-build-info.properties"
        })
    $entries = foreach ($file in $files) {
        [pscustomobject]@{
            path = (Get-NPDevWorkspaceRelativePath $RootPath $file.FullName)
            hash = (Get-FileHash -Algorithm SHA256 -LiteralPath $file.FullName).Hash
        }
    }
    return @($entries | Sort-Object path)
}

$sampleOutput = Resolve-NPDevWorkspacePath $WorkspaceRoot ("NPDevSamples\" + $SampleId + "\Output")
$artifactRoot = Join-Path $sampleOutput "ArtifactNP"
$appRoot = Join-Path $sampleOutput "App"

& $cleanScript -WorkspaceRoot $WorkspaceRoot -SampleIds @($SampleId) | Out-Null
& $generateScript -SampleId $SampleId -NPDevRoot $WorkspaceRoot | Out-Null
$first = @{
    artifact = Get-ArtifactFingerprint $artifactRoot
    app = Get-ArtifactFingerprint $appRoot
}

& $cleanScript -WorkspaceRoot $WorkspaceRoot -SampleIds @($SampleId) | Out-Null
& $generateScript -SampleId $SampleId -NPDevRoot $WorkspaceRoot | Out-Null
$second = @{
    artifact = Get-ArtifactFingerprint $artifactRoot
    app = Get-ArtifactFingerprint $appRoot
}

$firstJson = $first | ConvertTo-Json -Depth 20
$secondJson = $second | ConvertTo-Json -Depth 20
$differs = $firstJson -ne $secondJson

& $cleanScript -WorkspaceRoot $WorkspaceRoot -SampleIds @($SampleId) -BuildCachesOnly | Out-Null

$report = [pscustomobject]@{
    generatedAt = (Get-Date).ToString("o")
    workspaceRoot = $WorkspaceRoot
    sampleId = $SampleId
    overallStatus = if ($differs) { "failed" } else { "passed" }
    firstFileCount = $first.artifact.Count + $first.app.Count
    secondFileCount = $second.artifact.Count + $second.app.Count
}
Write-NPDevJsonFile $ReportPath $report

if (-not $differs) {
    Write-NPDevOk ("Deterministic generation check passed for " + $SampleId + ".")
    return
}

Write-NPDevWarn ("Deterministic generation check failed for " + $SampleId + ".")
throw "Generated artifacts differ between runs."
