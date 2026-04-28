[CmdletBinding()]
param(
    [string]$WorkspaceRoot = "",
    [string]$RunId = "",
    [string]$ReportPath = "",
    [string]$AggregateReportPath = "",
    [string]$StateZipPath = "",
    [switch]$PassThru
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

. (Join-Path $PSScriptRoot "prioritized-control-common.ps1")

function Resolve-LatestReleaseReadyStateZipPath {
    param(
        [string]$WorkspaceRoot,
        [string]$ExplicitStateZipPath
    )

    if (-not [string]::IsNullOrWhiteSpace($ExplicitStateZipPath)) {
        return Normalize-NPDevPath $ExplicitStateZipPath
    }

    $outDir = Get-DefaultStateZipOutDir -WorkspaceRoot $WorkspaceRoot
    if (-not (Test-Path -LiteralPath $outDir -PathType Container)) {
        return $null
    }

    $candidates = @(
        Get-ChildItem -LiteralPath $outDir -File -Filter "NPDev_General_State_ALL_*.zip" -ErrorAction SilentlyContinue |
        Sort-Object CreationTime, Name -Descending
    )
    foreach ($candidate in $candidates) {
        if ($null -ne (Get-ZipEntryText -ZipPath $candidate.FullName -EntryPath "release-ready-summary.json")) {
            return $candidate.FullName
        }
    }

    return $null
}

$WorkspaceRoot = Resolve-MaturityWorkspaceRoot -WorkspaceRoot $WorkspaceRoot -ScriptRoot $PSScriptRoot
$RunId = Resolve-NPDevRunId $RunId "b4-packaging-metadata-control"
$ReportPath = Resolve-PrioritizedControlReportPath -WorkspaceRoot $WorkspaceRoot -ReportPath $ReportPath -DefaultRelativePath "scripts\reports\out\prioritized-b4-packaging-metadata-report.json"
$AggregateReportPath = Resolve-Bucket1AggregateReportPath -WorkspaceRoot $WorkspaceRoot -AggregateReportPath $AggregateReportPath
$StateZipPath = Resolve-LatestReleaseReadyStateZipPath -WorkspaceRoot $WorkspaceRoot -ExplicitStateZipPath $StateZipPath

$checks = @()
$aggregateMetadata = Get-MaturityReportMetadata $AggregateReportPath
$aggregateExists = $aggregateMetadata.exists -and [string]::IsNullOrWhiteSpace([string]$aggregateMetadata.parseError)
$aggregateReport = if ($aggregateExists) { Read-MaturityJsonFile $AggregateReportPath } else { $null }

$zipExists = -not [string]::IsNullOrWhiteSpace($StateZipPath) -and (Test-Path -LiteralPath $StateZipPath -PathType Leaf)
$checks += New-MaturityCheck `
    -Name "release-ready-state-zip" `
    -Status $(if ($zipExists) { "passed" } else { "warning" }) `
    -Expectation "A release-ready ALL state zip should be available for packaging metadata validation." `
    -Summary $(if ($zipExists) { "A release-ready ALL state zip is available for inspection." } else { "No release-ready ALL state zip was found to inspect." }) `
    -Data @{
        stateZipPath = $StateZipPath
    }

$stateManifestText = if ($zipExists) { Get-ZipEntryText -ZipPath $StateZipPath -EntryPath "state-manifest.txt" } else { $null }
$releaseReadySummaryText = if ($zipExists) { Get-ZipEntryText -ZipPath $StateZipPath -EntryPath "release-ready-summary.json" } else { $null }
$aggregateEntryText = if ($zipExists) { Get-ZipEntryText -ZipPath $StateZipPath -EntryPath "scripts\reports\out\beta-release-gate-report.json" } else { $null }
$manifestMap = Convert-StateManifestTextToMap -ManifestText $stateManifestText
$requiredManifestKeys = @(
    "PackagingMode",
    "GeneratedAt",
    "AggregateStatus",
    "ReleaseRunId",
    "ReleaseEvidenceStatus",
    "ProvenanceGrade",
    "TraceabilitySatisfied",
    "CommitSha",
    "Branch"
)
$missingManifestKeys = @($requiredManifestKeys | Where-Object { -not $manifestMap.ContainsKey($_) })

$checks += New-MaturityCheck `
    -Name "zip-entry-presence" `
    -Status $(if ($zipExists -and -not [string]::IsNullOrWhiteSpace($stateManifestText) -and -not [string]::IsNullOrWhiteSpace($releaseReadySummaryText) -and -not [string]::IsNullOrWhiteSpace($aggregateEntryText)) { "passed" } else { $(if ($zipExists) { "failed" } else { "warning" }) }) `
    -Expectation "Release-ready zip must include state-manifest.txt, release-ready-summary.json, and the packaged aggregate report." `
    -Summary $(if ($zipExists -and -not [string]::IsNullOrWhiteSpace($stateManifestText) -and -not [string]::IsNullOrWhiteSpace($releaseReadySummaryText) -and -not [string]::IsNullOrWhiteSpace($aggregateEntryText)) { "Release-ready zip includes the required metadata entries." } elseif ($zipExists) { "Release-ready zip is missing one or more required metadata entries." } else { "Release-ready zip was not available for inspection." }) `
    -Data @{
        stateManifestPresent = -not [string]::IsNullOrWhiteSpace($stateManifestText)
        releaseReadySummaryPresent = -not [string]::IsNullOrWhiteSpace($releaseReadySummaryText)
        aggregateReportPresent = -not [string]::IsNullOrWhiteSpace($aggregateEntryText)
    }

$checks += New-MaturityCheck `
    -Name "state-manifest-schema" `
    -Status $(if ($zipExists -and @($missingManifestKeys).Count -eq 0) { "passed" } elseif ($zipExists) { "failed" } else { "warning" }) `
    -Expectation "state-manifest.txt must always include packaging mode, generatedAt, aggregate status, release run, release evidence status, provenance, traceability, commit SHA, and branch." `
    -Summary $(if ($zipExists -and @($missingManifestKeys).Count -eq 0) { "state-manifest.txt includes the required packaging metadata lines." } elseif ($zipExists) { "state-manifest.txt is missing one or more required packaging metadata lines." } else { "state-manifest.txt could not be inspected because no release-ready zip was available." }) `
    -Data @{
        missingKeys = $missingManifestKeys
        stateManifest = $manifestMap
    }

$releaseReadySummary = $null
$summaryParseError = $null
if (-not [string]::IsNullOrWhiteSpace($releaseReadySummaryText)) {
    try {
        $releaseReadySummary = $releaseReadySummaryText | ConvertFrom-Json
    }
    catch {
        $summaryParseError = $_.Exception.Message
    }
}

$summarySchema = Test-PrioritizedControlObjectProperties -Value $releaseReadySummary -RequiredProperties @(
    "releaseReady",
    "officialReleaseEligible",
    "packagingMode",
    "aggregateStatus",
    "releaseRunId",
    "provenanceGrade",
    "traceabilitySatisfied",
    "releaseEvidenceStatus"
)
$checks += New-MaturityCheck `
    -Name "release-ready-summary-schema" `
    -Status $(if ($zipExists -and [string]::IsNullOrWhiteSpace($summaryParseError) -and $summarySchema.valid) { "passed" } elseif ($zipExists) { "failed" } else { "warning" }) `
    -Expectation "release-ready-summary.json must remain readable and expose the current packaging/evidence fields." `
    -Summary $(if ($zipExists -and [string]::IsNullOrWhiteSpace($summaryParseError) -and $summarySchema.valid) { "release-ready-summary.json is readable and exposes the required fields." } elseif ($zipExists) { "release-ready-summary.json is missing fields or could not be parsed." } else { "release-ready-summary.json could not be inspected because no release-ready zip was available." }) `
    -Data @{
        parseError = $summaryParseError
        missingProperties = $summarySchema.missing
    }

$expectedPackagingMode = if ($aggregateExists) {
    (Get-ReleaseReadyDecision -AggregateStatus ([string]$aggregateReport.overallStatus) -ProvenanceGrade ([string]$aggregateReport.provenanceGrade)).packagingMode
}
else {
    $null
}
$packagingAgreementPassed = $zipExists -and $aggregateExists -and $null -ne $releaseReadySummary -and `
    ([string]$manifestMap["PackagingMode"] -eq [string]$releaseReadySummary.packagingMode) -and `
    ([string]$manifestMap["PackagingMode"] -eq [string]$expectedPackagingMode) -and `
    ([string]$manifestMap["AggregateStatus"] -eq [string]$aggregateReport.overallStatus) -and `
    ([string]$manifestMap["ReleaseEvidenceStatus"] -eq [string]$releaseReadySummary.releaseEvidenceStatus) -and `
    ([string]$manifestMap["ReleaseRunId"] -eq [string]$releaseReadySummary.releaseRunId) -and `
    ([string]$manifestMap["ProvenanceGrade"] -eq [string]$releaseReadySummary.provenanceGrade) -and `
    ([string]$manifestMap["TraceabilitySatisfied"] -eq [string]$releaseReadySummary.traceabilitySatisfied.ToString().ToLowerInvariant())
$checks += New-MaturityCheck `
    -Name "manifest-summary-agreement" `
    -Status $(if ($packagingAgreementPassed) { "passed" } elseif ($zipExists) { "failed" } else { "warning" }) `
    -Expectation "state-manifest.txt and release-ready-summary.json must agree with the aggregate report on packaging mode and release evidence metadata." `
    -Summary $(if ($packagingAgreementPassed) { "state-manifest.txt, release-ready-summary.json, and the aggregate report agree on packaging metadata." } elseif ($zipExists) { "state-manifest.txt, release-ready-summary.json, and the aggregate report do not fully agree on packaging metadata." } else { "Packaging metadata could not be cross-checked because no release-ready zip was available." }) `
    -Data @{
        expectedPackagingMode = $expectedPackagingMode
        manifestPackagingMode = $manifestMap["PackagingMode"]
        summaryPackagingMode = if ($null -eq $releaseReadySummary) { $null } else { [string]$releaseReadySummary.packagingMode }
        aggregateStatus = if ($null -eq $aggregateReport) { $null } else { [string]$aggregateReport.overallStatus }
    }

$diagnosticModePreserved = $zipExists -and $aggregateExists -and $null -ne $releaseReadySummary -and `
    ([string]$aggregateReport.provenanceGrade -eq "local-unanchored") -and `
    ([string]$aggregateReport.overallStatus -eq "passed") -and `
    ([string]$releaseReadySummary.packagingMode -eq "DIAGNOSTIC") -and `
    (-not [bool]$releaseReadySummary.officialReleaseEligible)
$checks += New-MaturityCheck `
    -Name "diagnostic-packaging-preserved" `
    -Status $(if ($zipExists -and [string]$aggregateReport.provenanceGrade -eq "local-unanchored") { $(if ($diagnosticModePreserved) { "passed" } else { "failed" }) } elseif ($zipExists) { "passed" } else { "warning" }) `
    -Expectation "Valid local-unanchored release evidence must still package as DIAGNOSTIC instead of hard-failing release-ready packaging." `
    -Summary $(if ($zipExists -and [string]$aggregateReport.provenanceGrade -eq "local-unanchored" -and $diagnosticModePreserved) { "Local-unanchored release evidence packaged successfully as DIAGNOSTIC." } elseif ($zipExists -and [string]$aggregateReport.provenanceGrade -eq "local-unanchored") { "Local-unanchored release evidence did not preserve DIAGNOSTIC packaging semantics." } elseif ($zipExists) { "Packaging semantics were validated against a traceable or non-local-unanchored aggregate run." } else { "Packaging semantics could not be validated because no release-ready zip was available." }) `
    -Data @{
        provenanceGrade = if ($null -eq $aggregateReport) { $null } else { [string]$aggregateReport.provenanceGrade }
        officialReleaseEligible = if ($null -eq $releaseReadySummary) { $null } else { [bool]$releaseReadySummary.officialReleaseEligible }
        packagingMode = if ($null -eq $releaseReadySummary) { $null } else { [string]$releaseReadySummary.packagingMode }
    }

$report = Write-PrioritizedControlReport `
    -WorkspaceRoot $WorkspaceRoot `
    -RunId $RunId `
    -ScriptPath $PSCommandPath `
    -Bucket "B1" `
    -ControlId "B4-PACKAGING-METADATA" `
    -ReportPath $ReportPath `
    -EvidencePaths @(
        Get-PrioritizedControlEvidencePath -WorkspaceRoot $WorkspaceRoot -PathValue $AggregateReportPath
        $StateZipPath
    ) `
    -Checks $checks `
    -Extra @{
        aggregateReportPath = Get-PrioritizedControlEvidencePath -WorkspaceRoot $WorkspaceRoot -PathValue $AggregateReportPath
        stateZipPath = $StateZipPath
        stateManifestKeys = @($manifestMap.Keys | Sort-Object)
    }

Complete-PrioritizedControlScript -Report $report -PassThru:$PassThru
