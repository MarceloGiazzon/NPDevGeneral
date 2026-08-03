param(
    [string]$RunId = "",
    [string]$ReportPath = "scripts/reports/out/doc-entrypoint-validation-report.json",
    [string]$ClassificationPolicyPath = "scripts/policy/doc-entrypoint-classification-policy.json",
    [string[]]$DocumentPaths = @(),
    [string[]]$AdditionalDocumentPaths = @()
)

$ErrorActionPreference = "Stop"

function Normalize-DocValidationPath {
    param([string]$PathValue)
    return ([string]$PathValue).Trim().Trim("'").Trim('"') -replace "\\", "/"
}

function Resolve-RepoPath {
    param([string]$PathValue)
    if ([System.IO.Path]::IsPathRooted($PathValue)) {
        return [System.IO.Path]::GetFullPath($PathValue)
    }
    return [System.IO.Path]::GetFullPath((Join-Path $script:workspaceRoot $PathValue))
}

function Get-RepoRelativePath {
    param([string]$FullPath)
    return ([System.IO.Path]::GetRelativePath($script:workspaceRoot, [System.IO.Path]::GetFullPath($FullPath)) -replace "\\", "/")
}

function Test-ExcludedMarkdownPath {
    param([string]$FullPath)
    $relative = Get-RepoRelativePath $FullPath
    $excludedSegments = @(
        ".git",
        ".gradle",
        ".cache",
        "build",
        "coverage",
        "dist",
        "generated",
        "node_modules",
        "out",
        "target",
        "vendor"
    )
    foreach ($segment in @($relative -split "/")) {
        if ($excludedSegments -contains $segment) {
            return $true
        }
    }
    return $false
}

function Add-UniqueDocumentPath {
    param(
        [System.Collections.Generic.List[string]]$Documents,
        [hashtable]$Seen,
        [string]$PathValue
    )
    if ([string]::IsNullOrWhiteSpace($PathValue)) {
        return
    }
    $fullPath = Resolve-RepoPath $PathValue
    if (-not (Test-Path -LiteralPath $fullPath -PathType Leaf)) {
        return
    }
    if (Test-ExcludedMarkdownPath $fullPath) {
        return
    }
    $key = [System.IO.Path]::GetFullPath($fullPath).ToLowerInvariant()
    if (-not $Seen.ContainsKey($key)) {
        $Seen[$key] = $true
        $Documents.Add($fullPath) | Out-Null
    }
}

function Get-DocumentsToScan {
    $documents = [System.Collections.Generic.List[string]]::new()
    $seen = @{}

    if ($DocumentPaths.Count -gt 0) {
        foreach ($documentPath in @($DocumentPaths)) {
            Add-UniqueDocumentPath $documents $seen $documentPath
        }
        return @($documents)
    }

    foreach ($rootDoc in @("README.md", "PROJECT_DIGEST.md")) {
        Add-UniqueDocumentPath $documents $seen $rootDoc
    }

    $docsRoot = Resolve-RepoPath "docs"
    if (Test-Path -LiteralPath $docsRoot -PathType Container) {
        foreach ($doc in @(Get-ChildItem -LiteralPath $docsRoot -Recurse -File -Filter "*.md" -ErrorAction SilentlyContinue | Sort-Object FullName)) {
            Add-UniqueDocumentPath $documents $seen $doc.FullName
        }
    }

    foreach ($documentPath in @($AdditionalDocumentPaths)) {
        Add-UniqueDocumentPath $documents $seen $documentPath
    }

    return @($documents)
}

function New-ReferenceFinding {
    param(
        [string]$Document,
        [int]$LineNumber,
        [string]$ReferencePath,
        [string]$NormalizedPath
    )
    return [ordered]@{
        document = $Document
        lineNumber = $LineNumber
        referencePath = $ReferencePath
        normalizedPath = $NormalizedPath
    }
}

