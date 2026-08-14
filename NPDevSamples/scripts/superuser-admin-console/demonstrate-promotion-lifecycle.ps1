param(
    [string]$ScrapForAIRoot = "",
    [int]$ScraperPort = 3010,
    [string]$OutputPath = ""
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

# S0-S8 promotion-lifecycle browser demonstration for superuser-admin-console
# (Increment 4 of the sample-based browser-verification methodology). Drives the
# new "Promotion" admin panel (added to business-ui-app.mustache, mirroring how
# Store/Box View were added) through the real rendered UI: stage-skip rejection,
# missing-evidence rejection, the full successful S1->S8 advance chain, the
# terminal-stage no-further-advance check, and the mixed accepted/rejected audit
# history table.
#
# currentStage is computed server-side from the full ACCEPTED-event history (see
# PromotionStateService.currentStage), not stored as a separate mutable flag, so
# this demo deletes the persisted database first -- exactly like
# demonstrate-schema-evolution.ps1 -- guaranteeing every run starts deterministically
# at S0_IDEA. This script therefore owns the full app lifecycle itself (generate,
# boot, run both routines in sequence, stop) rather than assuming an already-running
# app, because the two routines must execute in order against the SAME fresh
# database (state lives there, not in the browser, so each routine reloads/
# re-authenticates independently but the server-side stage carries over).
#
# The missing_role rejection (S7/S8 require ADMIN) IS covered, but not through a
# browser session: the trial-mode "api-dev" dev principal always carries ADMIN, so
# there's no way to get a lesser-privileged UI session just by typing a different
# string into #apiKey. Instead, between the "evidence-backed" and "release" browser
# routines, this script issues a REAL non-admin runtime credential via the existing
# T4/T5 admin HTTP API (POST /api/admin/tenants, POST /api/admin/credentials with
# roles=["USER"], no ADMIN) and attempts the S7 advance with it directly over HTTP.
# POST /api/admin/promotion/advance has no blanket admin gate of its own (only GET
# does) -- PromotionStateService's own ADMIN check is what rejects it, recording a
# real missing_role audit event. The next browser routine then asserts that
# rejection is visible in the real rendered history table, so the HTTP-issued
# credential and the browser-rendered proof are both exercised, end to end.

. (Join-Path $PSScriptRoot "..\sample-common.ps1")
. (Join-Path $PSScriptRoot "..\browser\scrapforai-harness.ps1")

$sampleId = "superuser-admin-console"
$port = 8094
$appBaseUrl = "http://localhost:$port"

$samplesRoot = Normalize-AbsolutePath (Join-Path $PSScriptRoot "..\..")
$scriptsRoot = Join-Path $samplesRoot "scripts"
$sample = Resolve-NPDevSample -SamplesRoot $samplesRoot -SampleId $sampleId
$evidenceDir = Join-Path $sample.RunOutputRoot "promotion"
New-Item -ItemType Directory -Force -Path $evidenceDir | Out-Null

$bootLogRoot = Join-Path "D:\WorkSpace\NPDev\Build\schema-evolution-logs" $sampleId
New-Item -ItemType Directory -Force -Path $bootLogRoot | Out-Null

$workspaceRoot = Normalize-AbsolutePath (Join-Path $samplesRoot "..\..")
$dbDir = Join-Path (Join-Path (Join-Path $workspaceRoot "Build") "databases") $sampleId

function Remove-PersistedDatabase {
    if (Test-Path -LiteralPath $dbDir) {
        Info ("Deleting persisted H2 database so this run starts deterministically at S0_IDEA: " + $dbDir)
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

    # Single pre-quoted string, not an array -- see the note in
    # demonstrate-schema-evolution.ps1 (a real bug found while authoring that
    # script: an array element with an embedded space gets mis-split by cmd.exe).
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

function Invoke-AdminJson([string]$Method, [string]$Route, [hashtable]$Body = $null) {
    $headers = @{ "X-Api-Key" = $script:liveApiKey }
    $uri = $appBaseUrl + $Route
    try {
        if ($null -ne $Body) {
            return Invoke-RestMethod -Method $Method -Uri $uri -Headers $headers -ContentType "application/json" -Body ($Body | ConvertTo-Json -Depth 10)
        }
        return Invoke-RestMethod -Method $Method -Uri $uri -Headers $headers
    } catch {
        $statusCode = 0
        if ($_.Exception.Response) { $statusCode = [int]$_.Exception.Response.StatusCode }
        return [pscustomobject]@{ NPDevError = $true; StatusCode = $statusCode; Message = $_.Exception.Message }
    }
}

# Issues a real, runtime-issued, non-admin (roles=["USER"], no ADMIN) credential via
# the existing T4/T5 admin API, then attempts an S7 advance with it directly over
# HTTP. PromotionController's POST /advance has no blanket admin gate (only GET
# does) -- it's PromotionStateService's own ADMIN check that produces the
# missing_role rejection, recorded as a real audit event regardless of HTTP status.
function Invoke-NonAdminMissingRoleCheck {
    Info "=== Closing the missing_role gap: issuing a non-admin credential and attempting S7 over HTTP ==="
    $tenant = Invoke-AdminJson -Method Post -Route "/api/admin/tenants" -Body @{ tenantId = "promo-demo"; displayName = "Promotion Demo Viewer" }
    if ($tenant -is [pscustomobject] -and $tenant.PSObject.Properties.Name -contains "NPDevError") {
        Info ("Platform tenant likely already exists from a prior run, re-enabling: " + $tenant.Message)
        Invoke-AdminJson -Method Post -Route "/api/admin/tenants/promo-demo/enable" | Out-Null
    }
    $credential = Invoke-AdminJson -Method Post -Route "/api/admin/credentials" -Body @{ tenantId = "promo-demo"; actorId = "viewer"; roles = @("USER") }
    if ($credential -is [pscustomobject] -and $credential.PSObject.Properties.Name -contains "NPDevError") {
        Fail ("Failed to issue the non-admin credential: " + $credential.Message)
    }
    $nonAdminKey = [string]$credential.apiKey
    Ok "Issued a non-admin (roles=[USER]) credential for the missing_role check"

    $headers = @{ "X-Api-Key" = $nonAdminKey }
    $body = @{ stage = "S7_RELEASE_APPROVED"; evidence = "Attempted by a non-admin viewer." } | ConvertTo-Json -Depth 5
    $rejected = $false
    try {
        Invoke-RestMethod -Method Post -Uri ($appBaseUrl + "/api/admin/promotion/advance") -Headers $headers -ContentType "application/json" -Body $body | Out-Null
    } catch {
        $statusCode = if ($_.Exception.Response) { [int]$_.Exception.Response.StatusCode } else { 0 }
        if ($statusCode -eq 400) { $rejected = $true }
        else { Fail ("Non-admin S7 attempt returned unexpected status $statusCode (expected 400)") }
    }
    if (-not $rejected) { Fail "Non-admin S7 attempt unexpectedly succeeded -- the ADMIN-role gate did not fire" }
    Ok "Non-admin S7 attempt correctly rejected with 400 (missing_role)"

    $state = Invoke-AdminJson -Method Get -Route "/api/admin/promotion"
    $missingRoleEvent = @($state.history) | Where-Object { $_.reasonCode -like "missing_role*" -and $_.actorId -eq "viewer" } | Select-Object -Last 1
    if (-not $missingRoleEvent) { Fail "Expected a missing_role REJECTED event for actor 'viewer' in the audit history but found none" }
    if ($missingRoleEvent.outcome -ne "REJECTED") { Fail "Expected the missing_role event's outcome to be REJECTED" }
    Ok ("Audit history confirms the REJECTED missing_role event: " + ($missingRoleEvent | ConvertTo-Json -Compress))
}

$results = @()
$appCtx = $null
$scrapCtx = $null

try {
    Remove-PersistedDatabase

    Info "=== Generate + boot ==="
    & (Join-Path $scriptsRoot "generate-sample-app.ps1") -SampleId $sampleId | Out-Null
    $appCtx = Start-SampleAppProcess -Label "promotion"

    # R7 Stage D: this script boots the app itself (raw gradlew, no Ensure-NpdevApiKey call), so
    # resolve whatever key actually authenticates against THIS run rather than hardcoding "api-dev".
    $liveApiKey = Get-NpdevLiveApiKey -AppRoot $sample.AppRoot
    $liveCreds = @{ apiKey = $liveApiKey }

    Initialize-ScrapForAI -Root $ScrapForAIRoot | Out-Null
    $scrapCtx = Start-ScrapForAI -AppBaseUrl $appBaseUrl -Root $ScrapForAIRoot -Port $ScraperPort `
        -ArtifactDir (Join-Path "D:\WorkSpace\NPDev\Build\scrapforai-artifacts" $sampleId)

    # Explicit order, not a generic glob: the missing_role HTTP check must run between
    # "02-evidence-backed" (ends at S6) and "03-release-and-terminal" (assumes S6,
    # attempts S7 as ADMIN) -- the rejected non-admin attempt in between must not move
    # the stage, and 03's history-table assertion expects to see it already recorded.
    $routineDir = Join-Path $PSScriptRoot "browser-routines\promotion"
    function Run-PromotionRoutine([string]$FileName) {
        $path = Join-Path $routineDir $FileName
        $name = [System.IO.Path]::GetFileNameWithoutExtension($FileName)
        Info ("=== Routine: " + $name + " ===")
        $result = Invoke-ScrapRoutine -Context $scrapCtx -RoutinePath $path -Credentials $liveCreds
        # These routines deliberately trigger rejected (400) advance attempts to prove
        # the gate rules -- Chrome logs the failed fetch to the console even though the
        # app code catches and handles it via setStatus(). See the parameter doc on
        # Assert-RoutineGreen for why this is safe to allow only here. R7 Stage D adds 401: the
        # routine's own pre-fill page load is now genuinely unauthenticated (no more guessed
        # devKeyHint auto-fill), logging an expected one-time 401 burst before the explicit
        # credential fill+reload takes effect.
        Assert-RoutineGreen -Result $result -Label $name -AllowConsoleErrorSubstrings @("responded with a status of 400", "responded with a status of 401") | Out-Null
        Save-RoutineEvidence -Result $result -OutDir $evidenceDir -Name $name | Out-Null
        $script:results += [ordered]@{ routine = $name; status = (Get-Prop $result "status") }
    }

    Run-PromotionRoutine "01-rejections-and-early-stages.json"
    Run-PromotionRoutine "02-evidence-backed.json"
    Invoke-NonAdminMissingRoleCheck
    Run-PromotionRoutine "03-release-and-terminal.json"
}
finally {
    if ($scrapCtx) { Stop-ScrapForAI $scrapCtx }
    if ($appCtx) { Stop-SampleAppProcess $appCtx }
}

if ([string]::IsNullOrWhiteSpace($OutputPath)) {
    $runStamp = (Get-Date).ToString("yyyyMMdd-HHmmss")
    $OutputPath = Join-Path $evidenceDir ("promotion-lifecycle-demo-" + $runStamp + ".json")
}
$summary = [ordered]@{
    sampleId    = $sampleId
    generatedAt = (Get-Date).ToString("o")
    checks      = [ordered]@{
        stageSkipRejected       = $true
        missingEvidenceRejected = $true
        missingRoleRejected     = $true
        fullChainToS8Accepted   = $true
        terminalStageEnforced   = $true
        mixedHistoryRendered    = $true
    }
    routines    = $results
}
$summary | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath $OutputPath -Encoding UTF8

Write-Host ""
Ok ("Evidence written to " + $OutputPath)
Ok "S0-S8 promotion lifecycle demonstrated end to end through the rendered Promotion admin panel: stage-skip rejection, missing-evidence rejection, a real non-admin credential's missing-role rejection, the full S1->S8 advance chain, terminal-stage enforcement, and the mixed accepted/rejected audit history."
