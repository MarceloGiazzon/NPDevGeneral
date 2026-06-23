param(
    [string]$BaseUrl = "http://localhost:8094",
    [string]$ScrapForAIRoot = "",
    [int]$ScraperPort = 3010,
    [string]$OutputPath = ""
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

# Browser-level demonstration for superuser-admin-console: drives the generated
# vanilla-JS business UI in a real (headless) browser via ScrapForAI and asserts on
# the structured evidence -- console errors, page errors, network failures,
# unexpected external requests, screenshots. This is the headline Increment-2
# sample from the sample-based-methodology plan: it browser-proves the super-user
# surfaces (composed identity + workspace built-in packs, Store, Box View) living
# alongside ordinary business CRUD (Project/Note) in the same generated app -- the
# permanent, re-runnable proof that the scratch-app-only verifications from the
# beta1-vision-spine roadmap were missing.
#
# Prereq: the generated app must already be running. Generate + start it first:
#   NPDevSamples/scripts/generate-sample-app.ps1 -SampleId superuser-admin-console
#   NPDevSamples/scripts/run-sample-app.ps1 -SampleId superuser-admin-console

. (Join-Path $PSScriptRoot "..\sample-common.ps1")
. (Join-Path $PSScriptRoot "..\browser\scrapforai-harness.ps1")

function Normalize-BaseUrl([string]$Value) { return $Value.TrimEnd("/") }

$BaseUrl = Normalize-BaseUrl $BaseUrl

# 1. Confirm the app is reachable before spinning up the browser runner.
try {
    $health = Invoke-RestMethod -Method Get -Uri ($BaseUrl + "/actuator/health") -TimeoutSec 5
    Info ("App reachable, health: " + $health.status)
} catch {
    throw "The generated app is not reachable at $BaseUrl. Generate + start it first (see header comment). Details: $($_.Exception.Message)"
}

$samplesRoot = Normalize-AbsolutePath (Join-Path $PSScriptRoot "..\..")
$sample = Resolve-NPDevSample -SamplesRoot $samplesRoot -SampleId "superuser-admin-console"
$evidenceDir = Join-Path $sample.RunOutputRoot "browser"
New-Item -ItemType Directory -Force -Path $evidenceDir | Out-Null

$routineDir = Join-Path $PSScriptRoot "browser-routines"
$routines = Get-ChildItem -LiteralPath $routineDir -Filter "*.json" | Sort-Object Name
if ($routines.Count -eq 0) { Fail "No browser routines found in $routineDir" }

# Per-run unique values so reruns never collide on a unique constraint. Routines
# 05 (create) and 06 (link) deliberately share roleName/userName -- 06 looks up the
# rows 05 created via the lookup picker's search filter.
$runStamp = (Get-Date).ToString("yyyyMMdd-HHmmss")
$sharedVars = @{
    projectName        = "UITEST-PROJECT-$runStamp"
    noteBody           = "UI test note $runStamp"
    roleName           = "UITEST-ROLE-$runStamp"
    userName           = "uitest-user-$runStamp"
    menuLabel          = "UITEST-MENU-$runStamp"
    prefKey            = "uitest-pref-$runStamp"
    shippingProjectName = "UITEST-SHIP-$runStamp"
}

Initialize-ScrapForAI -Root $ScrapForAIRoot | Out-Null
$ctx = Start-ScrapForAI -AppBaseUrl $BaseUrl -Root $ScrapForAIRoot -Port $ScraperPort `
    -ArtifactDir (Join-Path "D:\WorkSpace\NPDev\Build\scrapforai-artifacts" "superuser-admin-console")

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
Ok ("Browser verification green across " + $routines.Count + " routines (super-user nav, Store, Box View, business CRUD with reference, identity Role/User create + bond link, workspace Menu create, My Preferences).")
