[CmdletBinding()]
param(
    [string]$WorkspaceRoot = "",
    [ValidateSet("beta-release", "runtimehost", "sample-matrix", "frontend", "frontend-audit", "editor", "hygiene", "ai-beta-matrix")]
    [string]$Gate,
    [string]$JobId = "",
    [string]$SampleId = "",
    [string]$SourceCommitSha = "",
    [string]$SourceBranch = "",
    [AllowNull()][object]$SourceDirty = $null,
    [string]$SourceProvider = "",
    [string]$SourceRunId = "",
    [string]$SourceRunAttempt = "",
    [string]$SourceWorkflow = ""
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

. (Join-Path $PSScriptRoot "background-gate-common.ps1")

if ([string]::IsNullOrWhiteSpace($WorkspaceRoot)) {
    $WorkspaceRoot = Get-NPDevWorkspaceRoot $PSScriptRoot
}
$WorkspaceRoot = Normalize-NPDevPath $WorkspaceRoot
if ([string]::IsNullOrWhiteSpace($SampleId)) {
    $SampleId = Get-NPDevDefaultSampleId $WorkspaceRoot
}

if ([string]::IsNullOrWhiteSpace($JobId)) {
    $JobId = New-NPDevRunId ($Gate + "-background")
}

$definition = Resolve-NPDevBackgroundGateDefinition `
    -WorkspaceRoot $WorkspaceRoot `
    -Gate $Gate `
    -SampleId $SampleId `
    -SourceCommitSha $SourceCommitSha `
    -SourceBranch $SourceBranch `
    -SourceDirty $SourceDirty `
    -SourceProvider $SourceProvider `
    -SourceRunId $SourceRunId `
    -SourceRunAttempt $SourceRunAttempt `
    -SourceWorkflow $SourceWorkflow

$metadataPath = Get-NPDevBackgroundGateMetadataPath -WorkspaceRoot $WorkspaceRoot -JobId $JobId
$stdoutLogPath = Get-NPDevBackgroundGateLogPath -WorkspaceRoot $WorkspaceRoot -JobId $JobId -StreamName "stdout"
$stderrLogPath = Get-NPDevBackgroundGateLogPath -WorkspaceRoot $WorkspaceRoot -JobId $JobId -StreamName "stderr"
$runnerScriptPath = Join-Path $PSScriptRoot "invoke-background-gate-runner.ps1"
$pwshPath = (Get-Command "pwsh" -ErrorAction Stop).Source
$createdAt = Get-Date

$argumentList = [System.Collections.Generic.List[string]]::new()
foreach ($value in @(
        "-NoLogo",
        "-NoProfile",
        "-NonInteractive",
        "-File", $runnerScriptPath,
        "-WorkspaceRoot", $WorkspaceRoot,
        "-Gate", $Gate,
        "-MetadataPath", $metadataPath,
        "-SampleId", $SampleId
    )) {
    [void]$argumentList.Add([string]$value)
}

if (-not [string]::IsNullOrWhiteSpace($SourceCommitSha)) {
    [void]$argumentList.Add("-SourceCommitSha")
    [void]$argumentList.Add($SourceCommitSha)
}
if (-not [string]::IsNullOrWhiteSpace($SourceBranch)) {
    [void]$argumentList.Add("-SourceBranch")
    [void]$argumentList.Add($SourceBranch)
}
if ($null -ne $SourceDirty) {
    [void]$argumentList.Add("-SourceDirty")
    [void]$argumentList.Add(([string]$SourceDirty))
}
if (-not [string]::IsNullOrWhiteSpace($SourceProvider)) {
    [void]$argumentList.Add("-SourceProvider")
    [void]$argumentList.Add($SourceProvider)
}
if (-not [string]::IsNullOrWhiteSpace($SourceRunId)) {
    [void]$argumentList.Add("-SourceRunId")
    [void]$argumentList.Add($SourceRunId)
}
if (-not [string]::IsNullOrWhiteSpace($SourceRunAttempt)) {
    [void]$argumentList.Add("-SourceRunAttempt")
    [void]$argumentList.Add($SourceRunAttempt)
}
if (-not [string]::IsNullOrWhiteSpace($SourceWorkflow)) {
    [void]$argumentList.Add("-SourceWorkflow")
    [void]$argumentList.Add($SourceWorkflow)
}

$commandDisplay = $pwshPath + " " + (($argumentList | ForEach-Object {
            if ($_ -match "\s") { '"' + $_ + '"' } else { $_ }
        }) -join " ")

Write-NPDevBackgroundGateMetadata -MetadataPath $metadataPath -Updates @{
    createdAt = $createdAt.ToString("o")
    status = "starting"
    workspaceRoot = $WorkspaceRoot
    gate = $Gate
    jobId = $JobId
    scriptPath = $definition.scriptPathRelative
    expectedReportPath = $definition.expectedReportPathRelative
    sampleId = $SampleId
    stdoutLogPath = Get-NPDevWorkspaceRelativePath $WorkspaceRoot $stdoutLogPath
    stderrLogPath = Get-NPDevWorkspaceRelativePath $WorkspaceRoot $stderrLogPath
    command = $commandDisplay
    traceabilityOverrides = [pscustomobject]@{
        commitSha = if ([string]::IsNullOrWhiteSpace($SourceCommitSha)) { $null } else { $SourceCommitSha }
        branch = if ([string]::IsNullOrWhiteSpace($SourceBranch)) { $null } else { $SourceBranch }
        dirty = $SourceDirty
        provider = if ([string]::IsNullOrWhiteSpace($SourceProvider)) { $null } else { $SourceProvider }
        runId = if ([string]::IsNullOrWhiteSpace($SourceRunId)) { $null } else { $SourceRunId }
        runAttempt = if ([string]::IsNullOrWhiteSpace($SourceRunAttempt)) { $null } else { $SourceRunAttempt }
        workflow = if ([string]::IsNullOrWhiteSpace($SourceWorkflow)) { $null } else { $SourceWorkflow }
    }
} | Out-Null

$process = Start-Process `
    -FilePath $pwshPath `
    -ArgumentList $argumentList.ToArray() `
    -WorkingDirectory $WorkspaceRoot `
    -RedirectStandardOutput $stdoutLogPath `
    -RedirectStandardError $stderrLogPath `
    -PassThru

$launchedAt = Get-Date
$metadata = Write-NPDevBackgroundGateMetadata -MetadataPath $metadataPath -Updates @{
    status = "running"
    processId = $process.Id
    launchedAt = $launchedAt.ToString("o")
}

Write-NPDevInfo ("Started background gate '" + $Gate + "' with PID " + $process.Id + ".")
Write-NPDevInfo ("Metadata: " + $metadataPath)
Write-NPDevInfo ("Stdout:   " + $stdoutLogPath)
Write-NPDevInfo ("Stderr:   " + $stderrLogPath)
if (-not [string]::IsNullOrWhiteSpace([string]$definition.expectedReportPath)) {
    Write-NPDevInfo ("Report:   " + $definition.expectedReportPath)
}

return [pscustomobject]@{
    jobId = $JobId
    gate = $Gate
    processId = $process.Id
    metadataPath = $metadataPath
    stdoutLogPath = $stdoutLogPath
    stderrLogPath = $stderrLogPath
    expectedReportPath = $definition.expectedReportPath
    status = [string]$metadata.status
}
