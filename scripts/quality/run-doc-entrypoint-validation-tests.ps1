param(
    [string]$RunId = ""
)

$ErrorActionPreference = "Stop"

if ([string]::IsNullOrWhiteSpace($RunId)) {
    $RunId = "doc-entrypoint-validation-tests-" + (Get-Date).ToUniversalTime().ToString("yyyyMMdd-HHmmssfff")
}

$workspaceRoot = (Resolve-Path ".").Path
$testRoot = Join-Path $workspaceRoot "scripts/reports/tmp/doc-entrypoint-validation-tests"
if (Test-Path -LiteralPath $testRoot -PathType Container) {
    Remove-Item -LiteralPath $testRoot -Recurse -Force
}
New-Item -ItemType Directory -Force -Path $testRoot | Out-Null

$blockingFixturePath = "scripts/tests/fixtures/doc-entrypoint-validation/missing-blocking-script.md"
$blockingFixtureReportPath = "scripts/reports/tmp/doc-entrypoint-validation-tests/missing-blocking-script-report.json"
$futureFixturePath = "scripts/tests/fixtures/doc-entrypoint-validation/future-non-release-script.md"
$futureFixtureReportPath = "scripts/reports/tmp/doc-entrypoint-validation-tests/future-non-release-script-report.json"

$ErrorActionPreference = "Continue"
pwsh -NoProfile -File scripts/quality/run-doc-entrypoint-validation.ps1 `
    -RunId $RunId `
    -DocumentPaths $blockingFixturePath `
    -ReportPath $blockingFixtureReportPath 2>$null | Out-Null
$blockingFixtureExitCode = $LASTEXITCODE
$ErrorActionPreference = "Stop"

if ($blockingFixtureExitCode -eq 0) {
    throw "Doc entrypoint validation unexpectedly passed for a missing blocking script fixture."
}
if (-not (Test-Path -LiteralPath $blockingFixtureReportPath -PathType Leaf)) {
    throw "Doc entrypoint validation did not write the fixture report."
}

$fixtureReport = Get-Content -Raw -LiteralPath $blockingFixtureReportPath | ConvertFrom-Json
$expectedScript = "scripts/quality/missing-doc-entrypoint-fixture.ps1"
$finding = @($fixtureReport.blockingMissingScripts | Where-Object {
        [string]$_.normalizedPath -eq $expectedScript
    } | Select-Object -First 1)

if ($null -eq $finding) {
    throw "Missing blocking script fixture was not listed in blockingMissingScripts."
}
if ([string]$finding.document -ne $blockingFixturePath) {
    throw "Missing blocking script finding did not preserve the source document path."
}
if ([int]$finding.lineNumber -ne 3) {
    throw "Missing blocking script finding did not preserve the expected source line number."
}
if (-not [bool]$finding.blocking -or [string]$finding.classification -ne "release-relevant") {
    throw "Missing blocking script finding was not classified as release-relevant and blocking."
}
if ($fixtureReport.overallStatus -ne "failed") {
    throw "Fixture report did not fail overall for a missing blocking script."
}

$ErrorActionPreference = "Continue"
pwsh -NoProfile -File scripts/quality/run-doc-entrypoint-validation.ps1 `
    -RunId $RunId `
    -DocumentPaths $futureFixturePath `
    -ReportPath $futureFixtureReportPath 2>$null | Out-Null
$futureFixtureExitCode = $LASTEXITCODE
$ErrorActionPreference = "Stop"

if ($futureFixtureExitCode -ne 0) {
    throw "Doc entrypoint validation unexpectedly failed for a future/non-release script fixture."
}
if (-not (Test-Path -LiteralPath $futureFixtureReportPath -PathType Leaf)) {
    throw "Doc entrypoint validation did not write the future/non-release fixture report."
}

$futureFixtureReport = Get-Content -Raw -LiteralPath $futureFixtureReportPath | ConvertFrom-Json
$futureExpectedScript = "scripts/doctor/npdev-doctor.ps1"
$futureFinding = @($futureFixtureReport.futureOrNonReleaseReferences | Where-Object {
        [string]$_.normalizedPath -eq $futureExpectedScript
    } | Select-Object -First 1)
if ($null -eq $futureFinding) {
    throw "Future/non-release script fixture was not listed in futureOrNonReleaseReferences."
}
if ([string]$futureFinding.document -ne $futureFixturePath) {
    throw "Future/non-release script finding did not preserve the source document path."
}
if ([int]$futureFinding.lineNumber -ne 3) {
    throw "Future/non-release script finding did not preserve the expected source line number."
}
if ([bool]$futureFinding.blocking -or [string]$futureFinding.classification -ne "future-non-release") {
    throw "Future/non-release script finding was not classified as non-blocking future-non-release."
}
if ($futureFixtureReport.overallStatus -ne "passed") {
    throw "Future/non-release fixture report should pass overall."
}

