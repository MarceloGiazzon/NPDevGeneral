<#
.SYNOPSIS
    Packages a code+docs review snapshot of this repository, with a complete file tree listing
    every tracked file (scripts, ledger items, tests included) even though only code and docs get
    their actual bytes zipped.

.DESCRIPTION
    Enumerates tracked files with `git ls-files` (so nothing untracked or gitignored can leak in).
    The TREE listing (written both alongside the zip and as TREE.txt inside it) always names every
    one of those tracked files, annotated " [zipped]" where its content is actually in the archive.
    A file's bytes are staged into the zip only when it matches a contentInclude pattern in
    scripts/policy/code-docs-zip-manifest.json and no contentExclude pattern -- exclude always wins.
    scripts/, ledger/, and test sources/fixtures match no contentInclude pattern by construction, so
    they are named in the tree but never staged.

    All path knowledge lives in the manifest, not here -- to change what ships, edit the manifest.

    Output goes to the external build root (Get-NPDevBuildRoot), never inside the repo, per
    docs/BUILD_OUTPUT_LOCATION_POLICY.md.

.PARAMETER ManifestPath
    Content manifest. Defaults to scripts/policy/code-docs-zip-manifest.json.

.PARAMETER OutputDir
    Destination directory. Defaults to <BuildRoot>\code-docs-zip.

.PARAMETER ArchiveName
    Zip file name. Defaults to <archiveBaseName>-<yyyyMMdd-HHmm>.zip from the manifest.

.PARAMETER ListOnly
    Print what would be packaged and exit without writing anything.

.PARAMETER KeepStaging
    Leave the staging directory in place after zipping (for inspecting the exact tree).

