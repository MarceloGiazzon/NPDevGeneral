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
$apiKey = "dev-key"

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
# REG-57 (docs/NPDEV_OPEN_ITEMS_REGISTER.md): a hard kill landing within roughly the first second
# after the WAITING_EVENT response can catch the on-disk H2Local database before that checkpoint
# is physically durable -- empirically confirmed (3/3 failures with ~0s delay, 1/1 clean with 5s).
# Root cause not yet traced to a specific layer (H2's own file engine is the leading suspect; see
# the filing). This delay is a deliberate workaround so the demo exercises the INTENDED durable-
# resume path rather than this separate, real, filed gap -- it is not padding for its own sake.
Start-Sleep -Seconds 5
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
