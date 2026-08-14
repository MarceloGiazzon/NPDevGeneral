# ScrapForAI browser-verification harness (shared library, dot-source this file).
#
# Purpose: drive a locally-booted generated NPDev app's real vanilla-JS UI in a
# headless browser via Marcelo's ScrapForAI exploration runner
# (D:\WorkSpace\ScrapForAILegacy), and assert on the structured evidence it
# returns -- console errors, page errors, network failures, unexpected external
# requests, screenshots. This catches the class of client-side rendering bugs
# the existing HTTP-only demonstrate/verify scripts structurally cannot.
#
# This is the de-risking harness from the beta1 sample-based-methodology plan;
# it is reused by every sample's demonstrate-browser.ps1.
#
# Key facts baked in (validated live, see the plan + handoff doc):
#  - Talk to the scraper over 127.0.0.1, NOT localhost (the server binds IPv4;
#    Windows resolves localhost to ::1 first, so a localhost poll never connects).
#  - The scraper enforces an SSRF allowlist: localhost targets are BLOCKED unless
#    the exact origin is in ALLOWED_TARGET_ORIGINS. The harness sets it to the
#    booted app's origin, or every routine 400s at its first `goto`.
#  - dotenv does NOT override process env, and we launch from a cwd without a
#    .env, so the env we pass fully controls the server config -- we never touch
#    Marcelo's checked-in ScrapForAILegacy\.env.
#  - The generated business UI auto-authenticates from manifest devKeyHint
#    ("api-dev" -> super-user); routines also fill #apiKey explicitly + reload
#    for determinism, so no credentials block is needed in a routine.

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

# Reuse the sample helpers (Info/Ok/Fail/Ensure-*). Idempotent to re-dot-source.
. (Join-Path (Split-Path -Parent $PSScriptRoot) "sample-common.ps1")

# MONITOR_PLAN D9 / C1. This line used to be
#
#     $script:ScrapForAIDefaultRoot = "D:\WorkSpace\ScrapForAILegacy"
#
# -- an author's drive letter, used whenever no override was given, in the harness the Monitor is
# built on. That is the family PORT-1 removed from six emitters on 2026-08-10 and REG-144 removed
# from eleven root resolvers before that, and the obvious way to implement "detect the engine" was
# to copy this constant into the Manager.
#
# There is no default any more. The engine is DISCOVERED, by the one implementation that owns the
# question: `npdev monitor engine --json`, which probes the running service first, then a declared
# root, then candidates derived from this machine's own layout -- and reports "not found" rather than
# falling back to a path that exists on one machine.
$script:ScrapForAIDefaultKey  = "npdev-scrapforai-localkey-0001"   # >= 16 chars
$script:ScrapForAIDefaultPort = 3010

function Get-NpdevCliPath {
    # $PSScriptRoot arithmetic, exact and with no walk: NPDevSamples/scripts/browser -> repo root.
    $repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..\..")).Path
    return (Join-Path $repoRoot "NPDevCli\npdev_cli.py")
}

function Find-NpdevScrapEngine {
    <#
        Asks the CLI. One implementation of "where is the engine", shared by this harness, the
        Manager and a terminal user -- a second opinion here is the two-greens drift R10 forbids.
    #>
    $cli = Get-NpdevCliPath
    if (-not (Test-Path -LiteralPath $cli)) { return $null }
    try {
        $json = & python $cli monitor engine --json 2>$null
        if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($json)) { return $null }
        return ($json | ConvertFrom-Json)
    } catch { return $null }
}

# StrictMode-safe optional-property read. The scraper omits optional result fields
# (failedStepIndex on success, failureText/status on some network rows), and
# Set-StrictMode -Latest throws on a missing property -- so always read optionals
# through this helper.
function Get-Prop([object]$Obj, [string]$Name, $Default = $null) {
    if ($null -eq $Obj) { return $Default }
    $p = $Obj.PSObject.Properties[$Name]
    if ($null -eq $p) { return $Default }
    return $p.Value
}

