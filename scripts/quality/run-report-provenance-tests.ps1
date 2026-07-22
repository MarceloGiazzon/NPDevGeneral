param(
    [string]$RunId = ""
)

$ErrorActionPreference = "Stop"

$workspaceRoot = (Resolve-Path ".").Path
if ([string]::IsNullOrWhiteSpace($RunId)) {
    $RunId = "report-provenance-tests-" + (Get-Date).ToUniversalTime().ToString("yyyyMMdd-HHmmssfff")
}
$testRoot = Join-Path $workspaceRoot "scripts/reports/tmp/report-provenance-tests"
if (Test-Path -LiteralPath $testRoot) {
    Remove-Item -LiteralPath $testRoot -Recurse -Force
}
New-Item -ItemType Directory -Force -Path $testRoot | Out-Null

function Read-JsonFile {
    param([string]$Path)
    return Get-Content -Raw -LiteralPath $Path | ConvertFrom-Json
}

function Invoke-GitText {
    param([string[]]$Arguments)
    try {
        $output = & git @Arguments 2>$null
        if ($LASTEXITCODE -ne 0) { return "" }
        return (($output | Out-String).Trim())
    }
    catch {
        return ""
    }
}

$requiredCurrentReports = @(
    "scripts/reports/out/json-schema-validator-tests-report.json",
    "scripts/reports/out/ai-schema-validation-report.json",
    "scripts/reports/out/ai-beta-gate-report.json",
    "scripts/reports/out/controlled-command-runner-tests-report.json",
    "scripts/reports/out/ai-contract-normalizer-tests-report.json",
    "scripts/reports/out/ai-rest-smoke-verifier-tests-report.json",
    "scripts/reports/out/sample-matrix-report.json",
    "scripts/reports/out/report-schema-validation-report.json",
    "scripts/reports/out/doc-entrypoint-validation-report.json",
    "scripts/reports/out/ai-beta-reproducibility-report.json",
    "scripts/reports/out/runtime-null-context-tests-report.json",
    "scripts/reports/out/runtimehost-staged-jar-preflight-report.json",
    "scripts/reports/out/docker-linux-parity-report.json"
)

$missingReports = @($requiredCurrentReports | Where-Object { -not (Test-Path -LiteralPath $_ -PathType Leaf) })
if ($missingReports.Count -gt 0) {
    throw ("Required current reports are missing: " + ($missingReports -join ", "))
}

$releaseReportPath = Join-Path $testRoot "beta-release-gate-report.json"
$releaseManifestPath = Join-Path $testRoot "beta-release-evidence-manifest.json"
$releaseSummaryPath = Join-Path $testRoot "release-ready-summary.json"
$ErrorActionPreference = "Continue"
pwsh -NoProfile -File scripts/quality/run-beta-release-gate.ps1 `
    -ReportPath $releaseReportPath `
    -ManifestPath $releaseManifestPath `
    -SummaryPath $releaseSummaryPath `
    -RunId $RunId 2>$null | Out-Null
$releaseExit = $LASTEXITCODE
$ErrorActionPreference = "Stop"
if (-not (Test-Path -LiteralPath $releaseReportPath -PathType Leaf)) {
    throw "Release gate did not write a provenance test report."
}

