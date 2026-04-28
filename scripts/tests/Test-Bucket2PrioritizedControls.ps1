Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

. (Join-Path $PSScriptRoot "..\npdev-common.ps1")

$script:WorkspaceRootForFixtures = Get-NPDevWorkspaceRoot $PSScriptRoot

function Write-JsonFileForTest {
    param(
        [string]$PathValue,
        [object]$Value
    )

    $parent = Split-Path -Parent $PathValue
    if (-not [string]::IsNullOrWhiteSpace($parent)) {
        New-Item -ItemType Directory -Force -Path $parent | Out-Null
    }
    $Value | ConvertTo-Json -Depth 50 | Set-Content -LiteralPath $PathValue -Encoding UTF8
}

function Read-JsonFileForTest {
    param(
        [string]$PathValue
    )

    return Get-Content -LiteralPath $PathValue -Raw | ConvertFrom-Json
}

function New-Bucket2PassFixture {
    param(
        [string]$RootPath
    )

    if (Test-Path -LiteralPath $RootPath) {
        Remove-Item -LiteralPath $RootPath -Recurse -Force
    }
    New-Item -ItemType Directory -Force -Path $RootPath | Out-Null

    foreach ($relativePath in @(
            "scripts\npdev-common.ps1",
            "scripts\statezip-common.ps1",
            "scripts\maturity_adv\maturity-common.ps1",
            "scripts\maturity_adv\prioritized-control-common.ps1",
            "scripts\maturity_adv\run-prioritized-control-board.ps1",
            "scripts\maturity_adv\check-b6-editor-boundary-enforcement-control.ps1",
            "scripts\maturity_adv\check-b7-kernel-adapter-strict-mode-control.ps1",
            "scripts\maturity_adv\check-b8-observability-health-hardening-control.ps1",
            "scripts\maturity_adv\check-b9-security-consistency-control.ps1",
            "scripts\maturity_adv\check-b10-generator-determinism-migration-risk-control.ps1",
            "scripts\maturity_adv\check-b11-ai-determinism-baseline-governance-control.ps1"
        )) {
        $sourcePath = Resolve-NPDevWorkspacePath $script:WorkspaceRootForFixtures $relativePath
        $destinationPath = Join-Path $RootPath $relativePath
        $destinationParent = Split-Path -Parent $destinationPath
        if (-not [string]::IsNullOrWhiteSpace($destinationParent)) {
            New-Item -ItemType Directory -Force -Path $destinationParent | Out-Null
        }
        Copy-Item -LiteralPath $sourcePath -Destination $destinationPath -Force
    }

    $outRoot = Join-Path $RootPath "scripts\reports\out"
    $baselineGovernancePath = Join-Path $outRoot "ai-baseline-governance-report.json"
    $matrixReportPath = Join-Path $outRoot "ai-beta-matrix-report.json"

    Write-JsonFileForTest -PathValue (Join-Path $outRoot "frontend-boundary-report.json") -Value @{
        generatedAt = "2026-04-23T00:00:00Z"
        runId = "b2-fixture"
        overallStatus = "passed"
        classificationGroups = @{
            authoring = @{ include = @(); exclude = @() }
            runtime = @{ include = @(); exclude = @() }
            shared = @{ include = @(); exclude = @() }
        }
        summary = @{
            totalTsxFiles = 3
            unclassifiedSourceFiles = 0
            multiClassifiedSourceFiles = 0
            importViolations = 0
            unresolvedLocalImports = 0
        }
        imports = @{
            violations = @()
            unresolvedLocalImports = @()
        }
        checks = @()
    }
    Write-JsonFileForTest -PathValue (Join-Path $outRoot "frontend-gate-report.json") -Value @{
        generatedAt = "2026-04-23T00:00:01Z"
        runId = "b2-fixture"
        overallStatus = "passed"
        boundaryAudit = @{
            reportPath = "scripts\reports\out\frontend-boundary-report.json"
            overallStatus = "passed"
        }
        subSteps = @()
        summary = @{
            failed = 0
            total = 4
        }
    }

    Write-JsonFileForTest -PathValue (Join-Path $outRoot "kernel-runtime-proof-report.json") -Value @{
        generatedAt = "2026-04-23T00:00:02Z"
        runId = "b2-fixture"
        overallStatus = "passed"
        policyPath = "scripts\policy\kernel-adapter-compatibility-matrix.json"
        mixedAdapterProof = @{
            status = "passed"
            junit = @{
                exists = $true
                passed = $true
            }
        }
        strictDefaultProof = @{
            status = "passed"
        }
        startupFailureProof = @{
            status = "passed"
            junit = @{
                exists = $true
                passed = $true
            }
        }
        compatibilityMatrix = @(
            @{
                capability = "persistence"
                supportedAdapters = @("persistence-postgres")
            }
        )
        checks = @()
        summary = @{
            failed = 0
            total = 5
        }
    }

    Write-JsonFileForTest -PathValue (Join-Path $outRoot "observability-hardening-report.json") -Value @{
        generatedAt = "2026-04-23T00:00:03Z"
        runId = "b2-fixture"
        overallStatus = "passed"
        correlationProof = @{
            status = "passed"
        }
        healthIndicatorCoverage = @{
            status = "passed"
            requiredStoreBackedSurfaces = @("EventStore", "TraceStore")
        }
        brokenBackendAggregation = @{
            status = "passed"
        }
        evidencePaths = @("scripts\reports\out\runtimehost-gate-report.json")
        checks = @()
        summary = @{
            failed = 0
            total = 4
        }
    }

    Write-JsonFileForTest -PathValue (Join-Path $outRoot "runtime-security-consistency-report.json") -Value @{
        generatedAt = "2026-04-23T00:00:04Z"
        runId = "b2-fixture"
        overallStatus = "passed"
        policyPath = "scripts\policy\security-sensitive-field-inventory.json"
        controllerSecurity = @{
            controllerCount = 4
            authMode = "jwt"
            authEnabled = "true"
        }
        experimentalSurfaceProof = @{
            status = "passed"
        }
        redactionCoverage = @{
            fields = @()
            missingFields = @()
        }
        checks = @()
        summary = @{
            failed = 0
            total = 5
        }
    }

    Write-JsonFileForTest -PathValue (Join-Path $outRoot "deterministic-generation-report.json") -Value @{
        generatedAt = "2026-04-23T00:00:05Z"
        workspaceRoot = (Normalize-NPDevPath $RootPath)
        sampleId = "simple-contact-intake"
        overallStatus = "passed"
    }
    Write-JsonFileForTest -PathValue (Join-Path $outRoot "generator-governance-report.json") -Value @{
        generatedAt = "2026-04-23T00:00:06Z"
        runId = "b2-fixture"
        overallStatus = "passed"
        policyPath = "scripts\policy\generator-determinism-policy.json"
        determinism = @{
            status = "passed"
        }
        migrationRisk = @{
            reportPaths = @("NPDevSamples\simple-contact-intake\Output\App\src\main\resources\npdev\support\migration-risk-report.json")
            canonicalOutput = "NPDevSamples\simple-contact-intake\Output\App\src\main\resources\npdev\support\migration-risk-report.json"
            comparisonAvailable = $true
            status = "baseline-present"
        }
        checks = @()
        summary = @{
            failed = 0
            total = 5
        }
    }

    Write-JsonFileForTest -PathValue $baselineGovernancePath -Value @{
        generatedAt = "2026-04-23T00:00:07Z"
        runId = "b2-fixture"
        overallStatus = "passed"
        determinismContract = @{
            modelIdentifier = "npdev-ai-beta-harness"
            modelVersion = "v1"
            temperature = 0
            seed = 20260423
        }
        cases = @(
            @{
                scenarioId = "base-ai-loop"
                reviewMetadata = @{
                    status = "passed"
                }
            }
        )
        checks = @()
        summary = @{
            failed = 0
            warnings = 0
            total = 5
        }
    }
    Write-JsonFileForTest -PathValue $matrixReportPath -Value @{
        generatedAt = "2026-04-23T00:00:08Z"
        runId = "b2-fixture"
        overallStatus = "passed"
        matrixId = "golden-ai-beta"
        caseCount = 1
        determinismContract = @{
            modelIdentifier = "npdev-ai-beta-harness"
            modelVersion = "v1"
            temperature = 0
            seed = 20260423
        }
        baselineGovernance = @{
            overallStatus = "passed"
        }
        cases = @(
            @{
                id = "base-ai-loop"
                determinism = @{
                    status = "passed"
                }
                baselines = @(
                    @{
                        checkedIn = $true
                    }
                )
            }
        )
    }

    return [pscustomobject]@{
        root = (Normalize-NPDevPath $RootPath)
        outRoot = $outRoot
    }
}