function Get-ScrapForAIRoot([string]$Override = "") {
    $root = $Override
    if ([string]::IsNullOrWhiteSpace($root)) { $root = $env:SCRAPFORAI_ROOT }
    if ([string]::IsNullOrWhiteSpace($root)) {
        # Discovered, never defaulted. A "not found" here is a fixable sentence; a drive letter that
        # exists on one machine is a failure somebody else cannot diagnose.
        $found = Find-NpdevScrapEngine
        if ($null -ne $found -and $found.root) { $root = [string]$found.root }
    }
    if ([string]::IsNullOrWhiteSpace($root)) {
        Fail ("Could not find the ScrapForAI engine on this machine. Run ``npdev monitor engine --json`` " +
              "to see what was tried, set `$env:SCRAPFORAI_ROOT, or pass -Root explicitly.")
    }
    $root = Normalize-AbsolutePath $root
    Ensure-Directory -PathValue $root -Label "ScrapForAI root"
    Ensure-File -PathValue (Join-Path $root "src\server.ts") -Label "ScrapForAI server.ts"
    return $root
}

function Test-ScrapForAIReady([string]$Root) {
    $tsx = Join-Path $Root "node_modules\.bin\tsx.cmd"
    return (Test-Path -LiteralPath $tsx)
}

function Initialize-ScrapForAI {
    param(
        [string]$Root = "",
        [switch]$InstallBrowsers   # only needed on a fresh machine; chromium is usually already installed
    )
    $Root = Get-ScrapForAIRoot $Root
    if (-not (Test-ScrapForAIReady $Root)) {
        Info "ScrapForAI node_modules missing -- running npm install (one-time)"
        Push-Location $Root
        try {
            & npm install
            if ($LASTEXITCODE -ne 0) { Fail "npm install failed for ScrapForAI ($Root)" }
        } finally { Pop-Location }
    }
    if ($InstallBrowsers) {
        Info "Ensuring Playwright chromium is installed"
        Push-Location $Root
        try {
            & npx playwright install chromium
            if ($LASTEXITCODE -ne 0) { Fail "playwright install chromium failed" }
        } finally { Pop-Location }
    }
    Ok ("ScrapForAI ready at " + $Root)
    return $Root
}

