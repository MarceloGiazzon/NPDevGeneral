[CmdletBinding()]
param(
    [string]$WorkspaceRoot = "",
    [string]$StateZipPath = "",
    [switch]$RequireOfficialEligibility
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

. (Join-Path $PSScriptRoot "..\npdev-common.ps1")
. (Join-Path $PSScriptRoot "..\statezip-common.ps1")

if ([string]::IsNullOrWhiteSpace($WorkspaceRoot)) {
    $WorkspaceRoot = Get-NPDevWorkspaceRoot $PSScriptRoot
}
$WorkspaceRoot = Normalize-NPDevPath $WorkspaceRoot

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

function Test-TraceabilityPropertyPresent {
    param(
        [AllowNull()][object]$ObjectValue,
        [Parameter(Mandatory = $true)]
        [string]$PropertyName
    )

    return ($null -ne $ObjectValue -and $ObjectValue.PSObject.Properties.Name -contains $PropertyName)
}

function Get-TraceabilityProperty {
    param(
        [AllowNull()][object]$ObjectValue,
        [Parameter(Mandatory = $true)]
        [string]$PropertyName
    )

    if (Test-TraceabilityPropertyPresent -ObjectValue $ObjectValue -PropertyName $PropertyName) {
        return $ObjectValue.PSObject.Properties[$PropertyName].Value
    }

    return $null
}

function Read-TraceabilityJson {
    param(
        [Parameter(Mandatory = $true)]
        [string]$PathValue,
        [Parameter(Mandatory = $true)]
        [string]$Label
    )

    if (-not (Test-Path -LiteralPath $PathValue -PathType Leaf)) {
        [void]$failures.Add($Label + " not found: " + $PathValue)
        return $null
    }

    try {
        return Get-Content -LiteralPath $PathValue -Raw | ConvertFrom-Json
    }
    catch {
        [void]$failures.Add($Label + " could not be parsed: " + $_.Exception.Message)
        return $null
    }
}

function Get-ZipEntryTextForTraceabilityTest {
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

function Resolve-LatestStateZipPath {
    param([string]$WorkspaceRootValue)

    $outDir = Get-DefaultStateZipOutDir -WorkspaceRoot $WorkspaceRootValue
    if (-not (Test-Path -LiteralPath $outDir -PathType Container)) {
        return $null
    }

    $candidate = @(
        Get-ChildItem -LiteralPath $outDir -File -Filter "NPDev_General_State_ALL_*.zip" |
        Where-Object { $_.Name -notmatch "TEST_" } |
        Sort-Object LastWriteTime -Descending |
        Select-Object -First 1
    )
    if ($candidate.Count -eq 0) {
        return $null
    }

    return [string]$candidate[0].FullName
}

function Assert-TraceabilityShape {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Label,
        [AllowNull()][object]$Report
    )

    Assert-True ($null -ne $Report) ($Label + " should be available.")
    if ($null -eq $Report) {
        return
    }

    foreach ($propertyName in @("provenanceGrade", "traceabilitySatisfied", "releaseReady", "officialReleaseEligible", "commitIdentity")) {
        Assert-True (Test-TraceabilityPropertyPresent -ObjectValue $Report -PropertyName $propertyName) ($Label + " should include " + $propertyName + ".")
    }

    $commitIdentity = Get-TraceabilityProperty -ObjectValue $Report -PropertyName "commitIdentity"
    Assert-True (Test-TraceabilityPropertyPresent -ObjectValue $commitIdentity -PropertyName "available") ($Label + " commitIdentity should include available.")
    Assert-True (Test-TraceabilityPropertyPresent -ObjectValue $commitIdentity -PropertyName "commitSha") ($Label + " commitIdentity should include commitSha.")
    Assert-True (Test-TraceabilityPropertyPresent -ObjectValue $commitIdentity -PropertyName "source") ($Label + " commitIdentity should include source.")
}

function Get-OfficialEligibility {
    param([AllowNull()][object]$Report)

    if (-not (Test-TraceabilityPropertyPresent -ObjectValue $Report -PropertyName "officialReleaseEligible")) {
        return $false
    }

    return [bool](Get-TraceabilityProperty -ObjectValue $Report -PropertyName "officialReleaseEligible")
}

function Assert-TraceabilityAgreement {
    param(
        [Parameter(Mandatory = $true)]
        [string]$FieldName,
        [Parameter(Mandatory = $true)]
        [object[]]$Reports
    )

    $values = @(
        $Reports |
        Where-Object { $null -ne $_.report -and (Test-TraceabilityPropertyPresent -ObjectValue $_.report -PropertyName $FieldName) } |
        ForEach-Object {
            [pscustomobject]@{
                label = [string]$_.label
                value = [string](Get-TraceabilityProperty -ObjectValue $_.report -PropertyName $FieldName)
            }
        }
    )

    if ($values.Count -le 1) {
        return
    }

    $first = [string]$values[0].value
    foreach ($entry in $values) {
        Assert-True ([string]$entry.value -eq $first) ("Traceability field " + $FieldName + " differs: " + [string]$entry.label + " has '" + [string]$entry.value + "', expected '" + $first + "'.")
    }
}

$betaReportPath = Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\reports\out\beta-release-gate-report.json"
$betaReport = Read-TraceabilityJson -PathValue $betaReportPath -Label "Beta release gate report"
$manifest = $null
$summary = $null
$summarySource = $null

if ($null -ne $betaReport) {
    $evidenceRootValue = [string](Get-TraceabilityProperty -ObjectValue $betaReport -PropertyName "evidenceRoot")
    if ([string]::IsNullOrWhiteSpace($evidenceRootValue)) {
        [void]$failures.Add("Beta release gate report is missing evidenceRoot.")
    }
    else {
        $evidenceRoot = if ([System.IO.Path]::IsPathRooted($evidenceRootValue)) {
            Normalize-NPDevPath $evidenceRootValue
        }
        else {
            Resolve-NPDevWorkspacePath $WorkspaceRoot $evidenceRootValue
        }
        $manifestPath = Join-Path $evidenceRoot "evidence-manifest.json"
        $manifest = Read-TraceabilityJson -PathValue $manifestPath -Label "Release evidence manifest"
    }
}

if ([string]::IsNullOrWhiteSpace($StateZipPath)) {
    $StateZipPath = Resolve-LatestStateZipPath -WorkspaceRootValue $WorkspaceRoot
}
elseif (-not [System.IO.Path]::IsPathRooted($StateZipPath)) {
    $StateZipPath = Resolve-NPDevWorkspacePath $WorkspaceRoot $StateZipPath
}

if (-not [string]::IsNullOrWhiteSpace($StateZipPath) -and (Test-Path -LiteralPath $StateZipPath -PathType Leaf)) {
    $summaryText = Get-ZipEntryTextForTraceabilityTest -ZipPath $StateZipPath -EntryPath "release-ready-summary.json"
    if (-not [string]::IsNullOrWhiteSpace($summaryText)) {
        $candidateSummary = $summaryText | ConvertFrom-Json
        $candidateSummaryRunId = [string](Get-TraceabilityProperty -ObjectValue $candidateSummary -PropertyName "releaseRunId")
        $betaReleaseRunId = [string](Get-TraceabilityProperty -ObjectValue $betaReport -PropertyName "releaseRunId")
        if ($null -ne $betaReport -and $candidateSummaryRunId -eq $betaReleaseRunId) {
            $summary = $candidateSummary
            $summarySource = $StateZipPath
        }
    }
}

Assert-TraceabilityShape -Label "Beta release gate report" -Report $betaReport
Assert-TraceabilityShape -Label "Release evidence manifest" -Report $manifest
if ($null -ne $summary) {
    Assert-TraceabilityShape -Label "Release-ready summary" -Report $summary
}
elseif ($RequireOfficialEligibility) {
    [void]$failures.Add("RequireOfficialEligibility was set, but no current release-ready summary was found in the latest state zip.")
}

$reports = @(
    [pscustomobject]@{ label = "beta release gate report"; report = $betaReport },
    [pscustomobject]@{ label = "release evidence manifest"; report = $manifest }
)
if ($null -ne $summary) {
    $reports += [pscustomobject]@{ label = "release-ready summary"; report = $summary }
}

foreach ($fieldName in @("provenanceGrade", "traceabilitySatisfied", "releaseReady", "officialReleaseEligible")) {
    Assert-TraceabilityAgreement -FieldName $fieldName -Reports $reports
}

$anyOfficialReleaseEligible = @($reports | Where-Object { Get-OfficialEligibility -Report $_.report }).Count -gt 0
if ($RequireOfficialEligibility -or $anyOfficialReleaseEligible) {
    if ($null -eq $summary) {
        [void]$failures.Add("Official release eligibility requires a current release-ready summary in the state zip.")
    }

    foreach ($entry in $reports) {
        $label = [string]$entry.label
        $report = $entry.report
        Assert-True ([bool](Get-TraceabilityProperty -ObjectValue $report -PropertyName "officialReleaseEligible")) ($label + " should be official release eligible.")
        Assert-True ([bool](Get-TraceabilityProperty -ObjectValue $report -PropertyName "traceabilitySatisfied")) ($label + " should have traceabilitySatisfied = true.")
        Assert-True ([string](Get-TraceabilityProperty -ObjectValue $report -PropertyName "provenanceGrade") -in @("git-traceable", "ci-traceable")) ($label + " should have git-traceable or ci-traceable provenance.")

        $commitIdentity = Get-TraceabilityProperty -ObjectValue $report -PropertyName "commitIdentity"
        Assert-True ([bool](Get-TraceabilityProperty -ObjectValue $commitIdentity -PropertyName "available")) ($label + " commitIdentity.available should be true.")
        Assert-True (-not [string]::IsNullOrWhiteSpace([string](Get-TraceabilityProperty -ObjectValue $commitIdentity -PropertyName "commitSha"))) ($label + " commitIdentity.commitSha should be non-empty.")
    }
}

if ($failures.Count -eq 0) {
    $suffix = if ($null -eq $summarySource) { "" } else { " State zip: " + $summarySource }
    Write-NPDevOk ("Release traceability tests passed." + $suffix)
    exit 0
}

foreach ($failure in $failures) {
    Write-NPDevWarn $failure
}
throw "Release traceability tests failed."
