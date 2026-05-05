param(
    [string]$RunId = ""
)

$ErrorActionPreference = "Stop"

$workspaceRoot = (Resolve-Path ".").Path
if ([string]::IsNullOrWhiteSpace($RunId)) {
    $RunId = "ai-rest-smoke-verifier-tests-" + (Get-Date).ToUniversalTime().ToString("yyyyMMdd-HHmmssfff")
}
$testRoot = Join-Path $workspaceRoot "scripts/reports/tmp/ai-rest-smoke-verifier-tests"
if (Test-Path -LiteralPath $testRoot) {
    Remove-Item -LiteralPath $testRoot -Recurse -Force
}
New-Item -ItemType Directory -Force -Path $testRoot | Out-Null

$port = 18181
$readyPath = Join-Path $testRoot "server.ready"
$server = Start-Process -FilePath "pwsh" -ArgumentList @("-NoProfile", "-File", "scripts/tests/fixtures/controlled-runner/rest-smoke-fixture-server.ps1", "-Port", [string]$port, "-ReadyPath", $readyPath) -WorkingDirectory $workspaceRoot -PassThru -WindowStyle Hidden

try {
    $deadline = (Get-Date).AddSeconds(10)
    while (-not (Test-Path -LiteralPath $readyPath -PathType Leaf)) {
        if ((Get-Date) -gt $deadline) {
            throw "REST smoke fixture server did not become ready."
        }
        Start-Sleep -Milliseconds 100
    }

    $baseUrl = "http://127.0.0.1:$port"
    $positiveReportPath = Join-Path $testRoot "positive-result.json"
    pwsh -NoProfile -File scripts/ai/Invoke-AiRestSmokeVerifier.ps1 `
        -VerificationPath golden-ai-scenarios/base-ai-loop/ai-verification-report.json `
        -BaseUrl $baseUrl `
        -ReportPath $positiveReportPath | Out-Null
    if ($LASTEXITCODE -ne 0) {
        throw "Positive REST smoke verifier run failed."
    }
    $positiveResult = Get-Content -Raw -LiteralPath $positiveReportPath | ConvertFrom-Json
    if ($positiveResult.status -ne "passed" -or @($positiveResult.checks | Where-Object { $_.status -ne "passed" }).Count -gt 0) {
        throw "Positive REST smoke verifier result did not pass all checks."
    }
    if (@($positiveResult.checks | Where-Object { [string]::IsNullOrWhiteSpace([string]$_.responseBodySha256) -or $null -eq $_.assertions }).Count -gt 0) {
        throw "Positive REST smoke verifier result did not include observed response evidence."
    }

    $coveragePlan = [ordered]@{
        schemaVersion = "ai-verification-report.v1"
        scenarioId = "surface-coverage"
        baseUrlVariable = "NPDEV_GENERATED_APP_BASE_URL"
        requiredSurfaceCoverage = @("authentication", "rest-smoke")
        checks = @(
            [ordered]@{
                id = "health-ok"
                type = "http"
                method = "GET"
                path = "/actuator/health"
                expectedStatus = 200
                expectedJsonContains = @{ status = "UP" }
                coversSurfaces = @("rest-smoke")
            },
            [ordered]@{
                id = "create-user-auth-surface"
                type = "http"
                method = "POST"
                path = "/api/users"
                headers = @{ "X-Api-Key" = "dev-key" }
                body = @{
                    email = "surface@example.com"
                    displayName = "Surface Coverage"
                }
                expectedStatus = 201
                coversSurfaces = @("authentication")
            }
        )
    }
    $coveragePlanPath = Join-Path $testRoot "coverage-plan.json"
    $coveragePlan | ConvertTo-Json -Depth 20 | Set-Content -LiteralPath $coveragePlanPath -Encoding UTF8
    $coverageReportPath = Join-Path $testRoot "coverage-result.json"
    pwsh -NoProfile -File scripts/ai/Invoke-AiRestSmokeVerifier.ps1 `
        -VerificationPath $coveragePlanPath `
        -BaseUrl $baseUrl `
        -ReportPath $coverageReportPath | Out-Null
    if ($LASTEXITCODE -ne 0) {
        throw "Surface coverage REST smoke verifier run failed."
    }
    $coverageResult = Get-Content -Raw -LiteralPath $coverageReportPath | ConvertFrom-Json
    if ($coverageResult.surfaceCoverage.status -ne "passed" -or @($coverageResult.surfaceCoverage.covered).Count -lt 2) {
        throw "Surface coverage was not recorded for passing checks."
    }

    $missingCoveragePlan = [ordered]@{
        schemaVersion = "ai-verification-report.v1"
        scenarioId = "missing-surface-coverage"
        baseUrlVariable = "NPDEV_GENERATED_APP_BASE_URL"
        requiredSurfaceCoverage = @("custom-ui-panels")
        checks = @(
            [ordered]@{
                id = "health-ok"
                type = "http"
                method = "GET"
                path = "/actuator/health"
                expectedStatus = 200
            },
            [ordered]@{
                id = "create-user-ok"
                type = "http"
                method = "POST"
                path = "/api/users"
                headers = @{ "X-Api-Key" = "dev-key" }
                body = @{
                    email = "missing-coverage@example.com"
                    displayName = "Missing Coverage"
                }
                expectedStatus = 201
            }
        )
    }
    $missingCoveragePlanPath = Join-Path $testRoot "missing-coverage-plan.json"
    $missingCoveragePlan | ConvertTo-Json -Depth 20 | Set-Content -LiteralPath $missingCoveragePlanPath -Encoding UTF8
    $missingCoverageReportPath = Join-Path $testRoot "missing-coverage-result.json"
    $ErrorActionPreference = "Continue"
    pwsh -NoProfile -File scripts/ai/Invoke-AiRestSmokeVerifier.ps1 `
        -VerificationPath $missingCoveragePlanPath `
        -BaseUrl $baseUrl `
        -ReportPath $missingCoverageReportPath 2>$null | Out-Null
    $missingCoverageExit = $LASTEXITCODE
    $ErrorActionPreference = "Stop"
    if ($missingCoverageExit -eq 0) {
        throw "Missing required surface coverage was not rejected."
    }
    $missingCoverageResult = Get-Content -Raw -LiteralPath $missingCoverageReportPath | ConvertFrom-Json
    if ($missingCoverageResult.surfaceCoverage.status -ne "failed" -or @($missingCoverageResult.surfaceCoverage.missing) -notcontains "custom-ui-panels") {
        throw "Missing surface coverage was not reported."
    }

    $positiveSchemaReportPath = Join-Path $testRoot "positive-result-schema-validation.json"
    pwsh -NoProfile -File scripts/quality/Invoke-JsonSchemaValidation.ps1 `
        -SchemaPath schemas/ai/ai-rest-smoke-result.schema.json `
        -JsonPath $positiveReportPath `
        -ReportPath $positiveSchemaReportPath | Out-Null
    if ($LASTEXITCODE -ne 0) {
        throw "Positive REST smoke verifier result failed result schema validation."
    }

    $negativeReportPath = Join-Path $testRoot "negative-result.json"
    $ErrorActionPreference = "Continue"
    pwsh -NoProfile -File scripts/ai/Invoke-AiRestSmokeVerifier.ps1 `
        -VerificationPath golden-ai-scenarios/behavior-mismatch/ai-verification-report.json `
        -BaseUrl $baseUrl `
        -ReportPath $negativeReportPath 2>$null | Out-Null
    $negativeExit = $LASTEXITCODE
    $ErrorActionPreference = "Stop"
    if ($negativeExit -eq 0) {
        throw "Negative REST smoke verifier run unexpectedly passed."
    }
    $negativeResult = Get-Content -Raw -LiteralPath $negativeReportPath | ConvertFrom-Json
    if ($negativeResult.status -ne "failed") {
        throw "Negative REST smoke verifier result did not fail."
    }
    $failedChecks = @($negativeResult.checks | Where-Object { $_.status -eq "failed" })
    if ($failedChecks.Count -lt 1 -or ($failedChecks[0].failures -join "`n") -notmatch "JSON mismatch") {
        throw "Negative REST smoke verifier did not report the expected JSON mismatch."
    }

    $badPathPlan = [ordered]@{
        schemaVersion = "ai-verification-report.v1"
        scenarioId = "external-url-rejection"
        baseUrlVariable = "NPDEV_GENERATED_APP_BASE_URL"
        checks = @(
            [ordered]@{
                id = "health-ok"
                type = "http"
                method = "GET"
                path = "/actuator/health"
                expectedStatus = 200
            },
            [ordered]@{
                id = "external-url"
                type = "http"
                method = "GET"
                path = "https://example.com/"
                expectedStatus = 200
            }
        )
    }
    $badPathPlanPath = Join-Path $testRoot "bad-path-plan.json"
    $badPathPlan | ConvertTo-Json -Depth 20 | Set-Content -LiteralPath $badPathPlanPath -Encoding UTF8
    $badPathReportPath = Join-Path $testRoot "bad-path-result.json"
    $ErrorActionPreference = "Continue"
    pwsh -NoProfile -File scripts/ai/Invoke-AiRestSmokeVerifier.ps1 `
        -VerificationPath $badPathPlanPath `
        -BaseUrl $baseUrl `
        -ReportPath $badPathReportPath 2>$null | Out-Null
    $badPathExit = $LASTEXITCODE
    $ErrorActionPreference = "Stop"
    if ($badPathExit -eq 0) {
        throw "External URL path check unexpectedly passed."
    }
    if (Test-Path -LiteralPath $badPathReportPath -PathType Leaf) {
        $badPathResult = Get-Content -Raw -LiteralPath $badPathReportPath | ConvertFrom-Json
        if (($badPathResult.checks.failures -join "`n") -notmatch "local absolute path") {
            throw "External URL path was not rejected with a local-path failure."
        }
    }

    $wrongStatusPlan = [ordered]@{
        schemaVersion = "ai-verification-report.v1"
        scenarioId = "wrong-status-rejection"
        baseUrlVariable = "NPDEV_GENERATED_APP_BASE_URL"
        checks = @(
            [ordered]@{
                id = "health-wrong-status"
                type = "http"
                method = "GET"
                path = "/actuator/health"
                expectedStatus = 418
            },
            [ordered]@{
                id = "not-found"
                type = "http"
                method = "GET"
                path = "/missing"
                expectedStatus = 404
            }
        )
    }
    $wrongStatusPlanPath = Join-Path $testRoot "wrong-status-plan.json"
    $wrongStatusPlan | ConvertTo-Json -Depth 20 | Set-Content -LiteralPath $wrongStatusPlanPath -Encoding UTF8
    $wrongStatusReportPath = Join-Path $testRoot "wrong-status-result.json"
    $ErrorActionPreference = "Continue"
    pwsh -NoProfile -File scripts/ai/Invoke-AiRestSmokeVerifier.ps1 `
        -VerificationPath $wrongStatusPlanPath `
        -BaseUrl $baseUrl `
        -ReportPath $wrongStatusReportPath 2>$null | Out-Null
    $wrongStatusExit = $LASTEXITCODE
    $ErrorActionPreference = "Stop"
    if ($wrongStatusExit -eq 0) {
        throw "Wrong-status REST smoke verifier run unexpectedly passed."
    }
    $wrongStatusResult = Get-Content -Raw -LiteralPath $wrongStatusReportPath | ConvertFrom-Json
    if (($wrongStatusResult.checks.failures -join "`n") -notmatch "Expected HTTP status 418") {
        throw "Wrong-status REST smoke verifier run did not expose the status mismatch."
    }

    $ErrorActionPreference = "Continue"
    pwsh -NoProfile -File scripts/ai/Invoke-AiRestSmokeVerifier.ps1 `
        -VerificationPath golden-ai-scenarios/base-ai-loop/ai-verification-report.json `
        -BaseUrl $baseUrl `
        -ExpectedPort ($port + 1) `
        -ReportPath (Join-Path $testRoot "wrong-port-result.json") 2>$null | Out-Null
    $wrongPortExit = $LASTEXITCODE
    $ErrorActionPreference = "Stop"
    if ($wrongPortExit -eq 0) {
        throw "Wrong generated app port was not rejected."
    }

    $malformedPlan = [ordered]@{
        schemaVersion = "ai-verification-report.v1"
        scenarioId = "malformed-plan"
        baseUrlVariable = "NPDEV_GENERATED_APP_BASE_URL"
        checks = @(
            [ordered]@{
                id = "only-one-check"
                type = "http"
                method = "GET"
                path = "relative-health"
                expectedStatus = 200
            }
        )
    }
    $malformedPlanPath = Join-Path $testRoot "malformed-plan.json"
    $malformedPlan | ConvertTo-Json -Depth 20 | Set-Content -LiteralPath $malformedPlanPath -Encoding UTF8
    $ErrorActionPreference = "Continue"
    pwsh -NoProfile -File scripts/ai/Invoke-AiRestSmokeVerifier.ps1 `
        -VerificationPath $malformedPlanPath `
        -BaseUrl $baseUrl `
        -ReportPath (Join-Path $testRoot "malformed-plan-result.json") 2>$null | Out-Null
    $malformedPlanExit = $LASTEXITCODE
    $ErrorActionPreference = "Stop"
    if ($malformedPlanExit -eq 0) {
        throw "Malformed smoke plan was not rejected before execution."
    }
}
finally {
    if ($null -ne $server -and -not $server.HasExited) {
        Stop-Process -Id $server.Id -Force
        $server.WaitForExit()
    }
}

$report = [pscustomobject]@{
    schemaVersion = "npdev-ai-rest-smoke-verifier-test-report.v1"
    runId = $RunId
    generatedAt = (Get-Date).ToUniversalTime().ToString("o")
    scriptPath = "scripts/quality/run-ai-rest-smoke-verifier-tests.ps1"
    workspaceRoot = $workspaceRoot
    overallStatus = "passed"
    assertions = @(
        "formal REST checks execute against localhost",
        "health JSON exact assertions pass",
        "behavior JSON contains assertions pass",
        "wrong body assertions fail",
        "wrong status assertions fail",
        "external URL paths are rejected",
        "wrong generated app ports are rejected",
        "malformed smoke plans are rejected before execution",
        "required surface coverage is enforced",
        "result schema validation passes for positive evidence"
    )
}

$reportPath = "scripts/reports/out/ai-rest-smoke-verifier-tests-report.json"
New-Item -ItemType Directory -Force -Path (Split-Path -Parent $reportPath) | Out-Null
$report | ConvertTo-Json -Depth 20 | Set-Content -LiteralPath $reportPath -Encoding UTF8
Write-Host ("AI REST smoke verifier tests passed. Report: " + $reportPath)