# Starts the ScrapForAI server on 127.0.0.1:<Port>, allowlisting the booted
# app's origin so routines can navigate to it. Returns a context object passed
# to the other functions. Always pair with Stop-ScrapForAI in a finally block.
function Start-ScrapForAI {
    param(
        [Parameter(Mandatory = $true)][string]$AppBaseUrl,   # e.g. http://localhost:8093
        [string]$Root = "",
        [int]$Port = 0,
        [string]$ApiKey = "",
        [string]$ArtifactDir = "",
        [string[]]$ExtraAllowedOrigins = @(),
        [switch]$AllowEvaluate,
        [int]$ReadyTimeoutSec = 30
    )
    $Root = Get-ScrapForAIRoot $Root
    if ($Port -le 0) { $Port = $script:ScrapForAIDefaultPort }
    if ([string]::IsNullOrWhiteSpace($ApiKey)) { $ApiKey = $script:ScrapForAIDefaultKey }
    if ([string]::IsNullOrWhiteSpace($ArtifactDir)) {
        # REG-144, same family as the engine root above: this was a hardcoded build root. The answer
        # comes from Get-NPDevBuildRoot, which identifies the workspace by its CONTENTS -- never a
        # literal, and never repeated as one (CLAUDE.md's own rule about calling that function
        # rather than copying its answer).
        . (Join-Path (Split-Path -Parent (Split-Path -Parent $PSScriptRoot)) "..\scripts\npdev-common.ps1")
        $ArtifactDir = Join-Path (Get-NPDevBuildRoot) "scrapforai-artifacts"
    }
    $ArtifactDir = Normalize-AbsolutePath $ArtifactDir
    New-Item -ItemType Directory -Force -Path $ArtifactDir | Out-Null

    $appOrigin = ([uri]$AppBaseUrl).GetLeftPart([System.UriPartial]::Authority)
    $origins = @($appOrigin) + $ExtraAllowedOrigins | Where-Object { $_ } | Select-Object -Unique
    $originList = ($origins -join ",")

    # Free the port if a previous run leaked a listener on it.
    Stop-PortListener -Port $Port

    $outLog = Join-Path $ArtifactDir "scrapforai-out.log"
    $errLog = Join-Path $ArtifactDir "scrapforai-err.log"

    # We set the env on THIS process; the child inherits it. dotenv (loaded by the
    # server) will not override already-defined vars, and the cwd we hand it has no
    # .env, so these values win without mutating ScrapForAILegacy\.env.
    $env:PORT                     = [string]$Port
    $env:HOST                     = "127.0.0.1"
    $env:NODE_ENV                 = "development"
    $env:SCRAPFORAI_API_KEY       = $ApiKey
    $env:ALLOWED_TARGET_ORIGINS   = $originList
    $env:ALLOWED_RESOURCE_ORIGINS = $originList
    $env:ARTIFACT_DIR             = $ArtifactDir
    $env:ALLOW_EVALUATE           = ($(if ($AllowEvaluate) { "true" } else { "false" }))
    # Generated apps render a SPA that fetches /api/me etc.; give steps/jobs room.
    # (We run from a cwd without .env, so these would otherwise fall to library
    # defaults of 10s/60s, which is too tight for cold-cache page loads.)
    $env:STEP_TIMEOUT_MS          = "30000"
    $env:JOB_TIMEOUT_MS           = "120000"

    $tsx    = Join-Path $Root "node_modules\.bin\tsx.cmd"
    $server = Join-Path $Root "src\server.ts"
    Ensure-File -PathValue $tsx -Label "ScrapForAI tsx launcher (run Initialize-ScrapForAI first)"

    Info ("Starting ScrapForAI on http://127.0.0.1:$Port (allowlist: $originList)")
    $proc = Start-Process -FilePath $tsx -ArgumentList @($server) `
        -WorkingDirectory $ArtifactDir -PassThru -WindowStyle Hidden `
        -RedirectStandardOutput $outLog -RedirectStandardError $errLog

    $context = [pscustomobject]@{
        Proc        = $proc
        Port        = $Port
        BaseUrl     = "http://127.0.0.1:$Port"
        ApiKey      = $ApiKey
        ArtifactDir = $ArtifactDir
        AppBaseUrl  = $AppBaseUrl.TrimEnd("/")
        OutLog      = $outLog
        ErrLog      = $errLog
    }

    $ready = $false
    for ($i = 0; $i -lt ($ReadyTimeoutSec * 2); $i++) {
        if ($proc.HasExited) { break }
        try {
            $h = Invoke-RestMethod -Uri ($context.BaseUrl + "/health") -TimeoutSec 2
            if ($h.status -eq "ok") { $ready = $true; break }
        } catch { }
        Start-Sleep -Milliseconds 500
    }
    if (-not $ready) {
        $err = if (Test-Path $errLog) { Get-Content $errLog -Raw } else { "(no stderr)" }
        Stop-ScrapForAI $context
        Fail ("ScrapForAI did not become ready on " + $context.BaseUrl + ". stderr:`n" + $err)
    }
    Ok ("ScrapForAI ready on " + $context.BaseUrl)
    return $context
}

function Stop-PortListener([int]$Port) {
    $conns = Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue
    foreach ($c in $conns) {
        Stop-Process -Id $c.OwningProcess -Force -ErrorAction SilentlyContinue
    }
}

function Stop-ScrapForAI([object]$Context) {
    if ($null -eq $Context) { return }
    Stop-PortListener -Port $Context.Port
    if ($Context.Proc -and -not $Context.Proc.HasExited) {
        Stop-Process -Id $Context.Proc.Id -Force -ErrorAction SilentlyContinue
    }
    Info "ScrapForAI stopped"
}

# Low-level POST helper that returns the parsed body even on 4xx/5xx (the runner
# returns 200 for a passed routine, 500 for a routine that ran but failed a step,
# 400 for a validation/SSRF rejection -- we want the body in all cases).
function Invoke-ScrapPost([object]$Context, [string]$Route, [object]$BodyObject) {
    $json = $BodyObject | ConvertTo-Json -Depth 30
    $headers = @{ Authorization = ("Bearer " + $Context.ApiKey) }
    $code = 0
    $resp = Invoke-RestMethod -Method Post -Uri ($Context.BaseUrl + $Route) `
        -Headers $headers -ContentType "application/json" -Body $json `
        -SkipHttpErrorCheck -StatusCodeVariable code -TimeoutSec 120
    return [pscustomobject]@{ StatusCode = $code; Body = $resp }
}