# docs-decoupling-2026-08-11 PLAN.md Phase 2: a "historical" document (docs/archive/**,
# docs/beta/**) must not fail on a script reference that no longer exists. The classification
# lookup only runs for a path literally starting with "docs/" (hardcoded in
# run-doc-entrypoint-validation.ps1), so this fixture must live under a REAL docs/archive/ path to
# exercise the real code path -- created here and removed at the end of this test run, same
# create-prove-revert discipline as the Phase 0 empty-scope floor proof.
$historicalFixtureRelPath = "docs/archive/programme-history/_doc-entrypoint-validation-test-fixture-historical.md"
$historicalFixtureFullPath = Join-Path $workspaceRoot $historicalFixtureRelPath
$historicalFixtureReportPath = "scripts/reports/tmp/doc-entrypoint-validation-tests/historical-missing-script-report.json"
Set-Content -LiteralPath $historicalFixtureFullPath -Encoding UTF8 -Value @(
    "# Historical Doc Entrypoint Validation Fixture (auto-generated, deleted at end of test run)"
    ""
    "``pwsh -File scripts\quality\missing-doc-entrypoint-fixture.ps1``"
)

try {
    $ErrorActionPreference = "Continue"
    pwsh -NoProfile -File scripts/quality/run-doc-entrypoint-validation.ps1 `
        -RunId $RunId `
        -DocumentPaths $historicalFixtureRelPath `
        -ReportPath $historicalFixtureReportPath 2>$null | Out-Null
    $historicalFixtureExitCode = $LASTEXITCODE
    $ErrorActionPreference = "Stop"

    if ($historicalFixtureExitCode -ne 0) {
        throw "Doc entrypoint validation unexpectedly failed for a historical document citing a missing script."
    }
    if (-not (Test-Path -LiteralPath $historicalFixtureReportPath -PathType Leaf)) {
        throw "Doc entrypoint validation did not write the historical fixture report."
    }

    $historicalFixtureReport = Get-Content -Raw -LiteralPath $historicalFixtureReportPath | ConvertFrom-Json
    $historicalExpectedScript = "scripts/quality/missing-doc-entrypoint-fixture.ps1"
    $historicalFinding = @($historicalFixtureReport.scriptEntrypoints | Where-Object {
            [string]$_.normalizedPath -eq $historicalExpectedScript
        } | Select-Object -First 1)
    if ($null -eq $historicalFinding) {
        throw "Historical-document missing-script fixture was not listed in scriptEntrypoints."
    }
    if ([bool]$historicalFinding.blocking) {
        throw "Historical-document missing-script finding was blocking -- the whole point of the 'historical' classification is that it is not."
    }
    $historicalDocClassification = @($historicalFixtureReport.documentClassifications | Where-Object {
            [string]$_.document -eq $historicalFixtureRelPath
        } | Select-Object -First 1)
    if ($null -eq $historicalDocClassification -or [string]$historicalDocClassification.classification -ne "historical") {
        throw "Historical fixture document was not classified as 'historical' -- test fixture or policy drifted."
    }
    if ($historicalFixtureReport.overallStatus -ne "passed") {
        throw "Historical fixture report should pass overall."
    }
}
finally {
    Remove-Item -LiteralPath $historicalFixtureFullPath -Force -ErrorAction SilentlyContinue
}

$report = [pscustomobject]@{
    schemaVersion = "npdev-doc-entrypoint-validation-test-report.v1"
    runId = $RunId
    generatedAt = (Get-Date).ToUniversalTime().ToString("o")
    scriptPath = "scripts/quality/run-doc-entrypoint-validation-tests.ps1"
    workspaceRoot = $workspaceRoot
    overallStatus = "passed"
    assertions = @(
        "missing documented release-relevant script fails validation",
        "missing script finding includes source document path",
        "missing script finding includes 1-based source line number",
        "missing script finding is classified as release-relevant and blocking",
        "future/non-release missing script does not fail validation",
        "future/non-release script finding includes source document path",
        "future/non-release script finding includes 1-based source line number",
        "future/non-release script finding is classified as non-blocking",
        "historical document citing a missing script does not fail validation",
        "historical document missing-script finding is classified as non-blocking",
        "historical document is itself classified as 'historical'"
    )
    blockingFixtureReportPath = $blockingFixtureReportPath
    futureFixtureReportPath = $futureFixtureReportPath
    historicalFixtureReportPath = $historicalFixtureReportPath
}

$reportPath = "scripts/reports/out/doc-entrypoint-validation-tests-report.json"
New-Item -ItemType Directory -Force -Path (Split-Path -Parent $reportPath) | Out-Null
$report | ConvertTo-Json -Depth 20 | Set-Content -LiteralPath $reportPath -Encoding UTF8

Write-Host ("Doc entrypoint validation tests passed. Report: " + $reportPath)