function Read-JsonFile {
    param([string]$Path)
    return Get-Content -Raw -LiteralPath $Path | ConvertFrom-Json
}

function Read-ClassificationPolicy {
    param([string]$Path)
    $fullPath = Resolve-RepoPath $Path
    if (-not (Test-Path -LiteralPath $fullPath -PathType Leaf)) {
        throw "Doc entrypoint classification policy is missing: $Path"
    }
    $policy = Read-JsonFile $fullPath
    if ([string]$policy.schemaVersion -ne "npdev-doc-entrypoint-classification-policy.v1") {
        throw "Doc entrypoint classification policy has unsupported schemaVersion: " + [string]$policy.schemaVersion
    }
    return $policy
}

function Add-ReportMapping {
    param(
        [hashtable]$Mappings,
        [string]$ReportPathValue,
        [string[]]$ProducerScripts = @(),
        [string[]]$Consumers = @(),
        [string[]]$PlannedConsumers = @(),
        [string[]]$ScriptReferences = @(),
        [string]$Classification = "known-report",
        [string]$Reason = "Report path has a known producer or consumer.",
        [bool]$BlockingWhenProducerMissing = $false
    )

    $key = Normalize-DocValidationPath $ReportPathValue
    if (-not $Mappings.ContainsKey($key)) {
        $Mappings[$key] = [ordered]@{
            reportPath = $key
            producerScripts = [System.Collections.Generic.List[string]]::new()
            consumers = [System.Collections.Generic.List[string]]::new()
            plannedConsumers = [System.Collections.Generic.List[string]]::new()
            scriptReferences = [System.Collections.Generic.List[string]]::new()
            classification = $Classification
            reason = $Reason
            blockingWhenProducerMissing = $BlockingWhenProducerMissing
        }
    }

    $mapping = $Mappings[$key]
    foreach ($producer in @($ProducerScripts)) {
        $value = Normalize-DocValidationPath $producer
        if (-not [string]::IsNullOrWhiteSpace($value) -and -not $mapping.producerScripts.Contains($value)) {
            $mapping.producerScripts.Add($value) | Out-Null
        }
    }
    foreach ($consumer in @($Consumers)) {
        $value = Normalize-DocValidationPath $consumer
        if (-not [string]::IsNullOrWhiteSpace($value) -and -not $mapping.consumers.Contains($value)) {
            $mapping.consumers.Add($value) | Out-Null
        }
    }
    foreach ($plannedConsumer in @($PlannedConsumers)) {
        $value = Normalize-DocValidationPath $plannedConsumer
        if (-not [string]::IsNullOrWhiteSpace($value) -and -not $mapping.plannedConsumers.Contains($value)) {
            $mapping.plannedConsumers.Add($value) | Out-Null
        }
    }
    foreach ($scriptReference in @($ScriptReferences)) {
        $value = Normalize-DocValidationPath $scriptReference
        if (-not [string]::IsNullOrWhiteSpace($value) -and -not $mapping.scriptReferences.Contains($value)) {
            $mapping.scriptReferences.Add($value) | Out-Null
        }
    }

    $protectedClassification = [string]$mapping.classification -in @(
        "blocking-deferred-report",
        "blocking-planned-report",
        "future-non-release-report"
    )
    if ($Classification -ne "known-report" -and -not $protectedClassification) {
        $mapping.classification = $Classification
        $mapping.reason = $Reason
    }
    if ($BlockingWhenProducerMissing) {
        $mapping.blockingWhenProducerMissing = $true
    }
}

