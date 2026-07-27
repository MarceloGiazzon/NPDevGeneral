<#
.SYNOPSIS
    External AI delegation gate (ADR-0009): proves the mission registry, contract schemas, and pack
    producer are still honest and complete.

.DESCRIPTION
    Fails if:
      1. any register/roadmap summary row contradicts its own detail section, OR any external-AI
         mission lacks a run record (RUN or an explicit NOT_RUN reason) -- both now asserted by
         check-register-consistency.py's mission_run_coverage_gaps(), the P8 "asserts its own scope"
         requirement: a mission with neither is the same blind-spot shape every other check in that
         script exists to catch, one programme over.
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
param()

$ErrorActionPreference = "Stop"
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
Push-Location $repoRoot
try {
    $py = (Get-Command python -ErrorAction Stop).Source
    $failures = @()

    Write-Host "== External AI delegation gate (ADR-0009) ==" -ForegroundColor Cyan

    Write-Host "[1/3] Checking register consistency (includes mission-run coverage)..."
    & $py "scripts/quality/check-register-consistency.py"
    if ($LASTEXITCODE -ne 0) { $failures += "register consistency check failed (see output above -- may include missing mission run records)" }

    Write-Host "[2/3] Validating each mission in missions.json against external-ai-mission.schema.json..."
    $missionsPath = Join-Path $repoRoot "scripts/external-review/missions.json"
    $missionSchema = Join-Path $repoRoot "NPDevContract/schemas/external-ai-mission.schema.json"
    $missionsData = Get-Content -LiteralPath $missionsPath -Raw | ConvertFrom-Json
    $tempDir = Join-Path ([System.IO.Path]::GetTempPath()) ("npdev-mission-check-" + [guid]::NewGuid())
    New-Item -ItemType Directory -Force -Path $tempDir | Out-Null
    try {
        foreach ($mission in $missionsData.missions) {
            $missionFile = Join-Path $tempDir ($mission.missionId + ".json")
            $mission | ConvertTo-Json -Depth 20 | Set-Content -LiteralPath $missionFile -Encoding UTF8
            & pwsh -NoProfile -File "scripts/quality/Invoke-JsonSchemaValidation.ps1" -SchemaPath $missionSchema -InstancePath $missionFile | Out-Null
            if ($LASTEXITCODE -ne 0) {
                $failures += "mission $($mission.missionId) fails external-ai-mission.schema.json"
            }
        }
    }
    finally {
        Remove-Item -Recurse -Force $tempDir -ErrorAction SilentlyContinue
    }

    Write-Host "[3/3] Smoke-testing the pack producer (M2-SEC-ROWAUTHZ, working tree, no vendor call)..."
    $smokeOutputDir = Join-Path ([System.IO.Path]::GetTempPath()) ("npdev-pack-smoke-" + [guid]::NewGuid())
    & $py "scripts/external-review/build-review-pack.py" --mission-id M2-SEC-ROWAUTHZ --output-dir $smokeOutputDir
    if ($LASTEXITCODE -ne 0) {
        $failures += "pack producer smoke test failed to build M2-SEC-ROWAUTHZ"
    }
    else {
        $packFile = Get-ChildItem -Recurse -File -Path $smokeOutputDir -Filter "*.json" | Select-Object -First 1
        if (-not $packFile) {
            $failures += "pack producer smoke test produced no output file"
        }
        else {
            $packSchema = Join-Path $repoRoot "NPDevContract/schemas/external-ai-pack.schema.json"
            & pwsh -NoProfile -File "scripts/quality/Invoke-JsonSchemaValidation.ps1" -SchemaPath $packSchema -InstancePath $packFile.FullName | Out-Null
            if ($LASTEXITCODE -ne 0) { $failures += "smoke-test pack fails external-ai-pack.schema.json" }
        }
    }
    Remove-Item -Recurse -Force $smokeOutputDir -ErrorAction SilentlyContinue

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
