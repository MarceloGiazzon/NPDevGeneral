param(
    [string]$BaseUrl = "http://localhost:8101",
    [string]$ScrapForAIRoot = "",
    [int]$ScraperPort = 3011,
    [string]$OutputPath = ""
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

# Browser-level demonstration for 12works/reading-list: drives the generated
# vanilla-JS business UI in a real (headless) browser via ScrapForAI and asserts on
# the structured evidence -- console errors, page errors, network failures,
# unexpected external requests, screenshots. Exercises Book CRUD including the
# enum fields (genre, status) and the array-of-objects quotes field, plus the
# concept-scoped ui.frame.mode=minimal override (no side-nav while Book is active).
#
# Prereq: the generated app must already be running. Generate + start it first:
#   NPDevSamples/scripts/generate-sample-app.ps1 -SampleId "12works\reading-list"
#   NPDevSamples/scripts/run-sample-app.ps1 -SampleId "12works\reading-list"

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

# Book declares no server-side search field, so the UI's filter box can't isolate this run's
# row from earlier manual/automated test data -- wipe the table first so the routine's "exactly
# one Book" assertions are genuinely true on every rerun.
$existingBooks = @(Invoke-RestMethod -Method Get -Uri ($BaseUrl + "/api/books") -Headers @{ "X-API-Key" = "dev-key" } | ForEach-Object { $_ })
foreach ($book in $existingBooks) {
    Invoke-RestMethod -Method Delete -Uri ($BaseUrl + "/api/books/" + $book.id) -Headers @{ "X-API-Key" = "dev-key" } | Out-Null
}
if ($existingBooks.Count -gt 0) { Info ("Cleaned up " + $existingBooks.Count + " pre-existing Book row(s) before running.") }

$samplesRoot = Normalize-AbsolutePath (Join-Path $PSScriptRoot "..\..")
$sample = Resolve-NPDevSample -SamplesRoot $samplesRoot -SampleId "12works\reading-list"
$evidenceDir = Join-Path $sample.RunOutputRoot "browser"
New-Item -ItemType Directory -Force -Path $evidenceDir | Out-Null

$routineDir = Join-Path $PSScriptRoot "browser-routines"
$routines = @(Get-ChildItem -LiteralPath $routineDir -Filter "*.json" | Sort-Object Name)
if ($routines.Count -eq 0) { Fail "No browser routines found in $routineDir" }

$runStamp = (Get-Date).ToString("yyyyMMdd-HHmmss")
$sharedVars = @{
    bookTitle = "UITEST-BOOK-$runStamp"
}

Initialize-ScrapForAI -Root $ScrapForAIRoot | Out-Null
$ctx = Start-ScrapForAI -AppBaseUrl $BaseUrl -Root $ScrapForAIRoot -Port $ScraperPort `
    -ArtifactDir (Join-Path "D:\WorkSpace\NPDev\Build\scrapforai-artifacts" "12works-reading-list")

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
Ok ("Browser verification green across " + $routines.Count + " routine(s) (Book CRUD with enum + array-of-objects quotes, ui.frame.mode=minimal).")
