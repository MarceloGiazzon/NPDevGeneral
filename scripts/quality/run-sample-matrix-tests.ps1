param(
    [string]$RunId = "",
    [string]$ReportPath = "scripts/reports/out/sample-matrix-tests-report.json"
)

$ErrorActionPreference = "Stop"

function Add-TestFailure {
    param(
        [string]$Name,
        [string]$Message
    )
    $script:failures += [pscustomobject]@{
        name = $Name
        message = $Message
    }
}

function Assert-Condition {
    param(
        [bool]$Condition,
        [string]$Name,
        [string]$Message
    )
    if (-not $Condition) {
        Add-TestFailure -Name $Name -Message $Message
    }
}

function Write-TextFile {
    param(
        [string]$Path,
        [string]$Content
    )
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $Path) | Out-Null
    Set-Content -LiteralPath $Path -Encoding UTF8 -Value $Content
}

function Copy-ValidInputs {
    param(
        [string]$InputRoot,
        [string]$SampleId,
        [switch]$OmitConfig
    )
    New-Item -ItemType Directory -Force -Path $InputRoot | Out-Null
    Copy-Item -LiteralPath "scripts/tests/fixtures/schema-validation/official-model-valid.json" -Destination (Join-Path $InputRoot "model.json") -Force
    if (-not $OmitConfig) {
        Copy-Item -LiteralPath "scripts/tests/fixtures/schema-validation/official-config-valid.json" -Destination (Join-Path $InputRoot "config.json") -Force
    }
    Write-TextFile -Path (Join-Path $InputRoot "manifest.json") -Content (@{ id = $SampleId } | ConvertTo-Json)
    Write-TextFile -Path (Join-Path $InputRoot "expected-behavior.md") -Content "# Expected Behavior"
    Write-TextFile -Path (Join-Path $InputRoot "expected-endpoints.md") -Content "# Expected Endpoints"
}

function New-SampleMatrixFixture {
    param(
        [string]$Root,
        [switch]$ReleaseSampleMissingConfig
    )
    $sampleRoot = Join-Path $Root "Samples"
    New-Item -ItemType Directory -Force -Path $sampleRoot | Out-Null
    Copy-ValidInputs -InputRoot (Join-Path $sampleRoot "release-ok/Input") -SampleId "release-ok" -OmitConfig:$ReleaseSampleMissingConfig
    New-Item -ItemType Directory -Force -Path (Join-Path $sampleRoot "fixture-only/Input") | Out-Null
    Copy-Item -LiteralPath "scripts/tests/fixtures/schema-validation/official-model-valid.json" -Destination (Join-Path $sampleRoot "fixture-only/Input/model.json") -Force

    $catalog = [pscustomobject]@{
        version = "test"
        layout = "fixture"
        authority = "sample-matrix-tests"
        samples = @(
            [pscustomobject]@{ id = "release-ok"; name = "Release Ok"; kind = "official-sample"; inputRoot = "release-ok/Input"; outputRoot = "release-ok/Output" },
            [pscustomobject]@{ id = "fixture-only"; name = "Fixture Only"; kind = "test-model"; inputRoot = "fixture-only/Input"; outputRoot = "fixture-only/Output" }
        )
    }
    $catalog | ConvertTo-Json -Depth 20 | Set-Content -LiteralPath (Join-Path $sampleRoot "sample-catalog.json") -Encoding UTF8

    $policy = [pscustomobject]@{
        schemaVersion = "npdev-sample-matrix-policy.v1"
        release = "ai-only-beta-0"
        semantics = "strict-release-sample"
        blockingClassifications = @("release-sample")
        nonBlockingClassifications = @("fixture-only")
        coverage = [pscustomobject]@{
            requiredReleaseCoveragePercent = 100.0
            coverageDenominator = "release-sample"
            minimumRequiredSamples = 1
        }
        releaseBlockingSamples = @("release-ok")
        fixtureOnlySamples = @("fixture-only")
        aiBetaScenarioLinks = [pscustomobject]@{
            "release-ok" = @("fixture-scenario")
        }
        requiredInputFiles = @("model.json", "config.json", "manifest.json", "expected-behavior.md", "expected-endpoints.md")
        allowedDatabaseProviders = @("docker-postgres", "postgres", "embedded-test")
    }
    $policyPath = Join-Path $Root "sample-matrix-policy.json"
    $policy | ConvertTo-Json -Depth 20 | Set-Content -LiteralPath $policyPath -Encoding UTF8

    return [pscustomobject]@{
        sampleRoot = $sampleRoot
        policyPath = $policyPath
    }
}

