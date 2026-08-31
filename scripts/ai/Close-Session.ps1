<#
.SYNOPSIS
  Record where this session stopped, so the next one resumes without re-deriving it.

.DESCRIPTION
  The expensive habit is not a long prompt -- it is refusing to end a session
  because ending it loses the thread. Measured 2026-08-30: "Continue from where
  you left off" cost 421 tool calls at 933k context, and "Continue until full
  implementation" 346 calls at 934k. Both were cheap instructions made expensive
  by the context they were issued into.

  This script makes ending a session safe. It writes a handoff that survives
  /clear: the mechanical state it can derive itself (branch, HEAD, commits since
  the last close, dirty files, unpushed count) plus the narrative only a human or
  an agent mid-task can supply (what was done, the exact next step, what is
  blocked).

  Deliberately written OUTSIDE the repo. A handoff is working state, not
  documentation -- the repo's process-document ban (scripts/policy/
  doc-inventory-policy.json) is the standing rule, and this respects it.

  Two files are written:
    <OutsideRepo>\session-state\current.json    the live handoff; read at start
    <OutsideRepo>\session-state\history.jsonl   append-only, one line per close

  The history exists so that after some months of use there is a real record to
  analyse: how long sessions ran, how often a stated next step matched what
  actually happened next, which plans stalled. That analysis is the point --
  the handoff is just the daily benefit.

.PARAMETER Summary
  What this session actually did. One or two sentences.

.PARAMETER NextStep
  The exact resume point. Be specific enough to act on without re-reading
  anything: "REMEDIATION_PLAN step 7 (twin-pair rule for propertyScopes)".

.PARAMETER Plan
  Path to the plan or roadmap being followed, if any.

.PARAMETER Blocked
  Anything genuinely blocked, and on what.

.PARAMETER Verified
  What verification actually ran: T0/T1/T2, a gate name, or "none".

.EXAMPLE
  pwsh -NoProfile -File scripts/ai/Close-Session.ps1 `
      -Summary "Trimmed CLAUDE.md, added digest runner and symbol map." `
      -NextStep "Wire run_digest.py into run-all-gates.ps1 as the default wrapper." `
      -Verified "aiKnowledge gate 40/40, script inventory 212/212"
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)] [string] $Summary,
    [Parameter(Mandatory = $true)] [string] $NextStep,
    [string] $Plan = '',
    [string] $Blocked = '',
    [string] $Verified = 'none'
)

$ErrorActionPreference = 'Stop'

# npdev-build-root-resolution: identify the repo by CONTENTS, never by name (REG-144).
$repo = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
foreach ($m in 'NPDevContract', 'NPDevGenerator', 'NPDevKernel') {
    if (-not (Test-Path (Join-Path $repo $m))) { throw "not the repo root (missing $m): $repo" }
}

$stateDir = Join-Path (Split-Path -Parent $repo) 'NPDev_General__OutsideRepo\session-state'
if (-not (Test-Path $stateDir)) { New-Item -ItemType Directory -Path $stateDir -Force | Out-Null }

$currentPath = Join-Path $stateDir 'current.json'
$historyPath = Join-Path $stateDir 'history.jsonl'

Push-Location $repo
try {
    $branch = (git rev-parse --abbrev-ref HEAD 2>$null)
    $head = (git rev-parse --short HEAD 2>$null)
    $headSubject = (git log -1 --format='%s' 2>$null)
    $dirty = @(git status --porcelain 2>$null)
    $unpushed = (git rev-list --count '@{u}..HEAD' 2>$null)

    # Commits made since the previous close -- what this session actually landed.
    $sinceHead = $null
    if (Test-Path $currentPath) {
        try { $sinceHead = (Get-Content $currentPath -Raw | ConvertFrom-Json).head } catch { }
    }
    $landed = @()
    if ($sinceHead) {
        $landed = @(git log --oneline "$sinceHead..HEAD" 2>$null)
    }

    $record = [ordered]@{
        closedAt     = (Get-Date).ToUniversalTime().ToString('o')
        branch       = $branch
        head         = $head
        headSubject  = $headSubject
        summary      = $Summary
        nextStep     = $NextStep
        plan         = $Plan
        blocked      = $Blocked
        verified     = $Verified
        dirtyCount   = $dirty.Count
        dirtyFiles   = @($dirty | Select-Object -First 20)
        unpushed     = if ($unpushed) { [int]$unpushed } else { 0 }
        landedCommits = $landed
    }

    $json = $record | ConvertTo-Json -Depth 6
    [System.IO.File]::WriteAllText($currentPath, $json, [System.Text.UTF8Encoding]::new($false))
    [System.IO.File]::AppendAllText($historyPath,
        (($record | ConvertTo-Json -Depth 6 -Compress) + "`n"),
        [System.Text.UTF8Encoding]::new($false))

    Write-Host ''
    Write-Host '=== session closed ===' -ForegroundColor Cyan
    Write-Host ("branch    {0} @ {1}" -f $branch, $head)
    if ($landed.Count -gt 0) {
        Write-Host ("landed    {0} commit(s) this session:" -f $landed.Count)
        $landed | ForEach-Object { Write-Host "            $_" }
    }
    if ($dirty.Count -gt 0) {
        Write-Host ("dirty     {0} file(s) left uncommitted" -f $dirty.Count) -ForegroundColor Yellow
    }
    if ($record.unpushed -gt 0) {
        Write-Host ("unpushed  {0} commit(s)" -f $record.unpushed) -ForegroundColor Yellow
    }
    Write-Host ("verified  {0}" -f $Verified)
    Write-Host ''
    Write-Host 'NEXT STEP (this is what the next session resumes from):' -ForegroundColor Green
    Write-Host ("  {0}" -f $NextStep)
    if ($Plan) { Write-Host ("  plan: {0}" -f $Plan) }
    if ($Blocked) { Write-Host ("  BLOCKED: {0}" -f $Blocked) -ForegroundColor Yellow }
    Write-Host ''
    Write-Host ("saved to {0}" -f $currentPath) -ForegroundColor DarkGray
    Write-Host 'safe to /clear now.' -ForegroundColor Green
    Write-Host ''
}
finally {
    Pop-Location
}
