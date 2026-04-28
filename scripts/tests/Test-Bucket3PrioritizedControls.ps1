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

function New-Bucket3PassFixture {
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
            "scripts\maturity_adv\check-b12-sample-diagnostics-enrichment-control.ps1",
            "scripts\maturity_adv\check-b13-contract-schema-mirror-simplification-control.ps1",
            "scripts\maturity_adv\check-b14-cross-project-vocabulary-build-boundary-polish-control.ps1",
            "scripts\maturity_adv\check-b15-documentation-digest-governance-control.ps1",
            "scripts\maturity_adv\check-b16-script-automation-quality-polish-control.ps1"
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

    Write-JsonFileForTest -PathValue (Join-Path $outRoot "sample-matrix-report.json") -Value @{
        generatedAt = "2026-04-23T00:00:00Z"
        runId = "b3-fixture"
        overallStatus = "passed"
        matrixCoveragePercent = 100.0
        coverage = @{
            requiredReleaseCoveragePercent = 100.0
        }
        coverageAssertions = @{
            releaseMatrixCoverageSatisfied = $true
            releaseMatrixCoveragePercent = 100.0
            requiredReleaseCoveragePercent = 100.0
            releaseEvidenceEligible = $true
        }
        cleanupPolicy = @{
            mode = "build-caches-only"
        }
        releaseEvidence = @{
            eligible = $true
        }
        inputFingerprints = @(
            @{
                sampleId = "simple-contact-intake"
                kind = "official-sample"
                inputRoot = "NPDevSamples\\simple-contact-intake\\Input"
                files = @()
                issues = @()
            }
        )
        results = @(
            @{
                sampleId = "simple-contact-intake"
                kind = "official-sample"
                status = "passed"
                inputFingerprint = @{
                    sampleId = "simple-contact-intake"
                    kind = "official-sample"
                    inputRoot = "NPDevSamples\\simple-contact-intake\\Input"
                    files = @()
                    issues = @()
                }
                verificationCommand = @{
                    status = "passed"
                    workingDirectory = "NPDevSamples\\simple-contact-intake\\Output\\App"
                    executable = ".\\gradlew.bat"
                    arguments = @("--no-daemon", "--console=plain", "enforceSingleMigrationSource", "test")
                    display = ".\\gradlew.bat --no-daemon --console=plain enforceSingleMigrationSource test"
                    exitCode = 0
                    startedAt = "2026-04-23T00:00:00Z"
                    endedAt = "2026-04-23T00:00:10Z"
                    durationSeconds = 10.0
                    logPath = "scripts\\reports\\out\\sample-matrix\\simple-contact-intake-verification.log"
                }
                generationMarker = @{
                    status = "current"
                }
                cleanup = @{
                    status = "passed"
                    reportPath = "scripts\\reports\\out\\sample-matrix\\simple-contact-intake-clean-report.json"
                    removedPaths = @("NPDevSamples\\simple-contact-intake\\Output\\App\\build")
                    retainedEvidencePaths = @("NPDevSamples\\simple-contact-intake\\Output\\App\\MIGRATION_DIGEST.md")
                }
                outputSummary = @{
                    appRoot = "NPDevSamples\\simple-contact-intake\\Output\\App"
                    exists = $true
                    fileCount = 10
                    sizeBytes = 1000
                }
            }
        )
        summary = @{
            failed = 0
            warnings = 0
            passed = 1
            total = 1
        }
    }
    Write-JsonFileForTest -PathValue (Join-Path $outRoot "sample-diagnostics-enrichment-report.json") -Value @{
        generatedAt = "2026-04-23T00:00:01Z"
        runId = "b3-fixture"
        overallStatus = "passed"
        matrixReportPath = "scripts\\reports\\out\\sample-matrix-report.json"
        sampleAudits = @(
            @{
                sampleId = "simple-contact-intake"
                verificationLogExists = $true
                cleanupReportExists = $true
                cleanupMatchesResult = $true
                removedRetainedEvidencePaths = @()
            }
        )
        checks = @(
            @{ name = "per-result-diagnostics"; status = "passed" },
            @{ name = "verification-log-evidence"; status = "passed" },
            @{ name = "cleanup-report-evidence"; status = "passed" },
            @{ name = "retained-evidence-preserved"; status = "passed" },
            @{ name = "coverage-assertions"; status = "passed" }
        )
        summary = @{
            failed = 0
            warnings = 0
            passed = 5
            total = 5
        }
    }

    Write-JsonFileForTest -PathValue (Join-Path $outRoot "contract-gate-report.json") -Value @{
        generatedAt = "2026-04-23T00:00:02Z"
        runId = "b3-fixture"
        overallStatus = "passed"
        workingDirectory = "NPDevContract\\dsl"
        command = @{
            executable = "D:\\Fixture\\NPDevContract\\dsl\\gradlew.bat"
        }
        contractSchemaGovernance = @{
            overallStatus = "passed"
            reportPath = "scripts\\reports\\out\\contract-schema-governance-report.json"
        }
    }
    Write-JsonFileForTest -PathValue (Join-Path $outRoot "contract-schema-governance-report.json") -Value @{
        generatedAt = "2026-04-23T00:00:03Z"
        runId = "b3-fixture"
        overallStatus = "passed"
        inventoryPath = "scripts\\policy\\contract-schema-version-inventory.json"
        schemaInventory = @(
            @{
                name = "canonical-model-alias"
                matchesInventory = $true
            }
        )
        aliasBehavior = @{
            passed = $true
        }
        regressionCoverage = @(
            @{
                name = "deprecated-schema-rejection"
                passed = $true
            }
        )
        mirrorSync = @{
            overallStatus = "passed"
            summary = @{
                failed = 0
            }
        }
        checks = @()
        summary = @{
            failed = 0
            warnings = 0
            passed = 5
            total = 5
        }
    }

    Write-JsonFileForTest -PathValue (Join-Path $outRoot "cross-project-boundary-report.json") -Value @{
        generatedAt = "2026-04-23T00:00:04Z"
        runId = "b3-fixture"
        overallStatus = "passed"
        gateAudits = @(
            @{ name = "contract-gate"; passed = $true },
            @{ name = "editor-gate"; passed = $true },
            @{
                name = "frontend-gate"
                passed = $true
                detail = @{
                    passed = $true
                    steps = @(
                        @{
                            name = "dependency-install"
                            actualWorkingDirectory = "NPDevEditor"
                            actualExecutable = "NPDevEditor\\gradlew.bat"
                            passed = $true
                        },
                        @{
                            name = "test"
                            actualWorkingDirectory = "NPDevEditor"
                            actualExecutable = "NPDevEditor\\gradlew.bat"
                            passed = $true
                        },
                        @{
                            name = "build"
                            actualWorkingDirectory = "NPDevEditor"
                            actualExecutable = "NPDevEditor\\gradlew.bat"
                            passed = $true
                        }
                    )
                }
            },
            @{ name = "generator-gate"; passed = $true },
            @{ name = "kernel-gate"; passed = $true },
            @{
                name = "runtimehost-gate"
                passed = $true
                detail = @{
                    passed = $true
                    actualWorkingDirectory = "NPDevSamples\\simple-contact-intake\\Output\\App"
                    actualExecutable = ".\\gradlew.bat"
                    assembledAppRoot = "D:\\Fixture\\NPDevSamples\\simple-contact-intake\\Output\\App"
                }
            }
        )
        checks = @(
            @{ name = "vocabulary-checks"; status = "passed" },
            @{ name = "canonical-legacy-surface-checks"; status = "passed" },
            @{ name = "root-build-aggregator-only"; status = "passed" },
            @{ name = "subproject-local-gate-execution"; status = "passed" }
        )
        summary = @{
            failed = 0
            warnings = 0
            passed = 4
            total = 4
        }
    }

    Write-JsonFileForTest -PathValue (Join-Path $outRoot "documentation-digest-governance-report.json") -Value @{
        generatedAt = "2026-04-23T00:00:05Z"
        runId = "b3-fixture"
        overallStatus = "passed"
        digests = @()
        checks = @(
            @{ name = "required-project-digests"; status = "passed" },
            @{ name = "generated-sample-migration-digests"; status = "passed" },
            @{ name = "referenced-path-integrity"; status = "passed" },
            @{ name = "freshness-markers"; status = "passed" }
        )
        summary = @{
            failed = 0
            warnings = 0
            passed = 4
            total = 4
        }
    }

    Write-JsonFileForTest -PathValue (Join-Path $outRoot "script-automation-quality-report.json") -Value @{
        generatedAt = "2026-04-23T00:00:06Z"
        runId = "b3-fixture"
        overallStatus = "passed"
        parserValidation = @{
            checkedPaths = @(
                "scripts\\quality\\run-runtime-surface-evidence.ps1",
                "scripts\\quality\\run-traceable-local-release.ps1"
            )
            failures = @()
        }
        structuredReportContract = @{
            checkedPaths = @(
                "scripts\\quality\\run-runtime-surface-evidence.ps1",
                "scripts\\quality\\run-traceable-local-release.ps1"
            )
            failures = @()
        }
        commonHelperCoverage = @{
            checkedPaths = @(
                "scripts\\quality\\run-runtime-surface-evidence.ps1",
                "scripts\\quality\\run-traceable-local-release.ps1"
            )
            failures = @()
        }
        sharedModuleRegression = @{
            results = @(
                @{
                    name = "helper-regression"
                    passed = $true
                }
            )
        }
        analyzer = @{
            available = $false
            violations = @()
        }
        checks = @()
        summary = @{
            failed = 0
            warnings = 0
            passed = 5
            total = 5
        }
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
$b12Script = Resolve-NPDevWorkspacePath $workspaceRoot "scripts\maturity_adv\check-b12-sample-diagnostics-enrichment-control.ps1"
$b13Script = Resolve-NPDevWorkspacePath $workspaceRoot "scripts\maturity_adv\check-b13-contract-schema-mirror-simplification-control.ps1"
$b14Script = Resolve-NPDevWorkspacePath $workspaceRoot "scripts\maturity_adv\check-b14-cross-project-vocabulary-build-boundary-polish-control.ps1"
$b15Script = Resolve-NPDevWorkspacePath $workspaceRoot "scripts\maturity_adv\check-b15-documentation-digest-governance-control.ps1"
$b16Script = Resolve-NPDevWorkspacePath $workspaceRoot "scripts\maturity_adv\check-b16-script-automation-quality-polish-control.ps1"
$boardScript = Resolve-NPDevWorkspacePath $workspaceRoot "scripts\maturity_adv\run-prioritized-control-board.ps1"

$passFixtureRoot = Join-Path $env:TEMP "npdev-bucket3-controls-pass"
$b12LogFailFixtureRoot = Join-Path $env:TEMP "npdev-bucket3-controls-b12-log-fail"
$b12CleanupFailFixtureRoot = Join-Path $env:TEMP "npdev-bucket3-controls-b12-cleanup-fail"
$b12RetainedFailFixtureRoot = Join-Path $env:TEMP "npdev-bucket3-controls-b12-retained-fail"
$b12CoverageFailFixtureRoot = Join-Path $env:TEMP "npdev-bucket3-controls-b12-coverage-fail"
$b13InventoryFailFixtureRoot = Join-Path $env:TEMP "npdev-bucket3-controls-b13-inventory-fail"
$b13AliasFailFixtureRoot = Join-Path $env:TEMP "npdev-bucket3-controls-b13-alias-fail"
$b13RegressionFailFixtureRoot = Join-Path $env:TEMP "npdev-bucket3-controls-b13-regression-fail"
$b13MirrorFailFixtureRoot = Join-Path $env:TEMP "npdev-bucket3-controls-b13-mirror-fail"
$b14VocabularyFailFixtureRoot = Join-Path $env:TEMP "npdev-bucket3-controls-b14-vocabulary-fail"
$b14ExecutionFailFixtureRoot = Join-Path $env:TEMP "npdev-bucket3-controls-b14-execution-fail"
$b14RootBuildFailFixtureRoot = Join-Path $env:TEMP "npdev-bucket3-controls-b14-root-fail"
$b15MissingDigestFixtureRoot = Join-Path $env:TEMP "npdev-bucket3-controls-b15-digest-fail"
$b15BrokenRefFixtureRoot = Join-Path $env:TEMP "npdev-bucket3-controls-b15-reference-fail"
$b15MigrationDigestFixtureRoot = Join-Path $env:TEMP "npdev-bucket3-controls-b15-migration-fail"
$b15WarningFixtureRoot = Join-Path $env:TEMP "npdev-bucket3-controls-b15-warning"
$b16ParserFailFixtureRoot = Join-Path $env:TEMP "npdev-bucket3-controls-b16-parser-fail"
$b16ContractFailFixtureRoot = Join-Path $env:TEMP "npdev-bucket3-controls-b16-contract-fail"
$b16HelperFailFixtureRoot = Join-Path $env:TEMP "npdev-bucket3-controls-b16-helper-fail"
$b16AnalyzerFailFixtureRoot = Join-Path $env:TEMP "npdev-bucket3-controls-b16-analyzer-fail"
$b16AnalyzerUnavailableFixtureRoot = Join-Path $env:TEMP "npdev-bucket3-controls-b16-analyzer-unavailable"

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
    $passFixture = New-Bucket3PassFixture -RootPath $passFixtureRoot
    $passContractGateReport = Read-JsonFileForTest (Join-Path $passFixture.root "scripts\reports\out\contract-gate-report.json")
    $passCrossProjectReport = Read-JsonFileForTest (Join-Path $passFixture.root "scripts\reports\out\cross-project-boundary-report.json")
    $passScriptQualityReport = Read-JsonFileForTest (Join-Path $passFixture.root "scripts\reports\out\script-automation-quality-report.json")
    Assert-True ([string]$passContractGateReport.contractSchemaGovernance.overallStatus -eq "passed") "Expected the valid Bucket 3 fixture to expose contract schema governance linkage on the contract gate report."
    Assert-True ([string]$passContractGateReport.contractSchemaGovernance.reportPath -eq "scripts\\reports\\out\\contract-schema-governance-report.json") "Expected the valid Bucket 3 fixture to expose the governance report path on the contract gate report."
    Assert-True (@($passCrossProjectReport.gateAudits | Where-Object { [string]$_.name -eq "frontend-gate" -and [bool]$_.passed }).Count -eq 1) "Expected the valid Bucket 3 fixture to expose a passing frontend gate audit."
    Assert-True (@($passCrossProjectReport.gateAudits | Where-Object { [string]$_.name -eq "runtimehost-gate" -and [bool]$_.passed }).Count -eq 1) "Expected the valid Bucket 3 fixture to expose a passing runtimehost gate audit."
    Assert-True (@($passScriptQualityReport.structuredReportContract.checkedPaths | Where-Object { $_ -eq "scripts\\quality\\run-runtime-surface-evidence.ps1" }).Count -eq 1) "Expected the valid Bucket 3 fixture to audit run-runtime-surface-evidence.ps1 for the structured report contract."
    Assert-True (@($passScriptQualityReport.structuredReportContract.checkedPaths | Where-Object { $_ -eq "scripts\\quality\\run-traceable-local-release.ps1" }).Count -eq 1) "Expected the valid Bucket 3 fixture to audit run-traceable-local-release.ps1 for the structured report contract."
    Assert-True ([string](& $b12Script -WorkspaceRoot $passFixture.root -PassThru).overallStatus -eq "passed") "Expected B12 control to pass for the valid Bucket 3 fixture."
    Assert-True ([string](& $b13Script -WorkspaceRoot $passFixture.root -PassThru).overallStatus -eq "passed") "Expected B13 control to pass for the valid Bucket 3 fixture."
    Assert-True ([string](& $b14Script -WorkspaceRoot $passFixture.root -PassThru).overallStatus -eq "passed") "Expected B14 control to pass for the valid Bucket 3 fixture."
    Assert-True ([string](& $b15Script -WorkspaceRoot $passFixture.root -PassThru).overallStatus -eq "passed") "Expected B15 control to pass for the valid Bucket 3 fixture."
    Assert-True ([string](& $b16Script -WorkspaceRoot $passFixture.root -PassThru).overallStatus -eq "passed") "Expected B16 control to pass for the valid Bucket 3 fixture."

    $boardReport = & $boardScript -WorkspaceRoot $passFixture.root -RunId "test-bucket3-control-board" -Buckets @("B3") -PassThru
    Assert-True ($boardReport.summary.total -eq 5) ("Expected the Bucket 3 control board summary to aggregate five controls. Actual: " + $boardReport.summary.total)
    Assert-True (@($boardReport.controls).Count -eq 5) "Expected the Bucket 3 control board to expose five control entries."
    Assert-True ([string]$boardReport.overallStatus -eq "passed") ("Expected the Bucket 3 control board to pass for the valid fixture. Actual: " + [string]$boardReport.overallStatus)

    $b12LogFailRoot = Copy-TestFixture -SourceRoot $passFixture.root -DestinationRoot $b12LogFailFixtureRoot
    $b12LogFailReport = Read-JsonFileForTest (Join-Path $b12LogFailRoot "scripts\reports\out\sample-matrix-report.json")
    $b12LogFailReport.results[0].verificationCommand.logPath = $null
    Write-JsonFileForTest -PathValue (Join-Path $b12LogFailRoot "scripts\reports\out\sample-matrix-report.json") -Value $b12LogFailReport
    Assert-True ([string](& $b12Script -WorkspaceRoot $b12LogFailRoot -PassThru).overallStatus -eq "failed") "Expected B12 control to fail when verification logPath is missing."

    $b12CleanupFailRoot = Copy-TestFixture -SourceRoot $passFixture.root -DestinationRoot $b12CleanupFailFixtureRoot
    $b12CleanupFailReport = Read-JsonFileForTest (Join-Path $b12CleanupFailRoot "scripts\reports\out\sample-diagnostics-enrichment-report.json")
    $b12CleanupFailReport.checks[2].status = "failed"
    Write-JsonFileForTest -PathValue (Join-Path $b12CleanupFailRoot "scripts\reports\out\sample-diagnostics-enrichment-report.json") -Value $b12CleanupFailReport
    Assert-True ([string](& $b12Script -WorkspaceRoot $b12CleanupFailRoot -PassThru).overallStatus -eq "failed") "Expected B12 control to fail when cleanup report evidence is missing."

    $b12RetainedFailRoot = Copy-TestFixture -SourceRoot $passFixture.root -DestinationRoot $b12RetainedFailFixtureRoot
    $b12RetainedFailReport = Read-JsonFileForTest (Join-Path $b12RetainedFailRoot "scripts\reports\out\sample-diagnostics-enrichment-report.json")
    $b12RetainedFailReport.checks[3].status = "failed"
    Write-JsonFileForTest -PathValue (Join-Path $b12RetainedFailRoot "scripts\reports\out\sample-diagnostics-enrichment-report.json") -Value $b12RetainedFailReport
    Assert-True ([string](& $b12Script -WorkspaceRoot $b12RetainedFailRoot -PassThru).overallStatus -eq "failed") "Expected B12 control to fail when retained evidence was removed."

    $b12CoverageFailRoot = Copy-TestFixture -SourceRoot $passFixture.root -DestinationRoot $b12CoverageFailFixtureRoot
    $b12CoverageFailReport = Read-JsonFileForTest (Join-Path $b12CoverageFailRoot "scripts\reports\out\sample-matrix-report.json")
    $b12CoverageFailReport.coverageAssertions.releaseMatrixCoverageSatisfied = $false
    Write-JsonFileForTest -PathValue (Join-Path $b12CoverageFailRoot "scripts\reports\out\sample-matrix-report.json") -Value $b12CoverageFailReport
    Assert-True ([string](& $b12Script -WorkspaceRoot $b12CoverageFailRoot -PassThru).overallStatus -eq "failed") "Expected B12 control to fail when coverage assertions drift from actual coverage."

    $b13InventoryFailRoot = Copy-TestFixture -SourceRoot $passFixture.root -DestinationRoot $b13InventoryFailFixtureRoot
    $b13InventoryReport = Read-JsonFileForTest (Join-Path $b13InventoryFailRoot "scripts\reports\out\contract-schema-governance-report.json")
    $b13InventoryReport.schemaInventory[0].matchesInventory = $false
    Write-JsonFileForTest -PathValue (Join-Path $b13InventoryFailRoot "scripts\reports\out\contract-schema-governance-report.json") -Value $b13InventoryReport
    Assert-True ([string](& $b13Script -WorkspaceRoot $b13InventoryFailRoot -PassThru).overallStatus -eq "failed") "Expected B13 control to fail when schema inventory drifts."

    $b13AliasFailRoot = Copy-TestFixture -SourceRoot $passFixture.root -DestinationRoot $b13AliasFailFixtureRoot
    $b13AliasReport = Read-JsonFileForTest (Join-Path $b13AliasFailRoot "scripts\reports\out\contract-schema-governance-report.json")
    $b13AliasReport.aliasBehavior.passed = $false
    Write-JsonFileForTest -PathValue (Join-Path $b13AliasFailRoot "scripts\reports\out\contract-schema-governance-report.json") -Value $b13AliasReport
    Assert-True ([string](& $b13Script -WorkspaceRoot $b13AliasFailRoot -PassThru).overallStatus -eq "failed") "Expected B13 control to fail when deprecated alias behavior regresses."

    $b13RegressionFailRoot = Copy-TestFixture -SourceRoot $passFixture.root -DestinationRoot $b13RegressionFailFixtureRoot
    $b13RegressionReport = Read-JsonFileForTest (Join-Path $b13RegressionFailRoot "scripts\reports\out\contract-schema-governance-report.json")
    $b13RegressionReport.regressionCoverage[0].passed = $false
    Write-JsonFileForTest -PathValue (Join-Path $b13RegressionFailRoot "scripts\reports\out\contract-schema-governance-report.json") -Value $b13RegressionReport
    Assert-True ([string](& $b13Script -WorkspaceRoot $b13RegressionFailRoot -PassThru).overallStatus -eq "failed") "Expected B13 control to fail when deprecated schema rejection proof is missing."

    $b13MirrorFailRoot = Copy-TestFixture -SourceRoot $passFixture.root -DestinationRoot $b13MirrorFailFixtureRoot
    $b13MirrorReport = Read-JsonFileForTest (Join-Path $b13MirrorFailRoot "scripts\reports\out\contract-schema-governance-report.json")
    $b13MirrorReport.mirrorSync.overallStatus = "failed"
    $b13MirrorReport.mirrorSync.summary.failed = 1
    Write-JsonFileForTest -PathValue (Join-Path $b13MirrorFailRoot "scripts\reports\out\contract-schema-governance-report.json") -Value $b13MirrorReport
    Assert-True ([string](& $b13Script -WorkspaceRoot $b13MirrorFailRoot -PassThru).overallStatus -eq "failed") "Expected B13 control to fail when exact mirror sync fails."

    $b14VocabularyFailRoot = Copy-TestFixture -SourceRoot $passFixture.root -DestinationRoot $b14VocabularyFailFixtureRoot
    $b14VocabularyReport = Read-JsonFileForTest (Join-Path $b14VocabularyFailRoot "scripts\reports\out\cross-project-boundary-report.json")
    $b14VocabularyReport.checks[0].status = "failed"
    Write-JsonFileForTest -PathValue (Join-Path $b14VocabularyFailRoot "scripts\reports\out\cross-project-boundary-report.json") -Value $b14VocabularyReport
    Assert-True ([string](& $b14Script -WorkspaceRoot $b14VocabularyFailRoot -PassThru).overallStatus -eq "failed") "Expected B14 control to fail when blocked vocabulary leaks are present."

    $b14ExecutionFailRoot = Copy-TestFixture -SourceRoot $passFixture.root -DestinationRoot $b14ExecutionFailFixtureRoot
    $b14ExecutionReport = Read-JsonFileForTest (Join-Path $b14ExecutionFailRoot "scripts\reports\out\cross-project-boundary-report.json")
    $b14ExecutionReport.gateAudits[0].passed = $false
    $b14ExecutionReport.checks[3].status = "failed"
    Write-JsonFileForTest -PathValue (Join-Path $b14ExecutionFailRoot "scripts\reports\out\cross-project-boundary-report.json") -Value $b14ExecutionReport
    Assert-True ([string](& $b14Script -WorkspaceRoot $b14ExecutionFailRoot -PassThru).overallStatus -eq "failed") "Expected B14 control to fail when gate workingDirectory evidence is stale or misaligned."

    $b14RootBuildFailRoot = Copy-TestFixture -SourceRoot $passFixture.root -DestinationRoot $b14RootBuildFailFixtureRoot
    $b14RootBuildReport = Read-JsonFileForTest (Join-Path $b14RootBuildFailRoot "scripts\reports\out\cross-project-boundary-report.json")
    $b14RootBuildReport.checks[2].status = "failed"
    Write-JsonFileForTest -PathValue (Join-Path $b14RootBuildFailRoot "scripts\reports\out\cross-project-boundary-report.json") -Value $b14RootBuildReport
    Assert-True ([string](& $b14Script -WorkspaceRoot $b14RootBuildFailRoot -PassThru).overallStatus -eq "failed") "Expected B14 control to fail when root build coupling is detected."

    $b15MissingDigestRoot = Copy-TestFixture -SourceRoot $passFixture.root -DestinationRoot $b15MissingDigestFixtureRoot
    $b15MissingDigestReport = Read-JsonFileForTest (Join-Path $b15MissingDigestRoot "scripts\reports\out\documentation-digest-governance-report.json")
    $b15MissingDigestReport.overallStatus = "failed"
    $b15MissingDigestReport.checks[0].status = "failed"
    Write-JsonFileForTest -PathValue (Join-Path $b15MissingDigestRoot "scripts\reports\out\documentation-digest-governance-report.json") -Value $b15MissingDigestReport
    Assert-True ([string](& $b15Script -WorkspaceRoot $b15MissingDigestRoot -PassThru).overallStatus -eq "failed") "Expected B15 control to fail when a required PROJECT_DIGEST.md is missing."

    $b15BrokenRefRoot = Copy-TestFixture -SourceRoot $passFixture.root -DestinationRoot $b15BrokenRefFixtureRoot
    $b15BrokenRefReport = Read-JsonFileForTest (Join-Path $b15BrokenRefRoot "scripts\reports\out\documentation-digest-governance-report.json")
    $b15BrokenRefReport.overallStatus = "failed"
    $b15BrokenRefReport.checks[2].status = "failed"
    Write-JsonFileForTest -PathValue (Join-Path $b15BrokenRefRoot "scripts\reports\out\documentation-digest-governance-report.json") -Value $b15BrokenRefReport
    Assert-True ([string](& $b15Script -WorkspaceRoot $b15BrokenRefRoot -PassThru).overallStatus -eq "failed") "Expected B15 control to fail when digest references point to missing repo paths."

    $b15MigrationDigestRoot = Copy-TestFixture -SourceRoot $passFixture.root -DestinationRoot $b15MigrationDigestFixtureRoot
    $b15MigrationDigestReport = Read-JsonFileForTest (Join-Path $b15MigrationDigestRoot "scripts\reports\out\documentation-digest-governance-report.json")
    $b15MigrationDigestReport.overallStatus = "failed"
    $b15MigrationDigestReport.checks[1].status = "failed"
    Write-JsonFileForTest -PathValue (Join-Path $b15MigrationDigestRoot "scripts\reports\out\documentation-digest-governance-report.json") -Value $b15MigrationDigestReport
    Assert-True ([string](& $b15Script -WorkspaceRoot $b15MigrationDigestRoot -PassThru).overallStatus -eq "failed") "Expected B15 control to fail when a generated sample MIGRATION_DIGEST.md is missing."

    $b15WarningRoot = Copy-TestFixture -SourceRoot $passFixture.root -DestinationRoot $b15WarningFixtureRoot
    $b15WarningReport = Read-JsonFileForTest (Join-Path $b15WarningRoot "scripts\reports\out\documentation-digest-governance-report.json")
    $b15WarningReport.overallStatus = "warning"
    $b15WarningReport.checks[3].status = "warning"
    $b15WarningReport.summary.warnings = 1
    Write-JsonFileForTest -PathValue (Join-Path $b15WarningRoot "scripts\reports\out\documentation-digest-governance-report.json") -Value $b15WarningReport
    Assert-True ([string](& $b15Script -WorkspaceRoot $b15WarningRoot -PassThru).overallStatus -eq "warning") "Expected B15 control to return warning when only explicit freshness markers are stale."

    $b16ParserFailRoot = Copy-TestFixture -SourceRoot $passFixture.root -DestinationRoot $b16ParserFailFixtureRoot
    $b16ParserFailReport = Read-JsonFileForTest (Join-Path $b16ParserFailRoot "scripts\reports\out\script-automation-quality-report.json")
    $b16ParserFailReport.parserValidation.failures = @(@{ path = "scripts\\quality\\broken.ps1" })
    Write-JsonFileForTest -PathValue (Join-Path $b16ParserFailRoot "scripts\reports\out\script-automation-quality-report.json") -Value $b16ParserFailReport
    Assert-True ([string](& $b16Script -WorkspaceRoot $b16ParserFailRoot -PassThru).overallStatus -eq "failed") "Expected B16 control to fail when parser validation fails."

    $b16ContractFailRoot = Copy-TestFixture -SourceRoot $passFixture.root -DestinationRoot $b16ContractFailFixtureRoot
    $b16ContractFailReport = Read-JsonFileForTest (Join-Path $b16ContractFailRoot "scripts\reports\out\script-automation-quality-report.json")
    $b16ContractFailReport.structuredReportContract.failures = @(@{ path = "scripts\\quality\\missing-report.ps1" })
    Write-JsonFileForTest -PathValue (Join-Path $b16ContractFailRoot "scripts\reports\out\script-automation-quality-report.json") -Value $b16ContractFailReport
    Assert-True ([string](& $b16Script -WorkspaceRoot $b16ContractFailRoot -PassThru).overallStatus -eq "failed") "Expected B16 control to fail when the structured report contract is missing."

    $b16HelperFailRoot = Copy-TestFixture -SourceRoot $passFixture.root -DestinationRoot $b16HelperFailFixtureRoot
    $b16HelperFailReport = Read-JsonFileForTest (Join-Path $b16HelperFailRoot "scripts\reports\out\script-automation-quality-report.json")
    $b16HelperFailReport.sharedModuleRegression.results[0].passed = $false
    Write-JsonFileForTest -PathValue (Join-Path $b16HelperFailRoot "scripts\reports\out\script-automation-quality-report.json") -Value $b16HelperFailReport
    Assert-True ([string](& $b16Script -WorkspaceRoot $b16HelperFailRoot -PassThru).overallStatus -eq "failed") "Expected B16 control to fail when shared helper regression checks fail."

    $b16AnalyzerFailRoot = Copy-TestFixture -SourceRoot $passFixture.root -DestinationRoot $b16AnalyzerFailFixtureRoot
    $b16AnalyzerFailReport = Read-JsonFileForTest (Join-Path $b16AnalyzerFailRoot "scripts\reports\out\script-automation-quality-report.json")
    $b16AnalyzerFailReport.analyzer.available = $true
    $b16AnalyzerFailReport.analyzer.violations = @(@{ ruleName = "PSAvoidUsingWriteHost" })
    Write-JsonFileForTest -PathValue (Join-Path $b16AnalyzerFailRoot "scripts\reports\out\script-automation-quality-report.json") -Value $b16AnalyzerFailReport
    Assert-True ([string](& $b16Script -WorkspaceRoot $b16AnalyzerFailRoot -PassThru).overallStatus -eq "failed") "Expected B16 control to fail when PSScriptAnalyzer finds violations."

    $b16AnalyzerUnavailableRoot = Copy-TestFixture -SourceRoot $passFixture.root -DestinationRoot $b16AnalyzerUnavailableFixtureRoot
    $b16AnalyzerUnavailableReport = Read-JsonFileForTest (Join-Path $b16AnalyzerUnavailableRoot "scripts\reports\out\script-automation-quality-report.json")
    $b16AnalyzerUnavailableReport.analyzer.available = $false
    $b16AnalyzerUnavailableReport.analyzer.violations = @()
    Write-JsonFileForTest -PathValue (Join-Path $b16AnalyzerUnavailableRoot "scripts\reports\out\script-automation-quality-report.json") -Value $b16AnalyzerUnavailableReport
    Assert-True ([string](& $b16Script -WorkspaceRoot $b16AnalyzerUnavailableRoot -PassThru).overallStatus -eq "passed") "Expected B16 control to keep analyzer unavailability non-blocking."
}
catch {
    [void]$failures.Add($_.Exception.Message)
}
finally {
    foreach ($pathValue in @(
            $passFixtureRoot,
            $b12LogFailFixtureRoot,
            $b12CleanupFailFixtureRoot,
            $b12RetainedFailFixtureRoot,
            $b12CoverageFailFixtureRoot,
            $b13InventoryFailFixtureRoot,
            $b13AliasFailFixtureRoot,
            $b13RegressionFailFixtureRoot,
            $b13MirrorFailFixtureRoot,
            $b14VocabularyFailFixtureRoot,
            $b14ExecutionFailFixtureRoot,
            $b14RootBuildFailFixtureRoot,
            $b15MissingDigestFixtureRoot,
            $b15BrokenRefFixtureRoot,
            $b15MigrationDigestFixtureRoot,
            $b15WarningFixtureRoot,
            $b16ParserFailFixtureRoot,
            $b16ContractFailFixtureRoot,
            $b16HelperFailFixtureRoot,
            $b16AnalyzerFailFixtureRoot,
            $b16AnalyzerUnavailableFixtureRoot
        )) {
        if (Test-Path -LiteralPath $pathValue) {
            Remove-Item -LiteralPath $pathValue -Recurse -Force
        }
    }
}

if ($failures.Count -eq 0) {
    Write-NPDevOk "Bucket 3 prioritized control tests passed."
    exit 0
}

foreach ($failure in $failures) {
    Write-NPDevWarn $failure
}
throw "Bucket 3 prioritized control tests failed."
