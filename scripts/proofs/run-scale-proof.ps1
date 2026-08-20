<#
.SYNOPSIS
    R9 nightly model-scale ladder, FULL version (ROADMAP.md card R9; Track C C6; extends the
    reduced prelude done in MASTER-ROADMAP.md Step 2a). Synthesizes a deterministic N-concept
    model, then synthesize -> generate -> ddl-count -> build -> boot -> firstRequest -> latency ->
    memory, recording all EIGHT measurements per rung. One invocation = one rung; the 5-rung ladder
    (26/50/100/260/520) is driven by calling this script 5 times -- see
    .github/workflows/nightly-scale-ladder.yml for the scheduled driver.

.DESCRIPTION
    F2 (MASTER-ROADMAP.md): this is the baseline BT-1 needed before it landed, so BT-1's own
    before/after build-time claim was measurable. Writes its working files under an out-of-repo
    workspace (NEVER under AppGen/apps or NPDevSamples -- both are corpus roots scanned by
    validate-corpus.py, and a synthesized throwaway model joining that corpus needs a corpusRole
    it has no business carrying).

    The eight measurements, in pipeline order:
      1. synthesize   -- model.json generation time (scripts/proofs/synthesize_scale_model.py).
      2. generate      -- NPDevGenerator :generator:run time.
      3. ddl           -- CREATE TABLE count in the generated V1__npdev_schema_realization.sql
                          (SchemaRealizationEmitter's static output -- deterministic, read right
                          after generate, no dependency on runtime logging or boot succeeding).
      4. build         -- assembled app's own `gradlew clean build -x test` time.
      5. boot          -- gradlew bootRun until /actuator/health reports UP.
      6. firstRequest  -- one authenticated GET against the synthesized panel's runtime metadata.
      7. latency       -- average elapsed ms across -LatencyRequestCount repeat requests against
                          the same panel endpoint, once firstRequest has proven it works.
      8. memory        -- peak JVM resident-set size (WorkingSet64) sampled from the bootRun JVM's
                          own process tree while polling boot health and while issuing the
                          firstRequest/latency requests. NOT actuator-based: /actuator/metrics is
                          stripped down to health,info,mappings,beans by application-dev.properties
                          under the dev,trial profile this script boots with, and the remaining
                          metrics/prometheus endpoints are additionally gated by
                          ActuatorAdminGuardFilter behind a live SUPERUSER key -- reading the OS
                          process's own memory counters sidesteps both instead of fighting either.

.PARAMETER Concepts
    Number of concepts to synthesize.

.PARAMETER Port
    Port the assembled app boots on. Defaults to 18200 + Concepts so rungs run back-to-back
    without a stale process from a previous rung colliding.

.PARAMETER BootTimeoutSeconds
    Generous by default (300s) -- the fast-gate canary lesson: gradlew bootRun forks a single-use
    Gradle daemon, and that overhead, not the app, is most of the budget. Larger rungs (260/520)
    should pass a larger value explicitly.

.PARAMETER LatencyRequestCount
    Number of repeat requests issued for the latency phase once firstRequest has already proven
    the endpoint works. Default 20.

.PARAMETER BaselinePath
    The tracked, diffable record (repo-relative). Ratchet-only per ROADMAP.md D8 -- no ceiling,
    just an observed-value history per concept count.

.EXAMPLE
    pwsh -NoProfile -File scripts/proofs/run-scale-proof.ps1 -Concepts 26
    pwsh -NoProfile -File scripts/proofs/run-scale-proof.ps1 -Concepts 520 -BootTimeoutSeconds 900
#>
param(
    [Parameter(Mandatory = $true)][int]$Concepts,
    [string]$WorkspaceRoot = "",
    [int]$Port = 0,
    [int]$BootTimeoutSeconds = 300,
    [int]$LatencyRequestCount = 20,
    [string]$BaselinePath = "",
    [string]$ReportPath = "",
    [string]$SchemaPath = ""
)

$ErrorActionPreference = "Stop"
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
. (Join-Path $repoRoot "scripts\npdev-common.ps1")
Set-StrictMode -Off