# Runs one routine. A routine file is a JSON object with our own optional
# `targetPath` field (default /npdev-business-ui/) plus the scraper's own
# `steps`/`options`/`scenarioName`. The harness composes targetUrl from the
# app base URL + targetPath so routines stay port-agnostic.
function Invoke-ScrapRoutine {
    param(
        [Parameter(Mandatory = $true)][object]$Context,
        [Parameter(Mandatory = $true)][string]$RoutinePath,
        [hashtable]$Variables = @{},    # merged over the routine's own variables (e.g. a per-run unique code)
        [hashtable]$Credentials = @{}   # R7 Stage D: merged over the routine's own credentials (e.g. the
                                         # app's live per-app API key -- see Get-NpdevLiveApiKey in
                                         # sample-common.ps1). Same merge shape as -Variables; kept as a
                                         # SEPARATE bucket (not folded into -Variables) because the engine
                                         # redacts `credentials` values from evidence/logs
                                         # (collectKnownSecretValues in ScrapForAILegacy), which `variables`
                                         # does not -- a real API key belongs in this bucket, never that one.
    )
    Ensure-File -PathValue $RoutinePath -Label "Routine file"
    $routine = Get-Content -LiteralPath $RoutinePath -Raw | ConvertFrom-Json -Depth 30

    $targetPath = "/npdev-business-ui/"
    if ($routine.PSObject.Properties.Name -contains "targetPath") {
        $targetPath = [string]$routine.targetPath
    }
    $targetUrl = $Context.AppBaseUrl + $targetPath

    # Merge routine-declared variables with any runtime overrides.
    $mergedVars = @{}
    $declaredVars = Get-Prop $routine "variables"
    if ($declaredVars) {
        foreach ($p in $declaredVars.PSObject.Properties) { $mergedVars[$p.Name] = $p.Value }
    }
    foreach ($k in $Variables.Keys) { $mergedVars[$k] = $Variables[$k] }

    # Merge routine-declared credentials with any runtime overrides, identically.
    $mergedCreds = @{}
    $declaredCreds = Get-Prop $routine "credentials"
    if ($declaredCreds) {
        foreach ($p in $declaredCreds.PSObject.Properties) { $mergedCreds[$p.Name] = $p.Value }
    }
    foreach ($k in $Credentials.Keys) { $mergedCreds[$k] = $Credentials[$k] }

    # Rebuild a clean request object (drop our private targetPath; inject targetUrl).
    $request = [ordered]@{ targetUrl = $targetUrl }
    if ($routine.PSObject.Properties.Name -contains "scenarioName") { $request.scenarioName = $routine.scenarioName }
    if ($routine.PSObject.Properties.Name -contains "options")      { $request.options      = $routine.options }
    if ($mergedVars.Count -gt 0)                                    { $request.variables    = $mergedVars }
    if ($mergedCreds.Count -gt 0)                                    { $request.credentials  = $mergedCreds }
    $request.steps = $routine.steps

    Info ("Routine -> " + (Split-Path -Leaf $RoutinePath) + "  target=" + $targetUrl)
    $result = Invoke-ScrapPost -Context $Context -Route "/v1/explorations/run" -BodyObject $request
    return $result.Body
}

# inspect-dom against a path of the booted app -- used during authoring to
# discover real selectors/forms/controls before writing a routine.
function Invoke-ScrapInspectDom {
    param(
        [Parameter(Mandatory = $true)][object]$Context,
        [string]$TargetPath = "/npdev-business-ui/",
        [switch]$IncludeHiddenControls,
        [switch]$IncludeConsole,
        [switch]$IncludeNetwork,
        [string]$WaitUntil = "networkidle"
    )
    $body = [ordered]@{
        targetUrl              = $Context.AppBaseUrl + $TargetPath
        includeHiddenControls  = [bool]$IncludeHiddenControls
        includeRenderedHtml    = $false
        redactSensitiveValues  = $true
        includeConsole         = [bool]$IncludeConsole
        includeNetwork         = [bool]$IncludeNetwork
        waitUntil              = $WaitUntil
        timeoutMs              = 15000
    }
    $result = Invoke-ScrapPost -Context $Context -Route "/v1/explorations/inspect-dom" -BodyObject $body
    return $result.Body
}

