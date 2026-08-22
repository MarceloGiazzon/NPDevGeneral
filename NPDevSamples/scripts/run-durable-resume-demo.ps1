param(
    [string]$SampleId = "durable-workflow-demo",
    [string]$NPDevRoot = "",
    [int]$ResumePollTimeoutSeconds = 90
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

. (Join-Path $PSScriptRoot "sample-common.ps1")

# CORE C-3 (docs/EXECUTION_TREES.md, docs/POST_PUBLIC_PLAN.md P4.2): proves a flow parked on
# awaitEvent survives a REAL process restart and resumes to completion from persisted state alone --
# not just an in-process resume() call. See NPDevSamples/durable-workflow-demo/Input/README.md for
# the full explanation of what this demonstrates and why the sample is built the way it is.

$samplesRoot = Get-NPDevSamplesRoot -ScriptRoot $PSScriptRoot
if ([string]::IsNullOrWhiteSpace($NPDevRoot)) {
    $NPDevRoot = Get-NPDevWorkspaceRoot -SamplesRoot $samplesRoot
}
$NPDevRoot = Normalize-AbsolutePath $NPDevRoot

# Output/ is gitignored (NPDevSamples/**/Output/) and created on demand -- Resolve-NPDevSample
# requires it to already exist, and generate-sample-app.ps1 only creates it for itself, not for us.
New-Item -ItemType Directory -Force -Path (Join-Path (Join-Path $samplesRoot $SampleId) "Output") | Out-Null

$sample = Resolve-NPDevSample -SamplesRoot $samplesRoot -SampleId $SampleId
$config = Read-SampleConfig -Sample $sample
$port = Get-ConfigInt -Config $config -Path @("runtime", "serverPort") -Fallback 8097
$profiles = Get-ConfigString -Config $config -Path @("runtime", "springProfile") -Fallback "dev,trial"
$baseUrl = "http://localhost:$port"
# R7 Stage D: resolved below, once the app has been generated (Get-NpdevLiveApiKey needs
# _ops/resolved-db-plan.json, which generate-sample-app.ps1 has not written yet at this point).
# This script boots the app itself (raw java -jar), so a hardcoded "dev-key" is no longer a safe
# assumption -- ask what actually authenticates instead. Stale note removed 2026-08-15: this DOES
# call Ensure-NpdevSampleApiKey (below, once $appRoot exists) before that boot -- see T1/C2.
$apiKey = $null

$logDir = Join-Path $sample.OutputRoot "RunOutput"
New-Item -ItemType Directory -Force -Path $logDir | Out-Null
$log1 = Join-Path $logDir "boot-1.log"
$log2 = Join-Path $logDir "boot-2.log"

function Wait-ForAppReady([string]$LogPath, [int]$TimeoutSeconds) {
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        try {
            $resp = Invoke-RestMethod -Uri "$baseUrl/api/flows" -Headers @{ "X-Api-Key" = $apiKey } -TimeoutSec 3
            if ($resp) { return $true }
        }
        catch {
            # not up yet
        }
        Start-Sleep -Seconds 2
    }
    Write-Host "----- last 60 lines of $LogPath -----"
    if (Test-Path -LiteralPath $LogPath) { Get-Content -LiteralPath $LogPath -Tail 60 }
    Fail "App did not become ready within $TimeoutSeconds s (see log above: $LogPath)"
}

Info "=== Step -1: clean up any leftover process from a previous run on this port ==="
Get-CimInstance Win32_Process -Filter "Name='java.exe'" -ErrorAction SilentlyContinue |
    Where-Object { $_.CommandLine -and $_.CommandLine.Contains("FinalExec-") -and $_.CommandLine.Contains("server.port=$port") } |
    ForEach-Object {
        Info ("Killing leftover PID " + $_.ProcessId + " from a previous run.")
        Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue
    }
Start-Sleep -Seconds 1

Info "=== Step 0: generate + build ==="
& (Join-Path $PSScriptRoot "generate-sample-app.ps1") -SampleId $SampleId
if ($LASTEXITCODE -ne 0 -and $null -ne $LASTEXITCODE) { Fail "Sample generation failed" }

$appRoot = $sample.AppRoot
# T1/C2: application-dev.yml no longer seeds a known key, so provision one before this script's
# own raw java boot below -- Start-Process inherits this session's environment.
Ensure-NpdevSampleApiKey -AppRoot $appRoot
$apiKey = Get-NpdevLiveApiKey -AppRoot $appRoot
$gradlew = Get-NPDevGradleWrapperExecutable $appRoot
Ensure-File -PathValue $gradlew -Label "Generated app Gradle wrapper"

Push-Location $appRoot
try {
    Info "Building bootJar (once; both boots below reuse this jar) ..."
    & $gradlew --no-daemon bootJar
    if ($LASTEXITCODE -ne 0) { Fail "bootJar failed with exit code $LASTEXITCODE" }
}
finally {
    Pop-Location
}

$jar = Get-ChildItem -Path (Join-Path $appRoot "build\libs") -Filter "*.jar" |
    Where-Object { $_.Name -notlike "*-plain.jar" } | Select-Object -First 1
Ensure-File -PathValue $jar.FullName -Label "Built bootJar"
Info ("Jar: " + $jar.FullName)

