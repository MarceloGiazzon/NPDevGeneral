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

$cases = @()
if (Test-Path -LiteralPath "scripts/reports/out/ai-beta-gate-report.json" -PathType Leaf) {
    $cases += Invoke-ReportValidation "ai-beta-gate-report" "schemas/ai/ai-beta-gate-report.schema.json" "scripts/reports/out/ai-beta-gate-report.json" $true
}

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
