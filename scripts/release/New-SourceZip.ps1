<#
.SYNOPSIS
    Packages a source-code-only zip of this repository.

.DESCRIPTION
    Enumerates tracked files with `git ls-files` (so nothing untracked or gitignored can leak in),
    filters them through the include/exclude patterns in scripts/policy/source-zip-manifest.json,
    stages the survivors, and writes a zip plus a plain-text contents listing.

    All path knowledge lives in the manifest, not here -- to change what ships, edit the manifest.

    Output goes to the external build root (Get-NPDevBuildRoot), never inside the repo, per
    docs/BUILD_OUTPUT_LOCATION_POLICY.md.

.PARAMETER ManifestPath
    Content manifest. Defaults to scripts/policy/source-zip-manifest.json.

.PARAMETER OutputDir
    Destination directory. Defaults to <BuildRoot>\source-zip.

.PARAMETER ArchiveName
    Zip file name. Defaults to <archiveBaseName>-<yyyyMMdd-HHmm>.zip from the manifest.

.PARAMETER ListOnly
    Print what would be packaged and exit without writing anything.

.PARAMETER KeepStaging
    Leave the staging directory in place after zipping (for inspecting the exact tree).

.EXAMPLE
    pwsh -NoProfile -File scripts/release/New-SourceZip.ps1
.EXAMPLE
    pwsh -NoProfile -File scripts/release/New-SourceZip.ps1 -ListOnly
