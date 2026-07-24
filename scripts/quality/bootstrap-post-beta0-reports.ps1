param(
    [string]$RunId = "",
    [string]$ReportPath = "scripts/reports/out/report-bootstrap-and-regeneration-report.json"
)

$ErrorActionPreference = "Stop"

function Read-JsonFile {
    param([string]$Path)
    return Get-Content -Raw -Path $Path | ConvertFrom-Json
}

function Invoke-Producer {
    param([string]$Name, [string[]]$Command)
    $outputPath = "scripts/reports/out/cp4-bootstrap-$Name-output.txt"
    $startedAt = (Get-Date).ToUniversalTime().ToString("o")
    $executable = $Command[0]
    $arguments = @($Command | Select-Object -Skip 1)
    $ErrorActionPreference = "Continue"
    $output = & $executable @arguments 2>&1
    $exitCode = $LASTEXITCODE
    $ErrorActionPreference = "Stop"
    $output | ForEach-Object { [string]$_ } | Set-Content -Path $outputPath -Encoding UTF8
    # REG-3 exit-code convention (also adopted by validate-report-schemas.ps1 and
    # generate-final-evidence-bundle.ps1, REG-32): 0 passed, 2 precondition-unmet (required inputs
    # were never generated -- not a defect), 1 check-failed (a real, evaluable failure).
    $status = if ($exitCode -eq 0) { "passed" } elseif ($exitCode -eq 2) { "precondition-unmet" } else { "failed" }
    return [pscustomobject]@{
        name = $Name
        command = ($Command -join " ")
        startedAt = $startedAt
        completedAt = (Get-Date).ToUniversalTime().ToString("o")
        exitCode = $exitCode
        status = $status
        outputPath = $outputPath
    }
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
        "beta0-state-truth-report.json",
        "maturity-max-roadmap-boundary-report.json",
        "phase2-residual-fidelity-report.json",
        "runtimehost-integration-infrastructure-report.json",
        "scenario-scope-reconciliation-report.json",
        "postgres-fidelity-report.json",
        "runtimehost-postgres-profile-fidelity-report.json",
        "runtime-e2e-fidelity-report.json",
        "scenario-coherence-report.json",
        "boundary-lock-report.json",
        "ai-model-to-dsl-mapping-report.json",
        "post-beta0-maturity-closure-report.json",
        "schema-consolidation-report.json",
        "stateful-additive-migrations-report.json",
        "incremental-migration-testing-report.json",
        "trusted-source-security-report.json",
        "shift-left-ai-safety-report.json",
        "custom-ux-extensibility-report.json",
        "editor-decomplexification-report.json",
        "dsl-parser-robustness-report.json",
        "maturity-max-final-closure-report.json"
    )
}

$workspaceRoot = (Resolve-Path ".").Path
if ([string]::IsNullOrWhiteSpace($RunId)) {
    $RunId = "report-bootstrap-" + (Get-Date).ToUniversalTime().ToString("yyyyMMdd-HHmmssfff")
}
New-Item -ItemType Directory -Force -Path "scripts/reports/out" | Out-Null

$producers = @()
$producers += Invoke-Producer -Name "required-report-schema-validation" -Command @("pwsh", "-NoProfile", "-File", "scripts/quality/validate-report-schemas.ps1", "-RunId", $RunId, "-RequireAllMaturityReports")
$producers += Invoke-Producer -Name "final-evidence-bundle" -Command @("pwsh", "-NoProfile", "-File", "scripts/quality/generate-final-evidence-bundle.ps1", "-RunId", $RunId)

$requiredReports = New-RequiredReportRegistry
$reportStates = @()
foreach ($reportName in $requiredReports) {
    $path = "scripts/reports/out/$reportName"
    $report = if (Test-Path -Path $path -PathType Leaf) { Read-JsonFile $path } else { $null }
    $reportStates += [pscustomobject]@{
        name = $reportName
        path = $path
        exists = (Test-Path -Path $path -PathType Leaf)
        overallStatus = Get-ReportStatus $report
        sha256 = if (Test-Path -Path $path -PathType Leaf) { (Get-FileHash -Algorithm SHA256 -Path $path).Hash.ToLowerInvariant() } else { "" }
    }
}

