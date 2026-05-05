param(
    [string]$RunId = ""
)

$ErrorActionPreference = "Stop"

pwsh -NoProfile -File scripts/quality/run-json-schema-validator-tests.ps1 -RunId $RunId
if ($LASTEXITCODE -ne 0) {
    exit $LASTEXITCODE
}

$sourceReport = "scripts/reports/out/json-schema-validator-tests-report.json"
$compatReport = "scripts/reports/out/json-schema-validation-tests-report.json"
$report = Get-Content -Raw -LiteralPath $sourceReport | ConvertFrom-Json
$report.schemaVersion = "npdev-json-schema-validation-test-report.v1"
$report.scriptPath = "scripts/quality/run-json-schema-validation-tests.ps1"
$report | ConvertTo-Json -Depth 30 | Set-Content -LiteralPath $compatReport -Encoding UTF8
Write-Host ("Compatibility JSON schema validation tests passed. Report: " + $compatReport)
