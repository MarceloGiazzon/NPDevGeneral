param(
    [string]$BaseUrl = "http://localhost:8102",
    [string]$ScrapForAIRoot = "",
    [int]$ScraperPort = 3012,
    [string]$OutputPath = ""
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

# Browser-level demonstration for 12works/gift-idea-tracker: drives the generated
# vanilla-JS business UI in a real (headless) browser via ScrapForAI and asserts on
# the structured evidence -- console errors, page errors, network failures,
# unexpected external requests, screenshots. Exercises GiftIdea create with its
# personRef reference field rendered as a real <select> (field.widget=select
# override, not the default lookup picker), plus the occasion/status enum fields
# and the integer budget field.
#
# Prereq: the generated app must already be running. Generate + start it first:
#   NPDevSamples/scripts/generate-sample-app.ps1 -SampleId "12works\gift-idea-tracker"
#   NPDevSamples/scripts/run-sample-app.ps1 -SampleId "12works\gift-idea-tracker"

. (Join-Path $PSScriptRoot "..\..\scripts\sample-common.ps1")
. (Join-Path $PSScriptRoot "..\..\scripts\browser\scrapforai-harness.ps1")

function Normalize-BaseUrl([string]$Value) { return $Value.TrimEnd("/") }

$BaseUrl = Normalize-BaseUrl $BaseUrl

try {
    $health = Invoke-RestMethod -Method Get -Uri ($BaseUrl + "/actuator/health") -TimeoutSec 5
    Info ("App reachable, health: " + $health.status)
} catch {
    throw "The generated app is not reachable at $BaseUrl. Generate + start it first (see header comment). Details: $($_.Exception.Message)"
}

$samplesRoot = Normalize-AbsolutePath (Join-Path $PSScriptRoot "..\..")
$sample = Resolve-NPDevSample -SamplesRoot $samplesRoot -SampleId "12works\gift-idea-tracker"

# R7 Stage D: resolve whatever key actually authenticates against THIS running app right now,
# rather than hardcoding "dev-key" (Stage C's per-app random key replaces it once any of the
# platform's three shipped launch pipelines is used to start this app).
$liveApiKey = Get-NpdevLiveApiKey -AppRoot $sample.AppRoot
$liveCreds = @{ apiKey = $liveApiKey }

# Neither GiftIdea nor Person declares a server-side search field, so the UI's filter box
# can't isolate this run's rows from earlier manual/automated test data -- wipe both tables
# first so the routine's assertions are genuinely true on every rerun.
$existingGiftIdeas = @(Invoke-RestMethod -Method Get -Uri ($BaseUrl + "/api/gift_ideas") -Headers @{ "X-API-Key" = $liveApiKey } | ForEach-Object { $_ })
foreach ($g in $existingGiftIdeas) {
    Invoke-RestMethod -Method Delete -Uri ($BaseUrl + "/api/gift_ideas/" + $g.id) -Headers @{ "X-API-Key" = $liveApiKey } | Out-Null
}
if ($existingGiftIdeas.Count -gt 0) { Info ("Cleaned up " + $existingGiftIdeas.Count + " pre-existing GiftIdea row(s) before running.") }

$existingPersons = @(Invoke-RestMethod -Method Get -Uri ($BaseUrl + "/api/persons") -Headers @{ "X-API-Key" = $liveApiKey } | ForEach-Object { $_ })
foreach ($p in $existingPersons) {
    Invoke-RestMethod -Method Delete -Uri ($BaseUrl + "/api/persons/" + $p.id) -Headers @{ "X-API-Key" = $liveApiKey } | Out-Null
}
if ($existingPersons.Count -gt 0) { Info ("Cleaned up " + $existingPersons.Count + " pre-existing Person row(s) before running.") }

$runStamp = (Get-Date).ToString("yyyyMMdd-HHmmss")
$personName = "UITEST-PERSON-$runStamp"
$person = Invoke-RestMethod -Method Post -Uri ($BaseUrl + "/api/persons") -Headers @{ "X-API-Key" = $liveApiKey } -ContentType "application/json" `
    -Body (@{ name = $personName; relationship = "Friend" } | ConvertTo-Json)
Info ("Created test Person '" + $personName + "' (id " + $person.id + ") for the select-widget reference field.")

$evidenceDir = Join-Path $sample.RunOutputRoot "browser"
New-Item -ItemType Directory -Force -Path $evidenceDir | Out-Null

$routineDir = Join-Path $PSScriptRoot "browser-routines"
$routines = @(Get-ChildItem -LiteralPath $routineDir -Filter "*.json" | Sort-Object Name)
if ($routines.Count -eq 0) { Fail "No browser routines found in $routineDir" }

$sharedVars = @{
    personId   = [string]$person.id
    personName = $personName
    ideaText   = "UITEST-IDEA-$runStamp"
}

Initialize-ScrapForAI -Root $ScrapForAIRoot | Out-Null
$ctx = Start-ScrapForAI -AppBaseUrl $BaseUrl -Root $ScrapForAIRoot -Port $ScraperPort `
    -ArtifactDir (Join-Path "D:\WorkSpace\NPDev\Build\scrapforai-artifacts" "12works-gift-idea-tracker")

$results = @()
try {
    foreach ($routine in $routines) {
        $name = [System.IO.Path]::GetFileNameWithoutExtension($routine.Name)
        Info ("=== Routine: " + $name + " ===")
        $result = Invoke-ScrapRoutine -Context $ctx -RoutinePath $routine.FullName -Variables $sharedVars -Credentials $liveCreds
        # R7 Stage D: see the identical note in superuser-admin-console's demonstrate-browser.ps1 --
        # the routine's own pre-fill page load is now genuinely unauthenticated (no more guessed
        # devKeyHint auto-fill), which logs an expected one-time 401 burst before the explicit
        # credential fill+reload takes effect.
        Assert-RoutineGreen -Result $result -Label $name -AllowConsoleErrorSubstrings @("responded with a status of 401") | Out-Null
        Save-RoutineEvidence -Result $result -OutDir $evidenceDir -Name $name | Out-Null
        $results += [ordered]@{
            routine     = $name
            status      = (Get-Prop $result "status")
            steps       = @($result.steps).Count
            consoleErrs = @($result.evidence.consoleErrors).Count
            pageErrs    = @($result.evidence.pageErrors).Count
            netFails    = @($result.evidence.networkFailures).Count
        }
    }
} finally {
    Stop-ScrapForAI $ctx
}

if ([string]::IsNullOrWhiteSpace($OutputPath)) {
    $OutputPath = Join-Path $evidenceDir ("browser-demo-" + $runStamp + ".json")
}
$summary = [ordered]@{
    baseUrl       = $BaseUrl
    generatedAt   = (Get-Date).ToString("o")
    scraperBase   = $ctx.BaseUrl
    artifactDir   = $ctx.ArtifactDir
    sharedVars    = $sharedVars
    routineCount  = $routines.Count
    routines      = $results
    allGreen      = $true
}
$summary | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath $OutputPath -Encoding UTF8

Write-Host ""
Ok ("Evidence written to " + $OutputPath)
Ok ("Screenshots/traces under " + $ctx.ArtifactDir)
Ok ("Browser verification green across " + $routines.Count + " routine(s) (GiftIdea create with select-widget personRef reference, enum fields, integer budget).")
