param(
    [string]$ReportPath = "scripts/reports/out/dsl-parser-robustness-report.json",
    [string]$RunId = ""
)

$ErrorActionPreference = "Stop"

function Read-JsonFile {
    param([string]$Path)
    return Get-Content -Raw -LiteralPath $Path | ConvertFrom-Json
}

function Write-JsonFile {
    param([string]$Path, [object]$Value, [int]$Depth = 80)
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $Path) | Out-Null
    $Value | ConvertTo-Json -Depth $Depth | Set-Content -LiteralPath $Path -Encoding UTF8
}

function Invoke-CommandCapture {
    param(
        [string]$Name,
        [string]$Executable,
        [string[]]$Arguments,
        [string]$OutputPath,
        [int]$ExpectedExitCode = 0
    )
    $started = Get-Date
    $output = [System.Collections.Generic.List[string]]::new()
    $ErrorActionPreference = "Continue"
    & $Executable @Arguments 2>&1 | ForEach-Object { $output.Add([string]$_) | Out-Null }
    $exitCode = $LASTEXITCODE
    $ErrorActionPreference = "Stop"
    if ($null -eq $exitCode) {
        $exitCode = 0
    }
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $OutputPath) | Out-Null
    $output | Set-Content -LiteralPath $OutputPath -Encoding UTF8
    return [pscustomobject]@{
        name = $Name
        command = (@($Executable) + @($Arguments) -join " ")
        exitCode = $exitCode
        expectedExitCode = $ExpectedExitCode
        passed = ($exitCode -eq $ExpectedExitCode)
        durationSeconds = [math]::Round(((Get-Date) - $started).TotalSeconds, 3)
        outputPath = ($OutputPath -replace "\\", "/")
        outputTail = @($output | Select-Object -Last 100)
    }
}

function Read-TestSuite {
    param([string]$Path)
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        return [pscustomobject]@{
            path = ($Path -replace "\\", "/")
            exists = $false
            tests = 0
            failures = 0
            errors = 0
            skipped = 0
            passed = $false
        }
    }
    [xml]$xml = Get-Content -Raw -LiteralPath $Path
    return [pscustomobject]@{
        path = ($Path -replace "\\", "/")
        exists = $true
        tests = [int]$xml.testsuite.tests
        failures = [int]$xml.testsuite.failures
        errors = [int]$xml.testsuite.errors
        skipped = [int]$xml.testsuite.skipped
        passed = ([int]$xml.testsuite.failures -eq 0 -and [int]$xml.testsuite.errors -eq 0)
    }
}

$workspaceRoot = (Resolve-Path ".").Path
if ([string]::IsNullOrWhiteSpace($RunId)) {
    $RunId = "dsl-parser-robustness-" + (Get-Date).ToUniversalTime().ToString("yyyyMMdd-HHmmssfff")
}

$workRoot = Join-Path $workspaceRoot "build/cp14-dsl-parser-robustness"
if (Test-Path -LiteralPath $workRoot) {
    Remove-Item -LiteralPath $workRoot -Recurse -Force
}
New-Item -ItemType Directory -Force -Path $workRoot | Out-Null

$testArgs = @(
    "-p", "NPDevContract/dsl",
    "test",
    "--tests", "com.npdev.dsl.v1.DslParserRobustnessTest",
    "--tests", "com.npdev.dsl.v1.CanonicalDemoRegressionTest",
    "--tests", "com.npdev.dsl.v1.OfficialSamplesRegressionTest",
    "--tests", "com.npdev.dsl.v1.validation.ValidationErrorFormatTest",
    "--tests", "com.npdev.dsl.v1.LegacySchemaRejectionTest",
    "--rerun-tasks",
    "--no-daemon",
    "--console=plain"
)
$dslTests = Invoke-CommandCapture `
    -Name "dsl-parser-robustness-tests" `
    -Executable "./gradlew" `
    -Arguments $testArgs `
    -OutputPath (Join-Path $workRoot "dsl-parser-robustness-tests-output.txt")

