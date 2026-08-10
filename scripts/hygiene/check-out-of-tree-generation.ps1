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

    THE SECOND RULE: DOES THE OUTPUT NAME ITS OWN BIRTHPLACE? (PORT-2)
    ------------------------------------------------------------------
    The rule above -- "no absolute path from THIS machine" -- was BLIND BY CONSTRUCTION to an entire
    half of the defect class, and it took a hand test to notice. Read the vacuous-pass guard below:
    the output root is required to be token-free, precisely so that a file legitimately referring to
    where it was generated is not a false positive. The consequence nobody drew at the time is that
    an emitted file which hardcodes THE OUTPUT ROOT ITSELF contains no forbidden token and therefore
    cannot be seen. On 2026-08-10 this check reported "807 files scanned, 0 violations" on a tree in
    which four files -- _ops/Run-FinalApp.ps1, _ops/Build-FinalApp.ps1, _ops/resolved-db-plan.json
    and _ops/README_RUNBOOK.md -- all pointed at the directory the app had been generated into. That
    is PORT-2, and the guard that stopped false positives is exactly what hid it.

    It cannot be tuned away by adding tokens, because the offending string IS the output root. The
    SHAPE has to change, so it did:

        generate at A  ->  copy the whole tree to B (no shared ancestry, neither inside the other)
                       ->  scan B for any reference to A, in every path skin

    Nothing legitimate in a PORTABLE app names its birthplace, so this rule needs no allowlist: a
    generated app that still works after being moved cannot mention where it used to live. It is
    also the exact test that found PORT-2 by hand, and it is cheap -- one directory copy of an
    already-generated tree, no second generation.

    The two rules are complementary, not redundant. Rule 1 catches a path from the AUTHOR'S LAYOUT
    (the build root, ~/.gradle, D:\WorkSpace) that has nothing to do with this app; rule 2 catches a
    path that is correct on the generating machine and wrong the instant the app is handed to
    anybody. Both scans run every time.

    THE VACUOUS-PASS GUARD
    ----------------------
    Three ways this check could pass while proving nothing, all explicitly refused:
      1. Generating somewhere that is STILL inside the author's ancestry (or whose own path contains
         a forbidden token, which would make every self-reference a false positive). The output root
         is asserted to be outside the workspace AND outside AppGen AND token-free before generating.
      2. Generating nothing and scanning zero files. A file-count floor is enforced, so
         "0 scanned, 0 violations, PASS" cannot happen.
      3. Copying only part of the tree to B. A partial copy under-reports rule 2 in the quietest way
         imaginable -- the files that did not arrive cannot mention anything -- so B's file count is
         asserted to be at least A's before the birthplace scan is believed.

.PARAMETER Calibrate
    Run the synthetic control instead of a real generation: plant a poisoned file and confirm the
    scanner fires on it, then confirm a clean tree does not. Proves the DETECTOR works without
    paying for a generation. Both rules are calibrated -- for the birthplace rule, a synthetic A/B
    pair is built and a planted file naming A must fire while a clean sibling must not -- and so is
    the partial-copy guard, against a real short copy rather than a restatement of its comparison.
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

