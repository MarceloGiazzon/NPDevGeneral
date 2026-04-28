[CmdletBinding()]
param(
    [string]$WorkspaceRoot = "",
    [string]$RunId = "",
    [string]$ReportPath = "",
    [string]$FootprintReportPath = "",
    [string]$BetaReleaseReportPath = "",
    [switch]$PassThru
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

. (Join-Path $PSScriptRoot "..\npdev-common.ps1")

if ([string]::IsNullOrWhiteSpace($WorkspaceRoot)) {
    $WorkspaceRoot = Get-NPDevWorkspaceRoot $PSScriptRoot
}
$WorkspaceRoot = Normalize-NPDevPath $WorkspaceRoot
$RunId = Resolve-NPDevRunId $RunId "runtimehost-final-decision"

if ([string]::IsNullOrWhiteSpace($ReportPath)) {
    $ReportPath = Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\reports\out\runtimehost-final-decision-report.json"
}
else {
    $ReportPath = Normalize-NPDevPath $ReportPath
}

if ([string]::IsNullOrWhiteSpace($FootprintReportPath)) {
    $FootprintReportPath = Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\reports\out\runtime-footprint-report.json"
}
else {
    $FootprintReportPath = Normalize-NPDevPath $FootprintReportPath
}

if ([string]::IsNullOrWhiteSpace($BetaReleaseReportPath)) {
    $BetaReleaseReportPath = Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\reports\out\beta-release-gate-report.json"
}
else {
    $BetaReleaseReportPath = Normalize-NPDevPath $BetaReleaseReportPath
}

$manifestPath = Resolve-NPDevWorkspacePath $WorkspaceRoot "NPDevRuntimeHost\src\main\resources\npdev\runtime-supported-controllers.json"
Ensure-NPDevFile $FootprintReportPath "Runtime footprint report"
Ensure-NPDevFile $manifestPath "Runtime surface manifest"

function Test-RuntimeWildcardMatch([string]$Value, [string[]]$Patterns) {
    foreach ($pattern in $Patterns) {
        $regex = "^" + [Regex]::Escape($pattern).Replace("\*", ".*") + "$"
        if ($Value -match $regex) {
            return $true
        }
    }
    return $false
}

function Get-StringArray([object]$Value) {
    if ($null -eq $Value) {
        return @()
    }
    return @($Value | ForEach-Object {
            $text = [string]$_
            if (-not [string]::IsNullOrWhiteSpace($text)) {
                $text.Trim()
            }
        } | Where-Object { -not [string]::IsNullOrWhiteSpace($_) })
}

function Get-TextReferenceHits([string]$CandidateName) {
    $hits = [System.Collections.Generic.List[object]]::new()
    $searchFiles = @(
        Get-ChildItem -LiteralPath (Resolve-NPDevWorkspacePath $WorkspaceRoot "docs") -File -Recurse -Include "*.md","*.txt" -ErrorAction SilentlyContinue
        Get-ChildItem -LiteralPath $WorkspaceRoot -File -Include "*.md","*.txt" -ErrorAction SilentlyContinue
    )

    foreach ($file in @($searchFiles | Sort-Object FullName -Unique)) {
        foreach ($hit in @(Select-String -LiteralPath $file.FullName -Pattern $CandidateName -SimpleMatch -ErrorAction SilentlyContinue)) {
            [void]$hits.Add([pscustomobject]@{
                    category = "docs"
                    path = Get-NPDevWorkspaceRelativePath $WorkspaceRoot $file.FullName
                    line = $hit.LineNumber
                })
        }
    }

    return @($hits)
}

$footprintReport = Get-Content -LiteralPath $FootprintReportPath -Raw | ConvertFrom-Json -Depth 100
$betaReleaseReport = if (Test-Path -LiteralPath $BetaReleaseReportPath -PathType Leaf) {
    Get-Content -LiteralPath $BetaReleaseReportPath -Raw | ConvertFrom-Json -Depth 100
}
else {
    $null
}
$traceabilitySatisfied = ($null -ne $betaReleaseReport -and
    $betaReleaseReport.PSObject.Properties.Name -contains "traceabilitySatisfied" -and
    [bool]$betaReleaseReport.traceabilitySatisfied)
$officialReleaseEligible = ($null -ne $betaReleaseReport -and
    $betaReleaseReport.PSObject.Properties.Name -contains "officialReleaseEligible" -and
    [bool]$betaReleaseReport.officialReleaseEligible)
$manifest = Get-Content -LiteralPath $manifestPath -Raw | ConvertFrom-Json -Depth 100
$supportedServices = Get-StringArray $footprintReport.footprint.supportedServices
$manifestServicePatterns = @(
    Get-StringArray $manifest.supportedCoreServiceComponents
    Get-StringArray $manifest.supportedCoreServicePatterns
    Get-StringArray $manifest.nonDefaultServicePatterns
    Get-StringArray $manifest.experimentalServicePatterns
)
$candidates = @($footprintReport.footprint.deadRemoveCandidates.services)

$candidateDecisions = @($candidates | ForEach-Object {
        $candidateName = [string]$_.name
        $relativePath = [string]$_.relativePath
        $fullPath = Resolve-NPDevWorkspacePath $WorkspaceRoot $relativePath
        $runtimeManifestMatched = Test-RuntimeWildcardMatch -Value $candidateName -Patterns $manifestServicePatterns
        $directTextReferences = @(Get-TextReferenceHits -CandidateName $candidateName)
        $conditions = [pscustomobject]@{
            present = Test-Path -LiteralPath $fullPath -PathType Leaf
            zeroReference = ([int]$_.referenceHitCount -eq 0)
            internalButNeeded = ([string]$_.expectedBucket -eq "internal-but-needed")
            notSupportedCore = ($supportedServices -notcontains $candidateName)
            notReferencedByRuntimeManifest = (-not $runtimeManifestMatched)
            notReferencedByDocs = ($directTextReferences.Count -eq 0)
        }
        $safeToDelete = (
            $conditions.present -and
            $conditions.zeroReference -and
            $conditions.internalButNeeded -and
            $conditions.notSupportedCore -and
            $conditions.notReferencedByRuntimeManifest -and
            $conditions.notReferencedByDocs
        )

        [pscustomobject]@{
            name = $candidateName
            relativePath = $relativePath
            expectedBucket = [string]$_.expectedBucket
            referenceHitCount = [int]$_.referenceHitCount
            safeToDelete = $safeToDelete
            conditions = $conditions
            references = [pscustomobject]@{
                runtimeManifestPatternMatched = $runtimeManifestMatched
                docs = $directTextReferences
            }
        }
    })

$unsafeCandidates = @($candidateDecisions | Where-Object { -not $_.safeToDelete })
$decision = if (-not $traceabilitySatisfied) {
    "stop-runtimehost-deletion"
}
elseif ($candidates.Count -gt 0 -and $unsafeCandidates.Count -eq 0) {
    "prepare-final-runtimehost-batch"
}
else {
    "stop-runtimehost-deletion"
}

$checks = @(
    (New-NPDevCheckResult "footprint-clean" $(if ([string]$footprintReport.overallStatus -eq "passed") { "passed" } else { "failed" }) $(if ([string]$footprintReport.overallStatus -eq "passed") { "Runtime footprint report is green." } else { "Runtime footprint report is not green." }) @{
            footprintReportPath = Get-NPDevWorkspaceRelativePath $WorkspaceRoot $FootprintReportPath
            overallStatus = [string]$footprintReport.overallStatus
        }),
    (New-NPDevCheckResult "release-traceability" "passed" $(if ($traceabilitySatisfied) { "Official release traceability is satisfied; final deletion may be considered." } else { "Official release traceability is not satisfied; final RuntimeHost deletion remains blocked." }) @{
            betaReleaseReportPath = Get-NPDevWorkspaceRelativePath $WorkspaceRoot $BetaReleaseReportPath
            betaReleaseReportExists = ($null -ne $betaReleaseReport)
            provenanceGrade = if ($null -eq $betaReleaseReport) { $null } else { [string]$betaReleaseReport.provenanceGrade }
            traceabilitySatisfied = $traceabilitySatisfied
            officialReleaseEligible = $officialReleaseEligible
        }),
    (New-NPDevCheckResult "final-deletion-criteria" $(if ($decision -eq "prepare-final-runtimehost-batch") { "passed" } else { "passed" }) $(if ($decision -eq "prepare-final-runtimehost-batch") { "All final RuntimeHost deletion criteria are satisfied." } else { "Final RuntimeHost deletion criteria are not all satisfied; deletion work should stop." }) @{
            unsafeCandidates = @($unsafeCandidates | ForEach-Object { $_.name })
        })
)

$failedChecks = @($checks | Where-Object { $_.status -eq "failed" })
$report = [pscustomobject]@{
    generatedAt = (Get-Date).ToString("o")
    runId = $RunId
    scriptPath = Get-NPDevWorkspaceRelativePath $WorkspaceRoot $PSCommandPath
    workspaceRoot = $WorkspaceRoot
    overallStatus = if ($failedChecks.Count -eq 0) { "passed" } else { "failed" }
    decision = $decision
    decisionReason = if ($decision -eq "prepare-final-runtimehost-batch") {
        "Official release traceability is satisfied and all remaining candidates satisfy the final deletion criteria."
    }
    elseif (-not $traceabilitySatisfied) {
        "Official release traceability is not satisfied, so the optional final RuntimeHost deletion slice must not be prepared."
    }
    else {
        "At least one remaining candidate is still represented by runtime manifest patterns or documentation references, so no final deletion batch should be active."
    }
    traceability = [pscustomobject]@{
        betaReleaseReportPath = Get-NPDevWorkspaceRelativePath $WorkspaceRoot $BetaReleaseReportPath
        betaReleaseReportExists = ($null -ne $betaReleaseReport)
        provenanceGrade = if ($null -eq $betaReleaseReport) { $null } else { [string]$betaReleaseReport.provenanceGrade }
        traceabilitySatisfied = $traceabilitySatisfied
        officialReleaseEligible = $officialReleaseEligible
    }
    candidates = $candidateDecisions
    checks = $checks
    summary = [pscustomobject]@{
        failed = $failedChecks.Count
        warnings = 0
        passed = @($checks | Where-Object { $_.status -eq "passed" }).Count
        total = $checks.Count
    }
}
Write-NPDevJsonFile $ReportPath $report

if ($PassThru) {
    return $report
}

if ($report.overallStatus -eq "passed") {
    Write-NPDevOk ("RuntimeHost final decision recorded: " + $decision)
    return
}

Write-NPDevWarn "RuntimeHost final decision report failed."
throw "RuntimeHost final decision report failed."
