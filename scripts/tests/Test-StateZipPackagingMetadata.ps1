Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

. (Join-Path $PSScriptRoot "..\npdev-common.ps1")
. (Join-Path $PSScriptRoot "..\statezip-common.ps1")

function Get-ZipEntryTextForTest {
    param(
        [string]$ZipPath,
        [string]$EntryPath
    )

    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $archive = [System.IO.Compression.ZipFile]::OpenRead($ZipPath)
    try {
        $entry = $archive.GetEntry($EntryPath.Replace("\", "/"))
        if ($null -eq $entry) {
            return $null
        }

        $reader = New-Object System.IO.StreamReader($entry.Open())
        try {
            return $reader.ReadToEnd()
        }
        finally {
            $reader.Dispose()
        }
    }
    finally {
        $archive.Dispose()
    }
}

$workspaceRoot = Get-NPDevWorkspaceRoot $PSScriptRoot
$stateZipScript = Resolve-NPDevWorkspacePath $workspaceRoot "scripts\statezip-npdev-general.ps1"
$aggregateReportPath = Resolve-NPDevWorkspacePath $workspaceRoot "scripts\reports\out\beta-release-gate-report.json"
$aggregateReport = Get-Content -LiteralPath $aggregateReportPath -Raw | ConvertFrom-Json
$stamp = "TEST_BUCKET1_PACKAGING"

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
    & $stateZipScript -WorkspaceRoot $workspaceRoot -ReleaseReady -ExistingEvidenceRoot last -Stamp $stamp -Quiet | Out-Null
    $zipPath = Join-Path (Get-DefaultStateZipOutDir -WorkspaceRoot $workspaceRoot) ("NPDev_General_State_ALL_" + $stamp + ".zip")
    Assert-True (Test-Path -LiteralPath $zipPath -PathType Leaf) "Expected statezip-npdev-general.ps1 -ReleaseReady to produce the release-ready ALL zip."

    $manifestText = Get-ZipEntryTextForTest -ZipPath $zipPath -EntryPath "state-manifest.txt"
    $summaryText = Get-ZipEntryTextForTest -ZipPath $zipPath -EntryPath "release-ready-summary.json"
    Assert-True (-not [string]::IsNullOrWhiteSpace($manifestText)) "Expected release-ready zip to contain state-manifest.txt."
    Assert-True (-not [string]::IsNullOrWhiteSpace($summaryText)) "Expected release-ready zip to contain release-ready-summary.json."

    $requiredManifestPatterns = @(
        "PackagingMode=",
        "GeneratedAt=",
        "AggregateStatus=",
        "ReleaseRunId=",
        "ReleaseEvidenceStatus=",
        "ProvenanceGrade=",
        "TraceabilitySatisfied=",
        "ReleaseReady=",
        "OfficialReleaseEligible=",
        "CommitSha=",
        "Branch=",
        "SourceDirty=",
        "SourceProvider="
    )
    foreach ($pattern in $requiredManifestPatterns) {
        Assert-True ($manifestText -match [regex]::Escape($pattern)) ("Expected state-manifest.txt to include " + $pattern)
    }

    $summary = $summaryText | ConvertFrom-Json
    $expectedPackagingMode = if ([bool]$aggregateReport.officialReleaseEligible) { "RELEASE_READY" } else { "DIAGNOSTIC" }
    Assert-True ($summary.packagingMode -eq $expectedPackagingMode) "Expected release-ready summary packagingMode to match official release eligibility."
    Assert-True ([bool]$summary.officialReleaseEligible -eq [bool]$aggregateReport.officialReleaseEligible) "Expected release-ready summary officialReleaseEligible to match the aggregate beta release report."
    Assert-True ($summary.aggregateStatus -eq $aggregateReport.overallStatus) "Expected release-ready summary aggregateStatus to match the aggregate beta release report."
    Assert-True ($summary.releaseRunId -eq $aggregateReport.releaseRunId) "Expected release-ready summary releaseRunId to match the aggregate beta release report."
    Assert-True ($summary.provenanceGrade -eq $aggregateReport.provenanceGrade) "Expected release-ready summary provenanceGrade to match the aggregate beta release report."
    Assert-True ([bool]$summary.traceabilitySatisfied -eq [bool]$aggregateReport.traceabilitySatisfied) "Expected release-ready summary traceabilitySatisfied to match the aggregate beta release report."
}
catch {
    [void]$failures.Add($_.Exception.Message)
}

if ($failures.Count -eq 0) {
    Write-NPDevOk "State zip packaging metadata tests passed."
    exit 0
}

foreach ($failure in $failures) {
    Write-NPDevWarn $failure
}
throw "State zip packaging metadata tests failed."