$robustnessSuite = Read-TestSuite "NPDevContract/dsl/build/test-results/test/TEST-com.npdev.dsl.v1.DslParserRobustnessTest.xml"
$canonicalSuite = Read-TestSuite "NPDevContract/dsl/build/test-results/test/TEST-com.npdev.dsl.v1.CanonicalDemoRegressionTest.xml"
$officialSuite = Read-TestSuite "NPDevContract/dsl/build/test-results/test/TEST-com.npdev.dsl.v1.OfficialSamplesRegressionTest.xml"
$validationFormatSuite = Read-TestSuite "NPDevContract/dsl/build/test-results/test/TEST-com.npdev.dsl.v1.validation.ValidationErrorFormatTest.xml"
$legacySuite = Read-TestSuite "NPDevContract/dsl/build/test-results/test/TEST-com.npdev.dsl.v1.LegacySchemaRejectionTest.xml"

$parserSource = Get-Content -Raw -LiteralPath "NPDevContract/dsl/src/main/java/com/npdev/dsl/v1/parser/JsonModelParser.java"
$validatorSource = Get-Content -Raw -LiteralPath "NPDevContract/dsl/src/main/java/com/npdev/dsl/v1/validation/JsonModelSchemaValidator.java"
$exceptionSource = Get-Content -Raw -LiteralPath "NPDevContract/dsl/src/main/java/com/npdev/dsl/v1/validation/ModelSchemaValidationException.java"
$normalizerSource = Get-Content -Raw -LiteralPath "NPDevContract/dsl/src/main/java/com/npdev/dsl/v1/validation/ValidationDiagnosticNormalizer.java"

$schemaIndex = $parserSource.IndexOf("schemaValidator.validate(")
$namespaceIndex = $parserSource.IndexOf("String namespace =")
$versionIndex = $parserSource.IndexOf("String version = requiredText")
$schemaFirstGuardBeforeManualParsing = $schemaIndex -ge 0 -and $namespaceIndex -gt $schemaIndex -and $versionIndex -gt $schemaIndex
$canonicalSchemaValidationUsed = $validatorSource.Contains('SCHEMA_RESOURCE_PATH = "/schema/model.schema.json"') -and $parserSource.Contains("JsonModelSchemaValidator")
$typedSchemaDiagnostics = $validatorSource.Contains("ModelSchemaValidationException") -and $exceptionSource.Contains("List<ValidationDiagnostic>")
$pathBasedSchemaErrors = $normalizerSource.Contains("normalizeInstancePath") -and $validationFormatSuite.passed -and $robustnessSuite.passed
$semanticSuggestedFixVerified = $validationFormatSuite.passed -and $robustnessSuite.passed
$positiveAstCompatibilityPassed = $robustnessSuite.passed -and $canonicalSuite.passed -and $officialSuite.passed
$legacyRejectionPreserved = $legacySuite.passed
$handwrittenParserRetained = (Test-Path -LiteralPath "NPDevContract/dsl/src/main/java/com/npdev/dsl/v1/parser/JsonModelParser.java" -PathType Leaf) -and $parserSource.Contains("public final class JsonModelParser")
$jsonModelParserReplaced = $false
$schemaInvalidFailsBeforeManualParser = $robustnessSuite.passed -and $typedSchemaDiagnostics -and $schemaFirstGuardBeforeManualParsing

