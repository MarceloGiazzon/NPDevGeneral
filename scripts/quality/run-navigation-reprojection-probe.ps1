param(
    [switch]$KeepAppRunning
)

# W1.2: real two-boot proof that WorkspaceMenuSeeder's default `reconcile` mode actually
# re-projects a declared-menu change onto an already-seeded workspace_v1_menus table, instead of
# either ignoring it (old insert-if-empty default) or nuking every manual edit (old
# upsert-if-fingerprint-changed). Invoked by scripts/quality/check-navigation-reprojection.py.
#
# NPDevSamples/scripts/generate-sample-app.ps1 has no definition/menu.json flattening step (that
# authoring convention -- and Build-NpdevApp.ps1's Get-MenuSeedKey -- is AppGen-only, and AppGen
# apps live outside this repo in a non-git layer, which a committed, repeatable gate cannot depend
# on). WorkspaceMenuSeeder's real, documented input contract is the seed FILE at
# npdev-seed/workspace-menu-pages-seed.json, regardless of what produced it -- so this script
# writes that file directly, in the exact shape Build-NpdevApp.ps1 (also W1.2) produces, standing
# in for "the app author edited definition/menu.json and rebuilt." Everything downstream (boot,
# Flyway, the reconcile SQL, the second boot against the SAME database) is completely real.
#
# A user's generic-CRUD edit is simulated with a direct SQL UPDATE against the H2Local database
# file WHILE THE APP IS STOPPED (same org.h2.tools.Shell/RunScript pattern already proven in
# scripts/ops/canonicalize-tenant-ids.ps1) -- WorkspaceMenuSeeder only ever looks at DB state, so
# it cannot tell that edit apart from one made through the real generic CRUD UI. Final assertions
# read the same way: CSVWRITE from a RunScript pass, not the app's own REST API, so this script has
# no auth/route-naming dependency on the generic CRUD controller at all.

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
. (Join-Path $repoRoot "scripts\npdev-common.ps1")
. (Join-Path $repoRoot "NPDevSamples\scripts\sample-common.ps1")

$buildRoot = Get-NPDevBuildRoot $repoRoot
$sampleId = "probes/path-a-navigation-reprojection"
$probeRoot = Join-Path $repoRoot "NPDevSamples\probes\path-a-navigation-reprojection"
$appRoot = Join-Path $probeRoot "Output\App"
$seedRelativePath = "src\main\resources\npdev-seed\workspace-menu-pages-seed.json"
$dataDir = Join-Path $appRoot "data"
$dbBaseName = "path_a_navigation_reprojection"
$port = 8323
$appBaseUrl = "http://localhost:$port"
$menuTable = "workspace_v1_menus"
$tenantId = "dev"

$bootLogRoot = Join-Path $buildRoot "navigation-reprojection-logs"
New-Item -ItemType Directory -Force -Path $bootLogRoot | Out-Null

function Resolve-H2Jar {
    $roots = @($buildRoot, (Join-Path $env:USERPROFILE ".gradle\caches"))
    $jar = Get-ChildItem -Path $roots -Recurse -Filter 'h2-2*.jar' -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -notmatch 'sources|javadoc' } |
        Sort-Object LastWriteTime -Descending | Select-Object -First 1
    if (-not $jar) {
        Fail "No standalone h2-2*.jar found under the workspace or ~/.gradle -- build an app once first."
    }
    return $jar.FullName
}

function Invoke-H2RunScript([string]$JdbcUrl, [string]$SqlPath) {
    $h2Jar = Resolve-H2Jar
    $out = & java -cp $h2Jar org.h2.tools.RunScript -url $JdbcUrl -user sa -password "" -script $SqlPath 2>&1
    $text = ($out | Out-String)
    if ($text -match '(?im)^\s*(Error|Exception|SQLException)') {
        Fail "H2 RunScript failed for $SqlPath`:`n$text"
    }
    return $text
}

function Get-DbJdbcUrl {
    # PORT-1: the database is app-relative under <FinalApp>/data, spared by FinalAppAssembler
    # across regeneration -- see UserDatabaseDefinitionLoader.java's H2_LOCAL case and
    # FinalAppAssembler.PRESERVED_APP_DIRECTORIES. Must match the app's own connection flags
    # (MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE) or an unquoted identifier in a hand-run statement
    # here would resolve differently than it does for the app itself.
    $dbFile = (Join-Path $dataDir $dbBaseName) -replace '\\', '/'
    return "jdbc:h2:file:$dbFile;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE"
}

function Write-SeedFile([string]$SourceJsonPath) {
    $dest = Join-Path $appRoot $seedRelativePath
    New-Item -ItemType Directory -Force -Path (Split-Path $dest -Parent) | Out-Null
    Copy-Item -LiteralPath $SourceJsonPath -Destination $dest -Force
    Ok ("Wrote " + (Split-Path $SourceJsonPath -Leaf) + " into " + $dest)
}