function Invoke-SampleMatrixForTest {
    param(
        [string]$SampleRoot,
        [string]$PolicyPath,
        [string]$ReportPath,
        [string]$EvidenceRoot,
        [switch]$SkipGenerationRuntimeVerification
    )
    $ErrorActionPreference = "Continue"
    $arguments = @("-NoProfile", "-File", "scripts/quality/run-sample-matrix.ps1", "-SampleRoot", $SampleRoot, "-PolicyPath", $PolicyPath, "-ReportPath", $ReportPath, "-RunId", $RunId)
    if (-not [string]::IsNullOrWhiteSpace($EvidenceRoot)) {
        $arguments += @("-EvidenceRoot", $EvidenceRoot)
    }
    if ($SkipGenerationRuntimeVerification) {
        $arguments += "-SkipGenerationRuntimeVerification"
    }
    pwsh @arguments 2>&1 | Out-Null
    $exitCode = $LASTEXITCODE
    $ErrorActionPreference = "Stop"
    $report = Get-Content -Raw -LiteralPath $ReportPath | ConvertFrom-Json
    return [pscustomobject]@{
        exitCode = $exitCode
        report = $report
    }
}

$workspaceRoot = (Resolve-Path ".").Path
if ([string]::IsNullOrWhiteSpace($RunId)) {
    $RunId = "sample-matrix-tests-" + (Get-Date).ToUniversalTime().ToString("yyyyMMdd-HHmmssfff")
}

$script:failures = @()
$testRoot = Join-Path $workspaceRoot "scripts/reports/tmp/sample-matrix-tests"
if (Test-Path -LiteralPath $testRoot -PathType Container) {
    Remove-Item -LiteralPath $testRoot -Recurse -Force
}
New-Item -ItemType Directory -Force -Path $testRoot | Out-Null

$releaseEvidenceRoot = Join-Path $workspaceRoot "scripts/reports/out/sample-matrix"
$releaseSentinelPath = Join-Path $releaseEvidenceRoot "release-log-overwrite-sentinel.log"
New-Item -ItemType Directory -Force -Path $releaseEvidenceRoot | Out-Null
$releaseSentinelText = "release evidence sentinel " + $RunId
Set-Content -LiteralPath $releaseSentinelPath -Encoding UTF8 -Value $releaseSentinelText

$fixtureOnlyFixture = New-SampleMatrixFixture -Root (Join-Path $testRoot "fixture-only-nonblocking")
$fixtureOnlyReportPath = Join-Path $testRoot "fixture-only-nonblocking-report.json"
$fixtureOnlyRun = Invoke-SampleMatrixForTest -SampleRoot $fixtureOnlyFixture.sampleRoot -PolicyPath $fixtureOnlyFixture.policyPath -ReportPath $fixtureOnlyReportPath -EvidenceRoot (Join-Path $testRoot "fixture-only-nonblocking-evidence") -SkipGenerationRuntimeVerification
Assert-Condition -Condition ($fixtureOnlyRun.exitCode -eq 0) -Name "fixture-only-issue-exit" -Message "Fixture-only sample input issues must not fail the sample matrix command."
Assert-Condition -Condition ($fixtureOnlyRun.report.overallStatus -eq "passed") -Name "fixture-only-issue-status" -Message "Fixture-only sample input issues must not fail the input-contract matrix when release samples pass."
Assert-Condition -Condition ([bool]$fixtureOnlyRun.report.inputContractEvidence.eligible) -Name "fixture-input-contract-eligible" -Message "Input-contract evidence should remain eligible when only fixture-only samples have non-blocking issues."
Assert-Condition -Condition (-not [bool]$fixtureOnlyRun.report.releaseEvidence.eligible) -Name "fixture-full-release-ineligible" -Message "Full release evidence must remain ineligible when generation/runtime verification is not run."
Assert-Condition -Condition ([string]$fixtureOnlyRun.report.releaseEvidence.generationRuntimeVerificationStatus -eq "skipped") -Name "fixture-runtime-evidence-skipped" -Message "Skipped generation/runtime verification must be explicit in releaseEvidence."
Assert-Condition -Condition ([int]$fixtureOnlyRun.report.nonBlockingIssueCount -gt 0) -Name "fixture-only-issue-count" -Message "Fixture-only sample issues must be counted as non-blocking."
Assert-Condition -Condition ((@($fixtureOnlyRun.report.results | Where-Object sampleId -eq "fixture-only")[0]).classification -eq "fixture-only") -Name "fixture-only-classification" -Message "Fixture-only samples must be explicitly classified."

