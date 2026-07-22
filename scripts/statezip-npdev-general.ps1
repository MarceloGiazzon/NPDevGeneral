param(
    [string]$WorkspaceRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot "..")).Path,
    [string]$OutDir = "",
    [switch]$IncludeWorkspaceDocs = $true,
    [switch]$ReleaseReady,
    [string]$ExistingEvidenceRoot = "",
    [switch]$NoBundle,
    [string]$Stamp = "",
    [switch]$Quiet
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

. (Join-Path $PSScriptRoot "statezip-common.ps1")

$normalizedWorkspaceRoot = Get-NormalizedFullPath -PathValue $WorkspaceRoot
Assert-PathExists -PathValue $normalizedWorkspaceRoot -Label "NPDev_General workspace root"

if ([string]::IsNullOrWhiteSpace($OutDir)) {
    $OutDir = Get-DefaultStateZipOutDir -WorkspaceRoot $normalizedWorkspaceRoot
}

$normalizedOutDir = Get-NormalizedFullPath -PathValue $OutDir
Ensure-Directory -PathValue $normalizedOutDir

if ([string]::IsNullOrWhiteSpace($Stamp)) {
    $Stamp = Get-Date -Format "yyyyMMdd_HHmmss"
}

$subprojectScripts = @(
    @{ Name = "NPDevContract"; Path = Join-Path $PSScriptRoot "statezip-npdev-contract.ps1" },
    @{ Name = "NPDevEditor"; Path = Join-Path $PSScriptRoot "statezip-npdev-editor.ps1" },
    @{ Name = "NPDevGenerator"; Path = Join-Path $PSScriptRoot "statezip-npdev-generator.ps1" },
    @{ Name = "NPDevKernel"; Path = Join-Path $PSScriptRoot "statezip-npdev-kernel.ps1" },
    @{ Name = "NPDevRuntimeHost"; Path = Join-Path $PSScriptRoot "statezip-npdev-runtimehost.ps1" },
    @{ Name = "NPDevSamples"; Path = Join-Path $PSScriptRoot "statezip-npdev-samples.ps1" }
)

if (-not $Quiet) {
    Write-Host "== NPDev General: State zip ALL =="
    Write-Host "Workspace: $normalizedWorkspaceRoot"
    Write-Host "OutDir:    $normalizedOutDir"
    Write-Host "Stamp:     $Stamp"
    Write-Host ("Mode:      " + $(if ($ReleaseReady) { "release-ready" } else { "source-snapshot" }))
    Write-Host ""
}

$releaseEvidenceStatus = $null
$releaseEvidenceRoot = $null
$releaseEvidenceRunId = $null
$releaseEvidenceManifestPath = $null
$releaseProvenanceGrade = $null
$releaseTraceabilitySatisfied = $false
$releaseReadyDecision = $false
$officialReleaseEligible = $false
$releaseCommitIdentity = $null
$packagingMode = if ($ReleaseReady) { "DIAGNOSTIC" } else { "SOURCE" }
$releaseDecisionReason = "Release-ready mode was not requested."
$releaseEvidenceReportPath = Join-Path $normalizedWorkspaceRoot "scripts\reports\out\beta-release-gate-report.json"
$reportsOutRoot = Join-Path $normalizedWorkspaceRoot "scripts\reports\out"
$releaseReportsOutSourceRoot = $reportsOutRoot

