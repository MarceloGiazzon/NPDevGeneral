# Beta release evidence orchestration (REG-3 / GATE-REL-1).
#
# WHY THIS EXISTS
# ---------------
# scripts/policy/beta-release-gate-policy.json declares 36 requiredReports. Those reports are written
# by ~18 separate producer scripts, and nothing ever ran them as a set. The result: an invocation of
# run-beta-release-gate.ps1 on a clean tree exited 1 with 35 of 36 reports simply absent - which for
# two months was read as a *policy conflict* (the original GATE-REL-1/REG-3 framing) rather than as
# "the evidence was never generated." This script is the missing orchestration step.
#
# THE SINGLE-RUNID RULE
# ---------------------
# The gate requires every required report to carry exactly one shared runId. That is only satisfiable
# if one caller passes the same -RunId to every producer. That is this script's core job; running the
# producers by hand, one at a time, cannot satisfy the gate no matter how green each one is.
#
# ORDERING
# --------
# Producers are grouped into stages by real data dependency, not by preference:
#   stage 1  self-contained contract/unit-test reports - no app build, fast, fully parallelizable
#   stage 2  build/boot/container work - slow, and the source of the scenario evidence stage 3 reads
#   stage 3  derived reports that READ the reports produced above (schema validation, coverage audit,
#            command-surface alignment) - these are meaningless if run first
# Within a stage, order is not significant.
#
# A producer that fails does NOT stop the run. A failing producer usually still writes a report saying
# it failed - which is exactly the "check failed" evidence the gate should evaluate, and is
# categorically better than the "report missing" state that blocks evaluation entirely.

param(
    [string]$RunId = "",
    [string]$ReportPath = "scripts/reports/out/beta-release-evidence-orchestration-report.json",
    [string[]]$SkipProducers = @(),
    [string[]]$OnlyProducers = @(),
    [string[]]$Stages = @(),
    [int]$ProducerTimeoutSeconds = 5400
)

$ErrorActionPreference = "Stop"

$workspaceRoot = (Resolve-Path ".").Path

if ([string]::IsNullOrWhiteSpace($RunId)) {
    $RunId = "beta-release-gate-" + (Get-Date).ToUniversalTime().ToString("yyyyMMdd-HHmmssfff")
}

# Producer map. `reports` names the requiredReports entries each producer is responsible for, so a
# producer that exits 0 but leaves its report absent is still reported as a failure below.
$producers = @(
    # ---- stage 1: self-contained, no application build ------------------------------------------
    [pscustomobject]@{ name = "json-schema-validator-tests"; stage = 1
        script = "scripts/quality/run-json-schema-validator-tests.ps1"
        reports = @("json-schema-validator-tests-report.json") }
    [pscustomobject]@{ name = "ai-contract-normalizer-tests"; stage = 1
        script = "scripts/quality/run-ai-contract-normalizer-tests.ps1"
        reports = @("ai-contract-normalizer-tests-report.json") }
    [pscustomobject]@{ name = "ai-rest-smoke-verifier-tests"; stage = 1
        script = "scripts/quality/run-ai-rest-smoke-verifier-tests.ps1"
        reports = @("ai-rest-smoke-verifier-tests-report.json") }
    [pscustomobject]@{ name = "controlled-command-runner-tests"; stage = 1
        script = "scripts/quality/run-controlled-command-runner-tests.ps1"
        reports = @("controlled-command-runner-tests-report.json") }
    [pscustomobject]@{ name = "runtime-null-context-tests"; stage = 1
        script = "scripts/quality/run-runtime-null-context-tests.ps1"
        reports = @("runtime-null-context-tests-report.json") }
    [pscustomobject]@{ name = "direct-evidence-hardening-tests"; stage = 1
        script = "scripts/quality/run-direct-evidence-hardening-tests.ps1"
        reports = @("direct-evidence-hardening-tests-report.json") }
    [pscustomobject]@{ name = "doc-entrypoint-validation"; stage = 1
        script = "scripts/quality/run-doc-entrypoint-validation.ps1"
        reports = @("doc-entrypoint-validation-report.json") }
    [pscustomobject]@{ name = "ai-schema-validation"; stage = 1
        script = "scripts/quality/run-ai-schema-validation.ps1"
        reports = @("ai-schema-validation-report.json") }
    [pscustomobject]@{ name = "trusted-source-beta0-proof"; stage = 1
        script = "scripts/quality/run-trusted-source-beta0-proof.ps1"
        reports = @("trusted-source-beta0-proof-report.json") }

    # ---- stage 2: build / boot / container -------------------------------------------------------
    [pscustomobject]@{ name = "runtimehost-staged-jar-preflight"; stage = 2
        script = "scripts/quality/run-runtimehost-staged-jar-preflight.ps1"
        reports = @("runtimehost-staged-jar-preflight-report.json") }
    [pscustomobject]@{ name = "ai-beta-gate"; stage = 2
        script = "scripts/quality/run-ai-beta-gate.ps1"
        reports = @("ai-beta-gate-report.json") }
    # run-expanded-beta0-evidence reads the golden-scenario results the ai-beta-gate run produces, and
    # is single-handedly responsible for 18 of the 36 required reports.
    [pscustomobject]@{ name = "expanded-beta0-evidence"; stage = 2
        script = "scripts/quality/run-expanded-beta0-evidence.ps1"
        reports = @(
            "scope-policy-report.json", "schema-validation-report.json", "normalization-report.json",
            "custom-panel-validation-report.json", "custom-procedure-validation-report.json",
            "tenant-auth-role-validation-report.json", "workflow-validation-report.json",
            "generated-app-build-report.json", "generated-app-boot-report.json",
            "rest-smoke-report.json", "ui-panel-smoke-report.json", "procedure-smoke-report.json",
            "workflow-smoke-report.json", "tenant-isolation-smoke-report.json",
            "auth-role-smoke-report.json", "provenance-report.json",
            "stale-report-check-report.json", "workspace-cleanliness-report.json") }
    [pscustomobject]@{ name = "sample-matrix"; stage = 2
        script = "scripts/quality/run-sample-matrix.ps1"
        reports = @("sample-matrix-report.json") }
    [pscustomobject]@{ name = "docker-linux-parity"; stage = 2
        script = "scripts/quality/run-docker-linux-proof.ps1"
        requiresDocker = $true
        reports = @("docker-linux-parity-report.json") }

    # ---- stage 3: derived - these READ the reports produced above --------------------------------
    [pscustomobject]@{ name = "ai-command-policy"; stage = 3
        script = "scripts/quality/run-structured-command-surface-alignment.ps1"
        reads = @("ai-beta-gate-report.json")
        reports = @("ai-command-policy-report.json") }
    [pscustomobject]@{ name = "report-schema-validation"; stage = 3
        script = "scripts/quality/run-report-schema-validation.ps1"
        reads = @("<all reports produced above>")
        reports = @("report-schema-validation-report.json") }
    [pscustomobject]@{ name = "final-regression-coverage-audit"; stage = 3
        script = "scripts/quality/run-final-regression-coverage-audit.ps1"
        reads = @("sample-matrix-report.json", "beta-release-gate-report.json")
        reports = @("final-regression-coverage-audit-report.json") }
)

