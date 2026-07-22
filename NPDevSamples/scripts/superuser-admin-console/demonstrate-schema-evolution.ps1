param(
    [string]$ScrapForAIRoot = "",
    [int]$ScraperPort = 3010,
    [string]$OutputPath = ""
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

# Schema-evolution demonstration for superuser-admin-console -- the Increment-3
# item flagged in the beta1-vision-spine handoff doc as "the most load-bearing
# un-sample-tested logic in the spine": generate v1 -> populate a real row through
# the UI -> add a nullable column to the model -> regenerate -> reboot -> confirm,
# through the rendered UI, that (a) no data was lost, (b) the runtime took the
# SAFE-ADDITIVE path (not a destructive table recreate), and (c) the new column is
# genuinely usable (writable and rendered) on a record that predates it.
#
# This script owns the full app lifecycle itself (start/stop/restart), because it
# must boot the v1 schema, mutate the model, regenerate, and reboot the v2 schema
# all within one run -- unlike the other demonstrate-*.ps1 scripts, which assume an
# already-running app.
#
# The Input/model.json mutation is temporary and ALWAYS restored (even on failure,
# via try/finally) so the sample's checked-in v1 model is never left modified.
# The H2Local database file lives outside the sample tree entirely
# (D:\WorkSpace\NPDev\Build\databases\superuser-admin-console\*.mv.db, resolved by
# UserDatabaseDefinitionLoader from the Input/db.definition.json's databaseName) --
# it is deleted at the start of every run so the demo is self-contained and
# re-runnable from a guaranteed-fresh schema every time, not dependent on whatever
# rows a prior session left behind.

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

function Remove-PersistedDatabase {
    if (Test-Path -LiteralPath $dbDir) {
        Info ("Deleting persisted H2 database so this run starts from a guaranteed-fresh schema: " + $dbDir)
        Remove-Item -LiteralPath $dbDir -Recurse -Force
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

    # Single pre-quoted string, not an array: gradlew.bat runs via cmd.exe, and an
    # array element containing an embedded space (the --args value has one between
    # the two Spring properties) gets split into two separate cmd-line tokens
    # otherwise, which gradle then misreads as an unknown top-level --server.port
    # option instead of part of the single --args value. Matches the exact working
    # invocation in run-sample-app.ps1.
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

function Assert-BootLogNotContains([object]$AppCtx, [string]$Needle, [string]$Label) {
    $text = Get-Content -LiteralPath $AppCtx.OutLog -Raw
    if ($text -like ("*" + $Needle + "*")) {
        Fail ("$Label : boot log unexpectedly contains `"$Needle`" -- this means a DESTRUCTIVE recreate happened. Log: " + $AppCtx.OutLog)
    }
    Ok ("$Label : boot log does not contain `"$Needle`" (no destructive recreate)")
}

$runStamp = (Get-Date).ToString("yyyyMMdd-HHmmss")
$sharedVars = @{
    projectName       = "EVO-PROJECT-$runStamp"
    internalCodeValue = "EVO-CODE-$runStamp"
}

$modelPath = $sample.ModelPath
$originalModelRaw = Get-Content -LiteralPath $modelPath -Raw

$results = @()
$appCtx = $null
$scrapCtx = $null
$modelMutated = $false

try {
    Remove-PersistedDatabase

    Info "=== Phase 1: generate + boot the v1 schema ==="
    & (Join-Path $scriptsRoot "generate-sample-app.ps1") -SampleId $sampleId | Out-Null
    if ($LASTEXITCODE -ne 0 -and $null -ne $LASTEXITCODE) { Fail "v1 generation failed" }
    $appCtx = Start-SampleAppProcess -Label "v1"
    Assert-BootLogContains -AppCtx $appCtx -Needle "no stored schema fingerprint found" -Label "v1 boot (fresh database)"

    Info "=== Phase 2: populate one row through the real UI (the row whose survival proves no data loss) ==="
    Initialize-ScrapForAI -Root $ScrapForAIRoot | Out-Null
    $scrapCtx = Start-ScrapForAI -AppBaseUrl $appBaseUrl -Root $ScrapForAIRoot -Port $ScraperPort `
        -ArtifactDir (Join-Path "D:\WorkSpace\NPDev\Build\scrapforai-artifacts" $sampleId)
    $populateResult = Invoke-ScrapRoutine -Context $scrapCtx `
        -RoutinePath (Join-Path $PSScriptRoot "browser-routines\schema-evolution\01-populate-before.json") `
        -Variables $sharedVars
    Assert-RoutineGreen -Result $populateResult -Label "evo-01-populate-before" | Out-Null
    Save-RoutineEvidence -Result $populateResult -OutDir $evidenceDir -Name "evo-01-populate-before" | Out-Null
    $results += [ordered]@{ routine = "evo-01-populate-before"; status = (Get-Prop $populateResult "status") }
    Stop-ScrapForAI $scrapCtx
    $scrapCtx = $null

    Info "=== Phase 3: stop v1, add a nullable column to the model (Project.internalCode) ==="
    Stop-SampleAppProcess $appCtx
    $appCtx = $null

    $modelObj = $originalModelRaw | ConvertFrom-Json
    $projectIndex = -1
    for ($i = 0; $i -lt $modelObj.concepts.Count; $i++) {
        if ($modelObj.concepts[$i].name -eq "Project") { $projectIndex = $i; break }
    }
    if ($projectIndex -lt 0) { Fail "Could not find the Project concept in model.json" }
    $newField = [ordered]@{
        name = "internalCode"
        type = "string"
        ui   = [ordered]@{ label = "Internal code"; widget = "text" }
    }
    $modelObj.concepts[$projectIndex].fields = @($modelObj.concepts[$projectIndex].fields) + $newField
    ($modelObj | ConvertTo-Json -Depth 30) | Set-Content -LiteralPath $modelPath -Encoding UTF8
    $modelMutated = $true
    Ok "Added nullable Project.internalCode to a temporary copy of model.json"

    Info "=== Phase 4: regenerate (v2) and reboot -- this is where the safe-additive path is exercised ==="
    & (Join-Path $scriptsRoot "generate-sample-app.ps1") -SampleId $sampleId | Out-Null
    if ($LASTEXITCODE -ne 0 -and $null -ne $LASTEXITCODE) { Fail "v2 generation failed" }
    $appCtx = Start-SampleAppProcess -Label "v2"
    Assert-BootLogContains -AppCtx $appCtx -Needle "skipping destructive recreation" -Label "(b) v2 boot took the safe-additive path"
    Assert-BootLogNotContains -AppCtx $appCtx -Needle "NPDev destructive schema recreation" -Label "(b) v2 boot"

    Info "=== Phase 5: browser-verify no data loss + the new column is usable ==="
    $scrapCtx = Start-ScrapForAI -AppBaseUrl $appBaseUrl -Root $ScrapForAIRoot -Port $ScraperPort `
        -ArtifactDir (Join-Path "D:\WorkSpace\NPDev\Build\scrapforai-artifacts" $sampleId)
    $verifyResult = Invoke-ScrapRoutine -Context $scrapCtx `
        -RoutinePath (Join-Path $PSScriptRoot "browser-routines\schema-evolution\02-verify-after.json") `
        -Variables $sharedVars
    Assert-RoutineGreen -Result $verifyResult -Label "evo-02-verify-after" | Out-Null
    Save-RoutineEvidence -Result $verifyResult -OutDir $evidenceDir -Name "evo-02-verify-after" | Out-Null
    $results += [ordered]@{ routine = "evo-02-verify-after"; status = (Get-Prop $verifyResult "status") }
}
finally {
    if ($scrapCtx) { Stop-ScrapForAI $scrapCtx }
    if ($appCtx) { Stop-SampleAppProcess $appCtx }
    if ($modelMutated) {
        Set-Content -LiteralPath $modelPath -Value $originalModelRaw -Encoding UTF8 -NoNewline
        Ok "Restored Input/model.json to its original (v1) content"
    }
}

if ([string]::IsNullOrWhiteSpace($OutputPath)) {
    $OutputPath = Join-Path $evidenceDir ("schema-evolution-demo-" + $runStamp + ".json")
}
$summary = [ordered]@{
    sampleId      = $sampleId
    generatedAt   = (Get-Date).ToString("o")
    sharedVars    = $sharedVars
    checks        = [ordered]@{
        noDataLoss               = $true
        safeAdditiveNotDestructive = $true
        newColumnUsable           = $true
    }
    routines      = $results
}
$summary | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath $OutputPath -Encoding UTF8

Write-Host ""
Ok ("Evidence written to " + $OutputPath)
Ok "Schema-evolution demonstrated end to end: populate -> add nullable column -> regenerate -> reboot -> no data loss + safe-additive path (not destructive) + new column usable, all confirmed through the rendered UI."