function Resolve-LastExistingEvidenceRoot {
    param(
        [Parameter(Mandatory = $true)]
        [string]$WorkspaceRootValue
    )

    $releasesRoot = Join-Path $WorkspaceRootValue "scripts\reports\releases"
    Assert-PathExists -PathValue $releasesRoot -Label "Release evidence releases root"

    $candidates = @(
        Get-ChildItem -LiteralPath $releasesRoot -Directory -Force |
        Sort-Object LastWriteTime -Descending
    )

    foreach ($candidate in $candidates) {
        $manifestPath = Join-Path $candidate.FullName "evidence-manifest.json"
        $flatManifestPath = Join-Path $candidate.FullName "beta-release-evidence-manifest.json"
        $betaReportPath = Join-Path $candidate.FullName "scripts\reports\out\beta-release-gate-report.json"
        $flatBetaReportPath = Join-Path $candidate.FullName "beta-release-gate-report.json"
        $hasManifest = (Test-Path -LiteralPath $manifestPath -PathType Leaf) -or (Test-Path -LiteralPath $flatManifestPath -PathType Leaf)
        $hasBetaReport = (Test-Path -LiteralPath $betaReportPath -PathType Leaf) -or (Test-Path -LiteralPath $flatBetaReportPath -PathType Leaf)
        if ($hasManifest -and $hasBetaReport) {
            return $candidate.FullName
        }
    }

    throw ("No complete existing release evidence bundle was found under: " + $releasesRoot)
}

function Test-ObjectPropertyPresent {
    param(
        [AllowNull()][object]$ObjectValue,
        [Parameter(Mandatory = $true)]
        [string]$PropertyName
    )

    return ($null -ne $ObjectValue -and $ObjectValue.PSObject.Properties.Name -contains $PropertyName)
}

function Get-OptionalObjectProperty {
    param(
        [AllowNull()][object]$ObjectValue,
        [Parameter(Mandatory = $true)]
        [string]$PropertyName
    )

    if (Test-ObjectPropertyPresent -ObjectValue $ObjectValue -PropertyName $PropertyName) {
        return $ObjectValue.PSObject.Properties[$PropertyName].Value
    }

    return $null
}

function Get-OptionalTraceabilityBoolean {
    param(
        [AllowNull()][object]$ObjectValue,
        [Parameter(Mandatory = $true)]
        [string]$PropertyName
    )

    if (-not (Test-ObjectPropertyPresent -ObjectValue $ObjectValue -PropertyName $PropertyName)) {
        return $null
    }

    return [bool](Get-OptionalObjectProperty -ObjectValue $ObjectValue -PropertyName $PropertyName)
}

function Get-FirstNonBlankString {
    param([object[]]$Values)

    foreach ($value in @($Values)) {
        $text = [string]$value
        if (-not [string]::IsNullOrWhiteSpace($text)) {
            return $text
        }
    }

    return ""
}

function Assert-TraceabilityFieldAgreement {
    param(
        [string]$FieldName,
        [AllowNull()][object]$ReportValue,
        [AllowNull()][object]$ManifestValue
    )

    if ($null -eq $ReportValue -or $null -eq $ManifestValue) {
        return
    }

    if ([string]$ReportValue -ne [string]$ManifestValue) {
        throw ("Release evidence traceability field mismatch for " + $FieldName + ": beta report has '" + [string]$ReportValue + "', evidence manifest has '" + [string]$ManifestValue + "'.")
    }
}