# ai-beta-reproducibility-report.json is deliberately absent from this map: run-beta-release-gate.ps1
# refreshes it itself on every invocation, with its own runId. Producing it here would create two
# writers of one report.

function Get-ReportOutPath {
    param([string]$FileName)
    return [System.IO.Path]::GetFullPath((Join-Path $workspaceRoot (Join-Path "scripts/reports/out" $FileName)))
}

function Test-DockerAvailable {
    try {
        $ErrorActionPreference = "Continue"
        & docker info 2>&1 | Out-Null
        $ok = ($LASTEXITCODE -eq 0)
        $ErrorActionPreference = "Stop"
        return $ok
    }
    catch { return $false }
}

$selected = @($producers)
if (@($OnlyProducers).Count -gt 0) {
    $selected = @($selected | Where-Object { $OnlyProducers -contains $_.name })
}
if (@($Stages).Count -gt 0) {
    $stageFilter = @($Stages | ForEach-Object { [int]$_ })
    $selected = @($selected | Where-Object { $stageFilter -contains [int]$_.stage })
}
$selected = @($selected | Sort-Object -Property stage)

$dockerAvailable = $null
$results = @()
$logRoot = Join-Path $workspaceRoot "scripts/reports/out"
New-Item -ItemType Directory -Force -Path $logRoot | Out-Null

