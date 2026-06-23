param(
    [string]$ScrapForAIRoot = "",
    [int]$ScraperPort = 3010,
    [string]$OutputPath = ""
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

# Destructive-path counterpart to demonstrate-schema-evolution.ps1 (which proves the
# SAFE-ADDITIVE path). This proves the other half of Track 3a/3b's redefined claim:
# "no schema change can ever lose data" is false (a field removal IS destructive,
# unavoidably), so what's actually verifiable and demonstrated here is: every
# destructive recreate is preceded by an automatic, recoverable pre-drop snapshot
# (row count + best-effort full JSON-lines dump) -- generate v1 -> populate a real
# row through the UI -> REMOVE a field from the model (forces the destructive path,
# unlike the additive demo) -> regenerate -> reboot -> confirm, from the boot log AND
# the actual snapshot files on disk, that (a) the destructive path really ran, (b) a
# pre-drop snapshot was written before the DROP TABLE, and (c) that snapshot's row
# count and dumped data genuinely match what was populated in step (a) -- the loss is
# traceable and recoverable-from-snapshot, not silent.
#
# The Input/model.json mutation is temporary and ALWAYS restored (even on failure, via
# try/finally), matching demonstrate-schema-evolution.ps1's convention exactly.

. (Join-Path $PSScriptRoot "..\sample-common.ps1")
. (Join-Path $PSScriptRoot "..\browser\scrapforai-harness.ps1")

$sampleId = "superuser-admin-console"
$port = 8094
$appBaseUrl = "http://localhost:$port"

$samplesRoot = Normalize-AbsolutePath (Join-Path $PSScriptRoot "..\..")
$scriptsRoot = Join-Path $samplesRoot "scripts"
$sample = Resolve-NPDevSample -SamplesRoot $samplesRoot -SampleId $sampleId
$evidenceDir = Join-Path $sample.RunOutputRoot "schema-evolution"
New-Item -ItemType Directory -Force -Path $evidenceDir | Out-Null

$bootLogRoot = Join-Path "D:\WorkSpace\NPDev\Build\schema-evolution-logs" $sampleId
New-Item -ItemType Directory -Force -Path $bootLogRoot | Out-Null

$workspaceRoot = Normalize-AbsolutePath (Join-Path $samplesRoot "..\..")
$dbDir = Join-Path (Join-Path (Join-Path $workspaceRoot "Build") "databases") $sampleId
$appAppRoot = $sample.AppRoot
$snapshotRoot = Join-Path $appAppRoot "runtime-data\schema-snapshot-before-drop"

function Remove-PersistedDatabase {
    if (Test-Path -LiteralPath $dbDir) {
        Info ("Deleting persisted H2 database so this run starts from a guaranteed-fresh schema: " + $dbDir)
        Remove-Item -LiteralPath $dbDir -Recurse -Force
    }
}

function Remove-OldSnapshots {
    if (Test-Path -LiteralPath $snapshotRoot) {
        Info ("Clearing prior pre-drop snapshots so this run's assertions are unambiguous: " + $snapshotRoot)
        Remove-Item -LiteralPath $snapshotRoot -Recurse -Force
    }
}

function Start-SampleAppProcess([string]$Label) {
    $appRoot = $sample.AppRoot
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
        $tail = if (Test-Path -LiteralPath $outLog) { (Get-Content -LiteralPath $outLog -Tail 50) -join "`n" } else { "(no stdout log)" }
        Fail ("$Label : app did not become healthy on " + $appBaseUrl + ". Boot log tail:`n" + $tail)
    }
    Ok ("$Label : app healthy on " + $appBaseUrl)
    return [pscustomobject]@{ Proc = $proc; OutLog = $outLog; ErrLog = $errLog }
}

function Stop-SampleAppProcess([object]$AppCtx) {
    Stop-PortListener -Port $port
    if ($AppCtx -and $AppCtx.Proc -and -not $AppCtx.Proc.HasExited) {
        Stop-Process -Id $AppCtx.Proc.Id -Force -ErrorAction SilentlyContinue
    }
    Start-Sleep -Milliseconds 500
}

