param(
    [string]$RunId = "",
    [string]$ReportPath = "scripts/reports/out/required-report-schema-validation-report.json",
    [switch]$RequireAllMaturityReports
)

$ErrorActionPreference = "Stop"

function Read-JsonFile {
    param([string]$Path)
    return Get-Content -Raw -Path $Path | ConvertFrom-Json
}

function New-RequiredReportRegistry {
    return @(
        [pscustomobject]@{ name = "beta0-state-truth"; reportPath = "scripts/reports/out/beta0-state-truth-report.json"; schemaPath = "schemas/ai/beta0-state-truth-report.schema.json"; requiredForCheckpoint1 = $false },
        [pscustomobject]@{ name = "maturity-max-roadmap-boundary"; reportPath = "scripts/reports/out/maturity-max-roadmap-boundary-report.json"; schemaPath = "schemas/ai/maturity-max-roadmap-boundary-report.schema.json"; requiredForCheckpoint1 = $false },
        [pscustomobject]@{ name = "phase2-residual-fidelity"; reportPath = "scripts/reports/out/phase2-residual-fidelity-report.json"; schemaPath = "schemas/ai/phase2-residual-fidelity-report.schema.json"; requiredForCheckpoint1 = $false },
        [pscustomobject]@{ name = "maturity-score"; reportPath = "scripts/reports/out/maturity-score-report.json"; schemaPath = "schemas/ai/maturity-score-report.schema.json"; requiredForCheckpoint1 = $true },
        [pscustomobject]@{ name = "script-inventory"; reportPath = "scripts/reports/out/script-inventory-report.json"; schemaPath = "schemas/ai/script-inventory-report.schema.json"; requiredForCheckpoint1 = $true },
        [pscustomobject]@{ name = "runtimehost-integration-infrastructure"; reportPath = "scripts/reports/out/runtimehost-integration-infrastructure-report.json"; schemaPath = "schemas/ai/runtimehost-integration-infrastructure-report.schema.json"; requiredForCheckpoint1 = $false },
        [pscustomobject]@{ name = "scenario-scope-reconciliation"; reportPath = "scripts/reports/out/scenario-scope-reconciliation-report.json"; schemaPath = "schemas/ai/scenario-scope-reconciliation-report.schema.json"; requiredForCheckpoint1 = $false },
        [pscustomobject]@{ name = "postgres-fidelity"; reportPath = "scripts/reports/out/postgres-fidelity-report.json"; schemaPath = "schemas/ai/postgres-fidelity-report.schema.json"; requiredForCheckpoint1 = $false },
        [pscustomobject]@{ name = "runtimehost-postgres-profile-fidelity"; reportPath = "scripts/reports/out/runtimehost-postgres-profile-fidelity-report.json"; schemaPath = "schemas/ai/runtimehost-postgres-profile-fidelity-report.schema.json"; requiredForCheckpoint1 = $false },
        [pscustomobject]@{ name = "runtime-e2e-fidelity"; reportPath = "scripts/reports/out/runtime-e2e-fidelity-report.json"; schemaPath = "schemas/ai/runtime-e2e-fidelity-report.schema.json"; requiredForCheckpoint1 = $false },
        [pscustomobject]@{ name = "scenario-coherence"; reportPath = "scripts/reports/out/scenario-coherence-report.json"; schemaPath = "schemas/ai/scenario-coherence-report.schema.json"; requiredForCheckpoint1 = $false },
        [pscustomobject]@{ name = "boundary-lock"; reportPath = "scripts/reports/out/boundary-lock-report.json"; schemaPath = "schemas/ai/boundary-lock-report.schema.json"; requiredForCheckpoint1 = $false },
        [pscustomobject]@{ name = "ai-model-to-dsl-mapping"; reportPath = "scripts/reports/out/ai-model-to-dsl-mapping-report.json"; schemaPath = "schemas/ai/ai-model-to-dsl-mapping-report.schema.json"; requiredForCheckpoint1 = $false },
        [pscustomobject]@{ name = "post-beta0-maturity-closure"; reportPath = "scripts/reports/out/post-beta0-maturity-closure-report.json"; schemaPath = "schemas/ai/post-beta0-maturity-closure-report.schema.json"; requiredForCheckpoint1 = $false },
        [pscustomobject]@{ name = "schema-consolidation"; reportPath = "scripts/reports/out/schema-consolidation-report.json"; schemaPath = "schemas/ai/schema-consolidation-report.schema.json"; requiredForCheckpoint1 = $false },
        [pscustomobject]@{ name = "stateful-additive-migrations"; reportPath = "scripts/reports/out/stateful-additive-migrations-report.json"; schemaPath = "schemas/ai/stateful-additive-migrations-report.schema.json"; requiredForCheckpoint1 = $false },
        [pscustomobject]@{ name = "incremental-migration-testing"; reportPath = "scripts/reports/out/incremental-migration-testing-report.json"; schemaPath = "schemas/ai/incremental-migration-testing-report.schema.json"; requiredForCheckpoint1 = $false },
        [pscustomobject]@{ name = "trusted-source-security"; reportPath = "scripts/reports/out/trusted-source-security-report.json"; schemaPath = "schemas/ai/trusted-source-security-report.schema.json"; requiredForCheckpoint1 = $false },
        [pscustomobject]@{ name = "shift-left-ai-safety"; reportPath = "scripts/reports/out/shift-left-ai-safety-report.json"; schemaPath = "schemas/ai/shift-left-ai-safety-report.schema.json"; requiredForCheckpoint1 = $false },
        [pscustomobject]@{ name = "custom-ux-extensibility"; reportPath = "scripts/reports/out/custom-ux-extensibility-report.json"; schemaPath = "schemas/ai/custom-ux-extensibility-report.schema.json"; requiredForCheckpoint1 = $false },
        [pscustomobject]@{ name = "editor-decomplexification"; reportPath = "scripts/reports/out/editor-decomplexification-report.json"; schemaPath = "schemas/ai/editor-decomplexification-report.schema.json"; requiredForCheckpoint1 = $false },
        [pscustomobject]@{ name = "dsl-parser-robustness"; reportPath = "scripts/reports/out/dsl-parser-robustness-report.json"; schemaPath = "schemas/ai/dsl-parser-robustness-report.schema.json"; requiredForCheckpoint1 = $false },
        [pscustomobject]@{ name = "maturity-max-final-closure"; reportPath = "scripts/reports/out/maturity-max-final-closure-report.json"; schemaPath = "schemas/ai/maturity-max-final-closure-report.schema.json"; requiredForCheckpoint1 = $false }
    )
}