function Start-DemoApp([string]$LogPath) {
    $psi = Start-Process -FilePath "java" `
        -ArgumentList @("-jar", $jar.FullName, "--spring.profiles.active=$profiles", "--server.port=$port") `
        -WorkingDirectory $appRoot `
        -RedirectStandardOutput $LogPath `
        -RedirectStandardError ($LogPath + ".err") `
        -PassThru -WindowStyle Hidden
    return $psi
}

Info "=== Step 1: boot #1 ==="
$proc1 = Start-DemoApp -LogPath $log1
Info ("Started PID " + $proc1.Id + ", waiting for readiness ...")
Wait-ForAppReady -LogPath $log1 -TimeoutSeconds 90
Ok ("Boot #1 ready (PID " + $proc1.Id + ")")

Info "=== Step 2: submit the expense (expect it to park on awaitEvent) ==="
$submitPayload = Get-Content -LiteralPath (Join-Path $sample.InputRoot "Requests\submit-expense.json") -Raw
$submitResponse = Invoke-RestMethod -Uri "$baseUrl/api/flows/SubmitExpense/execute" `
    -Method Post -ContentType "application/json" -Headers @{ "X-Api-Key" = $apiKey } -Body $submitPayload

$executionId = $submitResponse.executionId
$correlationId = $submitResponse.correlationId
$status = $submitResponse.status
Info ("executionId=" + $executionId + " correlationId=" + $correlationId + " status=" + $status)
if ($status -ne "WAITING_EVENT") {
    Fail ("Expected status WAITING_EVENT after submit, got: " + $status + " (full response: " + ($submitResponse | ConvertTo-Json -Depth 10) + ")")
}
Ok "Flow parked on awaitEvent, as expected."

Info "=== Step 3: KILL the process (not a graceful shutdown) ==="
# REG-57 (docs/NPDEV_OPEN_ITEMS_REGISTER.md): FIXED 2026-07-28. H2's MVStore defaulted to a 500ms
# WRITE_DELAY, buffering committed writes before flushing to disk -- a hard kill inside that window
# could lose however many commits landed since the last flush, even though the JDBC call had already
# returned and the caller was already told WAITING_EVENT. Root-caused (not ordering -- traced the
# full synchronous call chain from KernelRunner's WAITING_EVENT branch to the HTTP response, no
# thread hop exists) and fixed by adding WRITE_DELAY=0 to the H2 JDBC URL
# (UserDatabaseDefinitionLoader.jdbcUrl). No more deliberate delay needed before the kill -- this is
# the whole point of the fix.
Stop-Process -Id $proc1.Id -Force
Start-Sleep -Seconds 2
$stillRunning = Get-Process -Id $proc1.Id -ErrorAction SilentlyContinue
if ($stillRunning) { Fail ("PID " + $proc1.Id + " is still running after Stop-Process -Force") }
Ok ("PID " + $proc1.Id + " confirmed killed.")

Info "=== Step 4: restart a NEW process from the SAME on-disk database ==="
$proc2 = Start-DemoApp -LogPath $log2
Info ("Started PID " + $proc2.Id + ", waiting for readiness ...")
Wait-ForAppReady -LogPath $log2 -TimeoutSeconds 90
Ok ("Boot #2 ready (PID " + $proc2.Id + ") -- this is a genuinely new JVM, not the same process resumed.")

Info "=== Step 5: publish the approval event ==="
# `output` is null while WAITING_EVENT (the flow hasn't returned yet) -- the generated concept's
# id comes from the ExpenseSubmitted event this same execution already emitted before parking.
$expenseId = $submitResponse.emittedEvents | Where-Object { $_.eventName -eq "ExpenseSubmitted" } | Select-Object -First 1 -ExpandProperty payload | Select-Object -ExpandProperty id
if ([string]::IsNullOrWhiteSpace($expenseId)) { Fail "Could not find the generated expense id in submitResponse.emittedEvents" }
Info ("expenseId=" + $expenseId)
$publishPayload = [ordered]@{
    eventName = "ExpenseApproved"
    correlationId = $correlationId
    payload = [ordered]@{
        expenseId = $expenseId
        approved = $true
        reviewer = "demo-manager"
    }
} | ConvertTo-Json -Depth 10
Invoke-RestMethod -Uri "$baseUrl/api/events/publish" -Method Post -ContentType "application/json" `
    -Headers @{ "X-Api-Key" = $apiKey } -Body $publishPayload | Out-Null
Ok "Approval event published."

Info "=== Step 6: poll boot #2's log for the SAME execution completing ==="
$deadline = (Get-Date).AddSeconds($ResumePollTimeoutSeconds)
$found = $false
while ((Get-Date) -lt $deadline) {
    if (Test-Path -LiteralPath $log2) {
        $matches = Select-String -LiteralPath $log2 -Pattern ([regex]::Escape('"executionId":"' + $executionId + '"')) -SimpleMatch:$false -ErrorAction SilentlyContinue
        foreach ($m in $matches) {
            if ($m.Line -match '"status":"OK"') {
                $found = $true
                Info ("Found outcome line: " + $m.Line.Trim())
                break
            }
        }
    }
    if ($found) { break }
    Start-Sleep -Seconds 3
}

Stop-Process -Id $proc2.Id -Force -ErrorAction SilentlyContinue

if (-not $found) {
    Write-Host "----- last 80 lines of $log2 -----"
    Get-Content -LiteralPath $log2 -Tail 80
    Fail ("Did not observe executionId " + $executionId + " reach status OK within " + $ResumePollTimeoutSeconds + "s after restart+publish.")
}

Ok "================================================================"
Ok " DURABLE RESUME CONFIRMED"
Ok "   executionId  : $executionId"
Ok "   correlationId: $correlationId"
Ok "   Timeline: submit (boot #1, PID $($proc1.Id)) -> WAITING_EVENT -> process KILLED ->"
Ok "             new process booted (PID $($proc2.Id)) -> approval published -> SAME"
Ok "             execution resumed from persisted state and reached status OK."
Ok "================================================================"
