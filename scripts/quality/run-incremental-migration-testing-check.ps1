param(
    [string]$WorkspaceRoot = ".",
    [string]$ReportPath = "scripts/reports/out/incremental-migration-testing-report.json",
    [string]$RunId = ""
)

$ErrorActionPreference = "Stop"

function Convert-ToRepoPath {
    param([string]$Root, [string]$PathValue)
    if ([string]::IsNullOrWhiteSpace($PathValue)) {
        return ""
    }
    $resolvedRoot = [System.IO.Path]::GetFullPath($Root)
    $resolvedPath = [System.IO.Path]::GetFullPath($PathValue)
    if ($resolvedPath.StartsWith($resolvedRoot, [System.StringComparison]::OrdinalIgnoreCase)) {
        return ($resolvedPath.Substring($resolvedRoot.Length).TrimStart("\", "/") -replace "\\", "/")
    }
    return ($resolvedPath -replace "\\", "/")
}

function Invoke-CommandCapture {
    param(
        [string]$Name,
        [scriptblock]$ScriptBlock,
        [int]$ExpectedExitCode = 0
    )
    $started = Get-Date
    $output = @(& $ScriptBlock 2>&1 | ForEach-Object { $_.ToString() })
    $exitCode = $LASTEXITCODE
    if ($null -eq $exitCode) {
        $exitCode = 0
    }
    $finished = Get-Date
    return [pscustomobject]@{
        name = $Name
        exitCode = $exitCode
        expectedExitCode = $ExpectedExitCode
        passed = ($exitCode -eq $ExpectedExitCode)
        durationSeconds = [math]::Round(($finished - $started).TotalSeconds, 3)
        outputTail = @($output | Select-Object -Last 100)
    }
}

function Add-Check {
    param(
        [System.Collections.Generic.List[object]]$Checks,
        [string]$Name,
        [bool]$Passed,
        [object]$Evidence
    )
    $Checks.Add([pscustomobject]@{
            name = $Name
            passed = $Passed
            evidence = $Evidence
        }) | Out-Null
}

function Get-GradleWrapper {
    # REG-11: gate on the OS, NOT on whether gradlew.bat exists. Both wrappers are committed at the
    # repo root, so the file always exists on Linux too -- the old existence check returned the .bat
    # and then failed to execute it on a Linux CI runner.
    if ($IsWindows) {
        if (Test-Path -LiteralPath ".\gradlew.bat" -PathType Leaf) { return ".\gradlew.bat" }
    }
    return "./gradlew"
}

function Get-NPDevCommand {
    if (Test-Path -LiteralPath ".\npdev.bat" -PathType Leaf) {
        return ".\npdev.bat"
    }
    return "./npdev"
}

$root = (Resolve-Path $WorkspaceRoot).Path
if ([string]::IsNullOrWhiteSpace($RunId)) {
    $RunId = "incremental-migration-testing-" + (Get-Date).ToUniversalTime().ToString("yyyyMMdd-HHmmssfff")
}

$workRoot = Join-Path $root "build/cp9-incremental-migration-testing"
if (Test-Path -LiteralPath $workRoot) {
    Remove-Item -LiteralPath $workRoot -Recurse -Force
}
New-Item -ItemType Directory -Force -Path $workRoot | Out-Null
$proofRoot = Join-Path $workRoot "proof"
$testResultRoot = Join-Path $workRoot "test-results"
New-Item -ItemType Directory -Force -Path $proofRoot | Out-Null
New-Item -ItemType Directory -Force -Path $testResultRoot | Out-Null

$checks = [System.Collections.Generic.List[object]]::new()
$gradle = Get-GradleWrapper
$npdev = Get-NPDevCommand

$incrementalProofPath = Join-Path $proofRoot "incremental-postgres-proof.json"
$unsafeProofPath = Join-Path $proofRoot "unsafe-backfill-proof.json"
$testResult = Invoke-CommandCapture "incremental-migration-harness-tests" {
    $previousIncremental = $env:NPDEV_CP9_INCREMENTAL_PROOF_PATH
    $previousUnsafe = $env:NPDEV_CP9_UNSAFE_PROOF_PATH
    $env:NPDEV_CP9_INCREMENTAL_PROOF_PATH = $incrementalProofPath
    $env:NPDEV_CP9_UNSAFE_PROOF_PATH = $unsafeProofPath
    try {
        & $gradle -p NPDevGenerator :generator:test --tests "*IncrementalMigrationHarnessTest" --rerun-tasks --no-daemon --console=plain
    }
    finally {
        if ($null -eq $previousIncremental) {
            Remove-Item Env:\NPDEV_CP9_INCREMENTAL_PROOF_PATH -ErrorAction SilentlyContinue
        }
        else {
            $env:NPDEV_CP9_INCREMENTAL_PROOF_PATH = $previousIncremental
        }
        if ($null -eq $previousUnsafe) {
            Remove-Item Env:\NPDEV_CP9_UNSAFE_PROOF_PATH -ErrorAction SilentlyContinue
        }
        else {
            $env:NPDEV_CP9_UNSAFE_PROOF_PATH = $previousUnsafe
        }
    }
}

$testXmlSource = Join-Path $root "NPDevGenerator/generator/build/test-results/test/TEST-com.npdev.generator.migration.IncrementalMigrationHarnessTest.xml"
$testXmlProof = Join-Path $testResultRoot "TEST-com.npdev.generator.migration.IncrementalMigrationHarnessTest.xml"
if (Test-Path -LiteralPath $testXmlSource -PathType Leaf) {
    Copy-Item -LiteralPath $testXmlSource -Destination $testXmlProof -Force
}
$incrementalProof = if (Test-Path -LiteralPath $incrementalProofPath -PathType Leaf) { Get-Content -Raw -LiteralPath $incrementalProofPath | ConvertFrom-Json } else { $null }
$unsafeProof = if (Test-Path -LiteralPath $unsafeProofPath -PathType Leaf) { Get-Content -Raw -LiteralPath $unsafeProofPath | ConvertFrom-Json } else { $null }
$scenarioCount = if ($null -ne $incrementalProof) { [int]$incrementalProof.scenarioCount } else { 0 }
$scenarioProofPassed = $testResult.passed -and $null -ne $incrementalProof -and $scenarioCount -ge 5 -and [bool]$incrementalProof.dataPreservationVerified
$unsafeProofPassed = $testResult.passed -and $null -ne $unsafeProof -and [bool]$unsafeProof.unsafeBackfillFailsGracefully
Add-Check $checks "incremental-postgres-harness-tests-pass" $testResult.passed ([pscustomobject]@{
        result = $testResult
        testXmlPath = Convert-ToRepoPath $root $testXmlProof
    })
Add-Check $checks "five-upgrade-scenarios-preserve-data" $scenarioProofPassed ([pscustomobject]@{
        proofPath = Convert-ToRepoPath $root $incrementalProofPath
        scenarioCount = $scenarioCount
        dataPreservationVerified = if ($null -ne $incrementalProof) { [bool]$incrementalProof.dataPreservationVerified } else { $false }
    })
Add-Check $checks "unsafe-backfill-fails-gracefully" $unsafeProofPassed ([pscustomobject]@{
        proofPath = Convert-ToRepoPath $root $unsafeProofPath
        errorCode = if ($null -ne $unsafeProof) { [string]$unsafeProof.errorCode } else { "" }
        risk = if ($null -ne $unsafeProof) { [string]$unsafeProof.risk } else { "" }
    })

$baselineSnapshot = Join-Path $workRoot "baseline-empty-storage-schema.json"
@{
    modelVersion = "empty-baseline"
    tables = @()
} | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath $baselineSnapshot -Encoding UTF8
$diffOutput = Join-Path $workRoot "npdev-migration-diff-output"
$diffDecision = Join-Path $workRoot "npdev-migration-diff-decision.json"
$diffResult = Invoke-CommandCapture "npdev-migration-diff" {
    & $npdev migration diff `
        --baseline $baselineSnapshot `
        --current "NPDevContract/examples/valid/minimal-model.json" `
        --output $diffOutput `
        --decision-report $diffDecision `
        --migrationRiskThreshold SAFE_ADDITIVE
}
$diffDecisionJson = if (Test-Path -LiteralPath $diffDecision -PathType Leaf) { Get-Content -Raw -LiteralPath $diffDecision | ConvertFrom-Json } else { $null }
$diffDryRun = Join-Path $diffOutput "db/migration-plans/latest-model-delta.sql"
$migrationDiffCliSupported = $diffResult.passed -and $null -ne $diffDecisionJson -and (Test-Path -LiteralPath $diffDryRun -PathType Leaf)
Add-Check $checks "npdev-migration-diff-cli-supported" $migrationDiffCliSupported ([pscustomobject]@{
        result = $diffResult
        baselineSnapshot = Convert-ToRepoPath $root $baselineSnapshot
        currentModel = "NPDevContract/examples/valid/minimal-model.json"
        decisionPath = Convert-ToRepoPath $root $diffDecision
        dryRunSqlPath = Convert-ToRepoPath $root $diffDryRun
    })

$failed = @($checks | Where-Object { -not $_.passed })
$overallStatus = if ($failed.Count -eq 0) { "passed" } else { "failed" }
$report = [pscustomobject]@{
    schemaVersion = "npdev-incremental-migration-testing-report.v1"
    runId = $RunId
    generatedAt = (Get-Date).ToUniversalTime().ToString("o")
    scriptPath = "scripts/quality/run-incremental-migration-testing-check.ps1"
    workspaceRoot = $root
    overallStatus = $overallStatus
    postgresTestcontainersHarnessPassed = $scenarioProofPassed
    upgradeScenarioCount = $scenarioCount
    baselineSchemaApplied = if ($null -ne $incrementalProof) { [bool]$incrementalProof.baselineSchemaApplied } else { $false }
    preMigrationDataInserted = if ($null -ne $incrementalProof) { [bool]$incrementalProof.preMigrationDataInserted } else { $false }
    newMigrationApplied = if ($null -ne $incrementalProof) { [bool]$incrementalProof.newMigrationApplied } else { $false }
    dataPreservationVerified = if ($null -ne $incrementalProof) { [bool]$incrementalProof.dataPreservationVerified } else { $false }
    flywaySchemaHistoryVerified = if ($null -ne $incrementalProof) { [bool]$incrementalProof.flywaySchemaHistoryVerified } else { $false }
    unsafeBackfillFailsGracefully = $unsafeProofPassed
    migrationDiffCliSupported = $migrationDiffCliSupported
    incrementalProofPath = Convert-ToRepoPath $root $incrementalProofPath
    unsafeBackfillProofPath = Convert-ToRepoPath $root $unsafeProofPath
    testXmlPath = Convert-ToRepoPath $root $testXmlProof
    migrationDiffDecisionPath = Convert-ToRepoPath $root $diffDecision
    migrationDiffDryRunSqlPath = Convert-ToRepoPath $root $diffDryRun
    checks = @($checks)
    findings = @()
    doesNotSolve = @(
        "Does not run production database upgrades.",
        "Does not implement automatic destructive migration repair.",
        "Does not proceed to Checkpoint 10."
    )
}

New-Item -ItemType Directory -Force -Path (Split-Path -Parent $ReportPath) | Out-Null
$report | ConvertTo-Json -Depth 80 | Set-Content -LiteralPath $ReportPath -Encoding UTF8

if ($overallStatus -ne "passed") {
    Write-Error ("Incremental migration testing check failed. Report: " + $ReportPath)
}

Write-Host ("Incremental migration testing report written: " + $ReportPath)