.PARAMETER IncludeScripts
    Also zip scripts/ content (manifest's optionalContentInclude.scripts) instead of tree-listing it
    only. Off by default -- scripts/ still always appears in the TREE listing either way.

.PARAMETER EmitTextBundle
    Also write one big text file with the TREE listing followed by every zipped file's content
    concatenated in order (same files the zip carries, same -IncludeScripts scope) -- a single
    self-contained document for pasting into an LLM context without unzipping anything. Written
    alongside the zip as <name>.bundle.txt and embedded inside the zip as BUNDLE.txt.

.EXAMPLE
    pwsh -NoProfile -File scripts/release/New-CodeDocsZip.ps1
.EXAMPLE
    pwsh -NoProfile -File scripts/release/New-CodeDocsZip.ps1 -ListOnly
.EXAMPLE
    pwsh -NoProfile -File scripts/release/New-CodeDocsZip.ps1 -IncludeScripts -EmitTextBundle
#>
[CmdletBinding()]
param(
    [string]$ManifestPath = "",
    [string]$OutputDir = "",
    [string]$ArchiveName = "",
    [switch]$ListOnly,
    [switch]$KeepStaging,
    [switch]$IncludeScripts,
    [switch]$EmitTextBundle
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

. (Join-Path $PSScriptRoot "..\npdev-common.ps1")

$workspaceRoot = Get-NPDevWorkspaceRoot $PSScriptRoot

if ([string]::IsNullOrWhiteSpace($ManifestPath)) {
    $ManifestPath = Resolve-NPDevWorkspacePath $workspaceRoot "scripts/policy/code-docs-zip-manifest.json"
}
Ensure-NPDevFile $ManifestPath "code+docs zip manifest"

$manifest = Get-Content -LiteralPath $ManifestPath -Raw | ConvertFrom-Json

$archiveBaseName = "npdev-code-docs"
if ($manifest.PSObject.Properties.Name -contains "archiveBaseName" -and -not [string]::IsNullOrWhiteSpace($manifest.archiveBaseName)) {
    $archiveBaseName = [string]$manifest.archiveBaseName
}

function New-NPDevZipRuleSet {
    param([object[]]$Rules)
    $result = @()
    foreach ($rule in @($Rules)) {
        $result += [pscustomobject]@{
            Pattern = [string]$rule.pattern
            Regex   = [regex]::new([string]$rule.pattern, [System.Text.RegularExpressions.RegexOptions]::IgnoreCase)
            Count   = 0
        }
    }
    return $result
}

$contentIncludeRules = New-NPDevZipRuleSet -Rules @($manifest.contentInclude)
$contentExcludeRules = New-NPDevZipRuleSet -Rules @($manifest.contentExclude)
if ($contentIncludeRules.Count -eq 0) {
    throw "Manifest declares no contentInclude patterns: $ManifestPath"
}

if ($IncludeScripts) {
    $optionalScripts = @()
    if ($manifest.PSObject.Properties.Name -contains "optionalContentInclude" -and
        $manifest.optionalContentInclude.PSObject.Properties.Name -contains "scripts") {
        $optionalScripts = @($manifest.optionalContentInclude.scripts)
    }
    if ($optionalScripts.Count -eq 0) {
        throw "manifest.optionalContentInclude.scripts is empty or missing: $ManifestPath"
    }
    $contentIncludeRules += New-NPDevZipRuleSet -Rules $optionalScripts
}

Write-NPDevInfo ("Workspace root        : " + $workspaceRoot)
Write-NPDevInfo ("Manifest              : " + $ManifestPath)
Write-NPDevInfo ("contentInclude rules  : " + $contentIncludeRules.Count + "   contentExclude rules: " + $contentExcludeRules.Count + "   IncludeScripts: " + [bool]$IncludeScripts)

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

$treeEntries = [System.Collections.Generic.List[string]]::new()
$zippedPaths = [System.Collections.Generic.List[string]]::new()
$missingZipped = [System.Collections.Generic.List[string]]::new()
$totalZippedBytes = [long]0

foreach ($path in ($tracked | Sort-Object)) {
    if ([string]::IsNullOrWhiteSpace($path)) { continue }

    $matchedInclude = $null
    foreach ($rule in $contentIncludeRules) {
        if ($rule.Regex.IsMatch($path)) { $matchedInclude = $rule; break }
    }

    $isZipped = $false
    if ($null -ne $matchedInclude) {
        $matchedExclude = $null
        foreach ($rule in $contentExcludeRules) {
            if ($rule.Regex.IsMatch($path)) { $matchedExclude = $rule; break }
        }
        if ($null -eq $matchedExclude) {
            $matchedInclude.Count++
            $isZipped = $true
        }
        else {
            $matchedExclude.Count++
        }
    }

    if ($isZipped) {
        $full = Join-Path $workspaceRoot ($path -replace "/", [System.IO.Path]::DirectorySeparatorChar)
        if (-not (Test-Path -LiteralPath $full -PathType Leaf)) {
            $missingZipped.Add($path)
            $treeEntries.Add($path)
            continue
        }
        $zippedPaths.Add($path)
        $totalZippedBytes += (Get-Item -LiteralPath $full).Length
        $treeEntries.Add($path + "  [zipped]")
    }
    else {
        $treeEntries.Add($path)
    }
}

foreach ($path in $missingZipped) {
    Write-NPDevWarn ("tracked but not on disk, skipped: " + $path)
}

Write-Host ""
Write-Host "Tracked files       : $($tracked.Count)"
Write-Host "Zipped (code+docs)  : $($zippedPaths.Count)  ($([math]::Round($totalZippedBytes / 1MB, 2)) MB uncompressed)"
Write-Host "Tree-listed only    : $($tracked.Count - $zippedPaths.Count)"
Write-Host ""
Write-Host "  matches per contentInclude pattern"
foreach ($rule in $contentIncludeRules) {
    Write-Host ("    {0,5}  {1}" -f $rule.Count, $rule.Pattern)
}
Write-Host "  files removed per contentExclude pattern"
foreach ($rule in $contentExcludeRules) {
    Write-Host ("    {0,5}  {1}" -f $rule.Count, $rule.Pattern)
}
Write-Host ""

if ($ListOnly) {
    Write-NPDevOk "-ListOnly: nothing written."
    exit 0
}

if ([string]::IsNullOrWhiteSpace($OutputDir)) {
    $OutputDir = Join-Path (Get-NPDevBuildRoot $workspaceRoot) "code-docs-zip"
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
$treePath = Join-Path $OutputDir ([System.IO.Path]::GetFileNameWithoutExtension($ArchiveName) + ".tree.txt")
$stagingRoot = Join-Path $OutputDir ("staging-" + $stamp)

if (Test-Path -LiteralPath $stagingRoot) {
    Remove-Item -LiteralPath $stagingRoot -Recurse -Force
}
New-Item -ItemType Directory -Path $stagingRoot -Force | Out-Null

Write-NPDevInfo ("Staging to            : " + $stagingRoot)
foreach ($path in $zippedPaths) {
    $relative = $path -replace "/", [System.IO.Path]::DirectorySeparatorChar
    $source = Join-Path $workspaceRoot $relative
    $target = Join-Path $stagingRoot $relative
    $targetDir = Split-Path -Parent $target
    if (-not (Test-Path -LiteralPath $targetDir -PathType Container)) {
        New-Item -ItemType Directory -Path $targetDir -Force | Out-Null
    }
    Copy-Item -LiteralPath $source -Destination $target -Force
}

Set-Content -LiteralPath $treePath -Value $treeEntries -Encoding utf8
Copy-Item -LiteralPath $treePath -Destination (Join-Path $stagingRoot "TREE.txt") -Force

$bundlePath = $null
if ($EmitTextBundle) {
    $bundlePath = Join-Path $OutputDir ([System.IO.Path]::GetFileNameWithoutExtension($ArchiveName) + ".bundle.txt")
    Write-NPDevInfo ("Writing text bundle   : " + $bundlePath)

    $bundleLines = [System.Collections.Generic.List[string]]::new()
    $bundleLines.Add("NPDev code+docs bundle -- generated " + (Get-Date -Format "yyyy-MM-dd HH:mm:ss") + " (local)")
    $bundleLines.Add("Zipped (" + $(if ($IncludeScripts) { "code+docs+scripts" } else { "code+docs" }) + "): " + $zippedPaths.Count + " of " + $tracked.Count + " tracked files")
    $bundleLines.Add("")
    $bundleLines.Add("======== TREE (every tracked file; [zipped] = content included below) ========")
    $bundleLines.AddRange($treeEntries)
    $bundleLines.Add("")
    $bundleLines.Add("======== FILE CONTENTS ========")
    foreach ($path in $zippedPaths) {
        $full = Join-Path $workspaceRoot ($path -replace "/", [System.IO.Path]::DirectorySeparatorChar)
        $bundleLines.Add("")
        $bundleLines.Add("---- FILE: " + $path + " ----")
        $fileContent = Get-Content -LiteralPath $full -Raw -Encoding UTF8 -ErrorAction SilentlyContinue
        if ($null -eq $fileContent) { $fileContent = "" }
        $bundleLines.Add($fileContent)
    }

    Set-Content -LiteralPath $bundlePath -Value $bundleLines -Encoding utf8
    Copy-Item -LiteralPath $bundlePath -Destination (Join-Path $stagingRoot "BUNDLE.txt") -Force
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

if (-not $KeepStaging) {
    Remove-Item -LiteralPath $stagingRoot -Recurse -Force
}

$zipItem = Get-Item -LiteralPath $zipPath
Write-Host ""
Write-NPDevOk ("zip      : " + $zipItem.FullName + "  (" + [math]::Round($zipItem.Length / 1MB, 2) + " MB)")
Write-NPDevOk ("tree     : " + $treePath + "  (also embedded as TREE.txt inside the zip)")
if ($bundlePath) {
    $bundleItem = Get-Item -LiteralPath $bundlePath
    Write-NPDevOk ("bundle   : " + $bundleItem.FullName + "  (" + [math]::Round($bundleItem.Length / 1MB, 2) + " MB, also embedded as BUNDLE.txt inside the zip)")
}
Write-NPDevOk ("zipped   : " + $zippedPaths.Count + " of " + $tracked.Count + " tracked files  (" + [math]::Round($totalZippedBytes / 1MB, 2) + " MB uncompressed)")
if ($KeepStaging) {
    Write-NPDevOk ("staging  : " + $stagingRoot)
}
