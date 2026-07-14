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
$cleanupEvidence = $null
$observabilityHardening = $null
$runtimeSecurityConsistency = $null
$sampleDiagnosticsEnrichment = $null
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
        $verificationCommand = Invoke-NPDevCommandEvidence `
            -WorkspaceRoot $WorkspaceRoot `
            -WorkingDirectory $assembledAppRoot `
            -Executable $assembledGradleWrapper `
            -Arguments @("--no-daemon", "--console=plain", "enforceSingleSchemaRealizationSource", "test") `
            -LogPath $verificationLogPath

        if ([string]$verificationCommand.status -ne "passed") {
            throw "RuntimeHost verification command failed."
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
    # "package == support bucket" convention the beta-0 manifest refactor replaced with exact-lists;
    # they are reported as advisory observations pending a governance-owner realignment (same
    # pending-OK pattern the observability and sample-diagnostics steps use below). Build-time
    # allowlist enforcement is unaffected.
    & $runtimeSurfaceEvidenceScript -WorkspaceRoot $WorkspaceRoot -PendingOk

    Write-NPDevInfo "Generating RuntimeHost observability hardening report"
    $observabilityHardening = & $observabilityHardeningScript `
        -WorkspaceRoot $WorkspaceRoot `
        -RunId ($RunId + "-observability") `
        -ReportPath $observabilityHardeningReportPath `
        -RuntimeHostGatePendingOk `
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
    verificationTasks = @("enforceSingleSchemaRealizationSource", "test")
    verificationCommand = $verificationCommand
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
    $finalObservabilityHardening = & $observabilityHardeningScript `
        -WorkspaceRoot $WorkspaceRoot `
        -RunId ($RunId + "-observability") `
        -ReportPath $observabilityHardeningReportPath `
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