function Get-PathSkins([string]$Path) {
    <#
        One path, every spelling an emitted file could use for it: native, forward-slash,
        JSON-escaped, and URI-percent. Extracted from Get-ForbiddenTokens so the birthplace rule
        asks the SAME question in the SAME skins -- a second, hand-rolled list of spellings is how
        one rule quietly stops seeing what the other one does.
    #>
    $skins = New-Object System.Collections.Generic.List[string]
    if ([string]::IsNullOrWhiteSpace($Path)) { return $skins }
    $n = $Path.TrimEnd('\', '/')
    if ($n.Length -lt 4) { return $skins }
    foreach ($v in @($n, $n.Replace('\', '/'), $n.Replace('\', '\\'), $n.Replace('\', '/').Replace(' ', '%20'))) {
        if (-not $skins.Contains($v)) { $skins.Add($v) }
    }
    return $skins
}

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

    $tokens = New-Object System.Collections.Generic.List[string]
    foreach ($p in $raw) {
        foreach ($v in (Get-PathSkins $p)) {
            if (-not $tokens.Contains($v)) { $tokens.Add($v) }
        }
    }
    return $tokens
}

function Test-CopyIsComplete {
    <#
        VACUOUS-PASS GUARD 3. A partial copy under-reports the birthplace rule in the quietest way
        available: a file that never arrived at B cannot mention A, so half a tree scans clean and
        the run says so. A function rather than an inline comparison so -Calibrate can exercise the
        REAL predicate instead of a restatement of it.
    #>
    param([string]$Source, [string]$Destination, [string]$CopyError = "")
    $src = @(Get-ChildItem -LiteralPath $Source -Recurse -File -Force -ErrorAction SilentlyContinue).Count
    $dst = @(Get-ChildItem -LiteralPath $Destination -Recurse -File -Force -ErrorAction SilentlyContinue).Count
    return [pscustomobject]@{
        sourceCount = $src
        copiedCount = $dst
        complete    = ($CopyError -eq "") -and ($src -gt 0) -and ($dst -ge $src)
    }
}

function Split-AgainstBaseline($Violations, $Baseline) {
    <#
        One splitter for both rules. A baseline entry excuses a PATH PATTERN, and the relative path
        of a file is the same whether it was scanned at A or in the copy at B -- so the same entry
        means the same thing to both scans, which is the only way the ratchet stays one ratchet.
    #>
    $fresh = New-Object System.Collections.Generic.List[object]
    $known = New-Object System.Collections.Generic.List[object]
    foreach ($v in $Violations) {
        $rel = $v.file.Replace('\', '/')
        $match = $Baseline | Where-Object { $rel -like ("*" + $_.pattern.Replace('\', '/') + "*") } | Select-Object -First 1
        if ($null -eq $match) { $fresh.Add($v) }
        elseif ($match.category -eq "known-defect") { $known.Add([pscustomobject]@{ file = $rel; ledger = $match.ledger }) }
    }
    return [pscustomobject]@{ fresh = $fresh; known = $known }
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

        # --- rule 2: the birthplace scan. -----------------------------------------------------
        # A synthetic A and a synthetic B, because that is the whole shape of the rule: the tokens
        # are A's own path, and NOTHING in the forbidden set is involved. Without this control the
        # new rule could silently degrade to "scan a copy for nothing" and still print PASS -- which
        # is precisely the failure the rule exists to end.
        $synthA = Join-Path $tmp "birth-A"
        $synthB = Join-Path $tmp "birth-B"
        New-Item -ItemType Directory -Force -Path $synthA | Out-Null
        New-Item -ItemType Directory -Force -Path $synthB | Out-Null
        $aSkins = Get-PathSkins $synthA

        Set-Content -LiteralPath (Join-Path $synthB "portable.ps1") -Value 'Set-Location (Split-Path -Parent $PSScriptRoot)' -Encoding UTF8
        $bClean = Find-TokenViolations $synthB $aSkins
        $c4 = ($bClean.violations.Count -eq 0)
        Write-Host ("  {0}  a moved file that resolves relatively stays quiet" -f $(if ($c4) { "PASS" } else { "FAIL" }))
        $ok = $ok -and $c4

        # Forward slashes on purpose: the JSON half of PORT-2 (resolved-db-plan.json's finalAppPath)
        # was written with '/' while the PowerShell half used '\'. A control that only plants the
        # native skin would pass while the rule was half blind.
        Set-Content -LiteralPath (Join-Path $synthB "birthplace.json") `
            -Value ('{ "finalAppPath": "' + $synthA.Replace('\', '/') + '" }') -Encoding UTF8
        $bDirty = Find-TokenViolations $synthB $aSkins
        $c5 = ($bDirty.violations.Count -ge 1)
        Write-Host ("  {0}  a moved file naming its birthplace fires" -f $(if ($c5) { "PASS" } else { "FAIL" }))
        $ok = $ok -and $c5

        # The rule must fire on the NATIVE skin too, not merely on whichever one the previous
        # control happened to plant.
        Set-Content -LiteralPath (Join-Path $synthB "birthplace.ps1") -Value ("Set-Location '" + $synthA + "'") -Encoding UTF8
        $bNative = Find-TokenViolations $synthB $aSkins
        $c6 = (@($bNative.violations | Where-Object { $_.file -like "*birthplace.ps1" }).Count -ge 1)
        Write-Host ("  {0}  ... in the native path skin as well as the JSON one" -f $(if ($c6) { "PASS" } else { "FAIL" }))
        $ok = $ok -and $c6

        # --- guard 3, exercised rather than asserted. -----------------------------------------
        # A real short copy, not a restatement of the comparison: the guard has to notice that B is
        # missing files, or the birthplace rule can pass by scanning a tree that never arrived.
        $shortA = Join-Path $tmp "guard3-A"
        $shortB = Join-Path $tmp "guard3-B"
        New-Item -ItemType Directory -Force -Path (Join-Path $shortA "nested") | Out-Null
        New-Item -ItemType Directory -Force -Path $shortB | Out-Null
        foreach ($n in @("one.txt", "two.txt")) { Set-Content -LiteralPath (Join-Path $shortA $n) -Value "x" -Encoding UTF8 }
        Set-Content -LiteralPath (Join-Path $shortA "nested\three.txt") -Value "x" -Encoding UTF8
        Copy-Item -LiteralPath (Join-Path $shortA "one.txt") -Destination $shortB -Force
        $short = Test-CopyIsComplete -Source $shortA -Destination $shortB
        $c7 = (-not $short.complete)
        Write-Host ("  {0}  a partial copy ({1} of {2} files) is refused, not scanned" -f $(if ($c7) { "PASS" } else { "FAIL" }), $short.copiedCount, $short.sourceCount)
        $ok = $ok -and $c7

        Copy-Item -Path (Join-Path $shortA '*') -Destination $shortB -Recurse -Force
        $full = Test-CopyIsComplete -Source $shortA -Destination $shortB
        $c8 = $full.complete
        Write-Host ("  {0}  ... and a complete copy ({1} of {2}) is accepted" -f $(if ($c8) { "PASS" } else { "FAIL" }), $full.copiedCount, $full.sourceCount)
        $ok = $ok -and $c8
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

function Select-CleanRoot {
    <#
        Pick the first writable candidate whose OWN path carries none of $Tokens and does not
        overlap anything in $Disjoint. "Overlap" is tested as substring BOTH WAYS, not just as an
        ancestry test, because the birthplace rule searches file CONTENT for A's path: if B's path
        contained A's as a substring, a file in B that named B would read as a file naming A. Used
        for both roots so the destination is chosen by the same rules as the source.
    #>
    param([string]$Leaf, [System.Collections.Generic.List[string]]$Tokens, [string[]]$Disjoint = @())
    foreach ($cand in $candidateRoots) {
        if ([string]::IsNullOrWhiteSpace($cand)) { continue }
        try { $probeFull = [System.IO.Path]::GetFullPath((Join-Path $cand $Leaf)) } catch { continue }
        $clean = $true
        foreach ($tok in $Tokens) {
            if ($probeFull.IndexOf($tok, [System.StringComparison]::OrdinalIgnoreCase) -ge 0) { $clean = $false; break }
        }
        if (-not $clean) { continue }
        foreach ($other in $Disjoint) {
            if ([string]::IsNullOrWhiteSpace($other)) { continue }
            if ($probeFull.IndexOf($other, [System.StringComparison]::OrdinalIgnoreCase) -ge 0 -or
                $other.IndexOf($probeFull, [System.StringComparison]::OrdinalIgnoreCase) -ge 0) { $clean = $false; break }
        }
        if (-not $clean) { continue }
        try {
            New-Item -ItemType Directory -Force -Path $probeFull -ErrorAction Stop | Out-Null
            return $probeFull
        }
        catch { continue }
    }
    return ""
}

$outRoot = Select-CleanRoot -Leaf $runId -Tokens $tokens

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

$split = Split-AgainstBaseline $result.violations $baseline
$newViolations = $split.fresh
$knownDefects = $split.known

# --- RULE 2 (PORT-2): move the tree, then ask whether it still names where it was born. ----
# The copy is the point. Rule 1 above cannot see this class at all -- the output root is required
# to be token-free, so a file hardcoding the output root contains no forbidden token. Copying to a
# disjoint B and scanning for A converts that blind spot into the primary signal, and does it with
# the same detector and the same path skins.
$copyRoot = Select-CleanRoot -Leaf ("oot-copy-" + $runId) -Tokens $tokens -Disjoint @($outFull)
$copyError = ""
$copyComplete = $false
$sourceFileCount = 0
$copiedFileCount = 0
$birth = [pscustomobject]@{ scanned = 0; violations = (New-Object System.Collections.Generic.List[object]) }

if ([string]::IsNullOrWhiteSpace($copyRoot)) {
    $copyError = "no writable copy root disjoint from the output root. Tried: " + ($candidateRoots -join ", ")
}
else {
    try {
        # Contents, not the directory: Copy-Item onto an EXISTING destination nests a level
        # ('<B>/<runid>/App'), which would silently change every relative path the baseline patterns
        # match on. Copying A's children one by one keeps B a peer of A.
        #
        # Enumerated with -Force and copied by LiteralPath rather than passing '<A>/*': a wildcard
        # source silently omits hidden entries, so a tree with one would arrive short. Guard 3 would
        # catch that as a FAILURE, which is the safe direction but the wrong answer -- the run would
        # go red over a copy technique rather than over the app.
        Get-ChildItem -LiteralPath $outFull -Force -ErrorAction Stop | ForEach-Object {
            Copy-Item -LiteralPath $_.FullName -Destination $copyRoot -Recurse -Force -ErrorAction Stop
        }
    }
    catch {
        $copyError = $_.Exception.Message
    }
    # --- vacuous-pass guard 3: a partial copy under-reports, and does it invisibly. --------
    $copyCheck = Test-CopyIsComplete -Source $outFull -Destination $copyRoot -CopyError $copyError
    $sourceFileCount = $copyCheck.sourceCount
    $copiedFileCount = $copyCheck.copiedCount
    $copyComplete = $copyCheck.complete
    if ($copyComplete) {
        $birth = Find-TokenViolations $copyRoot (Get-PathSkins $outFull)
    }
}

$birthSplit = Split-AgainstBaseline $birth.violations $baseline
$newBirthViolations = $birthSplit.fresh
$knownBirthDefects = $birthSplit.known

# --- vacuous-pass guard 2: a scan of nothing is not a pass. -------------------------------
$floorMet = ($result.scanned -ge $MinimumFilesScanned)
$passed = ($generationError -eq "") -and $floorMet -and $copyComplete `
    -and ($newViolations.Count -eq 0) -and ($newBirthViolations.Count -eq 0)

Write-Host ""
Write-Host "  RULE 1  no absolute path from THIS machine"
Write-Host "    files scanned : $($result.scanned)  (floor $MinimumFilesScanned)"
Write-Host "    total hits    : $($result.violations.Count)"
Write-Host "    new (fail)    : $($newViolations.Count)"
Write-Host "    known defects : $($knownDefects.Count)  (baselined, still real -- see PORT-1)"
Write-Host ""
Write-Host "  RULE 2  no reference to the directory it was generated in (PORT-2)"
Write-Host "    copied to     : $copyRoot"
Write-Host "    files copied  : $copiedFileCount  (source $sourceFileCount)"
Write-Host "    files scanned : $($birth.scanned)"
Write-Host "    total hits    : $($birth.violations.Count)"
Write-Host "    new (fail)    : $($newBirthViolations.Count)"
Write-Host "    known defects : $($knownBirthDefects.Count)"

if ($knownDefects.Count -gt 0) {
    Write-Host ""
    Write-Host "  Known absolute-path leaks still present (NOT excused, just already filed):"
    foreach ($k in ($knownDefects | Sort-Object file -Unique)) {
        Write-Host ("    {0}  [{1}]" -f $k.file, $k.ledger)
    }
}
if ($knownBirthDefects.Count -gt 0) {
    Write-Host ""
    Write-Host "  Known birthplace references still present (NOT excused, just already filed):"
    foreach ($k in ($knownBirthDefects | Sort-Object file -Unique)) {
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
elseif (-not $copyComplete) {
    Write-Host ""
    Write-Host "FAIL: the generated tree could not be copied to a disjoint directory, so the"
    Write-Host "birthplace rule did not actually run. A skipped rule is not a passing rule."
    if ($copyError -ne "") { Write-Host "  $copyError" }
    Write-Host "  source files: $sourceFileCount   copied: $copiedFileCount"
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
elseif ($newBirthViolations.Count -gt 0) {
    Write-Host ""
    Write-Host "FAIL: $($newBirthViolations.Count) file(s) in the MOVED copy still name the directory the app"
    Write-Host "was generated in. This is PORT-2's defect class, and it does not fail loudly for the"
    Write-Host "user -- a moved app that still points at its birthplace BUILDS AND RUNS THE ORIGINAL,"
    Write-Host "so someone who copies an app, edits it and runs it is running the copy they did not edit."
    Write-Host "Resolve against `$PSScriptRoot instead (see Get-NpdevDataRoot for the idiom).`n"
    foreach ($v in ($newBirthViolations | Select-Object -First 20)) {
        Write-Host ("  {0}:{1}" -f $v.file, $v.line)
        Write-Host ("      names : {0}" -f $v.token)
        Write-Host ("      near  : {0}" -f $v.excerpt)
    }
    if ($newBirthViolations.Count -gt 20) {
        Write-Host ("  ... and {0} more" -f ($newBirthViolations.Count - 20))
    }
}
else {
    Write-Host ""
    if ($knownDefects.Count -gt 0 -or $knownBirthDefects.Count -gt 0) {
        Write-Host "OK: $($result.scanned) emitted files, no NEW leak of either kind. $($knownDefects.Count + $knownBirthDefects.Count) baselined leak(s) remain -- see PORT-1/PORT-2."
    }
    else {
        Write-Host "OK: $($result.scanned) emitted files carry no local absolute path, and $($birth.scanned) files in"
        Write-Host "    a copy at a disjoint location name nothing at the location they were generated in."
    }
}

if (-not [string]::IsNullOrWhiteSpace($ReportPath)) {
    $report = [pscustomobject]@{
        check              = "out-of-tree-generation"
        runId              = $runId
        sampleId           = $SampleId
        outputRoot         = $outFull
        copyRoot           = $copyRoot
        filesScanned       = $result.scanned
        minimumRequired    = $MinimumFilesScanned
        generationError    = $generationError
        copyError          = $copyError
        sourceFileCount    = $sourceFileCount
        copiedFileCount    = $copiedFileCount
        copyComplete       = $copyComplete
        totalHits          = $result.violations.Count
        newViolations      = $newViolations
        knownDefects       = $knownDefects
        birthplaceScanned  = $birth.scanned
        birthplaceHits     = $birth.violations.Count
        newBirthViolations = $newBirthViolations
        knownBirthDefects  = $knownBirthDefects
        status             = $(if ($passed) { "PASS" } else { "FAIL" })
    }
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $ReportPath) | Out-Null
    $report | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath $ReportPath -Encoding UTF8
    Write-Host "  report: $ReportPath"
}

Remove-Item -LiteralPath $outFull -Recurse -Force -ErrorAction SilentlyContinue
if (-not [string]::IsNullOrWhiteSpace($copyRoot)) {
    Remove-Item -LiteralPath $copyRoot -Recurse -Force -ErrorAction SilentlyContinue
}

if (-not $passed) { exit 1 }
exit 0
