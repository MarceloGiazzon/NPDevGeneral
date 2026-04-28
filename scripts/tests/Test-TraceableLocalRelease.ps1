Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

. (Join-Path $PSScriptRoot "..\npdev-common.ps1")

$workspaceRoot = Get-NPDevWorkspaceRoot $PSScriptRoot
$wrapperPath = Resolve-NPDevWorkspacePath $workspaceRoot "scripts\quality\run-traceable-local-release.ps1"
$tempRoot = Join-Path $env:TEMP "npdev-traceable-local-release-test"
$stubPath = Join-Path $tempRoot "stub-beta-gate.ps1"
$capturePath = Join-Path $tempRoot "captured-params.json"
$reportPath = Join-Path $tempRoot "beta-release-gate-report.json"
$wrapperReportPath = Join-Path $tempRoot "traceable-local-release-report.json"
$evidenceRoot = Join-Path $tempRoot "evidence"

if (Test-Path -LiteralPath $tempRoot) {
    Remove-Item -LiteralPath $tempRoot -Recurse -Force
}
New-Item -ItemType Directory -Force -Path $tempRoot | Out-Null
$env:NPDEV_TRACEABLE_LOCAL_RELEASE_CAPTURE = $capturePath

$stubScript = @'
param(
    [string]$WorkspaceRoot = "",
    [string]$SampleId = "",
    [string]$ReportPath = "",
    [string]$EvidenceRoot = "",
    [switch]$PreserveExistingReports,
    [string]$SourceCommitSha = "",
    [string]$SourceBranch = "",
    [AllowNull()][object]$SourceDirty = $null,
    [string]$SourceProvider = "",
    [string]$SourceRunId = "",
    [string]$SourceRunAttempt = "",
    [string]$SourceWorkflow = ""
)

$capture = [pscustomobject]@{
    WorkspaceRoot = $WorkspaceRoot
    SampleId = $SampleId
    ReportPath = $ReportPath
    EvidenceRoot = $EvidenceRoot
    PreserveExistingReports = [bool]$PreserveExistingReports
    SourceCommitSha = $SourceCommitSha
    SourceBranch = $SourceBranch
    SourceDirty = $SourceDirty
    SourceProvider = $SourceProvider
    SourceRunId = $SourceRunId
    SourceRunAttempt = $SourceRunAttempt
    SourceWorkflow = $SourceWorkflow
}
$capture | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath $env:NPDEV_TRACEABLE_LOCAL_RELEASE_CAPTURE -Encoding UTF8

$report = [pscustomobject]@{
    generatedAt = (Get-Date).ToString("o")
    runId = "traceable-local-release-test"
    releaseRunId = "traceable-local-release-test"
    workspaceRoot = $WorkspaceRoot
    overallStatus = "passed"
    evidenceRoot = $EvidenceRoot
    provenanceGrade = "git-traceable"
    traceabilitySatisfied = $true
    commitIdentity = [pscustomobject]@{
        available = $true
        source = $SourceProvider
        commitSha = $SourceCommitSha
        branch = $SourceBranch
        dirty = $SourceDirty
        runId = $SourceRunId
        runAttempt = $SourceRunAttempt
        workflow = $SourceWorkflow
    }
    authoritativeDecision = [pscustomobject]@{
        sourceOfTruth = "scripts\\reports\\out\\beta-release-gate-report.json"
        releaseRunId = "traceable-local-release-test"
        rule = "stub"
        staleReportPolicy = "purged-before-execution"
    }
    steps = @()
    copiedEvidence = @()
}
$report | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath $ReportPath -Encoding UTF8
'@
Set-Content -LiteralPath $stubPath -Value $stubScript -Encoding UTF8

$failures = [System.Collections.Generic.List[string]]::new()
function Assert-True {
    param(
        [bool]$Condition,
        [string]$Message
    )

    if (-not $Condition) {
        [void]$failures.Add($Message)
    }
}

