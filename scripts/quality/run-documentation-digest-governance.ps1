[CmdletBinding()]
param(
    [string]$WorkspaceRoot = "",
    [string]$RunId = "",
    [string]$ReportPath = "",
    [int]$FreshnessDays = 180,
    [switch]$PassThru
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

. (Join-Path $PSScriptRoot "..\npdev-common.ps1")

if ([string]::IsNullOrWhiteSpace($WorkspaceRoot)) {
    $WorkspaceRoot = Get-NPDevWorkspaceRoot $PSScriptRoot
}
$WorkspaceRoot = Normalize-NPDevPath $WorkspaceRoot
$RunId = Resolve-NPDevRunId $RunId "documentation-digest-governance"

if ([string]::IsNullOrWhiteSpace($ReportPath)) {
    $ReportPath = Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\reports\out\documentation-digest-governance-report.json"
}
else {
    $ReportPath = Normalize-NPDevPath $ReportPath
}

function Resolve-DocReferencePath {
    param(
        [string]$DigestPath,
        [string]$Reference
    )

    if ([string]::IsNullOrWhiteSpace($Reference)) {
        return $null
    }
    if ($Reference -match '^[a-z]+://') {
        return $null
    }

    $normalizedReference = $Reference.Replace("/", "\")
    if ([System.IO.Path]::IsPathRooted($normalizedReference)) {
        return Normalize-NPDevPath $normalizedReference
    }

    $digestDirectory = Split-Path -Parent $DigestPath
    $relativeToDigest = Normalize-NPDevPath (Join-Path $digestDirectory $normalizedReference)
    if (Test-Path -LiteralPath $relativeToDigest) {
        return $relativeToDigest
    }

    return Resolve-NPDevWorkspacePath $WorkspaceRoot $normalizedReference
}

function Get-DocumentReferences {
    param(
        [string]$DigestPath
    )

    $content = Get-Content -LiteralPath $DigestPath -Raw
    $references = [System.Collections.Generic.List[string]]::new()
    foreach ($match in [regex]::Matches($content, '\[[^\]]+\]\((?<path>[^)]+)\)')) {
        $pathValue = [string]$match.Groups["path"].Value.Trim()
        if (-not [string]::IsNullOrWhiteSpace($pathValue)) {
            [void]$references.Add($pathValue)
        }
    }
    function Test-QualifiedBacktickReference {
        param(
            [string]$PathValue
        )

        if ([string]::IsNullOrWhiteSpace($PathValue)) {
            return $false
        }

        return ($PathValue -match '^[.]{1,2}[\\/]' -or $PathValue -match '[\\/]')
    }

    foreach ($match in [regex]::Matches($content, '`(?<path>[A-Za-z0-9_./\\-]+\.(?:md|ps1|json|java|bat|gradle|kts))`')) {
        $pathValue = [string]$match.Groups["path"].Value.Trim()
        if ((-not [string]::IsNullOrWhiteSpace($pathValue)) -and (Test-QualifiedBacktickReference -PathValue $pathValue)) {
            [void]$references.Add($pathValue)
        }
    }

    return @($references | Select-Object -Unique)
}

function Get-FreshnessMarker {
    param(
        [string]$DigestPath
    )

    $content = Get-Content -LiteralPath $DigestPath -Raw
    foreach ($pattern in @(
            '(?im)^\s*(?:Freshness|Last Reviewed|Reviewed At|ReviewedOn|Reviewed At UTC|reviewedAt)\s*:\s*(?<value>[0-9]{4}-[0-9]{2}-[0-9]{2}(?:[T ][^ \r\n]+)?)\s*$',
            '(?im)^\s*<!--\s*freshness\s*:\s*(?<value>[0-9]{4}-[0-9]{2}-[0-9]{2}(?:[T ][^ \r\n]+)?)\s*-->\s*$'
        )) {
        $match = [regex]::Match($content, $pattern)
        if ($match.Success) {
            return [string]$match.Groups["value"].Value
        }
    }

    return $null
}

$requiredProjectDigests = @(
    ".npdev-root",
    "NPDevContract\.npdev-root",
    "NPDevEditor\.npdev-root",
    "NPDevGenerator\.npdev-root",
    "NPDevKernel\.npdev-root",
    "NPDevRuntimeHost\.npdev-root",
    "NPDevSamples\.npdev-root"
)
$releaseSampleIds = Get-NPDevReleaseSampleIds $WorkspaceRoot
$requiredGeneratedDigests = @($releaseSampleIds | ForEach-Object {
        "NPDevSamples\" + $_ + "\Output\App\.npdev-root"
    })
$requiredGeneratedMigrationDigests = @($releaseSampleIds | ForEach-Object {
        "NPDevSamples\" + $_ + "\Output\App\MIGRATION_DIGEST.md"
    })

$digestAudits = [System.Collections.Generic.List[object]]::new()
$missingRequiredProjectDigests = [System.Collections.Generic.List[string]]::new()
$missingGeneratedMigrationDigests = [System.Collections.Generic.List[string]]::new()
$brokenReferences = [System.Collections.Generic.List[object]]::new()
$freshnessWarnings = [System.Collections.Generic.List[object]]::new()

foreach ($relativeDigestPath in @($requiredProjectDigests + $requiredGeneratedDigests + $requiredGeneratedMigrationDigests | Select-Object -Unique)) {
    $digestPath = Resolve-NPDevWorkspacePath $WorkspaceRoot $relativeDigestPath
    $exists = Test-Path -LiteralPath $digestPath -PathType Leaf
    $references = @()
    $referenceAudits = @()
    $freshnessMarker = $null
    $freshnessStatus = "not-declared"
    $freshnessAgeDays = $null

    if ($exists) {
        $references = Get-DocumentReferences -DigestPath $digestPath
        foreach ($reference in $references) {
            $resolvedReference = Resolve-DocReferencePath -DigestPath $digestPath -Reference $reference
            $isBroken = ($null -ne $resolvedReference) -and (-not (Test-Path -LiteralPath $resolvedReference))
            $referenceAudit = [pscustomobject]@{
                reference = $reference
                resolvedPath = if ($null -eq $resolvedReference) { $null } else { Get-NPDevWorkspaceRelativePath $WorkspaceRoot $resolvedReference }
                exists = (-not $isBroken)
            }
            $referenceAudits += $referenceAudit
            if ($isBroken) {
                [void]$brokenReferences.Add([pscustomobject]@{
                        digestPath = $relativeDigestPath
                        reference = $reference
                        resolvedPath = $referenceAudit.resolvedPath
                    })
            }
        }

        $freshnessMarker = Get-FreshnessMarker -DigestPath $digestPath
        if (-not [string]::IsNullOrWhiteSpace($freshnessMarker)) {
            try {
                $freshnessDate = [datetimeoffset]::Parse($freshnessMarker, [Globalization.CultureInfo]::InvariantCulture)
                $freshnessAgeDays = [math]::Round(((Get-Date) - $freshnessDate).TotalDays, 2)
                $freshnessStatus = if ($freshnessAgeDays -le $FreshnessDays) { "fresh" } else { "stale" }
            }
            catch {
                $freshnessStatus = "invalid"
            }

            if ($freshnessStatus -in @("stale", "invalid")) {
                [void]$freshnessWarnings.Add([pscustomobject]@{
                        digestPath = $relativeDigestPath
                        freshnessMarker = $freshnessMarker
                        freshnessStatus = $freshnessStatus
                        ageDays = $freshnessAgeDays
                    })
            }
        }
    }

    if (-not $exists -and ($requiredProjectDigests -contains $relativeDigestPath -or $requiredGeneratedDigests -contains $relativeDigestPath)) {
        [void]$missingRequiredProjectDigests.Add($relativeDigestPath)
    }
    if (-not $exists -and $requiredGeneratedMigrationDigests -contains $relativeDigestPath) {
        [void]$missingGeneratedMigrationDigests.Add($relativeDigestPath)
    }

    [void]$digestAudits.Add([pscustomobject]@{
            path = $relativeDigestPath
            exists = $exists
            references = $referenceAudits
            freshnessMarker = $freshnessMarker
            freshnessStatus = $freshnessStatus
            freshnessAgeDays = $freshnessAgeDays
        })
}

$checks = @(
    (New-NPDevCheckResult "required-project-digests" $(if ($missingRequiredProjectDigests.Count -eq 0) { "passed" } else { "failed" }) $(if ($missingRequiredProjectDigests.Count -eq 0) { "Required project digests are present." } else { "One or more required .npdev-root files are missing." }) @{
            missing = @($missingRequiredProjectDigests)
        }),
    (New-NPDevCheckResult "generated-sample-migration-digests" $(if ($missingGeneratedMigrationDigests.Count -eq 0) { "passed" } else { "failed" }) $(if ($missingGeneratedMigrationDigests.Count -eq 0) { "Generated release samples expose MIGRATION_DIGEST.md." } else { "One or more generated release samples are missing MIGRATION_DIGEST.md." }) @{
            missing = @($missingGeneratedMigrationDigests)
        }),
    (New-NPDevCheckResult "referenced-path-integrity" $(if ($brokenReferences.Count -eq 0) { "passed" } else { "failed" }) $(if ($brokenReferences.Count -eq 0) { "Digest references resolve to existing repo paths." } else { "One or more digest references resolve to missing repo paths." }) @{
            brokenReferences = @($brokenReferences)
        }),
    (New-NPDevCheckResult "freshness-markers" $(if ($freshnessWarnings.Count -eq 0) { "passed" } else { "warning" }) $(if ($freshnessWarnings.Count -eq 0) { "No explicit digest freshness markers are stale." } else { "One or more explicit digest freshness markers are stale or invalid." }) @{
            freshnessWarnings = @($freshnessWarnings)
            freshnessDays = $FreshnessDays
        })
)

$failedChecks = @($checks | Where-Object { $_.status -eq "failed" })
$warningChecks = @($checks | Where-Object { $_.status -eq "warning" })
$report = [pscustomobject]@{
    generatedAt = (Get-Date).ToString("o")
    runId = $RunId
    scriptPath = Get-NPDevWorkspaceRelativePath $WorkspaceRoot $PSCommandPath
    workspaceRoot = $WorkspaceRoot
    overallStatus = if ($failedChecks.Count -gt 0) { "failed" } elseif ($warningChecks.Count -gt 0) { "warning" } else { "passed" }
    freshnessDays = $FreshnessDays
    digests = @($digestAudits)
    checks = $checks
    summary = [pscustomobject]@{
        failed = $failedChecks.Count
        warnings = $warningChecks.Count
        passed = @($checks | Where-Object { $_.status -eq "passed" }).Count
        total = $checks.Count
    }
}
Write-NPDevJsonFile $ReportPath $report

if ($PassThru) {
    return $report
}

if ($report.overallStatus -eq "passed") {
    Write-NPDevOk "Documentation digest governance report generated."
    return
}

if ($report.overallStatus -eq "warning") {
    Write-NPDevWarn "Documentation digest governance report generated with warnings."
    return
}

Write-NPDevWarn "Documentation digest governance report failed."
throw "Documentation digest governance report failed."