# Asserts a routine result is green. Hard-fails (throws) on:
#   - status != "passed"  (a step failed)
#   - any pageErrors      (uncaught JS exceptions in the page)
#   - any consoleErrors   (console.error -- usually a real client-side bug)
#   - any unexpectedExternalRequests (UI calling a non-allowlisted origin)
# Network 4xx/5xx are reported and hard-fail only with -StrictNetwork (benign
# favicon 404s would otherwise red a run). Returns the result for chaining.
function Assert-RoutineGreen {
    param(
        [Parameter(Mandatory = $true)][object]$Result,
        [Parameter(Mandatory = $true)][string]$Label,
        [switch]$StrictNetwork,
        # Console errors whose text contains one of these substrings are expected
        # and excluded from the hard-fail count. Use this ONLY for routines that
        # deliberately trigger a non-2xx response (e.g. testing a rejection path) --
        # Chrome logs "Failed to load resource: ... 400" to the console for any
        # failed fetch/XHR, even one the application code catches and handles
        # correctly, so this is not necessarily evidence of a real client bug.
        # Leave empty (the default) for every other routine so the safety net stays
        # full-strength.
        [string[]]$AllowConsoleErrorSubstrings = @()
    )
    if ($null -eq $Result) { Fail ("$Label : no result returned from ScrapForAI") }

    $status = [string]$Result.status
    $ev = $Result.evidence
    $pageErrors   = @($ev.pageErrors)
    $consoleErrs  = @($ev.consoleErrors)
    if ($AllowConsoleErrorSubstrings.Count -gt 0) {
        $consoleErrs = @($consoleErrs | Where-Object {
            $text = [string](Get-Prop $_ "text" "")
            $allowed = $false
            foreach ($needle in $AllowConsoleErrorSubstrings) {
                if ($text -like ("*" + $needle + "*")) { $allowed = $true; break }
            }
            -not $allowed
        })
    }
    $extReqs      = @($ev.unexpectedExternalRequests)
    $netFails     = @($ev.networkFailures)

    if ($status -ne "passed") {
        $idx = Get-Prop $Result "failedStepIndex" "?"
        $err = Get-Prop $Result "error"
        $msg = if ($err) { (Get-Prop $err "type") + ": " + (Get-Prop $err "message") } else { "(no error detail)" }
        Fail ("$Label : routine status=$status at step #$idx -- $msg")
    }
    if ($pageErrors.Count -gt 0) {
        Fail ("$Label : $($pageErrors.Count) page error(s) -- first: " + $pageErrors[0])
    }
    if ($consoleErrs.Count -gt 0) {
        Fail ("$Label : $($consoleErrs.Count) console error(s) -- first: " + $consoleErrs[0].text)
    }
    if ($extReqs.Count -gt 0) {
        Fail ("$Label : $($extReqs.Count) unexpected external request(s) -- first: " + $extReqs[0].origin + $extReqs[0].pathname)
    }
    if ($netFails.Count -gt 0) {
        $first = $netFails[0]
        $detail = (Get-Prop $first "origin") + (Get-Prop $first "pathname") + " status=" + (Get-Prop $first "status") + " " + (Get-Prop $first "failureText")
        if ($StrictNetwork) {
            Fail ("$Label : $($netFails.Count) network failure(s) -- first: " + $detail)
        } else {
            Info ("$Label : NOTE $($netFails.Count) network failure(s) (non-strict) -- first: " + $detail)
        }
    }
    Ok ("$Label : routine green (" + @($Result.steps).Count + " steps, 0 page/console/external errors)")
    return $Result
}

