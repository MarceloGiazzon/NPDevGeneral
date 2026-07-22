param(
    [string]$RunId = "",
    [string]$ReportPath = "scripts/reports/out/final-regression-coverage-audit-tests-report.json"
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
    param([string]$Path, [object]$Value)
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $Path) | Out-Null
    $Value | ConvertTo-Json -Depth 80 | Set-Content -LiteralPath $Path -Encoding UTF8
}

function Read-JsonFile {
    param([string]$Path)
    return Get-Content -Raw -LiteralPath $Path | ConvertFrom-Json
}

function Invoke-Audit {
    param([hashtable]$AuditArgs)
    $argList = @("-NoProfile", "-File", "scripts/quality/run-final-regression-coverage-audit.ps1")
    foreach ($key in $AuditArgs.Keys) {
        $argList += ("-" + $key)
        $argList += [string]$AuditArgs[$key]
    }
    $ErrorActionPreference = "Continue"
    & pwsh @argList 2>&1 | Out-Null
    $exitCode = $LASTEXITCODE
    $ErrorActionPreference = "Stop"
    return $exitCode
}

function New-Fixture {
    param(
        [string]$Root,
        [bool]$IncludeSampleRequirement = $true,
        [bool]$WireSchemas = $true,
        [string[]]$OmitFinalEntrypoints = @()
    )
    New-Item -ItemType Directory -Force -Path $Root | Out-Null
    $policyPath = Join-Path $Root "beta-release-gate-policy.json"
    $scopePath = Join-Path $Root "beta0-scope.json"
    $manifestPath = Join-Path $Root "final-regression-coverage-manifest.json"
    $finalScriptPath = Join-Path $Root "run-beta0-final-release-check.ps1"
    $schemaScriptPath = Join-Path $Root "run-report-schema-validation.ps1"
    $samplePath = Join-Path $Root "sample-matrix-report.json"
    $betaPath = Join-Path $Root "beta-release-gate-report.json"
    $auditPath = Join-Path $Root "final-regression-coverage-audit-report.json"

    $sampleEvidenceRequirements = @()
    if ($IncludeSampleRequirement) {
        $sampleEvidenceRequirements += [pscustomobject]@{
            path = "releaseEvidence.eligible"
            expected = $true
            releaseBlocking = $true
            classification = "blocking-release-evidence"
            reason = "fixture"
        }
    }
    Write-JsonFile -Path $policyPath -Value ([pscustomobject]@{
            schemaVersion = "npdev-beta-release-gate-policy.v1"
            requiredReports = @(
                [pscustomobject]@{
                    name = "final-regression-coverage-audit"
                    path = "scripts/reports/out/final-regression-coverage-audit-report.json"
                },
                [pscustomobject]@{
                    name = "sample-matrix"
                    path = "scripts/reports/out/sample-matrix-report.json"
                    evidenceRequirements = $sampleEvidenceRequirements
                }
            )
        })
    Write-JsonFile -Path $scopePath -Value ([pscustomobject]@{
            schemaVersion = "npdev-beta0-scope.v2"
            blockingReports = @("final-regression-coverage-audit-report.json", "sample-matrix-report.json")
        })
    $realManifest = Read-JsonFile "scripts/policy/final-regression-coverage-manifest.json"
    Write-JsonFile -Path $manifestPath -Value $realManifest
    $entrypoints = @($realManifest.coverageItems | ForEach-Object { @($_.finalEntrypoints) } | Where-Object { -not [string]::IsNullOrWhiteSpace([string]$_) })
    $entrypoints += @(
        "scripts/quality/run-final-regression-coverage-audit.ps1",
        "scripts/quality/run-report-provenance-tests.ps1",
        "scripts/quality/run-report-schema-validation.ps1"
    )
    $entrypoints = @($entrypoints | Sort-Object -Unique)
    $entrypoints = @($entrypoints | Where-Object { $OmitFinalEntrypoints -notcontains [string]$_ })
    $entrypoints | Set-Content -LiteralPath $finalScriptPath -Encoding UTF8
    $schemaText = if ($WireSchemas) {
        @"
schemas/ai/direct-evidence-hardening-tests-report.schema.json
schemas/ai/traceable-local-release-report.schema.json
schemas/ai/roadmap-closure-check-report.schema.json
schemas/ai/runbook-workflow-alignment-tests-report.schema.json
schemas/ai/final-regression-coverage-audit-report.schema.json
"@
    }
    else {
        "schemas/ai/direct-evidence-hardening-tests-report.schema.json"
    }
    $schemaText | Set-Content -LiteralPath $schemaScriptPath -Encoding UTF8
    Write-JsonFile -Path $samplePath -Value ([pscustomobject]@{
            schemaVersion = "npdev-sample-matrix-report.v1"
            overallStatus = "passed"
            releaseEvidence = [pscustomobject]@{ eligible = $false }
        })
    Write-JsonFile -Path $betaPath -Value ([pscustomobject]@{
            schemaVersion = "beta-release-gate-report.v1"
            overallStatus = "failed"
            blockers = @("fixture red")
        })
    return [pscustomobject]@{
        policyPath = $policyPath
        scopePath = $scopePath
        manifestPath = $manifestPath
        finalScriptPath = $finalScriptPath
        schemaScriptPath = $schemaScriptPath
        samplePath = $samplePath
        betaPath = $betaPath
        auditPath = $auditPath
    }
}

