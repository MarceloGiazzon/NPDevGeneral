param(
    [string]$BaseUrl = "http://localhost:8103",
    [string]$ScrapForAIRoot = "",
    [int]$ScraperPort = 3013,
    [string]$OutputPath = ""
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

# Browser-level demonstration for 12works/plant-care-log: drives the generated
# vanilla-JS business UI in a real (headless) browser via ScrapForAI and asserts on
# the structured evidence -- console errors, page errors, network failures,
# unexpected external requests, screenshots. Exercises Plant create with a nested
# object field (careNeeds), then CareLogEntry create through the search-dialog
# lookup picker referencing that Plant, plus a datetime-local field and a checkbox.
#
# Prereq: the generated app must already be running. Generate + start it first:
#   NPDevSamples/scripts/generate-sample-app.ps1 -SampleId "12works\plant-care-log"
#   NPDevSamples/scripts/run-sample-app.ps1 -SampleId "12works\plant-care-log"

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

# Neither Plant nor CareLogEntry declares a server-side search field, so the UI's filter box
# can't isolate this run's rows from earlier manual/automated test data -- wipe both tables
# first so the routine's assertions are genuinely true on every rerun. CareLogEntry must be
# deleted before Plant since it references Plant.
$existingCareLogs = @(Invoke-RestMethod -Method Get -Uri ($BaseUrl + "/api/care_log_entrys") -Headers @{ "X-API-Key" = "dev-key" } | ForEach-Object { $_ })
foreach ($c in $existingCareLogs) {
    Invoke-RestMethod -Method Delete -Uri ($BaseUrl + "/api/care_log_entrys/" + $c.id) -Headers @{ "X-API-Key" = "dev-key" } | Out-Null
}
if ($existingCareLogs.Count -gt 0) { Info ("Cleaned up " + $existingCareLogs.Count + " pre-existing CareLogEntry row(s) before running.") }

$existingPlants = @(Invoke-RestMethod -Method Get -Uri ($BaseUrl + "/api/plants") -Headers @{ "X-API-Key" = "dev-key" } | ForEach-Object { $_ })
foreach ($p in $existingPlants) {
    Invoke-RestMethod -Method Delete -Uri ($BaseUrl + "/api/plants/" + $p.id) -Headers @{ "X-API-Key" = "dev-key" } | Out-Null
}
if ($existingPlants.Count -gt 0) { Info ("Cleaned up " + $existingPlants.Count + " pre-existing Plant row(s) before running.") }

$samplesRoot = Normalize-AbsolutePath (Join-Path $PSScriptRoot "..\..")
$sample = Resolve-NPDevSample -SamplesRoot $samplesRoot -SampleId "12works\plant-care-log"
$evidenceDir = Join-Path $sample.RunOutputRoot "browser"
New-Item -ItemType Directory -Force -Path $evidenceDir | Out-Null

$routineDir = Join-Path $PSScriptRoot "browser-routines"
$routines = @(Get-ChildItem -LiteralPath $routineDir -Filter "*.json" | Sort-Object Name)
if ($routines.Count -eq 0) { Fail "No browser routines found in $routineDir" }

$runStamp = (Get-Date).ToString("yyyyMMdd-HHmmss")
$sharedVars = @{
    plantName = "UITEST-PLANT-$runStamp"
}

Initialize-ScrapForAI -Root $ScrapForAIRoot | Out-Null
$ctx = Start-ScrapForAI -AppBaseUrl $BaseUrl -Root $ScrapForAIRoot -Port $ScraperPort `
    -ArtifactDir (Join-Path "D:\WorkSpace\NPDev\Build\scrapforai-artifacts" "12works-plant-care-log")

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
Ok ("Browser verification green across " + $routines.Count + " routine(s) (Plant nested object careNeeds + CareLogEntry via search-dialog picker, datetime-local, checkbox).")
