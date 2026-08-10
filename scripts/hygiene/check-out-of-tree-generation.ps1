<#
.SYNOPSIS
    Generate one app from OUTSIDE the author's directory layout and prove no local absolute path
    leaked into the emitted output.

.DESCRIPTION
    WHY THIS EXISTS
    ---------------
    Every quality gate this repo has verifies THE REPO. None of them verifies the EXPERIENCE OF
    SOMEONE GENERATING FROM SOMEWHERE ELSE, and that is where the expensive defects live. The
    third-person trial of 2026-08-10 found eight, and a fully green gate run had caught none of
    them:

        F1  the D:\WorkSpace build-root fallback
        F2  author paths reaching generated scripts
        F7  a leaked application-wmsoffice.yml
        F8  a hardcoded info-page path

    All four share one shape -- a path from the AUTHOR'S machine emitted into output a STRANGER
    runs -- and all four are invisible to a static scan of the repo, because they only appear once
    generation happens somewhere the author's layout does not reach. Nothing generated out-of-tree,
    so nothing saw them.

    This check is that missing generation. It is deliberately the cheapest possible version: ONE
    sample, generate only (no build, no boot), one assertion.

    WHAT IT DOES *NOT* CATCH -- state this plainly so nobody books the category as closed:
    F3 (the _ops engine gap) and F6 (the dual pluralizers) need generation PER ENGINE, not
    generation from a different DIRECTORY. This check closes the author's-layout half only.

    THE FORBIDDEN SET IS DERIVED, NOT HARDCODED
    -------------------------------------------
    Hardcoding "D:\WorkSpace" into the check that hunts hardcoded paths would be self-defeating: it
    would pass forever on any machine that is not the author's -- which is every machine that
    matters. So the forbidden strings are computed AT RUNTIME from the live layout (workspace root,
    build root, runtimehost-libs, AppGen root, user profile). On CI they are the runner's paths; on
    a contributor's machine, theirs. The literal author token is appended as belt-and-braces only.

    THE VACUOUS-PASS GUARD
    ----------------------
    Two ways this check could pass while proving nothing, both explicitly refused:
      1. Generating somewhere that is STILL inside the author's ancestry (or whose own path contains
         a forbidden token, which would make every self-reference a false positive). The output root
         is asserted to be outside the workspace AND outside AppGen AND token-free before generating.
      2. Generating nothing and scanning zero files. A file-count floor is enforced, so
         "0 scanned, 0 violations, PASS" cannot happen.

.PARAMETER Calibrate
    Run the synthetic control instead of a real generation: plant a poisoned file and confirm the
    scanner fires on it, then confirm a clean tree does not. Proves the DETECTOR works without
    paying for a generation.
