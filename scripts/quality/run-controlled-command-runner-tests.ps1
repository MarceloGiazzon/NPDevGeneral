param(
    [string]$RunId = ""
)

$ErrorActionPreference = "Stop"

$workspaceRoot = (Resolve-Path ".").Path
if ([string]::IsNullOrWhiteSpace($RunId)) {
    $RunId = "controlled-command-runner-tests-" + (Get-Date).ToUniversalTime().ToString("yyyyMMdd-HHmmssfff")
}
$testRoot = Join-Path $workspaceRoot "scripts/reports/tmp/controlled-command-runner-tests"
if (Test-Path -LiteralPath $testRoot) {
    Remove-Item -LiteralPath $testRoot -Recurse -Force
}
New-Item -ItemType Directory -Force -Path $testRoot | Out-Null

function Invoke-Runner {
    param(
        [string]$Name,
        [string]$Executable,
        [string[]]$Arguments,
        [int]$TimeoutSeconds = 5,
        [string]$WorkingDirectory = "."
    )
    $resultPath = Join-Path $testRoot ($Name + ".json")
    $argumentsJson = @($Arguments) | ConvertTo-Json -Compress
    $ErrorActionPreference = "Continue"
    pwsh -NoProfile -File scripts/security/Invoke-ControlledCommand.ps1 `
        -Executable $Executable `
        -ArgumentsJson $argumentsJson `
        -WorkingDirectory $WorkingDirectory `
        -TimeoutSeconds $TimeoutSeconds `
        -ResultPath $resultPath 2>$null | Out-Null
    $exitCode = $LASTEXITCODE
    $ErrorActionPreference = "Stop"
    if (-not (Test-Path -LiteralPath $resultPath -PathType Leaf)) {
        throw "Runner did not write result for $Name."
    }
    $result = Get-Content -Raw -LiteralPath $resultPath | ConvertFrom-Json
    return [pscustomobject]@{
        exitCode = $exitCode
        result = $result
    }
}

function Invoke-StructuredRunner {
    param(
        [string]$Name,
        [object]$Request
    )
    $requestPath = Join-Path $testRoot ($Name + "-request.json")
    $resultPath = Join-Path $testRoot ($Name + ".json")
    $Request | ConvertTo-Json -Depth 20 | Set-Content -LiteralPath $requestPath -Encoding UTF8
    $ErrorActionPreference = "Continue"
    pwsh -NoProfile -File scripts/security/Invoke-StructuredCommandRequest.ps1 `
        -RequestPath $requestPath `
        -ResultPath $resultPath 2>$null | Out-Null
    $exitCode = $LASTEXITCODE
    $ErrorActionPreference = "Stop"
    if (-not (Test-Path -LiteralPath $resultPath -PathType Leaf)) {
        throw "Structured runner did not write result for $Name."
    }
    $result = Get-Content -Raw -LiteralPath $resultPath | ConvertFrom-Json
    return [pscustomobject]@{
        exitCode = $exitCode
        result = $result
    }
}

$allowed = Invoke-Runner -Name "allowed" -Executable "pwsh" -Arguments @("-NoProfile", "-File", "scripts/tests/fixtures/controlled-runner/allowed-build.ps1")
if ($allowed.exitCode -ne 0 -or $allowed.result.status -ne "passed" -or $allowed.result.stdout -notmatch "BUILD_OK") {
    throw "Allowed command did not pass."
}
if ($allowed.result.stdout -match "fake-secret-123" -or $allowed.result.stdout -notmatch "\[REDACTED\]") {
    throw "Allowed command output was not redacted."
}

$curl = Invoke-Runner -Name "curl-blocked" -Executable "curl" -Arguments @("https://example.com")
if ($curl.result.status -ne "blocked" -or $curl.result.blockedReason -ne "EXECUTABLE_NOT_ALLOWED" -or $curl.result.errorCode -ne "NPDEV_COMMAND_EXECUTABLE_NOT_ALLOWED") {
    throw "curl was not blocked by executable policy."
}

$externalUrl = Invoke-Runner -Name "external-network-blocked" -Executable "node" -Arguments @("https://example.com")
if ($externalUrl.result.status -ne "blocked" -or $externalUrl.result.blockedReason -ne "NETWORK_BLOCKED" -or $externalUrl.result.errorCode -ne "NPDEV_COMMAND_NETWORK_BLOCKED") {
    throw "External network URL was not blocked by argument policy."
}

$removeItem = Invoke-Runner -Name "remove-item-blocked" -Executable "pwsh" -Arguments @("-NoProfile", "-Command", "Remove-Item -Recurse .")
if ($removeItem.result.status -ne "blocked" -or $removeItem.result.errorCode -ne "NPDEV_COMMAND_ARGUMENT_DENIED") {
    throw "PowerShell command mode/destructive argument was not blocked."
}

$outside = Invoke-Runner -Name "outside-workdir-blocked" -Executable "pwsh" -Arguments @("-NoProfile", "-File", "scripts/tests/fixtures/controlled-runner/allowed-build.ps1") -WorkingDirectory "D:\"
if ($outside.result.status -ne "blocked" -or $outside.result.blockedReason -ne "WORKING_DIRECTORY_OUTSIDE_WORKSPACE" -or $outside.result.errorCode -ne "NPDEV_COMMAND_WORKDIR_OUTSIDE_SANDBOX") {
    throw "Outside working directory was not blocked."
}

$outsideExecutable = Invoke-Runner -Name "outside-executable-blocked" -Executable "C:\Windows\System32\WindowsPowerShell\v1.0\powershell.exe" -Arguments @("-NoProfile", "-File", "scripts/tests/fixtures/controlled-runner/allowed-build.ps1")
if ($outsideExecutable.result.status -ne "blocked" -or $outsideExecutable.result.blockedReason -ne "EXECUTABLE_OUTSIDE_WORKSPACE" -or $outsideExecutable.result.errorCode -ne "NPDEV_COMMAND_EXECUTABLE_OUTSIDE_SANDBOX") {
    throw "Rooted executable outside the workspace was not blocked."
}

$traversalFile = Invoke-Runner -Name "pwsh-file-traversal-blocked" -Executable "pwsh" -Arguments @("-NoProfile", "-File", "scripts/quality/../security/Invoke-ControlledCommand.ps1")
if ($traversalFile.result.status -ne "blocked" -or $traversalFile.result.blockedReason -ne "PWSH_FILE_OUTSIDE_ALLOWED_ROOT" -or $traversalFile.result.errorCode -ne "NPDEV_COMMAND_ARGUMENT_DENIED") {
    throw "PowerShell -File path traversal into a disallowed root was not blocked."
}

$timeout = Invoke-Runner -Name "timeout" -Executable "pwsh" -Arguments @("-NoProfile", "-File", "scripts/tests/fixtures/controlled-runner/slow-build.ps1") -TimeoutSeconds 1
if ($timeout.result.status -ne "timed-out" -or -not [bool]$timeout.result.timedOut) {
    throw "Timeout command did not time out."
}

$rawNode = Invoke-StructuredRunner -Name "structured-raw-node-e-blocked" -Request ([ordered]@{
    schemaVersion = "npdev-ai-command-request.v1"
    type = "raw-command"
    executable = "node"
    arguments = @("-e", "require('fs').writeFileSync('blocked.txt','x')")
    workingDirectory = "."
})
if ($rawNode.result.status -ne "blocked" -or $rawNode.result.errorCode -ne "NPDEV_COMMAND_RAW_COMMAND_DENIED") {
    throw "Structured runner did not block raw node execution before execution."
}

$rawNpm = Invoke-StructuredRunner -Name "structured-raw-npm-blocked" -Request ([ordered]@{
    schemaVersion = "npdev-ai-command-request.v1"
    type = "raw-command"
    executable = "npm"
    arguments = @("run", "anything")
    workingDirectory = "."
})
if ($rawNpm.result.status -ne "blocked" -or $rawNpm.result.errorCode -ne "NPDEV_COMMAND_RAW_COMMAND_DENIED") {
    throw "Structured runner did not block raw npm execution before execution."
}

$rawNpx = Invoke-StructuredRunner -Name "structured-raw-npx-blocked" -Request ([ordered]@{
    schemaVersion = "npdev-ai-command-request.v1"
    type = "raw-command"
    executable = "npx"
    arguments = @("arbitrary-package")
    workingDirectory = "."
})
if ($rawNpx.result.status -ne "blocked" -or $rawNpx.result.errorCode -ne "NPDEV_COMMAND_EXECUTABLE_NOT_ALLOWED") {
    throw "Structured runner did not block raw npx execution before execution."
}

$rawEncodedPwsh = Invoke-StructuredRunner -Name "structured-raw-encoded-pwsh-blocked" -Request ([ordered]@{
    schemaVersion = "npdev-ai-command-request.v1"
    type = "raw-command"
    executable = "pwsh"
    arguments = @("-EncodedCommand", "SQBFAFgA")
    workingDirectory = "."
})
if ($rawEncodedPwsh.result.status -ne "blocked" -or $rawEncodedPwsh.result.errorCode -ne "NPDEV_COMMAND_ARGUMENT_DENIED") {
    throw "Structured runner did not block encoded PowerShell command mode."
}

$rawCmd = Invoke-StructuredRunner -Name "structured-raw-cmd-blocked" -Request ([ordered]@{
    schemaVersion = "npdev-ai-command-request.v1"
    type = "raw-command"
    executable = "cmd"
    arguments = @("/c", "echo unsafe")
    workingDirectory = "."
})
if ($rawCmd.result.status -ne "blocked" -or $rawCmd.result.errorCode -ne "NPDEV_COMMAND_EXECUTABLE_NOT_ALLOWED") {
    throw "Structured runner did not block raw cmd execution."
}

$futureGenerateApp = Invoke-StructuredRunner -Name "structured-future-generate-app-schema-invalid" -Request ([ordered]@{
    schemaVersion = "npdev-ai-command-request.v1"
    type = "generate-app"
    description = "Future request type must not be part of the release command surface yet."
})
if ($futureGenerateApp.result.status -ne "blocked" -or $futureGenerateApp.result.errorCode -ne "NPDEV_COMMAND_REQUEST_SCHEMA_INVALID") {
    throw "Structured runner did not reject future generate-app request type at schema validation."
}

$trustedScript = Invoke-StructuredRunner -Name "structured-trusted-script-schema-invalid" -Request ([ordered]@{
    schemaVersion = "npdev-ai-command-request.v1"
    type = "trusted-script"
    script = "scripts/quality/invoke-ai-beta-app-smoke.ps1"
})
if ($trustedScript.result.status -ne "blocked" -or $trustedScript.result.errorCode -ne "NPDEV_COMMAND_REQUEST_SCHEMA_INVALID") {
    throw "Structured runner did not reject trusted-script before its design checkpoint."
}

$structuredSchema = Invoke-StructuredRunner -Name "structured-schema-validation-allowed" -Request ([ordered]@{
    schemaVersion = "npdev-ai-command-request.v1"
    type = "schema-validation"
    schemaPath = "NPDevContract/schemas/model.schema.json"
    instancePath = "scripts/tests/fixtures/schema-validation/official-model-valid.json"
    timeoutSeconds = 30
})
if ($structuredSchema.exitCode -ne 0 -or $structuredSchema.result.status -ne "passed") {
    throw "Structured schema-validation request did not pass through the controlled runner."
}

$structuredGradle = Invoke-StructuredRunner -Name "structured-gradle-task-allowed" -Request ([ordered]@{
    schemaVersion = "npdev-ai-command-request.v1"
    type = "gradle-task"
    projectRoot = "NPDevGenerator"
    task = "tasks"
    timeoutSeconds = 120
})
if ($structuredGradle.exitCode -ne 0 -or $structuredGradle.result.status -ne "passed") {
    throw "Structured gradle-task request did not pass through the controlled runner."
}

$restPort = 18182
$readyPath = Join-Path $testRoot "structured-rest-smoke.ready"
$server = Start-Process -FilePath "pwsh" -ArgumentList @("-NoProfile", "-File", "scripts/tests/fixtures/controlled-runner/rest-smoke-fixture-server.ps1", "-Port", [string]$restPort, "-ReadyPath", $readyPath) -WorkingDirectory $workspaceRoot -PassThru -WindowStyle Hidden
try {
    $deadline = (Get-Date).AddSeconds(10)
    while (-not (Test-Path -LiteralPath $readyPath -PathType Leaf)) {
        if ((Get-Date) -gt $deadline) { throw "Structured REST smoke fixture server did not become ready." }
        Start-Sleep -Milliseconds 100
    }
    $structuredRestSmoke = Invoke-StructuredRunner -Name "structured-rest-smoke-allowed" -Request ([ordered]@{
        schemaVersion = "npdev-ai-command-request.v1"
        type = "rest-smoke"
        verificationPath = "golden-ai-scenarios/base-ai-loop/ai-verification-report.json"
        baseUrl = "http://127.0.0.1:$restPort"
        expectedPort = $restPort
        timeoutSeconds = 60
    })
    if ($structuredRestSmoke.exitCode -ne 0 -or $structuredRestSmoke.result.status -ne "passed") {
        throw "Structured rest-smoke request did not pass through the controlled runner."
    }
}
finally {
    if ($null -ne $server -and -not $server.HasExited) {
        Stop-Process -Id $server.Id -Force
        $server.WaitForExit()
    }
}

$report = [pscustomobject]@{
    schemaVersion = "npdev-controlled-command-runner-test-report.v1"
    runId = $RunId
    generatedAt = (Get-Date).ToUniversalTime().ToString("o")
    scriptPath = "scripts/quality/run-controlled-command-runner-tests.ps1"
    workspaceRoot = $workspaceRoot
    overallStatus = "passed"
    assertions = @(
        "allowed command executes",
        "stdout secrets are redacted",
        "external curl is blocked",
        "external network URL arguments are blocked",
        "destructive PowerShell command mode is blocked",
        "working directory sandbox is enforced",
        "rooted executable sandbox is enforced",
        "PowerShell file-root traversal is blocked",
        "timeout is enforced",
        "structured runner blocks raw node execution before execution",
        "structured runner blocks raw npm execution before execution",
        "structured runner blocks raw npx execution before execution",
        "structured runner blocks encoded PowerShell command mode",
        "structured runner blocks raw cmd execution",
        "structured runner rejects future generate-app request type at schema validation",
        "structured runner rejects trusted-script before its design checkpoint",
        "structured runner executes typed schema-validation requests",
        "structured runner executes typed gradle-task requests",
        "structured runner executes typed rest-smoke requests"
    )
}
$reportPath = "scripts/reports/out/controlled-command-runner-tests-report.json"
New-Item -ItemType Directory -Force -Path (Split-Path -Parent $reportPath) | Out-Null
$report | ConvertTo-Json -Depth 20 | Set-Content -LiteralPath $reportPath -Encoding UTF8
Write-Host ("Controlled command runner tests passed. Report: " + $reportPath)
