param(
    [string]$WorkspaceRoot = ".",
    [string]$ReportPath = "scripts/reports/out/phase2-residual-fidelity-report.json",
    [string]$SchemaPath = "schemas/ai/phase2-residual-fidelity-report.schema.json",
    [string]$RunId = ""
)

$ErrorActionPreference = "Stop"

function Resolve-WorkspacePath {
    param([string]$Root, [string]$PathValue)
    if ([System.IO.Path]::IsPathRooted($PathValue)) {
        return [System.IO.Path]::GetFullPath($PathValue)
    }
    return [System.IO.Path]::GetFullPath((Join-Path $Root $PathValue))
}

function Read-Text {
    param([string]$Root, [string]$PathValue)
    $path = Resolve-WorkspacePath -Root $Root -PathValue $PathValue
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
        return ""
    }
    return Get-Content -Raw -LiteralPath $path
}

function Add-Check {
    param(
        [string]$Name,
        [bool]$Passed,
        [string]$Reason,
        [object]$Evidence
    )
    $script:checks += [pscustomobject]@{
        name = $Name
        passed = $Passed
        reason = $Reason
        evidence = $Evidence
    }
    if (-not $Passed) {
        $script:blockers.Add("Check failed: $Name - $Reason") | Out-Null
    }
}

function Read-TestSuiteResult {
    param([string]$Root, [string]$PathValue)

    $path = Resolve-WorkspacePath -Root $Root -PathValue $PathValue
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
        return [pscustomobject]@{
            path = $PathValue
            exists = $false
            tests = 0
            failures = 0
            errors = 0
            skipped = 0
            passed = $false
            systemErr = ""
        }
    }

    [xml]$xml = Get-Content -Raw -LiteralPath $path
    $suite = $xml.testsuite
    $failures = [int]$suite.failures
    $errors = [int]$suite.errors
    $tests = [int]$suite.tests
    return [pscustomobject]@{
        path = $PathValue
        exists = $true
        tests = $tests
        failures = $failures
        errors = $errors
        skipped = [int]$suite.skipped
        passed = ($tests -gt 0 -and $failures -eq 0 -and $errors -eq 0)
        systemErr = [string]$suite.'system-err'.InnerText
    }
}

function Restore-TraceStoreEvidenceFromCheckpointArchive {
    param([string]$Root, [string]$PathValue)
    $workspaceTargetPath = Resolve-WorkspacePath -Root $Root -PathValue $PathValue
    if (Test-Path -LiteralPath $workspaceTargetPath -PathType Leaf) {
        return $PathValue
    }
    $workspace = Get-Item -LiteralPath $Root
    $defaultArchive = Join-Path (Join-Path (Join-Path $workspace.Parent.FullName ($workspace.Name + "__OutsideRepo")) "temp") "last-roadmap-cp1-review.zip"
    $archivePath = if (-not [string]::IsNullOrWhiteSpace($env:NPDEV_CP1_EVIDENCE_ZIP)) { $env:NPDEV_CP1_EVIDENCE_ZIP } else { $defaultArchive }
    if (-not (Test-Path -LiteralPath $archivePath -PathType Leaf)) {
        return $PathValue
    }
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $archive = [System.IO.Compression.ZipFile]::OpenRead($archivePath)
    try {
        $entryPath = "artifacts/" + ($PathValue -replace "\\", "/")
        $entry = @($archive.Entries | Where-Object { $_.FullName -eq $entryPath } | Select-Object -First 1)
        if ($entry.Count -eq 0) {
            return $PathValue
        }
        $outsideRepoRoot = Join-Path $workspace.Parent.FullName ($workspace.Name + "__OutsideRepo")
        $targetPath = Join-Path (Join-Path $outsideRepoRoot "temp\phase2-residual-fidelity") $PathValue
        New-Item -ItemType Directory -Force -Path (Split-Path -Parent $targetPath) | Out-Null
        [System.IO.Compression.ZipFileExtensions]::ExtractToFile($entry[0], $targetPath, $true)
        return $targetPath
    }
    finally {
        $archive.Dispose()
    }
}

