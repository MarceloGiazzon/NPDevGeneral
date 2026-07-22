param(
    [string]$RunId = "",
    [string]$ReportPath = "scripts/reports/out/direct-evidence-hardening-tests-report.json"
)

$ErrorActionPreference = "Stop"

function Add-TestFailure {
    param([string]$Name, [string]$Message)
    $script:failures += [pscustomobject]@{
        name = $Name
        message = $Message
    }
}

function Assert-Condition {
    param([bool]$Condition, [string]$Name, [string]$Message)
    if (-not $Condition) {
        Add-TestFailure -Name $Name -Message $Message
    }
}

function Write-JsonFile {
    param([object]$Value, [string]$Path)
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $Path) | Out-Null
    $Value | ConvertTo-Json -Depth 80 | Set-Content -LiteralPath $Path -Encoding UTF8
}

function Read-JsonFile {
    param([string]$Path)
    return Get-Content -Raw -LiteralPath $Path | ConvertFrom-Json
}

function New-SampleReport {
    param(
        [string]$RunId,
        [bool]$ReleaseEvidenceEligible,
        [string]$Path
    )
    Write-JsonFile -Path $Path -Value ([pscustomobject]@{
            schemaVersion = "npdev-sample-matrix-report.v1"
            runId = $RunId
            generatedAt = (Get-Date).ToUniversalTime().ToString("o")
            scriptPath = "test-fixture"
            overallStatus = "passed"
            inputContractEvidence = [pscustomobject]@{
                eligible = $true
                status = "eligible"
                reason = "Fixture input contracts passed."
            }
            releaseEvidence = [pscustomobject]@{
                eligible = $ReleaseEvidenceEligible
                status = if ($ReleaseEvidenceEligible) { "eligible" } else { "not-eligible" }
                reason = if ($ReleaseEvidenceEligible) { "Fixture full release evidence is explicitly present." } else { "Generation/runtime verification was not run; this must not satisfy release eligibility." }
            }
        })
}

function New-GateFixture {
    param(
        [string]$Root,
        [string]$RunId,
        [bool]$ReleaseEvidenceEligible
    )
    New-Item -ItemType Directory -Force -Path $Root | Out-Null
    $sampleReportPath = Join-Path $Root "sample-matrix-report.json"
    $scopePath = Join-Path $Root "beta0-scope.json"
    $truthPath = Join-Path $Root "truth-table.json"
    $policyPath = Join-Path $Root "beta-release-gate-policy.json"
    New-SampleReport -RunId $RunId -ReleaseEvidenceEligible $ReleaseEvidenceEligible -Path $sampleReportPath
    Write-JsonFile -Path $scopePath -Value ([pscustomobject]@{
            schemaVersion = "npdev-beta0-scope.v2"
            release = "ai-only-beta-0"
            scopePolicySingleSource = $true
            officialEvidencePlatform = "windows-ci+docker-linux-ci"
            dockerLinuxEvidence = "blocking-release-evidence"
            dockerRequiredForBeta0 = $true
            blockingReports = @($sampleReportPath)
        })
    Write-JsonFile -Path $truthPath -Value ([pscustomobject]@{
            schemaVersion = "npdev-beta0-release-truth-table.v1"
            candidateReady = [pscustomobject]@{ requires = @("sampleMatrixPassed") }
            releaseReady = [pscustomobject]@{ requires = @("candidateReady") }
            provenanceReady = [pscustomobject]@{ requires = @() }
            officialReleaseEligible = [pscustomobject]@{ requires = @("releaseReady", "provenanceReady") }
        })
    Write-JsonFile -Path $policyPath -Value ([pscustomobject]@{
            schemaVersion = "npdev-beta-release-gate-policy.v1"
            release = "ai-only-beta-0"
            maxReportAgeHours = 24
            officialEvidencePlatform = "windows-ci+docker-linux-ci"
            dockerLinuxEvidence = "blocking-release-evidence"
            scopePolicy = $scopePath
            truthTable = $truthPath
            requiredReports = @(
                [pscustomobject]@{
                    name = "sample-matrix"
                    path = $sampleReportPath
                    schemaVersion = "npdev-sample-matrix-report.v1"
                    statusProperty = "overallStatus"
                    passValue = "passed"
                    evidenceRequirements = @(
                        [pscustomobject]@{
                            path = "releaseEvidence.eligible"
                            expected = $true
                            releaseBlocking = $true
                            classification = "blocking-release-evidence"
                            reason = "Input-contract-only matrix evidence must not count as full release evidence."
                        }
                    )
                }
            )
            informationalReports = @()
            readinessRule = "test fixture"
        })
    return [pscustomobject]@{
        policyPath = $policyPath
        reportPath = Join-Path $Root "beta-release-gate-report.json"
        manifestPath = Join-Path $Root "beta-release-evidence-manifest.json"
        summaryPath = Join-Path $Root "release-ready-summary.json"
        sampleReportPath = $sampleReportPath
    }
}

