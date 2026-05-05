param(
    [string]$ReportPath = "scripts/reports/out/beta0-final-closure-report.json",
    [string]$RunId = ""
)

$ErrorActionPreference = "Stop"

function Read-JsonFile {
    param([string]$Path)
    return Get-Content -Raw -LiteralPath $Path | ConvertFrom-Json
}

function Add-Blocker {
    param([System.Collections.Generic.List[string]]$Blockers, [string]$Message)
    if (-not [string]::IsNullOrWhiteSpace($Message)) {
        $Blockers.Add($Message) | Out-Null
    }
}

function Invoke-GitText {
    param([string[]]$Arguments)
    try {
        $output = & git @Arguments 2>$null
        if ($LASTEXITCODE -ne 0) { return "" }
        return (($output | Out-String).Trim())
    }
    catch {
        return ""
    }
}

$workspaceRoot = (Resolve-Path ".").Path
if ([string]::IsNullOrWhiteSpace($RunId)) {
    $RunId = "beta0-final-closure-" + (Get-Date).ToUniversalTime().ToString("yyyyMMdd-HHmmssfff")
}
$blockers = [System.Collections.Generic.List[string]]::new()
$requiredReports = @(
    [pscustomobject]@{ name = "json-schema-validator-tests"; path = "scripts/reports/out/json-schema-validator-tests-report.json"; schemaVersion = "npdev-json-schema-validator-test-report.v1"; statusProperty = "overallStatus"; passValue = "passed" },
    [pscustomobject]@{ name = "ai-beta-gate"; path = "scripts/reports/out/ai-beta-gate-report.json"; schemaVersion = "npdev-ai-beta-gate-report.v1"; statusProperty = "overallStatus"; passValue = "passed" },
    [pscustomobject]@{ name = "controlled-command-runner-tests"; path = "scripts/reports/out/controlled-command-runner-tests-report.json"; schemaVersion = "npdev-controlled-command-runner-test-report.v1"; statusProperty = "overallStatus"; passValue = "passed" },
    [pscustomobject]@{ name = "ai-contract-normalizer-tests"; path = "scripts/reports/out/ai-contract-normalizer-tests-report.json"; schemaVersion = "npdev-ai-contract-normalizer-test-report.v1"; statusProperty = "overallStatus"; passValue = "passed" },
    [pscustomobject]@{ name = "ai-rest-smoke-verifier-tests"; path = "scripts/reports/out/ai-rest-smoke-verifier-tests-report.json"; schemaVersion = "npdev-ai-rest-smoke-verifier-test-report.v1"; statusProperty = "overallStatus"; passValue = "passed" },
    [pscustomobject]@{ name = "runtime-null-context-tests"; path = "scripts/reports/out/runtime-null-context-tests-report.json"; schemaVersion = "npdev-runtime-null-context-test-report.v1"; statusProperty = "overallStatus"; passValue = "passed" },
    [pscustomobject]@{ name = "sample-matrix"; path = "scripts/reports/out/sample-matrix-report.json"; schemaVersion = "npdev-sample-matrix-report.v1"; statusProperty = "overallStatus"; passValue = "passed" },
    [pscustomobject]@{ name = "report-schema-validation"; path = "scripts/reports/out/report-schema-validation-report.json"; schemaVersion = "npdev-report-schema-validation-report.v1"; statusProperty = "overallStatus"; passValue = "passed" },
    [pscustomobject]@{ name = "doc-entrypoint-validation"; path = "scripts/reports/out/doc-entrypoint-validation-report.json"; schemaVersion = "npdev-doc-entrypoint-validation-report.v1"; statusProperty = "overallStatus"; passValue = "passed" },
    [pscustomobject]@{ name = "report-provenance-tests"; path = "scripts/reports/out/report-provenance-tests-report.json"; schemaVersion = "npdev-report-provenance-test-report.v1"; statusProperty = "overallStatus"; passValue = "passed" },
    [pscustomobject]@{ name = "beta-release-gate"; path = "scripts/reports/out/beta-release-gate-report.json"; schemaVersion = "beta-release-gate-report.v1"; statusProperty = "overallStatus"; passValue = "passed" }
)

