[CmdletBinding()]
param(
    [string]$WorkspaceRoot = "",
    [string]$RunId = "",
    [string]$ReportPath = "",
    [string]$AggregateReportPath = "",
    [string]$EvidenceManifestPath = "",
    [switch]$PassThru
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

. (Join-Path $PSScriptRoot "prioritized-control-common.ps1")

function Get-CommandEvidenceSurfaces {
    param(
        [AllowNull()][object]$Node,
        [string]$Path = "$"
    )

    $surfaces = [System.Collections.Generic.List[object]]::new()

    function Visit-CommandNode {
        param(
            [AllowNull()][object]$CurrentNode,
            [string]$CurrentPath
        )

        if ($null -eq $CurrentNode) {
            return
        }

        if ($CurrentNode -is [string]) {
            return
        }

        if ($CurrentNode -is [System.Collections.IEnumerable]) {
            $index = 0
            foreach ($item in $CurrentNode) {
                Visit-CommandNode -CurrentNode $item -CurrentPath ($CurrentPath + "[" + $index + "]")
                $index++
            }
            return
        }

        if (($CurrentNode -isnot [System.Management.Automation.PSCustomObject]) -and
            ($CurrentNode -isnot [System.Collections.IDictionary])) {
            return
        }

        $propertyNames = if ($CurrentNode -is [System.Collections.IDictionary]) {
            @($CurrentNode.Keys | ForEach-Object { [string]$_ })
        }
        else {
            Get-PrioritizedControlObjectPropertyNames $CurrentNode
        }
        $hasCommandShape = ("display" -in $propertyNames) -or ("executable" -in $propertyNames) -or ("arguments" -in $propertyNames)
        $hasEvidenceSignal = ("exitCode" -in $propertyNames) -or ("outputTail" -in $propertyNames) -or ("failingTaskName" -in $propertyNames) -or ("logPath" -in $propertyNames)
        if ($hasCommandShape -and $hasEvidenceSignal) {
            $outputTailNonEmpty = $false
            if ("outputTail" -in $propertyNames) {
                $outputTailValue = $CurrentNode.outputTail
                $outputTailNonEmpty = if ($null -eq $outputTailValue) {
                    $false
                }
                elseif ($outputTailValue -is [string]) {
                    -not [string]::IsNullOrWhiteSpace($outputTailValue)
                }
                elseif ($outputTailValue -is [System.Collections.IEnumerable]) {
                    @($outputTailValue).Count -gt 0
                }
                else {
                    $true
                }
            }
            [void]$surfaces.Add([pscustomobject]@{
                    path = $CurrentPath
                    propertyNames = $propertyNames
                    exitCodePresent = ("exitCode" -in $propertyNames)
                    outputTailPresent = ("outputTail" -in $propertyNames)
                    outputTailNonEmpty = $outputTailNonEmpty
                    failingTaskNamePresent = ("failingTaskName" -in $propertyNames)
                    logPathPresent = ("logPath" -in $propertyNames)
                    logPath = if ("logPath" -in $propertyNames) { [string]$CurrentNode.logPath } else { $null }
                })
        }

        if ($CurrentNode -is [System.Collections.IDictionary]) {
            foreach ($key in $CurrentNode.Keys) {
                Visit-CommandNode -CurrentNode $CurrentNode[$key] -CurrentPath ($CurrentPath + "." + [string]$key)
            }
            return
        }

        foreach ($property in $CurrentNode.PSObject.Properties) {
            Visit-CommandNode -CurrentNode $property.Value -CurrentPath ($CurrentPath + "." + $property.Name)
        }
    }

    Visit-CommandNode -CurrentNode $Node -CurrentPath $Path
    return @($surfaces)
}

$WorkspaceRoot = Resolve-MaturityWorkspaceRoot -WorkspaceRoot $WorkspaceRoot -ScriptRoot $PSScriptRoot
$RunId = Resolve-NPDevRunId $RunId "b3-evidence-bundle-diagnostics-control"
$ReportPath = Resolve-PrioritizedControlReportPath -WorkspaceRoot $WorkspaceRoot -ReportPath $ReportPath -DefaultRelativePath "scripts\reports\out\prioritized-b3-evidence-bundle-report.json"
$AggregateReportPath = Resolve-Bucket1AggregateReportPath -WorkspaceRoot $WorkspaceRoot -AggregateReportPath $AggregateReportPath
$EvidenceManifestPath = Resolve-Bucket1EvidenceManifestPath -WorkspaceRoot $WorkspaceRoot -AggregateReportPath $AggregateReportPath -EvidenceManifestPath $EvidenceManifestPath

$checks = @()
$aggregateMetadata = Get-MaturityReportMetadata $AggregateReportPath
$aggregateExists = $aggregateMetadata.exists -and [string]::IsNullOrWhiteSpace([string]$aggregateMetadata.parseError)
$aggregateReport = if ($aggregateExists) { Read-MaturityJsonFile $AggregateReportPath } else { $null }
$manifestMetadata = if ([string]::IsNullOrWhiteSpace($EvidenceManifestPath)) {
    [pscustomobject]@{ exists = $false; parseError = $null }
}
else {
    Get-MaturityReportMetadata $EvidenceManifestPath
}
$manifestExists = $manifestMetadata.exists -and [string]::IsNullOrWhiteSpace([string]$manifestMetadata.parseError)
$evidenceManifest = if ($manifestExists) { Read-MaturityJsonFile $EvidenceManifestPath } else { $null }

$checks += New-MaturityCheck `
    -Name "aggregate-and-manifest" `
    -Status $(if ($aggregateExists -and $manifestExists) { "passed" } else { "failed" }) `
    -Expectation "Aggregate report and evidence manifest must both exist and parse successfully." `
    -Summary $(if ($aggregateExists -and $manifestExists) { "Aggregate report and evidence manifest are readable." } else { "Aggregate report or evidence manifest is missing or unreadable." }) `
    -Data @{
        aggregateReportPath = Get-PrioritizedControlEvidencePath -WorkspaceRoot $WorkspaceRoot -PathValue $AggregateReportPath
        evidenceManifestPath = Get-PrioritizedControlEvidencePath -WorkspaceRoot $WorkspaceRoot -PathValue $EvidenceManifestPath
        aggregateParseError = $aggregateMetadata.parseError
        manifestParseError = $manifestMetadata.parseError
    }

$manifestFiles = if ($null -eq $evidenceManifest) { @() } else { @($evidenceManifest.files) }
$manifestEntryAudits = [System.Collections.Generic.List[object]]::new()
foreach ($entry in $manifestFiles) {
    $entrySchema = Test-PrioritizedControlObjectProperties -Value $entry -RequiredProperties @(
        "source",
        "copiedTo",
        "sha256",
        "sourceSha256",
        "sizeBytes"
    )

    $sourcePath = $null
    $copiedPath = $null
    $sourceHashMatches = $false
    $copiedHashMatches = $false
    if ($entrySchema.valid) {
        $sourcePath = Resolve-NPDevWorkspacePath $WorkspaceRoot ([string]$entry.source)
        $copiedPath = Resolve-NPDevWorkspacePath $WorkspaceRoot ([string]$entry.copiedTo)
        if ((Test-Path -LiteralPath $sourcePath -PathType Leaf) -and (Test-Path -LiteralPath $copiedPath -PathType Leaf)) {
            $sourceHashMatches = ((Get-FileHash -LiteralPath $sourcePath -Algorithm SHA256).Hash.ToLowerInvariant() -eq [string]$entry.sourceSha256)
            $copiedHashMatches = ((Get-FileHash -LiteralPath $copiedPath -Algorithm SHA256).Hash.ToLowerInvariant() -eq [string]$entry.sha256)
        }
    }

    [void]$manifestEntryAudits.Add([pscustomobject]@{
            source = if ($null -eq $entry) { $null } else { [string]$entry.source }
            copiedTo = if ($null -eq $entry) { $null } else { [string]$entry.copiedTo }
            schemaValid = $entrySchema.valid
            missingProperties = $entrySchema.missing
            sourceExists = if ($null -eq $sourcePath) { $false } else { Test-Path -LiteralPath $sourcePath -PathType Leaf }
            copiedExists = if ($null -eq $copiedPath) { $false } else { Test-Path -LiteralPath $copiedPath -PathType Leaf }
            sourceHashMatches = $sourceHashMatches
            copiedHashMatches = $copiedHashMatches
        })
}

$checks += New-MaturityCheck `
    -Name "manifest-entry-schema" `
    -Status $(if ($manifestFiles.Count -gt 0 -and @($manifestEntryAudits | Where-Object { -not $_.schemaValid }).Count -eq 0) { "passed" } else { "failed" }) `
    -Expectation "Every evidence-manifest entry must include source, copiedTo, sha256, sourceSha256, and sizeBytes." `
    -Summary $(if ($manifestFiles.Count -gt 0 -and @($manifestEntryAudits | Where-Object { -not $_.schemaValid }).Count -eq 0) { "Every evidence-manifest entry includes the required fields." } else { "One or more evidence-manifest entries are missing required fields." }) `
    -Data @{
        invalidEntries = @($manifestEntryAudits | Where-Object { -not $_.schemaValid })
    }

$checks += New-MaturityCheck `
    -Name "manifest-hash-completeness" `
    -Status $(if ($manifestFiles.Count -gt 0 -and @($manifestEntryAudits | Where-Object { -not $_.sourceExists -or -not $_.copiedExists -or -not $_.sourceHashMatches -or -not $_.copiedHashMatches }).Count -eq 0) { "passed" } else { "failed" }) `
    -Expectation "Each evidence-manifest entry must point to real files whose hashes match the recorded values." `
    -Summary $(if ($manifestFiles.Count -gt 0 -and @($manifestEntryAudits | Where-Object { -not $_.sourceExists -or -not $_.copiedExists -or -not $_.sourceHashMatches -or -not $_.copiedHashMatches }).Count -eq 0) { "Evidence-manifest entries point to real files with matching hashes." } else { "One or more evidence-manifest entries are missing files or have mismatched hashes." }) `
    -Data @{
        invalidEntries = @($manifestEntryAudits | Where-Object { -not $_.sourceExists -or -not $_.copiedExists -or -not $_.sourceHashMatches -or -not $_.copiedHashMatches })
    }

$stepReportSources = if ($null -eq $aggregateReport) {
    @()
}
else {
    @($aggregateReport.steps | Where-Object { -not [string]::IsNullOrWhiteSpace([string]$_.reportPath) } | ForEach-Object { [string]$_.reportPath })
}
$manifestSources = @($manifestFiles | ForEach-Object { [string]$_.source })
$missingChildReports = @($stepReportSources | Where-Object { $_ -notin $manifestSources })
$aggregateReportSource = "scripts\reports\out\beta-release-gate-report.json"
$aggregateInManifest = $aggregateReportSource -in $manifestSources

$checks += New-MaturityCheck `
    -Name "bundle-inclusion" `
    -Status $(if ($aggregateInManifest -and @($missingChildReports).Count -eq 0) { "passed" } else { "failed" }) `
    -Expectation "Evidence bundle must include the aggregate report and every child report consumed by the aggregate gate." `
    -Summary $(if ($aggregateInManifest -and @($missingChildReports).Count -eq 0) { "Evidence bundle includes the aggregate report and every child report consumed by the aggregate gate." } else { "Evidence bundle is missing the aggregate report or one or more child reports consumed by the aggregate gate." }) `
    -Data @{
        aggregateInManifest = $aggregateInManifest
        missingChildReports = $missingChildReports
    }

$childReportEvidenceAudits = [System.Collections.Generic.List[object]]::new()
foreach ($step in @(if ($null -eq $aggregateReport) { @() } else { @($aggregateReport.steps) })) {
    if ([string]::IsNullOrWhiteSpace([string]$step.reportPath)) {
        continue
    }

    $childReportPath = Resolve-NPDevWorkspacePath $WorkspaceRoot ([string]$step.reportPath)
    if (-not (Test-Path -LiteralPath $childReportPath -PathType Leaf)) {
        continue
    }

    $childReport = Read-MaturityJsonFile $childReportPath
    $surfaces = @(Get-CommandEvidenceSurfaces -Node $childReport)
    foreach ($surface in $surfaces) {
        $requiresLogPath = $surface.path.EndsWith(".verificationCommand", [System.StringComparison]::OrdinalIgnoreCase)
        [void]$childReportEvidenceAudits.Add([pscustomobject]@{
                stepName = [string]$step.name
                reportPath = [string]$step.reportPath
                surfacePath = $surface.path
                exitCodePresent = $surface.exitCodePresent
                outputTailPresent = $surface.outputTailPresent
                failingTaskNamePresent = $surface.failingTaskNamePresent
                outputTailNonEmpty = $surface.outputTailNonEmpty
                requiresLogPath = $requiresLogPath
                logPathPresent = $surface.logPathPresent
                logPath = $surface.logPath
                logPathNonEmpty = if ($surface.logPathPresent) { -not [string]::IsNullOrWhiteSpace([string]$surface.logPath) } else { $false }
            })
    }
}

$invalidCommandSurfaces = @($childReportEvidenceAudits | Where-Object {
        -not $_.exitCodePresent -or
        -not $_.outputTailPresent -or
        -not $_.outputTailNonEmpty -or
        -not $_.failingTaskNamePresent -or
        ($_.requiresLogPath -and (-not $_.logPathPresent -or -not $_.logPathNonEmpty))
    })
$checks += New-MaturityCheck `
    -Name "failure-evidence-surfaces" `
    -Status $(if ($childReportEvidenceAudits.Count -gt 0 -and @($invalidCommandSurfaces).Count -eq 0) { "passed" } else { "failed" }) `
    -Expectation "Child reports must expose machine-readable command evidence with exitCode, outputTail, failingTaskName, and logPath where the verification report format requires it." `
    -Summary $(if ($childReportEvidenceAudits.Count -gt 0 -and @($invalidCommandSurfaces).Count -eq 0) { "Child reports expose machine-readable command evidence surfaces." } else { "One or more child report command evidence surfaces are incomplete." }) `
    -Data @{
        evidenceSurfaceCount = $childReportEvidenceAudits.Count
        invalidSurfaces = $invalidCommandSurfaces
    }

$report = Write-PrioritizedControlReport `
    -WorkspaceRoot $WorkspaceRoot `
    -RunId $RunId `
    -ScriptPath $PSCommandPath `
    -Bucket "B1" `
    -ControlId "B3-EVIDENCE-BUNDLE-DIAGNOSTICS" `
    -ReportPath $ReportPath `
    -EvidencePaths @(
        Get-PrioritizedControlEvidencePath -WorkspaceRoot $WorkspaceRoot -PathValue $AggregateReportPath
        Get-PrioritizedControlEvidencePath -WorkspaceRoot $WorkspaceRoot -PathValue $EvidenceManifestPath
        @($stepReportSources)
    ) `
    -Checks $checks `
    -Extra @{
        aggregateReportPath = Get-PrioritizedControlEvidencePath -WorkspaceRoot $WorkspaceRoot -PathValue $AggregateReportPath
        evidenceManifestPath = Get-PrioritizedControlEvidencePath -WorkspaceRoot $WorkspaceRoot -PathValue $EvidenceManifestPath
        manifestEntryCount = $manifestEntryAudits.Count
        commandEvidenceSurfaceCount = $childReportEvidenceAudits.Count
    }

Complete-PrioritizedControlScript -Report $report -PassThru:$PassThru
