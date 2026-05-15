param(
    [string]$WorkspaceRoot = ".",
    [string]$ReportPath = "scripts/reports/out/stateful-additive-migrations-report.json",
    [string]$RunId = ""
)

$ErrorActionPreference = "Stop"

function Convert-ToRepoPath {
    param([string]$Root, [string]$PathValue)
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

$planResult = Invoke-CommandCapture "npdev-generate-plan-only" {
    & .\npdev.bat generate app `
        --model $modelPath `
        --config $configPath `
        --output $planOutput `
        --migrationMode additive-only `
        --migrationPlanOnly `
        --migrationRiskThreshold SAFE_ADDITIVE `
        --migrationDecisionReport $planDecision
}
$planDecisionJson = if (Test-Path -LiteralPath $planDecision -PathType Leaf) { Get-Content -Raw -LiteralPath $planDecision | ConvertFrom-Json } else { $null }
$planDryRunPath = if ($null -ne $planDecisionJson) { [string]$planDecisionJson.dryRunSqlPath } else { "" }
$migrationPlanOnlySupported = $planResult.passed -and $null -ne $planDecisionJson -and [bool]$planDecisionJson.migrationPlanOnly
$dryRunSqlAttached = $migrationPlanOnlySupported -and (Test-Path -LiteralPath $planDryRunPath -PathType Leaf)
Add-Check $checks "migration-plan-only-dry-run-sql" ($migrationPlanOnlySupported -and $dryRunSqlAttached) ([pscustomobject]@{
        command = "npdev generate app --migrationMode additive-only --migrationPlanOnly"
        result = $planResult
        decisionPath = Convert-ToRepoPath $root $planDecision
        dryRunSqlPath = Convert-ToRepoPath $root $planDryRunPath
    })

$generateResult = Invoke-CommandCapture "npdev-generate-additive-only" {
    & .\npdev.bat generate app `
        --model $modelPath `
        --config $configPath `
        --output $generateOutput `
        --migrationMode additive-only `
        --migrationRiskThreshold SAFE_ADDITIVE `
        --migrationDecisionReport $generateDecision
}
$generateDecisionJson = if (Test-Path -LiteralPath $generateDecision -PathType Leaf) { Get-Content -Raw -LiteralPath $generateDecision | ConvertFrom-Json } else { $null }
$versionedMigrations = @(Get-ChildItem -LiteralPath (Join-Path $generateOutput "db/migration") -Filter "V*.sql" -File -ErrorAction SilentlyContinue)
$versionedFlywayMigrationGenerated = $generateResult.passed -and $versionedMigrations.Count -gt 0 -and $null -ne $generateDecisionJson -and [string]$generateDecisionJson.versionedFlywayMigrationPath
$safeAdditiveChangesAllowed = $generateResult.passed -and $null -ne $generateDecisionJson -and [string]$generateDecisionJson.overallRisk -eq "SAFE_ADDITIVE"
Add-Check $checks "additive-only-generation-versioned-flyway" ($versionedFlywayMigrationGenerated -and $safeAdditiveChangesAllowed) ([pscustomobject]@{
        command = "npdev generate app --migrationMode additive-only"
        result = $generateResult
        decisionPath = Convert-ToRepoPath $root $generateDecision
        versionedMigrations = @($versionedMigrations | ForEach-Object { Convert-ToRepoPath $root $_.FullName })
    })

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
    findings = @()
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
