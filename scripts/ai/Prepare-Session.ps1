<#
.SYNOPSIS
  Prime a working session: refresh the AI indexes, print one screen of orientation.

.DESCRIPTION
  A cold session spends its first dozen tool calls rediscovering what branch it is
  on, what is dirty, what is open, and where things are. Each of those calls is
  billed the full context, so the discovery is expensive precisely when the
  session is cheapest to steer.

  This script answers all of it in ONE call, and refreshes the two indexes that
  let later work grep instead of read.

  Deliberately terse: the output is meant to be read into an AI session's
  context once, so every line has to earn its place. Nothing here is a status
  register -- it is all derived live, and nothing is written into the repo.

.PARAMETER SkipIndexes
  Skip the symbol-map and knowledge-index rebuilds (they take a few seconds).

.EXAMPLE
  pwsh -NoProfile -File scripts/ai/Prepare-Session.ps1
#>
[CmdletBinding()]
param(
    [switch] $SkipIndexes
)

$ErrorActionPreference = 'Stop'

# npdev-build-root-resolution: identify the repo by its CONTENTS, never by name (REG-144).
$repo = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
foreach ($m in 'NPDevContract', 'NPDevGenerator', 'NPDevKernel') {
    if (-not (Test-Path (Join-Path $repo $m))) {
        throw "not the repo root (missing $m): $repo"
    }
}
Push-Location $repo
try {
    Write-Host ''
    Write-Host '=== NPDev session prep ===' -ForegroundColor Cyan

    # --- git -------------------------------------------------------------
    $branch = (git rev-parse --abbrev-ref HEAD 2>$null)
    $head = (git log -1 --format='%h %s' 2>$null)
    $dirty = @(git status --porcelain 2>$null)
    $ahead = (git rev-list --count '@{u}..HEAD' 2>$null)

    Write-Host ("branch   {0}" -f $branch)
    Write-Host ("head     {0}" -f $head)
    if ($dirty.Count -gt 0) {
        Write-Host ("dirty    {0} file(s)" -f $dirty.Count) -ForegroundColor Yellow
        $dirty | Select-Object -First 8 | ForEach-Object { Write-Host "           $_" }
        if ($dirty.Count -gt 8) { Write-Host ("           ... {0} more" -f ($dirty.Count - 8)) }
    } else {
        Write-Host 'dirty    clean'
    }
    if ($ahead -and $ahead -ne '0') {
        Write-Host ("unpushed {0} commit(s)" -f $ahead) -ForegroundColor Yellow
    }

    # --- stale worktrees -------------------------------------------------
    # git prints forward slashes; $repo carries the platform separator.
    $repoSlash = $repo.Replace('\', '/')
    $wt = @(@(git worktree list 2>$null) | Where-Object {
        $_.Replace('\', '/') -notmatch ('^' + [regex]::Escape($repoSlash) + '\s')
    })
    if ($wt.Count -gt 0) {
        Write-Host ("worktree {0} extra (stale ones slow commits to 8-13 min)" -f $wt.Count) -ForegroundColor Yellow
        $wt | ForEach-Object { Write-Host "           $_" }
    }

    # --- ledger ----------------------------------------------------------
    $items = @(Get-ChildItem -Path (Join-Path $repo 'ledger/items') -Filter '*.yml' -ErrorAction SilentlyContinue)
    if ($items.Count -gt 0) {
        $open = @($items | Where-Object {
            $t = Get-Content $_.FullName -Raw -ErrorAction SilentlyContinue
            $t -match '(?m)^status:\s*(OPEN|PARTIAL|IN_PROGRESS)\b'
        })
        Write-Host ("ledger   {0} items, {1} not DONE" -f $items.Count, $open.Count)
        $open | Select-Object -First 10 | ForEach-Object { Write-Host ("           " + $_.BaseName) }
        if ($open.Count -gt 10) { Write-Host ("           ... {0} more" -f ($open.Count - 10)) }
    }

    # --- indexes ---------------------------------------------------------
    if (-not $SkipIndexes) {
        Write-Host ''
        Write-Host 'refreshing indexes...' -ForegroundColor DarkGray
        & python (Join-Path $repo 'scripts/ai/build_symbol_map.py') --quiet
        if ($LASTEXITCODE -eq 0) { Write-Host '  symbol map    ok' } else { Write-Host '  symbol map    FAILED' -ForegroundColor Red }

        & python (Join-Path $repo 'scripts/ai/build_knowledge.py') 2>&1 | Out-Null
        if ($LASTEXITCODE -eq 0) { Write-Host '  knowledge     ok' } else { Write-Host '  knowledge     skipped' -ForegroundColor DarkGray }
    }

    # --- where the indexes landed ----------------------------------------
    $buildRoot = if ($env:NPDEV_BUILD_ROOT) { $env:NPDEV_BUILD_ROOT } else { Join-Path (Split-Path -Parent $repo) 'Build' }
    Write-Host ''
    Write-Host 'grep these instead of reading files whole:' -ForegroundColor Cyan
    Write-Host ("  {0}\npdev-ai\symbol-map.txt" -f $buildRoot)
    Write-Host ''
    Write-Host 'wrap long runs so only the verdict enters context:' -ForegroundColor Cyan
    Write-Host '  python scripts/ai/run_digest.py -- <command>'
    Write-Host ''
}
finally {
    Pop-Location
}
