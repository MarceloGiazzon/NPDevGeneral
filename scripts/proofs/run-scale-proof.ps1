<#
.SYNOPSIS
    R9 reduced scale ladder (ROADMAP.md card R9, reduced per MASTER-ROADMAP.md prelude card 0.3 /
    Step 2a): synthesize a deterministic N-concept model, then generate -> build -> boot -> one
    first request, timing each phase. Two rungs only (26, 100); generate/build/boot timings only --
    latency-under-load, DDL count and memory are Track C's "R9 full" upgrade, not this script.

.DESCRIPTION
    F2 (MASTER-ROADMAP.md): this is the baseline BT-1 needs before it lands, so BT-1's own
    before/after build-time claim is measurable. Writes its working files under an out-of-repo
    workspace (NEVER under AppGen/apps or NPDevSamples -- both are corpus roots scanned by
    validate-corpus.py, and a synthesized throwaway model joining that corpus needs a corpusRole
    it has no business carrying).

.PARAMETER Concepts
    Number of concepts to synthesize.

.PARAMETER Port
    Port the assembled app boots on. Defaults to 18200 + Concepts so two rungs run back-to-back
    without a stale process from the previous rung colliding.

.PARAMETER BootTimeoutSeconds
    Generous by default (300s) -- the fast-gate canary lesson: gradlew bootRun forks a single-use
    Gradle daemon, and that overhead, not the app, is most of the budget.

.PARAMETER BaselinePath
    The tracked, diffable record (repo-relative). Ratchet-only per ROADMAP.md D8 -- no ceiling,
    just an observed-value history per concept count.

.EXAMPLE
    pwsh -NoProfile -File scripts/proofs/run-scale-proof.ps1 -Concepts 26
    pwsh -NoProfile -File scripts/proofs/run-scale-proof.ps1 -Concepts 100
#>
param(
    [Parameter(Mandatory = $true)][int]$Concepts,
    [string]$WorkspaceRoot = "",
    [int]$Port = 0,
    [int]$BootTimeoutSeconds = 300,
    [string]$BaselinePath = "",
    [string]$ReportPath = ""
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
    param([string]$Status, [long]$DurationMs, [string]$Message = "")
    return [ordered]@{ status = $Status; durationMs = [int]$DurationMs; message = $Message }
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

function Get-DescendantProcessIds {
    param([int]$RootProcessId)
    $allProcesses = @(Get-CimInstance Win32_Process)
    $pending = [System.Collections.Generic.Queue[int]]::new()
    $descendants = [System.Collections.Generic.List[int]]::new()
    $pending.Enqueue($RootProcessId)
    while ($pending.Count -gt 0) {
        $parentId = $pending.Dequeue()
        foreach ($child in @($allProcesses | Where-Object { $_.ParentProcessId -eq $parentId })) {
            $childId = [int]$child.ProcessId
            $descendants.Add($childId) | Out-Null
            $pending.Enqueue($childId)
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
    if (-not $buildOk) { $overallFailed = $true }
}

# -- Phase: boot + firstRequest ------------------------------------------------------------------
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
    try {
        $process = Start-Process -FilePath $appGradlew -ArgumentList $bootArgs -WorkingDirectory $appRoot -NoNewWindow -PassThru -RedirectStandardOutput $bootStdout -RedirectStandardError $bootStderr
        $healthUri = "http://127.0.0.1:$Port/actuator/health"
        $deadline = (Get-Date).AddSeconds($BootTimeoutSeconds)
        while ((Get-Date) -lt $deadline) {
            if ($process.HasExited) { break }
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
                # REST endpoint here is behind RuntimeApiKeyAuthFilter, dev profile's key is "dev-key".
                $panelResponse = Invoke-WebRequest -Uri $panelUri -TimeoutSec 15 -SkipHttpErrorCheck -Headers @{ "X-NPDEV-API-Key" = "dev-key"; "X-API-Key" = "dev-key" }
                $firstRequestOk = [int]$panelResponse.StatusCode -eq 200
                $firstRequestDetail = "status " + [int]$panelResponse.StatusCode + ": " + (Convert-ResponseContentToString $panelResponse.Content)
            }
            catch {
                $firstRequestOk = $false
                $firstRequestDetail = $_.Exception.Message
            }
            $firstSw.Stop()
            $firstRequestMs = $firstSw.ElapsedMilliseconds
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
    }
    else {
        $phases.firstRequest = New-Phase -Status ($(if ($firstRequestOk) { "passed" } else { "failed" })) -DurationMs $firstRequestMs -Message ($panelUri + " -- " + $firstRequestDetail)
        if (-not $firstRequestOk) { $overallFailed = $true }
    }
}
else {
    $phases.build = if ($phases.Contains("build")) { $phases.build } else { New-Phase -Status "skipped" -DurationMs 0 }
    $phases.boot = New-Phase -Status "skipped" -DurationMs 0
    $phases.firstRequest = New-Phase -Status "skipped" -DurationMs 0
}

# -- Report ---------------------------------------------------------------------------------------
$report = [ordered]@{
    schemaVersion = "npdev-scale-proof-report.v1"
    generatedAt = (Get-Date).ToUniversalTime().ToString("o")
    concepts = $Concepts
    status = if ($overallFailed) { "failed" } else { "passed" }
    phases = $phases
}
Write-NPDevJsonFile $ReportPath $report

# -- Baseline (tracked, in-repo, ratchet-only per ROADMAP.md D8 -- no ceiling asserted here) ------
$baseline = if (Test-Path -LiteralPath $BaselinePath) {
    Get-Content -Raw -LiteralPath $BaselinePath | ConvertFrom-Json -AsHashtable
}
else {
    [ordered]@{ schemaVersion = "npdev-scale-proof-baseline.v1"; runs = [ordered]@{} }
}
if (-not $baseline.Contains("runs")) { $baseline["runs"] = [ordered]@{} }
$key = [string]$Concepts
$history = if ($baseline["runs"].Contains($key)) { @($baseline["runs"][$key]) } else { @() }
# The unary comma is load-bearing: `$array += $orderedDictionary` enumerates the dictionary's
# entries into the array instead of appending it as one element (a documented PowerShell pitfall
# once the dictionary is non-trivial/nested) -- `,$report` forces it to be treated as a single item.
$history += ,$report
$baseline["runs"][$key] = $history
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
