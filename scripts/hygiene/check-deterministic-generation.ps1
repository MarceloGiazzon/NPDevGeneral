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
            $_.Name -ne "npdev-build-info.properties" -and
            # Same non-reproducible-provenance shape (generatedAt / timestamp-derived runId): the sample
            # generation marker. Latent today (it lives in <Output>\Reports, outside the ArtifactNP/App
            # roots compared below) but excluded defensively so it can never fail this check if a future
            # change ever brings Reports into scope. (GATE-DET-1a)
            $_.Name -ne "generation-run.json"
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

# GATE-DET-1a: report WHICH files differ, not just an overall pass/fail. A plain JSON-string
# comparison only yielded a boolean, so four rounds of this saga knew "something differs" but never
# which file -- the very reason the storageBoundary Map.of non-determinism (GATE-DET-1) went
# unattributed. Build a per-file, per-root diff (added / removed / changed) instead.
function Get-DeterminismDiff([object[]]$FirstEntries, [object[]]$SecondEntries, [string]$RootLabel) {
    $firstByPath = @{}
    foreach ($entry in $FirstEntries) { $firstByPath[$entry.path] = $entry.hash }
    $secondByPath = @{}
    foreach ($entry in $SecondEntries) { $secondByPath[$entry.path] = $entry.hash }

    $diffs = @()
    foreach ($path in ($firstByPath.Keys | Sort-Object)) {
        if (-not $secondByPath.ContainsKey($path)) {
            $diffs += [pscustomobject]@{ root = $RootLabel; path = $path; kind = "removed-in-second" }
        }
        elseif ($firstByPath[$path] -ne $secondByPath[$path]) {
            $diffs += [pscustomobject]@{ root = $RootLabel; path = $path; kind = "content-differs" }
        }
    }
    foreach ($path in ($secondByPath.Keys | Sort-Object)) {
        if (-not $firstByPath.ContainsKey($path)) {
            $diffs += [pscustomobject]@{ root = $RootLabel; path = $path; kind = "added-in-second" }
        }
    }
    return @($diffs)
}

$differingFiles = @()
$differingFiles += Get-DeterminismDiff $first.artifact $second.artifact "ArtifactNP"
$differingFiles += Get-DeterminismDiff $first.app $second.app "App"
$differingFiles = @($differingFiles)
$differs = $differingFiles.Count -gt 0

& $cleanScript -WorkspaceRoot $WorkspaceRoot -SampleIds @($SampleId) -BuildCachesOnly | Out-Null

$report = [pscustomobject]@{
    generatedAt = (Get-Date).ToString("o")
    workspaceRoot = $WorkspaceRoot
    sampleId = $SampleId
    overallStatus = if ($differs) { "failed" } else { "passed" }
    firstFileCount = $first.artifact.Count + $first.app.Count
    secondFileCount = $second.artifact.Count + $second.app.Count
    differingFileCount = $differingFiles.Count
    differingFiles = $differingFiles
}
Write-NPDevJsonFile $ReportPath $report

if (-not $differs) {
    Write-NPDevOk ("Deterministic generation check passed for " + $SampleId + ".")
    return
}

Write-NPDevWarn ("Deterministic generation check failed for " + $SampleId + ". Differing files:")
foreach ($diff in $differingFiles) {
    Write-NPDevWarn ("  [" + $diff.root + "] " + $diff.kind + ": " + $diff.path)
}
throw ("Generated artifacts differ between runs (" + $differingFiles.Count + " file(s): " +
    (($differingFiles | ForEach-Object { $_.path }) -join ", ") + ").")