function Copy-TestFixture {
    param(
        [string]$SourceRoot,
        [string]$DestinationRoot
    )

    if (Test-Path -LiteralPath $DestinationRoot) {
        Remove-Item -LiteralPath $DestinationRoot -Recurse -Force
    }
    Copy-Item -LiteralPath $SourceRoot -Destination $DestinationRoot -Recurse -Force
    return (Normalize-NPDevPath $DestinationRoot)
}

$workspaceRoot = $script:WorkspaceRootForFixtures
$b6Script = Resolve-NPDevWorkspacePath $workspaceRoot "scripts\maturity_adv\check-b6-editor-boundary-enforcement-control.ps1"
$b7Script = Resolve-NPDevWorkspacePath $workspaceRoot "scripts\maturity_adv\check-b7-kernel-adapter-strict-mode-control.ps1"
$b8Script = Resolve-NPDevWorkspacePath $workspaceRoot "scripts\maturity_adv\check-b8-observability-health-hardening-control.ps1"
$b9Script = Resolve-NPDevWorkspacePath $workspaceRoot "scripts\maturity_adv\check-b9-security-consistency-control.ps1"
$b10Script = Resolve-NPDevWorkspacePath $workspaceRoot "scripts\maturity_adv\check-b10-generator-determinism-migration-risk-control.ps1"
$b11Script = Resolve-NPDevWorkspacePath $workspaceRoot "scripts\maturity_adv\check-b11-ai-determinism-baseline-governance-control.ps1"
$boardScript = Resolve-NPDevWorkspacePath $workspaceRoot "scripts\maturity_adv\run-prioritized-control-board.ps1"

