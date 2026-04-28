[CmdletBinding()]
param(
    [string]$WorkspaceRoot = 'D:\WorkSpace\NPDev_General',
    [Parameter(Mandatory = $true)]
    [string]$SourceCommitSha,
    [Parameter(Mandatory = $true)]
    [string]$SourceBranch,
    [bool]$SourceDirty = $false,
    [string]$SourceProvider = 'manual-traceable',
    [string]$SourceRunId = '',
    [string]$SourceRunAttempt = '',
    [string]$SourceWorkflow = 'manual-local-release',
    [string]$OutDir = 'D:\WorkSpace\NPDev_General__OutsideRepo\state-zips'
)

$ErrorActionPreference = 'Stop'

$traceableRelease = Join-Path $WorkspaceRoot 'scripts\quality\run-traceable-local-release.ps1'
$traceabilityTest = Join-Path $WorkspaceRoot 'scripts\tests\Test-ReleaseTraceability.ps1'
$statezip = Join-Path $WorkspaceRoot 'scripts\statezip-npdev-general.ps1'

foreach ($path in @($traceableRelease, $traceabilityTest, $statezip)) {
    if (-not (Test-Path -LiteralPath $path)) {
        throw "Required script not found: $path"
    }
}

& $traceableRelease `
    -WorkspaceRoot $WorkspaceRoot `
    -SourceCommitSha $SourceCommitSha `
    -SourceBranch $SourceBranch `
    -SourceDirty $SourceDirty `
    -SourceProvider $SourceProvider `
    -SourceRunId $SourceRunId `
    -SourceRunAttempt $SourceRunAttempt `
    -SourceWorkflow $SourceWorkflow

& $traceabilityTest `
    -WorkspaceRoot $WorkspaceRoot `
    -RequireOfficialEligibility

& $statezip `
    -WorkspaceRoot $WorkspaceRoot `
    -OutDir $OutDir `
    -ReleaseReady `
    -ExistingEvidenceRoot last

Write-Host 'OK    Explicit traceable release and state zip completed.'
