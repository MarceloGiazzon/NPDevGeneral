#Requires -Version 7
<#
.SYNOPSIS
    Creates a Windows directory junction so that NPDevEditor/ui-react/node_modules
    lives physically outside the source repo.

.DESCRIPTION
    Physical location : D:\WorkSpace\NPDev\Build\npm-modules\editor
    Junction           : NPDevEditor/ui-react/node_modules -> above

    Run once after a fresh clone (or if the junction is missing).
    If node_modules already exists as a real directory it is deleted first
    so `npm install` can re-populate the correct physical location.

.PARAMETER TargetDir
    Physical directory that will hold the npm packages.
    Default: D:\WorkSpace\NPDev\Build\npm-modules\editor

.PARAMETER NpmInstall
    Pass -NpmInstall to run `npm install` after creating the junction.
    Omit when you just want to re-create the junction without installing.
#>
param(
    [string] $TargetDir  = 'D:\WorkSpace\NPDev\Build\npm-modules\editor',
    [switch] $NpmInstall
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$RepoRoot   = $PSScriptRoot | Split-Path | Split-Path   # scripts\hygiene -> scripts -> root
$JunctionPath = Join-Path $RepoRoot 'NPDevEditor\ui-react\node_modules'
$UiReactDir   = Join-Path $RepoRoot 'NPDevEditor\ui-react'

# ── 1. Ensure physical target directory exists ──────────────────────────────
if (-not (Test-Path $TargetDir)) {
    New-Item -ItemType Directory -Path $TargetDir -Force | Out-Null
    Write-Host "Created target dir: $TargetDir"
}

# ── 2. Remove existing node_modules (real dir or stale junction) ────────────
if (Test-Path $JunctionPath) {
    $existing = Get-Item $JunctionPath -Force
    if ($existing.Attributes -match 'ReparsePoint') {
        # Already a junction — check if it already points to our target
        $linkTarget = $existing.Target
        if ($linkTarget -eq $TargetDir) {
            Write-Host "Junction already correct: $JunctionPath -> $TargetDir"
            if ($NpmInstall) { & npm install --prefix $UiReactDir }
            exit 0
        }
        # Stale junction pointing elsewhere — remove it
        Remove-Item $JunctionPath -Force
        Write-Host "Removed stale junction (was -> $linkTarget)"
    } else {
        # Real directory — delete it (slow if populated, fast if empty after clean)
        Remove-Item $JunctionPath -Recurse -Force
        Write-Host "Removed real node_modules directory"
    }
}

# ── 3. Create junction ──────────────────────────────────────────────────────
New-Item -ItemType Junction -Path $JunctionPath -Target $TargetDir | Out-Null
Write-Host "Junction created: $JunctionPath -> $TargetDir"

# ── 4. Optional: npm install ────────────────────────────────────────────────
if ($NpmInstall) {
    Write-Host "Running npm install in $UiReactDir ..."
    Push-Location $UiReactDir
    try { & npm install } finally { Pop-Location }
    Write-Host "npm install complete."
} else {
    Write-Host "Skipping npm install (pass -NpmInstall to run it)."
}