$passFixtureRoot = Join-Path $env:TEMP "npdev-bucket2-controls-pass"
$b6FailFixtureRoot = Join-Path $env:TEMP "npdev-bucket2-controls-b6-fail"
$b7FailFixtureRoot = Join-Path $env:TEMP "npdev-bucket2-controls-b7-fail"
$b8FailFixtureRoot = Join-Path $env:TEMP "npdev-bucket2-controls-b8-fail"
$b9FailFixtureRoot = Join-Path $env:TEMP "npdev-bucket2-controls-b9-fail"
$b10FailFixtureRoot = Join-Path $env:TEMP "npdev-bucket2-controls-b10-fail"
$b11WarnFixtureRoot = Join-Path $env:TEMP "npdev-bucket2-controls-b11-warn"
$b11NotApplicableFixtureRoot = Join-Path $env:TEMP "npdev-bucket2-controls-b11-not-applicable"

$failures = [System.Collections.Generic.List[string]]::new()
function Assert-True {
    param(
        [bool]$Condition,
        [string]$Message
    )

    if (-not $Condition) {
        [void]$failures.Add($Message)
    }
}

try {
    $passFixture = New-Bucket2PassFixture -RootPath $passFixtureRoot
    Assert-True ([string](& $b6Script -WorkspaceRoot $passFixture.root -PassThru).overallStatus -eq "passed") "Expected B6 control to pass for the valid Bucket 2 fixture."
    Assert-True ([string](& $b7Script -WorkspaceRoot $passFixture.root -PassThru).overallStatus -eq "passed") "Expected B7 control to pass for the valid Bucket 2 fixture."
    Assert-True ([string](& $b8Script -WorkspaceRoot $passFixture.root -PassThru).overallStatus -eq "passed") "Expected B8 control to pass for the valid Bucket 2 fixture."
    Assert-True ([string](& $b9Script -WorkspaceRoot $passFixture.root -PassThru).overallStatus -eq "passed") "Expected B9 control to pass for the valid Bucket 2 fixture."
    Assert-True ([string](& $b10Script -WorkspaceRoot $passFixture.root -PassThru).overallStatus -eq "passed") "Expected B10 control to pass for the valid Bucket 2 fixture."
    Assert-True ([string](& $b11Script -WorkspaceRoot $passFixture.root -PassThru).overallStatus -eq "passed") "Expected B11 control to pass for the valid Bucket 2 fixture."

    $boardReport = & $boardScript -WorkspaceRoot $passFixture.root -RunId "test-bucket2-control-board" -Buckets @("B2") -PassThru
    Assert-True ($boardReport.summary.total -eq 6) ("Expected the Bucket 2 control board summary to aggregate six controls. Actual: " + $boardReport.summary.total)
    Assert-True (@($boardReport.controls).Count -eq 6) "Expected the Bucket 2 control board to expose six control entries."
    Assert-True ([string]$boardReport.overallStatus -eq "passed") ("Expected the Bucket 2 control board to pass for the valid fixture. Actual: " + [string]$boardReport.overallStatus)

    $b6FailRoot = Copy-TestFixture -SourceRoot $passFixture.root -DestinationRoot $b6FailFixtureRoot
    $b6ReportPath = Join-Path $b6FailRoot "scripts\reports\out\frontend-boundary-report.json"
    $b6Report = Read-JsonFileForTest $b6ReportPath
    $b6Report.overallStatus = "failed"
    $b6Report.summary.importViolations = 1
    Write-JsonFileForTest -PathValue $b6ReportPath -Value $b6Report
    Assert-True ([string](& $b6Script -WorkspaceRoot $b6FailRoot -PassThru).overallStatus -eq "failed") "Expected B6 control to fail when the boundary report fails."

    $b7FailRoot = Copy-TestFixture -SourceRoot $passFixture.root -DestinationRoot $b7FailFixtureRoot
    $b7ReportPath = Join-Path $b7FailRoot "scripts\reports\out\kernel-runtime-proof-report.json"
    $b7Report = Read-JsonFileForTest $b7ReportPath
    $b7Report.overallStatus = "failed"
    $b7Report.mixedAdapterProof.status = "failed"
    $b7Report.mixedAdapterProof.junit.passed = $false
    Write-JsonFileForTest -PathValue $b7ReportPath -Value $b7Report
    Assert-True ([string](& $b7Script -WorkspaceRoot $b7FailRoot -PassThru).overallStatus -eq "failed") "Expected B7 control to fail when the mixed-adapter proof fails."

    $b8FailRoot = Copy-TestFixture -SourceRoot $passFixture.root -DestinationRoot $b8FailFixtureRoot
    $b8ReportPath = Join-Path $b8FailRoot "scripts\reports\out\observability-hardening-report.json"
    $b8Report = Read-JsonFileForTest $b8ReportPath
    $b8Report.overallStatus = "failed"
    $b8Report.brokenBackendAggregation.status = "failed"
    Write-JsonFileForTest -PathValue $b8ReportPath -Value $b8Report
    Assert-True ([string](& $b8Script -WorkspaceRoot $b8FailRoot -PassThru).overallStatus -eq "failed") "Expected B8 control to fail when broken-backend aggregation coverage fails."

    $b9FailRoot = Copy-TestFixture -SourceRoot $passFixture.root -DestinationRoot $b9FailFixtureRoot
    $b9ReportPath = Join-Path $b9FailRoot "scripts\reports\out\runtime-security-consistency-report.json"
    $b9Report = Read-JsonFileForTest $b9ReportPath
    $b9Report.overallStatus = "failed"
    $b9Report.redactionCoverage.missingFields = @("email")
    Write-JsonFileForTest -PathValue $b9ReportPath -Value $b9Report
    Assert-True ([string](& $b9Script -WorkspaceRoot $b9FailRoot -PassThru).overallStatus -eq "failed") "Expected B9 control to fail when redaction coverage is incomplete."

    $b10FailRoot = Copy-TestFixture -SourceRoot $passFixture.root -DestinationRoot $b10FailFixtureRoot
    $b10ReportPath = Join-Path $b10FailRoot "scripts\reports\out\generator-governance-report.json"
    $b10Report = Read-JsonFileForTest $b10ReportPath
    $b10Report.overallStatus = "failed"
    $b10Report.migrationRisk.canonicalOutput = $null
    $b10Report.migrationRisk.reportPaths = @()
    Write-JsonFileForTest -PathValue $b10ReportPath -Value $b10Report
    Assert-True ([string](& $b10Script -WorkspaceRoot $b10FailRoot -PassThru).overallStatus -eq "failed") "Expected B10 control to fail when migration-risk wiring is missing."

    $b11WarnRoot = Copy-TestFixture -SourceRoot $passFixture.root -DestinationRoot $b11WarnFixtureRoot
    $b11GovernancePath = Join-Path $b11WarnRoot "scripts\reports\out\ai-baseline-governance-report.json"
    $b11Governance = Read-JsonFileForTest $b11GovernancePath
    $b11Governance.overallStatus = "warning"
    $b11Governance.cases[0].reviewMetadata.status = "warning"
    $b11Governance.summary.warnings = 1
    Write-JsonFileForTest -PathValue $b11GovernancePath -Value $b11Governance
    $b11MatrixPath = Join-Path $b11WarnRoot "scripts\reports\out\ai-beta-matrix-report.json"
    $b11Matrix = Read-JsonFileForTest $b11MatrixPath
    $b11Matrix.overallStatus = "warning"
    $b11Matrix.baselineGovernance.overallStatus = "warning"
    Write-JsonFileForTest -PathValue $b11MatrixPath -Value $b11Matrix
    Assert-True ([string](& $b11Script -WorkspaceRoot $b11WarnRoot -PassThru).overallStatus -eq "warning") "Expected B11 control to return warning when only review metadata freshness is warning-only."

    $b11NotApplicableRoot = Copy-TestFixture -SourceRoot $passFixture.root -DestinationRoot $b11NotApplicableFixtureRoot
    $b11NotApplicableMatrixPath = Join-Path $b11NotApplicableRoot "scripts\reports\out\ai-beta-matrix-report.json"
    $b11NotApplicableMatrix = Read-JsonFileForTest $b11NotApplicableMatrixPath
    $b11NotApplicableMatrix.cases[0].determinism.status = "not-applicable"
    Write-JsonFileForTest -PathValue $b11NotApplicableMatrixPath -Value $b11NotApplicableMatrix
    Assert-True ([string](& $b11Script -WorkspaceRoot $b11NotApplicableRoot -PassThru).overallStatus -eq "passed") "Expected B11 control to accept governed not-applicable determinism status."
}
catch {
    [void]$failures.Add($_.Exception.Message)
}
finally {
    foreach ($pathValue in @($passFixtureRoot, $b6FailFixtureRoot, $b7FailFixtureRoot, $b8FailFixtureRoot, $b9FailFixtureRoot, $b10FailFixtureRoot, $b11WarnFixtureRoot, $b11NotApplicableFixtureRoot)) {
        if (Test-Path -LiteralPath $pathValue) {
            Remove-Item -LiteralPath $pathValue -Recurse -Force
        }
    }
}

if ($failures.Count -eq 0) {
    Write-NPDevOk "Bucket 2 prioritized control tests passed."
    exit 0
}

foreach ($failure in $failures) {
    Write-NPDevWarn $failure
}
throw "Bucket 2 prioritized control tests failed."
