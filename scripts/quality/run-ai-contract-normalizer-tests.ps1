param(
    [string]$RunId = ""
)

$ErrorActionPreference = "Stop"

$workspaceRoot = (Resolve-Path ".").Path
if ([string]::IsNullOrWhiteSpace($RunId)) {
    $RunId = "ai-contract-normalizer-tests-" + (Get-Date).ToUniversalTime().ToString("yyyyMMdd-HHmmssfff")
}
$testRoot = Join-Path $workspaceRoot "scripts/reports/tmp/ai-contract-normalizer-tests"
if (Test-Path -LiteralPath $testRoot) {
    Remove-Item -LiteralPath $testRoot -Recurse -Force
}
New-Item -ItemType Directory -Force -Path $testRoot | Out-Null

$positiveOut = Join-Path $testRoot "base-ai-loop"
$positiveResultPath = Join-Path $positiveOut "normalizer-result.json"
$normalizerOutput = pwsh -NoProfile -File scripts/ai/Normalize-AiContract.ps1 `
    -ScenarioPath golden-ai-scenarios/base-ai-loop `
    -OutputDirectory $positiveOut `
    -ResultPath $positiveResultPath

$positiveResult = Get-Content -Raw -LiteralPath $positiveResultPath | ConvertFrom-Json
if ($positiveResult.status -ne "passed") {
    throw "Positive normalizer scenario did not pass."
}

$modelPath = Join-Path $positiveOut "model.json"
$configPath = Join-Path $positiveOut "config.json"
if (-not (Test-Path -LiteralPath $modelPath -PathType Leaf)) { throw "Normalized model.json was not written." }
if (-not (Test-Path -LiteralPath $configPath -PathType Leaf)) { throw "Normalized config.json was not written." }

$model = Get-Content -Raw -LiteralPath $modelPath | ConvertFrom-Json
$config = Get-Content -Raw -LiteralPath $configPath | ConvertFrom-Json
if ($model.dslVersion -ne "1.0.0" -or $model.version -ne "1.0" -or @($model.concepts).Count -lt 1) {
    throw "Normalized model does not contain required official model fields."
}
if ($null -ne $model.PSObject.Properties["schemaVersion"]) {
    throw "Normalized official model must not emit unsupported schemaVersion."
}
if ($config.configVersion -ne "1.0" -or $config.runtime.springProfile -notmatch "ai-beta-local" -or $config.generator.cleanOutputBeforeGenerate -ne $true) {
    throw "Normalized config does not contain required official config fields."
}

$modelValidationPath = Join-Path $positiveOut "official-model-schema-validation.json"
pwsh -NoProfile -File scripts/quality/Invoke-JsonSchemaValidation.ps1 `
    -SchemaPath NPDevContract/schemas/model.schema.json `
    -JsonPath $modelPath `
    -ReportPath $modelValidationPath | Out-Null
if ($LASTEXITCODE -ne 0) {
    throw "Normalized model failed official JSON Schema validation."
}

$configValidationPath = Join-Path $positiveOut "official-config-schema-validation.json"
pwsh -NoProfile -File scripts/quality/Invoke-JsonSchemaValidation.ps1 `
    -SchemaPath NPDevContract/schemas/config.schema.json `
    -JsonPath $configPath `
    -ReportPath $configValidationPath | Out-Null
if ($LASTEXITCODE -ne 0) {
    throw "Normalized config failed official JSON Schema validation."
}

$secondOut = Join-Path $testRoot "base-ai-loop-second"
pwsh -NoProfile -File scripts/ai/Normalize-AiContract.ps1 `
    -ScenarioPath golden-ai-scenarios/base-ai-loop `
    -OutputDirectory $secondOut `
    -ResultPath (Join-Path $secondOut "normalizer-result.json") | Out-Null

$firstModelText = Get-Content -Raw -LiteralPath $modelPath
$secondModelText = Get-Content -Raw -LiteralPath (Join-Path $secondOut "model.json")
$firstConfigText = Get-Content -Raw -LiteralPath $configPath
$secondConfigText = Get-Content -Raw -LiteralPath (Join-Path $secondOut "config.json")
if ($firstModelText -ne $secondModelText -or $firstConfigText -ne $secondConfigText) {
    throw "Normalizer output is not deterministic."
}

$negativeOut = Join-Path $testRoot "unsupported"
$negativeResultPath = Join-Path $negativeOut "normalizer-result.json"
$ErrorActionPreference = "Continue"
pwsh -NoProfile -File scripts/ai/Normalize-AiContract.ps1 `
    -ScenarioPath golden-ai-scenarios/custom-procedure-panel `
    -OutputDirectory $negativeOut `
    -ResultPath $negativeResultPath 2>$null | Out-Null
$negativeExitCode = $LASTEXITCODE
$ErrorActionPreference = "Stop"
if ($negativeExitCode -eq 0) {
    throw "Normalizer accepted unsupported custom procedure panel scenario."
}
if (-not (Test-Path -LiteralPath $negativeResultPath -PathType Leaf)) {
    throw "Normalizer did not write a JSON failure result."
}
$negativeResult = Get-Content -Raw -LiteralPath $negativeResultPath | ConvertFrom-Json
if ($negativeResult.status -ne "failed" -or $negativeResult.errors[0].code -ne "AI_MODEL_KIND_UNSUPPORTED") {
    throw "Normalizer failure result did not expose the expected stable error code."
}

$redTeamModelPath = "scripts/tests/fixtures/schema-validation/official-model-extra-property-invalid.json"
$redTeamReportPath = Join-Path $testRoot "official-model-extra-property-invalid-validation.json"
$ErrorActionPreference = "Continue"
pwsh -NoProfile -File scripts/quality/Invoke-JsonSchemaValidation.ps1 `
    -SchemaPath NPDevContract/schemas/model.schema.json `
    -JsonPath $redTeamModelPath `
    -ReportPath $redTeamReportPath 2>$null | Out-Null
$redTeamExit = $LASTEXITCODE
$ErrorActionPreference = "Stop"
if ($redTeamExit -eq 0) {
    throw "Official model schema accepted an unsupported extra property."
}

$report = [pscustomobject]@{
    schemaVersion = "npdev-ai-contract-normalizer-test-report.v1"
    runId = $RunId
    generatedAt = (Get-Date).ToUniversalTime().ToString("o")
    scriptPath = "scripts/quality/run-ai-contract-normalizer-tests.ps1"
    workspaceRoot = $workspaceRoot
    overallStatus = "passed"
    testedScenarios = @("base-ai-loop", "custom-procedure-panel")
    assertions = @(
        "positive scenario normalizes",
        "official model/config fields are injected and schema-valid",
        "official model output does not emit unsupported schemaVersion",
        "normalizer output is deterministic",
        "unsupported custom code scenario is rejected with a stable error code",
        "official schema rejects unsupported extra properties"
    )
}

$reportPath = "scripts/reports/out/ai-contract-normalizer-tests-report.json"
New-Item -ItemType Directory -Force -Path (Split-Path -Parent $reportPath) | Out-Null
$report | ConvertTo-Json -Depth 20 | Set-Content -LiteralPath $reportPath -Encoding UTF8
Write-Host ("AI contract normalizer tests passed. Report: " + $reportPath)