try {
    try {
        & $wrapperPath `
            -WorkspaceRoot $workspaceRoot `
            -WrapperReportPath $wrapperReportPath `
            -BetaGateScriptPath $stubPath | Out-Null
        [void]$failures.Add("Expected run-traceable-local-release.ps1 to fail clearly outside a Git worktree when no explicit source metadata is supplied.")
    }
    catch {
        Assert-True ($_.Exception.Message -match "requires Git metadata") "Expected an explicit traceability discovery error."
        $failureReport = Get-Content -LiteralPath $wrapperReportPath -Raw | ConvertFrom-Json
        Assert-True ([string]$failureReport.overallStatus -eq "failed") "Expected failed discovery to write a failed wrapper report."
        Assert-True ([string]$failureReport.traceabilityDiagnostics.reason -in @("not-a-git-worktree", "git-not-found")) "Expected failed discovery to explain why Git metadata is unavailable."
    }

    $result = & $wrapperPath `
        -WorkspaceRoot $workspaceRoot `
        -RunId "traceable-local-release-wrapper" `
        -SourceCommitSha "abc123def456" `
        -SourceBranch "release/main" `
        -SourceDirty $false `
        -SourceProvider "manual-traceable" `
        -SourceRunId "local-run-7" `
        -SourceRunAttempt "3" `
        -SourceWorkflow "manual-release" `
        -SampleId "simple-contact-intake" `
        -ReportPath $reportPath `
        -WrapperReportPath $wrapperReportPath `
        -EvidenceRoot $evidenceRoot `
        -PreserveExistingReports `
        -BetaGateScriptPath $stubPath `
        -PassThru

    $captured = Get-Content -LiteralPath $capturePath -Raw | ConvertFrom-Json
    $wrapperReport = Get-Content -LiteralPath $wrapperReportPath -Raw | ConvertFrom-Json
    $expectedWrapperReportPath = Get-NPDevWorkspaceRelativePath $workspaceRoot $reportPath
    Assert-True ($captured.SourceCommitSha -eq "abc123def456") "Expected wrapper to forward SourceCommitSha to the beta gate."
    Assert-True ($captured.SourceBranch -eq "release/main") "Expected wrapper to forward SourceBranch to the beta gate."
    Assert-True ($captured.SourceProvider -eq "manual-traceable") "Expected wrapper to forward SourceProvider to the beta gate."
    Assert-True ($captured.SourceRunId -eq "local-run-7") "Expected wrapper to forward SourceRunId to the beta gate."
    Assert-True ($captured.SourceRunAttempt -eq "3") "Expected wrapper to forward SourceRunAttempt to the beta gate."
    Assert-True ($captured.SourceWorkflow -eq "manual-release") "Expected wrapper to forward SourceWorkflow to the beta gate."
    Assert-True ([bool]$captured.PreserveExistingReports) "Expected wrapper to forward PreserveExistingReports to the beta gate."
    Assert-True ($captured.ReportPath -eq $reportPath) "Expected wrapper to forward ReportPath to the beta gate."
    Assert-True ($captured.EvidenceRoot -eq $evidenceRoot) "Expected wrapper to forward EvidenceRoot to the beta gate."
    Assert-True ([string]$wrapperReport.runId -eq "traceable-local-release-wrapper") "Expected the wrapper report to keep the caller-provided runId."
    Assert-True ([string]$wrapperReport.overallStatus -eq "passed") "Expected the wrapper report to mirror the aggregate beta gate status."
    Assert-True ([string]$wrapperReport.betaReleaseGate.reportPath -eq $expectedWrapperReportPath) "Expected the wrapper report to point at the forwarded aggregate beta report path."
    Assert-True ($result.provenanceGrade -eq "git-traceable") "Expected PassThru to return the generated aggregate report."
    Assert-True ([bool]$result.traceabilitySatisfied) "Expected PassThru aggregate report to remain traceable."
}
catch {
    [void]$failures.Add($_.Exception.Message)
}
finally {
    Remove-Item Env:NPDEV_TRACEABLE_LOCAL_RELEASE_CAPTURE -ErrorAction SilentlyContinue
    if (Test-Path -LiteralPath $tempRoot) {
        Remove-Item -LiteralPath $tempRoot -Recurse -Force
    }
}

if ($failures.Count -eq 0) {
    Write-NPDevOk "Traceable local release wrapper tests passed."
    exit 0
}

foreach ($failure in $failures) {
    Write-NPDevWarn $failure
}
throw "Traceable local release wrapper tests failed."
