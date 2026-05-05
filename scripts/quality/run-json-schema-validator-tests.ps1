param(
    [string]$RunId = ""
)

$ErrorActionPreference = "Stop"

$workspaceRoot = (Resolve-Path ".").Path
if ([string]::IsNullOrWhiteSpace($RunId)) {
    $RunId = "json-schema-validator-tests-" + (Get-Date).ToUniversalTime().ToString("yyyyMMdd-HHmmssfff")
}
$testRoot = Join-Path $workspaceRoot "scripts/reports/tmp/json-schema-validator-tests"
if (Test-Path -LiteralPath $testRoot) {
    Remove-Item -LiteralPath $testRoot -Recurse -Force
}
New-Item -ItemType Directory -Force -Path $testRoot | Out-Null

function Invoke-ValidationCase {
    param(
        [string]$Name,
        [string]$SchemaPath,
        [string]$InstancePath,
        [bool]$ShouldPass,
        [string]$ExpectedKeyword = ""
    )
    $caseReportPath = Join-Path $testRoot ($Name + ".json")
    $ErrorActionPreference = "Continue"
    pwsh -NoProfile -File scripts/quality/Invoke-JsonSchemaValidation.ps1 `
        -SchemaPath $SchemaPath `
        -InstancePath $InstancePath `
        -ReportPath $caseReportPath 2>$null | Out-Null
    $exitCode = $LASTEXITCODE
    $ErrorActionPreference = "Stop"
    if (-not (Test-Path -LiteralPath $caseReportPath -PathType Leaf)) {
        throw "Schema validation case did not write a report: $Name"
    }
    $result = Get-Content -Raw -LiteralPath $caseReportPath | ConvertFrom-Json
    if ($ShouldPass -and ($exitCode -ne 0 -or $result.status -ne "passed")) {
        throw "Expected schema validation case to pass: $Name"
    }
    if (-not $ShouldPass -and ($exitCode -eq 0 -or $result.status -ne "failed")) {
        throw "Expected schema validation case to fail: $Name"
    }
    if (-not $ShouldPass -and -not [string]::IsNullOrWhiteSpace($ExpectedKeyword)) {
        $keywords = @($result.errors | ForEach-Object { [string]$_.keyword })
        if ($keywords -notcontains $ExpectedKeyword) {
            throw "Expected schema validation case $Name to fail with keyword $ExpectedKeyword, got: $($keywords -join ', ')"
        }
    }
    return [pscustomobject]@{
        name = $Name
        schemaPath = $SchemaPath
        instancePath = $InstancePath
        expectedStatus = if ($ShouldPass) { "passed" } else { "failed" }
        actualStatus = [string]$result.status
        engine = [string]$result.engine
        errorKeywords = @($result.errors | ForEach-Object { [string]$_.keyword })
    }
}

$cases = @(
    Invoke-ValidationCase "not-rejects-custom-panel" "schemas/ai/custom-panel.schema.json" "scripts/quality/fixtures/json-schema-validator/custom-panel-unsafe-invalid.json" $false "not"
    Invoke-ValidationCase "not-rejects-custom-procedure" "schemas/ai/custom-procedure.schema.json" "scripts/quality/fixtures/json-schema-validator/custom-procedure-unsafe-invalid.json" $false "not"
    Invoke-ValidationCase "unique-items-rejects-duplicates" "scripts/quality/fixtures/json-schema-validator/unique-items.schema.json" "scripts/quality/fixtures/json-schema-validator/unique-items-invalid.json" $false "uniqueItems"
    Invoke-ValidationCase "min-properties-rejects-empty-map" "scripts/quality/fixtures/json-schema-validator/min-properties.schema.json" "scripts/quality/fixtures/json-schema-validator/min-properties-invalid.json" $false "minProperties"
    Invoke-ValidationCase "format-rejects-invalid-values" "scripts/quality/fixtures/json-schema-validator/format.schema.json" "scripts/quality/fixtures/json-schema-validator/format-invalid.json" $false "format"
    Invoke-ValidationCase "else-branch-is-enforced" "scripts/quality/fixtures/json-schema-validator/if-then-else.schema.json" "scripts/quality/fixtures/json-schema-validator/else-invalid.json" $false "const"
    Invoke-ValidationCase "official-model-extra-property-invalid" "NPDevContract/schemas/model.schema.json" "scripts/tests/fixtures/schema-validation/official-model-extra-property-invalid.json" $false "additionalProperties"
    Invoke-ValidationCase "official-model-valid" "NPDevContract/schemas/model.schema.json" "scripts/tests/fixtures/schema-validation/official-model-valid.json" $true
    Invoke-ValidationCase "official-config-valid" "NPDevContract/schemas/config.schema.json" "scripts/tests/fixtures/schema-validation/official-config-valid.json" $true
)

$report = [pscustomobject]@{
    schemaVersion = "npdev-json-schema-validator-test-report.v1"
    runId = $RunId
    generatedAt = (Get-Date).ToUniversalTime().ToString("o")
    scriptPath = "scripts/quality/run-json-schema-validator-tests.ps1"
    workspaceRoot = $workspaceRoot
    overallStatus = "passed"
    cases = $cases
}

$reportPath = "scripts/reports/out/json-schema-validator-tests-report.json"
New-Item -ItemType Directory -Force -Path (Split-Path -Parent $reportPath) | Out-Null
$report | ConvertTo-Json -Depth 30 | Set-Content -LiteralPath $reportPath -Encoding UTF8
Write-Host ("JSON schema validator tests passed. Report: " + $reportPath)
