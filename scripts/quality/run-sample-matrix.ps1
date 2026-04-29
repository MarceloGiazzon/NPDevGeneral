[CmdletBinding()]
param(
    [string]$WorkspaceRoot = "",
    [string[]]$SampleIds = @(),
    [switch]$AllowPartialMatrix,
    [string]$RunId = "",
    [string]$PolicyPath = "",
    [string]$ReportPath = ""
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

. (Join-Path $PSScriptRoot "..\npdev-common.ps1")

if ([string]::IsNullOrWhiteSpace($WorkspaceRoot)) {
    $WorkspaceRoot = Get-NPDevWorkspaceRoot $PSScriptRoot
}
$WorkspaceRoot = Normalize-NPDevPath $WorkspaceRoot
$RunId = Resolve-NPDevRunId $RunId "sample-matrix"

if ([string]::IsNullOrWhiteSpace($ReportPath)) {
    $ReportPath = Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\reports\out\sample-matrix-report.json"
}
else {
    $ReportPath = Normalize-NPDevPath $ReportPath
}

if ([string]::IsNullOrWhiteSpace($PolicyPath)) {
    $PolicyPath = Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\policy\sample-matrix-policy.json"
}
else {
    $PolicyPath = Normalize-NPDevPath $PolicyPath
}

$generateScript = Resolve-NPDevWorkspacePath $WorkspaceRoot "NPDevSamples\scripts\generate-sample-app.ps1"
$cleanScript = Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\samples\clean-sample-output.ps1"
$syncRuntimeHostLibsScript = Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\runtimehost\sync-runtimehost-libs.ps1"
Ensure-NPDevFile $generateScript "Sample generation script"
Ensure-NPDevFile $cleanScript "Sample cleanup script"
Ensure-NPDevFile $syncRuntimeHostLibsScript "RuntimeHost libs sync script"
Ensure-NPDevFile $PolicyPath "Sample matrix policy"

$policy = Get-Content -LiteralPath $PolicyPath -Raw | ConvertFrom-Json
$releaseKinds = @($policy.releaseKinds | ForEach-Object { [string]$_ })
$excludedKinds = @($policy.excludedKinds | ForEach-Object { [string]$_ })
$requiredReleaseCoveragePercent = [double]$policy.requiredReleaseCoveragePercent
$minimumReleaseSampleCount = [int]$policy.minimumReleaseSampleCount

function Get-SampleEntryById(
    [object[]]$Entries,
    [string]$SampleId
) {
    foreach ($entry in $Entries) {
        if ([string]$entry.id -eq $SampleId) {
            return $entry
        }
    }
    return $null
}

function Get-HashtableValueOrNull(
    [hashtable]$Map,
    [string]$Key
) {
    if ($null -eq $Map) {
        return $null
    }
    if (-not $Map.ContainsKey($Key)) {
        return $null
    }
    return $Map[$Key]
}

function Get-SampleInputFingerprint(
    [object]$Entry
) {
    $sampleId = [string]$Entry.id
    $inputPath = Resolve-NPDevWorkspacePath $WorkspaceRoot (([string]$Entry.inputPath).Replace("/", "\"))
    $issues = [System.Collections.Generic.List[string]]::new()
    $files = [System.Collections.Generic.List[object]]::new()

    foreach ($inputFile in @(
            @{ name = "model.json"; required = $true },
            @{ name = "config.json"; required = $true },
            @{ name = "manifest.json"; required = $false }
        )) {
        $filePath = Join-Path $inputPath ([string]$inputFile.name)
        $exists = Test-Path -LiteralPath $filePath -PathType Leaf
        if (-not $exists -and [bool]$inputFile.required) {
            [void]$issues.Add("Missing required sample input file: " + (Get-NPDevWorkspaceRelativePath $WorkspaceRoot $filePath))
        }

        if ($exists) {
            $item = Get-Item -LiteralPath $filePath
            [void]$files.Add([pscustomobject]@{
                    name = [string]$inputFile.name
                    path = Get-NPDevWorkspaceRelativePath $WorkspaceRoot $filePath
                    sha256 = (Get-FileHash -LiteralPath $filePath -Algorithm SHA256).Hash.ToLowerInvariant()
                    sizeBytes = $item.Length
                })
        }
    }

    return [pscustomobject]@{
        sampleId = $sampleId
        kind = [string]$Entry.kind
        inputRoot = Get-NPDevWorkspaceRelativePath $WorkspaceRoot $inputPath
        files = @($files)
        issues = @($issues)
    }
}

function Get-SampleRequestExampleAudit(
    [object]$Entry
) {
    $sampleId = [string]$Entry.id
    $inputPath = Resolve-NPDevWorkspacePath $WorkspaceRoot (([string]$Entry.inputPath).Replace("/", "\"))
    $requestsRoot = Join-Path $inputPath "Requests"
    if (-not (Test-Path -LiteralPath $requestsRoot -PathType Container)) {
        return [pscustomobject]@{
            sampleId = $sampleId
            requestSchemaValidation = "not-applicable"
            invalidRequestBoundaryTest = $false
            files = @()
            issues = @()
        }
    }

    $issues = [System.Collections.Generic.List[string]]::new()
    $requestFiles = @(Get-ChildItem -LiteralPath $requestsRoot -File -Filter "*.json" -ErrorAction SilentlyContinue | Sort-Object Name)
    foreach ($requestFile in $requestFiles) {
        try {
            Get-Content -LiteralPath $requestFile.FullName -Raw | ConvertFrom-Json | Out-Null
        }
        catch {
            [void]$issues.Add("Request schema validation failed for " + (Get-NPDevWorkspaceRelativePath $WorkspaceRoot $requestFile.FullName))
        }
    }

    $invalidRequestBoundaryFiles = @($requestFiles | Where-Object { $_.Name -match 'invalid|boundary' })
    if ($requestFiles.Count -gt 0 -and $invalidRequestBoundaryFiles.Count -eq 0) {
        [void]$issues.Add("Missing invalid request boundary test for sample " + $sampleId)
    }

    return [pscustomobject]@{
        sampleId = $sampleId
        requestSchemaValidation = "performed"
        invalidRequestBoundaryTest = ($invalidRequestBoundaryFiles.Count -gt 0)
        files = @($requestFiles | ForEach-Object { Get-NPDevWorkspaceRelativePath $WorkspaceRoot $_.FullName })
        issues = @($issues)
    }
}

function Get-SampleOutputSummary(
    [string]$AppRoot
) {
    $exists = Test-Path -LiteralPath $AppRoot -PathType Container
    $files = if ($exists) {
        @(Get-ChildItem -LiteralPath $AppRoot -Recurse -File -Force -ErrorAction SilentlyContinue)
    }
    else {
        @()
    }
    $sizeMeasure = $files | Measure-Object -Property Length -Sum
    $sizeBytes = if ($null -eq $sizeMeasure.Sum) { 0 } else { [int64]$sizeMeasure.Sum }

    return [pscustomobject]@{
        appRoot = Get-NPDevWorkspaceRelativePath $WorkspaceRoot $AppRoot
        exists = [bool]$exists
        fileCount = $files.Count
        sizeBytes = $sizeBytes
    }
}

function Get-GenerationMarkerEvidence(
    [string]$MarkerPath,
    [string]$ExpectedRunId
) {
    $relativePath = Get-NPDevWorkspaceRelativePath $WorkspaceRoot $MarkerPath
    if (-not (Test-Path -LiteralPath $MarkerPath -PathType Leaf)) {
        return [pscustomobject]@{
            path = $relativePath
            status = "missing"
            markerRunId = $null
            markerSampleId = $null
            markerGeneratedAt = $null
            runIdMatches = $false
            error = "Generation marker file was not found."
        }
    }

    try {
        $marker = Get-Content -LiteralPath $MarkerPath -Raw | ConvertFrom-Json
        $markerRunId = [string]$marker.runId
        $markerGeneratedAt = if ($null -eq $marker.generatedAt) {
            $null
        }
        elseif ($marker.generatedAt -is [datetime]) {
            $marker.generatedAt.ToString("o")
        }
        else {
            [string]$marker.generatedAt
        }
        $runIdMatches = $markerRunId -eq $ExpectedRunId

        return [pscustomobject]@{
            path = $relativePath
            status = if ($runIdMatches) { "current" } else { "stale" }
            markerRunId = $markerRunId
            markerSampleId = if ($null -eq $marker.sampleId) { $null } else { [string]$marker.sampleId }
            markerGeneratedAt = $markerGeneratedAt
            runIdMatches = $runIdMatches
            error = if ($runIdMatches) { $null } else { "Generation marker runId does not match current sample matrix runId." }
        }
    }
    catch {
        return [pscustomobject]@{
            path = $relativePath
            status = "parse-error"
            markerRunId = $null
            markerSampleId = $null
            markerGeneratedAt = $null
            runIdMatches = $false
            error = $_.Exception.Message
        }
    }
}

function New-DefaultVerificationCommand(
    [string]$AppRoot,
    [string]$LogPath,
    [string]$ErrorMessage
) {
    $relativeAppRoot = Get-NPDevWorkspaceRelativePath $WorkspaceRoot $AppRoot
    $relativeLogPath = if ([string]::IsNullOrWhiteSpace($LogPath)) {
        $null
    }
    else {
        Get-NPDevWorkspaceRelativePath $WorkspaceRoot $LogPath
    }

    return [pscustomobject]@{
        status = "failed"
        workingDirectory = $relativeAppRoot
        executable = ".\gradlew.bat"
        arguments = @("--no-daemon", "--console=plain", "enforceSingleMigrationSource", "test")
        display = ".\gradlew.bat --no-daemon --console=plain enforceSingleMigrationSource test"
        exitCode = $null
        startedAt = $null
        endedAt = $null
        durationSeconds = $null
        outputLineCount = 0
        outputTail = @()
        failingTaskName = $null
        logPath = $relativeLogPath
        error = $ErrorMessage
        failureReasons = @($ErrorMessage)
    }
}

function New-SampleMatrixReport(
    [string]$Status,
    [object[]]$Results,
    [string[]]$PreflightFailures
) {
    $failedResults = @($Results | Where-Object { $_.status -eq "failed" })
    $releaseEvidenceEligible = $PreflightFailures.Count -eq 0 `
        -and $failedResults.Count -eq 0 `
        -and $matrixCoveragePercent -ge $requiredReleaseCoveragePercent
    $releaseEvidenceReason = if ($releaseEvidenceEligible) {
        "Full release sample matrix covered and passed."
    }
    elseif ($matrixCoveragePercent -lt $requiredReleaseCoveragePercent -and [bool]$AllowPartialMatrix) {
        "Partial matrix run is diagnostic only and is not eligible for release evidence."
    }
    elseif ($PreflightFailures.Count -gt 0) {
        "Sample matrix preflight failed."
    }
    else {
        "One or more selected samples failed verification."
    }

    return [pscustomobject]@{
        generatedAt = (Get-Date).ToString("o")
        runId = $RunId
        scriptPath = Get-NPDevWorkspaceRelativePath $WorkspaceRoot $PSCommandPath
        workspaceRoot = $WorkspaceRoot
        overallStatus = $Status
        phase = "sample-matrix-governance"
        policyPath = Get-NPDevWorkspaceRelativePath $WorkspaceRoot $PolicyPath
        policyVersion = [string]$policy.version
        sampleIds = $SampleIds
        defaultMatrixMode = $defaultMatrixMode
        allowPartialMatrix = [bool]$AllowPartialMatrix
        matrixMode = $matrixMode
        catalogSampleCount = $catalogEntries.Count
        releaseMatrixSampleCount = $releaseSampleIds.Count
        matrixCoveragePercent = $matrixCoveragePercent
        catalog = [pscustomobject]@{
            duplicateSampleIds = $duplicateCatalogIds
            unknownKindEntries = $unknownKindEntries
            releaseKinds = $releaseKinds
            excludedKinds = $excludedKinds
            releaseSampleIds = $releaseSampleIds
            nonReleaseSampleIds = $nonReleaseSampleIds
        }
        coverage = [pscustomobject]@{
            requiredReleaseCoveragePercent = $requiredReleaseCoveragePercent
            coveredReleaseSampleIds = $coveredReleaseSamples
            missingReleaseSampleIds = $missingReleaseSampleIds
            coverageByKind = $coverageByKind
        }
        coverageAssertions = [pscustomobject]@{
            releaseMatrixCoverageSatisfied = ($matrixCoveragePercent -ge $requiredReleaseCoveragePercent)
            releaseMatrixCoveragePercent = $matrixCoveragePercent
            requiredReleaseCoveragePercent = $requiredReleaseCoveragePercent
            requestedSampleIdsKnown = ($unknownRequestedSampleIds.Count -eq 0)
            duplicateCatalogIds = $duplicateCatalogIds
            minimumReleaseSampleCountSatisfied = ($releaseSampleIds.Count -ge $minimumReleaseSampleCount)
            requestExampleAuditPassed = ($requestIssues.Count -eq 0)
            inputFingerprintIssues = @($inputIssues)
            releaseEvidenceEligible = $releaseEvidenceEligible
        }
        cleanupPolicy = [pscustomobject]@{
            mode = "build-caches-only"
            removedPathPatterns = @("App\.gradle", "App\build", "App\node_modules")
            retainedEvidencePatterns = @(
                "Output\Reports\generation-run.json",
                "Output\App\.npdev-root",
                "Output\App\MIGRATION_DIGEST.md",
                "Output\App\gradlew.bat",
                "Output\App\settings.gradle",
                "Output\App\build.gradle",
                "Output\App\src\main\resources\db\migration"
            )
        }
        releaseEvidence = [pscustomobject]@{
            eligible = $releaseEvidenceEligible
            reason = $releaseEvidenceReason
            rule = "Release evidence requires full policy-defined release sample coverage and passing verification for every covered release sample."
        }
        inputFingerprints = $inputFingerprints
        preflightFailures = $PreflightFailures
        results = $Results
        summary = [pscustomobject]@{
            failed = $failedResults.Count
            warnings = if ($Status -eq "warning") { 1 } else { 0 }
            passed = @($Results | Where-Object { $_.status -eq "passed" }).Count
            total = $Results.Count
        }
    }
}

$catalogEntries = @(Get-NPDevSampleEntries $WorkspaceRoot)
$catalogSampleIds = @($catalogEntries | ForEach-Object { [string]$_.id })
$duplicateCatalogIds = @($catalogSampleIds | Group-Object | Where-Object { $_.Count -gt 1 } | ForEach-Object { [string]$_.Name })
$unknownKindEntries = @($catalogEntries | Where-Object {
        $kind = [string]$_.kind
        $releaseKinds -notcontains $kind -and $excludedKinds -notcontains $kind
    } | ForEach-Object {
        [pscustomobject]@{
            sampleId = [string]$_.id
            kind = [string]$_.kind
        }
    })

$releaseMatrixEntries = @($catalogEntries | Where-Object { $releaseKinds -contains [string]$_.kind })
$nonReleaseEntries = @($catalogEntries | Where-Object { $excludedKinds -contains [string]$_.kind })
$releaseSampleIds = @($releaseMatrixEntries | Select-Object -ExpandProperty id)
$nonReleaseSampleIds = @($nonReleaseEntries | Select-Object -ExpandProperty id)

$defaultMatrixMode = $SampleIds.Count -eq 0
if ($defaultMatrixMode) {
    $SampleIds = @($releaseSampleIds)
}
$SampleIds = @($SampleIds | ForEach-Object { [string]$_ } | Where-Object { -not [string]::IsNullOrWhiteSpace($_) })
$selectedUniqueSampleIds = @($SampleIds | Select-Object -Unique)
$unknownRequestedSampleIds = @($selectedUniqueSampleIds | Where-Object { $catalogSampleIds -notcontains $_ })
$coveredReleaseSamples = @($selectedUniqueSampleIds | Where-Object { $releaseSampleIds -contains $_ })
$missingReleaseSampleIds = @($releaseSampleIds | Where-Object { $selectedUniqueSampleIds -notcontains $_ })
$matrixCoveragePercent = if ($releaseSampleIds.Count -eq 0) {
    100.0
}
else {
    [math]::Round(([double]$coveredReleaseSamples.Count / [double]$releaseSampleIds.Count) * 100.0, 1)
}
$matrixMode = if ($defaultMatrixMode) {
    "release"
}
elseif ([bool]$AllowPartialMatrix) {
    "partial-diagnostic"
}
else {
    "explicit-release-required"
}

$coverageByKind = @($releaseKinds | ForEach-Object {
        $kind = [string]$_
        $expectedIds = @($releaseMatrixEntries | Where-Object { [string]$_.kind -eq $kind } | Select-Object -ExpandProperty id)
        $coveredIds = @($coveredReleaseSamples | Where-Object { $expectedIds -contains $_ })
        $missingIds = @($expectedIds | Where-Object { $coveredIds -notcontains $_ })
        $coveragePercent = if ($expectedIds.Count -eq 0) {
            100.0
        }
        else {
            [math]::Round(([double]$coveredIds.Count / [double]$expectedIds.Count) * 100.0, 1)
        }
        [pscustomobject]@{
            kind = $kind
            expectedCount = $expectedIds.Count
            coveredCount = $coveredIds.Count
            coveragePercent = $coveragePercent
            coveredSampleIds = $coveredIds
            missingSampleIds = $missingIds
        }
    })

$selectedCatalogEntries = @($selectedUniqueSampleIds | ForEach-Object {
        Get-SampleEntryById -Entries $catalogEntries -SampleId $_
    } | Where-Object { $null -ne $_ })
$inputFingerprints = @($selectedCatalogEntries | ForEach-Object { Get-SampleInputFingerprint $_ })
$inputFingerprintBySampleId = @{}
foreach ($fingerprint in $inputFingerprints) {
    $inputFingerprintBySampleId[[string]$fingerprint.sampleId] = $fingerprint
}
$requestExampleAudit = @($selectedCatalogEntries | ForEach-Object { Get-SampleRequestExampleAudit $_ })
$inputIssues = @($inputFingerprints | ForEach-Object { $_.issues } | Where-Object { -not [string]::IsNullOrWhiteSpace([string]$_) })
$requestIssues = @($requestExampleAudit | ForEach-Object { $_.issues } | Where-Object { -not [string]::IsNullOrWhiteSpace([string]$_) })

$preflightFailures = [System.Collections.Generic.List[string]]::new()
if ($duplicateCatalogIds.Count -gt 0) {
    [void]$preflightFailures.Add("Sample catalog has duplicate sample id(s): " + ($duplicateCatalogIds -join ", "))
}
if ($unknownKindEntries.Count -gt 0) {
    [void]$preflightFailures.Add("Sample catalog has kind(s) outside the sample matrix policy.")
}
if ($unknownRequestedSampleIds.Count -gt 0) {
    [void]$preflightFailures.Add("Requested sample id(s) are not present in the sample catalog: " + ($unknownRequestedSampleIds -join ", "))
}
if ($releaseSampleIds.Count -lt $minimumReleaseSampleCount) {
    [void]$preflightFailures.Add("Release sample count " + $releaseSampleIds.Count + " is below policy minimum " + $minimumReleaseSampleCount + ".")
}
foreach ($minimumByKind in @($policy.minimumReleaseSamplesByKind.PSObject.Properties)) {
    $kind = [string]$minimumByKind.Name
    $minimumCount = [int]$minimumByKind.Value
    $actualCount = @($releaseMatrixEntries | Where-Object { [string]$_.kind -eq $kind }).Count
    if ($actualCount -lt $minimumCount) {
        [void]$preflightFailures.Add("Release sample count for kind " + $kind + " is " + $actualCount + ", below policy minimum " + $minimumCount + ".")
    }
}
foreach ($inputIssue in $inputIssues) {
    [void]$preflightFailures.Add([string]$inputIssue)
}
foreach ($requestIssue in $requestIssues) {
    [void]$preflightFailures.Add([string]$requestIssue)
}
if (-not [bool]$AllowPartialMatrix -and $matrixCoveragePercent -lt $requiredReleaseCoveragePercent) {
    [void]$preflightFailures.Add("Sample matrix coverage is " + $matrixCoveragePercent + "%; release evidence requires " + $requiredReleaseCoveragePercent + "%. Missing: " + ($missingReleaseSampleIds -join ", "))
}

if ($preflightFailures.Count -gt 0) {
    $report = New-SampleMatrixReport -Status "failed" -Results @() -PreflightFailures @($preflightFailures)
    Write-NPDevJsonFile $ReportPath $report
    Write-NPDevWarn ("Sample matrix preflight failed for " + ($SampleIds -join ", ") + ".")
    throw "Sample matrix preflight failed."
}

Write-NPDevInfo "Synchronizing RuntimeHost local dependency jars before sample generation"
& $syncRuntimeHostLibsScript `
    -WorkspaceRoot $WorkspaceRoot `
    -BuildLocalJars `
    -ReportPath (Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\reports\out\runtimehost-libs-sync-report.json")

$results = @()
foreach ($sampleId in $SampleIds) {
    $appRoot = Resolve-NPDevWorkspacePath $WorkspaceRoot ("NPDevSamples\" + $sampleId + "\Output\App")
    $sampleEntry = Get-SampleEntryById -Entries $catalogEntries -SampleId $sampleId
    $sampleKind = [string]$sampleEntry.kind
    $startedAt = Get-Date
    $sampleStatus = "passed"
    $sampleError = $null
    $generationMarkerEvidence = $null
    $verificationCommand = $null
    $cleanupEvidence = $null
    $cleanupReportForSample = $null
    $verificationLogPath = Resolve-NPDevWorkspacePath $WorkspaceRoot ("scripts\reports\out\sample-matrix\" + $sampleId + "-verification.log")

    try {
        Write-NPDevInfo ("Generating release sample: " + $sampleId)
        & $generateScript -SampleId $sampleId -NPDevRoot $WorkspaceRoot -RunId $RunId

        $generationMarkerPath = Resolve-NPDevWorkspacePath $WorkspaceRoot ("NPDevSamples\" + $sampleId + "\Output\Reports\generation-run.json")
        Ensure-NPDevFile $generationMarkerPath "Sample generation marker"
        $generationMarkerEvidence = Get-GenerationMarkerEvidence $generationMarkerPath $RunId
        if (-not [bool]$generationMarkerEvidence.runIdMatches) {
            throw "Generated sample artifacts do not match current sample matrix runId."
        }

        Ensure-NPDevDirectory $appRoot "Generated sample app"

        Write-NPDevInfo ("Running runtime build/tests for " + $sampleId)
        $verificationCommand = Invoke-NPDevCommandEvidence `
            -WorkspaceRoot $WorkspaceRoot `
            -WorkingDirectory $appRoot `
            -Executable ".\gradlew.bat" `
            -Arguments @("--no-daemon", "--console=plain", "enforceSingleMigrationSource", "test") `
            -LogPath $verificationLogPath

        if ([string]$verificationCommand.status -ne "passed") {
            throw "Sample verification command failed."
        }
    }
    catch {
        $sampleStatus = "failed"
        $sampleError = $_.Exception.Message
        if ($null -eq $generationMarkerEvidence) {
            $generationMarkerEvidence = Get-GenerationMarkerEvidence (Resolve-NPDevWorkspacePath $WorkspaceRoot ("NPDevSamples\" + $sampleId + "\Output\Reports\generation-run.json")) $RunId
        }
    }
    finally {
        $cleanupReportPath = Resolve-NPDevWorkspacePath $WorkspaceRoot ("scripts\reports\out\sample-matrix\" + $sampleId + "-clean-report.json")
        try {
            & $cleanScript -WorkspaceRoot $WorkspaceRoot -SampleIds @($sampleId) -BuildCachesOnly -ReportPath $cleanupReportPath | Out-Null
            if (Test-Path -LiteralPath $cleanupReportPath -PathType Leaf) {
                $cleanupReportDoc = Get-Content -LiteralPath $cleanupReportPath -Raw | ConvertFrom-Json
                $cleanupReportForSample = @($cleanupReportDoc.results | Where-Object { [string]$_.sampleId -eq $sampleId } | Select-Object -First 1)
            }
            $cleanupEvidence = [pscustomobject]@{
                status = "passed"
                reportPath = Get-NPDevWorkspaceRelativePath $WorkspaceRoot $cleanupReportPath
                error = $null
                removedPaths = if ($null -eq $cleanupReportForSample) { @() } else { @($cleanupReportForSample.removedPaths) }
                retainedEvidencePaths = if ($null -eq $cleanupReportForSample) { @() } else { @($cleanupReportForSample.retainedEvidencePaths) }
            }
        }
        catch {
            $cleanupError = $_.Exception.Message
            $cleanupEvidence = [pscustomobject]@{
                status = "failed"
                reportPath = if (Test-Path -LiteralPath $cleanupReportPath -PathType Leaf) { Get-NPDevWorkspaceRelativePath $WorkspaceRoot $cleanupReportPath } else { $null }
                error = $cleanupError
                removedPaths = @()
                retainedEvidencePaths = @()
            }
            if ($sampleStatus -eq "passed") {
                $sampleStatus = "failed"
                $sampleError = "Sample output cleanup failed after verification: " + $cleanupError
            }
            else {
                $sampleError = $sampleError + " Sample output cleanup also failed: " + $cleanupError
            }
        }
    }

    $endedAt = Get-Date
    $results += [pscustomobject]@{
        sampleId = $sampleId
        kind = $sampleKind
        inputFingerprint = Get-HashtableValueOrNull -Map $inputFingerprintBySampleId -Key $sampleId
        appRoot = Get-NPDevWorkspaceRelativePath $WorkspaceRoot $appRoot
        status = $sampleStatus
        startedAt = $startedAt.ToString("o")
        endedAt = $endedAt.ToString("o")
        durationSeconds = [math]::Round(($endedAt - $startedAt).TotalSeconds, 1)
        command = ".\gradlew.bat --no-daemon --console=plain enforceSingleMigrationSource test"
        verificationCommand = if ($null -eq $verificationCommand) {
            New-DefaultVerificationCommand -AppRoot $appRoot -LogPath $verificationLogPath -ErrorMessage "Sample verification command did not complete."
        }
        else {
            $verificationCommand
        }
        cleanup = $cleanupEvidence
        generationMarker = $generationMarkerEvidence
        outputSummary = Get-SampleOutputSummary $appRoot
        error = $sampleError
    }
}

$failed = @($results | Where-Object { $_.status -eq "failed" })
$status = if ($failed.Count -gt 0) {
    "failed"
}
elseif ($matrixCoveragePercent -ge $requiredReleaseCoveragePercent) {
    "passed"
}
else {
    "warning"
}

$report = New-SampleMatrixReport -Status $status -Results $results -PreflightFailures @()
Write-NPDevJsonFile $ReportPath $report

if ($status -eq "passed") {
    Write-NPDevOk ("Sample matrix passed for " + ($SampleIds -join ", ") + ".")
    return
}

if ($status -eq "warning") {
    Write-NPDevWarn ("Sample matrix completed as a partial diagnostic run for " + ($SampleIds -join ", ") + ".")
    return
}

Write-NPDevWarn ("Sample matrix failed for " + ($failed.sampleId -join ", ") + ".")
throw "Sample matrix failed."