#>
[CmdletBinding()]
param(
    [string]$ManifestPath = "",
    [string]$OutputDir = "",
    [string]$ArchiveName = "",
    [switch]$ListOnly,
    [switch]$KeepStaging
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

. (Join-Path $PSScriptRoot "..\npdev-common.ps1")

$workspaceRoot = Get-NPDevWorkspaceRoot $PSScriptRoot

if ([string]::IsNullOrWhiteSpace($ManifestPath)) {
    $ManifestPath = Resolve-NPDevWorkspacePath $workspaceRoot "scripts/policy/source-zip-manifest.json"
}
Ensure-NPDevFile $ManifestPath "source zip manifest"

$manifest = Get-Content -LiteralPath $ManifestPath -Raw | ConvertFrom-Json

$archiveBaseName = "npdev-source"
if ($manifest.PSObject.Properties.Name -contains "archiveBaseName" -and -not [string]::IsNullOrWhiteSpace($manifest.archiveBaseName)) {
    $archiveBaseName = [string]$manifest.archiveBaseName
}

$includeRules = @()
foreach ($rule in @($manifest.include)) {
    $includeRules += [pscustomobject]@{
        Pattern = [string]$rule.pattern
        Regex   = [regex]::new([string]$rule.pattern, [System.Text.RegularExpressions.RegexOptions]::IgnoreCase)
        Count   = 0
    }
}
$excludeRules = @()
foreach ($rule in @($manifest.exclude)) {
    $excludeRules += [pscustomobject]@{
        Pattern = [string]$rule.pattern
        Regex   = [regex]::new([string]$rule.pattern, [System.Text.RegularExpressions.RegexOptions]::IgnoreCase)
        Count   = 0
    }
}
if ($includeRules.Count -eq 0) {
    throw "Manifest declares no include patterns: $ManifestPath"
}

Write-NPDevInfo ("Workspace root : " + $workspaceRoot)
Write-NPDevInfo ("Manifest       : " + $ManifestPath)
Write-NPDevInfo ("Include rules  : " + $includeRules.Count + "   Exclude rules: " + $excludeRules.Count)

if (-not (Test-NPDevCommandAvailable "git")) {
    throw "git is required to enumerate tracked files but was not found on PATH."
}

Push-Location $workspaceRoot
try {
    $tracked = @(& git ls-files)
    if ($LASTEXITCODE -ne 0) {
        throw "git ls-files failed with exit code $LASTEXITCODE"
    }
}
finally {
    Pop-Location
}

$selected = [System.Collections.Generic.List[string]]::new()
foreach ($path in $tracked) {
    if ([string]::IsNullOrWhiteSpace($path)) { continue }

    $matchedInclude = $null
    foreach ($rule in $includeRules) {
        if ($rule.Regex.IsMatch($path)) { $matchedInclude = $rule; break }
    }
    if ($null -eq $matchedInclude) { continue }

    $matchedExclude = $null
    foreach ($rule in $excludeRules) {
        if ($rule.Regex.IsMatch($path)) { $matchedExclude = $rule; break }
    }
    if ($null -ne $matchedExclude) {
        $matchedExclude.Count++
        continue
    }

    $matchedInclude.Count++
    $selected.Add($path)
}

$missing = [System.Collections.Generic.List[string]]::new()
$present = [System.Collections.Generic.List[string]]::new()
$totalBytes = [long]0
foreach ($path in $selected) {
    $full = Join-Path $workspaceRoot ($path -replace "/", [System.IO.Path]::DirectorySeparatorChar)
    if (-not (Test-Path -LiteralPath $full -PathType Leaf)) {
        $missing.Add($path)
        continue
    }
    $present.Add($path)
    $totalBytes += (Get-Item -LiteralPath $full).Length
}

foreach ($path in $missing) {
    Write-NPDevWarn ("tracked but not on disk, skipped: " + $path)
}

Write-Host ""
Write-Host "Selected $($present.Count) of $($tracked.Count) tracked files  ($([math]::Round($totalBytes / 1MB, 2)) MB uncompressed)"
Write-Host ""
Write-Host "  by top-level directory"
$present |
    ForEach-Object { if ($_ -like "*/*") { ($_ -split "/")[0] } else { "<root>" } } |
    Group-Object |
    Sort-Object Count -Descending |
    ForEach-Object { "    {0,-22} {1,5} files" -f $_.Name, $_.Count } |
    Write-Host

Write-Host ""
Write-Host "  matches per include pattern"
foreach ($rule in $includeRules) {
    Write-Host ("    {0,5}  {1}" -f $rule.Count, $rule.Pattern)
}
Write-Host "  files removed per exclude pattern"
foreach ($rule in $excludeRules) {
    Write-Host ("    {0,5}  {1}" -f $rule.Count, $rule.Pattern)
}
Write-Host ""

if ($ListOnly) {
    Write-NPDevOk "-ListOnly: nothing written."
    exit 0
}

if ([string]::IsNullOrWhiteSpace($OutputDir)) {
    $OutputDir = Join-Path (Get-NPDevBuildRoot $workspaceRoot) "source-zip"
}
$OutputDir = Normalize-NPDevPath $OutputDir

$insideRepo = $OutputDir.StartsWith($workspaceRoot, [System.StringComparison]::OrdinalIgnoreCase)
if ($insideRepo) {
    throw "Refusing to write build output inside the repository ($OutputDir). See docs/BUILD_OUTPUT_LOCATION_POLICY.md."
}

$stamp = Get-Date -Format "yyyyMMdd-HHmm"
if ([string]::IsNullOrWhiteSpace($ArchiveName)) {
    $ArchiveName = "$archiveBaseName-$stamp.zip"
}
if (-not $ArchiveName.EndsWith(".zip", [System.StringComparison]::OrdinalIgnoreCase)) {
    $ArchiveName = $ArchiveName + ".zip"
}

New-Item -ItemType Directory -Path $OutputDir -Force | Out-Null
$zipPath = Join-Path $OutputDir $ArchiveName
$contentsPath = Join-Path $OutputDir ([System.IO.Path]::GetFileNameWithoutExtension($ArchiveName) + ".contents.txt")
$stagingRoot = Join-Path $OutputDir ("staging-" + $stamp)

if (Test-Path -LiteralPath $stagingRoot) {
    Remove-Item -LiteralPath $stagingRoot -Recurse -Force
}
New-Item -ItemType Directory -Path $stagingRoot -Force | Out-Null

Write-NPDevInfo ("Staging to     : " + $stagingRoot)
foreach ($path in $present) {
    $relative = $path -replace "/", [System.IO.Path]::DirectorySeparatorChar
    $source = Join-Path $workspaceRoot $relative
    $target = Join-Path $stagingRoot $relative
    $targetDir = Split-Path -Parent $target
    if (-not (Test-Path -LiteralPath $targetDir -PathType Container)) {
        New-Item -ItemType Directory -Path $targetDir -Force | Out-Null
    }
    Copy-Item -LiteralPath $source -Destination $target -Force
}

if (-not ([System.Management.Automation.PSTypeName]"System.IO.Compression.ZipFile").Type) {
    Add-Type -AssemblyName "System.IO.Compression.FileSystem"
}
if (Test-Path -LiteralPath $zipPath) {
    Remove-Item -LiteralPath $zipPath -Force
}
[System.IO.Compression.ZipFile]::CreateFromDirectory(
    $stagingRoot,
    $zipPath,
    [System.IO.Compression.CompressionLevel]::Optimal,
    $false)

Set-Content -LiteralPath $contentsPath -Value ($present | Sort-Object) -Encoding utf8

if (-not $KeepStaging) {
    Remove-Item -LiteralPath $stagingRoot -Recurse -Force
}

$zipItem = Get-Item -LiteralPath $zipPath
Write-Host ""
Write-NPDevOk ("zip      : " + $zipItem.FullName + "  (" + [math]::Round($zipItem.Length / 1MB, 2) + " MB)")
Write-NPDevOk ("contents : " + $contentsPath)
Write-NPDevOk ("files    : " + $present.Count + "  (" + [math]::Round($totalBytes / 1MB, 2) + " MB uncompressed)")
if ($KeepStaging) {
    Write-NPDevOk ("staging  : " + $stagingRoot)
}