$releaseFailureFixture = New-SampleMatrixFixture -Root (Join-Path $testRoot "release-blocking") -ReleaseSampleMissingConfig
$releaseFailureReportPath = Join-Path $testRoot "release-blocking-report.json"
$releaseFailureRun = Invoke-SampleMatrixForTest -SampleRoot $releaseFailureFixture.sampleRoot -PolicyPath $releaseFailureFixture.policyPath -ReportPath $releaseFailureReportPath -EvidenceRoot (Join-Path $testRoot "release-blocking-evidence") -SkipGenerationRuntimeVerification
Assert-Condition -Condition ($releaseFailureRun.exitCode -ne 0) -Name "release-issue-exit" -Message "Release-blocking sample input issues must fail the sample matrix command."
Assert-Condition -Condition ($releaseFailureRun.report.overallStatus -eq "failed") -Name "release-issue-status" -Message "Release-blocking sample input issues must fail input-contract evidence."
Assert-Condition -Condition (-not [bool]$releaseFailureRun.report.inputContractEvidence.eligible) -Name "release-input-contract-ineligible" -Message "Input-contract evidence must be ineligible when release samples fail."
Assert-Condition -Condition (-not [bool]$releaseFailureRun.report.releaseEvidence.eligible) -Name "release-full-ineligible" -Message "Full release evidence must be ineligible when release samples fail."
Assert-Condition -Condition ([int]$releaseFailureRun.report.blockingIssueCount -gt 0) -Name "release-blocking-count" -Message "Release sample issues must be counted as blocking."

$actualReportPath = Join-Path $testRoot "actual-release-samples-report.json"
$actualRun = Invoke-SampleMatrixForTest -SampleRoot "NPDevSamples" -PolicyPath "scripts/policy/sample-matrix-policy.json" -ReportPath $actualReportPath -EvidenceRoot (Join-Path $testRoot "actual-release-samples-evidence") -SkipGenerationRuntimeVerification
$actualMedium = @($actualRun.report.results | Where-Object sampleId -eq "medium-expense-approval")[0]
$actualRestaurant = @($actualRun.report.results | Where-Object sampleId -eq "restaurant-saas-multitenant")[0]
$actualUserMinimal = @($actualRun.report.results | Where-Object sampleId -eq "user-minimal")[0]
Assert-Condition -Condition ($actualRun.exitCode -eq 0) -Name "actual-release-samples-exit" -Message "Actual strict release sample input contracts should pass after sample fixture/config repair."
Assert-Condition -Condition ($actualRun.report.overallStatus -eq "passed") -Name "actual-release-samples-status" -Message "Actual strict release sample input matrix should pass after sample repair."
Assert-Condition -Condition ([bool]$actualRun.report.inputContractEvidence.eligible) -Name "actual-input-contract-eligible" -Message "Actual inputContractEvidence should be eligible when all release sample inputs are valid."
Assert-Condition -Condition (-not [bool]$actualRun.report.releaseEvidence.eligible) -Name "actual-full-release-ineligible" -Message "Full release evidence must remain ineligible when generation/runtime verification is not run."
Assert-Condition -Condition ([string]$actualRun.report.releaseEvidence.generationRuntimeVerificationStatus -eq "skipped") -Name "actual-full-release-skipped-status" -Message "Actual input-contract-only fixture test must explicitly report skipped generation/runtime verification."
Assert-Condition -Condition ($actualMedium.status -eq "passed") -Name "actual-medium-passed" -Message "medium-expense-approval should pass the repaired input contract."
Assert-Condition -Condition ($actualRestaurant.status -eq "passed") -Name "actual-restaurant-passed" -Message "restaurant-saas-multitenant should pass the repaired input contract."
Assert-Condition -Condition ($actualUserMinimal.classification -eq "fixture-only" -and -not [bool]$actualUserMinimal.releaseBlocking) -Name "actual-user-minimal-fixture-only" -Message "user-minimal should remain fixture-only and non-blocking."

