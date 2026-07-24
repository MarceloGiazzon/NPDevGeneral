param(
    [string]$RunId = "",
    [string]$ManifestPath = "scripts/reports/out/final-evidence-bundle-manifest.json",
    [string]$BundleRoot = ""
)

$ErrorActionPreference = "Stop"

function Read-JsonFile {
    param([string]$Path)
    return Get-Content -Raw -Path $Path | ConvertFrom-Json
}

function Get-ReportStatus {
    param([object]$Report)
    if ($null -eq $Report) { return "missing" }
    if ($Report.PSObject.Properties.Name -contains "overallStatus") { return [string]$Report.overallStatus }
    if ($Report.PSObject.Properties.Name -contains "status") { return [string]$Report.status }
    return "missing-status"
}

function New-RequiredReportRegistry {
    return @(
        [pscustomobject]@{ name = "beta0-state-truth"; reportPath = "scripts/reports/out/beta0-state-truth-report.json"; schemaPath = "schemas/ai/beta0-state-truth-report.schema.json" },
        [pscustomobject]@{ name = "maturity-max-roadmap-boundary"; reportPath = "scripts/reports/out/maturity-max-roadmap-boundary-report.json"; schemaPath = "schemas/ai/maturity-max-roadmap-boundary-report.schema.json" },
        [pscustomobject]@{ name = "phase2-residual-fidelity"; reportPath = "scripts/reports/out/phase2-residual-fidelity-report.json"; schemaPath = "schemas/ai/phase2-residual-fidelity-report.schema.json" },
        [pscustomobject]@{ name = "runtimehost-integration-infrastructure"; reportPath = "scripts/reports/out/runtimehost-integration-infrastructure-report.json"; schemaPath = "schemas/ai/runtimehost-integration-infrastructure-report.schema.json" },
        [pscustomobject]@{ name = "scenario-scope-reconciliation"; reportPath = "scripts/reports/out/scenario-scope-reconciliation-report.json"; schemaPath = "schemas/ai/scenario-scope-reconciliation-report.schema.json" },
        [pscustomobject]@{ name = "postgres-fidelity"; reportPath = "scripts/reports/out/postgres-fidelity-report.json"; schemaPath = "schemas/ai/postgres-fidelity-report.schema.json" },
        [pscustomobject]@{ name = "runtimehost-postgres-profile-fidelity"; reportPath = "scripts/reports/out/runtimehost-postgres-profile-fidelity-report.json"; schemaPath = "schemas/ai/runtimehost-postgres-profile-fidelity-report.schema.json" },
        [pscustomobject]@{ name = "runtime-e2e-fidelity"; reportPath = "scripts/reports/out/runtime-e2e-fidelity-report.json"; schemaPath = "schemas/ai/runtime-e2e-fidelity-report.schema.json" },
        [pscustomobject]@{ name = "scenario-coherence"; reportPath = "scripts/reports/out/scenario-coherence-report.json"; schemaPath = "schemas/ai/scenario-coherence-report.schema.json" },
        [pscustomobject]@{ name = "boundary-lock"; reportPath = "scripts/reports/out/boundary-lock-report.json"; schemaPath = "schemas/ai/boundary-lock-report.schema.json" },
        [pscustomobject]@{ name = "ai-model-to-dsl-mapping"; reportPath = "scripts/reports/out/ai-model-to-dsl-mapping-report.json"; schemaPath = "schemas/ai/ai-model-to-dsl-mapping-report.schema.json" },
        [pscustomobject]@{ name = "post-beta0-maturity-closure"; reportPath = "scripts/reports/out/post-beta0-maturity-closure-report.json"; schemaPath = "schemas/ai/post-beta0-maturity-closure-report.schema.json" },
        [pscustomobject]@{ name = "schema-consolidation"; reportPath = "scripts/reports/out/schema-consolidation-report.json"; schemaPath = "schemas/ai/schema-consolidation-report.schema.json" },
        [pscustomobject]@{ name = "stateful-additive-migrations"; reportPath = "scripts/reports/out/stateful-additive-migrations-report.json"; schemaPath = "schemas/ai/stateful-additive-migrations-report.schema.json" },
        [pscustomobject]@{ name = "incremental-migration-testing"; reportPath = "scripts/reports/out/incremental-migration-testing-report.json"; schemaPath = "schemas/ai/incremental-migration-testing-report.schema.json" },
        [pscustomobject]@{ name = "trusted-source-security"; reportPath = "scripts/reports/out/trusted-source-security-report.json"; schemaPath = "schemas/ai/trusted-source-security-report.schema.json" },
        [pscustomobject]@{ name = "shift-left-ai-safety"; reportPath = "scripts/reports/out/shift-left-ai-safety-report.json"; schemaPath = "schemas/ai/shift-left-ai-safety-report.schema.json" },
        [pscustomobject]@{ name = "custom-ux-extensibility"; reportPath = "scripts/reports/out/custom-ux-extensibility-report.json"; schemaPath = "schemas/ai/custom-ux-extensibility-report.schema.json" },
        [pscustomobject]@{ name = "editor-decomplexification"; reportPath = "scripts/reports/out/editor-decomplexification-report.json"; schemaPath = "schemas/ai/editor-decomplexification-report.schema.json" },
        [pscustomobject]@{ name = "dsl-parser-robustness"; reportPath = "scripts/reports/out/dsl-parser-robustness-report.json"; schemaPath = "schemas/ai/dsl-parser-robustness-report.schema.json" },
        [pscustomobject]@{ name = "maturity-max-final-closure"; reportPath = "scripts/reports/out/maturity-max-final-closure-report.json"; schemaPath = "schemas/ai/maturity-max-final-closure-report.schema.json" }
    )
}

