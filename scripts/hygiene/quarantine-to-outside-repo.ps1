<#
.SYNOPSIS
    Move removal candidates OUT of the repo into a dated quarantine zone outside it, with a manifest
    that can restore every one of them exactly.

.DESCRIPTION
    Quarantine, not deletion. Nothing here is judged safe on its own -- the point is to make
    "is this actually unused?" a MEASUREMENT instead of an opinion:

        1. quarantine a candidate
        2. run the gates
        3. green -> it was genuinely unused.  red -> restore it, you just learned why it exists.

    Being wrong is free, which is the only reason it is reasonable to try.

    WHAT IT WILL AND WILL NOT TOUCH
    -------------------------------
    -Scope ephemeral   (default) regenerable build byproducts ONLY. node_modules, sample Output
                       dirs, .gradle caches, __pycache__. These are untracked, the workspace-slimness
                       gate already blocks a commit when they accumulate, and nothing is lost.
    -Scope orphans     the 4 documents referenced by no file anywhere in the repo, and the 1 script
                       declared `retired` in script-invocation-declarations.json. TRACKED files --
                       they leave a git deletion behind, so review the diff before committing.
    -Scope candidate   whatever you pass in -Path. For testing one specific thing, e.g. a sample app.

    IT REFUSES to quarantine anything not in the declared list unless you name it with -Path, and it
    refuses to run if the quarantine root would land inside the repo.

    MEASURED CONTEXT (audit 2026-08-10, see __OutsideRepo/audit-2026-08-10/CLEANUP_AND_SAMPLES.md):
    the tracked repo is NOT bloated. 79 of 83 docs are referenced; 201/201 scripts are declared and
    match reality; 0 tracked build artefacts. The orphan list really is 4 files totalling 6.7 KB.
    Do not expect this script to find much -- that is the finding, not a failure of the script.

    DO NOT quarantine docs/*_PLAN.md, MOVE*, *_CHECKLIST*, *_FINDINGS*. All 23 tested are cited by
    live files including CLAUDE.md, CONTRIBUTING.md and a CI workflow, AND
    check-blocker-citation-freshness.py is scoped to those exact filenames -- moving them makes that
    check pass while checking zero files. The script will refuse them.

.PARAMETER Scope
    ephemeral (default) | orphans | candidate

.PARAMETER Path
    With -Scope candidate: the repo-relative path to quarantine.

.PARAMETER Restore
    Restore everything from a named quarantine batch, putting each file back where it came from.

.PARAMETER WhatIf
    Print what would move; touch nothing.

.EXAMPLE
    pwsh -File scripts/hygiene/quarantine-to-outside-repo.ps1 -WhatIf
    pwsh -File scripts/hygiene/quarantine-to-outside-repo.ps1 -Scope ephemeral
    pwsh -File scripts/hygiene/quarantine-to-outside-repo.ps1 -Scope candidate -Path NPDevSamples/durable-workflow-demo
    pwsh -File scripts/hygiene/quarantine-to-outside-repo.ps1 -Restore quarantine-20260810-143000
#>
[CmdletBinding()]
param(
    [ValidateSet("ephemeral", "orphans", "candidate")]
    [string]$Scope = "ephemeral",
    [string]$Path = "",
    [string]$Restore = "",
    [switch]$WhatIf
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

. (Join-Path $PSScriptRoot "..\npdev-common.ps1")

$WorkspaceRoot = [System.IO.Path]::GetFullPath((Get-NPDevWorkspaceRoot -ScriptRoot $PSScriptRoot))
$QuarantineRoot = Join-Path (Split-Path -Parent $WorkspaceRoot) "NPDev_General__OutsideRepo\quarantine"

# Guard: the quarantine must be OUTSIDE the repo, or this achieves nothing.
#
# Compare against the repo root WITH A TRAILING SEPARATOR. A bare StartsWith is wrong and this
# script proved it on its own first run: the quarantine lives in the sibling directory
# `NPDev_General__OutsideRepo`, whose name begins with `NPDev_General`, so the prefix test called a
# sibling "inside the repo" and refused every scope. Same family as REG-144 -- reasoning about a
# path by its NAME instead of by a directory boundary.
$repoWithSep = $WorkspaceRoot.TrimEnd('\', '/') + [System.IO.Path]::DirectorySeparatorChar
if ([System.IO.Path]::GetFullPath($QuarantineRoot).StartsWith($repoWithSep, [System.StringComparison]::OrdinalIgnoreCase)) {
    Write-Error "REFUSING: quarantine root '$QuarantineRoot' is inside the repo."
    exit 1
}

# Regenerable byproducts. Losing any of these costs a rebuild and nothing else.
#
# PATTERNS, not a fixed list. The first version enumerated seven paths by hand and missed
# NPDevKernel/.gradle and every NPDevSamples/*/Output the moment a gate run produced them -- a
# hand-list of things that appear dynamically drifts the same way every other hand-list in this repo
# has. Every module gets a .gradle when Gradle runs in it; every sample gets an Output when
# generated.
$EPHEMERAL_PATTERNS = @(
    ".gradle",              # repo root
    "*/.gradle",            # every module: Contract, Generator, Kernel, RuntimeHost, ...
    "NPDevSamples/*/Output",
    "*/__pycache__",
    "*/*/__pycache__"
)