$failedChecks = @()
if (-not $dslTests.passed) { $failedChecks += "DSL parser robustness test command must pass." }
if (-not $schemaFirstGuardBeforeManualParsing) { $failedChecks += "JsonModelParser must validate schema before manual parser field extraction." }
if (-not $canonicalSchemaValidationUsed) { $failedChecks += "JsonModelParser must use the canonical model.schema.json validator." }
if (-not $typedSchemaDiagnostics) { $failedChecks += "Schema validation failures must expose diagnostics." }
if (-not $schemaInvalidFailsBeforeManualParser) { $failedChecks += "Schema-invalid models must fail before manual parser errors." }
if (-not $pathBasedSchemaErrors) { $failedChecks += "Schema diagnostics must include path-based errors." }
if (-not $semanticSuggestedFixVerified) { $failedChecks += "Semantic diagnostics must include suggested fixes where applicable." }
if (-not $positiveAstCompatibilityPassed) { $failedChecks += "Canonical and official positive model AST behavior must remain compatible." }
if (-not $legacyRejectionPreserved) { $failedChecks += "Legacy root entities rejection must remain preserved." }
if (-not $handwrittenParserRetained -or $jsonModelParserReplaced) { $failedChecks += "JsonModelParser must remain in place for CP14." }

$overallStatus = if ($failedChecks.Count -eq 0) { "passed" } else { "failed" }
$report = [pscustomobject]@{
    schemaVersion = "npdev-dsl-parser-robustness-report.v1"
    runId = $RunId
    generatedAt = (Get-Date).ToUniversalTime().ToString("o")
    scriptPath = "scripts/quality/run-dsl-parser-robustness-check.ps1"
    workspaceRoot = $workspaceRoot
    overallStatus = $overallStatus
    checkpoint = "CP14"
    canonicalSchemaPath = "NPDevContract/dsl/src/main/resources/schema/model.schema.json"
    parserPath = "NPDevContract/dsl/src/main/java/com/npdev/dsl/v1/parser/JsonModelParser.java"
    schemaValidatorPath = "NPDevContract/dsl/src/main/java/com/npdev/dsl/v1/validation/JsonModelSchemaValidator.java"
    schemaValidationExceptionPath = "NPDevContract/dsl/src/main/java/com/npdev/dsl/v1/validation/ModelSchemaValidationException.java"
    schemaFirstValidationBeforeManualParser = $schemaFirstGuardBeforeManualParsing
    canonicalSchemaValidationUsed = $canonicalSchemaValidationUsed
    schemaInvalidFailsBeforeManualParser = $schemaInvalidFailsBeforeManualParser
    typedSchemaDiagnosticsExposed = $typedSchemaDiagnostics
    pathBasedSchemaErrors = $pathBasedSchemaErrors
    semanticDiagnosticsSuggestedFixVerified = $semanticSuggestedFixVerified
    positiveAstCompatibilityPassed = $positiveAstCompatibilityPassed
    canonicalAndOfficialModelsRetainAstBehavior = $positiveAstCompatibilityPassed
    handwrittenParserRetained = $handwrittenParserRetained
    jsonModelParserReplaced = $jsonModelParserReplaced
    legacyEntitiesRootRejectionPreserved = $legacyRejectionPreserved
    parserRobustnessTestsPassed = $robustnessSuite.passed
    validationErrorFormatTestsPassed = $validationFormatSuite.passed
    canonicalDemoRegressionPassed = $canonicalSuite.passed
    officialSamplesRegressionPassed = $officialSuite.passed
    legacySchemaRejectionPassed = $legacySuite.passed
    testSuites = @($robustnessSuite, $canonicalSuite, $officialSuite, $validationFormatSuite, $legacySuite)
    validationCommands = @($dslTests)
    findings = @(
        [pscustomobject]@{
            id = "CP14-HANDWRITTEN-PARSER-RETAINED"
            classification = "known-risk-accepted"
            status = "accepted"
            summary = "CP14 keeps JsonModelParser in place by locked decision; parser replacement remains outside this checkpoint."
        }
    )
    failures = @($failedChecks)
    doesNotSolve = @(
        "Does not remove or replace the handwritten JsonModelParser.",
        "Does not implement a fully generated parser.",
        "Does not redesign the DSL grammar.",
        "Does not proceed to Checkpoint 15."
    )
}

Write-JsonFile $ReportPath $report 100

if ($overallStatus -ne "passed") {
    Write-Error ("DSL parser robustness check failed. Report: " + $ReportPath)
}

Write-Host ("DSL parser robustness report written: " + $ReportPath)
