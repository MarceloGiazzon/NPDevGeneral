param(
    [string]$WorkspaceRoot = ".",
    [string]$ReportPath = "scripts/reports/out/runtimehost-integration-infrastructure-report.json",
    [string]$SchemaPath = "schemas/ai/runtimehost-integration-infrastructure-report.schema.json",
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

function Read-TestSuiteAggregate {
    param([string]$Root, [string]$PathValue)

    $directory = Resolve-WorkspacePath -Root $Root -PathValue $PathValue
    if (-not (Test-Path -LiteralPath $directory -PathType Container)) {
        return [pscustomobject]@{
            path = $PathValue
            exists = $false
            files = @()
            tests = 0
            failures = 0
            errors = 0
            skipped = 0
            passed = $false
        }
    }

    $files = @(Get-ChildItem -LiteralPath $directory -Filter "TEST-*.xml" -File)
    $tests = 0
    $failures = 0
    $errors = 0
    $skipped = 0
    foreach ($file in $files) {
        [xml]$xml = Get-Content -Raw -LiteralPath $file.FullName
        $suite = $xml.testsuite
        $tests += [int]$suite.tests
        $failures += [int]$suite.failures
        $errors += [int]$suite.errors
        $skipped += [int]$suite.skipped
    }

    return [pscustomobject]@{
        path = $PathValue
        exists = $true
        files = @($files | ForEach-Object { $_.FullName.Substring($Root.Length + 1).Replace("\", "/") })
        tests = $tests
        failures = $failures
        errors = $errors
        skipped = $skipped
        passed = ($files.Count -gt 0 -and $tests -gt 0 -and $failures -eq 0 -and $errors -eq 0)
    }
}

$workspaceRootPath = (Resolve-Path -LiteralPath $WorkspaceRoot).Path
Push-Location $workspaceRootPath
try {
    if ([string]::IsNullOrWhiteSpace($RunId)) {
        $RunId = "runtimehost-integration-infrastructure-" + (Get-Date).ToUniversalTime().ToString("yyyyMMdd-HHmmssfff")
    }

    $script:checks = @()
    $script:blockers = [System.Collections.Generic.List[string]]::new()

    $runtimeBuildPath = "NPDevRuntimeHost/build.gradle"
    $runtimeTemplatePath = "NPDevRuntimeHost/build.gradle.template"
    $workflowPath = ".github/workflows/npdev-ci-validation.yml"
    $digestPath = "NPDevRuntimeHost/MIGRATION_DIGEST.md"
    $missingMountLogPath = "scripts/reports/out/runtimehost-integrationTest-missing-mount-output.txt"
    $unitTestResultPath = "NPDevRuntimeHost/build/test-results/test"
    $generatedIntegrationResultPath = "NPDevSamples/canonical-demo/Output/App/build/test-results/integrationTest"

    $runtimeBuildText = Read-Text -Root $workspaceRootPath -PathValue $runtimeBuildPath
    $runtimeTemplateText = Read-Text -Root $workspaceRootPath -PathValue $runtimeTemplatePath
    $workflowText = Read-Text -Root $workspaceRootPath -PathValue $workflowPath
    $digestText = Read-Text -Root $workspaceRootPath -PathValue $digestPath
    $missingMountLogText = Read-Text -Root $workspaceRootPath -PathValue $missingMountLogPath

    $runtimeGradleInputs = @($runtimeBuildText, $runtimeTemplateText)
    $integrationTestTaskExists = ($runtimeGradleInputs | Where-Object {
            $_.Contains("integrationTest {") `
                -and $_.Contains("tasks.register('integrationTest', Test)") `
                -and $_.Contains("includeTags 'integration'")
        }).Count -eq 2
    $unitTestTaskDoesNotRequireGeneratedMount = ($runtimeGradleInputs | Where-Object {
            $_.Contains("if (!generatedRuntimeMountPresent())") `
                -and $_.Contains("generatedRuntimeDependentMainSources.each { exclude it }") `
                -and $_.Contains("generatedRuntimeDependentTestSources.each { exclude it }") `
                -and $_.Contains("modelSpecificGeneratedAppTests.each { exclude it }")
        }).Count -eq 2
    $integrationTestRequiresGeneratedMount = ($runtimeGradleInputs | Where-Object {
            $_.Contains("tasks.register('verifyGeneratedRuntimeMount')") `
                -and $_.Contains("generated-runtime-mount missing") `
                -and $_.Contains("dependsOn tasks.named('verifyGeneratedRuntimeMount')") `
                -and $_.Contains("tasks.named('compileIntegrationTestJava')")
        }).Count -eq 2

    $integrationTaggedFiles = @(Get-ChildItem -LiteralPath (Resolve-WorkspacePath -Root $workspaceRootPath -PathValue "NPDevRuntimeHost/src/test/java/com/finalexec") -Filter "*.java" -File |
        Where-Object { (Get-Content -Raw -LiteralPath $_.FullName).Contains('@Tag("integration")') })
    $integrationTestsTaggedCount = $integrationTaggedFiles.Count

    $linuxCiGeneratesRuntimeBeforeIntegrationTests = $workflowText.Contains("Generate RuntimeHost canonical app for integration tests") `
        -and $workflowText.Contains("scripts/samples/generate-sample.ps1") `
        -and $workflowText.Contains("-SampleIds canonical-demo") `
        -and $workflowText.Contains("RuntimeHost generated-app Postgres integration tests") `
        -and $workflowText.Contains("working-directory: NPDevSamples/canonical-demo/Output/App") `
        -and $workflowText.Contains("./gradlew integrationTest") `
        -and $workflowText.Contains("-Dspring.profiles.active=test,postgres")
    $runtimehostTestArtifactsUploaded = $workflowText.Contains("NPDevSamples/**/Output/App/**/build/test-results/integrationTest") `
        -and $workflowText.Contains("NPDevSamples/**/Output/App/**/build/reports/tests/integrationTest")
    $missingMountFailureIsClear = $missingMountLogText.Contains("generated-runtime-mount missing") `
        -and $missingMountLogText.Contains("integrationTest requires generated runtime sources")
    $unitTestResult = Read-TestSuiteAggregate -Root $workspaceRootPath -PathValue $unitTestResultPath
    $generatedRuntimeIntegrationTestResult = Read-TestSuiteAggregate -Root $workspaceRootPath -PathValue $generatedIntegrationResultPath
    $digestDocumentsBoundary = $digestText.Contains("./gradlew test") `
        -and $digestText.Contains("./gradlew integrationTest") `
        -and $digestText.Contains("NPDevSamples/canonical-demo/Output/App") `
        -and $digestText.Contains("generated-runtime-mount missing")

    Add-Check "integration-test-task-exists" $integrationTestTaskExists "RuntimeHost build.gradle and template define the integrationTest source set and task with the integration tag." ([pscustomobject]@{ buildPath = $runtimeBuildPath; templatePath = $runtimeTemplatePath })
    Add-Check "unit-test-task-does-not-require-generated-mount" $unitTestTaskDoesNotRequireGeneratedMount "RuntimeHost template-local test excludes generated-runtime-dependent source when npdev-generated is absent." ([pscustomobject]@{ buildPath = $runtimeBuildPath; templatePath = $runtimeTemplatePath; unitTestResult = $unitTestResult })
    Add-Check "integration-test-requires-generated-mount" $integrationTestRequiresGeneratedMount "integrationTest depends on verifyGeneratedRuntimeMount before compileIntegrationTestJava." ([pscustomobject]@{ buildPath = $runtimeBuildPath; templatePath = $runtimeTemplatePath })
    Add-Check "missing-generated-mount-fails-clearly" $missingMountFailureIsClear "Direct RuntimeHost integrationTest fails clearly when npdev-generated is absent." ([pscustomobject]@{ path = $missingMountLogPath })
    Add-Check "integration-tests-are-tagged" ($integrationTestsTaggedCount -gt 0) "Generated-runtime integration tests are explicitly tagged for the integrationTest task." ([pscustomobject]@{ taggedFiles = @($integrationTaggedFiles | ForEach-Object { $_.FullName.Substring($workspaceRootPath.Length + 1).Replace("\", "/") }) })
    Add-Check "linux-ci-generates-runtime-before-integration-tests" $linuxCiGeneratesRuntimeBeforeIntegrationTests "Linux maturity CI generates canonical-demo before running generated-app integrationTest." ([pscustomobject]@{ path = $workflowPath })
    Add-Check "runtimehost-test-artifacts-uploaded" $runtimehostTestArtifactsUploaded "Linux maturity evidence upload includes generated-app integration test XML and report directories." ([pscustomobject]@{ path = $workflowPath })
    Add-Check "generated-runtime-integration-test-passed" $generatedRuntimeIntegrationTestResult.passed "Generated canonical app integrationTest produced passing JUnit XML evidence." $generatedRuntimeIntegrationTestResult
    Add-Check "runtimehost-digest-documents-integration-boundary" $digestDocumentsBoundary "RuntimeHost migration digest documents unit-vs-integration behavior and generated-app proof boundary." ([pscustomobject]@{ path = $digestPath })

    $overallStatus = if ($blockers.Count -eq 0) { "passed" } else { "failed" }
    $report = [pscustomobject]@{
        schemaVersion = "npdev-runtimehost-integration-infrastructure-report.v1"
        runId = $RunId
        generatedAt = (Get-Date).ToUniversalTime().ToString("o")
        scriptPath = "scripts/quality/run-runtimehost-integration-infrastructure-check.ps1"
        workspaceRoot = $workspaceRootPath
        overallStatus = $overallStatus
        integrationTestTaskExists = $integrationTestTaskExists
        unitTestTaskDoesNotRequireGeneratedMount = $unitTestTaskDoesNotRequireGeneratedMount
        integrationTestRequiresGeneratedMount = $integrationTestRequiresGeneratedMount
        integrationTestsTaggedCount = $integrationTestsTaggedCount
        linuxCiGeneratesRuntimeBeforeIntegrationTests = $linuxCiGeneratesRuntimeBeforeIntegrationTests
        runtimehostTestArtifactsUploaded = $runtimehostTestArtifactsUploaded
        missingGeneratedMountFailureIsClear = $missingMountFailureIsClear
        unitTestResultPassed = $unitTestResult.passed
        unitTestResultPath = $unitTestResultPath
        generatedRuntimeIntegrationTestPassed = $generatedRuntimeIntegrationTestResult.passed
        generatedRuntimeIntegrationTestResultPath = $generatedIntegrationResultPath
        generatedRuntimeIntegrationTestResult = $generatedRuntimeIntegrationTestResult
        checks = @($checks)
        blockers = @($blockers)
        newFindings = @(
            [pscustomobject]@{
                id = "CP2-RUNTIMEHOST-GENERATED-APP-EXECUTION-PROOF-OWNED"
                description = "Generated-runtime-dependent RuntimeHost execution proof is now owned by the generated app integrationTest path rather than direct template execution."
                classification = "known-risk-accepted"
                status = "accepted-boundary"
            }
        )
        notSolved = @(
            "Does not proceed to Checkpoint 3.",
            "Does not make direct NPDevRuntimeHost template integrationTest executable without generated runtime sources.",
            "Does not remove H2 from unit and slice test profiles.",
            "Does not retag or move beta0.",
            "Does not clean unrelated dirty worktree state."
        )
    }

    $reportFullPath = Resolve-WorkspacePath -Root $workspaceRootPath -PathValue $ReportPath
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $reportFullPath) | Out-Null
    $report | ConvertTo-Json -Depth 80 | Set-Content -LiteralPath $reportFullPath -Encoding UTF8

    $schemaValidationPath = Resolve-WorkspacePath -Root $workspaceRootPath -PathValue "scripts/reports/tmp/runtimehost-integration-infrastructure-report-schema-validation.json"
    $ErrorActionPreference = "Continue"
    pwsh -NoProfile -File scripts/quality/Invoke-JsonSchemaValidation.ps1 -SchemaPath $SchemaPath -InstancePath $ReportPath -ReportPath $schemaValidationPath 2>$null | Out-Null
    $schemaExitCode = $LASTEXITCODE
    $ErrorActionPreference = "Stop"
    if ($schemaExitCode -ne 0) {
        Write-Error "RuntimeHost integration infrastructure report failed schema validation."
        exit 1
    }

    if ($overallStatus -eq "passed") {
        Write-Host "RuntimeHost integration infrastructure check passed. Report: $ReportPath"
        exit 0
    }

    Write-Error "RuntimeHost integration infrastructure check failed. Report: $ReportPath"
    exit 1
}
finally {
    Pop-Location
}