# Referenced by NO file anywhere (measured 2026-08-10 across 3033 files), plus the one script whose
# own declaration says `retired`. TRACKED -- these leave a git deletion.
$ORPHANS = @(
    "docs/AI_CUSTOM_PANEL_CONTRACT.md",
    "docs/AI_CUSTOM_PROCEDURE_CONTRACT.md",
    "docs/AI_SCENARIO_DIRECTORY_CONTRACT.md",
    "docs/reference/LEGACY_SCHEMA_MIGRATION.md",
    "scripts/quality/run-beta0-final-release-check_v2.ps1"
)

# Load-bearing history. Cited by CLAUDE.md/CONTRIBUTING.md/CI, and two gates key on these names.
$FORBIDDEN_PATTERNS = @("*_PLAN.md", "*_CHECKLIST*.md", "*_FINDINGS*.md", "*MOVE*", "*SCREEN_TAXONOMY*")

function Get-EphemeralTargets {
    $found = @()
    $seen = New-Object System.Collections.Generic.HashSet[string]
    foreach ($pat in $EPHEMERAL_PATTERNS) {
        $glob = Join-Path $WorkspaceRoot ($pat -replace '/', '\')
        foreach ($item in @(Get-Item -Path $glob -Force -ErrorAction SilentlyContinue)) {
            $full = $item.FullName
            if (-not $seen.Add($full.ToLowerInvariant())) { continue }
            $rel = $full.Substring($WorkspaceRoot.Length).TrimStart('\', '/') -replace '\\', '/'
            $n = (Get-ChildItem -LiteralPath $full -Recurse -File -Force -ErrorAction SilentlyContinue | Measure-Object).Count

            # BUSY CHECK. Another session's gate run writes into these while it runs -- 1,181 files
            # landed under NPDevSamples/*/Output in five minutes on 2026-08-10 while a T2 sweep was
            # mid-flight. Moving a directory out from under a running build is the definition of
            # "causes trouble", so anything written to recently is reported and skipped, not moved.
            $recent = @(Get-ChildItem -LiteralPath $full -Recurse -File -Force -ErrorAction SilentlyContinue |
                Where-Object { $_.LastWriteTime -gt (Get-Date).AddMinutes(-5) }).Count
            $found += [pscustomobject]@{ rel = $rel; full = $full; files = $n; busy = ($recent -gt 0) }
        }
    }
    # Plain return; the CALLER wraps with @(). Returning `,@($found)` here double-wraps -- the caller
    # then holds a one-element array containing an array, and `$t.rel` throws. Caught on first run.
    return $found
}

# ---------------------------------------------------------------- restore
if (-not [string]::IsNullOrWhiteSpace($Restore)) {
    $batch = Join-Path $QuarantineRoot $Restore
    $manifestPath = Join-Path $batch "manifest.json"
    if (-not (Test-Path -LiteralPath $manifestPath)) {
        Write-Error "No manifest at $manifestPath"
        exit 1
    }
    $manifest = Get-Content -LiteralPath $manifestPath -Raw | ConvertFrom-Json
    Write-Host "Restoring batch $Restore ($(@($manifest.entries).Count) entry(ies))"
    foreach ($e in $manifest.entries) {
        $dest = Join-Path $WorkspaceRoot ($e.rel -replace '/', '\')
        $src = Join-Path $batch $e.stored
        if (-not (Test-Path -LiteralPath $src)) { Write-Host "  MISSING in quarantine: $($e.rel)"; continue }
        New-Item -ItemType Directory -Force -Path (Split-Path -Parent $dest) | Out-Null
        Move-Item -LiteralPath $src -Destination $dest -Force
        Write-Host "  restored  $($e.rel)"
    }
    Write-Host "Done. Re-run the gates to confirm the tree is back where it was."
    exit 0
}

# ---------------------------------------------------------------- select
$targets = @()
switch ($Scope) {
    "ephemeral" { $targets = @(Get-EphemeralTargets) }
    "orphans" {
        foreach ($rel in $ORPHANS) {
            $full = Join-Path $WorkspaceRoot ($rel -replace '/', '\')
            if (Test-Path -LiteralPath $full) { $targets += [pscustomobject]@{ rel = $rel; full = $full; files = 1; busy = $false } }
        }
    }
    "candidate" {
        if ([string]::IsNullOrWhiteSpace($Path)) { Write-Error "-Scope candidate requires -Path"; exit 1 }
        foreach ($pat in $FORBIDDEN_PATTERNS) {
            if ((Split-Path -Leaf $Path) -like $pat -or $Path -like $pat) {
                Write-Error @"
REFUSING to quarantine '$Path'.

Process docs (plans, checklists, findings, MOVE*, SCREEN_TAXONOMY) are load-bearing history: all 23
tested on 2026-08-10 are cited by live files including CLAUDE.md, CONTRIBUTING.md and a CI workflow.
Worse, check-blocker-citation-freshness.py is SCOPED to these exact filenames -- move them and it
passes while checking zero files, which is a silent green.

If you are certain, move it by hand and run the full gate suite.
"@
                exit 1
            }
        }
        $full = Join-Path $WorkspaceRoot ($Path -replace '/', '\')
        if (-not (Test-Path -LiteralPath $full)) { Write-Error "Not found: $Path"; exit 1 }
        $n = if (Test-Path -LiteralPath $full -PathType Container) {
            (Get-ChildItem -LiteralPath $full -Recurse -File -Force -ErrorAction SilentlyContinue | Measure-Object).Count
        } else { 1 }
        $targets += [pscustomobject]@{ rel = $Path; full = $full; files = $n; busy = $false }
    }
}

if (@($targets).Count -eq 0) {
    Write-Host "Nothing to quarantine for scope '$Scope' -- the tree is already clean."
    exit 0
}

Write-Host "Quarantine scope: $Scope"
Write-Host "Destination     : $QuarantineRoot"
Write-Host ""
$total = 0
foreach ($t in @($targets)) {
    $tag = if ($t.busy) { "  BUSY - written to in the last 5 min, SKIPPED" } else { "" }
    Write-Host ("  {0,-52} {1,6} file(s){2}" -f $t.rel, $t.files, $tag)
    if (-not $t.busy) { $total += $t.files }
}
$busyCount = @($targets | Where-Object { $_.busy }).Count
if ($busyCount -gt 0) {
    Write-Host ""
    Write-Host "  $busyCount target(s) skipped as BUSY. A build is writing there right now; moving it"
    Write-Host "  would break that run. Re-run when it finishes."
}
$targets = @($targets | Where-Object { -not $_.busy })
if (@($targets).Count -eq 0) { Write-Host ""; Write-Host "Nothing movable right now."; exit 0 }
Write-Host ("  {0,-52} {1,6} total" -f "", $total)

if ($WhatIf) {
    Write-Host ""
    Write-Host "-WhatIf: nothing moved."
    exit 0
}

# ---------------------------------------------------------------- move
$batchName = "quarantine-" + (Get-Date).ToString("yyyyMMdd-HHmmss")
$batch = Join-Path $QuarantineRoot $batchName
New-Item -ItemType Directory -Force -Path $batch | Out-Null

$entries = @()
foreach ($t in $targets) {
    $stored = ($t.rel -replace '[\\/]', '__')
    $dest = Join-Path $batch $stored
    Move-Item -LiteralPath $t.full -Destination $dest -Force
    $entries += [pscustomobject]@{ rel = $t.rel; stored = $stored; files = $t.files }
    Write-Host "  moved  $($t.rel)"
}

$manifest = [pscustomobject]@{
    batch      = $batchName
    scope      = $Scope
    takenAt    = (Get-Date).ToString("o")
    repoRoot   = $WorkspaceRoot
    entries    = $entries
    restoreCmd = "pwsh -File scripts/hygiene/quarantine-to-outside-repo.ps1 -Restore $batchName"
}
$manifest | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath (Join-Path $batch "manifest.json") -Encoding UTF8

Write-Host ""
Write-Host "Quarantined to: $batch"
Write-Host ""
Write-Host "NOW PROVE IT WAS UNUSED -- quarantining is the experiment, not the conclusion:"
Write-Host "    pwsh -File scripts/quality/run-all-gates.ps1"
Write-Host ""
Write-Host "Red means it was load-bearing. Put it back:"
Write-Host "    $($manifest.restoreCmd)"
exit 0