if ($ReleaseReady) {
    if (-not $Quiet) {
        Write-Host "-- Using existing aggregate release evidence --"
    }

    if (-not [string]::IsNullOrWhiteSpace($ExistingEvidenceRoot)) {
        if ($ExistingEvidenceRoot.Equals("last", [System.StringComparison]::OrdinalIgnoreCase)) {
            $releaseEvidenceRoot = Resolve-LastExistingEvidenceRoot -WorkspaceRootValue $normalizedWorkspaceRoot
            if (-not $Quiet) {
                Write-Host ("Resolved -ExistingEvidenceRoot last to: " + $releaseEvidenceRoot)
            }
        }
        else {
            $releaseEvidenceRoot = if ([System.IO.Path]::IsPathRooted($ExistingEvidenceRoot)) {
                Get-NormalizedFullPath -PathValue $ExistingEvidenceRoot
            }
            else {
                Get-NormalizedFullPath -PathValue (Join-Path $normalizedWorkspaceRoot $ExistingEvidenceRoot)
            }
        }
        Assert-PathExists -PathValue $releaseEvidenceRoot -Label "Existing release evidence root"

        $structuredReportsOutSourceRoot = Join-Path $releaseEvidenceRoot "scripts\reports\out"
        $releaseReportsOutSourceRoot = if (Test-Path -LiteralPath $structuredReportsOutSourceRoot -PathType Container) {
            $structuredReportsOutSourceRoot
        }
        else {
            $releaseEvidenceRoot
        }
        Assert-PathExists -PathValue $releaseReportsOutSourceRoot -Label "Bundled reports/out evidence"

        $releaseEvidenceReportPath = Join-Path $releaseReportsOutSourceRoot "beta-release-gate-report.json"
    }

    Assert-PathExists -PathValue $releaseEvidenceReportPath -Label "Beta release gate report"
    $releaseEvidenceReport = Get-Content -LiteralPath $releaseEvidenceReportPath -Raw | ConvertFrom-Json
    $releaseEvidenceStatus = [string]$releaseEvidenceReport.overallStatus
    $releaseCommitIdentity = if ($releaseEvidenceReport.PSObject.Properties.Name -contains "commitIdentity") { $releaseEvidenceReport.commitIdentity } else { $null }
    if ([string]::IsNullOrWhiteSpace($releaseEvidenceStatus)) {
        throw "Beta release gate report is missing overallStatus."
    }
    if ($releaseEvidenceStatus -notin @("passed", "warning", "failed")) {
        throw ("Beta release gate report has invalid overallStatus: " + $releaseEvidenceStatus)
    }

    $releaseEvidenceRunId = Get-FirstNonBlankString -Values @(
        (Get-OptionalObjectProperty -ObjectValue $releaseEvidenceReport -PropertyName "releaseRunId"),
        (Get-OptionalObjectProperty -ObjectValue $releaseEvidenceReport -PropertyName "runId")
    )
    if ([string]::IsNullOrWhiteSpace($releaseEvidenceRunId)) {
        throw "Beta release gate report is missing releaseRunId/runId."
    }

    if ([string]::IsNullOrWhiteSpace($releaseEvidenceRoot)) {
        $releaseEvidenceRootValue = [string]$releaseEvidenceReport.evidenceRoot
        if ([string]::IsNullOrWhiteSpace($releaseEvidenceRootValue)) {
            throw "Beta release gate report is missing evidenceRoot."
        }

        $releaseEvidenceRoot = if ([System.IO.Path]::IsPathRooted($releaseEvidenceRootValue)) {
            $releaseEvidenceRootValue
        }
        else {
            Join-Path $normalizedWorkspaceRoot $releaseEvidenceRootValue
        }
    }
    Assert-PathExists -PathValue $releaseEvidenceRoot -Label "Release evidence root"

    $releaseEvidenceManifestPath = Join-Path $releaseEvidenceRoot "evidence-manifest.json"
    if (-not (Test-Path -LiteralPath $releaseEvidenceManifestPath -PathType Leaf)) {
        $releaseEvidenceManifestPath = Join-Path $releaseEvidenceRoot "beta-release-evidence-manifest.json"
    }
    Assert-PathExists -PathValue $releaseEvidenceManifestPath -Label "Release evidence manifest"
    $releaseEvidenceManifest = Get-Content -LiteralPath $releaseEvidenceManifestPath -Raw | ConvertFrom-Json

    $reportProvenanceGrade = Get-OptionalObjectProperty -ObjectValue $releaseEvidenceReport -PropertyName "provenanceGrade"
    $manifestProvenanceGrade = Get-OptionalObjectProperty -ObjectValue $releaseEvidenceManifest -PropertyName "provenanceGrade"
    Assert-TraceabilityFieldAgreement -FieldName "provenanceGrade" -ReportValue $reportProvenanceGrade -ManifestValue $manifestProvenanceGrade
    $reportProvenanceReady = Get-OptionalTraceabilityBoolean -ObjectValue $releaseEvidenceReport -PropertyName "provenanceReady"
    $manifestProvenanceReady = Get-OptionalTraceabilityBoolean -ObjectValue $releaseEvidenceManifest -PropertyName "provenanceReady"
    $derivedProvenanceGrade = if ($manifestProvenanceReady -or $reportProvenanceReady) { "git-traceable" } else { "local-unanchored" }
    $releaseProvenanceGrade = Get-FirstNonBlankString -Values @($manifestProvenanceGrade, $reportProvenanceGrade, $derivedProvenanceGrade)

    $reportTraceabilitySatisfied = Get-OptionalTraceabilityBoolean -ObjectValue $releaseEvidenceReport -PropertyName "traceabilitySatisfied"
    $manifestTraceabilitySatisfied = Get-OptionalTraceabilityBoolean -ObjectValue $releaseEvidenceManifest -PropertyName "traceabilitySatisfied"
    Assert-TraceabilityFieldAgreement -FieldName "traceabilitySatisfied" -ReportValue $reportTraceabilitySatisfied -ManifestValue $manifestTraceabilitySatisfied

    $reportReleaseReady = Get-OptionalTraceabilityBoolean -ObjectValue $releaseEvidenceReport -PropertyName "releaseReady"
    $manifestReleaseReady = Get-OptionalTraceabilityBoolean -ObjectValue $releaseEvidenceManifest -PropertyName "releaseReady"
    Assert-TraceabilityFieldAgreement -FieldName "releaseReady" -ReportValue $reportReleaseReady -ManifestValue $manifestReleaseReady

    $reportOfficialReleaseEligible = Get-OptionalTraceabilityBoolean -ObjectValue $releaseEvidenceReport -PropertyName "officialReleaseEligible"
    $manifestOfficialReleaseEligible = Get-OptionalTraceabilityBoolean -ObjectValue $releaseEvidenceManifest -PropertyName "officialReleaseEligible"
    Assert-TraceabilityFieldAgreement -FieldName "officialReleaseEligible" -ReportValue $reportOfficialReleaseEligible -ManifestValue $manifestOfficialReleaseEligible

    if ($null -eq $releaseCommitIdentity -and (Test-ObjectPropertyPresent -ObjectValue $releaseEvidenceManifest -PropertyName "commitIdentity")) {
        $releaseCommitIdentity = $releaseEvidenceManifest.commitIdentity
    }

    $releaseDecision = Get-ReleaseReadyDecision -AggregateStatus $releaseEvidenceStatus -ProvenanceGrade $releaseProvenanceGrade
    $releaseTraceabilitySatisfied = if ($null -ne $manifestTraceabilitySatisfied) { [bool]$manifestTraceabilitySatisfied } elseif ($null -ne $reportTraceabilitySatisfied) { [bool]$reportTraceabilitySatisfied } elseif ($null -ne $manifestProvenanceReady) { [bool]$manifestProvenanceReady } elseif ($null -ne $reportProvenanceReady) { [bool]$reportProvenanceReady } else { [bool]$releaseDecision.traceabilitySatisfied }
    $releaseReadyDecision = if ($null -ne $manifestReleaseReady) { [bool]$manifestReleaseReady } elseif ($null -ne $reportReleaseReady) { [bool]$reportReleaseReady } else { [bool]$releaseDecision.releaseReady }
    $officialReleaseEligible = if ($null -ne $manifestOfficialReleaseEligible) { [bool]$manifestOfficialReleaseEligible } elseif ($null -ne $reportOfficialReleaseEligible) { [bool]$reportOfficialReleaseEligible } else { [bool]$releaseDecision.officialReleaseEligible }
    $packagingMode = if ($officialReleaseEligible) { "RELEASE_READY" } else { [string]$releaseDecision.packagingMode }
    $releaseDecisionReason = if ($officialReleaseEligible) {
        "Aggregate beta release gate passed with traceable provenance preserved from release evidence."
    }
    else {
        [string]$releaseDecision.decisionReason
    }

    if (-not $Quiet) {
        Write-Host ("Release evidence status: " + $releaseEvidenceStatus)
        Write-Host ("Release evidence root:   " + $releaseEvidenceRoot)
        Write-Host ("Provenance grade:       " + $releaseProvenanceGrade)
        Write-Host ("Traceability satisfied: " + $releaseTraceabilitySatisfied)
        Write-Host ""
    }
}