$releaseReport = Read-JsonFile $releaseReportPath
$schemaResultPath = Join-Path $testRoot "release-report-schema-validation.json"
$ErrorActionPreference = "Continue"
pwsh -NoProfile -File scripts/quality/Invoke-JsonSchemaValidation.ps1 `
    -SchemaPath schemas/ai/beta-release-gate-report.schema.json `
    -JsonPath $releaseReportPath `
    -ReportPath $schemaResultPath 2>$null | Out-Null
$schemaExit = $LASTEXITCODE
$ErrorActionPreference = "Stop"
if ($schemaExit -ne 0) {
    throw "Release gate report failed schema validation."
}

$dirtyStatus = Invoke-GitText @("status", "--porcelain=v1")
$workspaceDirty = -not [string]::IsNullOrWhiteSpace($dirtyStatus)
if ($workspaceDirty -and [bool]$releaseReport.officialReleaseEligible) {
    throw "Dirty workspace was incorrectly marked officialReleaseEligible."
}
if (-not $workspaceDirty -and $releaseExit -eq 0 -and -not [bool]$releaseReport.officialReleaseEligible) {
    throw "Clean passing workspace was not marked officialReleaseEligible."
}

foreach ($required in @($releaseReport.requiredReports)) {
    if ([string]::IsNullOrWhiteSpace([string]$required.contentSha256)) {
        throw ("Required report is missing contentSha256 provenance: " + [string]$required.name)
    }
    if (-not [bool]$required.fresh) {
        throw ("Required report is not fresh: " + [string]$required.name)
    }
    if ([string]$required.runId -ne $RunId) {
        throw ("Required report runId did not match provenance test runId: " + [string]$required.name)
    }
}

function Write-FakeReleasePolicyAndReports {
    param([string]$CaseName, [bool]$MissingRunId, [bool]$MixedRunId)
    $caseRoot = Join-Path $testRoot $CaseName
    New-Item -ItemType Directory -Force -Path $caseRoot | Out-Null
    $requiredNames = @(
        "json-schema-validator-tests",
        "ai-beta-gate",
        "report-schema-validation",
        "doc-entrypoint-validation",
        "ai-schema-validation",
        "controlled-command-runner-tests",
        "ai-contract-normalizer-tests",
        "ai-rest-smoke-verifier-tests",
        "sample-matrix",
        "ai-beta-reproducibility",
        "runtime-null-context-tests"
    )
    $definitions = @()
    for ($i = 0; $i -lt $requiredNames.Count; $i++) {
        $name = $requiredNames[$i]
        $reportPath = Join-Path $caseRoot ($name + ".json")
        $reportRunId = if ($MissingRunId -and $i -eq 0) { "" } elseif ($MixedRunId -and $i -eq 1) { $RunId + "-other" } else { $RunId }
        $fakeReport = [ordered]@{
            schemaVersion = "fake-report.v1"
            generatedAt = (Get-Date).ToUniversalTime().ToString("o")
            overallStatus = "passed"
        }
        if (-not [string]::IsNullOrWhiteSpace($reportRunId)) {
            $fakeReport["runId"] = $reportRunId
        }
        $fakeReport | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath $reportPath -Encoding UTF8
        $definitions += [pscustomobject]@{
            name = $name
            path = $reportPath
            schemaVersion = "fake-report.v1"
            statusProperty = "overallStatus"
            passValue = "passed"
        }
    }
    $policyPath = Join-Path $caseRoot "policy.json"
    [ordered]@{
        schemaVersion = "npdev-beta-release-gate-policy.v1"
        release = "ai-only-beta-0"
        maxReportAgeHours = 24
        officialEvidencePlatform = "windows-ci"
        dockerLinuxEvidence = "blocking-release-evidence"
        scopePolicy = "scripts/policy/beta0-scope.json"
        truthTable = "scripts/policy/beta0-release-truth-table.json"
        requiredReports = $definitions
        informationalReports = @()
        readinessRule = "fake policy for provenance tests"
    } | ConvertTo-Json -Depth 20 | Set-Content -LiteralPath $policyPath -Encoding UTF8
    return $policyPath
}

foreach ($case in @(
    [pscustomobject]@{ name = "missing-run-id"; missing = $true; mixed = $false; expected = "missing runId" },
    [pscustomobject]@{ name = "mixed-run-id"; missing = $false; mixed = $true; expected = "exactly one runId" }
)) {
    $fakePolicyPath = Write-FakeReleasePolicyAndReports -CaseName $case.name -MissingRunId $case.missing -MixedRunId $case.mixed
    $fakeReportPath = Join-Path $testRoot ($case.name + "-release-report.json")
    $ErrorActionPreference = "Continue"
    pwsh -NoProfile -File scripts/quality/run-beta-release-gate.ps1 `
        -PolicyPath $fakePolicyPath `
        -ReportPath $fakeReportPath `
        -ManifestPath (Join-Path $testRoot ($case.name + "-manifest.json")) `
        -SummaryPath (Join-Path $testRoot ($case.name + "-summary.json")) `
        -RunId $RunId 2>$null | Out-Null
    $fakeExit = $LASTEXITCODE
    $ErrorActionPreference = "Stop"
    if ($fakeExit -eq 0) {
        throw ("Release gate accepted fake " + $case.name + " provenance.")
    }
    $fakeReport = Read-JsonFile $fakeReportPath
    if ((@($fakeReport.blockers) -join "`n") -notmatch [regex]::Escape([string]$case.expected)) {
        throw ("Release gate did not expose expected blocker for " + $case.name + ".")
    }
}

$missingScenarioRoot = Join-Path $testRoot "missing-required-scenario-root"
New-Item -ItemType Directory -Force -Path $missingScenarioRoot | Out-Null
$missingScenarioReportPath = Join-Path $testRoot "missing-required-scenario-schema-validation.json"
$ErrorActionPreference = "Continue"
pwsh -NoProfile -File scripts/quality/run-ai-schema-validation.ps1 `
    -ScenarioRoot $missingScenarioRoot `
    -ReportPath $missingScenarioReportPath `
    -RunId $RunId 2>$null | Out-Null
$missingScenarioExit = $LASTEXITCODE
$ErrorActionPreference = "Stop"
if ($missingScenarioExit -eq 0) {
    throw "AI schema validation accepted a scenario root missing required Beta 0 scenarios."
}
$missingScenarioReport = Read-JsonFile $missingScenarioReportPath
if ([bool]$missingScenarioReport.scenarioCoverage.requiredScenarioCoveragePassed -or @($missingScenarioReport.scenarioCoverage.missingRequiredScenarios).Count -eq 0) {
    throw "AI schema validation did not report missing required scenario coverage."
}

$report = [pscustomobject]@{
    schemaVersion = "npdev-report-provenance-test-report.v1"
    runId = $RunId
    generatedAt = (Get-Date).ToUniversalTime().ToString("o")
    scriptPath = "scripts/quality/run-report-provenance-tests.ps1"
    workspaceRoot = $workspaceRoot
    overallStatus = "passed"
    workspaceDirty = $workspaceDirty
    releaseGateExitCode = $releaseExit
    releaseReportSchemaValidated = $true
    officialReleaseEligible = [bool]$releaseReport.officialReleaseEligible
    assertions = @(
        "all required current source-of-truth reports exist",
        "release gate report validates against its report schema",
        "dirty workspace blocks officialReleaseEligible",
        "required reports carry hashes and freshness provenance",
        "missing child report runId blocks release",
        "mixed child report runIds block release",
        "missing required AI scenario coverage blocks schema validation"
    )
}

$reportPath = "scripts/reports/out/report-provenance-tests-report.json"
New-Item -ItemType Directory -Force -Path (Split-Path -Parent $reportPath) | Out-Null
$report | ConvertTo-Json -Depth 20 | Set-Content -LiteralPath $reportPath -Encoding UTF8
Write-Host ("Report provenance tests passed. Report: " + $reportPath)