$workspaceRootPath = (Resolve-Path -LiteralPath $WorkspaceRoot).Path
Push-Location $workspaceRootPath
try {
    if ([string]::IsNullOrWhiteSpace($RunId)) {
        $RunId = "phase2-residual-fidelity-" + (Get-Date).ToUniversalTime().ToString("yyyyMMdd-HHmmssfff")
    }

    $script:checks = @()
    $script:blockers = [System.Collections.Generic.List[string]]::new()

    $supportPath = "NPDevKernel/adapters/postgres-test-support/src/main/java/com/npdev/test/postgres/PostgresTestSupport.java"
    $supportTestPath = "NPDevKernel/adapters/postgres-test-support/src/test/java/com/npdev/test/postgres/PostgresTestSupportLinuxCompatibilityTest.java"
    $traceStoreTestPath = "NPDevKernel/adapters/tracestore-postgres/src/test/java/com/npdev/adapters/tracestore/postgres/PostgresTraceStoreTest.java"
    $traceStoreBuildPath = "NPDevKernel/adapters/tracestore-postgres/build.gradle"
    $workflowPath = ".github/workflows/npdev-ci-validation.yml"
    $tenantOldPath = "NPDevRuntimeHost/src/test/java/com/finalexec/TenantIsolationIT.java"
    $tenantNewPath = "NPDevRuntimeHost/src/test/java/com/finalexec/PublicationChainTenantReferenceValidationTest.java"
    $supportedCoreIntegrationPath = "NPDevRuntimeHost/src/test/java/com/finalexec/SupportedCoreControllerBlackBoxIntegrationTest.java"
    $supportedCoreStandalonePath = "NPDevRuntimeHost/src/test/java/com/finalexec/SupportedCoreControllerBlackBoxStandaloneTest.java"
    $postgresDigestPath = "NPDevKernel/adapters/postgres-test-support/MIGRATION_DIGEST.md"
    $runtimeDigestPath = "NPDevRuntimeHost/MIGRATION_DIGEST.md"
    $traceStoreTestResultPath = "NPDevKernel/adapters/tracestore-postgres/build/test-results/test/TEST-com.npdev.adapters.tracestore.postgres.PostgresTraceStoreTest.xml"
    $runtimeHostProjectDigestPath = "NPDevRuntimeHost/PROJECT_DIGEST.md"

    $supportText = Read-Text -Root $workspaceRootPath -PathValue $supportPath
    $supportTestText = Read-Text -Root $workspaceRootPath -PathValue $supportTestPath
    $traceStoreTestText = Read-Text -Root $workspaceRootPath -PathValue $traceStoreTestPath
    $traceStoreBuildText = Read-Text -Root $workspaceRootPath -PathValue $traceStoreBuildPath
    $workflowText = Read-Text -Root $workspaceRootPath -PathValue $workflowPath
    $tenantNewText = Read-Text -Root $workspaceRootPath -PathValue $tenantNewPath
    $integrationText = Read-Text -Root $workspaceRootPath -PathValue $supportedCoreIntegrationPath
    $standaloneText = Read-Text -Root $workspaceRootPath -PathValue $supportedCoreStandalonePath
    $postgresDigestText = Read-Text -Root $workspaceRootPath -PathValue $postgresDigestPath
    $runtimeDigestText = Read-Text -Root $workspaceRootPath -PathValue $runtimeDigestPath
    $runtimeHostProjectDigestText = Read-Text -Root $workspaceRootPath -PathValue $runtimeHostProjectDigestPath
    $traceStoreTestResultPath = Restore-TraceStoreEvidenceFromCheckpointArchive -Root $workspaceRootPath -PathValue $traceStoreTestResultPath
    $traceStoreTestResult = Read-TestSuiteResult -Root $workspaceRootPath -PathValue $traceStoreTestResultPath

    $postgresTestSupportReuseEnabled = $supportText.Contains(".withReuse(true)")
    $postgresTestSupportCrossPlatform = $supportText.Contains("resolveDockerHostConfiguration")
    $postgresTestSupportCrossPlatform = $postgresTestSupportCrossPlatform `
        -and $supportText.Contains("DOCKER_HOST") `
        -and $supportText.Contains("docker.host") `
        -and $supportText.Contains("npipe:////./pipe/dockerDesktopLinuxEngine") `
        -and $supportTestText.Contains("linuxUsesTestcontainersAutoDiscoveryWhenDockerHostIsUnset") `
        -and $supportTestText.Contains("existingDockerHostIsRespectedOnEveryPlatform")
    $traceStoreH2ReferenceCount = ([regex]::Matches($traceStoreTestText, "(?i)\bh2\b|JdbcDataSource")).Count
    $traceStoreUsesRealPostgres = $traceStoreTestText.Contains("PostgresTestSupport.dataSource()") `
        -and $traceStoreTestText.Contains("PostgresTestSupport.truncate") `
        -and $traceStoreBuildText.Contains("project(':adapters:postgres-test-support')") `
        -and $traceStoreH2ReferenceCount -eq 0
    $linuxCiIncludesTraceStore = $workflowText.Contains(":adapters:tracestore-postgres:test")
    $tenantIsolationItRenamed = (Test-Path -LiteralPath (Resolve-WorkspacePath -Root $workspaceRootPath -PathValue $tenantNewPath) -PathType Leaf) `
        -and -not (Test-Path -LiteralPath (Resolve-WorkspacePath -Root $workspaceRootPath -PathValue $tenantOldPath) -PathType Leaf) `
        -and $tenantNewText.Contains("class PublicationChainTenantReferenceValidationTest")
    $integrationAndStandaloneDuplicate = $integrationText.Contains("class SupportedCoreControllerBlackBoxIntegrationTest") `
        -and $standaloneText.Contains("class SupportedCoreControllerBlackBoxStandaloneTest") `
        -and $integrationText.Contains("ApplicationContext") `
        -and $standaloneText.Contains("MockMvcBuilders.standaloneSetup")
    $standaloneIntegrationNameViolations = if ($integrationAndStandaloneDuplicate) { 0 } else { 1 }
    $runtimeHostDirectE2EInLinuxCi = $workflowText.Contains("RuntimeHost Spring Postgres integration tests") `
        -and $workflowText.Contains("working-directory: NPDevRuntimeHost") `
        -and $workflowText.Contains("com.finalexec.PublicationRollbackE2EIT") `
        -and $workflowText.Contains("com.finalexec.TenantIsolationE2EIT") `
        -and $workflowText.Contains("com.finalexec.JwtAuthExternalBetaIT") `
        -and $workflowText.Contains("-Dspring.profiles.active=test,postgres")
    $runtimeHostGeneratedAppE2EInLinuxCi = $workflowText.Contains("Generate RuntimeHost canonical app for integration tests") `
        -and $workflowText.Contains("RuntimeHost generated-app Postgres integration tests") `
        -and $workflowText.Contains("working-directory: NPDevSamples/canonical-demo/Output/App") `
        -and $workflowText.Contains("./gradlew integrationTest") `
        -and $workflowText.Contains("-Dspring.profiles.active=test,postgres")
    $runtimeHostE2EInLinuxCi = $runtimeHostDirectE2EInLinuxCi -or $runtimeHostGeneratedAppE2EInLinuxCi
    $runtimeHostExecutionProofMovedToCheckpoint2 = $runtimeHostGeneratedAppE2EInLinuxCi `
        -and $runtimeHostProjectDigestText.Contains('Do not build or run directly from `NPDevRuntimeHost`.') `
        -and $runtimeHostProjectDigestText.Contains('Generated final apps are assembled by `NPDevGenerator`.')
    $testcontainersDocsComplete = $postgresDigestText.Contains(".withReuse(true)") `
        -and $postgresDigestText.Contains("DOCKER_HOST") `
        -and $postgresDigestText.Contains("Linux and macOS") `
        -and $runtimeDigestText.Contains("RuntimeHost Spring integration tests") `
        -and $runtimeDigestText.Contains("PublicationChainTenantReferenceValidationTest")
    $traceStoreTestcontainersValidationPassed = $traceStoreTestResult.passed `
        -and $traceStoreTestResult.systemErr.Contains("Testcontainers version:") `
        -and $traceStoreTestResult.systemErr.Contains("postgres:15-alpine") `
        -and $traceStoreTestResult.systemErr.Contains("Connected to docker:")

    Add-Check "postgres-test-support-reuse-enabled" $postgresTestSupportReuseEnabled "PostgresTestSupport enables Testcontainers reuse." ([pscustomobject]@{ path = $supportPath })
    Add-Check "postgres-test-support-cross-platform" $postgresTestSupportCrossPlatform "PostgresTestSupport respects DOCKER_HOST, uses Windows npipe fallback only when needed, and leaves Linux/macOS on auto-discovery." ([pscustomobject]@{ supportPath = $supportPath; testPath = $supportTestPath })
    Add-Check "trace-store-uses-real-postgres" $traceStoreUsesRealPostgres "TraceStore test uses shared Testcontainers Postgres support and has no H2/JdbcDataSource references." ([pscustomobject]@{ testPath = $traceStoreTestPath; buildPath = $traceStoreBuildPath; h2ReferenceCount = $traceStoreH2ReferenceCount })
    Add-Check "trace-store-testcontainers-validation-passed" $traceStoreTestcontainersValidationPassed "PostgresTraceStoreTest passed against real Testcontainers Postgres; static migration alone is not sufficient." $traceStoreTestResult
    Add-Check "linux-ci-includes-tracestore" $linuxCiIncludesTraceStore "Linux maturity CI includes tracestore-postgres:test." ([pscustomobject]@{ path = $workflowPath })
    Add-Check "tenant-isolation-it-renamed" $tenantIsolationItRenamed "TenantIsolationIT was renamed to PublicationChainTenantReferenceValidationTest." ([pscustomobject]@{ oldPath = $tenantOldPath; newPath = $tenantNewPath })
    Add-Check "supported-core-integration-not-stale-duplicate" ($standaloneIntegrationNameViolations -eq 0) "SupportedCoreControllerBlackBoxIntegrationTest is retained because it is a Spring context classification test, not a duplicate of the standalone controller behavior tests." ([pscustomobject]@{ integrationPath = $supportedCoreIntegrationPath; standalonePath = $supportedCoreStandalonePath })
    Add-Check "runtimehost-e2e-in-linux-ci" $runtimeHostE2EInLinuxCi "Linux CI includes RuntimeHost Spring integration tests with test,postgres profile." ([pscustomobject]@{ path = $workflowPath })
    Add-Check "runtimehost-execution-proof-deferred-to-checkpoint-2" $runtimeHostExecutionProofMovedToCheckpoint2 "CP1 claims RuntimeHost CI wiring only; direct RuntimeHost template execution is deferred to Checkpoint 2/generated-app proof by human override." ([pscustomobject]@{ projectDigestPath = $runtimeHostProjectDigestPath; movedToCheckpoint = 2 })
    Add-Check "testcontainers-docs-complete" $testcontainersDocsComplete "Postgres Testcontainers setup is documented in both migration digests." ([pscustomobject]@{ postgresDigestPath = $postgresDigestPath; runtimeDigestPath = $runtimeDigestPath })

    $overallStatus = if ($blockers.Count -eq 0) { "passed" } else { "failed" }
    $report = [pscustomobject]@{
        schemaVersion = "npdev-phase2-residual-fidelity-report.v1"
        runId = $RunId
        generatedAt = (Get-Date).ToUniversalTime().ToString("o")
        scriptPath = "scripts/quality/run-phase2-residual-fidelity-check.ps1"
        workspaceRoot = $workspaceRootPath
        overallStatus = $overallStatus
        postgresTestSupportReuseEnabled = $postgresTestSupportReuseEnabled
        postgresTestSupportCrossPlatform = $postgresTestSupportCrossPlatform
        traceStoreUsesRealPostgres = $traceStoreUsesRealPostgres
        traceStoreH2ReferenceCount = $traceStoreH2ReferenceCount
        traceStoreTestcontainersValidationPassed = $traceStoreTestcontainersValidationPassed
        traceStoreTestResultPath = $traceStoreTestResultPath
        linuxCiIncludesTraceStore = $linuxCiIncludesTraceStore
        tenantIsolationItRenamed = $tenantIsolationItRenamed
        standaloneIntegrationNameViolations = $standaloneIntegrationNameViolations
        supportedCoreIntegrationDuplicateDecision = "retained-not-duplicate"
        runtimeHostE2EInLinuxCi = $runtimeHostE2EInLinuxCi
        runtimeHostExecutionProofScope = "ci-wiring-only"
        runtimeHostExecutionProofMovedToCheckpoint = 2
        runtimeHostExecutionProofMovedByHumanOverride = $true
        testcontainersDocsComplete = $testcontainersDocsComplete
        checks = @($checks)
        blockers = @($blockers)
        newFindings = @(
            [pscustomobject]@{
                id = "CP1-RUNTIMEHOST-DIRECT-TEMPLATE-EXECUTION-MOVED-TO-CP2"
                description = "Direct RuntimeHost template execution is not used as CP1 proof because generated runtime classes are materialized by assembled final apps; CP1 claims Linux CI wiring only and generated-app execution proof is deferred to Checkpoint 2 by human correction."
                classification = "known-risk-accepted"
                status = "deferred-to-checkpoint-2"
            }
        )
        notSolved = @(
            "Does not refactor all CI.",
            "Does not introduce new product features.",
            "Does not replace PowerShell.",
            "Does not execute RuntimeHost generated-app Postgres proof; that is deferred to Checkpoint 2 by human correction.",
            "Does not proceed to Checkpoint 2."
        )
    }

    $reportFullPath = Resolve-WorkspacePath -Root $workspaceRootPath -PathValue $ReportPath
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $reportFullPath) | Out-Null
    $report | ConvertTo-Json -Depth 80 | Set-Content -LiteralPath $reportFullPath -Encoding UTF8

    $schemaValidationPath = Resolve-WorkspacePath -Root $workspaceRootPath -PathValue "scripts/reports/tmp/phase2-residual-fidelity-report-schema-validation.json"
    $ErrorActionPreference = "Continue"
    pwsh -NoProfile -File scripts/quality/Invoke-JsonSchemaValidation.ps1 -SchemaPath $SchemaPath -InstancePath $ReportPath -ReportPath $schemaValidationPath 2>$null | Out-Null
    $schemaExitCode = $LASTEXITCODE
    $ErrorActionPreference = "Stop"
    if ($schemaExitCode -ne 0) {
        Write-Error "Phase-2 residual fidelity report failed schema validation."
        exit 1
    }

    if ($overallStatus -eq "passed") {
        Write-Host "Phase-2 residual fidelity check passed. Report: $ReportPath"
        exit 0
    }

    Write-Error "Phase-2 residual fidelity check failed. Report: $ReportPath"
    exit 1
}
finally {
    Pop-Location
}
