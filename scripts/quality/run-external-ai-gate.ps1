<#
.SYNOPSIS
    External AI delegation gate (ADR-0009): proves the mission registry, contract schemas, and pack
    producer are still honest and complete.

.DESCRIPTION
    Fails if:
      1. any external-AI mission lacks a run record (RUN or an explicit NOT_RUN reason), or a RUN
         record's backing pack evidence reads stale/unverified -- check-external-ai-mission-coverage.py
         (extracted from check-register-consistency.py by md-zero-2026-08-11 PLAN.md Phase 2, which
         deleted that script; these two checks read only JSON, never markdown, so they moved rather
         than deleted), the P8 "asserts its own scope" requirement: a mission with neither is the
         same blind-spot shape every other check in this gate exists to catch, one programme over.
      2. any mission in scripts/external-review/missions.json fails external-ai-mission.schema.json.
      3. the pack producer can no longer build a real pack end to end, or its output fails
         external-ai-pack.schema.json -- a smoke-test rebuild of M2-SEC-ROWAUTHZ against the CURRENT
         working tree, so this gate never depends on git history or a vendor key.

    This gate never sends anything to a vendor and never requires an API key: it only proves the
    LOCAL half of the pipeline (registry, schemas, producer) is still correct. Real vendor calls are
    gated separately by ADR-0009 D3 + actual credentials, never by this script.

.EXAMPLE
    pwsh -NoProfile -File scripts/quality/run-external-ai-gate.ps1
#>
param(
    [string]$WorkspaceRoot = "",
    [string]$RunId = "",
    [string]$ReportPath = ""
)

$ErrorActionPreference = "Stop"
. (Join-Path $PSScriptRoot "..\npdev-common.ps1")
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
if ([string]::IsNullOrWhiteSpace($WorkspaceRoot)) { $WorkspaceRoot = $repoRoot }
$WorkspaceRoot = Normalize-NPDevPath $WorkspaceRoot
$RunId = Resolve-NPDevRunId $RunId "external-ai-gate"
if ([string]::IsNullOrWhiteSpace($ReportPath)) {
    $ReportPath = Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\reports\out\external-ai-gate-report.json"
}
else {
    $ReportPath = Normalize-NPDevPath $ReportPath
}
Push-Location $repoRoot
try {
    $py = (Get-Command python -ErrorAction Stop).Source
    $failures = @()

    Write-Host "== External AI delegation gate (ADR-0009) ==" -ForegroundColor Cyan

    Write-Host "[1/3] Checking external-AI mission run coverage + provenance audit..."
    & $py "scripts/quality/check-external-ai-mission-coverage.py"
    $registerConsistencyPassed = ($LASTEXITCODE -eq 0)
    if (-not $registerConsistencyPassed) { $failures += "external-AI mission coverage check failed (see output above -- missing mission run records, or a stale/unverified pack)" }

    Write-Host "[2/3] Validating each mission in missions.json against external-ai-mission.schema.json..."
    $missionsPath = Join-Path $repoRoot "scripts/external-review/missions.json"
    $missionSchema = Join-Path $repoRoot "NPDevContract/schemas/external-ai-mission.schema.json"
    $missionsData = Get-Content -LiteralPath $missionsPath -Raw | ConvertFrom-Json
    $tempDir = Join-Path ([System.IO.Path]::GetTempPath()) ("npdev-mission-check-" + [guid]::NewGuid())
    New-Item -ItemType Directory -Force -Path $tempDir | Out-Null
    $missionSchemaFailures = @()
    try {
        foreach ($mission in $missionsData.missions) {
            $missionFile = Join-Path $tempDir ($mission.missionId + ".json")
            $mission | ConvertTo-Json -Depth 20 | Set-Content -LiteralPath $missionFile -Encoding utf8NoBOM
            & pwsh -NoProfile -File "scripts/quality/Invoke-JsonSchemaValidation.ps1" -SchemaPath $missionSchema -InstancePath $missionFile | Out-Null
            if ($LASTEXITCODE -ne 0) {
                $missionSchemaFailures += $mission.missionId
                $failures += "mission $($mission.missionId) fails external-ai-mission.schema.json"
            }
        }
    }
    finally {
        Remove-Item -Recurse -Force $tempDir -ErrorAction SilentlyContinue
    }
    $missionCount = @($missionsData.missions).Count

    Write-Host "[3/3] Smoke-testing the pack producer (M2-SEC-ROWAUTHZ, working tree, no vendor call)..."
    $smokeOutputDir = Join-Path ([System.IO.Path]::GetTempPath()) ("npdev-pack-smoke-" + [guid]::NewGuid())
    $packProducerPassed = $true
    $packSchemaPassed = $true
    & $py "scripts/external-review/build-review-pack.py" --mission-id M2-SEC-ROWAUTHZ --output-dir $smokeOutputDir
    if ($LASTEXITCODE -ne 0) {
        $packProducerPassed = $false
        $packSchemaPassed = $false
        $failures += "pack producer smoke test failed to build M2-SEC-ROWAUTHZ"
    }
    else {
        $packFile = Get-ChildItem -Recurse -File -Path $smokeOutputDir -Filter "*.json" | Select-Object -First 1
        if (-not $packFile) {
            $packSchemaPassed = $false
            $failures += "pack producer smoke test produced no output file"
        }
        else {
            $packSchema = Join-Path $repoRoot "NPDevContract/schemas/external-ai-pack.schema.json"
            & pwsh -NoProfile -File "scripts/quality/Invoke-JsonSchemaValidation.ps1" -SchemaPath $packSchema -InstancePath $packFile.FullName | Out-Null
            if ($LASTEXITCODE -ne 0) {
                $packSchemaPassed = $false
                $failures += "smoke-test pack fails external-ai-pack.schema.json"
            }
        }
    }
    Remove-Item -Recurse -Force $smokeOutputDir -ErrorAction SilentlyContinue

    $overallStatus = if ($failures.Count -eq 0) { "passed" } else { "failed" }
    $report = [pscustomobject]@{
        generatedAt = (Get-Date).ToString("o")
        runId = $RunId
        scriptPath = Get-NPDevWorkspaceRelativePath $WorkspaceRoot $PSCommandPath
        workspaceRoot = $WorkspaceRoot
        overallStatus = $overallStatus
        checks = @(
            [pscustomobject]@{ name = "external-ai-mission-coverage"; status = if ($registerConsistencyPassed) { "passed" } else { "failed" } }
            [pscustomobject]@{ name = "mission-schema-validation"; status = if ($missionSchemaFailures.Count -eq 0) { "passed" } else { "failed" }; missionCount = $missionCount; failingMissionIds = @($missionSchemaFailures) }
            [pscustomobject]@{ name = "pack-producer-smoke-test"; status = if ($packProducerPassed -and $packSchemaPassed) { "passed" } else { "failed" } }
        )
        failures = @($failures)
    }
    Write-NPDevJsonFile $ReportPath $report

    if ($failures.Count -gt 0) {
        Write-Host ""
        Write-Host "External AI delegation gate FAILED:" -ForegroundColor Red
        $failures | ForEach-Object { Write-Host "  - $_" -ForegroundColor Red }
        exit 1
    }
    Write-Host ""
    Write-Host "External AI delegation gate PASSED." -ForegroundColor Green
    exit 0
}
finally {
    Pop-Location
}
