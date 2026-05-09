param(
    [switch]$ContinueOnFailure,
    [string]$ReportPath = "scripts/reports/out/beta0-final-release-check-report.json",
    [string]$RunId = ""
)

$ErrorActionPreference = "Stop"

function Invoke-Gate {
    param(
        [string]$Name,
        [string]$Command,
        [bool]$AlwaysContinue = $false,
        [bool]$ExpectedNonzero = $false
    )
    $startedAt = (Get-Date).ToUniversalTime()
    $ErrorActionPreference = "Continue"
    pwsh -NoProfile -File $Command -RunId $RunId 2>&1 | Out-Host
    $exitCode = $LASTEXITCODE
    $ErrorActionPreference = "Stop"
    $finishedAt = (Get-Date).ToUniversalTime()
    $status = if ($exitCode -eq 0) { "passed" } elseif ($ExpectedNonzero) { "failed-as-expected" } else { "failed" }
    $result = [pscustomobject]@{
        name = $Name
        command = ($Command + " -RunId " + $RunId)
        status = $status
        exitCode = $exitCode
        blocking = (-not $ExpectedNonzero)
        expectedNonzero = $ExpectedNonzero
        startedAt = $startedAt.ToString("o")
        finishedAt = $finishedAt.ToString("o")
        durationSeconds = [int]([DateTimeOffset]$finishedAt - [DateTimeOffset]$startedAt).TotalSeconds
    }
    $script:gateResults += $result
    if ($exitCode -ne 0 -and -not $ContinueOnFailure -and -not $AlwaysContinue) {
        throw "Gate failed: $Name"
    }
    return $result
}

$workspaceRoot = (Resolve-Path ".").Path
if ([string]::IsNullOrWhiteSpace($RunId)) {
    $RunId = "beta0-final-release-check-" + (Get-Date).ToUniversalTime().ToString("yyyyMMdd-HHmmssfff")
}
$gateResults = @()
$failedEarly = $false
$outRoot = Join-Path $workspaceRoot "scripts/reports/out"
if (Test-Path -LiteralPath $outRoot -PathType Container) {
    Get-ChildItem -LiteralPath $outRoot -Filter "*.json" -File | Remove-Item -Force
}
else {
    New-Item -ItemType Directory -Force -Path $outRoot | Out-Null
}

$orderedGates = @(
    [pscustomobject]@{ name = "json-schema-validator-tests"; command = "scripts/quality/run-json-schema-validator-tests.ps1" },
    [pscustomobject]@{ name = "ai-schema-validation"; command = "scripts/quality/run-ai-schema-validation.ps1" },
    [pscustomobject]@{ name = "ai-contract-normalizer-tests"; command = "scripts/quality/run-ai-contract-normalizer-tests.ps1" },
    [pscustomobject]@{ name = "controlled-command-runner-tests"; command = "scripts/quality/run-controlled-command-runner-tests.ps1" },
    [pscustomobject]@{ name = "ai-rest-smoke-verifier-tests"; command = "scripts/quality/run-ai-rest-smoke-verifier-tests.ps1" },
    [pscustomobject]@{ name = "runtime-null-context-tests"; command = "scripts/quality/run-runtime-null-context-tests.ps1" },
    [pscustomobject]@{ name = "runtimehost-staged-jar-preflight-tests"; command = "scripts/quality/run-runtimehost-staged-jar-preflight-tests.ps1" },
    [pscustomobject]@{ name = "runtimehost-staged-jar-preflight"; command = "scripts/quality/run-runtimehost-staged-jar-preflight.ps1" },
    [pscustomobject]@{ name = "frontend-gate"; command = "scripts/quality/run-frontend-gate.ps1" },
    [pscustomobject]@{ name = "frontend-gate-tests"; command = "scripts/quality/run-frontend-gate-tests.ps1" },
    [pscustomobject]@{ name = "docker-linux-proof-tests"; command = "scripts/quality/run-docker-linux-proof-tests.ps1" },
    [pscustomobject]@{ name = "docker-linux-proof"; command = "scripts/quality/run-docker-linux-proof.ps1" },
    [pscustomobject]@{ name = "sample-matrix-tests"; command = "scripts/quality/run-sample-matrix-tests.ps1" },
    [pscustomobject]@{ name = "scope-policy-enforcement-tests"; command = "scripts/quality/run-scope-policy-enforcement-tests.ps1" },
    [pscustomobject]@{ name = "direct-evidence-hardening-tests"; command = "scripts/quality/run-direct-evidence-hardening-tests.ps1" },
    [pscustomobject]@{ name = "runbook-workflow-alignment-tests"; command = "scripts/quality/run-runbook-workflow-alignment-tests.ps1" },
    [pscustomobject]@{ name = "sample-matrix"; command = "scripts/quality/run-sample-matrix.ps1" },
    [pscustomobject]@{ name = "ai-beta-gate"; command = "scripts/quality/run-ai-beta-gate.ps1" },
    [pscustomobject]@{ name = "expanded-beta0-evidence"; command = "scripts/quality/run-expanded-beta0-evidence.ps1" },
    [pscustomobject]@{ name = "structured-command-surface-alignment"; command = "scripts/quality/run-structured-command-surface-alignment.ps1" },
    [pscustomobject]@{ name = "trusted-source-beta0-proof-tests"; command = "scripts/quality/run-trusted-source-beta0-proof-tests.ps1" },
    [pscustomobject]@{ name = "trusted-source-beta0-proof"; command = "scripts/quality/run-trusted-source-beta0-proof.ps1" },
    [pscustomobject]@{ name = "doc-entrypoint-validation-tests"; command = "scripts/quality/run-doc-entrypoint-validation-tests.ps1" },
    [pscustomobject]@{ name = "report-schema-validation"; command = "scripts/quality/run-report-schema-validation.ps1" },
    [pscustomobject]@{ name = "doc-entrypoint-validation"; command = "scripts/quality/run-doc-entrypoint-validation.ps1" },
    [pscustomobject]@{ name = "beta-release-gate-pre-audit"; command = "scripts/quality/run-beta-release-gate.ps1"; alwaysContinue = $true; expectedNonzero = $true },
    [pscustomobject]@{ name = "final-regression-coverage-audit-tests"; command = "scripts/quality/run-final-regression-coverage-audit-tests.ps1" },
    [pscustomobject]@{ name = "final-regression-coverage-audit"; command = "scripts/quality/run-final-regression-coverage-audit.ps1" },
    [pscustomobject]@{ name = "report-schema-validation-final"; command = "scripts/quality/run-report-schema-validation.ps1" },
    [pscustomobject]@{ name = "report-provenance-tests"; command = "scripts/quality/run-report-provenance-tests.ps1" }
)