if ([string]::IsNullOrWhiteSpace($WorkspaceRoot)) {
    $WorkspaceRoot = Get-NPDevWorkspaceRoot $PSScriptRoot
}
if ($Port -eq 0) {
    $Port = 18200 + $Concepts
}
if ([string]::IsNullOrWhiteSpace($BaselinePath)) {
    $BaselinePath = Join-Path $repoRoot "scripts\policy\scale-proof-baseline.json"
}
if ([string]::IsNullOrWhiteSpace($SchemaPath)) {
    $SchemaPath = Join-Path $repoRoot "schemas\ai\scale-proof-report.schema.json"
}

# Out-of-repo workspace: sibling of the repo, exactly like invoke-ai-beta-app-smoke.ps1's own
# NPDEV_RUNTIMEHOST_LIBS_DIR default derivation -- never under a corpus root.
$workspaceItem = Get-Item -LiteralPath $WorkspaceRoot
$outsideRepoRoot = Join-Path $workspaceItem.Parent.FullName ($workspaceItem.Name + "__OutsideRepo")
$rungRoot = Join-Path $outsideRepoRoot ("scale-proof\" + $Concepts)
$inputRoot = Join-Path $rungRoot "Input"
$outputRoot = Join-Path $rungRoot "Output"
$artifactRoot = Join-Path $outputRoot "ArtifactNP"
$appRoot = Join-Path $outputRoot "App"
if ([string]::IsNullOrWhiteSpace($ReportPath)) {
    $ReportPath = Join-Path $rungRoot "scale-proof-report.json"
}

New-Item -ItemType Directory -Force -Path $inputRoot | Out-Null

function New-Phase {
    param([string]$Status, [long]$DurationMs, [string]$Message = "", [object]$Value = $null, [string]$Unit = "")
    $phase = [ordered]@{ status = $Status; durationMs = [int]$DurationMs; message = $Message }
    if ($null -ne $Value) { $phase.value = $Value }
    if (-not [string]::IsNullOrWhiteSpace($Unit)) { $phase.unit = $Unit }
    return $phase
}

function Convert-ResponseContentToString {
    # Mirrors invoke-ai-beta-app-smoke.ps1's own helper: Invoke-WebRequest does not decode
    # .Content to a string for actuator's vendor content-type
    # (application/vnd.spring-boot.actuator.v3+json) -- it comes back as a [byte[]], so a
    # regex match against it silently never matches even though the body IS "status":"UP".
    param([object]$Content)
    if ($null -eq $Content) { return "" }
    if ($Content -is [byte[]]) { return [System.Text.Encoding]::UTF8.GetString($Content) }
    return [string]$Content
}

# Cross-platform (added for the FULL ladder's CI wiring, which targets ubuntu-latest per the
# card's own "CI runner physics" framing -- the reduced prelude only ever ran on the author's
# Windows machine, so Win32_Process-only was never exercised off it). Win32_Process/CIM does not
# exist outside Windows; `ps -eo pid,ppid` is the POSIX equivalent, available on every GitHub-hosted
# ubuntu-latest runner without extra setup.
function Get-DescendantProcessIds {
    param([int]$RootProcessId)
    $descendants = [System.Collections.Generic.List[int]]::new()
    $pending = [System.Collections.Generic.Queue[int]]::new()
    $pending.Enqueue($RootProcessId)
    if ($IsWindows) {
        $allProcesses = @(Get-CimInstance Win32_Process)
        while ($pending.Count -gt 0) {
            $parentId = $pending.Dequeue()
            foreach ($child in @($allProcesses | Where-Object { $_.ParentProcessId -eq $parentId })) {
                $childId = [int]$child.ProcessId
                $descendants.Add($childId) | Out-Null
                $pending.Enqueue($childId)
            }
        }
    }
    else {
        $childrenByParent = @{}
        foreach ($line in @(& ps -eo pid,ppid --no-headers 2>$null)) {
            $parts = @(([string]$line).Trim() -split '\s+')
            if ($parts.Count -lt 2) { continue }
            $childPid = 0
            $parentPid = 0
            if (-not [int]::TryParse($parts[0], [ref]$childPid)) { continue }
            if (-not [int]::TryParse($parts[1], [ref]$parentPid)) { continue }
            if (-not $childrenByParent.ContainsKey($parentPid)) {
                $childrenByParent[$parentPid] = [System.Collections.Generic.List[int]]::new()
            }
            $childrenByParent[$parentPid].Add($childPid) | Out-Null
        }
        while ($pending.Count -gt 0) {
            $parentId = $pending.Dequeue()
            if ($childrenByParent.ContainsKey($parentId)) {
                foreach ($childId in $childrenByParent[$parentId]) {
                    $descendants.Add($childId) | Out-Null
                    $pending.Enqueue($childId)
                }
            }
        }
    }
    return @($descendants)
}

function Stop-ProcessTree {
    param([int]$RootProcessId)
    $ids = @((Get-DescendantProcessIds -RootProcessId $RootProcessId) | Select-Object -Unique)
    [array]::Reverse($ids)
    foreach ($id in $ids) {
        if ($id -ne $PID) { Stop-Process -Id $id -Force -ErrorAction SilentlyContinue }
    }
    if ($RootProcessId -ne $PID) { Stop-Process -Id $RootProcessId -Force -ErrorAction SilentlyContinue }
}

# Memory measurement (phase 8): read the OS process's own resident-set size rather than fighting
# actuator's dev-profile exposure list (health,info,mappings,beans only -- metrics/prometheus are
# stripped by application-dev.properties) or its ActuatorAdminGuardFilter SUPERUSER-key gate.
# `gradlew bootRun` forks Gradle -> a worker -> the actual java process, so the JVM is a
# DESCENDANT of $RootProcessId, never $RootProcessId itself.
function Get-PeakJavaWorkingSetMb {
    param([int]$RootProcessId)
    $candidateIds = @(@($RootProcessId) + (Get-DescendantProcessIds -RootProcessId $RootProcessId))
    $peakBytes = 0L
    foreach ($id in $candidateIds) {
        try {
            $proc = Get-Process -Id $id -ErrorAction Stop
        }
        catch { continue }
        if ($proc.ProcessName -notmatch '(?i)java') { continue }
        if ($proc.WorkingSet64 -gt $peakBytes) { $peakBytes = $proc.WorkingSet64 }
    }
    return [Math]::Round($peakBytes / 1MB, 1)
}

$phases = [ordered]@{}
$overallFailed = $false

# -- Phase: synthesize (model.json + config.json + db.definition.json) ----------------------------
Write-Host "== R9 scale proof: $Concepts concepts ==" -ForegroundColor Cyan
Write-Host "-- synthesize --"
$sw = [System.Diagnostics.Stopwatch]::StartNew()
$modelPath = Join-Path $inputRoot "model.json"
$py = if (Get-Command python -ErrorAction SilentlyContinue) { "python" } else { "py" }
& $py (Join-Path $repoRoot "scripts\proofs\synthesize_scale_model.py") --concepts $Concepts --out $modelPath
$synthesizeOk = $LASTEXITCODE -eq 0

$scenarioName = "scaleproof$Concepts"
$configPath = Join-Path $inputRoot "config.json"
$dbDefinitionPath = Join-Path $inputRoot "db.definition.json"
if ($synthesizeOk) {
    $config = [ordered]@{
        configVersion = "1.0"
        scenario = [ordered]@{
            name = $scenarioName
            description = "R9 reduced scale ladder rung ($Concepts concepts). Synthesized, not authored -- see scripts/proofs/run-scale-proof.ps1."
            outputRoot = "..\Output"
        }
        generator = [ordered]@{
            failIfModelMissing = $true
            failIfConfigMissing = $true
            cleanOutputBeforeGenerate = $true
            emitPluginAssets = $true
            emitRuntimeAssets = $true
            emitUiAssets = $true
        }
        bootstrap = [ordered]@{ root = (Join-Path $repoRoot "NPDevRuntimeHost"); mergeStrategy = "clean-copy" }
        artifact = [ordered]@{ root = "..\Output\ArtifactNP"; generatedFolderName = "npdev-generated"; libsFolderName = "libs"; metaFolderName = "npdev-meta" }
        finalExec = [ordered]@{ root = "..\Output\App"; deleteBeforeMount = $true }
        database = [ordered]@{ provider = "h2-local"; database = $scenarioName; resetMode = "reset" }
        runtime = [ordered]@{ springProfile = "dev,trial"; serverPort = $Port; javaArgs = @(); gradleTask = "bootRun" }
    }
    $config | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath $configPath -Encoding UTF8

    $dbDefinition = [ordered]@{
        database = [ordered]@{ engine = "H2Local"; databaseName = $scenarioName; username = "sa"; password = ""; createInternalTables = $true; createBusinessTables = $true }
        schemaLifecycle = [ordered]@{ strategy = "KeepExistingIfCompatible"; allowDestructiveRecreate = $false; destructiveRecreateConfirmation = ""; scope = "NpdevOwnedTablesOnly" }
    }
    $dbDefinition | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath $dbDefinitionPath -Encoding UTF8
}
$sw.Stop()
$phases.synthesize = New-Phase -Status ($(if ($synthesizeOk) { "passed" } else { "failed" })) -DurationMs $sw.ElapsedMilliseconds -Message $modelPath
if (-not $synthesizeOk) { $overallFailed = $true }

# -- Phase: generate (NPDevGenerator :generator:run, direct invocation -- same contract as
#    NPDevSamples/scripts/generate-sample-app.ps1, just pointed at an out-of-repo model) ----------
if (-not $overallFailed) {
    Write-Host "-- generate --"
    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    $generatorRoot = Join-Path $repoRoot "NPDevGenerator"
    $generatorGradlew = Get-NPDevGradleWrapperExecutable $generatorRoot
    $generatorArgLine = "--config `"$configPath`" --model `"$modelPath`" --out `"$artifactRoot`" --dbDefinitionPath `"$dbDefinitionPath`" --runtimeHostTemplate `"$(Join-Path $repoRoot 'NPDevRuntimeHost')`" --finalAppOut `"$appRoot`" --clean --assembleFinalApp --cleanFinalApp"
    $gradleUserHome = Get-NPDevGradleUserHome $repoRoot
    Push-Location $generatorRoot
    $previousGradleUserHome = $env:GRADLE_USER_HOME
    $env:GRADLE_USER_HOME = $gradleUserHome
    try {
        & $generatorGradlew ":generator:run" "--args=$generatorArgLine" "--console=plain"
        $generateOk = $LASTEXITCODE -eq 0
    }
    finally {
        if ($null -eq $previousGradleUserHome) { Remove-Item Env:GRADLE_USER_HOME -ErrorAction SilentlyContinue } else { $env:GRADLE_USER_HOME = $previousGradleUserHome }
        Pop-Location
    }
    $sw.Stop()
    $phases.generate = New-Phase -Status ($(if ($generateOk) { "passed" } else { "failed" })) -DurationMs $sw.ElapsedMilliseconds -Message $appRoot
    if (-not $generateOk) { $overallFailed = $true }
}

# -- Phase: ddl (static count -- SchemaRealizationEmitter writes every CREATE TABLE to this file
#    deterministically at generate time; reading it needs neither a successful build nor a boot,
#    and sidesteps SchemaLifecycleExecutor entirely -- confirmed to carry no logger and no
#    per-statement DDL log line, only unconditional System.out.println narration of LATER
#    drift-driven boots, never a fresh one). Non-fatal: a miscount here is informative, not a
#    reason to skip build/boot. ------------------------------------------------------------------
if (-not $overallFailed) {
    Write-Host "-- ddl --"
    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    $schemaRealizationPath = Join-Path $artifactRoot "src\main\resources\db\schema-realization\V1__npdev_schema_realization.sql"
    $ddlCount = 0
    $ddlOk = $false
    if (Test-Path -LiteralPath $schemaRealizationPath -PathType Leaf) {
        $ddlText = Get-Content -Raw -LiteralPath $schemaRealizationPath
        $ddlCount = @([regex]::Matches($ddlText, '(?m)^CREATE TABLE ')).Count
        $ddlOk = $ddlCount -gt 0
    }
    $sw.Stop()
    $ddlMessage = if ($ddlOk) { "$ddlCount CREATE TABLE statement(s) in $schemaRealizationPath" } else { "no CREATE TABLE statements found at $schemaRealizationPath" }
    $phases.ddl = New-Phase -Status ($(if ($ddlOk) { "passed" } else { "failed" })) -DurationMs $sw.ElapsedMilliseconds -Message $ddlMessage -Value $ddlCount -Unit "count"
}
else {
    $phases.ddl = New-Phase -Status "skipped" -DurationMs 0 -Message "generate did not pass"
}

# -- Phase: build (assembled app's own Gradle build, no tests -- same command
#    invoke-ai-beta-app-smoke.ps1 uses) --------------------------------------------------------
if (-not $overallFailed) {
    Write-Host "-- build --"
    if ([string]::IsNullOrWhiteSpace($env:NPDEV_RUNTIMEHOST_LIBS_DIR)) {
        $env:NPDEV_RUNTIMEHOST_LIBS_DIR = Get-NPDevRuntimeHostLibsDir $repoRoot
    }
    $appGradlew = Get-NPDevGradleWrapperExecutable $appRoot
    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    $buildStdout = Join-Path $appRoot "scale-proof-build.stdout.log"
    $buildStderr = Join-Path $appRoot "scale-proof-build.stderr.log"
    $build = Start-Process -FilePath $appGradlew -ArgumentList @("--no-daemon", "clean", "build", "-x", "test", "--console=plain") -WorkingDirectory $appRoot -NoNewWindow -Wait -PassThru -RedirectStandardOutput $buildStdout -RedirectStandardError $buildStderr
    $sw.Stop()
    $buildOk = $build.ExitCode -eq 0
    $phases.build = New-Phase -Status ($(if ($buildOk) { "passed" } else { "failed" })) -DurationMs $sw.ElapsedMilliseconds -Message ("exit " + $build.ExitCode)
    if (-not $buildOk) {
        $overallFailed = $true
        # SCALE-1: a build failure captured only into log files leaves the CI log with a silent gap
        # and no cause. Stream the tail of both logs so the failure is visible where the run is
        # inspected -- the full files remain on disk under $appRoot for anyone who needs them.
        Write-Host "-- build FAILED (exit $($build.ExitCode)); tail of scale-proof-build.stderr.log --" -ForegroundColor Red
        if (Test-Path -LiteralPath $buildStderr) { Get-Content -LiteralPath $buildStderr -Tail 40 | ForEach-Object { Write-Host $_ } }
        Write-Host "-- tail of scale-proof-build.stdout.log --" -ForegroundColor Red
        if (Test-Path -LiteralPath $buildStdout) { Get-Content -LiteralPath $buildStdout -Tail 40 | ForEach-Object { Write-Host $_ } }
    }
}

# -- API key provisioning (SCALE-2) ----------------------------------------------------------------
# This launcher never provisioned one, and it hardcoded "dev-key" in its own request headers. That
# was invisible for as long as the ladder failed earlier: the 260/520 rungs died in `build` (the
# 255-parameter ceiling), so `boot` never ran and nobody saw that it could not have worked either.
# With the build fixed, boot failed immediately on StartupValidator's
#   "npdev.auth.api-keys must define at least one mapping when auth is enabled"
# -- the check f11bf212 ("provision API keys on every launcher") added. That commit fixed the
# launchers it knew about; this proof was not one of them, because it was already red for a
# different reason and so produced no new evidence when it stayed red.
#
# Same file format and env-var contract as Build-NpdevApp.ps1's Ensure-NpdevApiKey and
# OperationalRunbookEmitter.API_KEY_PROVISIONER: secrets\api-key.env holding
# NPDEV_AUTH_API_KEYS=<key>=dev:developer:admin. bootRun is launched by Start-Process below, which
# inherits this process's environment, so setting it here is what reaches the app.
$scaleProofApiKey = $null
if (-not $overallFailed) {
    $secretsDir = Join-Path $appRoot "secrets"
    if (-not (Test-Path -LiteralPath $secretsDir)) { New-Item -ItemType Directory -Force -Path $secretsDir | Out-Null }
    $keyFile = Join-Path $secretsDir "api-key.env"
    $keyBytes = New-Object byte[] 24
    [System.Security.Cryptography.RandomNumberGenerator]::Fill($keyBytes)
    $scaleProofApiKey = ([Convert]::ToBase64String($keyBytes) -replace '[^a-zA-Z0-9]', '')
    Set-Content -LiteralPath $keyFile -Value ("NPDEV_AUTH_API_KEYS=" + $scaleProofApiKey + "=dev:developer:admin") -Encoding UTF8 -NoNewline
    $env:NPDEV_AUTH_API_KEYS = $scaleProofApiKey + "=dev:developer:admin"
}

# -- Phase: boot + firstRequest + latency + memory ------------------------------------------------
if (-not $overallFailed) {
    Write-Host "-- boot --"
    $appGradlew = Get-NPDevGradleWrapperExecutable $appRoot
    $bootStdout = Join-Path $appRoot "scale-proof-boot.stdout.log"
    $bootStderr = Join-Path $appRoot "scale-proof-boot.stderr.log"
    $bootArgs = @("--no-daemon", "bootRun", ('--args="--spring.profiles.active=dev,trial --server.port=' + $Port + '"'))
    $process = $null
    $bootOk = $false
    $firstRequestOk = $false
    $bootSw = [System.Diagnostics.Stopwatch]::StartNew()
    $firstRequestMs = 0
    $peakMemoryMb = 0.0
    $latencyPhaseResult = $null
    try {
        $process = Start-Process -FilePath $appGradlew -ArgumentList $bootArgs -WorkingDirectory $appRoot -NoNewWindow -PassThru -RedirectStandardOutput $bootStdout -RedirectStandardError $bootStderr
        $healthUri = "http://127.0.0.1:$Port/actuator/health"
        $deadline = (Get-Date).AddSeconds($BootTimeoutSeconds)
        while ((Get-Date) -lt $deadline) {
            if ($process.HasExited) { break }
            # Phase 8 (memory) sampling: piggybacks on the health poll that already runs every 2s,
            # so watching for boot completion is what pays for watching peak RSS too -- no extra
            # polling loop, no extra wall-clock cost.
            $sample = Get-PeakJavaWorkingSetMb -RootProcessId $process.Id
            if ($sample -gt $peakMemoryMb) { $peakMemoryMb = $sample }
            try {
                $healthResponse = Invoke-WebRequest -Uri $healthUri -TimeoutSec 5 -SkipHttpErrorCheck
                $healthBody = Convert-ResponseContentToString $healthResponse.Content
                if ([int]$healthResponse.StatusCode -eq 200 -and $healthBody -match '"status"\s*:\s*"UP"') {
                    $bootOk = $true
                    break
                }
            }
            catch { }
            Start-Sleep -Seconds 2
        }
        $bootSw.Stop()

        if ($bootOk) {
            Write-Host "-- firstRequest --"
            $panelUri = "http://127.0.0.1:$Port/api/runtime/metadata/ui/panels/ScaleProofPanel"
            $firstSw = [System.Diagnostics.Stopwatch]::StartNew()
            $firstRequestDetail = ""
            try {
                # Matches scripts/ai/Invoke-AiRestSmokeVerifier.ps1's own default headers -- every
                # REST endpoint here is behind RuntimeApiKeyAuthFilter. The key is the one this run
                # provisioned above -- NOT a hardcoded "dev-key", which no longer authenticates
                # anything now that StartupValidator requires a real npdev.auth.api-keys mapping.
                $panelResponse = Invoke-WebRequest -Uri $panelUri -TimeoutSec 15 -SkipHttpErrorCheck -Headers @{ "X-NPDEV-API-Key" = $scaleProofApiKey; "X-API-Key" = $scaleProofApiKey }
                $firstRequestOk = [int]$panelResponse.StatusCode -eq 200
                $firstRequestDetail = "status " + [int]$panelResponse.StatusCode + ": " + (Convert-ResponseContentToString $panelResponse.Content)
            }
            catch {
                $firstRequestOk = $false
                $firstRequestDetail = $_.Exception.Message
            }
            $firstSw.Stop()
            $firstRequestMs = $firstSw.ElapsedMilliseconds
            $sample = Get-PeakJavaWorkingSetMb -RootProcessId $process.Id
            if ($sample -gt $peakMemoryMb) { $peakMemoryMb = $sample }

            if ($firstRequestOk) {
                Write-Host "-- latency --"
                $latencies = [System.Collections.Generic.List[double]]::new()
                $latencyFailures = 0
                for ($i = 0; $i -lt $LatencyRequestCount; $i++) {
                    $reqSw = [System.Diagnostics.Stopwatch]::StartNew()
                    try {
                        $latencyResponse = Invoke-WebRequest -Uri $panelUri -TimeoutSec 15 -SkipHttpErrorCheck -Headers @{ "X-NPDEV-API-Key" = $scaleProofApiKey; "X-API-Key" = $scaleProofApiKey }
                        $reqSw.Stop()
                        if ([int]$latencyResponse.StatusCode -eq 200) {
                            $latencies.Add([double]$reqSw.ElapsedMilliseconds) | Out-Null
                        }
                        else {
                            $latencyFailures++
                        }
                    }
                    catch {
                        $reqSw.Stop()
                        $latencyFailures++
                    }
                }
                $sample = Get-PeakJavaWorkingSetMb -RootProcessId $process.Id
                if ($sample -gt $peakMemoryMb) { $peakMemoryMb = $sample }

                if ($latencies.Count -gt 0) {
                    $sorted = @($latencies | Sort-Object)
                    $avgMs = [Math]::Round((($sorted | Measure-Object -Average).Average), 1)
                    $p95Index = [Math]::Min($sorted.Count - 1, [Math]::Ceiling(0.95 * $sorted.Count) - 1)
                    $p95Ms = $sorted[$p95Index]
                    $latencyPhaseResult = New-Phase -Status "passed" -DurationMs ([long]$avgMs) `
                        -Message ("avg " + $avgMs + "ms / p95 " + $p95Ms + "ms over " + $sorted.Count + " request(s) against " + $panelUri + " (" + $latencyFailures + " failed)") `
                        -Value $avgMs -Unit "ms"
                }
                else {
                    $latencyPhaseResult = New-Phase -Status "failed" -DurationMs 0 -Message ("all " + $LatencyRequestCount + " latency request(s) failed against " + $panelUri)
                }
            }
        }
    }
    finally {
        if ($null -ne $process -and -not $process.HasExited) {
            Stop-ProcessTree -RootProcessId $process.Id
        }
    }
    $phases.boot = New-Phase -Status ($(if ($bootOk) { "passed" } else { "failed" })) -DurationMs $bootSw.ElapsedMilliseconds -Message $healthUri
    if (-not $bootOk) {
        $overallFailed = $true
        $phases.firstRequest = New-Phase -Status "skipped" -DurationMs 0 -Message "boot did not pass"
        $phases.latency = New-Phase -Status "skipped" -DurationMs 0 -Message "boot did not pass"
        $phases.memory = New-Phase -Status ($(if ($peakMemoryMb -gt 0) { "passed" } else { "skipped" })) -DurationMs 0 -Message "peak RSS sampled while polling boot (boot never reached UP)" -Value $peakMemoryMb -Unit "mb"
    }
    else {
        $phases.firstRequest = New-Phase -Status ($(if ($firstRequestOk) { "passed" } else { "failed" })) -DurationMs $firstRequestMs -Message ($panelUri + " -- " + $firstRequestDetail)
        if (-not $firstRequestOk) {
            $overallFailed = $true
            $phases.latency = New-Phase -Status "skipped" -DurationMs 0 -Message "firstRequest did not pass"
        }
        else {
            $phases.latency = $latencyPhaseResult
            if ($latencyPhaseResult.status -ne "passed") { $overallFailed = $true }
        }
        $phases.memory = New-Phase -Status "passed" -DurationMs 0 -Message "peak RSS sampled across boot polling + firstRequest + latency (point samples, not a continuous profiler)" -Value $peakMemoryMb -Unit "mb"
    }
}
else {
    $phases.build = if ($phases.Contains("build")) { $phases.build } else { New-Phase -Status "skipped" -DurationMs 0 }
    $phases.boot = New-Phase -Status "skipped" -DurationMs 0
    $phases.firstRequest = New-Phase -Status "skipped" -DurationMs 0
    $phases.latency = New-Phase -Status "skipped" -DurationMs 0
    $phases.memory = New-Phase -Status "skipped" -DurationMs 0
}

# -- Report ---------------------------------------------------------------------------------------
# schemaVersion bumped v1 -> v2: v1 reports (still sitting in scale-proof-baseline.json's ratchet
# history, never rewritten) had 5 phases; v2 adds the 3 new required ones (ddl/latency/memory) --
# see schemas/ai/scale-proof-report.schema.json.
$report = [ordered]@{
    schemaVersion = "npdev-scale-proof-report.v2"
    generatedAt = (Get-Date).ToUniversalTime().ToString("o")
    concepts = $Concepts
    status = if ($overallFailed) { "failed" } else { "passed" }
    phases = $phases
}
Write-NPDevJsonFile $ReportPath $report

# Schema validation is best-effort/non-fatal here on purpose: this script's primary signal is
# whether generate/build/boot/firstRequest actually passed, and that must not go red just because
# the AJV validator's Node toolchain (scripts/quality/Invoke-JsonSchemaValidation.ps1) is
# unavailable or slow to npm-install on a given machine/runner. A malformed report is still worth
# surfacing loudly, just not as this script's own exit code.
try {
    $schemaValidationPath = Join-Path $rungRoot "scale-proof-report-schema-validation.json"
    $ErrorActionPreference = "Continue"
    pwsh -NoProfile -File (Join-Path $repoRoot "scripts\quality\Invoke-JsonSchemaValidation.ps1") -SchemaPath $SchemaPath -InstancePath $ReportPath -ReportPath $schemaValidationPath 2>$null | Out-Null
    $schemaExitCode = $LASTEXITCODE
    $ErrorActionPreference = "Stop"
    if ($schemaExitCode -eq 0) {
        Write-Host "-- schema: report matches $SchemaPath --" -ForegroundColor Green
    }
    else {
        Write-Host "-- schema: WARNING -- report did NOT validate against $SchemaPath (see $schemaValidationPath) --" -ForegroundColor Yellow
    }
}
catch {
    $ErrorActionPreference = "Stop"
    Write-Host ("-- schema: WARNING -- validation could not run (" + $_.Exception.Message + ") --") -ForegroundColor Yellow
}

# -- Baseline (tracked, in-repo, ratchet-only per ROADMAP.md D8 -- no ceiling asserted here) ------
$baseline = if (Test-Path -LiteralPath $BaselinePath) {
    Get-Content -Raw -LiteralPath $BaselinePath | ConvertFrom-Json -AsHashtable
}
else {
    [ordered]@{ schemaVersion = "npdev-scale-proof-baseline.v1"; runs = [ordered]@{} }
}
if (-not $baseline.Contains("runs")) { $baseline["runs"] = [ordered]@{} }
$key = [string]$Concepts
# BT-1: found live while recording this card's own required before/after measurement -- a REPEAT
# run for a $key that already has history (e.g. rerunning 26 or 100) threw "A hash table can only
# be added to another hash table". `ConvertFrom-Json -AsHashtable` types each existing history entry
# as a strict [hashtable], but $report above is built with `[ordered]@{}` ([OrderedDictionary]) --
# PowerShell's `+=` on the resulting strongly-[hashtable]-typed array enforces that element type, so
# appending an OrderedDictionary (not exactly [hashtable]) fails. A List[object] has no such
# enforcement -- `,$report`'s single-item-append intent (see the comment this replaces) is preserved
# by .Add(), which never enumerates its argument's entries the way `+=` on an array can.
$history = [System.Collections.Generic.List[object]]::new()
if ($baseline["runs"].Contains($key)) {
    foreach ($entry in @($baseline["runs"][$key])) { $history.Add($entry) | Out-Null }
}
$history.Add($report) | Out-Null
$baseline["runs"][$key] = @($history)
($baseline | ConvertTo-Json -Depth 30) | Set-Content -LiteralPath $BaselinePath -Encoding UTF8

Write-Host ""
Write-Host "== Summary ($Concepts concepts) ==" -ForegroundColor Cyan
foreach ($name in $phases.Keys) {
    $p = $phases[$name]
    $color = if ($p.status -eq "passed") { "Green" } elseif ($p.status -eq "skipped") { "Yellow" } else { "Red" }
    Write-Host ("  {0,-14} {1,-8} {2,8} ms" -f $name, $p.status, $p.durationMs) -ForegroundColor $color
}
Write-Host ("Report: " + $ReportPath)
Write-Host ("Baseline: " + $BaselinePath)

if ($overallFailed) {
    Write-Host "SCALE PROOF FAILED ($Concepts concepts)." -ForegroundColor Red
    exit 1
}
Write-Host "SCALE PROOF PASSED ($Concepts concepts)." -ForegroundColor Green
exit 0