function Start-ProbeApp([string]$Label) {
    $gradlew = Join-Path $appRoot "gradlew.bat"
    Ensure-File -PathValue $gradlew -Label "Generated app gradlew.bat"
    $outLog = Join-Path $bootLogRoot ($Label + "-boot-out.log")
    $errLog = Join-Path $bootLogRoot ($Label + "-boot-err.log")
    Remove-Item -LiteralPath $outLog -Force -ErrorAction SilentlyContinue
    Remove-Item -LiteralPath $errLog -Force -ErrorAction SilentlyContinue

    $argsLine = '--no-daemon bootRun "--args=--spring.profiles.active=dev,trial --server.port=' + $port + '"'
    $proc = Start-Process -FilePath $gradlew -ArgumentList $argsLine `
        -WorkingDirectory $appRoot -PassThru -WindowStyle Hidden `
        -RedirectStandardOutput $outLog -RedirectStandardError $errLog

    $ready = $false
    for ($i = 0; $i -lt 90; $i++) {
        if ($proc.HasExited) { break }
        try {
            $h = Invoke-RestMethod -Uri ($appBaseUrl + "/actuator/health") -TimeoutSec 2
            if ($h.status -eq "UP") { $ready = $true; break }
        } catch { }
        Start-Sleep -Seconds 2
    }
    if (-not $ready) {
        $tail = if (Test-Path -LiteralPath $outLog) { (Get-Content -LiteralPath $outLog -Tail 60) -join "`n" } else { "(no stdout log)" }
        Fail ("$Label`: app did not become healthy on $appBaseUrl. Boot log tail:`n" + $tail)
    }
    Ok ("$Label`: app healthy on $appBaseUrl")
    return [pscustomobject]@{ Proc = $proc; OutLog = $outLog }
}

function Get-DescendantProcessIds {
    param([int]$RootProcessId)
    $allProcesses = @(Get-CimInstance Win32_Process)
    $pending = [System.Collections.Generic.Queue[int]]::new()
    $descendants = [System.Collections.Generic.List[int]]::new()
    $pending.Enqueue($RootProcessId)
    while ($pending.Count -gt 0) {
        $parentId = $pending.Dequeue()
        foreach ($child in @($allProcesses | Where-Object { $_.ParentProcessId -eq $parentId })) {
            $childId = [int]$child.ProcessId
            $descendants.Add($childId) | Out-Null
            $pending.Enqueue($childId)
        }
    }
    return @($descendants)
}

function Stop-ProbeApp([object]$AppCtx) {
    # gradlew.bat forks Gradle -> a worker -> the actual java.exe running Spring Boot/H2 -- that
    # JVM is a DESCENDANT of $AppCtx.Proc.Id, never $AppCtx.Proc.Id itself (same note
    # scripts/proofs/run-scale-proof.ps1 already makes). Killing only the tracked PID leaves the
    # real process (and its exclusive H2Local file lock) running, which is exactly what made the
    # very next phase's direct H2 RunScript connection fail with "Database may be already in use".
    if ($AppCtx -and $AppCtx.Proc -and -not $AppCtx.Proc.HasExited) {
        $ids = @((Get-DescendantProcessIds -RootProcessId $AppCtx.Proc.Id) | Select-Object -Unique)
        [array]::Reverse($ids)
        foreach ($id in $ids) { Stop-Process -Id $id -Force -ErrorAction SilentlyContinue }
        Stop-Process -Id $AppCtx.Proc.Id -Force -ErrorAction SilentlyContinue
    }
    Start-Sleep -Seconds 3
}

function Get-ReconcileSummaryLine([object]$AppCtx, [int]$TimeoutSeconds = 60) {
    # WAIT for the line rather than reading once. "App healthy" is the actuator reporting UP, which
    # does not imply the menu seeder has finished -- nor that its stdout has been flushed into the
    # redirected log file yet. Reading once raced both, and lost: a real gate run failed here with
    # "reconcile mode did not run" while the expected line, with the exact expected counts, was
    # sitting in the very log file the failure message pointed at. Same defect class as RUN-31 --
    # an assertion racing a write, which looks exactly like the bug the assertion exists to catch.
    # A bounded poll makes the absence of the line mean "it genuinely never ran", which is the only
    # thing this check should ever fail on.
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    do {
        if (Test-Path -LiteralPath $AppCtx.OutLog) {
            $text = Get-Content -LiteralPath $AppCtx.OutLog -Raw
            $m = [regex]::Match($text, 'WorkspaceMenuSeeder: reconciled [^\r\n]+')
            if ($m.Success) {
                return $m.Value
            }
        }
        Start-Sleep -Milliseconds 500
    } while ((Get-Date) -lt $deadline)

    Fail ("Boot log has no 'WorkspaceMenuSeeder: reconciled ...' line after waiting ${TimeoutSeconds}s -- " +
          "reconcile mode did not run. Log: " + $AppCtx.OutLog)
}