try {
    foreach ($gate in $orderedGates) {
        $alwaysContinue = $gate.PSObject.Properties.Name -contains "alwaysContinue" -and [bool]$gate.alwaysContinue
        $expectedNonzero = $gate.PSObject.Properties.Name -contains "expectedNonzero" -and [bool]$gate.expectedNonzero
        Invoke-Gate -Name $gate.name -Command $gate.command -AlwaysContinue $alwaysContinue -ExpectedNonzero $expectedNonzero | Out-Null
    }
}
catch {
    $failedEarly = $true
    if ($ContinueOnFailure) {
        Write-Warning $_.Exception.Message
    }
    else {
        Write-Warning $_.Exception.Message
    }
}

Invoke-Gate -Name "beta-release-gate" -Command "scripts/quality/run-beta-release-gate.ps1" -AlwaysContinue $true | Out-Null
Invoke-Gate -Name "beta0-final-closure-gate" -Command "scripts/quality/run-beta0-final-closure-gate.ps1" -AlwaysContinue $true | Out-Null

$closureReportPath = "scripts/reports/out/beta0-final-closure-report.json"
$closureReport = if (Test-Path -LiteralPath $closureReportPath -PathType Leaf) {
    Get-Content -Raw -LiteralPath $closureReportPath | ConvertFrom-Json
}
else {
    $null
}

$candidateReady = $null -ne $closureReport -and [bool]$closureReport.candidateReady
$releaseReady = $null -ne $closureReport -and [bool]$closureReport.releaseReady
$provenanceReady = $null -ne $closureReport -and [bool]$closureReport.provenanceReady
$officialReleaseEligible = $null -ne $closureReport -and [bool]$closureReport.officialReleaseEligible
$beta0TagAllowed = $null -ne $closureReport -and [bool]$closureReport.beta0TagAllowed
$blockingFailedGateCount = @($gateResults | Where-Object { $_.status -eq "failed" -and $_.blocking }).Count
$overallStatus = if ($beta0TagAllowed -and -not $failedEarly -and $blockingFailedGateCount -eq 0) { "passed" } else { "failed" }

$report = [pscustomobject]@{
    schemaVersion = "npdev-beta0-final-release-check-report.v1"
    runId = $RunId
    generatedAt = (Get-Date).ToUniversalTime().ToString("o")
    scriptPath = "scripts/quality/run-beta0-final-release-check.ps1"
    workspaceRoot = $workspaceRoot
    overallStatus = $overallStatus
    candidateReady = $candidateReady
    releaseReady = $releaseReady
    provenanceReady = $provenanceReady
    officialReleaseEligible = $officialReleaseEligible
    beta0TagAllowed = $beta0TagAllowed
    gates = $gateResults
}

New-Item -ItemType Directory -Force -Path (Split-Path -Parent $ReportPath) | Out-Null
$report | ConvertTo-Json -Depth 30 | Set-Content -LiteralPath $ReportPath -Encoding UTF8

if ($overallStatus -eq "passed") {
    Write-Host ("Beta 0 final release check passed. Report: " + $ReportPath)
    exit 0
}

Write-Error ("Beta 0 final release check failed. Report: " + $ReportPath)
