param(
    [string]$BaseUrl = "http://localhost:8093",
    [string]$ScrapForAIRoot = "",
    [int]$ScraperPort = 3010,
    [string]$OutputPath = ""
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

# Browser-level demonstration for restaurant-saas-multitenant: drives the generated
# vanilla-JS business UI in a real (headless) browser via ScrapForAI and asserts on
# the structured evidence -- console errors, page errors, network failures,
# unexpected external requests, screenshots. This is the complement to
# demonstrate-platform-tenancy.ps1 (which proves the HTTP/backend path): it proves
# the rendered super-user UI surfaces (nav, Store, Box View, business grid) and a
# create-through-the-form write path that the HTTP scripts structurally cannot reach.
#
# Prereq: the generated app must already be running. Start it first with:
#   run-generated-app.ps1   (or run-sample-app.ps1 -SampleId restaurant-saas-multitenant)

. (Join-Path $PSScriptRoot "..\sample-common.ps1")
. (Join-Path $PSScriptRoot "..\browser\scrapforai-harness.ps1")

function Normalize-BaseUrl([string]$Value) { return $Value.TrimEnd("/") }

$BaseUrl = Normalize-BaseUrl $BaseUrl

# 1. Confirm the app is reachable before spinning up the browser runner.
try {
    $health = Invoke-RestMethod -Method Get -Uri ($BaseUrl + "/actuator/health") -TimeoutSec 5
    Info ("App reachable, health: " + $health.status)
} catch {
    throw "The generated app is not reachable at $BaseUrl. Start it with run-generated-app.ps1 first. Details: $($_.Exception.Message)"
}

$samplesRoot = Normalize-AbsolutePath (Join-Path $PSScriptRoot "..\..")
$sample = Resolve-NPDevSample -SamplesRoot $samplesRoot -SampleId "restaurant-saas-multitenant"
$evidenceDir = Join-Path $sample.RunOutputRoot "browser"
New-Item -ItemType Directory -Force -Path $evidenceDir | Out-Null

# R7 Stage D: routines fill #apiKey via valueFromCredential rather than a hardcoded literal --
# resolve whatever key actually authenticates against THIS running app right now.
$liveCreds = @{ apiKey = (Get-NpdevLiveApiKey -AppRoot $sample.AppRoot) }

$routineDir = Join-Path $PSScriptRoot "browser-routines"
$routines = Get-ChildItem -LiteralPath $routineDir -Filter "*.json" | Sort-Object Name
if ($routines.Count -eq 0) { Fail "No browser routines found in $routineDir" }

# A per-run unique code so the create-through-UI routine never collides on the
# Tenant.code unique constraint across reruns.
$runStamp   = (Get-Date).ToString("yyyyMMdd-HHmmss")
$uniqueCode = "UITEST-" + $runStamp

Initialize-ScrapForAI -Root $ScrapForAIRoot | Out-Null
$ctx = Start-ScrapForAI -AppBaseUrl $BaseUrl -Root $ScrapForAIRoot -Port $ScraperPort `
    -ArtifactDir (Join-Path "D:\WorkSpace\NPDev\Build\scrapforai-artifacts" "restaurant-saas-multitenant")

$results = @()
try {
    foreach ($routine in $routines) {
        $name = [System.IO.Path]::GetFileNameWithoutExtension($routine.Name)
        Info ("=== Routine: " + $name + " ===")
        $vars = @{ uniqueCode = $uniqueCode }
        $result = Invoke-ScrapRoutine -Context $ctx -RoutinePath $routine.FullName -Variables $vars -Credentials $liveCreds
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
    uniqueCode    = $uniqueCode
    routineCount  = $routines.Count
    routines      = $results
    allGreen      = $true
}
$summary | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath $OutputPath -Encoding UTF8

Write-Host ""
Ok ("Evidence written to " + $OutputPath)
Ok ("Screenshots/traces under " + $ctx.ArtifactDir)
Ok ("Browser verification green across " + $routines.Count + " routines (nav, Store, Box View, business grid, create-through-UI).")