$subprojectResults = New-Object System.Collections.Generic.List[object]

foreach ($entry in $subprojectScripts) {
    Assert-PathExists -PathValue $entry.Path -Label ($entry.Name + " statezip script")

    if (-not $Quiet) {
        Write-Host ("-- Running " + $entry.Name + " state zip --")
    }

    $result = & $entry.Path `
        -WorkspaceRoot $normalizedWorkspaceRoot `
        -OutDir $normalizedOutDir `
        -IncludeWorkspaceDocs:$IncludeWorkspaceDocs `
        -Stamp $Stamp `
        -Quiet:$Quiet

    if ($null -eq $result -or [string]::IsNullOrWhiteSpace([string]$result.ZipPath)) {
        throw ("State zip script did not return a ZipPath: " + $entry.Path)
    }

    Assert-PathExists -PathValue $result.ZipPath -Label ($entry.Name + " zip")
    $subprojectResults.Add($result)

    if (-not $Quiet) {
        Write-Host ("Finished " + $entry.Name + ": " + $result.ZipPath)
        Write-Host ""
    }
}

if ($NoBundle) {
    if (-not $Quiet) {
        Write-Host "Resume: NPDev General subproject zips"
        foreach ($result in $subprojectResults) {
            $zipSize = if ($result.PSObject.Properties.Name -contains "ZipSizeBytes") { [long]$result.ZipSizeBytes } else { (Get-Item -LiteralPath $result.ZipPath).Length }
            Write-Host (" - " + $result.SubprojectName + ": " + $result.ZipPath + " (" + (Format-StateZipSize -ByteCount $zipSize) + ")")
        }
        Write-Host ""
        Write-Host "Skipped ALL bundle because -NoBundle was provided."
    }

    return [pscustomobject]@{
        StateName = "NPDev_General_State_ALL"
        ZipPath = $null
        SubprojectZips = @($subprojectResults | ForEach-Object { $_.ZipPath })
    }
}

$stateName = "NPDev_General_State_ALL"
$stageRoot = Join-Path $env:TEMP ($stateName + "_" + $Stamp)
$zipPath = Join-Path $normalizedOutDir ($stateName + "_" + $Stamp + ".zip")

if (Test-Path -LiteralPath $zipPath) {
    Remove-Item -LiteralPath $zipPath -Force
}

Remove-DirectorySafe -PathValue $stageRoot
Ensure-Directory -PathValue $stageRoot

$copiedCounts = @{}
$includedRoots = New-Object System.Collections.Generic.List[string]

try {
    $scriptsExtraExcludeDirNames = @("background", "releases")
    $reportsOutDest = $null
    $releaseEvidenceDest = $null

    if ($IncludeWorkspaceDocs) {
        Write-StateZipInfo -Quiet:$Quiet -Message ("ALL bundle step 1/" + $(if ($ReleaseReady) { "7" } else { "5" }) + " - Copying workspace docs")
        $workspaceDocsRoot = Join-Path $stageRoot "_workspace-root"
        $copiedCounts["_workspace-root"] = Copy-WorkspaceDocs -WorkspaceRoot $normalizedWorkspaceRoot -DestRoot $workspaceDocsRoot -Quiet:$Quiet
        $includedRoots.Add($normalizedWorkspaceRoot)
    } else {
        Write-StateZipInfo -Quiet:$Quiet -Message ("ALL bundle step 1/" + $(if ($ReleaseReady) { "7" } else { "5" }) + " - Skipping workspace docs")
    }

    Write-StateZipInfo -Quiet:$Quiet -Message ("ALL bundle step 2/" + $(if ($ReleaseReady) { "7" } else { "5" }) + " - Copying statezip scripts")
    $scriptsRoot = Join-Path $normalizedWorkspaceRoot "scripts"
    $copiedCounts["scripts"] = Copy-SignificantTree `
        -SourceRoot $scriptsRoot `
        -DestRoot (Join-Path $stageRoot "scripts") `
        -StatusLabel "scripts" `
        -Quiet:$Quiet `
        -ExtraExcludeDirNames $scriptsExtraExcludeDirNames
    $includedRoots.Add($scriptsRoot)

    if ($ReleaseReady -and (Test-Path -LiteralPath $releaseReportsOutSourceRoot -PathType Container)) {
        Write-StateZipInfo -Quiet:$Quiet -Message "ALL bundle step 3/7 - Copying current reports/out evidence"
        $reportsOutDest = Join-Path $stageRoot "scripts\reports\out"
        $copiedCounts["scripts/reports/out"] = Copy-DirectoryTreeExact -SourceRoot $releaseReportsOutSourceRoot -DestRoot $reportsOutDest -StatusLabel "scripts/reports/out" -Quiet:$Quiet
        $includedRoots.Add($releaseReportsOutSourceRoot)
    }
    elseif ($ReleaseReady) {
        throw ("Release-ready packaging requires existing reports/out evidence, but it was not found: " + $releaseReportsOutSourceRoot)
    }

    if ($ReleaseReady -and -not [string]::IsNullOrWhiteSpace($releaseEvidenceRoot)) {
        Write-StateZipInfo -Quiet:$Quiet -Message "ALL bundle step 4/7 - Copying current release evidence bundle"
        $releaseEvidenceDest = Join-Path $stageRoot ("scripts\reports\releases\" + (Split-Path -Leaf $releaseEvidenceRoot))
        $copiedCounts["scripts/reports/releases/current"] = Copy-DirectoryTreeExact -SourceRoot $releaseEvidenceRoot -DestRoot $releaseEvidenceDest -StatusLabel "scripts/reports/releases/current" -Quiet:$Quiet
        $includedRoots.Add($releaseEvidenceRoot)
    }
    elseif ($ReleaseReady) {
        throw "Release-ready packaging requires a current release evidence bundle, but beta release evidence did not resolve releaseEvidenceRoot."
    }

    if ($ReleaseReady) {
        $stagedBetaReport = Join-Path $reportsOutDest "beta-release-gate-report.json"
        $stagedEvidenceManifest = Join-Path $releaseEvidenceDest (Split-Path -Leaf $releaseEvidenceManifestPath)
        Assert-PathExists -PathValue $stagedBetaReport -Label "Staged beta release gate report"
        Assert-PathExists -PathValue $stagedEvidenceManifest -Label "Staged release evidence manifest"

        $stagedReportsOutFileCount = @(Get-ChildItem -LiteralPath $reportsOutDest -File -Recurse -Force).Count
        $stagedReleaseEvidenceFileCount = @(Get-ChildItem -LiteralPath $releaseEvidenceDest -File -Recurse -Force).Count
        if ($stagedReportsOutFileCount -le 0) {
            throw ("Release-ready packaging staged no reports/out files: " + $reportsOutDest)
        }
        if ($stagedReleaseEvidenceFileCount -le 0) {
            throw ("Release-ready packaging staged no release evidence files: " + $releaseEvidenceDest)
        }
    }

    Write-StateZipInfo -Quiet:$Quiet -Message ($(if ($ReleaseReady) { "ALL bundle step 5/7 - Copying subproject zip files" } else { "ALL bundle step 3/5 - Copying subproject zip files" }))
    $bundleZipRoot = Join-Path $stageRoot "subproject-zips"
    Ensure-Directory -PathValue $bundleZipRoot

    $subprojectZipCount = 0
    foreach ($result in $subprojectResults) {
        $destination = Join-Path $bundleZipRoot (Split-Path -Leaf $result.ZipPath)
        Copy-StateZipFile -SourcePath $result.ZipPath -DestinationPath $destination
        $subprojectZipCount++
        $includedRoots.Add($result.ZipPath)
        Write-StateZipInfo -Quiet:$Quiet -Message ("  Included subproject zip: " + (Split-Path -Leaf $result.ZipPath))
    }
    $copiedCounts["subproject-zips"] = $subprojectZipCount

    Write-StateZipInfo -Quiet:$Quiet -Message ($(if ($ReleaseReady) { "ALL bundle step 6/7 - Writing state manifest" } else { "ALL bundle step 4/5 - Writing state manifest" }))
    $manifestPath = Join-Path $stageRoot "state-manifest.txt"
    Write-StateManifest `
        -ManifestPath $manifestPath `
        -WorkspaceRootValue $normalizedWorkspaceRoot `
        -ZipPathValue $zipPath `
        -StateName $stateName `
        -IncludedRoots $includedRoots.ToArray() `
        -CopiedCounts $copiedCounts `
        -PackagingMode $packagingMode `
        -AggregateStatus $(if ($ReleaseReady) { $releaseEvidenceStatus } else { "" }) `
        -ReleaseRunId $(if ($ReleaseReady) { $releaseEvidenceRunId } else { "" }) `
        -ReleaseEvidenceStatus $(if ($ReleaseReady) { $releaseEvidenceStatus } else { "" }) `
        -ProvenanceGrade $(if ($ReleaseReady) { $releaseProvenanceGrade } else { "" }) `
        -TraceabilitySatisfied $(if ($ReleaseReady) { $releaseTraceabilitySatisfied } else { $null }) `
        -ReleaseReady $(if ($ReleaseReady) { $releaseReadyDecision } else { $null }) `
        -OfficialReleaseEligible $(if ($ReleaseReady) { $officialReleaseEligible } else { $null }) `
        -CommitSha $(if ($ReleaseReady -and $null -ne $releaseCommitIdentity) { [string]$releaseCommitIdentity.commitSha } else { "" }) `
        -Branch $(if ($ReleaseReady -and $null -ne $releaseCommitIdentity) { [string]$releaseCommitIdentity.branch } else { "" }) `
        -SourceDirty $(if ($ReleaseReady -and $null -ne $releaseCommitIdentity) { $releaseCommitIdentity.dirty } else { $null }) `
        -SourceProvider $(if ($ReleaseReady -and $null -ne $releaseCommitIdentity) { [string]$releaseCommitIdentity.source } else { "" })

    if ($ReleaseReady) {
        $releaseReadySummaryPath = Join-Path $stageRoot "release-ready-summary.json"
        $packagedReleaseEvidenceRoot = "scripts\reports\releases\" + (Split-Path -Leaf $releaseEvidenceRoot)
        [pscustomobject]@{
            generatedAt = (Get-Date).ToString("o")
            workspaceRoot = $normalizedWorkspaceRoot
            releaseReady = $releaseReadyDecision
            officialReleaseEligible = $officialReleaseEligible
            packagingMode = $packagingMode
            decisionReason = $releaseDecisionReason
            aggregateReportPath = "scripts\reports\out\beta-release-gate-report.json"
            aggregateStatus = $releaseEvidenceStatus
            releaseRunId = $releaseEvidenceRunId
            provenanceGrade = $releaseProvenanceGrade
            traceabilitySatisfied = $releaseTraceabilitySatisfied
            commitIdentity = $releaseCommitIdentity
            sourceDirty = if ($null -eq $releaseCommitIdentity) { $null } else { $releaseCommitIdentity.dirty }
            sourceProvider = if ($null -eq $releaseCommitIdentity) { $null } else { [string]$releaseCommitIdentity.source }
            releaseEvidenceStatus = $releaseEvidenceStatus
            releaseEvidenceRunId = $releaseEvidenceRunId
            releaseEvidenceManifest = $packagedReleaseEvidenceRoot + "\" + (Split-Path -Leaf $releaseEvidenceManifestPath)
            releaseEvidenceReport = "scripts\reports\out\beta-release-gate-report.json"
            releaseEvidenceRoot = $packagedReleaseEvidenceRoot
            authoritativeRule = "releaseReady is true only when scripts\reports\out\beta-release-gate-report.json has overallStatus 'passed'. officialReleaseEligible additionally requires git-traceable or ci-traceable provenance."
        } | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath $releaseReadySummaryPath -Encoding UTF8
        Set-StateZipTimestamp -PathValue $releaseReadySummaryPath
    }

    Write-StateZipInfo -Quiet:$Quiet -Message ($(if ($ReleaseReady) { "ALL bundle step 7/7 - Compressing ALL zip" } else { "ALL bundle step 5/5 - Compressing ALL zip" }))
    Compress-Archive -Path (Join-Path $stageRoot "*") -DestinationPath $zipPath -Force
    Finalize-StateZipArchive -ZipPath $zipPath
    $zipSizeBytes = (Get-Item -LiteralPath $zipPath).Length

    if (-not $Quiet) {
        Write-Host ""
        Write-Host "Resume: NPDev General ALL"
        Write-Host (" - ALL zip: " + $zipPath)
        Write-Host (" - ALL size: " + (Format-StateZipSize -ByteCount $zipSizeBytes))
        if ($ReleaseReady) {
            Write-Host (" - Release evidence status: " + $releaseEvidenceStatus)
            Write-Host (" - Release evidence runId: " + $releaseEvidenceRunId)
            Write-Host (" - Provenance grade: " + $releaseProvenanceGrade)
            Write-Host (" - Traceability satisfied: " + $releaseTraceabilitySatisfied)
        }
        Write-Host " - Subproject zips:"
        foreach ($result in $subprojectResults) {
            $subprojectZipSize = if ($result.PSObject.Properties.Name -contains "ZipSizeBytes") { [long]$result.ZipSizeBytes } else { (Get-Item -LiteralPath $result.ZipPath).Length }
            Write-Host ("   - " + $result.SubprojectName + ": " + $result.ZipPath + " (" + (Format-StateZipSize -ByteCount $subprojectZipSize) + ")")
        }
        Write-Host " - Copied counts:"
        foreach ($key in ($copiedCounts.Keys | Sort-Object)) {
            Write-Host ("   - " + $key + ": " + $copiedCounts[$key])
        }
        Write-Host ""
    }

    return [pscustomobject]@{
        StateName = $stateName
        ZipPath = $zipPath
        ZipSizeBytes = $zipSizeBytes
        ReleaseReadyMode = [bool]$ReleaseReady
        ReleaseReady = [bool]$releaseReadyDecision
        OfficialReleaseEligible = [bool]$officialReleaseEligible
        PackagingMode = $packagingMode
        ReleaseEvidenceStatus = $releaseEvidenceStatus
        ReleaseEvidenceRunId = $releaseEvidenceRunId
        ProvenanceGrade = $releaseProvenanceGrade
        TraceabilitySatisfied = [bool]$releaseTraceabilitySatisfied
        SubprojectZips = @($subprojectResults | ForEach-Object { $_.ZipPath })
    }
}
finally {
    Remove-DirectorySafe -PathValue $stageRoot
}