function New-ReportMappings {
    param([object]$ClassificationPolicy)
    $mappings = @{}

    foreach ($reportClassification in @($ClassificationPolicy.reportClassifications)) {
        Add-ReportMapping $mappings ([string]$reportClassification.reportPath) `
            -ProducerScripts @($reportClassification.producerScripts) `
            -Consumers @($reportClassification.consumers) `
            -PlannedConsumers @($reportClassification.plannedConsumers) `
            -Classification ([string]$reportClassification.classification) `
            -Reason ([string]$reportClassification.reason) `
            -BlockingWhenProducerMissing ([bool]$reportClassification.blockingWhenProducerMissing)
    }

    $policyPath = Resolve-RepoPath "scripts/policy/beta-release-gate-policy.json"
    if (Test-Path -LiteralPath $policyPath -PathType Leaf) {
        try {
            $policy = Get-Content -Raw -LiteralPath $policyPath | ConvertFrom-Json
            foreach ($required in @($policy.requiredReports)) {
                if (-not [string]::IsNullOrWhiteSpace([string]$required.path)) {
                    Add-ReportMapping $mappings ([string]$required.path) `
                        -Consumers @("scripts/policy/beta-release-gate-policy.json") `
                        -Classification "release-policy-report" `
                        -Reason "Report is declared in beta-release-gate-policy.json."
                }
            }
            foreach ($informational in @($policy.informationalReports)) {
                if (-not [string]::IsNullOrWhiteSpace([string]$informational)) {
                    Add-ReportMapping $mappings ([string]$informational) `
                        -Consumers @("scripts/policy/beta-release-gate-policy.json") `
                        -Classification "release-policy-informational-report" `
                        -Reason "Report is declared as informational in beta-release-gate-policy.json."
                }
            }
        }
        catch {
        }
    }

    $reportPattern = [regex]"scripts[\\/]+reports[\\/]+out[\\/]+[A-Za-z0-9_./\\-]+\.json"
    foreach ($scriptFile in @(Get-ChildItem -LiteralPath (Resolve-RepoPath "scripts") -Recurse -File -Filter "*.ps1" -ErrorAction SilentlyContinue)) {
        $scriptRelative = Get-RepoRelativePath $scriptFile.FullName
        foreach ($line in @(Get-Content -LiteralPath $scriptFile.FullName -ErrorAction SilentlyContinue)) {
            foreach ($match in $reportPattern.Matches([string]$line)) {
                Add-ReportMapping $mappings (Normalize-DocValidationPath $match.Value) `
                    -ScriptReferences @($scriptRelative) `
                    -Classification "script-mapped-report" `
                    -Reason "Report path is referenced by a repository script."
            }
        }
    }

    return $mappings
}

function Get-ScriptClassification {
    param(
        [string]$NormalizedPath,
        [object]$ClassificationPolicy
    )
    $matchingClassifications = @($ClassificationPolicy.scriptClassifications | Where-Object {
            (Normalize-DocValidationPath ([string]$_.path)) -eq $NormalizedPath
        } | Select-Object -First 1)

    if ($matchingClassifications.Count -gt 0) {
        $matchingClassification = $matchingClassifications[0]
        return [pscustomobject]@{
            classification = [string]$matchingClassification.classification
            releaseRelevant = [bool]$matchingClassification.releaseRelevant
            plannedResolutionItem = if ($null -ne $matchingClassification.plannedResolutionItem) { [int]$matchingClassification.plannedResolutionItem } else { $null }
            reason = [string]$matchingClassification.reason
        }
    }

    $defaultClassification = $ClassificationPolicy.defaultScriptClassification
    return [pscustomobject]@{
        classification = [string]$defaultClassification.classification
        releaseRelevant = [bool]$defaultClassification.releaseRelevant
        plannedResolutionItem = if ($null -ne $defaultClassification.plannedResolutionItem) { [int]$defaultClassification.plannedResolutionItem } else { $null }
        reason = [string]$defaultClassification.reason
    }
}

function Convert-PolicyPatternToRegex {
    param([string]$Pattern)
    $escaped = [regex]::Escape((Normalize-DocValidationPath $Pattern))
    $escaped = $escaped -replace "\\\*\\\*/", "(?:.*/)?"
    $escaped = $escaped -replace "\\\*", "[^/]*"
    return "^" + $escaped + "$"
}

function Get-DocumentClassification {
    param(
        [string]$NormalizedPath,
        [object]$ClassificationPolicy
    )

    foreach ($documentClassification in @($ClassificationPolicy.documentClassifications)) {
        $pattern = [string]$documentClassification.pathPattern
        if ([string]::IsNullOrWhiteSpace($pattern)) {
            continue
        }
        if ($NormalizedPath -match (Convert-PolicyPatternToRegex $pattern)) {
            return [pscustomobject]@{
                classification = [string]$documentClassification.classification
                releaseRelevant = [bool]$documentClassification.releaseRelevant
                reason = [string]$documentClassification.reason
                pathPattern = Normalize-DocValidationPath $pattern
            }
        }
    }

    return $null
}

function Get-ReportReferenceClassification {
    param(
        [string]$NormalizedPath,
        [hashtable]$Mappings
    )

    if (-not $Mappings.ContainsKey($NormalizedPath)) {
        return [pscustomobject]@{
            mappingStatus = "unmapped"
            classification = "unmapped-report-reference"
            blocking = $true
            reason = "No known script or policy maps this report reference."
            producerScripts = @()
            consumers = @()
            plannedConsumers = @()
            scriptReferences = @()
            missingProducerScripts = @()
        }
    }

    $mapping = $Mappings[$NormalizedPath]
    $missingProducerScripts = @()
    foreach ($producer in @($mapping.producerScripts)) {
        if (-not (Test-Path -LiteralPath (Resolve-RepoPath $producer) -PathType Leaf)) {
            $missingProducerScripts += [string]$producer
        }
    }

    $blocking = [bool]$mapping.blockingWhenProducerMissing -and $missingProducerScripts.Count -gt 0
    $mappingStatus = if ($blocking) {
        "known-producer-missing"
    }
    elseif ([string]$mapping.classification -eq "future-non-release-report") {
        "future-non-release"
    }
    else {
        "mapped"
    }

    $reason = if ($blocking) {
        [string]$mapping.reason + " Missing producer script(s): " + ($missingProducerScripts -join ", ")
    }
    else {
        [string]$mapping.reason
    }

    return [pscustomobject]@{
        mappingStatus = $mappingStatus
        classification = [string]$mapping.classification
        blocking = $blocking
        reason = $reason
        producerScripts = @($mapping.producerScripts)
        consumers = @($mapping.consumers)
        plannedConsumers = @($mapping.plannedConsumers)
        scriptReferences = @($mapping.scriptReferences)
        missingProducerScripts = $missingProducerScripts
    }
}

$workspaceRoot = (Resolve-Path ".").Path
if ([string]::IsNullOrWhiteSpace($RunId)) {
    $RunId = "doc-entrypoint-validation-" + (Get-Date).ToUniversalTime().ToString("yyyyMMdd-HHmmssfff")
}

    # REG-123: the bare "scripts[\\/]+...\.ps1" pattern has no anchor, so a doc reference to a
    # script living under a NESTED "scripts/" directory (e.g. "NPDevSamples/scripts/generate-sample-app.ps1",
    # the real current location of several scripts moved out of this repo's own top-level scripts/
    # tree) only ever matches its OWN "scripts/...ps1" suffix -- silently dropping the "NPDevSamples/"
    # prefix -- and then resolves that truncated, wrong path against the REPO ROOT, where no such file
    # exists. No doc-text fix can satisfy this: correcting a stale reference to the accurate
    # "NPDevSamples/scripts/..." path still only ever gets checked as bare "scripts/...", which is
    # exactly as wrong as the ORIGINAL stale reference was. The leading `(?:[A-Za-z0-9_.\-]+[\\/])*`
    # lets the match grow backward through real path segments when a longer prefix is present, while
    # leaving every existing bare "scripts/...ps1" reference (zero-length prefix) matched exactly as
    # before.
$scriptPattern = [regex]"(?:[A-Za-z0-9_.\-]+[\\/])*(?<core>scripts[\\/]+[A-Za-z0-9_./\\-]+\.ps1)"
$reportPattern = [regex]"scripts[\\/]+reports[\\/]+out[\\/]+[A-Za-z0-9_./\\-]+\.json"
$classificationPolicy = Read-ClassificationPolicy $ClassificationPolicyPath
$documentsToScan = @(Get-DocumentsToScan | Sort-Object)
$reportMappings = New-ReportMappings $classificationPolicy
$failures = [System.Collections.Generic.List[string]]::new()
$documentClassifications = @()
$scriptEntrypoints = @()
$reportReferences = @()

foreach ($docFullPath in $documentsToScan) {
    $documentRelative = Get-RepoRelativePath $docFullPath
    $documentNormalized = Normalize-DocValidationPath $documentRelative
    if ($documentNormalized.StartsWith("docs/") -and $documentNormalized.EndsWith(".md")) {
        $documentClassification = Get-DocumentClassification $documentNormalized $classificationPolicy
        if ($null -eq $documentClassification) {
            $failures.Add($documentRelative + " is not classified by " + $ClassificationPolicyPath) | Out-Null
            $documentClassifications += [pscustomobject]@{
                document = $documentRelative
                classification = "unclassified"
                blocking = $true
                reason = "docs/**/*.md files must be classified."
            }
        }
        else {
            $documentClassifications += [pscustomobject]@{
                document = $documentRelative
                classification = [string]$documentClassification.classification
                releaseRelevant = [bool]$documentClassification.releaseRelevant
                blocking = $false
                reason = [string]$documentClassification.reason
                pathPattern = [string]$documentClassification.pathPattern
            }
        }
    }
    $lines = @(Get-Content -LiteralPath $docFullPath)
    for ($index = 0; $index -lt $lines.Count; $index++) {
        $line = [string]$lines[$index]
        $lineNumber = $index + 1

        foreach ($match in $scriptPattern.Matches($line)) {
            $referencePath = [string]$match.Value
            $normalizedPath = Normalize-DocValidationPath $referencePath
            $exists = Test-Path -LiteralPath (Resolve-RepoPath $normalizedPath) -PathType Leaf
            # REG-123: the greedy leading-path-segment prefix above lets a NESTED "scripts/" reference
            # (e.g. "NPDevSamples/scripts/generate-sample-app.ps1") match in full instead of losing its
            # prefix -- but the SAME greediness over-captures when a doc instead writes a full absolute
            # path whose ancestor directories happen to look like path segments too (e.g.
            # "D:\WorkSpace\NPDev\NPDev_General\scripts\...", where the colon after the drive letter
            # breaks the match, but "WorkSpace/NPDev/NPDev_General/" still gets swept into the captured
            # prefix, which is not a real repo-relative path). Falling back to the inner "core" group
            # (the un-prefixed "scripts/...ps1" tail) covers that case: a reference exists if EITHER
            # interpretation resolves to a real file.
            if (-not $exists -and $match.Groups["core"].Success) {
                $corePath = Normalize-DocValidationPath $match.Groups["core"].Value
                if ($corePath -ne $normalizedPath -and (Test-Path -LiteralPath (Resolve-RepoPath $corePath) -PathType Leaf)) {
                    $exists = $true
                    $normalizedPath = $corePath
                }
            }
            $classification = Get-ScriptClassification $normalizedPath $classificationPolicy
            $blocking = -not $exists -and [bool]$classification.releaseRelevant
            $reason = if ($exists) { "Referenced script exists." } else { [string]$classification.reason }
            if ($blocking) {
                $failures.Add($documentRelative + ":" + $lineNumber + " references missing release-relevant script " + $normalizedPath) | Out-Null
            }
            $finding = New-ReferenceFinding $documentRelative $lineNumber $referencePath $normalizedPath
            $finding["exists"] = $exists
            $finding["classification"] = [string]$classification.classification
            $finding["blocking"] = $blocking
            $finding["plannedResolutionItem"] = $classification.plannedResolutionItem
            $finding["reason"] = $reason
            $scriptEntrypoints += [pscustomobject]$finding
        }

        foreach ($match in $reportPattern.Matches($line)) {
            $referencePath = [string]$match.Value
            $normalizedPath = Normalize-DocValidationPath $referencePath
            $classification = Get-ReportReferenceClassification $normalizedPath $reportMappings
            if ([bool]$classification.blocking) {
                $failures.Add($documentRelative + ":" + $lineNumber + " references blocking report with unresolved mapping " + $normalizedPath + ": " + [string]$classification.reason) | Out-Null
            }
            $finding = New-ReferenceFinding $documentRelative $lineNumber $referencePath $normalizedPath
            $finding["mappingStatus"] = [string]$classification.mappingStatus
            $finding["classification"] = [string]$classification.classification
            $finding["blocking"] = [bool]$classification.blocking
            $finding["reason"] = [string]$classification.reason
            $finding["producerScripts"] = @($classification.producerScripts)
            $finding["consumers"] = @($classification.consumers)
            $finding["plannedConsumers"] = @($classification.plannedConsumers)
            $finding["scriptReferences"] = @($classification.scriptReferences)
            $finding["missingProducerScripts"] = @($classification.missingProducerScripts)
            $reportReferences += [pscustomobject]$finding
        }
    }
}

$missingScripts = @($scriptEntrypoints | Where-Object { -not [bool]$_.exists })
$blockingMissingScripts = @($missingScripts | Where-Object { [bool]$_.blocking })
$futureOrNonReleaseReferences = @($scriptEntrypoints | Where-Object { [string]$_.classification -eq "future-non-release" })
$unmappedReports = @($reportReferences | Where-Object { [string]$_.mappingStatus -eq "unmapped" })
$blockingReportReferences = @($reportReferences | Where-Object { [bool]$_.blocking })
$unclassifiedDocuments = @($documentClassifications | Where-Object { [string]$_.classification -eq "unclassified" })

$status = if ($failures.Count -eq 0) { "passed" } else { "failed" }
$report = [pscustomobject]@{
    schemaVersion = "npdev-doc-entrypoint-validation-report.v1"
    runId = $RunId
    generatedAt = (Get-Date).ToUniversalTime().ToString("o")
    scriptPath = "scripts/quality/run-doc-entrypoint-validation.ps1"
    workspaceRoot = $workspaceRoot
    classificationPolicyPath = $ClassificationPolicyPath
    classificationPolicySchemaVersion = [string]$classificationPolicy.schemaVersion
    overallStatus = $status
    documentDiscoveryMode = if ($DocumentPaths.Count -gt 0) { "explicit" } else { "root-readme-project-digest-and-docs" }
    documentsScanned = $documentsToScan.Count
    documents = @($documentsToScan | ForEach-Object { Get-RepoRelativePath $_ })
    documentClassifications = $documentClassifications
    unclassifiedDocuments = $unclassifiedDocuments
    scriptEntrypoints = $scriptEntrypoints
    reportReferences = $reportReferences
    missingScripts = $missingScripts
    blockingMissingScripts = $blockingMissingScripts
    futureOrNonReleaseReferences = $futureOrNonReleaseReferences
    unmappedReports = $unmappedReports
    blockingReportReferences = $blockingReportReferences
    failures = @($failures)
}

$reportPathFull = Resolve-RepoPath $ReportPath
New-Item -ItemType Directory -Force -Path (Split-Path -Parent $reportPathFull) | Out-Null
$report | ConvertTo-Json -Depth 30 | Set-Content -LiteralPath $reportPathFull -Encoding UTF8

if ($status -eq "passed") {
    Write-Host ("Doc entrypoint validation passed. Report: " + $ReportPath)
    exit 0
}

Write-Error ("Doc entrypoint validation failed. Report: " + $ReportPath)