# Writes a compact, checked-in-friendly evidence summary next to the sample's run
# output. Large binaries (screenshots/trace) stay under the scraper ArtifactDir.
function Save-RoutineEvidence {
    param(
        [Parameter(Mandatory = $true)][object]$Result,
        [Parameter(Mandatory = $true)][string]$OutDir,
        [Parameter(Mandatory = $true)][string]$Name
    )
    New-Item -ItemType Directory -Force -Path $OutDir | Out-Null
    $ev = $Result.evidence
    $summary = [ordered]@{
        name            = $Name
        jobId           = Get-Prop $Result "jobId"
        scenarioName    = Get-Prop $Result "scenarioName"
        status          = Get-Prop $Result "status"
        finalUrl        = Get-Prop $Result "finalUrl"
        failedStepIndex = Get-Prop $Result "failedStepIndex"
        error           = Get-Prop $Result "error"
        durationMs      = Get-Prop $Result "durationMs"
        stepCount       = @($Result.steps).Count
        steps           = @($Result.steps | ForEach-Object { [ordered]@{ index = (Get-Prop $_ "index"); action = (Get-Prop $_ "action"); label = (Get-Prop $_ "label"); status = (Get-Prop $_ "status") } })
        counts          = [ordered]@{
            consoleErrors             = @($ev.consoleErrors).Count
            pageErrors                = @($ev.pageErrors).Count
            networkFailures           = @($ev.networkFailures).Count
            unexpectedExternalRequests = @($ev.unexpectedExternalRequests).Count
            screenshots               = @($ev.screenshots).Count
        }
        screenshots     = @($ev.screenshots | ForEach-Object { $_.name })
        extracted       = $Result.extracted
    }
    $path = Join-Path $OutDir ($Name + ".json")
    $summary | ConvertTo-Json -Depth 30 | Set-Content -LiteralPath $path -Encoding UTF8
    Ok ("Evidence summary -> " + $path)
    return $path
}

# MONITOR_PLAN C1 + R10. Append this result to the app's append-only run history, THROUGH THE CLI.
#
# The point is not convenience, it is that the verdict is computed in exactly one place. This harness
# keeps `Assert-RoutineGreen` for console UX -- a hard stop with a readable message is what a sample
# script needs -- but the RECORDED verdict comes from `npdev explore record`, which applies the same
# D5 rules the Manager and `npdev explore run` apply. Two implementations of "green" is how green
# quietly comes to mean different things per driver, which is exactly what the all-gates rule exists
# to prevent one layer up.
#
# PowerShell therefore does no schema work and holds no allowlist: it hands over the engine's raw
# result and the CLI decides.
function Add-RoutineRunToHistory {
    param(
        [Parameter(Mandatory = $true)][object]$Result,
        [Parameter(Mandatory = $true)][string]$AppDir,
        [string]$RoutinePath = "",
        [string]$Driver = "harness",
        [string]$LedgerId = "",
        [string]$ArtifactDir = ""
    )
    $cli = Get-NpdevCliPath
    if (-not (Test-Path -LiteralPath $cli)) {
        Info "npdev CLI not found next to this harness -- run history not recorded."
        return $null
    }
    if (-not (Test-Path -LiteralPath (Join-Path $AppDir "_ops"))) {
        Info ("No _ops toolbox at " + $AppDir + " -- run history not recorded (nowhere to record it).")
        return $null
    }

    $temp = Join-Path ([System.IO.Path]::GetTempPath()) ("npdev-run-" + [guid]::NewGuid().ToString("N") + ".json")
    try {
        $Result | ConvertTo-Json -Depth 40 | Set-Content -LiteralPath $temp -Encoding UTF8
        $args = @($cli, "explore", "record", "--json", "--from-file", $temp, "--app-dir", $AppDir, "--driver", $Driver)
        if (-not [string]::IsNullOrWhiteSpace($RoutinePath)) { $args += @("--routine-file", $RoutinePath) }
        if (-not [string]::IsNullOrWhiteSpace($LedgerId))    { $args += @("--ledger-id", $LedgerId) }
        if (-not [string]::IsNullOrWhiteSpace($ArtifactDir)) { $args += @("--artifact-dir", $ArtifactDir) }
        $json = & python @args 2>&1
        if ($LASTEXITCODE -ne 0) {
            # Never fail the SAMPLE because history could not be written. The run itself already
            # passed or failed on its own evidence; losing the history line is a smaller problem than
            # turning a green demonstration red for a bookkeeping reason.
            Info ("Could not record run history: " + ($json | Out-String).Trim())
            return $null
        }
        $record = $json | ConvertFrom-Json
        Ok ("Run recorded -> " + $record.runId + "  (verdict green=" + $record.verdict.green + ")")
        return $record
    } finally {
        Remove-Item -LiteralPath $temp -Force -ErrorAction SilentlyContinue
    }
}