foreach ($producer in $selected) {
    $name = [string]$producer.name
    $scriptRelative = [string]$producer.script
    $scriptFull = [System.IO.Path]::GetFullPath((Join-Path $workspaceRoot $scriptRelative))
    $logPath = "scripts/reports/out/orchestration-$name-output.txt"
    $logFull = Join-Path $workspaceRoot $logPath

    if ($SkipProducers -contains $name) {
        $results += [pscustomobject]@{ name = $name; stage = [int]$producer.stage; script = $scriptRelative
            status = "skipped"; reason = "explicitly skipped via -SkipProducers"; exitCode = $null
            durationSeconds = 0; logPath = $null; reports = @($producer.reports); reportsWritten = @(); reportsMissing = @() }
        Write-Host ("[skip] " + $name)
        continue
    }

    if (-not (Test-Path -LiteralPath $scriptFull -PathType Leaf)) {
        $results += [pscustomobject]@{ name = $name; stage = [int]$producer.stage; script = $scriptRelative
            status = "failed"; reason = "producer script not found"; exitCode = $null
            durationSeconds = 0; logPath = $null; reports = @($producer.reports); reportsWritten = @(); reportsMissing = @($producer.reports) }
        Write-Host ("[MISSING SCRIPT] " + $name + " -> " + $scriptRelative)
        continue
    }

    if ([bool]$producer.requiresDocker) {
        if ($null -eq $dockerAvailable) { $dockerAvailable = Test-DockerAvailable }
        if (-not $dockerAvailable) {
            $results += [pscustomobject]@{ name = $name; stage = [int]$producer.stage; script = $scriptRelative
                status = "skipped"; reason = "docker is not available on this host"; exitCode = $null
                durationSeconds = 0; logPath = $null; reports = @($producer.reports); reportsWritten = @(); reportsMissing = @($producer.reports) }
            Write-Host ("[skip] " + $name + " (docker unavailable)")
            continue
        }
    }

    Write-Host ("[stage " + [string]$producer.stage + "] " + $name + " ...")
    $startedAt = (Get-Date).ToUniversalTime()
    $ErrorActionPreference = "Continue"
    $output = & pwsh -NoProfile -File $scriptFull -RunId $RunId 2>&1
    $exitCode = $LASTEXITCODE
    $ErrorActionPreference = "Stop"
    $completedAt = (Get-Date).ToUniversalTime()
    @($output) | ForEach-Object { [string]$_ } | Set-Content -LiteralPath $logFull -Encoding UTF8

    $written = @()
    $missing = @()
    foreach ($reportFile in @($producer.reports)) {
        if (Test-Path -LiteralPath (Get-ReportOutPath $reportFile) -PathType Leaf) { $written += $reportFile }
        else { $missing += $reportFile }
    }

    # A producer is only "passed" if it exited 0 AND actually left every report it owns on disk.
    # Exit 0 with a missing report is the precise failure mode that made the gate uninterpretable.
    $status = if ($exitCode -eq 0 -and $missing.Count -eq 0) { "passed" }
        elseif ($missing.Count -eq 0) { "failed-with-evidence" }
        else { "failed-no-evidence" }

    $results += [pscustomobject]@{
        name = $name
        stage = [int]$producer.stage
        script = $scriptRelative
        status = $status
        reason = if ($status -eq "failed-no-evidence") { "producer did not write: " + (@($missing) -join ", ") } else { "" }
        exitCode = $exitCode
        durationSeconds = [int]($completedAt - $startedAt).TotalSeconds
        logPath = $logPath
        reports = @($producer.reports)
        reportsWritten = @($written)
        reportsMissing = @($missing)
    }
    Write-Host ("    -> " + $status + " (exit " + [string]$exitCode + ", " + [string][int]($completedAt - $startedAt).TotalSeconds + "s, " +
        [string]@($written).Count + "/" + [string]@($producer.reports).Count + " reports)")
}

$passedCount = @($results | Where-Object { [string]$_.status -eq "passed" }).Count
$noEvidenceCount = @($results | Where-Object { [string]$_.status -eq "failed-no-evidence" }).Count
$withEvidenceCount = @($results | Where-Object { [string]$_.status -eq "failed-with-evidence" }).Count
$skippedCount = @($results | Where-Object { [string]$_.status -eq "skipped" }).Count

# The orchestration's own success criterion is EVIDENCE COMPLETENESS, not producer greenness. A
# producer that fails loudly and writes a failing report has done its job here - judging that report
# is the gate's job, not this script's.
$overallStatus = if ($noEvidenceCount -eq 0 -and $skippedCount -eq 0) { "passed" } else { "failed" }

$report = [pscustomobject]@{
    schemaVersion = "npdev-beta-release-evidence-orchestration-report.v1"
    runId = $RunId
    generatedAt = (Get-Date).ToUniversalTime().ToString("o")
    scriptPath = "scripts/quality/run-beta-release-evidence-orchestration.ps1"
    workspaceRoot = $workspaceRoot
    overallStatus = $overallStatus
    status = $overallStatus
    successCriterion = "evidence completeness: every selected producer wrote every required report it owns. Producer non-zero exit with a written report is 'failed-with-evidence' and is the gate's problem to evaluate, not this script's."
    producerCount = @($results).Count
    passedCount = $passedCount
    failedWithEvidenceCount = $withEvidenceCount
    failedNoEvidenceCount = $noEvidenceCount
    skippedCount = $skippedCount
    producers = @($results)
}

$reportFull = [System.IO.Path]::GetFullPath((Join-Path $workspaceRoot $ReportPath))
New-Item -ItemType Directory -Force -Path (Split-Path -Parent $reportFull) | Out-Null
$report | ConvertTo-Json -Depth 20 | Set-Content -LiteralPath $reportFull -Encoding UTF8

Write-Host ""
Write-Host ("Evidence orchestration " + $overallStatus + ": " + [string]$passedCount + " passed, " +
    [string]$withEvidenceCount + " failed-with-evidence, " + [string]$noEvidenceCount + " failed-no-evidence, " +
    [string]$skippedCount + " skipped. RunId " + $RunId)
Write-Host ("Report: " + $ReportPath)

if ($overallStatus -eq "passed") { exit 0 }
exit 1