function Invoke-GateFixture {
    param([object]$Fixture, [string]$RunId)
    $ErrorActionPreference = "Continue"
    pwsh -NoProfile -File scripts/quality/run-beta-release-gate.ps1 `
        -PolicyPath $Fixture.policyPath `
        -ReportPath $Fixture.reportPath `
        -ManifestPath $Fixture.manifestPath `
        -SummaryPath $Fixture.summaryPath `
        -RunId $RunId 2>&1 | Out-Null
    $exitCode = $LASTEXITCODE
    $ErrorActionPreference = "Stop"
    return [pscustomobject]@{
        exitCode = $exitCode
        report = Read-JsonFile $Fixture.reportPath
    }
}

$workspaceRoot = (Resolve-Path ".").Path
if ([string]::IsNullOrWhiteSpace($RunId)) {
    $RunId = "direct-evidence-hardening-tests-" + (Get-Date).ToUniversalTime().ToString("yyyyMMdd-HHmmssfff")
}
$script:failures = @()
$testRoot = Join-Path $workspaceRoot "scripts/reports/tmp/direct-evidence-hardening-tests"
if (Test-Path -LiteralPath $testRoot -PathType Container) {
    Remove-Item -LiteralPath $testRoot -Recurse -Force
}
New-Item -ItemType Directory -Force -Path $testRoot | Out-Null

$partialFixture = New-GateFixture -Root (Join-Path $testRoot "input-contract-only") -RunId $RunId -ReleaseEvidenceEligible:$false
$partialRun = Invoke-GateFixture -Fixture $partialFixture -RunId $RunId
$partialRequired = @($partialRun.report.requiredReports | Where-Object name -eq "sample-matrix" | Select-Object -First 1)
$partialRequirement = @($partialRequired.evidenceRequirements | Where-Object path -eq "releaseEvidence.eligible" | Select-Object -First 1)
Assert-Condition -Condition ($partialRun.exitCode -ne 0) -Name "input-only-gate-fails" -Message "Input-contract-only sample evidence must fail the release gate."
Assert-Condition -Condition (-not [bool]$partialRequired.valid) -Name "input-only-report-invalid" -Message "The sample-matrix required report must be invalid when releaseEvidence.eligible=false."
Assert-Condition -Condition (-not [bool]$partialRequirement.passed) -Name "input-only-requirement-failed" -Message "The blocking releaseEvidence.eligible requirement must be recorded as failed."
Assert-Condition -Condition (@($partialRequired.blockers | Where-Object { [string]$_ -match "releaseEvidence\.eligible" }).Count -gt 0) -Name "input-only-blocker-source" -Message "The blocker must cite the releaseEvidence.eligible source field."

$fullFixture = New-GateFixture -Root (Join-Path $testRoot "full-release-evidence") -RunId $RunId -ReleaseEvidenceEligible:$true
$fullRun = Invoke-GateFixture -Fixture $fullFixture -RunId $RunId
$fullRequired = @($fullRun.report.requiredReports | Where-Object name -eq "sample-matrix" | Select-Object -First 1)
$fullRequirement = @($fullRequired.evidenceRequirements | Where-Object path -eq "releaseEvidence.eligible" | Select-Object -First 1)
$sampleTruth = @($fullRun.report.truthTableEvaluation | Where-Object name -eq "candidateReady" | Select-Object -First 1).requires | Where-Object name -eq "sampleMatrixPassed" | Select-Object -First 1
Assert-Condition -Condition ([bool]$fullRequired.valid) -Name "full-evidence-report-valid" -Message "The sample-matrix report should satisfy required-report validation when releaseEvidence.eligible=true."
Assert-Condition -Condition ([bool]$fullRequirement.passed) -Name "full-evidence-requirement-passed" -Message "The blocking releaseEvidence.eligible requirement should pass when true."
Assert-Condition -Condition ([string]$sampleTruth.evidence.evidenceType -eq "required-report") -Name "truth-table-direct-evidence" -Message "Truth-table sampleMatrixPassed must carry direct required-report evidence."
Assert-Condition -Condition ([string]$fullRun.report.directEvidenceSummary.evidenceContractVersion -eq "npdev-direct-release-evidence.v1") -Name "direct-evidence-summary" -Message "Release gate report must emit the direct evidence summary contract."

$overallStatus = if ($failures.Count -eq 0) { "passed" } else { "failed" }
$report = [pscustomobject]@{
    schemaVersion = "npdev-direct-evidence-hardening-test-report.v1"
    runId = $RunId
    generatedAt = (Get-Date).ToUniversalTime().ToString("o")
    scriptPath = "scripts/quality/run-direct-evidence-hardening-tests.ps1"
    workspaceRoot = $workspaceRoot
    overallStatus = $overallStatus
    testedReports = @(
        "scripts/reports/tmp/direct-evidence-hardening-tests/input-contract-only/beta-release-gate-report.json",
        "scripts/reports/tmp/direct-evidence-hardening-tests/full-release-evidence/beta-release-gate-report.json"
    )
    assertions = [pscustomobject]@{
        failed = $failures.Count
    }
    failures = @($failures)
}

New-Item -ItemType Directory -Force -Path (Split-Path -Parent $ReportPath) | Out-Null
$report | ConvertTo-Json -Depth 40 | Set-Content -LiteralPath $ReportPath -Encoding UTF8

if ($overallStatus -eq "passed") {
    Write-Host ("Direct evidence hardening tests passed. Report: " + $ReportPath)
    exit 0
}

Write-Error ("Direct evidence hardening tests failed. Report: " + $ReportPath)