$workspaceRoot = (Resolve-Path ".").Path
if ([string]::IsNullOrWhiteSpace($RunId)) {
    $RunId = "final-regression-coverage-audit-tests-" + (Get-Date).ToUniversalTime().ToString("yyyyMMdd-HHmmssfff")
}
$script:failures = @()
$testRoot = Join-Path $workspaceRoot "scripts/reports/tmp/final-regression-coverage-audit-tests"
if (Test-Path -LiteralPath $testRoot -PathType Container) {
    Remove-Item -LiteralPath $testRoot -Recurse -Force
}
New-Item -ItemType Directory -Force -Path $testRoot | Out-Null

$passingFixture = New-Fixture -Root (Join-Path $testRoot "passing-with-release-blockers") -IncludeSampleRequirement:$true -WireSchemas:$true
$passingExit = Invoke-Audit -AuditArgs @{
    RunId = $RunId
    ReportPath = $passingFixture.auditPath
    PolicyPath = $passingFixture.policyPath
    ScopePath = $passingFixture.scopePath
    CoverageManifestPath = $passingFixture.manifestPath
    FinalReleaseScriptPath = $passingFixture.finalScriptPath
    ReportSchemaValidationScriptPath = $passingFixture.schemaScriptPath
    SampleMatrixReportPath = $passingFixture.samplePath
    BetaReleaseGateReportPath = $passingFixture.betaPath
}
$passingReport = Read-JsonFile $passingFixture.auditPath
Assert-Condition -Condition ($passingExit -eq 0) -Name "audit-passes-coverage-while-release-blocked" -Message "Audit should pass coverage controls while preserving separate release blockers."
Assert-Condition -Condition ([string]$passingReport.releaseReadinessStatus -eq "blocked") -Name "audit-records-release-blocked" -Message "Audit must record blocked release readiness when sample runtime evidence is absent."
Assert-Condition -Condition (@($passingReport.releaseBlockers).Count -gt 0) -Name "audit-lists-release-blockers" -Message "Audit must list known release blockers separately from coverage failures."

$missingRequirementFixture = New-Fixture -Root (Join-Path $testRoot "missing-sample-requirement") -IncludeSampleRequirement:$false -WireSchemas:$true
$missingRequirementExit = Invoke-Audit -AuditArgs @{
    RunId = $RunId
    ReportPath = $missingRequirementFixture.auditPath
    PolicyPath = $missingRequirementFixture.policyPath
    ScopePath = $missingRequirementFixture.scopePath
    CoverageManifestPath = $missingRequirementFixture.manifestPath
    FinalReleaseScriptPath = $missingRequirementFixture.finalScriptPath
    ReportSchemaValidationScriptPath = $missingRequirementFixture.schemaScriptPath
    SampleMatrixReportPath = $missingRequirementFixture.samplePath
    BetaReleaseGateReportPath = $missingRequirementFixture.betaPath
}
$missingRequirementReport = Read-JsonFile $missingRequirementFixture.auditPath
Assert-Condition -Condition ($missingRequirementExit -ne 0) -Name "audit-fails-missing-sample-release-requirement" -Message "Audit should fail when sample releaseEvidence.eligible is no longer blocking."
Assert-Condition -Condition (@($missingRequirementReport.failures | Where-Object { [string]$_ -match "sample-release-evidence-remains-blocking" }).Count -gt 0) -Name "audit-records-sample-requirement-failure" -Message "Audit must record the missing sample release evidence requirement."

$missingSchemaFixture = New-Fixture -Root (Join-Path $testRoot "missing-schema-wiring") -IncludeSampleRequirement:$true -WireSchemas:$false
$missingSchemaExit = Invoke-Audit -AuditArgs @{
    RunId = $RunId
    ReportPath = $missingSchemaFixture.auditPath
    PolicyPath = $missingSchemaFixture.policyPath
    ScopePath = $missingSchemaFixture.scopePath
    CoverageManifestPath = $missingSchemaFixture.manifestPath
    FinalReleaseScriptPath = $missingSchemaFixture.finalScriptPath
    ReportSchemaValidationScriptPath = $missingSchemaFixture.schemaScriptPath
    SampleMatrixReportPath = $missingSchemaFixture.samplePath
    BetaReleaseGateReportPath = $missingSchemaFixture.betaPath
}
$missingSchemaReport = Read-JsonFile $missingSchemaFixture.auditPath
Assert-Condition -Condition ($missingSchemaExit -ne 0) -Name "audit-fails-missing-schema-validation-wiring" -Message "Audit should fail when report-schema validation does not cover governance schemas."
Assert-Condition -Condition (@($missingSchemaReport.failures | Where-Object { [string]$_ -match "schema-validation-case-wired" }).Count -gt 0) -Name "audit-records-schema-wiring-failure" -Message "Audit must record missing schema-validation wiring."