$schemaValidation = if (Test-Path -Path "scripts/reports/out/required-report-schema-validation-report.json" -PathType Leaf) { Read-JsonFile "scripts/reports/out/required-report-schema-validation-report.json" } else { $null }
$finalManifest = if (Test-Path -Path "scripts/reports/out/final-evidence-bundle-manifest.json" -PathType Leaf) { Read-JsonFile "scripts/reports/out/final-evidence-bundle-manifest.json" } else { $null }
$missingReports = @($reportStates | Where-Object { -not $_.exists })
$failedReports = @($reportStates | Where-Object { $_.exists -and $_.overallStatus -ne "passed" })
# REG-32: schemaInvalidReportCount now comes from validate-report-schemas.ps1 already scoped to
# reports that EXIST but fail schema (not ones that were simply never produced -- see that script).
$schemaInvalidCount = if ($null -ne $schemaValidation) { [int]$schemaValidation.schemaInvalidReportCount } else { 0 }
$producerFailures = @($producers | Where-Object { $_.status -eq "failed" })
$producerPreconditionsUnmet = @($producers | Where-Object { $_.status -eq "precondition-unmet" })
$finalManifestStatus = if ($null -ne $finalManifest) { [string]$finalManifest.overallStatus } else { "missing" }

# REG-32 (REG-3 pattern): distinguish "required evidence was never generated" (precondition-unmet --
# this job does not run the ~21 producer gates, so ~19 missing reports is expected, not a defect)
# from "an existing report/producer asserts something is actually broken" (check-failed). Only the
# latter should hard-fail; the former degrades to a non-fatal, clearly-labeled precondition state.
$hasCheckFailure = ($failedReports.Count -gt 0) -or ($schemaInvalidCount -gt 0) -or ($producerFailures.Count -gt 0) -or ($finalManifestStatus -eq "failed")
$hasPreconditionGap = ($missingReports.Count -gt 0) -or ($producerPreconditionsUnmet.Count -gt 0) -or ($finalManifestStatus -eq "precondition-unmet") -or ($finalManifestStatus -eq "missing")
$overallStatus = if ($hasCheckFailure) { "failed" } elseif ($hasPreconditionGap) { "precondition-unmet" } else { "passed" }

$report = [pscustomobject]@{
    schemaVersion = "npdev-report-bootstrap-and-regeneration-report.v1"
    runId = $RunId
    generatedAt = (Get-Date).ToUniversalTime().ToString("o")
    scriptPath = "scripts/quality/bootstrap-post-beta0-reports.ps1"
    workspaceRoot = $workspaceRoot
    overallStatus = $overallStatus
    requiredReportCount = $requiredReports.Count
    missingReportCount = $missingReports.Count
    failedReportCount = $failedReports.Count
    schemaInvalidReportCount = $schemaInvalidCount
    producerPreconditionUnmetCount = $producerPreconditionsUnmet.Count
    finalEvidenceManifestGenerated = ($null -ne $finalManifest -and [string]$finalManifest.overallStatus -eq "passed")
    ciUploadsReports = $true
    producers = $producers
    requiredReports = $reportStates
    finalEvidenceManifestPath = "scripts/reports/out/final-evidence-bundle-manifest.json"
    findings = @(
        [pscustomobject]@{
            id = "CP4-REPORT-BOOTSTRAP-REGISTERED"
            classification = "current-checkpoint-blocker"
            status = if ($overallStatus -eq "passed") { "resolved" } else { "open" }
            summary = "Required maturity reports are verified, schema-validated, and included in the final evidence manifest without re-running historical checkpoint producers during final closure."
        }
    )
    doesNotSolve = @(
        "Does not retag or move beta0.",
        "Does not re-run every historical checkpoint proof.",
        "Does not commit bulky generated workspaces."
    )
}

New-Item -ItemType Directory -Force -Path (Split-Path -Parent $ReportPath) | Out-Null
$report | ConvertTo-Json -Depth 60 | Set-Content -Path $ReportPath -Encoding UTF8

# EXIT CODES (REG-3 pattern): 0 passed, 2 PRECONDITION-UNMET (required reports/producers never ran --
# not a defect), 1 CHECK-FAILED (an existing report/producer asserts something is actually broken).
if ($overallStatus -eq "failed") {
    Write-Error ("Report bootstrap failed. Report: " + $ReportPath)
}
if ($overallStatus -eq "precondition-unmet") {
    Write-Host ("PRECONDITION-UNMET: " + $missingReports.Count + " of " + $requiredReports.Count + " required reports were never generated (producers not run). Report: " + $ReportPath)
    exit 2
}

Write-Host ("Report bootstrap passed. Report: " + $ReportPath)