$workspaceRoot = (Resolve-Path ".").Path
if ([string]::IsNullOrWhiteSpace($RunId)) {
    $RunId = "final-evidence-bundle-" + (Get-Date).ToUniversalTime().ToString("yyyyMMdd-HHmmssfff")
}
if ([string]::IsNullOrWhiteSpace($BundleRoot)) {
    if (-not [string]::IsNullOrWhiteSpace($env:NPDEV_FINAL_EVIDENCE_BUNDLE_DIR)) {
        $BundleRoot = $env:NPDEV_FINAL_EVIDENCE_BUNDLE_DIR
    }
    else {
        $workspace = Get-Item -Path $workspaceRoot
        $BundleRoot = Join-Path (Join-Path (Join-Path $workspace.Parent.FullName ($workspace.Name + "__OutsideRepo")) "temp") "final-evidence-bundle"
    }
}
$bundleRootFull = [System.IO.Path]::GetFullPath($BundleRoot)
$reportBundleRoot = Join-Path $bundleRootFull "reports"
if (Test-Path -Path $bundleRootFull) {
    Remove-Item -Path $bundleRootFull -Recurse -Force
}
New-Item -ItemType Directory -Force -Path $reportBundleRoot | Out-Null

$schemaValidationReportPath = "scripts/reports/out/required-report-schema-validation-report.json"
pwsh -NoProfile -File scripts/quality/validate-report-schemas.ps1 -RunId $RunId -ReportPath $schemaValidationReportPath -RequireAllMaturityReports | Out-Null
$schemaValidationReport = Read-JsonFile $schemaValidationReportPath