$reportResults = @()
foreach ($definition in $requiredReports) {
    $exists = Test-Path -LiteralPath $definition.path -PathType Leaf
    $status = "missing"
    $schemaVersion = ""
    $reportRunId = ""
    $hash = $null
    if ($exists) {
        $report = Read-JsonFile $definition.path
        $schemaVersion = [string]$report.schemaVersion
        $reportRunId = [string]$report.runId
        $property = $report.PSObject.Properties[[string]$definition.statusProperty]
        $status = if ($null -ne $property) { [string]$property.Value } else { "" }
        $hash = (Get-FileHash -Algorithm SHA256 -LiteralPath $definition.path).Hash.ToLowerInvariant()
        if ([string]::IsNullOrWhiteSpace($reportRunId)) {
            Add-Blocker $blockers ([string]$definition.name + " is missing runId.")
        }
        elseif ($reportRunId -ne $RunId) {
            Add-Blocker $blockers ([string]$definition.name + " runId does not match final closure runId.")
        }
        if ($schemaVersion -ne [string]$definition.schemaVersion) {
            Add-Blocker $blockers ([string]$definition.name + " schemaVersion mismatch.")
        }
        if ($status -ne [string]$definition.passValue) {
            Add-Blocker $blockers ([string]$definition.name + " is not passing.")
        }
    }
    else {
        Add-Blocker $blockers ([string]$definition.name + " report is missing.")
    }
    $reportResults += [pscustomobject]@{
        name = [string]$definition.name
        path = [string]$definition.path
        exists = $exists
        schemaVersion = $schemaVersion
        runId = $reportRunId
        status = $status
        contentSha256 = $hash
    }
}

$releaseReport = if (Test-Path -LiteralPath "scripts/reports/out/beta-release-gate-report.json" -PathType Leaf) {
    Read-JsonFile "scripts/reports/out/beta-release-gate-report.json"
}
else {
    $null
}

$candidateReady = $null -ne $releaseReport -and [bool]$releaseReport.candidateReady
$releaseReady = $null -ne $releaseReport -and [bool]$releaseReport.releaseReady
$provenanceReady = $null -ne $releaseReport -and [bool]$releaseReport.provenanceReady
$officialReleaseEligible = $null -ne $releaseReport -and [bool]$releaseReport.officialReleaseEligible

$dirtyStatus = Invoke-GitText @("status", "--porcelain=v1")
$workspaceDirty = -not [string]::IsNullOrWhiteSpace($dirtyStatus)
if ($workspaceDirty) {
    Add-Blocker $blockers "Workspace is dirty; beta0TagAllowed is blocked."
}
if (-not $officialReleaseEligible) {
    Add-Blocker $blockers "Release gate did not grant officialReleaseEligible."
}

$beta0TagAllowed = $candidateReady -and $releaseReady -and $provenanceReady -and $officialReleaseEligible -and $blockers.Count -eq 0
$status = if ($beta0TagAllowed) { "passed" } else { "failed" }

$finalReport = [pscustomobject]@{
    schemaVersion = "npdev-beta0-final-closure-report.v1"
    runId = $RunId
    generatedAt = (Get-Date).ToUniversalTime().ToString("o")
    scriptPath = "scripts/quality/run-beta0-final-closure-gate.ps1"
    workspaceRoot = $workspaceRoot
    status = $status
    overallStatus = $status
    candidateReady = $candidateReady
    releaseReady = $releaseReady
    provenanceReady = $provenanceReady
    officialReleaseEligible = $officialReleaseEligible
    beta0TagAllowed = $beta0TagAllowed
    workspaceDirty = $workspaceDirty
    requiredReports = $reportResults
    blockers = @($blockers)
}

New-Item -ItemType Directory -Force -Path (Split-Path -Parent $ReportPath) | Out-Null
$finalReport | ConvertTo-Json -Depth 30 | Set-Content -LiteralPath $ReportPath -Encoding UTF8

$schemaResultPath = "scripts/reports/tmp/report-schema-validation/beta0-final-closure-report-self.json"
$ErrorActionPreference = "Continue"
pwsh -NoProfile -File scripts/quality/Invoke-JsonSchemaValidation.ps1 `
    -SchemaPath "schemas/ai/beta0-final-closure-report.schema.json" `
    -InstancePath $ReportPath `
    -ReportPath $schemaResultPath 2>$null | Out-Null
$schemaExit = $LASTEXITCODE
$ErrorActionPreference = "Stop"
if ($schemaExit -ne 0) {
    Write-Error ("Beta 0 final closure report failed its report schema. Validation: " + $schemaResultPath)
}

if ($beta0TagAllowed) {
    Write-Host ("Beta 0 final closure gate passed. Report: " + $ReportPath)
    exit 0
}

Write-Error ("Beta 0 final closure gate failed. Report: " + $ReportPath)