#>
[CmdletBinding()]
param(
    [string]$WorkspaceRoot = "",
    [string]$SampleId = "npdev-canary",
    [string]$ReportPath = "",
    [int]$MinimumFilesScanned = 50,
    [switch]$Calibrate
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

. (Join-Path $PSScriptRoot "..\npdev-common.ps1")

if ([string]::IsNullOrWhiteSpace($WorkspaceRoot)) {
    $WorkspaceRoot = Get-NPDevWorkspaceRoot -ScriptRoot $PSScriptRoot
}
$WorkspaceRoot = [System.IO.Path]::GetFullPath($WorkspaceRoot)

# Text-ish files only. A jar or png cannot leak a readable path, and decoding them is wasted time.
$TextSuffixes = @(
    ".java", ".kt", ".ts", ".tsx", ".js", ".json", ".yml", ".yaml", ".xml", ".properties",
    ".ps1", ".sh", ".bat", ".cmd", ".md", ".txt", ".sql", ".html", ".css", ".gradle", ".mustache"
)

function Get-ForbiddenTokens([string]$Root) {
    <# Every absolute path THIS machine would be tempted to bake in. Derived, not hardcoded. #>
    $raw = New-Object System.Collections.Generic.List[string]

    $raw.Add($Root)                                             # the repo itself
    try { $raw.Add((Get-NPDevBuildRoot $Root)) } catch { }       # where build output goes
    try { $raw.Add((Get-NPDevRuntimeHostLibsDir $Root)) } catch { }
    $raw.Add((Split-Path -Parent $Root))                        # the workspace parent (D:\WorkSpace\NPDev)
    $raw.Add((Join-Path (Split-Path -Parent $Root) "AppGen"))
    if ($env:USERPROFILE) { $raw.Add($env:USERPROFILE) }
    $raw.Add("D:\WorkSpace")                                    # belt-and-braces: the known author token

    # Each path can appear in four skins: native, forward-slash, JSON-escaped, and URI.
    $tokens = New-Object System.Collections.Generic.List[string]
    foreach ($p in $raw) {
        if ([string]::IsNullOrWhiteSpace($p)) { continue }
        $n = $p.TrimEnd('\', '/')
        if ($n.Length -lt 4) { continue }
        foreach ($v in @($n, $n.Replace('\', '/'), $n.Replace('\', '\\'), $n.Replace('\', '/').Replace(' ', '%20'))) {
            if (-not $tokens.Contains($v)) { $tokens.Add($v) }
        }
    }
    return $tokens
}

function Find-TokenViolations([string]$ScanRoot, [System.Collections.Generic.List[string]]$Tokens) {
    $violations = New-Object System.Collections.Generic.List[object]
    $scanned = 0
    Get-ChildItem -LiteralPath $ScanRoot -Recurse -File -ErrorAction SilentlyContinue | ForEach-Object {
        if ($TextSuffixes -notcontains $_.Extension.ToLowerInvariant()) { return }
        $scanned++
        $content = Get-Content -LiteralPath $_.FullName -Raw -ErrorAction SilentlyContinue
        if ([string]::IsNullOrEmpty($content)) { return }
        # Report EVERY distinct offending line, not just the first. The original version stopped at
        # the first hit per file "because the rest is noise" -- and on the very first real run that
        # cost something concrete: in application-npdev-db.properties it reported
        # `npdev.database.data-root` (line 6, read by NO Java code) and hid `spring.datasource.url`
        # two lines below, which embeds the same absolute path INTO THE JDBC URL and is read by
        # Spring at boot. The item got filed against the harmless property. First-hit-only does not
        # just lose detail, it can point the fix at the wrong line.
        $seenLines = New-Object System.Collections.Generic.HashSet[int]
        foreach ($tok in $Tokens) {
            $from = 0
            while ($true) {
                $idx = $content.IndexOf($tok, $from, [System.StringComparison]::OrdinalIgnoreCase)
                if ($idx -lt 0) { break }
                $line = ($content.Substring(0, $idx) -split "`n").Count
                if ($seenLines.Add($line)) {
                    $start = [Math]::Max(0, $idx - 40)
                    $len = [Math]::Min(140, $content.Length - $start)
                    $violations.Add([pscustomobject]@{
                        file    = $_.FullName.Substring($ScanRoot.Length).TrimStart('\', '/')
                        line    = $line
                        token   = $tok
                        excerpt = ($content.Substring($start, $len) -replace "\s+", " ").Trim()
                    })
                }
                $from = $idx + $tok.Length
            }
        }
    }
    return [pscustomobject]@{ scanned = $scanned; violations = $violations }
}

# ---------------------------------------------------------------- calibrate
if ($Calibrate) {
    Write-Host "Calibrating check-out-of-tree-generation (detector only, no generation):"
    $ok = $true
    $tmp = Join-Path ([System.IO.Path]::GetTempPath()) ("npdev-oot-cal-" + [guid]::NewGuid().ToString("N").Substring(0, 8))
    New-Item -ItemType Directory -Force -Path $tmp | Out-Null
    try {
        $tokens = Get-ForbiddenTokens $WorkspaceRoot

        Set-Content -LiteralPath (Join-Path $tmp "clean.ps1") -Value '$root = Join-Path $PSScriptRoot ".."' -Encoding UTF8
        $clean = Find-TokenViolations $tmp $tokens
        $c1 = ($clean.violations.Count -eq 0)
        Write-Host ("  {0}  a clean emitted file stays quiet" -f $(if ($c1) { "PASS" } else { "FAIL" }))
        $ok = $ok -and $c1

        Set-Content -LiteralPath (Join-Path $tmp "poisoned.ps1") -Value "`$libs = '$WorkspaceRoot\..\Build\runtimehost-libs'" -Encoding UTF8
        $dirty = Find-TokenViolations $tmp $tokens
        $c2 = ($dirty.violations.Count -ge 1)
        Write-Host ("  {0}  a leaked absolute path fires" -f $(if ($c2) { "PASS" } else { "FAIL" }))
        $ok = $ok -and $c2

        $c3 = ($dirty.scanned -ge 2)
        Write-Host ("  {0}  the file-count floor counts real files" -f $(if ($c3) { "PASS" } else { "FAIL" }))
        $ok = $ok -and $c3
    }
    finally { Remove-Item -LiteralPath $tmp -Recurse -Force -ErrorAction SilentlyContinue }
    if (-not $ok) { exit 1 }
    Write-Host "Calibration OK."
    exit 0
}

# ---------------------------------------------------------------- real run
$runId = "oot-generation-" + (Get-Date).ToString("yyyyMMdd-HHmmssfff")
$tokens = Get-ForbiddenTokens $WorkspaceRoot

# Candidate output roots, most-preferred first. The Windows TEMP dir is DELIBERATELY last: on
# Windows it lives under $env:USERPROFILE, which is itself a forbidden token (a generated app
# carrying "C:\Users\<someone>" is the same defect as one carrying "D:\WorkSpace"), so generating
# there would make every self-reference a false positive. On Linux CI, USERPROFILE is unset and
# /tmp is clean, so the temp path is fine there -- hence a list, not a constant.
$candidateRoots = @()
if ($env:NPDEV_OOT_ROOT) { $candidateRoots += $env:NPDEV_OOT_ROOT }
if ($env:SystemDrive) { $candidateRoots += (Join-Path $env:SystemDrive "npdev-oot") }
$candidateRoots += "/tmp/npdev-oot"
$candidateRoots += ([System.IO.Path]::GetTempPath())

$outRoot = ""
foreach ($cand in $candidateRoots) {
    if ([string]::IsNullOrWhiteSpace($cand)) { continue }
    $probe = Join-Path $cand $runId
    try { $probeFull = [System.IO.Path]::GetFullPath($probe) } catch { continue }
    $clean = $true
    foreach ($tok in $tokens) {
        if ($probeFull.IndexOf($tok, [System.StringComparison]::OrdinalIgnoreCase) -ge 0) { $clean = $false; break }
    }
    if (-not $clean) { continue }
    try {
        New-Item -ItemType Directory -Force -Path $probeFull -ErrorAction Stop | Out-Null
        $outRoot = $probeFull
        break
    }
    catch { continue }
}

if ([string]::IsNullOrWhiteSpace($outRoot)) {
    Write-Error ("REFUSING TO RUN: no writable output root outside every forbidden token. Tried: " + ($candidateRoots -join ", ") + ". Set NPDEV_OOT_ROOT to a clean absolute path.")
    exit 1
}

# --- vacuous-pass guard 1: the output root must genuinely be out of ancestry. -------------
$outFull = [System.IO.Path]::GetFullPath($outRoot)
$appGenRoot = Join-Path (Split-Path -Parent $WorkspaceRoot) "AppGen"
foreach ($ancestor in @($WorkspaceRoot, $appGenRoot)) {
    if ($outFull.StartsWith($ancestor, [System.StringComparison]::OrdinalIgnoreCase)) {
        Write-Error "REFUSING TO RUN: output root '$outFull' is inside '$ancestor'. This check is meaningless in-ancestry."
        exit 1
    }
}
foreach ($tok in $tokens) {
    if ($outFull.IndexOf($tok, [System.StringComparison]::OrdinalIgnoreCase) -ge 0) {
        Write-Error "REFUSING TO RUN: output root '$outFull' itself contains forbidden token '$tok'; every self-reference would be a false positive."
        exit 1
    }
}

Write-Host "Out-of-tree generation check"
Write-Host "  sample      : $SampleId"
Write-Host "  output root : $outFull   (outside the workspace and AppGen)"
Write-Host "  tokens      : $($tokens.Count) derived absolute-path forms"

$generateScript = Resolve-NPDevWorkspacePath $WorkspaceRoot "NPDevSamples\scripts\generate-sample-app.ps1"

$generationError = ""
try {
    & $generateScript -SampleId $SampleId -NPDevRoot $WorkspaceRoot -OutputRoot $outFull -RunId $runId | Out-Null
}
catch {
    $generationError = $_.Exception.Message
}

$result = Find-TokenViolations $outFull $tokens

# --- the ratchet: split findings against the baseline. ------------------------------------
# Everything already leaking when this check was written is recorded, WITH a reason, in
# out-of-tree-generation-baseline.json. New leaks fail. Known ones are reported every run so they
# cannot quietly become permanent.
$baselinePath = Join-Path $PSScriptRoot "out-of-tree-generation-baseline.json"
$baseline = @()
if (Test-Path -LiteralPath $baselinePath) {
    $baseline = (Get-Content -LiteralPath $baselinePath -Raw | ConvertFrom-Json).entries
}

$newViolations = New-Object System.Collections.Generic.List[object]
$knownDefects = New-Object System.Collections.Generic.List[object]
foreach ($v in $result.violations) {
    $rel = $v.file.Replace('\', '/')
    $match = $baseline | Where-Object { $rel -like ("*" + $_.pattern.Replace('\', '/') + "*") } | Select-Object -First 1
    if ($null -eq $match) { $newViolations.Add($v) }
    elseif ($match.category -eq "known-defect") { $knownDefects.Add([pscustomobject]@{ file = $rel; ledger = $match.ledger }) }
}

# --- vacuous-pass guard 2: a scan of nothing is not a pass. -------------------------------
$floorMet = ($result.scanned -ge $MinimumFilesScanned)
$passed = ($generationError -eq "") -and $floorMet -and ($newViolations.Count -eq 0)

Write-Host ""
Write-Host "  files scanned : $($result.scanned)  (floor $MinimumFilesScanned)"
Write-Host "  total hits    : $($result.violations.Count)"
Write-Host "  new (fail)    : $($newViolations.Count)"
Write-Host "  known defects : $($knownDefects.Count)  (baselined, still real -- see PORT-1)"

if ($knownDefects.Count -gt 0) {
    Write-Host ""
    Write-Host "  Known absolute-path leaks still present (NOT excused, just already filed):"
    foreach ($k in ($knownDefects | Sort-Object file -Unique)) {
        Write-Host ("    {0}  [{1}]" -f $k.file, $k.ledger)
    }
}

if ($generationError -ne "") {
    Write-Host ""
    Write-Host "FAIL: generation itself failed out-of-tree -- which is the defect class, not an excuse."
    Write-Host "  $generationError"
}
elseif (-not $floorMet) {
    Write-Host ""
    Write-Host "FAIL: only $($result.scanned) file(s) scanned, below the floor of $MinimumFilesScanned."
    Write-Host "  Generation produced (almost) nothing, so a clean result proves nothing."
}
elseif ($newViolations.Count -gt 0) {
    Write-Host ""
    Write-Host "FAIL: $($newViolations.Count) NEW emitted file(s) carry an absolute path from THIS machine."
    Write-Host "A stranger running this output would follow a path that does not exist for them."
    Write-Host "If one is genuinely correct, add it to out-of-tree-generation-baseline.json WITH a reason.`n"
    foreach ($v in ($newViolations | Select-Object -First 20)) {
        Write-Host ("  {0}:{1}" -f $v.file, $v.line)
        Write-Host ("      token : {0}" -f $v.token)
        Write-Host ("      near  : {0}" -f $v.excerpt)
    }
    if ($newViolations.Count -gt 20) {
        Write-Host ("  ... and {0} more" -f ($newViolations.Count - 20))
    }
}
else {
    Write-Host ""
    if ($knownDefects.Count -gt 0) {
        Write-Host "OK: $($result.scanned) emitted files, no NEW absolute-path leak. $($knownDefects.Count) baselined leak(s) remain -- see PORT-1."
    }
    else {
        Write-Host "OK: $($result.scanned) emitted files, none carrying a local absolute path."
    }
}

if (-not [string]::IsNullOrWhiteSpace($ReportPath)) {
    $report = [pscustomobject]@{
        check           = "out-of-tree-generation"
        runId           = $runId
        sampleId        = $SampleId
        outputRoot      = $outFull
        filesScanned    = $result.scanned
        minimumRequired = $MinimumFilesScanned
        generationError = $generationError
        totalHits       = $result.violations.Count
        newViolations   = $newViolations
        knownDefects    = $knownDefects
        status          = $(if ($passed) { "PASS" } else { "FAIL" })
    }
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $ReportPath) | Out-Null
    $report | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath $ReportPath -Encoding UTF8
    Write-Host "  report: $ReportPath"
}

Remove-Item -LiteralPath $outFull -Recurse -Force -ErrorAction SilentlyContinue

if (-not $passed) { exit 1 }
exit 0
