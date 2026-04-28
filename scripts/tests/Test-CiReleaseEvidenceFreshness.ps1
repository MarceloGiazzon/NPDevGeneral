[CmdletBinding()]
param(
    [string]$WorkspaceRoot = "",
    [string]$StateZipOut = "",
    [string]$ReportPath = ""
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

. (Join-Path $PSScriptRoot "..\npdev-common.ps1")
. (Join-Path $PSScriptRoot "..\statezip-common.ps1")

if ([string]::IsNullOrWhiteSpace($WorkspaceRoot)) {
    $WorkspaceRoot = Get-NPDevWorkspaceRoot $PSScriptRoot
}
$WorkspaceRoot = Normalize-NPDevPath $WorkspaceRoot

if ([string]::IsNullOrWhiteSpace($StateZipOut)) {
    $StateZipOut = Get-DefaultStateZipOutDir -WorkspaceRoot $WorkspaceRoot
}
else {
    $StateZipOut = Normalize-NPDevPath $StateZipOut
}

if ([string]::IsNullOrWhiteSpace($ReportPath)) {
    $ReportPath = Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\reports\out\ci-release-evidence-freshness-report.json"
}
else {
    $ReportPath = Normalize-NPDevPath $ReportPath
}

$failures = [System.Collections.Generic.List[string]]::new()

function Add-FreshnessFailure {
    param([string]$Message)

    [void]$failures.Add($Message)
}

function Test-JsonPropertyPresent {
    param(
        [AllowNull()][object]$ObjectValue,
        [Parameter(Mandatory = $true)]
        [string]$PropertyName
    )

    return ($null -ne $ObjectValue -and $ObjectValue.PSObject.Properties.Name -contains $PropertyName)
}

function Get-JsonPropertyValue {
    param(
        [AllowNull()][object]$ObjectValue,
        [Parameter(Mandatory = $true)]
        [string]$PropertyName
    )

    if (Test-JsonPropertyPresent -ObjectValue $ObjectValue -PropertyName $PropertyName) {
        return $ObjectValue.PSObject.Properties[$PropertyName].Value
    }

    return $null
}

function Read-JsonFileForFreshness {
    param(
        [Parameter(Mandatory = $true)]
        [string]$PathValue,
        [Parameter(Mandatory = $true)]
        [string]$Label
    )

    if (-not (Test-Path -LiteralPath $PathValue -PathType Leaf)) {
        Add-FreshnessFailure ($Label + " is missing: " + $PathValue)
        return $null
    }

    try {
        return Get-Content -LiteralPath $PathValue -Raw | ConvertFrom-Json
    }
    catch {
        Add-FreshnessFailure ($Label + " could not be parsed: " + $_.Exception.Message)
        return $null
    }
}

function Get-StateZipStamp {
    param([System.IO.FileInfo]$File)

    if ($File.BaseName -match '_(\d{8})_(\d{6})$') {
        try {
            return [datetime]::ParseExact(
                ($matches[1] + "_" + $matches[2]),
                "yyyyMMdd_HHmmss",
                [Globalization.CultureInfo]::InvariantCulture
            )
        }
        catch {
            return [datetime]::MinValue
        }
    }

    return [datetime]::MinValue
}

function Resolve-LatestAllStateZip {
    param([string]$ZipRoot)

    if (-not (Test-Path -LiteralPath $ZipRoot -PathType Container)) {
        Add-FreshnessFailure ("State zip output directory is missing: " + $ZipRoot)
        return $null
    }

    $candidates = @(
        Get-ChildItem -LiteralPath $ZipRoot -File -Filter "NPDev_General_State_ALL_*.zip" |
        Where-Object { $_.Name -notmatch "TEST_" } |
        Sort-Object @{ Expression = { Get-StateZipStamp $_ }; Descending = $true }, LastWriteTime -Descending
    )

    if ($candidates.Count -eq 0) {
        Add-FreshnessFailure ("No ALL state zip was found under: " + $ZipRoot)
        return $null
    }

    return $candidates[0].FullName
}

function Get-ZipEntryText {
    param(
        [Parameter(Mandatory = $true)]
        [string]$ZipPath,
        [Parameter(Mandatory = $true)]
        [string]$EntryPath
    )

    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $archive = [System.IO.Compression.ZipFile]::OpenRead($ZipPath)
    try {
        $entry = $archive.GetEntry($EntryPath.Replace("\", "/"))
        if ($null -eq $entry) {
            return $null
        }

        $reader = [System.IO.StreamReader]::new($entry.Open())
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

function Get-ZipEntryJson {
    param(
        [Parameter(Mandatory = $true)]
        [string]$ZipPath,
        [Parameter(Mandatory = $true)]
        [string]$EntryPath,
        [Parameter(Mandatory = $true)]
        [string]$Label
    )

    $text = Get-ZipEntryText -ZipPath $ZipPath -EntryPath $EntryPath
    if ([string]::IsNullOrWhiteSpace($text)) {
        Add-FreshnessFailure ($Label + " is missing from state zip: " + $EntryPath)
        return $null
    }

    try {
        return $text | ConvertFrom-Json
    }
    catch {
        Add-FreshnessFailure ($Label + " could not be parsed from state zip: " + $_.Exception.Message)
        return $null
    }
}

function Get-PackagedEvidenceManifest {
    param(
        [Parameter(Mandatory = $true)]
        [string]$ZipPath
    )

    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $archive = [System.IO.Compression.ZipFile]::OpenRead($ZipPath)
    try {
        $manifestEntries = @(
            $archive.Entries |
            Where-Object { $_.FullName -match '^scripts/reports/releases/[^/]+/evidence-manifest\.json$' } |
            Sort-Object FullName -Descending
        )

        if ($manifestEntries.Count -eq 0) {
            Add-FreshnessFailure "No packaged release evidence manifest was found in the state zip."
            return $null
        }

        if ($manifestEntries.Count -gt 1) {
            Add-FreshnessFailure ("Expected one packaged release evidence manifest, found " + $manifestEntries.Count + ".")
            return $null
        }

        $reader = [System.IO.StreamReader]::new($manifestEntries[0].Open())
        try {
            $manifestText = $reader.ReadToEnd()
            return [pscustomobject]@{
                entryPath = [string]$manifestEntries[0].FullName
                runIdFromPath = [string]([regex]::Match($manifestEntries[0].FullName, '^scripts/reports/releases/([^/]+)/').Groups[1].Value)
                manifest = ($manifestText | ConvertFrom-Json)
            }
        }
        finally {
            $reader.Dispose()
        }
    }
    catch {
        Add-FreshnessFailure ("Packaged release evidence manifest could not be read: " + $_.Exception.Message)
        return $null
    }
    finally {
        $archive.Dispose()
    }
}

function Assert-TraceableReleaseFields {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Label,
        [AllowNull()][object]$Report
    )

    if ($null -eq $Report) {
        return
    }

    if (-not [bool](Get-JsonPropertyValue -ObjectValue $Report -PropertyName "officialReleaseEligible")) {
        Add-FreshnessFailure ($Label + " officialReleaseEligible must be true.")
    }

    if (-not [bool](Get-JsonPropertyValue -ObjectValue $Report -PropertyName "traceabilitySatisfied")) {
        Add-FreshnessFailure ($Label + " traceabilitySatisfied must be true.")
    }

    $provenanceGrade = [string](Get-JsonPropertyValue -ObjectValue $Report -PropertyName "provenanceGrade")
    if ($provenanceGrade -notin @("git-traceable", "ci-traceable")) {
        Add-FreshnessFailure ($Label + " provenanceGrade must be git-traceable or ci-traceable; found '" + $provenanceGrade + "'.")
    }

    $commitIdentity = Get-JsonPropertyValue -ObjectValue $Report -PropertyName "commitIdentity"
    if (-not [bool](Get-JsonPropertyValue -ObjectValue $commitIdentity -PropertyName "available")) {
        Add-FreshnessFailure ($Label + " commitIdentity.available must be true.")
    }
}

$betaReportPath = Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\reports\out\beta-release-gate-report.json"
$betaReport = Read-JsonFileForFreshness -PathValue $betaReportPath -Label "Beta release gate report"

if ($null -ne $betaReport -and [string]$betaReport.overallStatus -ne "passed") {
    Add-FreshnessFailure ("Beta release gate overallStatus must be passed; found '" + [string]$betaReport.overallStatus + "'.")
}
Assert-TraceableReleaseFields -Label "Beta release gate report" -Report $betaReport

$latestStateZip = Resolve-LatestAllStateZip -ZipRoot $StateZipOut
$releaseSummary = $null
$packagedEvidence = $null
if (-not [string]::IsNullOrWhiteSpace($latestStateZip)) {
    $releaseSummary = Get-ZipEntryJson -ZipPath $latestStateZip -EntryPath "release-ready-summary.json" -Label "Release-ready summary"
    $packagedEvidence = Get-PackagedEvidenceManifest -ZipPath $latestStateZip
}

Assert-TraceableReleaseFields -Label "Release-ready summary" -Report $releaseSummary

if ($null -ne $releaseSummary) {
    if (-not [bool](Get-JsonPropertyValue -ObjectValue $releaseSummary -PropertyName "releaseReady")) {
        Add-FreshnessFailure "Release-ready summary releaseReady must be true."
    }

    if ([string](Get-JsonPropertyValue -ObjectValue $releaseSummary -PropertyName "packagingMode") -ne "RELEASE_READY") {
        Add-FreshnessFailure ("Release-ready summary packagingMode must be RELEASE_READY; found '" + [string](Get-JsonPropertyValue -ObjectValue $releaseSummary -PropertyName "packagingMode") + "'.")
    }
}

if ($null -ne $betaReport -and $null -ne $releaseSummary) {
    $betaRunId = [string](Get-JsonPropertyValue -ObjectValue $betaReport -PropertyName "releaseRunId")
    $summaryRunId = [string](Get-JsonPropertyValue -ObjectValue $releaseSummary -PropertyName "releaseEvidenceRunId")
    if ([string]::IsNullOrWhiteSpace($summaryRunId)) {
        Add-FreshnessFailure "Release-ready summary releaseEvidenceRunId is missing."
    }
    elseif ($summaryRunId -ne $betaRunId) {
        Add-FreshnessFailure ("Release-ready summary releaseEvidenceRunId '" + $summaryRunId + "' does not match beta release runId '" + $betaRunId + "'.")
    }
}

if ($null -ne $packagedEvidence -and $null -ne $releaseSummary) {
    $summaryEvidenceRunId = [string](Get-JsonPropertyValue -ObjectValue $releaseSummary -PropertyName "releaseEvidenceRunId")
    $manifestRunId = [string](Get-JsonPropertyValue -ObjectValue $packagedEvidence.manifest -PropertyName "releaseRunId")

    if ($summaryEvidenceRunId -ne [string]$packagedEvidence.runIdFromPath) {
        Add-FreshnessFailure ("Release-ready summary releaseEvidenceRunId '" + $summaryEvidenceRunId + "' does not match packaged evidence folder '" + [string]$packagedEvidence.runIdFromPath + "'.")
    }

    if ($summaryEvidenceRunId -ne $manifestRunId) {
        Add-FreshnessFailure ("Release-ready summary releaseEvidenceRunId '" + $summaryEvidenceRunId + "' does not match packaged evidence manifest releaseRunId '" + $manifestRunId + "'.")
    }

    Assert-TraceableReleaseFields -Label "Packaged release evidence manifest" -Report $packagedEvidence.manifest
}

$checks = @(
    New-NPDevCheckResult "beta-release-report-present" $(if ($null -ne $betaReport) { "passed" } else { "failed" }) "Beta release gate report must exist."
    New-NPDevCheckResult "beta-release-passed" $(if ($null -ne $betaReport -and [string]$betaReport.overallStatus -eq "passed") { "passed" } else { "failed" }) "Beta release gate report must be passed."
    New-NPDevCheckResult "release-summary-present" $(if ($null -ne $releaseSummary) { "passed" } else { "failed" }) "Release-ready summary must be packaged in the latest ALL state zip."
    New-NPDevCheckResult "packaged-evidence-present" $(if ($null -ne $packagedEvidence) { "passed" } else { "failed" }) "Exactly one release evidence manifest must be packaged in the latest ALL state zip."
)

$overallStatus = if ($failures.Count -eq 0) { "passed" } else { "failed" }
$report = [pscustomobject]@{
    generatedAt = (Get-Date).ToString("o")
    scriptPath = Get-NPDevWorkspaceRelativePath $WorkspaceRoot $PSCommandPath
    workspaceRoot = $WorkspaceRoot
    stateZipOut = $StateZipOut
    latestStateZip = $latestStateZip
    overallStatus = $overallStatus
    releaseRunId = if ($null -eq $betaReport) { $null } else { [string]$betaReport.releaseRunId }
    packagedEvidenceManifest = if ($null -eq $packagedEvidence) { $null } else { [string]$packagedEvidence.entryPath }
    failures = @($failures)
    checks = $checks
}
Write-NPDevJsonFile $ReportPath $report

if ($overallStatus -eq "passed") {
    Write-NPDevOk ("CI release evidence freshness passed. State zip: " + $latestStateZip)
    return
}

foreach ($failure in $failures) {
    Write-NPDevWarn $failure
}
throw "CI release evidence freshness failed."
