[CmdletBinding()]
param(
    [string]$WorkspaceRoot = "",
    [string]$RunId = "",
    [string]$ReportPath = "",
    [string]$SampleId = ""
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

. (Join-Path $PSScriptRoot "..\npdev-common.ps1")

if ([string]::IsNullOrWhiteSpace($WorkspaceRoot)) {
    $WorkspaceRoot = Get-NPDevWorkspaceRoot $PSScriptRoot
}
$WorkspaceRoot = Normalize-NPDevPath $WorkspaceRoot
$RunId = Resolve-NPDevRunId $RunId "runtimehost-gate"
if ([string]::IsNullOrWhiteSpace($SampleId)) {
    $SampleId = Get-NPDevDefaultSampleId $WorkspaceRoot
}

if ([string]::IsNullOrWhiteSpace($ReportPath)) {
    $ReportPath = Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\reports\out\runtimehost-gate-report.json"
}

$templateRoot = Resolve-NPDevWorkspacePath $WorkspaceRoot "NPDevRuntimeHost"
$runtimeHostCoreRoot = Resolve-NPDevWorkspacePath $WorkspaceRoot "NPDevRuntimeHost\runtimehost-core"
$syncRuntimeHostLibsScript = Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\runtimehost\sync-runtimehost-libs.ps1"
$generateSampleScript = Resolve-NPDevWorkspacePath $WorkspaceRoot "NPDevSamples\scripts\generate-sample-app.ps1"
$cleanSampleOutputScript = Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\samples\clean-sample-output.ps1"
$runtimeSurfaceEvidenceScript = Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\quality\run-runtime-surface-evidence.ps1"
$observabilityHardeningScript = Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\quality\run-observability-hardening.ps1"
$runtimeSecurityConsistencyScript = Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\quality\run-runtime-security-consistency.ps1"
$sampleDiagnosticsAuditScript = Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\quality\run-sample-diagnostics-audit.ps1"
$assembledAppRoot = Resolve-NPDevWorkspacePath $WorkspaceRoot ("NPDevSamples\" + $SampleId + "\Output\App")
$generationMarkerPath = Resolve-NPDevWorkspacePath $WorkspaceRoot ("NPDevSamples\" + $SampleId + "\Output\Reports\generation-run.json")
$observabilityHardeningReportPath = Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\reports\out\observability-hardening-report.json"
$runtimeSecurityConsistencyReportPath = Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\reports\out\runtime-security-consistency-report.json"
$sampleDiagnosticsReportPath = Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\reports\out\sample-diagnostics-enrichment-report.json"

Ensure-NPDevDirectory $templateRoot "RuntimeHost template root"
Ensure-NPDevDirectory $runtimeHostCoreRoot "runtimehost-core module root"
Ensure-NPDevFile $syncRuntimeHostLibsScript "RuntimeHost libs sync script"
Ensure-NPDevFile $generateSampleScript "Sample generation script"
Ensure-NPDevFile $cleanSampleOutputScript "Sample cleanup script"
Ensure-NPDevFile $runtimeSurfaceEvidenceScript "Runtime surface evidence script"
Ensure-NPDevFile $observabilityHardeningScript "Observability hardening report script"
Ensure-NPDevFile $runtimeSecurityConsistencyScript "Runtime security consistency report script"
Ensure-NPDevFile $sampleDiagnosticsAuditScript "Sample diagnostics enrichment audit script"

$status = "passed"
$errorMessage = $null
$verificationCommand = $null
$runtimeHostCoreCommand = $null
$cleanupEvidence = $null
$observabilityHardening = $null
$runtimeSecurityConsistency = $null
$sampleDiagnosticsEnrichment = $null
$coverageRatchetEvidence = $null
try {
    Write-NPDevInfo ("Synchronizing RuntimeHost local dependency jars for sample " + $SampleId)
    & $syncRuntimeHostLibsScript `
        -WorkspaceRoot $WorkspaceRoot `
        -BuildLocalJars `
        -ReportPath (Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\reports\out\runtimehost-libs-sync-report.json")

    Write-NPDevInfo ("Generating assembled RuntimeHost verification app from sample " + $SampleId)
    & $generateSampleScript -SampleId $SampleId -NPDevRoot $WorkspaceRoot -RunId $RunId

    Ensure-NPDevFile $generationMarkerPath "Sample generation marker"
    $generationMarker = Get-Content -LiteralPath $generationMarkerPath -Raw | ConvertFrom-Json
    if ([string]$generationMarker.runId -ne $RunId) {
        throw "Generated RuntimeHost verification app does not match current runId."
    }

    Ensure-NPDevDirectory $assembledAppRoot "Assembled RuntimeHost verification app"

    $cleanupError = $null
    try {
        Write-NPDevInfo ("Running RuntimeHost verification tasks for sample " + $SampleId)
        $verificationLogPath = Resolve-NPDevWorkspacePath $WorkspaceRoot ("scripts\reports\out\runtimehost-" + $SampleId + "-verification.log")
        $assembledGradleWrapper = Get-NPDevGradleWrapperExecutable $assembledAppRoot
        # R3 (MASTER-ROADMAP.md Step 9 / ledger QUAL-6): -PenableCoverage=true opts this ONE
        # gate-owned test run into the JaCoCo plugin gated in NPDevRuntimeHost/build.gradle.template
        # -- this does not change what FinalAppAssembler copies into any user's generated app, only
        # this script's own throwaway verification build.
        $verificationCommand = Invoke-NPDevCommandEvidence `
            -WorkspaceRoot $WorkspaceRoot `
            -WorkingDirectory $assembledAppRoot `
            -Executable $assembledGradleWrapper `
            -Arguments @("--no-daemon", "--console=plain", "-PenableCoverage=true", "enforceSingleSchemaRealizationSource", "test") `
            -LogPath $verificationLogPath

        if ([string]$verificationCommand.status -ne "passed") {
            throw "RuntimeHost verification command failed."
        }

        # QUAL-19: runtimehost-core is a STANDALONE Gradle root (its own settings.gradle, included by no
        # other settings.gradle), so nothing else runs its `test` task. Run it here -- the sync above
        # already staged the kernel/contract jars the module resolves from the runtimehost-libs fileTree.
        Write-NPDevInfo "Running runtimehost-core test task"
        $runtimeHostCoreLogPath = Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\reports\out\runtimehost-core-test.log"
        $runtimeHostCoreWrapper = Get-NPDevGradleWrapperExecutable $runtimeHostCoreRoot
        $runtimeHostCoreCommand = Invoke-NPDevCommandEvidence `
            -WorkspaceRoot $WorkspaceRoot `
            -WorkingDirectory $runtimeHostCoreRoot `
            -Executable $runtimeHostCoreWrapper `
            -Arguments @("--no-daemon", "--console=plain", "test") `
            -LogPath $runtimeHostCoreLogPath
        if ([string]$runtimeHostCoreCommand.status -ne "passed") {
            throw "runtimehost-core test task failed."
        }

        # R3: scripts/quality/check-coverage-ratchet.py reads this stable, non-cleaned path -- the
        # cleanup step below (-BuildCachesOnly) deletes the assembled app's own build/ directory
        # (where jacocoTestReport.xml actually lands) before run-ai-knowledge-gate.ps1 ever runs.
        $runtimeHostJacocoSource = Resolve-NPDevWorkspacePath $assembledAppRoot "build\reports\jacoco\test\jacocoTestReport.xml"
        $runtimeHostJacocoDestination = Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\reports\out\runtimehost-jacoco-test-report.xml"
        if (Test-Path -LiteralPath $runtimeHostJacocoSource -PathType Leaf) {
            New-Item -ItemType Directory -Force -Path (Split-Path -Parent $runtimeHostJacocoDestination) | Out-Null
            Copy-Item -LiteralPath $runtimeHostJacocoSource -Destination $runtimeHostJacocoDestination -Force
            Write-NPDevInfo ("Preserved RuntimeHost JaCoCo report before cleanup: " + $runtimeHostJacocoDestination)
        }

        # W3.2 (2026-08-25 remediation plan / QUAL-32, COV-RATCHET): check the ratchet HERE, right
        # after the report above is copied to its stable path -- this is the freshest this module's
        # coverage evidence will ever be. Previously the only ratchet check lived in
        # run-ai-knowledge-gate.ps1, which runs FIRST in run-all-gates.ps1 and so could never see this
        # run's own output within one invocation.
        Write-NPDevInfo "Checking RuntimeHost coverage against its recorded floor"
        $coverageRatchetPyExe = (Get-Command python -ErrorAction Stop).Source
        $coverageRatchetOutput = & $coverageRatchetPyExe "scripts/quality/check-coverage-ratchet.py" 2>&1 | ForEach-Object { $_.ToString() }
        $coverageRatchetExitCode = $LASTEXITCODE
        $coverageRatchetEvidence = [pscustomobject]@{
            overallStatus = if ($coverageRatchetExitCode -eq 0) { "passed" } else { "failed" }
            exitCode = $coverageRatchetExitCode
            output = @($coverageRatchetOutput | Select-Object -Last 30)
        }
        if ($coverageRatchetExitCode -ne 0) {
            throw "Coverage ratchet failed for RuntimeHost -- see scripts/quality/check-coverage-ratchet.py output above."
        }
    }
    finally {
        try {
            $cleanupReportPath = Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\reports\out\runtimehost-sample-clean-report.json"
            & $cleanSampleOutputScript -WorkspaceRoot $WorkspaceRoot -SampleIds @($SampleId) -BuildCachesOnly -ReportPath $cleanupReportPath | Out-Null
            $cleanupEvidence = [pscustomobject]@{
                status = "passed"
                reportPath = Get-NPDevWorkspaceRelativePath $WorkspaceRoot $cleanupReportPath
                error = $null
            }
        }
        catch {
            $cleanupError = $_.Exception.Message
            $cleanupEvidence = [pscustomobject]@{
                status = "failed"
                reportPath = $null
                error = $cleanupError
            }
        }
    }

    if (-not [string]::IsNullOrWhiteSpace($cleanupError)) {
        throw ("Sample output cleanup failed after RuntimeHost verification: " + $cleanupError)
    }

    Write-NPDevInfo "Generating RuntimeHost surface evidence reports"
    # -PendingOk: the surface-governance convergence/exclusivity checks encode the pre-d0bf41b
    # "package == support bucket" convention the beta-0 manifest refactor replaced with exact-lists.
    # GATE-OBS-1a DECISION (REG-5, 2026-07-21): these six checks are FORMALLY RETIRED as superseded by
    # the exact-list allowlist (runtime-surface-allowlist-report.json), which is the blocking
    # enforcement and passes -- they are informational only, not a pending-owner item. The switch name
    # stays -PendingOk for compatibility; the semantics are "retired convergence checks are advisory."
    # See docs/OPEN_GAPS_AND_ROADMAP.md#GATE-OBS-1a and run-observability-hardening.ps1's header.
    & $runtimeSurfaceEvidenceScript -WorkspaceRoot $WorkspaceRoot -PendingOk

    Write-NPDevInfo "Generating RuntimeHost observability hardening report"
    $observabilityHardening = & $observabilityHardeningScript `
        -WorkspaceRoot $WorkspaceRoot `
        -RunId ($RunId + "-observability") `
        -ReportPath $observabilityHardeningReportPath `
        -RuntimeHostGatePendingOk `
        -SurfaceConvergencePendingOk `
        -PassThru

    Write-NPDevInfo "Generating RuntimeHost security consistency report"
    $runtimeSecurityConsistency = & $runtimeSecurityConsistencyScript `
        -WorkspaceRoot $WorkspaceRoot `
        -RunId ($RunId + "-security") `
        -ReportPath $runtimeSecurityConsistencyReportPath `
        -PassThru

    Write-NPDevInfo "Generating sample diagnostics enrichment report"
    $sampleDiagnosticsEnrichment = & $sampleDiagnosticsAuditScript `
        -WorkspaceRoot $WorkspaceRoot `
        -RunId ($RunId + "-sample-diagnostics") `
        -ReportPath $sampleDiagnosticsReportPath `
        -MatrixPendingOk `
        -PassThru
}
catch {
    $status = "failed"
    $errorMessage = $_.Exception.Message
}

$generationMarkerEvidence = [pscustomobject]@{
    path = Get-NPDevWorkspaceRelativePath $WorkspaceRoot $generationMarkerPath
    status = "missing"
    markerRunId = $null
    markerSampleId = $null
    markerGeneratedAt = $null
    runIdMatches = $false
    error = "Generation marker file was not found."
}
if (Test-Path -LiteralPath $generationMarkerPath -PathType Leaf) {
    try {
        $marker = Get-Content -LiteralPath $generationMarkerPath -Raw | ConvertFrom-Json
        $markerRunId = [string]$marker.runId
        $markerGeneratedAt = if ($null -eq $marker.generatedAt) {
            $null
        }
        elseif ($marker.generatedAt -is [datetime]) {
            $marker.generatedAt.ToString("o")
        }
        else {
            [string]$marker.generatedAt
        }
        $matchesRun = $markerRunId -eq $RunId
        $generationMarkerEvidence = [pscustomobject]@{
            path = Get-NPDevWorkspaceRelativePath $WorkspaceRoot $generationMarkerPath
            status = if ($matchesRun) { "current" } else { "stale" }
            markerRunId = $markerRunId
            markerSampleId = if ($null -eq $marker.sampleId) { $null } else { [string]$marker.sampleId }
            markerGeneratedAt = $markerGeneratedAt
            runIdMatches = $matchesRun
            error = if ($matchesRun) { $null } else { "Generation marker runId does not match current runtimehost gate runId." }
        }
    }
    catch {
        $generationMarkerEvidence = [pscustomobject]@{
            path = Get-NPDevWorkspaceRelativePath $WorkspaceRoot $generationMarkerPath
            status = "parse-error"
            markerRunId = $null
            markerSampleId = $null
            markerGeneratedAt = $null
            runIdMatches = $false
            error = $_.Exception.Message
        }
    }
}

$report = [pscustomobject]@{
    generatedAt = (Get-Date).ToString("o")
    runId = $RunId
    scriptPath = Get-NPDevWorkspaceRelativePath $WorkspaceRoot $PSCommandPath
    workspaceRoot = $WorkspaceRoot
    overallStatus = $status
    templateRoot = $templateRoot
    sampleId = $SampleId
    assembledAppRoot = $assembledAppRoot
    generationMarker = $generationMarkerEvidence
    verificationTasks = @("enforceSingleSchemaRealizationSource", "test", "runtimehost-core:test")
    verificationCommand = $verificationCommand
    runtimeHostCoreCommand = $runtimeHostCoreCommand
    coverageRatchet = $coverageRatchetEvidence
    cleanup = $cleanupEvidence
    observabilityHardening = if ($null -eq $observabilityHardening) {
        $null
    }
    else {
        [pscustomobject]@{
            overallStatus = [string]$observabilityHardening.overallStatus
            reportPath = Get-NPDevWorkspaceRelativePath $WorkspaceRoot $observabilityHardeningReportPath
        }
    }
    runtimeSecurityConsistency = if ($null -eq $runtimeSecurityConsistency) {
        $null
    }
    else {
        [pscustomobject]@{
            overallStatus = [string]$runtimeSecurityConsistency.overallStatus
            reportPath = Get-NPDevWorkspaceRelativePath $WorkspaceRoot $runtimeSecurityConsistencyReportPath
        }
    }
    sampleDiagnosticsEnrichment = if ($null -eq $sampleDiagnosticsEnrichment) {
        $null
    }
    else {
        [pscustomobject]@{
            overallStatus = [string]$sampleDiagnosticsEnrichment.overallStatus
            reportPath = Get-NPDevWorkspaceRelativePath $WorkspaceRoot $sampleDiagnosticsReportPath
        }
    }
    error = $errorMessage
}
Write-NPDevJsonFile $ReportPath $report

if ($status -eq "passed") {
    Write-NPDevInfo "Refreshing RuntimeHost observability hardening report after gate finalization"
    # -RuntimeHostGatePendingOk is deliberately NOT passed here: by this point the gate report has
    # been written as passed, so `runtimehost-gate-current` must be able to read it as genuinely
    # green rather than as "pending". -SurfaceConvergencePendingOk IS passed, because the
    # surface-governance drift it covers (GATE-OBS-1) is a property of the codebase, not of where we
    # are in the gate -- omitting it here is what made this refresh re-fail on exactly the drift the
    # first invocation had already accepted as advisory.
    $finalObservabilityHardening = & $observabilityHardeningScript `
        -WorkspaceRoot $WorkspaceRoot `
        -RunId ($RunId + "-observability") `
        -ReportPath $observabilityHardeningReportPath `
        -SurfaceConvergencePendingOk `
        -PassThru

    if ([string]$finalObservabilityHardening.overallStatus -ne "passed") {
        $report.overallStatus = "failed"
        $report.error = "Final RuntimeHost observability hardening report did not pass after gate finalization."
        $report.observabilityHardening = [pscustomobject]@{
            overallStatus = [string]$finalObservabilityHardening.overallStatus
            reportPath = Get-NPDevWorkspaceRelativePath $WorkspaceRoot $observabilityHardeningReportPath
        }
        Write-NPDevJsonFile $ReportPath $report
        Write-NPDevWarn "NPDevRuntimeHost gate failed."
        throw $report.error
    }

    $report.observabilityHardening = [pscustomobject]@{
        overallStatus = [string]$finalObservabilityHardening.overallStatus
        reportPath = Get-NPDevWorkspaceRelativePath $WorkspaceRoot $observabilityHardeningReportPath
    }
    Write-NPDevJsonFile $ReportPath $report
    Write-NPDevOk ("NPDevRuntimeHost gate passed via assembled app sample " + $SampleId + ".")
    return
}

Write-NPDevWarn "NPDevRuntimeHost gate failed."
throw $errorMessage
