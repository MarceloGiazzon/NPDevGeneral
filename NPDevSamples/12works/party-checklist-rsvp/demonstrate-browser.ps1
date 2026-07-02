param(
    [string]$BaseUrl = "http://localhost:8111",
    [string]$ScrapForAIRoot = "",
    [int]$ScraperPort = 3021,
    [string]$OutputPath = ""
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

# Browser-level demonstration for 12works/party-checklist-rsvp: drives the generated
# vanilla-JS business UI in a real (headless) browser via ScrapForAI and asserts on
# the structured evidence -- console errors, page errors, network failures,
# unexpected external requests, screenshots. Exercises Event create, then Guest and
# Task create (both referencing the same Event via the search-dialog picker), then
# renews the Guest's RSVP via the UpdateRsvp update Flow (which emits a custom
# GuestRsvpUpdated event).

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

# None of Event/Guest/Task declare a server-side search field, so the UI's filter box
# can't isolate this run's rows from earlier manual/automated test data -- wipe all
# three tables first (Guest/Task before Event, since both reference it) so the
# routine's assertions are genuinely true on every rerun.
$existingGuests = @(Invoke-RestMethod -Method Get -Uri ($BaseUrl + "/api/guests") -Headers @{ "X-API-Key" = "dev-key" } | ForEach-Object { $_ })
foreach ($g in $existingGuests) {
    Invoke-RestMethod -Method Delete -Uri ($BaseUrl + "/api/guests/" + $g.id) -Headers @{ "X-API-Key" = "dev-key" } | Out-Null
}
if ($existingGuests.Count -gt 0) { Info ("Cleaned up " + $existingGuests.Count + " pre-existing Guest row(s) before running.") }

$existingTasks = @(Invoke-RestMethod -Method Get -Uri ($BaseUrl + "/api/tasks") -Headers @{ "X-API-Key" = "dev-key" } | ForEach-Object { $_ })
foreach ($t in $existingTasks) {
    Invoke-RestMethod -Method Delete -Uri ($BaseUrl + "/api/tasks/" + $t.id) -Headers @{ "X-API-Key" = "dev-key" } | Out-Null
}
if ($existingTasks.Count -gt 0) { Info ("Cleaned up " + $existingTasks.Count + " pre-existing Task row(s) before running.") }

$existingEvents = @(Invoke-RestMethod -Method Get -Uri ($BaseUrl + "/api/party_events") -Headers @{ "X-API-Key" = "dev-key" } | ForEach-Object { $_ })
foreach ($e in $existingEvents) {
    Invoke-RestMethod -Method Delete -Uri ($BaseUrl + "/api/party_events/" + $e.id) -Headers @{ "X-API-Key" = "dev-key" } | Out-Null
}
if ($existingEvents.Count -gt 0) { Info ("Cleaned up " + $existingEvents.Count + " pre-existing Event row(s) before running.") }

$samplesRoot = Normalize-AbsolutePath (Join-Path $PSScriptRoot "..\..")
$sample = Resolve-NPDevSample -SamplesRoot $samplesRoot -SampleId "12works\party-checklist-rsvp"
$evidenceDir = Join-Path $sample.RunOutputRoot "browser"
New-Item -ItemType Directory -Force -Path $evidenceDir | Out-Null

$routineDir = Join-Path $PSScriptRoot "browser-routines"
$routines = @(Get-ChildItem -LiteralPath $routineDir -Filter "*.json" | Sort-Object Name)
if ($routines.Count -eq 0) { Fail "No browser routines found in $routineDir" }

$runStamp = (Get-Date).ToString("yyyyMMdd-HHmmss")
$sharedVars = @{
    eventName = "UITEST-EVENT-$runStamp"
    guestName = "UITEST-GUEST-$runStamp"
    taskTitle = "UITEST-TASK-$runStamp"
}

Initialize-ScrapForAI -Root $ScrapForAIRoot | Out-Null
$ctx = Start-ScrapForAI -AppBaseUrl $BaseUrl -Root $ScrapForAIRoot -Port $ScraperPort `
    -ArtifactDir (Join-Path "D:\WorkSpace\NPDev\Build\scrapforai-artifacts" "12works-party-checklist-rsvp")

$results = @()
try {
    foreach ($routine in $routines) {
        $name = [System.IO.Path]::GetFileNameWithoutExtension($routine.Name)
        Info ("=== Routine: " + $name + " ===")
        $result = Invoke-ScrapRoutine -Context $ctx -RoutinePath $routine.FullName -Variables $sharedVars
        Assert-RoutineGreen -Result $result -Label $name | Out-Null
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
Ok ("Browser verification green across " + $routines.Count + " routine(s) (Event create; Guest and Task create both referencing the same Event via the search-dialog picker; RSVP status updated via the UpdateRsvp update Flow, which emits a custom GuestRsvpUpdated event).")