$representativeMissingEntrypoints = @(
    [pscustomobject]@{ name = "frontend-gate-tests"; omitted = "scripts/quality/run-frontend-gate-tests.ps1"; expectedCoverage = "frontend-gate-success-failure-tests" },
    [pscustomobject]@{ name = "runtimehost-staged-jar-preflight-tests"; omitted = "scripts/quality/run-runtimehost-staged-jar-preflight-tests.ps1"; expectedCoverage = "runtimehost-full-staged-jar-preflight" },
    [pscustomobject]@{ name = "trusted-source-proof-tests"; omitted = "scripts/quality/run-trusted-source-beta0-proof-tests.ps1"; expectedCoverage = "trusted-source-manifest-locking" },
    [pscustomobject]@{ name = "scope-policy-enforcement-tests"; omitted = "scripts/quality/run-scope-policy-enforcement-tests.ps1"; expectedCoverage = "scope-policy-enforcement-drift-tests" },
    [pscustomobject]@{ name = "structured-command-alignment"; omitted = "scripts/quality/run-structured-command-surface-alignment.ps1"; expectedCoverage = "structured-command-schema-policy-runner-alignment" },
    [pscustomobject]@{ name = "sample-matrix-strict-semantics-tests"; omitted = "scripts/quality/run-sample-matrix-tests.ps1"; expectedCoverage = "strict-sample-matrix-semantics" }
)
$representativeReports = @()
foreach ($case in $representativeMissingEntrypoints) {
    $fixture = New-Fixture -Root (Join-Path $testRoot ("missing-" + [string]$case.name)) -IncludeSampleRequirement:$true -WireSchemas:$true -OmitFinalEntrypoints @([string]$case.omitted)
    $exitCode = Invoke-Audit -AuditArgs @{
        RunId = $RunId
        ReportPath = $fixture.auditPath
        PolicyPath = $fixture.policyPath
        ScopePath = $fixture.scopePath
        CoverageManifestPath = $fixture.manifestPath
        FinalReleaseScriptPath = $fixture.finalScriptPath
        ReportSchemaValidationScriptPath = $fixture.schemaScriptPath
        SampleMatrixReportPath = $fixture.samplePath
        BetaReleaseGateReportPath = $fixture.betaPath
    }
    $report = Read-JsonFile $fixture.auditPath
    $representativeReports += $fixture.auditPath
    $matchingCoverage = @($report.coverageItems | Where-Object { [string]$_.id -eq [string]$case.expectedCoverage } | Select-Object -First 1)
    Assert-Condition -Condition ($exitCode -ne 0) -Name ("audit-fails-missing-" + [string]$case.name) -Message ("Audit should fail when final release path omits " + [string]$case.omitted)
    Assert-Condition -Condition ($matchingCoverage.Count -eq 1 -and -not [bool]$matchingCoverage[0].passed) -Name ("audit-records-missing-" + [string]$case.name) -Message ("Audit should record failed coverage item " + [string]$case.expectedCoverage)
}

$overallStatus = if ($failures.Count -eq 0) { "passed" } else { "failed" }
$report = [pscustomobject]@{
    schemaVersion = "npdev-final-regression-coverage-audit-test-report.v1"
    runId = $RunId
    generatedAt = (Get-Date).ToUniversalTime().ToString("o")
    scriptPath = "scripts/quality/run-final-regression-coverage-audit-tests.ps1"
    workspaceRoot = $workspaceRoot
    overallStatus = $overallStatus
    assertions = [pscustomobject]@{
        failed = $failures.Count
        names = @(
            "audit-passes-coverage-while-release-blocked",
            "audit-fails-missing-sample-release-requirement",
            "audit-fails-missing-schema-validation-wiring",
            "audit-fails-missing-frontend-gate-tests",
            "audit-fails-missing-runtimehost-staged-jar-preflight-tests",
            "audit-fails-missing-trusted-source-proof-tests",
            "audit-fails-missing-scope-policy-enforcement-tests",
            "audit-fails-missing-structured-command-alignment",
            "audit-fails-missing-sample-matrix-strict-semantics-tests"
        )
    }
    fixtureReports = @($passingFixture.auditPath, $missingRequirementFixture.auditPath, $missingSchemaFixture.auditPath) + @($representativeReports)
    failures = @($failures)
}

New-Item -ItemType Directory -Force -Path (Split-Path -Parent $ReportPath) | Out-Null
$report | ConvertTo-Json -Depth 60 | Set-Content -LiteralPath $ReportPath -Encoding UTF8

if ($overallStatus -eq "passed") {
    Write-Host ("Final regression coverage audit tests passed. Report: " + $ReportPath)
    exit 0
}

Write-Error ("Final regression coverage audit tests failed. Report: " + $ReportPath)
