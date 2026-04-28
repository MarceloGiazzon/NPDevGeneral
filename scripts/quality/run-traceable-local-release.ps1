[CmdletBinding()]
param(
    [string]$WorkspaceRoot = "",
    [string]$RunId = "",
    [AllowEmptyString()]
    [string]$SourceCommitSha = "",
    [AllowEmptyString()]
    [string]$SourceBranch = "",
    [AllowNull()][object]$SourceDirty = $null,
    [string]$SourceProvider = "local-git",
    [string]$SourceRunId = "",
    [string]$SourceRunAttempt = "",
    [string]$SourceWorkflow = "traceable-local-release",
    [string]$SampleId = "",
    [string]$ReportPath = "",
    [string]$WrapperReportPath = "",
    [string]$EvidenceRoot = "",
    [switch]$PreserveExistingReports,
    [switch]$AllowDiagnosticFallback,
    [switch]$PassThru,
    [string]$BetaGateScriptPath = ""
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

. (Join-Path $PSScriptRoot "..\npdev-common.ps1")

if ([string]::IsNullOrWhiteSpace($WorkspaceRoot)) {
    $WorkspaceRoot = Get-NPDevWorkspaceRoot $PSScriptRoot
}
$WorkspaceRoot = Normalize-NPDevPath $WorkspaceRoot
$RunId = Resolve-NPDevRunId $RunId "traceable-local-release"
if ([string]::IsNullOrWhiteSpace($SampleId)) {
    $SampleId = Get-NPDevDefaultSampleId $WorkspaceRoot
}
if ([string]::IsNullOrWhiteSpace($BetaGateScriptPath)) {
    $BetaGateScriptPath = Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\quality\run-beta-release-gate.ps1"
}
else {
    $BetaGateScriptPath = Normalize-NPDevPath $BetaGateScriptPath
}
Ensure-NPDevFile $BetaGateScriptPath "Beta release gate script"

if ([string]::IsNullOrWhiteSpace($WrapperReportPath)) {
    $WrapperReportPath = Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\reports\out\traceable-local-release-report.json"
}
else {
    $WrapperReportPath = Normalize-NPDevPath $WrapperReportPath
}

function Get-TraceableGitFirstOutputLine([object]$Capture) {
    $lines = @($Capture.Output | Where-Object { -not [string]::IsNullOrWhiteSpace([string]$_) })
    if ($lines.Count -eq 0) {
        return $null
    }
    return [string]$lines[0]
}

function New-TraceableLocalReleaseDiagnostic(
    [string]$Status,
    [string]$Reason,
    [string]$Message,
    [object[]]$Steps = @()
) {
    return [pscustomobject]@{
        status = $Status
        reason = $Reason
        message = $Message
        steps = @($Steps)
    }
}

function Get-LocalGitTraceability {
    param(
        [Parameter(Mandatory = $true)]
        [string]$WorkspaceRootValue
    )

    $steps = [System.Collections.Generic.List[object]]::new()
    if (-not (Test-NPDevCommandAvailable "git")) {
        return [pscustomobject]@{
            identity = $null
            diagnostics = New-TraceableLocalReleaseDiagnostic `
                -Status "failed" `
                -Reason "git-not-found" `
                -Message "Git was not found on PATH; cannot discover source commit identity." `
                -Steps @()
        }
    }

    $insideWorkTreeCapture = Invoke-NPDevCommandCapture -WorkingDirectory $WorkspaceRootValue -Executable "git" -Arguments @("rev-parse", "--is-inside-work-tree")
    $insideWorkTree = Get-TraceableGitFirstOutputLine $insideWorkTreeCapture
    [void]$steps.Add([pscustomobject]@{
            command = "git rev-parse --is-inside-work-tree"
            exitCode = [int]$insideWorkTreeCapture.ExitCode
            firstOutputLine = $insideWorkTree
        })
    if ($insideWorkTreeCapture.ExitCode -ne 0 -or [string]$insideWorkTree -ne "true") {
        return [pscustomobject]@{
            identity = $null
            diagnostics = New-TraceableLocalReleaseDiagnostic `
                -Status "failed" `
                -Reason "not-a-git-worktree" `
                -Message ("Workspace is not inside a Git worktree: " + $WorkspaceRootValue) `
                -Steps @($steps)
        }
    }

    $commitCapture = Invoke-NPDevCommandCapture -WorkingDirectory $WorkspaceRootValue -Executable "git" -Arguments @("rev-parse", "HEAD")
    $commitSha = Get-TraceableGitFirstOutputLine $commitCapture
    [void]$steps.Add([pscustomobject]@{
            command = "git rev-parse HEAD"
            exitCode = [int]$commitCapture.ExitCode
            firstOutputLine = $commitSha
        })
    if ($commitCapture.ExitCode -ne 0 -or [string]::IsNullOrWhiteSpace($commitSha)) {
        return [pscustomobject]@{
            identity = $null
            diagnostics = New-TraceableLocalReleaseDiagnostic `
                -Status "failed" `
                -Reason "commit-unavailable" `
                -Message "Git worktree was detected, but HEAD commit could not be resolved." `
                -Steps @($steps)
        }
    }

    $branchCapture = Invoke-NPDevCommandCapture -WorkingDirectory $WorkspaceRootValue -Executable "git" -Arguments @("rev-parse", "--abbrev-ref", "HEAD")
    $branch = Get-TraceableGitFirstOutputLine $branchCapture
    [void]$steps.Add([pscustomobject]@{
            command = "git rev-parse --abbrev-ref HEAD"
            exitCode = [int]$branchCapture.ExitCode
            firstOutputLine = $branch
        })
    if ($branchCapture.ExitCode -ne 0 -or [string]::IsNullOrWhiteSpace($branch)) {
        return [pscustomobject]@{
            identity = $null
            diagnostics = New-TraceableLocalReleaseDiagnostic `
                -Status "failed" `
                -Reason "branch-unavailable" `
                -Message "Git worktree was detected, but the current branch could not be resolved." `
                -Steps @($steps)
        }
    }

    $statusCapture = Invoke-NPDevCommandCapture -WorkingDirectory $WorkspaceRootValue -Executable "git" -Arguments @("status", "--porcelain")
    [void]$steps.Add([pscustomobject]@{
            command = "git status --porcelain"
            exitCode = [int]$statusCapture.ExitCode
            firstOutputLine = Get-TraceableGitFirstOutputLine $statusCapture
        })
    if ($statusCapture.ExitCode -ne 0) {
        return [pscustomobject]@{
            identity = $null
            diagnostics = New-TraceableLocalReleaseDiagnostic `
                -Status "failed" `
                -Reason "dirty-status-unavailable" `
                -Message "Git worktree was detected, but dirty status could not be resolved." `
                -Steps @($steps)
        }
    }

    return [pscustomobject]@{
        identity = [pscustomobject]@{
            commitSha = $commitSha
            branch = $branch
            dirty = (@($statusCapture.Output | Where-Object { -not [string]::IsNullOrWhiteSpace([string]$_) }).Count -gt 0)
            provider = "local-git"
        }
        diagnostics = New-TraceableLocalReleaseDiagnostic `
            -Status "passed" `
            -Reason "local-git-discovered" `
            -Message "Local Git commit identity was discovered." `
            -Steps @($steps)
    }
}

function Get-TraceableReleaseCommandText(
    [string]$WorkspaceRootValue
) {
    return "& 'C:\Program Files (x86)\PowerShell\7\pwsh.exe' -NoProfile -ExecutionPolicy Bypass -File '" + `
        (Resolve-NPDevWorkspacePath $WorkspaceRootValue "scripts\quality\run-traceable-local-release.ps1") + `
        "' -WorkspaceRoot '" + $WorkspaceRootValue + "'"
}

$explicitCommitProvided = -not [string]::IsNullOrWhiteSpace($SourceCommitSha)
$explicitBranchProvided = -not [string]::IsNullOrWhiteSpace($SourceBranch)
$traceabilityDiagnostics = $null

if ($explicitCommitProvided -or $explicitBranchProvided) {
    if (-not $explicitCommitProvided) {
        throw "SourceCommitSha is required when explicit source metadata is supplied."
    }
    if (-not $explicitBranchProvided) {
        throw "SourceBranch is required when explicit source metadata is supplied."
    }
    if ([string]::IsNullOrWhiteSpace($SourceProvider)) {
        $SourceProvider = "explicit"
    }
    if ($null -eq $SourceDirty) {
        $SourceDirty = $false
    }
    $traceabilityDiagnostics = New-TraceableLocalReleaseDiagnostic `
        -Status "passed" `
        -Reason "explicit-source-metadata" `
        -Message "Explicit source metadata was supplied by the caller." `
        -Steps @()
}
else {
    $localGit = Get-LocalGitTraceability -WorkspaceRootValue $WorkspaceRoot
    $traceabilityDiagnostics = $localGit.diagnostics
    if ($null -ne $localGit.identity) {
        $SourceCommitSha = [string]$localGit.identity.commitSha
        $SourceBranch = [string]$localGit.identity.branch
        $SourceDirty = [bool]$localGit.identity.dirty
        $SourceProvider = [string]$localGit.identity.provider
    }
    elseif (-not $AllowDiagnosticFallback) {
        $wrapperReport = [pscustomobject]@{
            generatedAt = (Get-Date).ToString("o")
            runId = $RunId
            scriptPath = Get-NPDevWorkspaceRelativePath $WorkspaceRoot $PSCommandPath
            workspaceRoot = $WorkspaceRoot
            overallStatus = "failed"
            summary = [pscustomobject]@{
                betaReleaseGateStatus = $null
                traceabilitySatisfied = $false
                provenanceGrade = "local-unanchored"
            }
            betaReleaseGate = [pscustomobject]@{
                scriptPath = Get-NPDevWorkspaceRelativePath $WorkspaceRoot $BetaGateScriptPath
                reportPath = $null
                evidenceRoot = $null
                overallStatus = $null
                releaseRunId = $null
            }
            source = [pscustomobject]@{
                commitSha = $null
                branch = $null
                dirty = $null
                provider = "unavailable"
                runId = $SourceRunId
                runAttempt = $SourceRunAttempt
                workflow = $SourceWorkflow
                sampleId = $SampleId
            }
            traceabilityDiagnostics = $traceabilityDiagnostics
            copyableCommand = Get-TraceableReleaseCommandText -WorkspaceRootValue $WorkspaceRoot
            error = "Traceable local release requires Git metadata. Discovery failed with reason '" + [string]$traceabilityDiagnostics.reason + "'."
        }
        Write-NPDevJsonFile $WrapperReportPath $wrapperReport
        throw ($wrapperReport.error + " Run from a Git worktree, pass explicit source metadata, or use -AllowDiagnosticFallback for diagnostic-only evidence.")
    }
}

$betaGateParams = @{
    WorkspaceRoot = $WorkspaceRoot
    SampleId = $SampleId
}
if (-not [string]::IsNullOrWhiteSpace($SourceCommitSha)) {
    $betaGateParams["SourceCommitSha"] = $SourceCommitSha
    $betaGateParams["SourceBranch"] = $SourceBranch
    $betaGateParams["SourceDirty"] = $SourceDirty
    $betaGateParams["SourceProvider"] = $SourceProvider
    $betaGateParams["SourceRunId"] = $SourceRunId
    $betaGateParams["SourceRunAttempt"] = $SourceRunAttempt
    $betaGateParams["SourceWorkflow"] = $SourceWorkflow
}
if (-not [string]::IsNullOrWhiteSpace($ReportPath)) {
    $betaGateParams["ReportPath"] = Normalize-NPDevPath $ReportPath
}
if (-not [string]::IsNullOrWhiteSpace($EvidenceRoot)) {
    $betaGateParams["EvidenceRoot"] = Normalize-NPDevPath $EvidenceRoot
}
if ($PreserveExistingReports) {
    $betaGateParams["PreserveExistingReports"] = $true
}

if ([string]::IsNullOrWhiteSpace($SourceCommitSha)) {
    Write-NPDevWarn "Running traceable local release wrapper in diagnostic fallback mode; source commit identity is unavailable."
}
else {
    Write-NPDevInfo ("Running traceable local release with source commit " + $SourceCommitSha + " on branch " + $SourceBranch)
}

$aggregateReport = $null
$wrapperStatus = "passed"
$wrapperError = $null
try {
    & $BetaGateScriptPath @betaGateParams
}
catch {
    $wrapperStatus = "failed"
    $wrapperError = $_.Exception.Message
}

$resolvedReportPath = if ($betaGateParams.ContainsKey("ReportPath")) {
    [string]$betaGateParams["ReportPath"]
}
else {
    Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\reports\out\beta-release-gate-report.json"
}
if (Test-Path -LiteralPath $resolvedReportPath -PathType Leaf) {
    $aggregateReport = Get-Content -LiteralPath $resolvedReportPath -Raw | ConvertFrom-Json
    if ($wrapperStatus -ne "failed") {
        $wrapperStatus = [string]$aggregateReport.overallStatus
    }
}

$wrapperReport = [pscustomobject]@{
    generatedAt = (Get-Date).ToString("o")
    runId = $RunId
    scriptPath = Get-NPDevWorkspaceRelativePath $WorkspaceRoot $PSCommandPath
    workspaceRoot = $WorkspaceRoot
    overallStatus = $wrapperStatus
    summary = [pscustomobject]@{
        betaReleaseGateStatus = if ($null -eq $aggregateReport) { $null } else { [string]$aggregateReport.overallStatus }
        traceabilitySatisfied = if ($null -eq $aggregateReport) { $null } else { [bool]$aggregateReport.traceabilitySatisfied }
        provenanceGrade = if ($null -eq $aggregateReport) { $null } else { [string]$aggregateReport.provenanceGrade }
        officialReleaseEligible = if ($null -eq $aggregateReport -or -not ($aggregateReport.PSObject.Properties.Name -contains "officialReleaseEligible")) { $null } else { [bool]$aggregateReport.officialReleaseEligible }
    }
    betaReleaseGate = [pscustomobject]@{
        scriptPath = Get-NPDevWorkspaceRelativePath $WorkspaceRoot $BetaGateScriptPath
        reportPath = Get-NPDevWorkspaceRelativePath $WorkspaceRoot $resolvedReportPath
        evidenceRoot = if (-not [string]::IsNullOrWhiteSpace($EvidenceRoot)) { $EvidenceRoot } elseif ($null -eq $aggregateReport) { $null } else { [string]$aggregateReport.evidenceRoot }
        overallStatus = if ($null -eq $aggregateReport) { $null } else { [string]$aggregateReport.overallStatus }
        releaseRunId = if ($null -eq $aggregateReport) { $null } else { [string]$aggregateReport.releaseRunId }
    }
    source = [pscustomobject]@{
        commitSha = if ([string]::IsNullOrWhiteSpace($SourceCommitSha)) { $null } else { $SourceCommitSha }
        branch = if ([string]::IsNullOrWhiteSpace($SourceBranch)) { $null } else { $SourceBranch }
        dirty = $SourceDirty
        provider = if ([string]::IsNullOrWhiteSpace($SourceCommitSha)) { "unavailable" } else { $SourceProvider }
        runId = $SourceRunId
        runAttempt = $SourceRunAttempt
        workflow = $SourceWorkflow
        sampleId = $SampleId
    }
    traceabilityDiagnostics = $traceabilityDiagnostics
    copyableCommand = Get-TraceableReleaseCommandText -WorkspaceRootValue $WorkspaceRoot
    error = $wrapperError
}
Write-NPDevJsonFile $WrapperReportPath $wrapperReport

if ($wrapperStatus -eq "failed") {
    if (-not [string]::IsNullOrWhiteSpace($wrapperError)) {
        throw $wrapperError
    }

    throw "Traceable local release failed."
}

if ($PassThru) {
    if ($null -ne $aggregateReport) {
        return $aggregateReport
    }
}
