param(
    [string]$BaseUrl = "http://localhost:8110",
    [string]$ScrapForAIRoot = "",
    [int]$ScraperPort = 3020,
    [string]$OutputPath = ""
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

# Browser-level demonstration for 12works/home-inventory: drives the generated
# vanilla-JS business UI in a real (headless) browser via ScrapForAI and asserts on
# the structured evidence -- console errors, page errors, network failures,
# unexpected external requests, screenshots. Exercises Room/Tag/Item create, a
# 3-level nested warranty object (warranty -> terms -> coverage), an N:1 Room
# reference via the search-dialog picker, and an N:M Tag bond via the
# checkbox-list multiselect widget, then reopens the Item to confirm round-trip.

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

# None of Room/Tag/Item declare a server-side search field, so the UI's filter box
# can't isolate this run's rows from earlier manual/automated test data -- wipe all
# three tables first (Item before Room/Tag, since Item references both) so the
# routine's assertions are genuinely true on every rerun.
$existingItems = @(Invoke-RestMethod -Method Get -Uri ($BaseUrl + "/api/items") -Headers @{ "X-API-Key" = "dev-key" } | ForEach-Object { $_ })
foreach ($i in $existingItems) {
    Invoke-RestMethod -Method Delete -Uri ($BaseUrl + "/api/items/" + $i.id) -Headers @{ "X-API-Key" = "dev-key" } | Out-Null
}
if ($existingItems.Count -gt 0) { Info ("Cleaned up " + $existingItems.Count + " pre-existing Item row(s) before running.") }

$existingRooms = @(Invoke-RestMethod -Method Get -Uri ($BaseUrl + "/api/rooms") -Headers @{ "X-API-Key" = "dev-key" } | ForEach-Object { $_ })
foreach ($r in $existingRooms) {
    Invoke-RestMethod -Method Delete -Uri ($BaseUrl + "/api/rooms/" + $r.id) -Headers @{ "X-API-Key" = "dev-key" } | Out-Null
}
if ($existingRooms.Count -gt 0) { Info ("Cleaned up " + $existingRooms.Count + " pre-existing Room row(s) before running.") }

$existingTags = @(Invoke-RestMethod -Method Get -Uri ($BaseUrl + "/api/tags") -Headers @{ "X-API-Key" = "dev-key" } | ForEach-Object { $_ })
foreach ($t in $existingTags) {
    Invoke-RestMethod -Method Delete -Uri ($BaseUrl + "/api/tags/" + $t.id) -Headers @{ "X-API-Key" = "dev-key" } | Out-Null
}
if ($existingTags.Count -gt 0) { Info ("Cleaned up " + $existingTags.Count + " pre-existing Tag row(s) before running.") }

$samplesRoot = Normalize-AbsolutePath (Join-Path $PSScriptRoot "..\..")
$sample = Resolve-NPDevSample -SamplesRoot $samplesRoot -SampleId "12works\home-inventory"
$evidenceDir = Join-Path $sample.RunOutputRoot "browser"
New-Item -ItemType Directory -Force -Path $evidenceDir | Out-Null

$routineDir = Join-Path $PSScriptRoot "browser-routines"
$routines = @(Get-ChildItem -LiteralPath $routineDir -Filter "*.json" | Sort-Object Name)
if ($routines.Count -eq 0) { Fail "No browser routines found in $routineDir" }

$runStamp = (Get-Date).ToString("yyyyMMdd-HHmmss")
$sharedVars = @{
    roomName = "UITEST-ROOM-$runStamp"
    tagName  = "UITEST-TAG-$runStamp"
    itemName = "UITEST-ITEM-$runStamp"
}

Initialize-ScrapForAI -Root $ScrapForAIRoot | Out-Null
$ctx = Start-ScrapForAI -AppBaseUrl $BaseUrl -Root $ScrapForAIRoot -Port $ScraperPort `
    -ArtifactDir (Join-Path "D:\WorkSpace\NPDev\Build\scrapforai-artifacts" "12works-home-inventory")

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
Ok ("Browser verification green across " + $routines.Count + " routine(s) (Room/Tag/Item create; 3-level nested warranty object; N:1 Room reference via picker; N:M Tag bond via multiselect checkboxes; round-trip verified by reopening the Item).")