function Get-ReportStatus {
    param([object]$Report)
    if ($null -eq $Report) {
        return "missing"
    }
    if ($Report.PSObject.Properties.Name -contains "overallStatus") {
        return [string]$Report.overallStatus
    }
    if ($Report.PSObject.Properties.Name -contains "status") {
        return [string]$Report.status
    }
    return "missing-status"
}

function Invoke-RequiredReportSchemaValidation {
    param([object]$RequiredReport, [string]$ValidationRoot)
    $validationPath = Join-Path $ValidationRoot ($RequiredReport.name + ".json")
    $exists = (Test-Path -Path $RequiredReport.reportPath -PathType Leaf) -and (Test-Path -Path $RequiredReport.schemaPath -PathType Leaf)
    $report = if (Test-Path -Path $RequiredReport.reportPath -PathType Leaf) { Read-JsonFile $RequiredReport.reportPath } else { $null }
    $status = Get-ReportStatus $report
    $schemaStatus = "not-run"
    $errors = @()
    if ($exists) {
        $ErrorActionPreference = "Continue"
        pwsh -NoProfile -File scripts/quality/Invoke-JsonSchemaValidation.ps1 `
            -SchemaPath $RequiredReport.schemaPath `
            -InstancePath $RequiredReport.reportPath `
            -ReportPath $validationPath 2>$null | Out-Null
        $exitCode = $LASTEXITCODE
        $ErrorActionPreference = "Stop"
        $result = if (Test-Path -Path $validationPath -PathType Leaf) { Read-JsonFile $validationPath } else { $null }
        $schemaStatus = if ($exitCode -eq 0 -and $null -ne $result -and [string]$result.status -eq "passed") { "passed" } else { "failed" }
        $errors = if ($null -ne $result) { @($result.failures) } else { @("schema validation did not write a result") }
    }
    elseif (-not (Test-Path -Path $RequiredReport.reportPath -PathType Leaf)) {
        $errors = @("required report is missing")
    }
    else {
        $errors = @("required schema is missing")
    }

    return [pscustomobject]@{
        name = $RequiredReport.name
        reportPath = $RequiredReport.reportPath
        schemaPath = $RequiredReport.schemaPath
        required = ($RequireAllMaturityReports -or [bool]$RequiredReport.requiredForCheckpoint1)
        exists = (Test-Path -Path $RequiredReport.reportPath -PathType Leaf)
        reportStatus = $status
        schemaStatus = $schemaStatus
        passed = ((Test-Path -Path $RequiredReport.reportPath -PathType Leaf) -and $status -eq "passed" -and $schemaStatus -eq "passed")
        errors = @($errors)
    }
}

$workspaceRoot = (Resolve-Path ".").Path
if ([string]::IsNullOrWhiteSpace($RunId)) {
    $RunId = "required-report-schema-validation-" + (Get-Date).ToUniversalTime().ToString("yyyyMMdd-HHmmssfff")
}
$validationRoot = "scripts/reports/tmp/required-report-schema-validation"
if (Test-Path -Path $validationRoot) {
    Remove-Item -Path $validationRoot -Recurse -Force
}
New-Item -ItemType Directory -Force -Path $validationRoot | Out-Null

$requiredReports = New-RequiredReportRegistry
$results = @($requiredReports | ForEach-Object { Invoke-RequiredReportSchemaValidation $_ $validationRoot })
$missing = @($results | Where-Object { $_.required -and -not $_.exists })
$failed = @($results | Where-Object { $_.required -and $_.exists -and $_.reportStatus -ne "passed" })
# REG-32: a report that was never generated has schemaStatus "not-run", not "invalid" -- only an
# EXISTING report that fails schema validation is a genuine defect. Scoping this to $_.exists keeps
# "never produced" (precondition-unmet) from being counted as "produced but wrong" (check-failed).
$schemaInvalid = @($results | Where-Object { $_.required -and $_.exists -and $_.schemaStatus -ne "passed" })
$hasCheckFailure = ($failed.Count -gt 0) -or ($schemaInvalid.Count -gt 0)
$hasPreconditionGap = ($missing.Count -gt 0)
$overallStatus = if ($hasCheckFailure) { "failed" } elseif ($hasPreconditionGap) { "precondition-unmet" } else { "passed" }

$report = [pscustomobject]@{
    schemaVersion = "npdev-required-report-schema-validation-report.v1"
    runId = $RunId
    generatedAt = (Get-Date).ToUniversalTime().ToString("o")
    scriptPath = "scripts/quality/validate-report-schemas.ps1"
    workspaceRoot = $workspaceRoot
    overallStatus = $overallStatus
    requiredReportCount = $requiredReports.Count
    missingReportCount = $missing.Count
    failedReportCount = $failed.Count
    schemaInvalidReportCount = $schemaInvalid.Count
    reports = $results
}

New-Item -ItemType Directory -Force -Path (Split-Path -Parent $ReportPath) | Out-Null
$report | ConvertTo-Json -Depth 40 | Set-Content -Path $ReportPath -Encoding UTF8

# EXIT CODES (REG-3 pattern): 0 passed, 2 PRECONDITION-UNMET (required reports never generated --
# not a defect), 1 CHECK-FAILED (an existing report is schema-invalid or its own status is failed).
if ($overallStatus -eq "failed") {
    Write-Error ("Required report schema validation failed. Report: " + $ReportPath)
}
if ($overallStatus -eq "precondition-unmet") {
    Write-Host ("PRECONDITION-UNMET: " + $missing.Count + " of " + $requiredReports.Count + " required reports were never generated (producers not run). Report: " + $ReportPath)
    exit 2
}

Write-Host ("Required report schema validation passed. Report: " + $ReportPath)