$tmpDir = Join-Path $bootLogRoot "csv"
New-Item -ItemType Directory -Force -Path $tmpDir | Out-Null

function Read-Csv1([string]$Name) {
    $path = Join-Path $tmpDir ($Name + ".csv")
    if (-not (Test-Path -LiteralPath $path)) { return @() }
    return @(Import-Csv -LiteralPath $path)
}

$v1AppCtx = $null
$v2AppCtx = $null
$problems = New-Object System.Collections.Generic.List[string]

try {
    Info "=== Phase 0: guaranteed-fresh start -- delete any leftover Output/App from a prior run ==="
    if (Test-Path -LiteralPath $appRoot) {
        Remove-Item -LiteralPath $appRoot -Recurse -Force
    }

    Info "=== Phase 1: generate v1 and write the v1 menu seed ==="
    & (Join-Path $repoRoot "NPDevSamples\scripts\generate-sample-app.ps1") -SampleId $sampleId -NPDevRoot $repoRoot | Out-Null
    if ($LASTEXITCODE -ne 0 -and $null -ne $LASTEXITCODE) { Fail "v1 generation failed" }
    Write-SeedFile (Join-Path $probeRoot "menu-seed-v1.json")
    Ensure-NpdevSampleApiKey -AppRoot $appRoot

    Info "=== Phase 2: boot v1 -- reconcile mode inserts all 3 rows into an empty table ==="
    $v1AppCtx = Start-ProbeApp -Label "v1"
    $v1Summary = Get-ReconcileSummaryLine -AppCtx $v1AppCtx
    Ok ("v1 boot: " + $v1Summary)
    if ($v1Summary -notmatch '3 inserted') {
        $problems.Add("v1 boot did not insert exactly 3 rows into an empty table: $v1Summary")
    }
    Stop-ProbeApp $v1AppCtx

    Info "=== Phase 3: simulate a user's generic-CRUD edit via direct SQL (app stopped) ==="
    $editSql = Join-Path $tmpDir "01-user-edit.sql"
    @"
UPDATE $menuTable SET label = 'Tasks (user edited)' WHERE seed_key = 'BUSINESS:Task' AND tenant_id = '$tenantId';
"@ | Set-Content -LiteralPath $editSql -Encoding UTF8
    Invoke-H2RunScript -JdbcUrl (Get-DbJdbcUrl) -SqlPath $editSql | Out-Null
    Ok "Hand-edited the 'BUSINESS:Task' row's label directly in the database (simulating generic CRUD)."

    Info "=== Phase 4: regenerate (v2) and write the v2 menu seed -- 1 changed, 1 dropped, 1 added ==="
    & (Join-Path $repoRoot "NPDevSamples\scripts\generate-sample-app.ps1") -SampleId $sampleId -NPDevRoot $repoRoot | Out-Null
    if ($LASTEXITCODE -ne 0 -and $null -ne $LASTEXITCODE) { Fail "v2 generation failed" }
    if (-not (Test-Path -LiteralPath $dataDir)) {
        Fail "Output/App/data did not survive regeneration -- FinalAppAssembler's PRESERVED_APP_DIRECTORIES regressed, or PORT-1's app-relative DB path assumption is stale."
    }
    Write-SeedFile (Join-Path $probeRoot "menu-seed-v2.json")
    Ensure-NpdevSampleApiKey -AppRoot $appRoot

    Info "=== Phase 5: boot v2 against the SAME database -- this is where reconcile actually reconciles ==="
    $v2AppCtx = Start-ProbeApp -Label "v2"
    $v2Summary = Get-ReconcileSummaryLine -AppCtx $v2AppCtx
    Ok ("v2 boot: " + $v2Summary)
    if ($v2Summary -notmatch '1 inserted' -or $v2Summary -notmatch '1 updated' -or $v2Summary -notmatch '1 removed' `
            -or $v2Summary -notmatch '1 preserved-as-override' -or $v2Summary -notmatch '1 unchanged') {
        $problems.Add("v2 boot's reconcile summary does not show the expected 1/1/1/1/1 split: $v2Summary")
    }
    Stop-ProbeApp $v2AppCtx

    Info "=== Phase 6: assert final table state directly ==="
    $assertSql = Join-Path $tmpDir "02-assert.sql"
    @"
CALL CSVWRITE('$((Join-Path $tmpDir "unchanged.csv") -replace '\\','/')', 'SELECT label, seed_origin FROM $menuTable WHERE seed_key = ''GROUP:Ops'' AND tenant_id = ''$tenantId''');
CALL CSVWRITE('$((Join-Path $tmpDir "conflict-row.csv") -replace '\\','/')', 'SELECT label, seed_origin FROM $menuTable WHERE seed_key = ''BUSINESS:Task'' AND tenant_id = ''$tenantId''');
CALL CSVWRITE('$((Join-Path $tmpDir "override-row.csv") -replace '\\','/')', 'SELECT label, seed_origin, override_of, visible FROM $menuTable WHERE override_of = ''BUSINESS:Task'' AND tenant_id = ''$tenantId''');
CALL CSVWRITE('$((Join-Path $tmpDir "dropped-row.csv") -replace '\\','/')', 'SELECT label FROM $menuTable WHERE seed_key = ''PAGE:legacy.html'' AND tenant_id = ''$tenantId''');
CALL CSVWRITE('$((Join-Path $tmpDir "added-row.csv") -replace '\\','/')', 'SELECT label, seed_origin FROM $menuTable WHERE seed_key = ''PAGE:new.html'' AND tenant_id = ''$tenantId''');
"@ | Set-Content -LiteralPath $assertSql -Encoding UTF8
    Invoke-H2RunScript -JdbcUrl (Get-DbJdbcUrl) -SqlPath $assertSql | Out-Null

    # @(...) at the CALL SITE, not just inside Read-Csv1 -- a function `return`ing an empty array
    # collapses to $null across the call boundary unless the CALLER also wraps it (confirmed live:
    # `$x = FunctionThatReturnsEmptyArray` gives $null; `$x = @(FunctionThatReturnsEmptyArray)`
    # gives a real 0-length array). Without this, a correctly-empty "dropped-row" result set makes
    # `.Count` throw instead of reading 0.
    $unchanged = @(Read-Csv1 "unchanged")
    if ($unchanged.Count -ne 1 -or $unchanged[0].LABEL -ne "Operations" -or $unchanged[0].SEED_ORIGIN -ne "generated") {
        $problems.Add("unrelated 'GROUP:Ops' row was not left untouched: " + ($unchanged | ConvertTo-Json -Compress))
    }

    $conflict = @(Read-Csv1 "conflict-row")
    if ($conflict.Count -ne 1 -or $conflict[0].LABEL -ne "Tasks (renamed by model)" -or $conflict[0].SEED_ORIGIN -ne "generated") {
        $problems.Add("D7 'model wins': the canonical 'BUSINESS:Task' row does not show the fresh v2 content: " + ($conflict | ConvertTo-Json -Compress))
    }

    $override = @(Read-Csv1 "override-row")
    if ($override.Count -ne 1) {
        $problems.Add("expected exactly one preserved-override row for 'BUSINESS:Task', found " + $override.Count)
    } else {
        if ($override[0].LABEL -notlike "*Tasks (user edited)*") {
            $problems.Add("D7 'user edit preserved': override row does not carry the hand-edited label: " + ($override[0] | ConvertTo-Json -Compress))
        }
        $visibleText = [string]$override[0].VISIBLE
        if ($override[0].SEED_ORIGIN -ne "user" -or $visibleText -notin @("FALSE", "0", "0.0")) {
            $problems.Add("override row provenance/visibility is wrong: " + ($override[0] | ConvertTo-Json -Compress))
        }
    }

    $dropped = @(Read-Csv1 "dropped-row")
    if ($dropped.Count -ne 0) {
        $problems.Add("'PAGE:legacy.html' should have been removed (no longer in the v2 seed) but is still present: " + ($dropped | ConvertTo-Json -Compress))
    }

    $added = @(Read-Csv1 "added-row")
    if ($added.Count -ne 1 -or $added[0].LABEL -ne "New Page" -or $added[0].SEED_ORIGIN -ne "generated") {
        $problems.Add("'PAGE:new.html' (new in v2) was not inserted: " + ($added | ConvertTo-Json -Compress))
    }
}
finally {
    if (-not $KeepAppRunning) {
        if ($v1AppCtx) { Stop-ProbeApp $v1AppCtx }
        if ($v2AppCtx) { Stop-ProbeApp $v2AppCtx }
    }
}

if ($problems.Count -gt 0) {
    Write-Host ""
    foreach ($p in $problems) { Write-Host ("FAIL  " + $p) -ForegroundColor Red }
    Fail ($problems.Count.ToString() + " problem(s) -- see above.")
}

Write-Host ""
Ok "Navigation reprojection proof passed: insert + update-with-preserved-override (D7: model wins) + remove + leave-unrelated-row-untouched, all confirmed across a real two-boot cycle against the same database."
