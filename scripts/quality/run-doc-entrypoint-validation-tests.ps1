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
        "future/non-release script finding is classified as non-blocking"
    )
    blockingFixtureReportPath = $blockingFixtureReportPath
    futureFixtureReportPath = $futureFixtureReportPath
}

$reportPath = "scripts/reports/out/doc-entrypoint-validation-tests-report.json"
New-Item -ItemType Directory -Force -Path (Split-Path -Parent $reportPath) | Out-Null
$report | ConvertTo-Json -Depth 20 | Set-Content -LiteralPath $reportPath -Encoding UTF8

Write-Host ("Doc entrypoint validation tests passed. Report: " + $reportPath)