function Assert-BootLogContains([object]$AppCtx, [string]$Needle, [string]$Label) {
    $text = Get-Content -LiteralPath $AppCtx.OutLog -Raw
    if ($text -notlike ("*" + $Needle + "*")) {
        Fail ("$Label : expected boot log to contain `"$Needle`" but it did not. Log: " + $AppCtx.OutLog)
    }
    Ok ("$Label : boot log contains `"$Needle`"")
}

$runStamp = (Get-Date).ToString("yyyyMMdd-HHmmss")
$sharedVars = @{
    projectName = "UITEST-PROJECT-DESTRUCTIVE-$runStamp"
    noteBody    = "UITEST-NOTE-DESTRUCTIVE-$runStamp"
}

$modelPath = $sample.ModelPath
$originalModelRaw = Get-Content -LiteralPath $modelPath -Raw

$results = @()
$appCtx = $null
$scrapCtx = $null
$modelMutated = $false

try {
    Remove-PersistedDatabase
    Remove-OldSnapshots

    Info "=== Phase 1: generate + boot the v1 schema ==="
    & (Join-Path $scriptsRoot "generate-sample-app.ps1") -SampleId $sampleId | Out-Null
    if ($LASTEXITCODE -ne 0 -and $null -ne $LASTEXITCODE) { Fail "v1 generation failed" }
    $appCtx = Start-SampleAppProcess -Label "v1"
    Assert-BootLogContains -AppCtx $appCtx -Needle "no stored schema fingerprint found" -Label "v1 boot (fresh database)"

    Info "=== Phase 2: populate one Project + one Note through the real UI (Note is the leaf table this demo targets -- nothing has an FK pointing at it, so dropping it alone never hits the pre-existing FK-ordering limitation that dropping the referenced Project table would) ==="
    Initialize-ScrapForAI -Root $ScrapForAIRoot | Out-Null
    $scrapCtx = Start-ScrapForAI -AppBaseUrl $appBaseUrl -Root $ScrapForAIRoot -Port $ScraperPort `
        -ArtifactDir (Join-Path "D:\WorkSpace\NPDev\Build\scrapforai-artifacts" $sampleId)
    $populateResult = Invoke-ScrapRoutine -Context $scrapCtx `
        -RoutinePath (Join-Path $PSScriptRoot "browser-routines\04-create-project-and-note-via-ui.json") `
        -Variables $sharedVars
    Assert-RoutineGreen -Result $populateResult -Label "destructive-01-populate-before" | Out-Null
    Save-RoutineEvidence -Result $populateResult -OutDir $evidenceDir -Name "destructive-01-populate-before" | Out-Null
    $results += [ordered]@{ routine = "destructive-01-populate-before"; status = (Get-Prop $populateResult "status") }
    Stop-ScrapForAI $scrapCtx
    $scrapCtx = $null

    Info "=== Phase 3: stop v1, REMOVE Note.pinned from the model (forces the destructive path on the leaf 'notes' table) ==="
    Stop-SampleAppProcess $appCtx
    $appCtx = $null

    $modelObj = $originalModelRaw | ConvertFrom-Json
    $noteIndex = -1
    for ($i = 0; $i -lt $modelObj.concepts.Count; $i++) {
        if ($modelObj.concepts[$i].name -eq "Note") { $noteIndex = $i; break }
    }
    if ($noteIndex -lt 0) { Fail "Could not find the Note concept in model.json" }
    $modelObj.concepts[$noteIndex].fields = @($modelObj.concepts[$noteIndex].fields | Where-Object { $_.name -ne "pinned" })
    ($modelObj | ConvertTo-Json -Depth 30) | Set-Content -LiteralPath $modelPath -Encoding UTF8
    $modelMutated = $true
    Ok "Removed Note.pinned from a temporary copy of model.json"

    Info "=== Phase 4: regenerate (v2) and reboot -- this is where the pre-drop snapshot must fire ==="
    & (Join-Path $scriptsRoot "generate-sample-app.ps1") -SampleId $sampleId | Out-Null
    if ($LASTEXITCODE -ne 0 -and $null -ne $LASTEXITCODE) { Fail "v2 generation failed" }
    $appCtx = Start-SampleAppProcess -Label "v2"
    Assert-BootLogContains -AppCtx $appCtx -Needle "pre-drop snapshot written to" -Label "(a) v2 boot wrote a pre-drop snapshot"
    Assert-BootLogContains -AppCtx $appCtx -Needle "NPDev destructive schema recreation" -Label "(a) v2 boot took the destructive path (expected -- a field removal is not additive)"

    Info "=== Phase 5: verify the snapshot files on disk actually captured the populated row ==="
    if (-not (Test-Path -LiteralPath $snapshotRoot)) {
        Fail "Expected a pre-drop snapshot directory under $snapshotRoot but found none"
    }
    $snapshotDirs = @(Get-ChildItem -LiteralPath $snapshotRoot -Directory | Sort-Object Name -Descending)
    if ($snapshotDirs.Count -eq 0) {
        Fail "Expected at least one pre-drop snapshot directory under $snapshotRoot"
    }
    $latestSnapshot = $snapshotDirs[0].FullName
    Ok ("Latest pre-drop snapshot directory: " + $latestSnapshot)

    $summaryPath = Join-Path $latestSnapshot "_summary.json"
    Ensure-File -PathValue $summaryPath -Label "Pre-drop snapshot summary"
    $summaryJson = Get-Content -LiteralPath $summaryPath -Raw | ConvertFrom-Json
    $notesSummary = $summaryJson.notes
    if (-not $notesSummary) { $notesSummary = $summaryJson.NOTES }
    if (-not $notesSummary) { Fail "Expected a 'notes' entry in the snapshot summary: $summaryPath" }
    if ([int]$notesSummary.rowCount -lt 1) {
        Fail ("Expected the notes table's pre-drop row count to be >= 1 (the row populated in Phase 2), got: " + $notesSummary.rowCount)
    }
    Ok ("Snapshot summary recorded notes.rowCount = " + $notesSummary.rowCount)

    $dumpFile = Join-Path $latestSnapshot "notes.jsonl"
    Ensure-File -PathValue $dumpFile -Label "Pre-drop snapshot data dump"
    $dumpContent = Get-Content -LiteralPath $dumpFile -Raw
    if ($dumpContent -notlike ("*" + $sharedVars.noteBody + "*")) {
        Fail ("Expected the pre-drop dump to contain the populated row's body `"" + $sharedVars.noteBody + "`" but it did not. File: " + $dumpFile)
    }
    Ok ("Pre-drop snapshot dump file genuinely contains the populated row's data (body=`"" + $sharedVars.noteBody + "`")")
    $results += [ordered]@{ check = "snapshot-on-disk-matches-populated-row"; status = "passed"; snapshotDir = $latestSnapshot }
}
finally {
    if ($scrapCtx) { Stop-ScrapForAI $scrapCtx }
    if ($appCtx) { Stop-SampleAppProcess $appCtx }
    if ($modelMutated) {
        Set-Content -LiteralPath $modelPath -Value $originalModelRaw -Encoding UTF8 -NoNewline
        Ok "Restored Input/model.json to its original content"
    }
}

if ([string]::IsNullOrWhiteSpace($OutputPath)) {
    $OutputPath = Join-Path $evidenceDir ("schema-destructive-snapshot-demo-" + $runStamp + ".json")
}
$summary = [ordered]@{
    sampleId    = $sampleId
    generatedAt = (Get-Date).ToString("o")
    sharedVars  = $sharedVars
    checks      = [ordered]@{
        destructivePathTaken         = $true
        preDropSnapshotWritten       = $true
        snapshotDataMatchesPopulated = $true
    }
    routines    = $results
}
$summary | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath $OutputPath -Encoding UTF8

Write-Host ""
Ok ("Evidence written to " + $OutputPath)
Ok "Destructive schema recreation demonstrated end to end: populate -> remove a field (forces destructive) -> regenerate -> reboot -> pre-drop snapshot written (row count + full dump) before the DROP TABLE, snapshot data verified to genuinely match the populated row, all confirmed from the boot log and the actual snapshot files on disk."
