param(
    [string]$WorkspaceRoot = ".",
    [string]$ReportPath = "scripts/reports/out/stateful-additive-migrations-report.json",
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
        outputTail = @($output | Select-Object -Last 80)
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

$root = (Resolve-Path $WorkspaceRoot).Path
if ([string]::IsNullOrWhiteSpace($RunId)) {
    $RunId = "stateful-additive-migrations-" + (Get-Date).ToUniversalTime().ToString("yyyyMMdd-HHmmssfff")
}

$workRoot = Join-Path $root "build/cp8-stateful-additive-migrations"
if (Test-Path -LiteralPath $workRoot) {
    Remove-Item -LiteralPath $workRoot -Recurse -Force
}
New-Item -ItemType Directory -Force -Path $workRoot | Out-Null

$modelPath = "NPDevGenerator\resources\Models\canonical-demo\model.json"
$configPath = "NPDevGenerator\resources\Models\canonical-demo\config.json"
$planOutput = Join-Path $workRoot "plan-output"
$generateOutput = Join-Path $workRoot "generate-output"
$planDecision = Join-Path $workRoot "plan-decision.json"
$generateDecision = Join-Path $workRoot "generate-decision.json"

$checks = [System.Collections.Generic.List[object]]::new()
$proofRoot = Join-Path $workRoot "test-results"
New-Item -ItemType Directory -Force -Path $proofRoot | Out-Null

$testResult = Invoke-CommandCapture "stateful-migration-planner-tests" {
    & .\gradlew.bat -p NPDevGenerator :generator:test --tests "*StatefulMigrationPlannerTest" --rerun-tasks --no-daemon --console=plain
}
$plannerXmlSource = Join-Path $root "NPDevGenerator/generator/build/test-results/test/TEST-com.npdev.generator.migration.StatefulMigrationPlannerTest.xml"
$plannerXmlProof = Join-Path $proofRoot "TEST-com.npdev.generator.migration.StatefulMigrationPlannerTest.xml"
if (Test-Path -LiteralPath $plannerXmlSource -PathType Leaf) {
    Copy-Item -LiteralPath $plannerXmlSource -Destination $plannerXmlProof -Force
}
Add-Check $checks "stateful-migration-unit-tests-pass" $testResult.passed $testResult

$flywayProofResult = Invoke-CommandCapture "stateful-flyway-postgres-proof" {
    $flywayProofJson = Join-Path $workRoot "flyway-postgres-proof.json"
    $previousProofPath = $env:NPDEV_CP8_FLYWAY_PROOF_PATH
    $env:NPDEV_CP8_FLYWAY_PROOF_PATH = $flywayProofJson
    try {
    & .\gradlew.bat -p NPDevGenerator :generator:test --tests "*StatefulFlywayPostgresMigrationProofTest" --rerun-tasks --no-daemon --console=plain
    }
    finally {
        if ($null -eq $previousProofPath) {
            Remove-Item Env:\NPDEV_CP8_FLYWAY_PROOF_PATH -ErrorAction SilentlyContinue
        }
        else {
            $env:NPDEV_CP8_FLYWAY_PROOF_PATH = $previousProofPath
        }
    }
}
$flywayProofJson = Join-Path $workRoot "flyway-postgres-proof.json"
$flywayProofJsonData = if (Test-Path -LiteralPath $flywayProofJson -PathType Leaf) { Get-Content -Raw -LiteralPath $flywayProofJson | ConvertFrom-Json } else { $null }
$flywayXmlSource = Join-Path $root "NPDevGenerator/generator/build/test-results/test/TEST-com.npdev.generator.migration.StatefulFlywayPostgresMigrationProofTest.xml"
$flywayXmlProof = Join-Path $proofRoot "TEST-com.npdev.generator.migration.StatefulFlywayPostgresMigrationProofTest.xml"
if (Test-Path -LiteralPath $flywayXmlSource -PathType Leaf) {
    Copy-Item -LiteralPath $flywayXmlSource -Destination $flywayXmlProof -Force
}
$flywaySchemaHistoryVerified = $null -ne $flywayProofJsonData -and [bool]$flywayProofJsonData.flywaySchemaHistoryVerified -and [int]$flywayProofJsonData.schemaHistorySuccessRows -ge 1
$flywayPostgresMigrationProofPassed = $flywayProofResult.passed -and $null -ne $flywayProofJsonData -and [bool]$flywayProofJsonData.flywayPostgresMigrationProofPassed
$flywayValidateOrMigrateProofPassed = $flywayPostgresMigrationProofPassed -and [bool]$flywayProofJsonData.flywayValidateOrMigrateProofPassed -and $flywaySchemaHistoryVerified
Add-Check $checks "flyway-postgres-migrate-validate-proof" $flywayValidateOrMigrateProofPassed ([pscustomobject]@{
        result = $flywayProofResult
        proofPath = Convert-ToRepoPath $root $flywayProofJson
        proof = $flywayProofJsonData
        testXmlPath = Convert-ToRepoPath $root $flywayXmlProof
    })

# SKIPPED, not attempted: `npdev generate app` never had --migrationMode/--migrationPlanOnly/
# --migrationDecisionReport (confirmed via NPDevCli/npdev_cli.py -- that subcommand only ever
# accepted --model/--config/--output/--require-db-definition). Those flags belong to a DIFFERENT
# subcommand, `npdev migration diff` (npdev_cli.py:run_migration_diff), which itself needs a
# --baseline snapshot file this script never produces -- so porting to the correct CLI shape is a
# real, separate redesign, not a one-line rename. Confirmed live (LNCH-1, 2026-07-19) that the
# underlying generator-level logic these two steps were meant to exercise is NOT dead: the JUnit
# suite above (StatefulMigrationPlannerTest et al.) passes on its own, proving the capability works
# at the unit level -- it's specifically this script's npdev.bat shell-out shape that went stale.
# Previously this crashed obscurely on `[System.IO.Path]::GetFullPath($PathValue)` with an empty
# path (Convert-ToRepoPath called on the never-written decision report's dryRunSqlPath) instead of
# reporting a clear reason. LNCH-1's `Build-NpdevApp.ps1 -PlanOnly`/`-Upgrade` is the modern,
# live-verified equivalent of "preview a migration plan before it touches anything" this script was
# trying to prove -- see docs/SCHEMA_EVOLUTION.md.
$planResult = [pscustomobject]@{ name = "npdev-generate-plan-only"; exitCode = -1; expectedExitCode = 0; passed = $false; durationSeconds = 0; outputTail = @("SKIPPED: see comment above this block in the script -- npdev generate app never supported --migrationMode/--migrationPlanOnly/--migrationDecisionReport.") }
Add-Check $checks "migration-plan-only-dry-run-sql" $false ([pscustomobject]@{
        command = "npdev generate app --migrationMode additive-only --migrationPlanOnly"
        result = $planResult
        skippedReason = "Stale CLI contract -- see script comment. Use 'Build-NpdevApp.ps1 -Upgrade -PlanOnly' instead (docs/SCHEMA_EVOLUTION.md)."
    })
$planDecisionJson = $null
$planDryRunPath = ""
$migrationPlanOnlySupported = $false
$dryRunSqlAttached = $false

$generateResult = [pscustomobject]@{ name = "npdev-generate-additive-only"; exitCode = -1; expectedExitCode = 0; passed = $false; durationSeconds = 0; outputTail = @("SKIPPED: see comment above the npdev-generate-plan-only block in the script -- same stale CLI contract.") }
Add-Check $checks "additive-only-generation-versioned-flyway" $false ([pscustomobject]@{
        command = "npdev generate app --migrationMode additive-only"
        result = $generateResult
        skippedReason = "Stale CLI contract -- see script comment. Use 'Build-NpdevApp.ps1 -Upgrade -PlanOnly' instead (docs/SCHEMA_EVOLUTION.md)."
    })
$generateDecisionJson = $null
$versionedMigrations = @()
$versionedFlywayMigrationGenerated = $false
$safeAdditiveChangesAllowed = $false

$runtimePreflightResult = Invoke-CommandCapture "runtime-migration-static-preflight" {
    & .\gradlew.bat -p NPDevRuntimeHost runtimeMigrationPreflight
}
Add-Check $checks "runtime-migration-static-preflight-passed" $runtimePreflightResult.passed $runtimePreflightResult

$destructiveChangesRejected = $testResult.passed -and ($testResult.outputTail -join "`n") -notmatch "FAILED"
$riskThresholdConfigurable = $testResult.passed
$safeNewTableNotNullExplicit = $null -ne $planDecisionJson -and @($planDecisionJson.safeNewTableNotNullChanges).Count -gt 0
$existingTableNotNullBackfillExplicit = $testResult.passed
Add-Check $checks "destructive-changes-rejected-by-tests" $destructiveChangesRejected ([pscustomobject]@{
        testClass = "com.npdev.generator.migration.StatefulMigrationPlannerTest"
        destructiveTest = "additiveOnlyRejectsDestructiveSnapshotChanges"
        thresholdTest = "riskThresholdAllowsBackfillOnlyWhenConfigured"
    })
Add-Check $checks "not-null-risk-buckets-explicit" ($safeNewTableNotNullExplicit -and $existingTableNotNullBackfillExplicit) ([pscustomobject]@{
        safeNewTableNotNullChanges = if ($null -ne $planDecisionJson) { @($planDecisionJson.safeNewTableNotNullChanges) } else { @() }
        existingTableNotNullBackfillTest = "decisionReportSeparatesSafeAndBackfillNotNullChanges"
    })

$failed = @($checks | Where-Object { -not $_.passed })
$overallStatus = if ($failed.Count -eq 0) { "passed" } else { "failed" }
$report = [pscustomobject]@{
    schemaVersion = "npdev-stateful-additive-migrations-report.v1"
    runId = $RunId
    generatedAt = (Get-Date).ToUniversalTime().ToString("o")
    scriptPath = "scripts/quality/run-stateful-additive-migrations-check.ps1"
    workspaceRoot = $root
    overallStatus = $overallStatus
    migrationModeAdditiveOnlySupported = $safeAdditiveChangesAllowed
    migrationPlanOnlySupported = $migrationPlanOnlySupported
    versionedFlywayMigrationGenerated = $versionedFlywayMigrationGenerated
    safeAdditiveChangesAllowed = $safeAdditiveChangesAllowed
    destructiveChangesRejected = $destructiveChangesRejected
    runtimeMigrationPreflightPassed = $flywayValidateOrMigrateProofPassed
    runtimeMigrationPreflightProofType = "flyway-postgres-testcontainers-migrate-validate"
    runtimeMigrationPreflightEvidencePath = Convert-ToRepoPath $root $flywayProofJson
    runtimeMigrationStaticPreflightPassed = $runtimePreflightResult.passed
    flywayPostgresMigrationProofPassed = $flywayPostgresMigrationProofPassed
    flywayValidateOrMigrateProofPassed = $flywayValidateOrMigrateProofPassed
    flywaySchemaHistoryVerified = $flywaySchemaHistoryVerified
    dryRunSqlAttached = $dryRunSqlAttached
    riskThresholdConfigurable = $riskThresholdConfigurable
    safeNewTableNotNullExplicit = $safeNewTableNotNullExplicit
    existingTableNotNullBackfillExplicit = $existingTableNotNullBackfillExplicit
    planDecisionPath = Convert-ToRepoPath $root $planDecision
    generateDecisionPath = Convert-ToRepoPath $root $generateDecision
    dryRunSqlPath = Convert-ToRepoPath $root $planDryRunPath
    plannerTestXmlPath = Convert-ToRepoPath $root $plannerXmlProof
    flywayPostgresTestXmlPath = Convert-ToRepoPath $root $flywayXmlProof
    flywayPostgresProofPath = Convert-ToRepoPath $root $flywayProofJson
    versionedFlywayMigrationPaths = @($versionedMigrations | ForEach-Object { Convert-ToRepoPath $root $_.FullName })
    checks = @($checks)
    findings = @(
        "npdev-generate-plan-only and npdev-generate-additive-only are SKIPPED, not run: they " +
        "shell out to 'npdev generate app' with --migrationMode/--migrationPlanOnly/" +
        "--migrationDecisionReport, flags that subcommand never supported (confirmed via " +
        "NPDevCli/npdev_cli.py). The correct flags belong to 'npdev migration diff', which needs a " +
        "--baseline snapshot this script doesn't produce -- porting is a real redesign, not done " +
        "here. The underlying generator logic is confirmed alive (StatefulMigrationPlannerTest et " +
        "al. pass independently); LNCH-1's Build-NpdevApp.ps1 -PlanOnly/-Upgrade is the modern, " +
        "live-verified equivalent of what these two checks were trying to prove -- see " +
        "docs/SCHEMA_EVOLUTION.md.",
        "Fixing the crash above (2026-07-19) surfaced two FURTHER pre-existing, previously-invisible " +
        "failures this script never reached before (the crash always fired first): (1) " +
        "'flyway-postgres-migrate-validate-proof' -- the :generator:test --tests " +
        "'*StatefulFlywayPostgresMigrationProofTest' invocation reports BUILD SUCCESSFUL but " +
        "produces zero test-results output (confirmed: no XML under " +
        "NPDevGenerator/generator/build/test-results/test after a clean --rerun-tasks run), meaning " +
        "the test filter matches nothing -- root cause not investigated further (a separate, " +
        "unbounded audit). (2) 'runtime-migration-static-preflight-passed' -- " +
        "'gradlew.bat -p NPDevRuntimeHost runtimeMigrationPreflight' fails with " +
        "\"Task 'runtimeMigrationPreflight' not found in root project 'FinalExec'\" -- " +
        "NPDevRuntimeHost is a template, not a standalone buildable project (see its own " +
        "build.gradle.template header comment: \"build from the generated final app root\"), so " +
        "this step was never going to resolve a project at that path; whether the task still exists " +
        "in a REAL generated app's build.gradle was not checked. Neither issue was introduced or " +
        "fixed this session -- both are now documented instead of silently masked by the earlier " +
        "crash, which is the actionable improvement this pass made."
    )
    doesNotSolve = @(
        "Does not run destructive migrations against a live production database.",
        "Does not promote generated CP8 smoke artifacts into committed migration history.",
        "Does not proceed to Checkpoint 9."
    )
}

New-Item -ItemType Directory -Force -Path (Split-Path -Parent $ReportPath) | Out-Null
$report | ConvertTo-Json -Depth 80 | Set-Content -LiteralPath $ReportPath -Encoding UTF8

if ($overallStatus -ne "passed") {
    Write-Error ("Stateful additive migration check failed. Report: " + $ReportPath)
}

Write-Host ("Stateful additive migration report written: " + $ReportPath)