$sentinelAfter = if (Test-Path -LiteralPath $releaseSentinelPath -PathType Leaf) { Get-Content -LiteralPath $releaseSentinelPath -Raw } else { "" }
Assert-Condition -Condition ($sentinelAfter.Trim() -eq $releaseSentinelText) -Name "tests-do-not-overwrite-release-evidence-logs" -Message "Sample matrix tests must write diagnostic logs under scripts/reports/tmp and leave scripts/reports/out/sample-matrix untouched."
Assert-Condition -Condition (Test-Path -LiteralPath (Join-Path $testRoot "actual-release-samples-evidence/canonical-demo-verification.log") -PathType Leaf) -Name "tests-write-isolated-diagnostic-evidence" -Message "Sample matrix diagnostic evidence should be written under the test temp root."

$contractReport = $fixtureOnlyRun.report
$requiredTopLevel = @("matrixCoveragePercent", "coverage", "inputContractEvidence", "releaseEvidence", "inputFingerprints", "coverageAssertions", "cleanupPolicy", "results", "summary", "blockingIssueCount", "nonBlockingIssueCount")
foreach ($property in $requiredTopLevel) {
    Assert-Condition -Condition ($contractReport.PSObject.Properties.Name -contains $property) -Name ("contract-top-level-" + $property) -Message ("Sample matrix report is missing top-level field: " + $property)
}
$firstResult = @($contractReport.results)[0]
foreach ($property in @("sampleId", "kind", "classification", "releaseBlocking", "verificationCommand", "generationMarker", "cleanup", "outputSummary", "status", "blockingIssues", "nonBlockingIssues")) {
    Assert-Condition -Condition ($firstResult.PSObject.Properties.Name -contains $property) -Name ("contract-result-" + $property) -Message ("Sample matrix result is missing field: " + $property)
}

$overallStatus = if ($failures.Count -eq 0) { "passed" } else { "failed" }
$report = [pscustomobject]@{
    schemaVersion = "npdev-sample-matrix-test-report.v1"
    runId = $RunId
    generatedAt = (Get-Date).ToUniversalTime().ToString("o")
    scriptPath = "scripts/quality/run-sample-matrix-tests.ps1"
    workspaceRoot = $workspaceRoot
    overallStatus = $overallStatus
    testedReports = @(
        "scripts/reports/tmp/sample-matrix-tests/fixture-only-nonblocking-report.json",
        "scripts/reports/tmp/sample-matrix-tests/release-blocking-report.json",
        "scripts/reports/tmp/sample-matrix-tests/actual-release-samples-report.json",
        "scripts/reports/tmp/sample-matrix-tests/actual-release-samples-evidence/canonical-demo-verification.log"
    )
    assertions = [pscustomobject]@{
        failed = $failures.Count
    }
    failures = @($failures)
}

New-Item -ItemType Directory -Force -Path (Split-Path -Parent $ReportPath) | Out-Null
$report | ConvertTo-Json -Depth 30 | Set-Content -LiteralPath $ReportPath -Encoding UTF8

if ($overallStatus -eq "passed") {
    Write-Host ("Sample matrix tests passed. Report: " + $ReportPath)
    exit 0
}

Write-Error ("Sample matrix tests failed. Report: " + $ReportPath)
