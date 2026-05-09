param(
    [string]$RunId = ""
)

$ErrorActionPreference = "Stop"

$workspaceRoot = (Resolve-Path ".").Path
if ([string]::IsNullOrWhiteSpace($RunId)) {
    $RunId = "report-schema-validation-" + (Get-Date).ToUniversalTime().ToString("yyyyMMdd-HHmmssfff")
}
$testRoot = Join-Path $workspaceRoot "scripts/reports/tmp/report-schema-validation-tests"
if (Test-Path -LiteralPath $testRoot) {
    Remove-Item -LiteralPath $testRoot -Recurse -Force
}
New-Item -ItemType Directory -Force -Path $testRoot | Out-Null

function Invoke-ReportValidation {
    param(
        [string]$Name,
        [string]$SchemaPath,
        [string]$ReportPath,
        [bool]$ShouldPass
    )
    $validationPath = Join-Path $testRoot ($Name + ".json")
    $ErrorActionPreference = "Continue"
    pwsh -NoProfile -File scripts/quality/Invoke-JsonSchemaValidation.ps1 `
        -SchemaPath $SchemaPath `
        -InstancePath $ReportPath `
        -ReportPath $validationPath 2>$null | Out-Null
    $exitCode = $LASTEXITCODE
    $ErrorActionPreference = "Stop"
    $result = if (Test-Path -LiteralPath $validationPath -PathType Leaf) { Get-Content -Raw -LiteralPath $validationPath | ConvertFrom-Json } else { $null }
    if ($ShouldPass -and ($exitCode -ne 0 -or $null -eq $result -or $result.status -ne "passed")) {
        throw "Expected report schema validation to pass: $Name"
    }
    if (-not $ShouldPass -and ($exitCode -eq 0 -or $null -eq $result -or $result.status -ne "failed")) {
        throw "Expected report schema validation to fail: $Name"
    }
    return [pscustomobject]@{
        name = $Name
        schemaPath = $SchemaPath
        reportPath = $ReportPath
        expectedStatus = if ($ShouldPass) { "passed" } else { "failed" }
        actualStatus = if ($null -ne $result) { [string]$result.status } else { "missing-validation-result" }
        errors = if ($null -ne $result) { @($result.errors) } else { @() }
    }
}

function Write-JsonFixture {
    param([string]$Path, [object]$Value)
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $Path) | Out-Null
    $Value | ConvertTo-Json -Depth 80 | Set-Content -LiteralPath $Path -Encoding UTF8
}

$cases = @()
if (Test-Path -LiteralPath "scripts/reports/out/ai-beta-gate-report.json" -PathType Leaf) {
    $cases += Invoke-ReportValidation "ai-beta-gate-report" "schemas/ai/ai-beta-gate-report.schema.json" "scripts/reports/out/ai-beta-gate-report.json" $true
}
if (Test-Path -LiteralPath "scripts/reports/out/runtimehost-staged-jar-preflight-report.json" -PathType Leaf) {
    $cases += Invoke-ReportValidation "runtimehost-staged-jar-preflight-report" "schemas/ai/runtimehost-staged-jar-preflight-report.schema.json" "scripts/reports/out/runtimehost-staged-jar-preflight-report.json" $true
}
if (Test-Path -LiteralPath "scripts/reports/out/docker-linux-parity-report.json" -PathType Leaf) {
    $cases += Invoke-ReportValidation "docker-linux-parity-report" "schemas/ai/docker-linux-parity-report.schema.json" "scripts/reports/out/docker-linux-parity-report.json" $true
}
if (Test-Path -LiteralPath "scripts/reports/out/ai-command-policy-report.json" -PathType Leaf) {
    $cases += Invoke-ReportValidation "ai-command-policy-report" "schemas/ai/ai-command-policy-report.schema.json" "scripts/reports/out/ai-command-policy-report.json" $true
}
if (Test-Path -LiteralPath "scripts/reports/out/trusted-source-beta0-proof-report.json" -PathType Leaf) {
    $cases += Invoke-ReportValidation "trusted-source-beta0-proof-report" "schemas/ai/trusted-source-beta0-proof-report.schema.json" "scripts/reports/out/trusted-source-beta0-proof-report.json" $true
}
if (Test-Path -LiteralPath "scripts/reports/out/direct-evidence-hardening-tests-report.json" -PathType Leaf) {
    $cases += Invoke-ReportValidation "direct-evidence-hardening-tests-report" "schemas/ai/direct-evidence-hardening-tests-report.schema.json" "scripts/reports/out/direct-evidence-hardening-tests-report.json" $true
}
if (Test-Path -LiteralPath "scripts/reports/out/roadmap-closure-check-report.json" -PathType Leaf) {
    $cases += Invoke-ReportValidation "roadmap-closure-check-report" "schemas/ai/roadmap-closure-check-report.schema.json" "scripts/reports/out/roadmap-closure-check-report.json" $true
}
else {
    $validRoadmapClosurePath = Join-Path $testRoot "valid-roadmap-closure-check-report.json"
    Write-JsonFixture -Path $validRoadmapClosurePath -Value ([pscustomobject]@{
            schemaVersion = "npdev-roadmap-closure-check-report.v1"
            runId = $RunId
            generatedAt = (Get-Date).ToUniversalTime().ToString("o")
            scriptPath = "scripts/quality/run-roadmap-closure-check.ps1"
            workspaceRoot = $workspaceRoot
            overallStatus = "failed"
            checks = @(
                [pscustomobject]@{
                    name = "fixture"
                    passed = $false
                    reason = "fixture"
                    evidence = [pscustomobject]@{ path = "fixture" }
                }
            )
            blockers = @("fixture")
        })
    $cases += Invoke-ReportValidation "roadmap-closure-check-report" "schemas/ai/roadmap-closure-check-report.schema.json" $validRoadmapClosurePath $true
}
if (Test-Path -LiteralPath "scripts/reports/out/runbook-workflow-alignment-tests-report.json" -PathType Leaf) {
    $cases += Invoke-ReportValidation "runbook-workflow-alignment-tests-report" "schemas/ai/runbook-workflow-alignment-tests-report.schema.json" "scripts/reports/out/runbook-workflow-alignment-tests-report.json" $true
}
if (Test-Path -LiteralPath "scripts/reports/out/final-regression-coverage-audit-report.json" -PathType Leaf) {
    $cases += Invoke-ReportValidation "final-regression-coverage-audit-report" "schemas/ai/final-regression-coverage-audit-report.schema.json" "scripts/reports/out/final-regression-coverage-audit-report.json" $true
}
else {
    $validCurrentFinalAuditPath = Join-Path $testRoot "valid-current-final-regression-coverage-audit-report.json"
    Write-JsonFixture -Path $validCurrentFinalAuditPath -Value ([pscustomobject]@{
            schemaVersion = "npdev-final-regression-coverage-audit-report.v1"
            runId = $RunId
            generatedAt = (Get-Date).ToUniversalTime().ToString("o")
            scriptPath = "scripts/quality/run-final-regression-coverage-audit.ps1"
            workspaceRoot = $workspaceRoot
            overallStatus = "passed"
            coverageStatus = "passed"
            releaseReadinessStatus = "blocked"
            coverageManifestPath = "scripts/policy/final-regression-coverage-manifest.json"
            coverageItems = @(
                [pscustomobject]@{
                    id = "fixture"
                    description = "fixture"
                    passed = $true
                    evidence = @()
                    failures = @()
                }
            )
            checks = @(
                [pscustomobject]@{
                    name = "fixture"
                    passed = $true
                    releaseBlocking = $true
                    reason = "fixture"
                    evidence = [pscustomobject]@{ path = "fixture" }
                }
            )
            releaseBlockers = @(
                [pscustomobject]@{ name = "aggregate-beta-release-gate"; expected = $true }
            )
            failures = @()
        })
    $cases += Invoke-ReportValidation "final-regression-coverage-audit-report" "schemas/ai/final-regression-coverage-audit-report.schema.json" $validCurrentFinalAuditPath $true
}

$validTraceableReleasePath = Join-Path $testRoot "valid-traceable-local-release-report.json"
Write-JsonFixture -Path $validTraceableReleasePath -Value ([pscustomobject]@{
        schemaVersion = "npdev-traceable-local-release-report.v1"
        runId = $RunId
        generatedAt = (Get-Date).ToUniversalTime().ToString("o")
        scriptPath = "scripts/quality/run-traceable-local-release.ps1"
        overallStatus = "failed"
        source = [pscustomobject]@{
            commitSha = "fixture"
            branch = "main"
            commitAvailable = $true
            branchAvailable = $true
            statusCommand = "git status --porcelain=v1"
            dirtyLineCount = 0
        }
        canonicalRelease = [pscustomobject]@{
            scriptPath = "scripts/quality/run-beta0-final-release-check.ps1"
            command = "pwsh -NoProfile -File scripts/quality/run-beta0-final-release-check.ps1"
            exitCode = 1
            stdoutLog = "scripts/reports/out/traceable-local-release/stdout.log"
            stderrLog = "scripts/reports/out/traceable-local-release/stderr.log"
            finalReleaseReportPath = "scripts/reports/out/beta0-final-release-check-report.json"
            finalReleaseOverallStatus = "failed"
            officialReleaseEligible = $false
            beta0TagAllowed = $false
        }
        blockers = @("fixture")
    })
$cases += Invoke-ReportValidation "valid-traceable-local-release-report" "schemas/ai/traceable-local-release-report.schema.json" $validTraceableReleasePath $true

$invalidTraceableReleasePath = Join-Path $testRoot "invalid-traceable-local-release-report.json"
Write-JsonFixture -Path $invalidTraceableReleasePath -Value ([pscustomobject]@{
        schemaVersion = "npdev-traceable-local-release-report.v1"
        runId = $RunId
        generatedAt = (Get-Date).ToUniversalTime().ToString("o")
        scriptPath = "scripts/quality/run-traceable-local-release.ps1"
        overallStatus = "passed"
        blockers = @()
    })
$cases += Invoke-ReportValidation "invalid-traceable-local-release-report-rejected" "schemas/ai/traceable-local-release-report.schema.json" $invalidTraceableReleasePath $false

$validFinalAuditPath = Join-Path $testRoot "valid-final-regression-coverage-audit-report.json"
Write-JsonFixture -Path $validFinalAuditPath -Value ([pscustomobject]@{
        schemaVersion = "npdev-final-regression-coverage-audit-report.v1"
        runId = $RunId
        generatedAt = (Get-Date).ToUniversalTime().ToString("o")
        scriptPath = "scripts/quality/run-final-regression-coverage-audit.ps1"
        workspaceRoot = $workspaceRoot
        overallStatus = "passed"
        coverageStatus = "passed"
        releaseReadinessStatus = "blocked"
        coverageManifestPath = "scripts/policy/final-regression-coverage-manifest.json"
        coverageItems = @(
            [pscustomobject]@{
                id = "fixture"
                description = "fixture"
                passed = $true
                evidence = @()
                failures = @()
            }
        )
        checks = @(
            [pscustomobject]@{
                name = "fixture"
                passed = $true
                releaseBlocking = $true
                reason = "fixture"
                evidence = [pscustomobject]@{ path = "fixture" }
            }
        )
        releaseBlockers = @(
            [pscustomobject]@{ name = "sample-runtime-generation-release-evidence"; expected = $true }
        )
        failures = @()
    })
$cases += Invoke-ReportValidation "valid-final-regression-coverage-audit-report" "schemas/ai/final-regression-coverage-audit-report.schema.json" $validFinalAuditPath $true

$invalidFinalAuditPath = Join-Path $testRoot "invalid-final-regression-coverage-audit-report.json"
Write-JsonFixture -Path $invalidFinalAuditPath -Value ([pscustomobject]@{
        schemaVersion = "npdev-final-regression-coverage-audit-report.v1"
        runId = $RunId
        generatedAt = (Get-Date).ToUniversalTime().ToString("o")
        scriptPath = "scripts/quality/run-final-regression-coverage-audit.ps1"
        overallStatus = "passed"
    })
$cases += Invoke-ReportValidation "invalid-final-regression-coverage-audit-report-rejected" "schemas/ai/final-regression-coverage-audit-report.schema.json" $invalidFinalAuditPath $false

$fakeAiBetaReportPath = Join-Path $testRoot "fake-minimal-ai-beta-green.json"
@{
    schemaVersion = "npdev-ai-beta-gate-report.v1"
    overallStatus = "passed"
    generatedAt = (Get-Date).ToUniversalTime().ToString("o")
    scriptPath = "scripts/quality/run-ai-beta-gate.ps1"
    workspaceRoot = $workspaceRoot
    scenarioRoot = "golden-ai-scenarios"
    scenarioCount = 1
    scenarios = @(
        @{
            scenarioId = "fake"
            status = "passed"
            expectedOutcome = "pass"
            stages = @()
            failureReasons = @()
        }
    )
} | ConvertTo-Json -Depth 20 | Set-Content -LiteralPath $fakeAiBetaReportPath -Encoding UTF8
$cases += Invoke-ReportValidation "fake-minimal-ai-beta-green-rejected" "schemas/ai/ai-beta-gate-report.schema.json" $fakeAiBetaReportPath $false

$fakeFinalClosurePath = Join-Path $testRoot "fake-inconsistent-final-closure.json"
@{
    schemaVersion = "npdev-beta0-final-closure-report.v1"
    status = "passed"
    overallStatus = "passed"
    candidateReady = $true
    releaseReady = $true
    provenanceReady = $false
    officialReleaseEligible = $true
    beta0TagAllowed = $true
    workspaceDirty = $true
    requiredReports = @(@{ name = "fake" })
    blockers = @()
} | ConvertTo-Json -Depth 20 | Set-Content -LiteralPath $fakeFinalClosurePath -Encoding UTF8
$cases += Invoke-ReportValidation "fake-inconsistent-final-closure-rejected" "schemas/ai/beta0-final-closure-report.schema.json" $fakeFinalClosurePath $false

$report = [pscustomobject]@{
    schemaVersion = "npdev-report-schema-validation-report.v1"
    runId = $RunId
    generatedAt = (Get-Date).ToUniversalTime().ToString("o")
    scriptPath = "scripts/quality/run-report-schema-validation.ps1"
    workspaceRoot = $workspaceRoot
    overallStatus = "passed"
    cases = $cases
}

$reportPath = "scripts/reports/out/report-schema-validation-report.json"
New-Item -ItemType Directory -Force -Path (Split-Path -Parent $reportPath) | Out-Null
$report | ConvertTo-Json -Depth 40 | Set-Content -LiteralPath $reportPath -Encoding UTF8
Write-Host ("Report schema validation passed. Report: " + $reportPath)
