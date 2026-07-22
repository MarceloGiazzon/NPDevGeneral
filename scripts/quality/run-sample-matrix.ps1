param(
    [string]$SampleRoot = "NPDevSamples",
    [string]$PolicyPath = "scripts/policy/sample-matrix-policy.json",
    [string]$ReportPath = "scripts/reports/out/sample-matrix-report.json",
    [string]$RunId = "",
    [string[]]$SampleIds = @(),
    [switch]$AllowPartialMatrix,
    [string]$EvidenceRoot = "",
    [switch]$SkipGenerationRuntimeVerification
)

$ErrorActionPreference = "Stop"

function Read-JsonFile {
    param([string]$Path)
    return Get-Content -Raw -LiteralPath $Path | ConvertFrom-Json
}

function Add-Issue {
    param(
        [System.Collections.Generic.List[string]]$Issues,
        [string]$Message
    )
    if (-not [string]::IsNullOrWhiteSpace($Message)) {
        $Issues.Add($Message) | Out-Null
    }
}

function Convert-ToRelativePath {
    param(
        [string]$Root,
        [string]$Path
    )
    if ([string]::IsNullOrWhiteSpace($Path)) {
        return ""
    }
    $full = [System.IO.Path]::GetFullPath($Path)
    $rootFull = [System.IO.Path]::GetFullPath($Root).TrimEnd([System.IO.Path]::DirectorySeparatorChar, [System.IO.Path]::AltDirectorySeparatorChar)
    if ($full.StartsWith($rootFull, [System.StringComparison]::OrdinalIgnoreCase)) {
        return $full.Substring($rootFull.Length).TrimStart([System.IO.Path]::DirectorySeparatorChar, [System.IO.Path]::AltDirectorySeparatorChar).Replace("\", "/")
    }
    return $full.Replace("\", "/")
}

function Resolve-RepoPath {
    param(
        [string]$WorkspaceRoot,
        [string]$Path
    )
    if ([System.IO.Path]::IsPathRooted($Path)) {
        return $Path
    }
    return Join-Path $WorkspaceRoot $Path
}

function Invoke-OfficialSchemaValidation {
    param(
        [string]$SchemaPath,
        [string]$JsonPath,
        [string]$ResultPath
    )
    $ErrorActionPreference = "Continue"
    pwsh -NoProfile -File scripts/quality/Invoke-JsonSchemaValidation.ps1 `
        -SchemaPath $SchemaPath `
        -JsonPath $JsonPath `
        -ReportPath $ResultPath 2>$null | Out-Null
    $exitCode = $LASTEXITCODE
    $ErrorActionPreference = "Stop"
    $result = if (Test-Path -LiteralPath $ResultPath -PathType Leaf) { Read-JsonFile $ResultPath } else { $null }
    if ($exitCode -eq 0 -and $null -ne $result -and [string]$result.status -eq "passed") {
        return [pscustomobject]@{
            status = "passed"
            failures = @()
            reportPath = $ResultPath
        }
    }
    if ($null -ne $result) {
        return [pscustomobject]@{
            status = "failed"
            failures = @($result.failures | ForEach-Object { [string]$_ })
            reportPath = $ResultPath
        }
    }
    return [pscustomobject]@{
        status = "failed"
        failures = @("JSON Schema validation did not write a result.")
        reportPath = $ResultPath
    }
}

function Get-SampleClassification {
    param(
        [string]$SampleId,
        [object]$Policy
    )
    $matches = @()
    if (@($Policy.releaseBlockingSamples) -contains $SampleId) { $matches += "release-sample" }
    if (@($Policy.fixtureOnlySamples) -contains $SampleId) { $matches += "fixture-only" }
    if ($matches.Count -eq 0) { return "unclassified" }
    if ($matches.Count -gt 1) { return "ambiguous" }
    return $matches[0]
}

function Get-ClassificationReason {
    param([string]$Classification)
    switch ($Classification) {
        "release-sample" { return "Release samples are release-blocking and must satisfy the strict sample matrix input contract." }
        "fixture-only" { return "Fixture-only samples are low-level fixtures excluded from release coverage and release eligibility." }
        "ambiguous" { return "Sample appears in more than one policy classification list and must be corrected." }
        default { return "Sample is not explicitly classified by the sample matrix policy." }
    }
}

function Test-ReleaseBlockingClassification {
    param([string]$Classification)
    return $Classification -eq "release-sample"
}

function Get-PolicyStringList {
    param(
        [object]$Policy,
        [string]$PropertyName
    )
    if ($null -eq $Policy -or $Policy.PSObject.Properties.Name -notcontains $PropertyName) {
        return @()
    }
    return @($Policy.$PropertyName | Where-Object { -not [string]::IsNullOrWhiteSpace([string]$_) } | ForEach-Object { [string]$_ })
}

function Get-FileFingerprint {
    param(
        [string]$WorkspaceRoot,
        [string]$Path
    )
    $exists = Test-Path -LiteralPath $Path -PathType Leaf
    return [pscustomobject]@{
        path = Convert-ToRelativePath -Root $WorkspaceRoot -Path $Path
        exists = $exists
        sizeBytes = if ($exists) { (Get-Item -LiteralPath $Path).Length } else { 0 }
        sha256 = if ($exists) { (Get-FileHash -Algorithm SHA256 -LiteralPath $Path).Hash.ToLowerInvariant() } else { $null }
    }
}

function Get-DirectorySummary {
    param(
        [string]$WorkspaceRoot,
        [string]$Path
    )
    $exists = Test-Path -LiteralPath $Path -PathType Container
    $files = if ($exists) { @(Get-ChildItem -LiteralPath $Path -File -Recurse -Force -ErrorAction SilentlyContinue) } else { @() }
    $size = 0
    foreach ($file in $files) {
        $size += [int64]$file.Length
    }
    return [pscustomobject]@{
        appRoot = Convert-ToRelativePath -Root $WorkspaceRoot -Path $Path
        exists = $exists
        fileCount = $files.Count
        sizeBytes = $size
    }
}

function New-VerificationLog {
    param(
        [string]$Path,
        [string]$SampleId,
        [string]$Message
    )
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $Path) | Out-Null
    Set-Content -LiteralPath $Path -Encoding UTF8 -Value @(
        "sampleId=$SampleId"
        "status=not-run"
        $Message
    )
}

function Invoke-ProcessEvidence {
    param(
        [string]$WorkspaceRoot,
        [string]$WorkingDirectory,
        [string]$Executable,
        [string[]]$Arguments,
        [string]$LogPath
    )

    $startedAt = Get-Date
    $captureOutput = @()
    $exitCode = $null
    $commandError = $null

    try {
        $process = New-Object System.Diagnostics.Process
        $process.StartInfo = New-Object System.Diagnostics.ProcessStartInfo
        $process.StartInfo.FileName = $Executable
        $process.StartInfo.WorkingDirectory = $WorkingDirectory
        $process.StartInfo.UseShellExecute = $false
        $process.StartInfo.RedirectStandardOutput = $true
        $process.StartInfo.RedirectStandardError = $true
        $process.StartInfo.Arguments = (@($Arguments) | ForEach-Object {
                if ([string]$_ -match '[\s"]') {
                    '"' + ([string]$_ -replace '"', '\"') + '"'
                }
                else {
                    [string]$_
                }
            }) -join " "

        [void]$process.Start()
        $stdout = $process.StandardOutput.ReadToEnd()
        $stderr = $process.StandardError.ReadToEnd()
        $process.WaitForExit()
        $exitCode = [int]$process.ExitCode
        foreach ($chunk in @($stdout, $stderr)) {
            if (-not [string]::IsNullOrWhiteSpace($chunk)) {
                $captureOutput += @($chunk -split "(`r`n|`n|`r)" | Where-Object { -not [string]::IsNullOrWhiteSpace([string]$_) })
            }
        }
    }
    catch {
        $commandError = $_.Exception.Message
    }

    $endedAt = Get-Date
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $LogPath) | Out-Null
    Set-Content -LiteralPath $LogPath -Encoding UTF8 -Value @($captureOutput)
    $status = if ([string]::IsNullOrWhiteSpace($commandError) -and $null -ne $exitCode -and $exitCode -eq 0) { "passed" } else { "failed" }
    $failureReasons = [System.Collections.Generic.List[string]]::new()
    if (-not [string]::IsNullOrWhiteSpace($commandError)) {
        $failureReasons.Add($commandError) | Out-Null
    }
    if ($null -eq $exitCode) {
        $failureReasons.Add("Command did not produce an exit code.") | Out-Null
    }
    elseif ($exitCode -ne 0) {
        $failureReasons.Add("Command failed with exit code " + $exitCode + ".") | Out-Null
    }

    return [pscustomobject]@{
        status = $status
        workingDirectory = Convert-ToRelativePath -Root $WorkspaceRoot -Path $WorkingDirectory
        executable = $Executable
        arguments = @($Arguments)
        display = $Executable + " " + (@($Arguments) -join " ")
        exitCode = $exitCode
        startedAt = $startedAt.ToUniversalTime().ToString("o")
        endedAt = $endedAt.ToUniversalTime().ToString("o")
        durationSeconds = [math]::Round(($endedAt - $startedAt).TotalSeconds, 3)
        outputLineCount = @($captureOutput).Count
        outputTail = @($captureOutput | Select-Object -Last 160)
        logPath = Convert-ToRelativePath -Root $WorkspaceRoot -Path $LogPath
        error = $commandError
        failureReasons = @($failureReasons)
    }
}

function Get-GenerationMarkerEvidence {
    param(
        [string]$WorkspaceRoot,
        [string]$MarkerPath
    )
    $exists = Test-Path -LiteralPath $MarkerPath -PathType Leaf
    $marker = $null
    $readError = $null
    if ($exists) {
        try {
            $marker = Read-JsonFile $MarkerPath
        }
        catch {
            $readError = $_.Exception.Message
        }
    }

    $assembled = $exists -and $null -ne $marker -and [bool]$marker.assembledFinalApp
    $passed = $exists -and $null -eq $readError -and $assembled
    return [pscustomobject]@{
        status = if ($passed) { "passed" } else { "failed" }
        path = Convert-ToRelativePath -Root $WorkspaceRoot -Path $MarkerPath
        exists = $exists
        sha256 = if ($exists) { (Get-FileHash -Algorithm SHA256 -LiteralPath $MarkerPath).Hash.ToLowerInvariant() } else { $null }
        runId = if ($null -ne $marker) { [string]$marker.runId } else { $null }
        assembledFinalApp = if ($null -ne $marker) { [bool]$marker.assembledFinalApp } else { $false }
        finalAppRoot = if ($null -ne $marker) { [string]$marker.finalAppRoot } else { $null }
        error = $readError
    }
}

function Get-PolicySampleIds {
    param([object]$Policy)
    return @(
        @(Get-PolicyStringList -Policy $Policy -PropertyName "releaseBlockingSamples")
        @(Get-PolicyStringList -Policy $Policy -PropertyName "fixtureOnlySamples")
    ) | ForEach-Object { $_ } | Where-Object { -not [string]::IsNullOrWhiteSpace([string]$_) }
}

$workspaceRoot = (Resolve-Path ".").Path
if ([string]::IsNullOrWhiteSpace($RunId)) {
    $RunId = "sample-matrix-" + (Get-Date).ToUniversalTime().ToString("yyyyMMdd-HHmmssfff")
}
$startedAt = (Get-Date).ToUniversalTime()
$sampleRootPath = (Resolve-Path -LiteralPath $SampleRoot).Path
$policy = Read-JsonFile $PolicyPath
$catalogPath = Join-Path $sampleRootPath "sample-catalog.json"
$catalog = Read-JsonFile $catalogPath
$reportDirectory = Split-Path -Parent $ReportPath
if (-not [string]::IsNullOrWhiteSpace($reportDirectory)) {
    New-Item -ItemType Directory -Force -Path $reportDirectory | Out-Null
}
$logRoot = if ([string]::IsNullOrWhiteSpace($EvidenceRoot)) {
    Resolve-RepoPath -WorkspaceRoot $workspaceRoot -Path "scripts/reports/out/sample-matrix"
}
else {
    Resolve-RepoPath -WorkspaceRoot $workspaceRoot -Path $EvidenceRoot
}
New-Item -ItemType Directory -Force -Path $logRoot | Out-Null
$validationRoot = Join-Path $workspaceRoot "scripts/reports/tmp/sample-matrix-schema-validation"
if (Test-Path -LiteralPath $validationRoot) {
    Remove-Item -LiteralPath $validationRoot -Recurse -Force
}
New-Item -ItemType Directory -Force -Path $validationRoot | Out-Null

$policyFailures = [System.Collections.Generic.List[string]]::new()
if ($policy.schemaVersion -ne "npdev-sample-matrix-policy.v1") {
    Add-Issue $policyFailures "sample-matrix-policy schemaVersion must be npdev-sample-matrix-policy.v1."
}
if ([string]$policy.semantics -ne "strict-release-sample") {
    Add-Issue $policyFailures "sample-matrix-policy semantics must be strict-release-sample."
}

$catalogSampleIds = @($catalog.samples | ForEach-Object { [string]$_.id })
$policySampleIds = @(Get-PolicySampleIds -Policy $policy)
$missingPolicySamples = @($policySampleIds | Where-Object { $catalogSampleIds -notcontains $_ })
foreach ($sampleId in $missingPolicySamples) {
    Add-Issue $policyFailures ("Policy classifies sample not present in catalog: " + $sampleId)
}
foreach ($sampleId in $catalogSampleIds) {
    $classification = Get-SampleClassification -SampleId $sampleId -Policy $policy
    if ($classification -eq "unclassified" -or $classification -eq "ambiguous") {
        Add-Issue $policyFailures ("Sample classification is not strict for " + $sampleId + ": " + $classification)
    }
}

$isPartialMatrix = @($SampleIds).Count -gt 0
if ($isPartialMatrix -and -not $AllowPartialMatrix) {
    Add-Issue $policyFailures "SampleIds was provided without AllowPartialMatrix; subset runs are not release evidence."
}

$negativeFixturePath = "scripts/tests/fixtures/sample-matrix-invalid-required/Input/model.json"
$negativeFixture = Invoke-OfficialSchemaValidation `
    -SchemaPath "NPDevContract/schemas/model.schema.json" `
    -JsonPath $negativeFixturePath `
    -ResultPath (Join-Path $validationRoot "negative-required-sample-model.json")
if ($negativeFixture.status -ne "failed") {
    Add-Issue $policyFailures "Negative required-sample fixture unexpectedly passed official model schema validation."
}

$selectedSamples = @($catalog.samples | Sort-Object id)
if ($isPartialMatrix) {
    $selectedSamples = @($selectedSamples | Where-Object { @($SampleIds) -contains [string]$_.id })
    $unknownRequestedSamples = @($SampleIds | Where-Object { $catalogSampleIds -notcontains $_ })
    foreach ($sampleId in $unknownRequestedSamples) {
        Add-Issue $policyFailures ("Requested sample does not exist in catalog: " + $sampleId)
    }
}

$sampleResults = @()
$inputFingerprints = @()
$cleanupResults = @()

foreach ($sample in $selectedSamples) {
    $sampleId = [string]$sample.id
    $classification = Get-SampleClassification -SampleId $sampleId -Policy $policy
    $releaseBlocking = Test-ReleaseBlockingClassification -Classification $classification
    $classificationReason = Get-ClassificationReason -Classification $classification
    $blockingIssues = [System.Collections.Generic.List[string]]::new()
    $nonBlockingIssues = [System.Collections.Generic.List[string]]::new()
    $warnings = [System.Collections.Generic.List[string]]::new()
    $inputRoot = Join-Path $sampleRootPath ([string]$sample.inputRoot)
    $outputRoot = Join-Path $sampleRootPath ([string]$sample.outputRoot)
    $requiredFiles = @()
    $fingerprintFiles = @()

    if (-not (Test-Path -LiteralPath $inputRoot -PathType Container)) {
        if ($releaseBlocking) {
            Add-Issue $blockingIssues "inputRoot is missing."
        }
        else {
            Add-Issue $nonBlockingIssues "inputRoot is missing."
        }
    }
    else {
        foreach ($file in @($policy.requiredInputFiles)) {
            $filePath = Join-Path $inputRoot ([string]$file)
            $exists = Test-Path -LiteralPath $filePath -PathType Leaf
            $requiredFiles += [pscustomobject]@{
                path = Convert-ToRelativePath -Root $workspaceRoot -Path $filePath
                exists = $exists
                releaseBlocking = $releaseBlocking
            }
            if (-not $exists) {
                if ($releaseBlocking) {
                    Add-Issue $blockingIssues ("required file is missing: " + [string]$file)
                }
                else {
                    Add-Issue $nonBlockingIssues ("non-blocking sample file is missing: " + [string]$file)
                }
            }
            else {
                $fingerprintFiles += Get-FileFingerprint -WorkspaceRoot $workspaceRoot -Path $filePath
            }
        }

        $manifestPath = Join-Path $inputRoot "manifest.json"
        if (Test-Path -LiteralPath $manifestPath -PathType Leaf) {
            try {
                $manifest = Read-JsonFile $manifestPath
                if ([string]$manifest.id -ne $sampleId) {
                    if ($releaseBlocking) {
                        Add-Issue $blockingIssues "manifest.id must match sample catalog id."
                    }
                    else {
                        Add-Issue $nonBlockingIssues "manifest.id must match sample catalog id."
                    }
                }
            }
            catch {
                if ($releaseBlocking) {
                    Add-Issue $blockingIssues ("manifest.json is not valid JSON: " + $_.Exception.Message)
                }
                else {
                    Add-Issue $nonBlockingIssues ("manifest.json is not valid JSON: " + $_.Exception.Message)
                }
            }
        }

        $modelPath = Join-Path $inputRoot "model.json"
        if (Test-Path -LiteralPath $modelPath -PathType Leaf) {
            $modelValidation = Invoke-OfficialSchemaValidation `
                -SchemaPath "NPDevContract/schemas/model.schema.json" `
                -JsonPath $modelPath `
                -ResultPath (Join-Path $validationRoot ($sampleId + "-model.json"))
            foreach ($failure in @($modelValidation.failures)) {
                if ($releaseBlocking) {
                    Add-Issue $blockingIssues ("model schema: " + [string]$failure)
                }
                else {
                    Add-Issue $nonBlockingIssues ("model schema: " + [string]$failure)
                }
            }
        }

        $configPath = Join-Path $inputRoot "config.json"
        if (Test-Path -LiteralPath $configPath -PathType Leaf) {
            $configValidation = Invoke-OfficialSchemaValidation `
                -SchemaPath "NPDevContract/schemas/config.schema.json" `
                -JsonPath $configPath `
                -ResultPath (Join-Path $validationRoot ($sampleId + "-config.json"))
            foreach ($failure in @($configValidation.failures)) {
                if ($releaseBlocking) {
                    Add-Issue $blockingIssues ("config schema: " + [string]$failure)
                }
                else {
                    Add-Issue $nonBlockingIssues ("config schema: " + [string]$failure)
                }
            }
            try {
                $config = Read-JsonFile $configPath
                if ($null -ne $config.database -and @($policy.allowedDatabaseProviders) -notcontains [string]$config.database.provider) {
                    if ($releaseBlocking) {
                        Add-Issue $blockingIssues ("config.database.provider is not allowed for sample matrix: " + [string]$config.database.provider)
                    }
                    else {
                        Add-Issue $nonBlockingIssues ("config.database.provider is not allowed for sample matrix: " + [string]$config.database.provider)
                    }
                }
            }
            catch {
                if ($releaseBlocking) {
                    Add-Issue $blockingIssues ("config.json is not valid JSON: " + $_.Exception.Message)
                }
                else {
                    Add-Issue $nonBlockingIssues ("config.json is not valid JSON: " + $_.Exception.Message)
                }
            }
        }
    }

    if ($classification -eq "fixture-only") {
        Add-Issue $warnings "fixture-only sample is intentionally excluded from release coverage."
    }

    $inputFingerprint = [pscustomobject]@{
        sampleId = $sampleId
        kind = [string]$sample.kind
        classification = $classification
        inputRoot = Convert-ToRelativePath -Root $workspaceRoot -Path $inputRoot
        files = @($fingerprintFiles)
        issues = @($blockingIssues + $nonBlockingIssues)
    }
    $inputFingerprints += $inputFingerprint

    $verificationLogPath = Join-Path $logRoot ($sampleId + "-verification.log")
    $verificationReportPath = Join-Path $logRoot ($sampleId + "-sample-verify-report.json")
    $verificationCommand = $null
    $cleanupEvidence = $null

    if ($releaseBlocking -and -not $SkipGenerationRuntimeVerification) {
        $verificationArguments = @(
            "-NoProfile",
            "-File",
            "scripts/samples/verify-sample.ps1",
            "-WorkspaceRoot",
            $workspaceRoot,
            "-SampleIds",
            $sampleId,
            "-GenerateIfMissing",
            "-ReportPath",
            $verificationReportPath
        )
        $verificationCommand = Invoke-ProcessEvidence `
            -WorkspaceRoot $workspaceRoot `
            -WorkingDirectory $workspaceRoot `
            -Executable "pwsh" `
            -Arguments $verificationArguments `
            -LogPath $verificationLogPath
        $verificationCommand | Add-Member -NotePropertyName reportPath -NotePropertyValue (Convert-ToRelativePath -Root $workspaceRoot -Path $verificationReportPath)

        if ($verificationCommand.status -ne "passed") {
            Add-Issue $blockingIssues ("generation/runtime verification failed for release sample " + $sampleId)
        }

        if (Test-Path -LiteralPath $verificationReportPath -PathType Leaf) {
            try {
                $verificationReport = Read-JsonFile $verificationReportPath
                $firstVerificationResult = @($verificationReport.results | Select-Object -First 1)
                if ($firstVerificationResult.Count -gt 0 -and $null -ne $firstVerificationResult[0].cleanup) {
                    $cleanupEvidence = $firstVerificationResult[0].cleanup
                }
            }
            catch {
                Add-Issue $blockingIssues ("generation/runtime verification report was not valid JSON for " + $sampleId + ": " + $_.Exception.Message)
            }
        }
        else {
            Add-Issue $blockingIssues ("generation/runtime verification report was not produced for release sample " + $sampleId)
        }
    }
    elseif ($releaseBlocking) {
        New-VerificationLog -Path $verificationLogPath -SampleId $sampleId -Message "Generation/runtime verification was skipped by -SkipGenerationRuntimeVerification; this run cannot produce full release evidence."
        $verificationCommand = [pscustomobject]@{
            status = "not-run"
            workingDirectory = Convert-ToRelativePath -Root $workspaceRoot -Path $workspaceRoot
            executable = "pwsh"
            arguments = @("-NoProfile", "-File", "scripts/samples/verify-sample.ps1", "-SampleIds", $sampleId, "-GenerateIfMissing")
            display = "pwsh -NoProfile -File scripts/samples/verify-sample.ps1 -SampleIds $sampleId -GenerateIfMissing"
            exitCode = $null
            startedAt = $startedAt.ToString("o")
            endedAt = $startedAt.ToString("o")
            durationSeconds = 0
            outputLineCount = 0
            outputTail = @()
            logPath = Convert-ToRelativePath -Root $workspaceRoot -Path $verificationLogPath
            reportPath = Convert-ToRelativePath -Root $workspaceRoot -Path $verificationReportPath
            reason = "Generation/runtime verification was skipped by -SkipGenerationRuntimeVerification."
        }
    }
    else {
        New-VerificationLog -Path $verificationLogPath -SampleId $sampleId -Message "Fixture-only sample is excluded from release generation/runtime evidence."
        $verificationCommand = [pscustomobject]@{
            status = "not-run"
            workingDirectory = Convert-ToRelativePath -Root $workspaceRoot -Path $workspaceRoot
            executable = "pwsh"
            arguments = @("-NoProfile", "-File", "scripts/samples/verify-sample.ps1", "-SampleIds", $sampleId, "-GenerateIfMissing")
            display = "pwsh -NoProfile -File scripts/samples/verify-sample.ps1 -SampleIds $sampleId -GenerateIfMissing"
            exitCode = $null
            startedAt = $startedAt.ToString("o")
            endedAt = $startedAt.ToString("o")
            durationSeconds = 0
            outputLineCount = 0
            outputTail = @()
            logPath = Convert-ToRelativePath -Root $workspaceRoot -Path $verificationLogPath
            reportPath = Convert-ToRelativePath -Root $workspaceRoot -Path $verificationReportPath
            reason = "Fixture-only samples are excluded from release generation/runtime verification."
        }
    }

    $generationMarkerEvidence = Get-GenerationMarkerEvidence -WorkspaceRoot $workspaceRoot -MarkerPath (Join-Path $outputRoot "Reports/generation-run.json")
    if ($releaseBlocking -and -not $SkipGenerationRuntimeVerification -and $generationMarkerEvidence.status -ne "passed") {
        Add-Issue $blockingIssues ("generation marker is missing, invalid, or did not assemble the final app for release sample " + $sampleId)
    }

    if ($null -eq $cleanupEvidence) {
        $cleanupEvidence = [pscustomobject]@{
            status = if ($releaseBlocking -and -not $SkipGenerationRuntimeVerification) { "not-reported" } else { "not-run" }
            reportPath = if (Test-Path -LiteralPath $verificationReportPath -PathType Leaf) { Convert-ToRelativePath -Root $workspaceRoot -Path $verificationReportPath } else { "scripts/reports/out/sample-matrix-cleanup-report.json" }
            removedPaths = @()
            retainedEvidencePaths = @()
        }
    }
    $cleanupResult = [pscustomobject]@{
        sampleId = $sampleId
        mode = if ($null -ne $cleanupEvidence -and $cleanupEvidence.PSObject.Properties.Name -contains "status") { [string]$cleanupEvidence.status } else { "unknown" }
        removedPaths = if ($null -ne $cleanupEvidence -and $cleanupEvidence.PSObject.Properties.Name -contains "removedPaths") { @($cleanupEvidence.removedPaths) } else { @() }
        retainedEvidencePaths = if ($null -ne $cleanupEvidence -and $cleanupEvidence.PSObject.Properties.Name -contains "retainedEvidencePaths") { @($cleanupEvidence.retainedEvidencePaths) } else { @() }
    }
    $cleanupResults += $cleanupResult

    $status = if ($blockingIssues.Count -gt 0) {
        "failed"
    }
    elseif ($nonBlockingIssues.Count -gt 0) {
        "non-blocking-issues"
    }
    elseif ($classification -eq "fixture-only") {
        "fixture-only"
    }
    else {
        "passed"
    }

    $sampleResults += [pscustomobject]@{
        sampleId = $sampleId
        name = [string]$sample.name
        kind = [string]$sample.kind
        classification = $classification
        classificationReason = $classificationReason
        releaseBlocking = $releaseBlocking
        releaseCoverageEligible = $releaseBlocking
        status = $status
        inputRoot = Convert-ToRelativePath -Root $workspaceRoot -Path $inputRoot
        outputRoot = Convert-ToRelativePath -Root $workspaceRoot -Path $outputRoot
        aiBetaScenarios = @($policy.aiBetaScenarioLinks.$sampleId)
        requiredFiles = @($requiredFiles)
        inputFingerprint = $inputFingerprint
        verificationCommand = $verificationCommand
        generationMarker = $generationMarkerEvidence
        cleanup = $cleanupEvidence
        outputSummary = Get-DirectorySummary -WorkspaceRoot $workspaceRoot -Path (Join-Path $outputRoot "App")
        blockingIssues = @($blockingIssues)
        nonBlockingIssues = @($nonBlockingIssues)
        failures = @($blockingIssues)
        warnings = @($warnings + $nonBlockingIssues)
    }
}

$cleanupReport = [pscustomobject]@{
    schemaVersion = "npdev-sample-matrix-cleanup-report.v1"
    runId = $RunId
    generatedAt = (Get-Date).ToUniversalTime().ToString("o")
    scriptPath = "scripts/quality/run-sample-matrix.ps1"
    status = if ($SkipGenerationRuntimeVerification) { "not-run" } else { "collected" }
    reason = if ($SkipGenerationRuntimeVerification) { "Generation/runtime verification was skipped, so cleanup was not invoked by sample verification." } else { "Cleanup evidence was collected from each canonical sample verification report when available." }
    results = @($cleanupResults)
}
$cleanupReportPath = Resolve-RepoPath -WorkspaceRoot $workspaceRoot -Path "scripts/reports/out/sample-matrix-cleanup-report.json"
$cleanupReport | ConvertTo-Json -Depth 30 | Set-Content -LiteralPath $cleanupReportPath -Encoding UTF8

$blockingIssueCount = @($sampleResults | ForEach-Object { @($_.blockingIssues).Count } | Measure-Object -Sum).Sum
if ($null -eq $blockingIssueCount) { $blockingIssueCount = 0 }
$nonBlockingIssueCount = @($sampleResults | ForEach-Object { @($_.nonBlockingIssues).Count } | Measure-Object -Sum).Sum
if ($null -eq $nonBlockingIssueCount) { $nonBlockingIssueCount = 0 }
$policyFailureCount = $policyFailures.Count
$releaseBlockingSamples = @(Get-PolicyStringList -Policy $policy -PropertyName "releaseBlockingSamples")
$fixtureOnlySamples = @(Get-PolicyStringList -Policy $policy -PropertyName "fixtureOnlySamples")
$requiredResults = @($sampleResults | Where-Object { $_.classification -eq "release-sample" })
$requiredPassed = @($requiredResults | Where-Object { $_.status -eq "passed" }).Count
$requiredTotal = $releaseBlockingSamples.Count
$matrixCoveragePercent = if ($requiredTotal -gt 0) { [math]::Round(($requiredPassed / $requiredTotal) * 100, 2) } else { 0.0 }
$requiredCoveragePercent = if ($null -ne $policy.coverage -and $null -ne $policy.coverage.requiredReleaseCoveragePercent) { [double]$policy.coverage.requiredReleaseCoveragePercent } else { 100.0 }
$coverageSatisfied = $matrixCoveragePercent -ge $requiredCoveragePercent
$reportMode = if ($isPartialMatrix) { "partial-diagnostic" } else { "release-evidence" }
$inputContractEligible = (-not $isPartialMatrix) -and $policyFailureCount -eq 0 -and $blockingIssueCount -eq 0 -and $coverageSatisfied
$releaseVerificationPassed = @($requiredResults | Where-Object {
        $null -ne $_.verificationCommand -and
        [string]$_.verificationCommand.status -eq "passed" -and
        $null -ne $_.generationMarker -and
        [string]$_.generationMarker.status -eq "passed"
    }).Count
$generationRuntimeVerificationEligible = (-not $isPartialMatrix) -and
    (-not $SkipGenerationRuntimeVerification) -and
    $requiredTotal -gt 0 -and
    $releaseVerificationPassed -eq $requiredTotal
$releaseEligible = $inputContractEligible -and $generationRuntimeVerificationEligible
$overallStatus = if ($isPartialMatrix -and $AllowPartialMatrix -and $policyFailureCount -eq 0 -and $blockingIssueCount -eq 0) {
    "diagnostic"
}
elseif ($inputContractEligible) {
    "passed"
}
else {
    "failed"
}

$summary = [pscustomobject]@{
    totalCatalogSamples = $catalogSampleIds.Count
    evaluatedSamples = $sampleResults.Count
    releaseSamples = @($sampleResults | Where-Object { $_.classification -eq "release-sample" }).Count
    fixtureOnlySamples = @($sampleResults | Where-Object { $_.classification -eq "fixture-only" }).Count
    blockingIssueCount = [int]$blockingIssueCount
    nonBlockingIssueCount = [int]$nonBlockingIssueCount
    policyFailureCount = $policyFailureCount
    requiredSamplesPassed = $requiredPassed
    requiredSamplesTotal = $requiredTotal
    releaseSamplesWithGenerationRuntimeEvidence = $releaseVerificationPassed
    statusCounts = [pscustomobject]@{
        passed = @($sampleResults | Where-Object { $_.status -eq "passed" }).Count
        failed = @($sampleResults | Where-Object { $_.status -eq "failed" }).Count
        nonBlockingIssues = @($sampleResults | Where-Object { $_.status -eq "non-blocking-issues" }).Count
        fixtureOnly = @($sampleResults | Where-Object { $_.status -eq "fixture-only" }).Count
    }
}

$finishedAt = (Get-Date).ToUniversalTime()
$report = [pscustomobject]@{
    schemaVersion = "npdev-sample-matrix-report.v1"
    runId = $RunId
    generatedAt = $finishedAt.ToString("o")
    scriptPath = "scripts/quality/run-sample-matrix.ps1"
    workspaceRoot = $workspaceRoot
    sampleRoot = $sampleRootPath
    policyPath = $PolicyPath
    policy = [pscustomobject]@{
        path = $PolicyPath
        schemaVersion = [string]$policy.schemaVersion
        semantics = [string]$policy.semantics
        blockingClassifications = @($policy.blockingClassifications)
        nonBlockingClassifications = @($policy.nonBlockingClassifications)
        fixtureOnlySamples = @($policy.fixtureOnlySamples)
    }
    reportMode = $reportMode
    overallStatus = $overallStatus
    sampleCount = $sampleResults.Count
    releaseBlockingSamples = @($releaseBlockingSamples)
    fixtureOnlySamples = @($fixtureOnlySamples)
    matrixCoveragePercent = $matrixCoveragePercent
    coverage = [pscustomobject]@{
        semantics = [string]$policy.semantics
        requiredReleaseCoveragePercent = $requiredCoveragePercent
        requiredSamplesTotal = $requiredTotal
        requiredSamplesPassed = $requiredPassed
        requiredSamplesFailed = [math]::Max(0, $requiredTotal - $requiredPassed)
        fixtureOnlySamplesTotal = $fixtureOnlySamples.Count
        excludedFromReleaseCoverage = @($fixtureOnlySamples)
    }
    inputContractEvidence = [pscustomobject]@{
        eligible = $inputContractEligible
        status = if ($inputContractEligible) { "passed" } else { "failed" }
        scope = "strict-release-sample-input-contract"
        reason = if ($inputContractEligible) { "All release-blocking samples satisfied the strict sample input contract." } elseif ($isPartialMatrix) { "Partial diagnostic sample matrix runs are not release evidence." } else { "One or more release-blocking sample input-contract conditions failed." }
    }
    releaseEvidence = [pscustomobject]@{
        eligible = $releaseEligible
        status = if ($releaseEligible) { "passed" } else { "not-eligible" }
        scope = "ai-only-beta-0"
        semantics = [string]$policy.semantics
        reportMode = $reportMode
        decisionSource = "scripts/policy/sample-matrix-policy.json"
        blockingClassifications = @($policy.blockingClassifications)
        nonBlockingClassifications = @($policy.nonBlockingClassifications)
        generationRuntimeVerificationStatus = if ($generationRuntimeVerificationEligible) { "passed" } elseif ($SkipGenerationRuntimeVerification) { "skipped" } else { "failed" }
        releaseSamplesVerified = $releaseVerificationPassed
        releaseSamplesRequired = $requiredTotal
        reason = if ($releaseEligible) { "All release-blocking samples passed strict input-contract validation plus real generation/runtime verification." } elseif ($isPartialMatrix) { "Partial diagnostic sample matrix runs are not release evidence." } elseif ($SkipGenerationRuntimeVerification) { "Generation/runtime verification was skipped, so full release evidence is not eligible." } else { "One or more release-blocking samples lacks passing generation/runtime verification evidence." }
        directEvidence = @($requiredResults | ForEach-Object {
                [pscustomobject]@{
                    sampleId = [string]$_.sampleId
                    verificationStatus = [string]$_.verificationCommand.status
                    verificationCommand = [string]$_.verificationCommand.display
                    exitCode = $_.verificationCommand.exitCode
                    logPath = [string]$_.verificationCommand.logPath
                    reportPath = if ($_.verificationCommand.PSObject.Properties.Name -contains "reportPath") { [string]$_.verificationCommand.reportPath } else { $null }
                    generationMarkerStatus = [string]$_.generationMarker.status
                    generationMarkerPath = [string]$_.generationMarker.path
                    generationMarkerHash = [string]$_.generationMarker.sha256
                    appRoot = [string]$_.outputSummary.appRoot
                    appExists = [bool]$_.outputSummary.exists
                }
            })
    }
    inputFingerprints = @($inputFingerprints)
    coverageAssertions = [pscustomobject]@{
        releaseMatrixCoverageSatisfied = $coverageSatisfied
        releaseMatrixCoveragePercent = $matrixCoveragePercent
        requiredReleaseCoveragePercent = $requiredCoveragePercent
        releaseEvidenceEligible = $releaseEligible
        inputContractEvidenceEligible = $inputContractEligible
        generationRuntimeVerificationEligible = $generationRuntimeVerificationEligible
    }
    cleanupPolicy = [pscustomobject]@{
        mode = "not-run"
        reportPath = "scripts/reports/out/sample-matrix-cleanup-report.json"
        retainedEvidence = @("sample matrix report", "schema validation reports", "verification not-run logs")
    }
    commands = @(
        [pscustomobject]@{
            name = "sample-input-contract-validation"
            status = if ($policyFailureCount -eq 0 -and $blockingIssueCount -eq 0) { "passed" } else { "failed" }
            startedAt = $startedAt.ToString("o")
            endedAt = $finishedAt.ToString("o")
            durationSeconds = [math]::Round(([DateTimeOffset]$finishedAt - [DateTimeOffset]$startedAt).TotalSeconds, 3)
            outputSummary = "Validated sample policy classifications, required input files, JSON schemas, fingerprints, and release eligibility semantics."
        }
        [pscustomobject]@{
            name = "sample-generation-runtime-verification"
            status = if ($generationRuntimeVerificationEligible) { "passed" } elseif ($SkipGenerationRuntimeVerification) { "skipped" } else { "failed" }
            startedAt = $startedAt.ToString("o")
            endedAt = $finishedAt.ToString("o")
            durationSeconds = [math]::Round(([DateTimeOffset]$finishedAt - [DateTimeOffset]$startedAt).TotalSeconds, 3)
            outputSummary = "Executed canonical sample verification for release-blocking samples and collected command/report/log/generation-marker evidence."
            releaseSamplesVerified = $releaseVerificationPassed
            releaseSamplesRequired = $requiredTotal
        }
    )
    blockingIssueCount = [int]$blockingIssueCount
    nonBlockingIssueCount = [int]$nonBlockingIssueCount
    policyFailures = @($policyFailures)
    negativeFixture = [pscustomobject]@{
        path = $negativeFixturePath
        expectedStatus = "failed"
        status = [string]$negativeFixture.status
        failureCount = @($negativeFixture.failures).Count
    }
    summary = $summary
    samples = @($sampleResults)
    results = @($sampleResults)
}

$report | ConvertTo-Json -Depth 50 | Set-Content -LiteralPath $ReportPath -Encoding UTF8

if ($overallStatus -eq "passed" -or $overallStatus -eq "diagnostic") {
    Write-Host ("Sample matrix " + $overallStatus + ". Report: " + $ReportPath)
    exit 0
}

Write-Error ("Sample matrix failed. Report: " + $ReportPath)