$artifacts = @()
foreach ($entry in New-RequiredReportRegistry) {
    $report = if (Test-Path -Path $entry.reportPath -PathType Leaf) { Read-JsonFile $entry.reportPath } else { $null }
    $status = Get-ReportStatus $report
    $destination = Join-Path $reportBundleRoot (Split-Path -Leaf $entry.reportPath)
    if (Test-Path -Path $entry.reportPath -PathType Leaf) {
        Copy-Item -Path $entry.reportPath -Destination $destination -Force
    }
    $artifactPath = if (Test-Path -Path $destination -PathType Leaf) { $destination } else { $entry.reportPath }
    $artifacts += [pscustomobject]@{
        name = $entry.name
        reportPath = $entry.reportPath
        bundledPath = ($artifactPath -replace "\\", "/")
        schemaPath = $entry.schemaPath
        schemaVersion = if ($null -ne $report) { [string]$report.schemaVersion } else { "" }
        overallStatus = $status
        bytes = if (Test-Path -Path $artifactPath -PathType Leaf) { (Get-Item -Path $artifactPath).Length } else { 0 }
        sha256 = if (Test-Path -Path $artifactPath -PathType Leaf) { (Get-FileHash -Algorithm SHA256 -Path $artifactPath).Hash.ToLowerInvariant() } else { "" }
    }
}

$missing = @($artifacts | Where-Object { $_.bytes -eq 0 })
$failed = @($artifacts | Where-Object { $_.bytes -gt 0 -and $_.overallStatus -ne "passed" })
# REG-32: scope schema-invalid to reports that actually EXIST here (mirrors validate-report-schemas.ps1
# -- a report with schemaStatus "not-run" was never produced, which is the $missing precondition
# above, not a schema defect).
$schemaInvalid = @($schemaValidationReport.reports | Where-Object { [bool]$_.exists -and [string]$_.schemaStatus -ne "passed" })
$hasCheckFailure = ($failed.Count -gt 0) -or ($schemaInvalid.Count -gt 0)
$hasPreconditionGap = ($missing.Count -gt 0)
$overallStatus = if ($hasCheckFailure) { "failed" } elseif ($hasPreconditionGap) { "precondition-unmet" } else { "passed" }
$manifest = [pscustomobject]@{
    schemaVersion = "npdev-final-evidence-bundle-manifest.v1"
    runId = $RunId
    generatedAt = (Get-Date).ToUniversalTime().ToString("o")
    scriptPath = "scripts/quality/generate-final-evidence-bundle.ps1"
    workspaceRoot = $workspaceRoot
    overallStatus = $overallStatus
    bundleRoot = ($bundleRootFull -replace "\\", "/")
    requiredReportCount = $artifacts.Count
    missingReportCount = $missing.Count
    failedReportCount = $failed.Count
    schemaInvalidReportCount = $schemaInvalid.Count
    schemaValidationReportPath = $schemaValidationReportPath
    artifacts = $artifacts
}

New-Item -ItemType Directory -Force -Path (Split-Path -Parent $ManifestPath) | Out-Null
$manifest | ConvertTo-Json -Depth 40 | Set-Content -Path $ManifestPath -Encoding UTF8
Copy-Item -Path $ManifestPath -Destination (Join-Path $bundleRootFull "final-evidence-bundle-manifest.json") -Force

$schemaValidationPath = "scripts/reports/tmp/final-evidence-bundle-manifest-schema-validation.json"
pwsh -NoProfile -File scripts/quality/Invoke-JsonSchemaValidation.ps1 `
    -SchemaPath "schemas/ai/final-evidence-bundle-manifest.schema.json" `
    -InstancePath $ManifestPath `
    -ReportPath $schemaValidationPath | Out-Null

# EXIT CODES (REG-3 pattern): 0 passed, 2 PRECONDITION-UNMET (required reports never generated --
# not a defect), 1 CHECK-FAILED (an existing report is schema-invalid or its own status is failed).
if ($overallStatus -eq "failed") {
    Write-Error ("Final evidence bundle generation failed. Manifest: " + $ManifestPath)
}
if ($overallStatus -eq "precondition-unmet") {
    Write-Host ("PRECONDITION-UNMET: " + $missing.Count + " of " + $artifacts.Count + " required reports were never generated (producers not run). Manifest: " + $ManifestPath)
    exit 2
}

Write-Host ("Final evidence bundle generated. Manifest: " + $ManifestPath)
