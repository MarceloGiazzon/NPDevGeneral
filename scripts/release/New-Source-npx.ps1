<#
.SYNOPSIS
    Packs the source-code-only file set into a single text file with `npx repomix@latest`.

.DESCRIPTION
    Same selection as scripts/release/New-SourceZip.ps1 -- `git ls-files` filtered through
    scripts/policy/source-zip-manifest.json -- but the output is one AI-readable .txt instead of a
    zip, produced by repomix.

    The exact file list is handed to repomix as an explicit `include` array in a generated config
    file, so no glob translation happens and the text pack cannot drift from the zip's content.
    (`--stdin` would be the obvious channel, but on Windows repomix silently drops every path
    containing a directory separator and packs only the root-level files -- measured 2026-08-17,
    15 of 1377. The script therefore verifies afterwards that every expected file is present in the
    output, rather than trusting repomix's exit code.)

    The manifest's optional `textExclude` list is applied on top of the shared include/exclude rules:
    binaries and minified bundles belong in a zip but are noise (or impossible) in a text pack.

    Output goes to the external build root (Get-NPDevBuildRoot), never inside the repo, per
    docs/BUILD_OUTPUT_LOCATION_POLICY.md.

.PARAMETER Style
    repomix output style: plain (default, matches the .txt), markdown, xml, or json.

.PARAMETER ListOnly
    Print what would be packed and exit without invoking repomix.

.PARAMETER RemoveComments
    Pass --remove-comments to repomix (smaller pack, loses the explanatory comments this codebase
    leans on heavily -- off by default for that reason).

.EXAMPLE
    pwsh -NoProfile -File scripts/release/New-Source-npx.ps1
.EXAMPLE
    pwsh -NoProfile -File scripts/release/New-Source-npx.ps1 -ListOnly
#>
[CmdletBinding()]
param(
    [string]$ManifestPath = "",
    [string]$OutputDir = "",
    [string]$OutputName = "",
    [ValidateSet("plain", "markdown", "xml", "json")]
    [string]$Style = "plain",
    [switch]$RemoveComments,
    [switch]$ListOnly
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

function New-RuleSet([object]$Rules) {
    $set = @()
    foreach ($rule in @($Rules)) {
        $set += [pscustomobject]@{
            Pattern = [string]$rule.pattern
            Regex   = [regex]::new([string]$rule.pattern, [System.Text.RegularExpressions.RegexOptions]::IgnoreCase)
            Count   = 0
        }
    }
    return , $set
}

$includeRules = New-RuleSet $manifest.include
$excludeRules = New-RuleSet $manifest.exclude
$textExcludeRules = @()
if ($manifest.PSObject.Properties.Name -contains "textExclude") {
    $textExcludeRules = New-RuleSet $manifest.textExclude
}
if ($includeRules.Count -eq 0) {
    throw "Manifest declares no include patterns: $ManifestPath"
}

Write-NPDevInfo ("Workspace root : " + $workspaceRoot)
Write-NPDevInfo ("Manifest       : " + $ManifestPath)
Write-NPDevInfo ("Rules          : " + $includeRules.Count + " include, " + $excludeRules.Count + " exclude, " + $textExcludeRules.Count + " text-only exclude")

if (-not (Test-NPDevCommandAvailable "git")) {
    throw "git is required to enumerate tracked files but was not found on PATH."
}
if (-not (Test-NPDevCommandAvailable "npx")) {
    throw "npx (Node.js) is required to run repomix but was not found on PATH."
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
$textOnlyDropped = [System.Collections.Generic.List[string]]::new()
foreach ($path in $tracked) {
    if ([string]::IsNullOrWhiteSpace($path)) { continue }

    $included = $false
    foreach ($rule in $includeRules) {
        if ($rule.Regex.IsMatch($path)) { $rule.Count++; $included = $true; break }
    }
    if (-not $included) { continue }

    $dropped = $false
    foreach ($rule in $excludeRules) {
        if ($rule.Regex.IsMatch($path)) { $rule.Count++; $dropped = $true; break }
    }
    if ($dropped) { continue }

    foreach ($rule in $textExcludeRules) {
        if ($rule.Regex.IsMatch($path)) { $rule.Count++; $textOnlyDropped.Add($path); $dropped = $true; break }
    }
    if ($dropped) { continue }

    $selected.Add($path)
}

$present = [System.Collections.Generic.List[string]]::new()
$totalBytes = [long]0
foreach ($path in $selected) {
    $full = Join-Path $workspaceRoot ($path -replace "/", [System.IO.Path]::DirectorySeparatorChar)
    if (-not (Test-Path -LiteralPath $full -PathType Leaf)) {
        Write-NPDevWarn ("tracked but not on disk, skipped: " + $path)
        continue
    }
    $present.Add($path)
    $totalBytes += (Get-Item -LiteralPath $full).Length
}

Write-Host ""
Write-Host "Packing $($present.Count) of $($tracked.Count) tracked files  ($([math]::Round($totalBytes / 1MB, 2)) MB of text)"
Write-Host ""
Write-Host "  by top-level directory"
$present |
    ForEach-Object { if ($_ -like "*/*") { ($_ -split "/")[0] } else { "<root>" } } |
    Group-Object |
    Sort-Object Count -Descending |
    ForEach-Object { "    {0,-22} {1,5} files" -f $_.Name, $_.Count } |
    Write-Host

if ($textOnlyDropped.Count -gt 0) {
    Write-Host ""
    Write-Host "  held back from the text pack only (still in the zip)"
    foreach ($rule in $textExcludeRules) {
        Write-Host ("    {0,5}  {1}" -f $rule.Count, $rule.Pattern)
    }
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

if ($OutputDir.StartsWith($workspaceRoot, [System.StringComparison]::OrdinalIgnoreCase)) {
    throw "Refusing to write build output inside the repository ($OutputDir). See docs/BUILD_OUTPUT_LOCATION_POLICY.md."
}

$stamp = Get-Date -Format "yyyyMMdd-HHmm"
if ([string]::IsNullOrWhiteSpace($OutputName)) {
    $extension = if ($Style -eq "plain") { "txt" } elseif ($Style -eq "markdown") { "md" } else { $Style }
    # "-pack-" keeps this run's sidecar files from colliding with a same-minute New-SourceZip.ps1 run,
    # whose contents listing describes a different (binary-inclusive) file set.
    $OutputName = "$archiveBaseName-pack-$stamp.$extension"
}

New-Item -ItemType Directory -Path $OutputDir -Force | Out-Null
$outputPath = Join-Path $OutputDir $OutputName
$listPath = Join-Path $OutputDir ([System.IO.Path]::GetFileNameWithoutExtension($OutputName) + ".contents.txt")

Set-Content -LiteralPath $listPath -Value ($present | Sort-Object) -Encoding utf8

$configPath = Join-Path $OutputDir ([System.IO.Path]::GetFileNameWithoutExtension($OutputName) + ".repomix.config.json")
$config = [ordered]@{
    output = [ordered]@{
        filePath           = $outputPath
        style              = $Style
        fileSummary        = $true
        directoryStructure = $true
        topFilesLength     = 20
        removeComments     = [bool]$RemoveComments
    }
    include = @($present)
    ignore  = [ordered]@{
        useGitignore       = $false
        useDefaultPatterns = $false
        customPatterns     = @()
    }
}
Set-Content -LiteralPath $configPath -Value ($config | ConvertTo-Json -Depth 6) -Encoding utf8

$repomixArgs = @("--yes", "repomix@latest", "-c", $configPath)

Write-NPDevInfo ("Running        : npx " + ($repomixArgs -join " "))
Write-Host ""

Push-Location $workspaceRoot
try {
    & npx @repomixArgs
    $exitCode = if ($null -eq $LASTEXITCODE) { 0 } else { [int]$LASTEXITCODE }
}
finally {
    Pop-Location
}

if ($exitCode -ne 0) {
    throw "repomix failed with exit code $exitCode"
}
if (-not (Test-Path -LiteralPath $outputPath -PathType Leaf)) {
    throw "repomix reported success but produced no output at $outputPath"
}

# repomix can drop files quietly (path handling, the security scanner). Never trust the exit code --
# confirm every expected path actually landed in the pack.
$headerPrefix = switch ($Style) {
    "plain" { "File: " }
    "markdown" { "## File: " }
    default { $null }
}
if ($null -eq $headerPrefix) {
    Write-NPDevWarn "Content verification is only implemented for -Style plain|markdown; skipped."
}
else {
    $seen = [System.Collections.Generic.HashSet[string]]::new([System.StringComparer]::OrdinalIgnoreCase)
    foreach ($line in [System.IO.File]::ReadLines($outputPath)) {
        if ($line.StartsWith($headerPrefix, [System.StringComparison]::Ordinal)) {
            [void]$seen.Add($line.Substring($headerPrefix.Length).Trim().Replace("\", "/"))
        }
    }
    $absent = @($present | Where-Object { -not $seen.Contains($_) })
    if ($absent.Count -gt 0) {
        foreach ($path in ($absent | Select-Object -First 10)) {
            Write-NPDevWarn ("expected but not found in the pack: " + $path)
        }
        throw "repomix packed $($seen.Count) files but $($absent.Count) of the $($present.Count) expected files are missing."
    }
    Write-NPDevOk ("verified : all " + $present.Count + " expected files are present in the pack")
}

$outputItem = Get-Item -LiteralPath $outputPath
Write-Host ""
Write-NPDevOk ("pack     : " + $outputItem.FullName + "  (" + [math]::Round($outputItem.Length / 1MB, 2) + " MB)")
Write-NPDevOk ("contents : " + $listPath)
Write-NPDevOk ("files    : " + $present.Count + "  (" + [math]::Round($totalBytes / 1MB, 2) + " MB of source text)")
