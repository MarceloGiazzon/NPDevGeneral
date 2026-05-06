param(
    [string]$PolicyPath = "scripts/policy/beta-release-gate-policy.json",
    [string]$ReportPath = "scripts/reports/out/beta-release-gate-report.json",
    [string]$ManifestPath = "scripts/reports/out/beta-release-evidence-manifest.json",
    [string]$SummaryPath = "scripts/reports/out/release-ready-summary.json",
    [string]$RunId = "",
    [int]$MaxReportAgeHours = 0
)

$ErrorActionPreference = "Stop"

function Read-JsonFile {
    param([string]$Path)
    return Get-Content -Raw -LiteralPath $Path | ConvertFrom-Json
}

function Add-Blocker {
    param([System.Collections.Generic.List[string]]$Blockers, [string]$Message)
    if (-not [string]::IsNullOrWhiteSpace($Message)) {
        $Blockers.Add($Message) | Out-Null
    }
}

function Resolve-RepoPath {
    param([string]$PathValue)
    if ([System.IO.Path]::IsPathRooted($PathValue)) {
        return [System.IO.Path]::GetFullPath($PathValue)
    }
    return [System.IO.Path]::GetFullPath((Join-Path (Resolve-Path ".").Path $PathValue))
}

function Get-JsonPropertyValue {
    param([object]$ObjectValue, [string]$PropertyName)
    if ($null -eq $ObjectValue) {
        return $null
    }
    $property = $ObjectValue.PSObject.Properties[$PropertyName]
    if ($null -eq $property) {
        return $null
    }
    return $property.Value
}

function Get-ReportByName {
    param([object[]]$Reports, [string]$Name)
    return @($Reports | Where-Object { [string]$_.name -eq $Name } | Select-Object -First 1)
}

function New-TruthEvaluation {
    param([string]$Name, [string[]]$Requires, [hashtable]$Inputs)
    $requirementResults = @()
    $passed = $true
    foreach ($requirement in @($Requires)) {
        $value = [bool]$Inputs[$requirement]
        if (-not $value) { $passed = $false }
        $requirementResults += [pscustomobject]@{
            name = $requirement
            passed = $value
        }
    }
    return [pscustomobject]@{
        name = $Name
        passed = $passed
        requires = $requirementResults
    }
}

function Invoke-GitText {
    param([string[]]$Arguments)
    try {
        $output = & git @Arguments 2>$null
        if ($LASTEXITCODE -ne 0) {
            return ""
        }
        return (($output | Out-String).TrimEnd())
    }
    catch {
        return ""
    }
}

function Convert-GitStatusLineToPath {
    param([string]$Line)
    if ([string]::IsNullOrWhiteSpace($Line)) { return "" }
    $value = $Line
    if ($value.Length -ge 4) {
        $value = $value.Substring(3)
    }
    $value = $value.Trim()
    if ($value -match " -> ") {
        $parts = $value -split " -> "
        $value = $parts[$parts.Count - 1]
    }
    $value = $value.Trim('"') -replace "\\", "/"
    return $value
}

function Test-AllowedGeneratedEvidenceDirtyPath {
    param([string]$PathValue)
    $normalized = ([string]$PathValue) -replace "\\", "/"
    if ($normalized -match "^scripts/reports/out/[^/]+\.(json|log)$") { return $true }
    if ($normalized -match "^scripts/reports/tmp/") { return $true }
    return $false
}

function New-HashForLines {
    param([string[]]$Lines)
    $joined = (@($Lines) -join "`n")
    if ([string]::IsNullOrWhiteSpace($joined)) { return $null }
    $bytes = [System.Text.Encoding]::UTF8.GetBytes($joined)
    $hash = [System.Security.Cryptography.SHA256]::HashData($bytes)
    return ([System.BitConverter]::ToString($hash) -replace "-", "").ToLowerInvariant()
}

function Get-DirtyFingerprint {
    $status = Invoke-GitText @("status", "--porcelain=v1")
    if ([string]::IsNullOrWhiteSpace($status)) {
        return [pscustomobject]@{
            dirty = $false
            dirtyFileCount = 0
            dirtyHash = $null
            rawDirty = $false
            rawDirtyFileCount = 0
            rawDirtyHash = $null
            allowedGeneratedEvidenceDirty = $false
            allowedGeneratedEvidenceDirtyFileCount = 0
            dirtyPaths = @()
            allowedGeneratedEvidenceDirtyPaths = @()
        }
    }

    $lines = @($status -split "`r?`n" | Where-Object { -not [string]::IsNullOrWhiteSpace($_) })
    $sourceDirtyLines = @()
    $sourceDirtyPaths = @()
    $allowedLines = @()
    $allowedPaths = @()

    foreach ($line in $lines) {
        $pathValue = Convert-GitStatusLineToPath $line
        if (Test-AllowedGeneratedEvidenceDirtyPath $pathValue) {
            $allowedLines += $line
            $allowedPaths += $pathValue
        }
        else {
            $sourceDirtyLines += $line
            $sourceDirtyPaths += $pathValue
        }
    }

    return [pscustomobject]@{
        dirty = @($sourceDirtyLines).Count -gt 0
        dirtyFileCount = @($sourceDirtyLines).Count
        dirtyHash = New-HashForLines $sourceDirtyLines
        rawDirty = @($lines).Count -gt 0
        rawDirtyFileCount = @($lines).Count
        rawDirtyHash = New-HashForLines $lines
        allowedGeneratedEvidenceDirty = @($allowedLines).Count -gt 0
        allowedGeneratedEvidenceDirtyFileCount = @($allowedLines).Count
        dirtyPaths = @($sourceDirtyPaths)
        allowedGeneratedEvidenceDirtyPaths = @($allowedPaths)
    }
}

function Test-ReportFreshness {
    param([object]$Report, [int]$MaxAgeHours)
    $generatedAtValue = Get-JsonPropertyValue $Report "generatedAt"
    if ([string]::IsNullOrWhiteSpace([string]$generatedAtValue)) {
        return [pscustomobject]@{
            fresh = $false
            generatedAt = $null
            ageSeconds = $null
            reason = "generatedAt is missing"
        }
    }
    try {
        if ($generatedAtValue -is [DateTimeOffset]) {
            $generatedAt = $generatedAtValue.ToUniversalTime()
        }
        elseif ($generatedAtValue -is [DateTime]) {
            $generatedAt = [DateTimeOffset]::new(([DateTime]$generatedAtValue).ToUniversalTime())
        }
        else {
            $generatedAt = [DateTimeOffset]::Parse([string]$generatedAtValue, [System.Globalization.CultureInfo]::InvariantCulture).ToUniversalTime()
        }
        $age = [DateTimeOffset]::UtcNow - $generatedAt
        return [pscustomobject]@{
            fresh = $age.TotalHours -le $MaxAgeHours
            generatedAt = $generatedAt.ToString("o")
            ageSeconds = [int][Math]::Max(0, $age.TotalSeconds)
            reason = if ($age.TotalHours -le $MaxAgeHours) { "" } else { "report is older than max age" }
        }
    }
    catch {
        return [pscustomobject]@{
            fresh = $false
            generatedAt = [string]$generatedAtValue
            ageSeconds = $null
            reason = "generatedAt is not parseable"
        }
    }
}

function Test-RequiredReport {
    param([object]$Definition, [int]$MaxAgeHours, [System.Collections.Generic.List[string]]$Blockers)
    $relativePath = [string]$Definition.path
    $fullPath = Resolve-RepoPath $relativePath
    $exists = Test-Path -LiteralPath $fullPath -PathType Leaf
    if (-not $exists) {
        Add-Blocker $Blockers ("Required report is missing: " + $relativePath)
        return [pscustomobject]@{
            name = [string]$Definition.name
            path = $relativePath
            exists = $false
            status = "missing"
            runId = $null
            fresh = $false
            valid = $false
            contentSha256 = $null
            generatedAt = $null
            ageSeconds = $null
            blockers = @("missing")
        }
    }

    $reportBlockers = [System.Collections.Generic.List[string]]::new()
    $report = $null
    try {
        $report = Read-JsonFile $fullPath
    }
    catch {
        Add-Blocker $reportBlockers ("Report is not valid JSON: " + $_.Exception.Message)
    }

    $schemaVersion = if ($null -ne $report) { [string](Get-JsonPropertyValue $report "schemaVersion") } else { "" }
    $reportRunId = if ($null -ne $report) { [string](Get-JsonPropertyValue $report "runId") } else { "" }
    if ($null -ne $report -and $schemaVersion -ne [string]$Definition.schemaVersion) {
        Add-Blocker $reportBlockers ("Expected schemaVersion " + [string]$Definition.schemaVersion + " but got " + $schemaVersion)
    }

    $actualStatus = if ($null -ne $report) { [string](Get-JsonPropertyValue $report ([string]$Definition.statusProperty)) } else { "" }
    if ($null -ne $report -and $actualStatus -ne [string]$Definition.passValue) {
        Add-Blocker $reportBlockers ("Expected " + [string]$Definition.statusProperty + "=" + [string]$Definition.passValue + " but got " + $actualStatus)
    }

    $schemaPath = [string](Get-JsonPropertyValue $Definition "schemaPath")
    if ($null -ne $report -and -not [string]::IsNullOrWhiteSpace($schemaPath)) {
        $schemaValidationPath = Join-Path "scripts/reports/tmp/report-schema-validation" (([string]$Definition.name) + ".json")
        $ErrorActionPreference = "Continue"
        pwsh -NoProfile -File scripts/quality/Invoke-JsonSchemaValidation.ps1 `
            -SchemaPath $schemaPath `
            -JsonPath $fullPath `
            -ReportPath $schemaValidationPath 2>$null | Out-Null
        $schemaExit = $LASTEXITCODE
        $ErrorActionPreference = "Stop"
        $schemaResult = if (Test-Path -LiteralPath $schemaValidationPath -PathType Leaf) { Read-JsonFile $schemaValidationPath } else { $null }
        if ($schemaExit -ne 0 -or $null -eq $schemaResult -or [string]$schemaResult.status -ne "passed") {
            $schemaFailures = if ($null -ne $schemaResult) { @($schemaResult.failures) -join "; " } else { "schema validation did not write a result" }
            Add-Blocker $reportBlockers ("Report schema validation failed: " + $schemaFailures)
        }
    }

    $freshness = if ($null -ne $report) {
        Test-ReportFreshness $report $MaxAgeHours
    }
    else {
        [pscustomobject]@{ fresh = $false; generatedAt = $null; ageSeconds = $null; reason = "report could not be read" }
    }
    if (-not [bool]$freshness.fresh) {
        Add-Blocker $reportBlockers ("Freshness failed: " + [string]$freshness.reason)
    }

    foreach ($blocker in @($reportBlockers)) {
        Add-Blocker $Blockers ([string]$Definition.name + ": " + $blocker)
    }

    return [pscustomobject]@{
        name = [string]$Definition.name
        path = $relativePath
        exists = $true
        status = $actualStatus
        runId = $reportRunId
        fresh = [bool]$freshness.fresh
        valid = $reportBlockers.Count -eq 0
        contentSha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath $fullPath).Hash.ToLowerInvariant()
        generatedAt = $freshness.generatedAt
        ageSeconds = $freshness.ageSeconds
        blockers = @($reportBlockers)
    }
}

$workspaceRoot = (Resolve-Path ".").Path
$policyPathFull = Resolve-RepoPath $PolicyPath
$policy = Read-JsonFile $policyPathFull
$scopePolicy = Read-JsonFile (Resolve-RepoPath ([string]$policy.scopePolicy))
$truthTable = Read-JsonFile (Resolve-RepoPath ([string]$policy.truthTable))
$existingRunIds = @()
foreach ($definition in @($policy.requiredReports)) {
    $candidateReportPath = Resolve-RepoPath ([string]$definition.path)
    if (Test-Path -LiteralPath $candidateReportPath -PathType Leaf) {
        try {
            $candidateReport = Read-JsonFile $candidateReportPath
            $candidateRunId = [string](Get-JsonPropertyValue $candidateReport "runId")
            if (-not [string]::IsNullOrWhiteSpace($candidateRunId)) {
                $existingRunIds += $candidateRunId
            }
        }
        catch {
        }
    }
}
$existingUniqueRunIds = @($existingRunIds | Select-Object -Unique)
if ([string]::IsNullOrWhiteSpace($RunId)) {
    $RunId = if ($existingUniqueRunIds.Count -eq 1) { [string]$existingUniqueRunIds[0] } else { "beta-release-gate-" + (Get-Date).ToUniversalTime().ToString("yyyyMMdd-HHmmssfff") }
}
$maxAge = if ($MaxReportAgeHours -gt 0) { $MaxReportAgeHours } else { [int]$policy.maxReportAgeHours }
$blockers = [System.Collections.Generic.List[string]]::new()

try {
    pwsh -NoProfile -File scripts/quality/write-ai-beta-reproducibility-report.ps1 -ReportPath "scripts/reports/out/ai-beta-reproducibility-report.json" -RunId $RunId | Out-Null
}
catch {
    Add-Blocker $blockers ("Failed to refresh reproducibility report: " + $_.Exception.Message)
}

if ($policy.schemaVersion -ne "npdev-beta-release-gate-policy.v1" -or $policy.release -ne "ai-only-beta-0") {
    Add-Blocker $blockers "Beta release gate policy is missing or not ai-only-beta-0."
}
if ($scopePolicy.schemaVersion -notin @("npdev-beta0-scope.v1", "npdev-beta0-scope.v2") -or $scopePolicy.release -ne "ai-only-beta-0") {
    Add-Blocker $blockers "Beta 0 scope policy is missing or not ai-only-beta-0."
}
if ($truthTable.schemaVersion -ne "npdev-beta0-release-truth-table.v1") {
    Add-Blocker $blockers "Beta 0 release truth table is missing or invalid."
}

$requiredReports = @()
if (Test-Path -LiteralPath "scripts/reports/tmp/report-schema-validation") {
    Remove-Item -LiteralPath "scripts/reports/tmp/report-schema-validation" -Recurse -Force
}
New-Item -ItemType Directory -Force -Path "scripts/reports/tmp/report-schema-validation" | Out-Null
foreach ($definition in @($policy.requiredReports)) {
    $requiredReports += Test-RequiredReport $definition $maxAge $blockers
}

$requiredRunIds = @($requiredReports | Where-Object { $_.exists } | ForEach-Object { [string]$_.runId })
$missingRunIdReports = @($requiredReports | Where-Object { $_.exists -and [string]::IsNullOrWhiteSpace([string]$_.runId) } | ForEach-Object { [string]$_.name })
$uniqueRunIds = @($requiredRunIds | Where-Object { -not [string]::IsNullOrWhiteSpace($_) } | Select-Object -Unique)
if ($missingRunIdReports.Count -gt 0) {
    Add-Blocker $blockers ("Required reports are missing runId: " + ($missingRunIdReports -join ", "))
}
if ($uniqueRunIds.Count -ne 1) {
    Add-Blocker $blockers ("Required reports must share exactly one runId; found: " + ($uniqueRunIds -join ", "))
}
elseif ([string]$uniqueRunIds[0] -ne $RunId) {
    Add-Blocker $blockers ("Required report runId " + [string]$uniqueRunIds[0] + " does not match aggregate runId " + $RunId + ".")
}

$informationalReports = @()
foreach ($relativePath in @($policy.informationalReports)) {
    $fullPath = Resolve-RepoPath ([string]$relativePath)
    $informationalReports += [pscustomobject]@{
        path = [string]$relativePath
        exists = (Test-Path -LiteralPath $fullPath -PathType Leaf)
        contentSha256 = if (Test-Path -LiteralPath $fullPath -PathType Leaf) { (Get-FileHash -Algorithm SHA256 -LiteralPath $fullPath).Hash.ToLowerInvariant() } else { $null }
    }
}

$dirty = Get-DirtyFingerprint
$commit = Invoke-GitText @("rev-parse", "HEAD")
$branch = Invoke-GitText @("branch", "--show-current")
$officialBlockers = [System.Collections.Generic.List[string]]::new()
if ([string]::IsNullOrWhiteSpace($commit)) {
    Add-Blocker $officialBlockers "Git commit identity is missing."
}
if ([bool]$dirty.dirty) {
    Add-Blocker $officialBlockers ("Workspace is dirty; official release eligibility is blocked. Dirty file count: " + [string]$dirty.dirtyFileCount)
}
$reportsFresh = @($requiredReports | Where-Object { -not [bool]$_.fresh }).Count -eq 0
$noStaleContradictoryReports = $true
$activeContradictoryReports = @()
$selfReportPaths = @(
    "scripts/reports/out/beta-release-gate-report.json",
    "scripts/reports/out/beta-release-evidence-manifest.json",
    "scripts/reports/out/release-ready-summary.json",
    "scripts/reports/out/beta0-final-closure-report.json",
    "scripts/reports/out/beta0-final-release-check-report.json"
)
foreach ($jsonFile in @(Get-ChildItem -LiteralPath (Resolve-RepoPath "scripts/reports/out") -Filter "*.json" -File -ErrorAction SilentlyContinue)) {
    try {
        $relativeJsonPath = [System.IO.Path]::GetRelativePath($workspaceRoot, $jsonFile.FullName) -replace "\\", "/"
        if ($selfReportPaths -contains $relativeJsonPath) { continue }
        $activeReport = Read-JsonFile $jsonFile.FullName
        $activeStatus = [string](Get-JsonPropertyValue $activeReport "overallStatus")
        if ([string]::IsNullOrWhiteSpace($activeStatus)) { $activeStatus = [string](Get-JsonPropertyValue $activeReport "status") }
        $activeFlag = Get-JsonPropertyValue $activeReport "active"
        if ($null -ne $activeFlag -and [bool]$activeFlag -eq $false) { continue }
        if ($activeStatus -eq "failed" -and @($requiredReports.path) -notcontains $relativeJsonPath) {
            $noStaleContradictoryReports = $false
            $activeContradictoryReports += $relativeJsonPath
        }
    }
    catch {
    }
}
if (-not $noStaleContradictoryReports) {
    Add-Blocker $officialBlockers ("Active contradictory failed reports found: " + ($activeContradictoryReports -join ", "))
}
$workspaceFingerprintSource = ($commit + "|" + [string]$dirty.dirty + "|" + [string]$dirty.dirtyFileCount + "|" + [string]$dirty.dirtyHash)
$workspaceFingerprintBytes = [System.Text.Encoding]::UTF8.GetBytes($workspaceFingerprintSource)
$workspaceFingerprint = ([System.BitConverter]::ToString([System.Security.Cryptography.SHA256]::HashData($workspaceFingerprintBytes)) -replace "-", "").ToLowerInvariant()

$inputs = @{
    aiBetaGatePassed = [bool](Get-ReportByName $requiredReports "ai-beta-gate").valid
    sampleMatrixPassed = [bool](Get-ReportByName $requiredReports "sample-matrix").valid
    normalizerTestsPassed = [bool](Get-ReportByName $requiredReports "ai-contract-normalizer-tests").valid
    restSmokeVerifierTestsPassed = [bool](Get-ReportByName $requiredReports "ai-rest-smoke-verifier-tests").valid
    controlledCommandRunnerTestsPassed = [bool](Get-ReportByName $requiredReports "controlled-command-runner-tests").valid
    schemaValidatorTestsPassed = [bool](Get-ReportByName $requiredReports "json-schema-validator-tests").valid
    reportSchemaValidationPassed = [bool](Get-ReportByName $requiredReports "report-schema-validation").valid
    workspaceClean = -not [bool]$dirty.dirty
    commitIdentityAvailable = -not [string]::IsNullOrWhiteSpace($commit)
    reportsFresh = $reportsFresh
    approvedEvidencePlatform = ([string]$scopePolicy.officialEvidencePlatform -eq "windows-ci" -and [string]$scopePolicy.dockerLinuxEvidence -eq "experimental-non-release")
    noStaleContradictoryReports = $noStaleContradictoryReports
}
$candidateEvaluation = New-TruthEvaluation "candidateReady" @($truthTable.candidateReady.requires) $inputs
$inputs["candidateReady"] = [bool]$candidateEvaluation.passed -and $blockers.Count -eq 0
$releaseEvaluation = New-TruthEvaluation "releaseReady" @($truthTable.releaseReady.requires) $inputs
$inputs["releaseReady"] = [bool]$releaseEvaluation.passed -and $blockers.Count -eq 0
$provenanceEvaluation = New-TruthEvaluation "provenanceReady" @($truthTable.provenanceReady.requires) $inputs
$inputs["provenanceReady"] = [bool]$provenanceEvaluation.passed -and $officialBlockers.Count -eq 0
$officialEvaluation = New-TruthEvaluation "officialReleaseEligible" @($truthTable.officialReleaseEligible.requires) $inputs
$candidateReady = [bool]$inputs["candidateReady"]
$releaseReady = [bool]$inputs["releaseReady"]
$provenanceReady = [bool]$inputs["provenanceReady"]
$officialReleaseEligible = [bool]$officialEvaluation.passed -and $releaseReady -and $provenanceReady
$status = if ($releaseReady -and $officialReleaseEligible) { "passed" } else { "failed" }
$generatedAt = (Get-Date).ToUniversalTime().ToString("o")

$manifest = [pscustomobject]@{
    schemaVersion = "npdev-beta-release-evidence-manifest.v1"
    runId = $RunId
    generatedAt = $generatedAt
    release = "ai-only-beta-0"
    workspaceRoot = $workspaceRoot
    git = [pscustomobject]@{
        branch = $branch
        commit = $commit
        dirty = [bool]$dirty.dirty
        dirtyFileCount = [int]$dirty.dirtyFileCount
        dirtyHash = $dirty.dirtyHash
        rawDirty = [bool]$dirty.rawDirty
        rawDirtyFileCount = [int]$dirty.rawDirtyFileCount
        rawDirtyHash = $dirty.rawDirtyHash
        allowedGeneratedEvidenceDirty = [bool]$dirty.allowedGeneratedEvidenceDirty
        allowedGeneratedEvidenceDirtyFileCount = [int]$dirty.allowedGeneratedEvidenceDirtyFileCount
        allowedGeneratedEvidenceDirtyPaths = @($dirty.allowedGeneratedEvidenceDirtyPaths)
        dirtyPaths = @($dirty.dirtyPaths)
        generatedReportDirtinessPolicy = "dirty paths under scripts/reports/out/*.json, scripts/reports/out/*.log, and scripts/reports/tmp/** are generated evidence and do not block official eligibility; any other dirty path blocks."
        workspaceFingerprint = $workspaceFingerprint
    }
    candidateReady = $candidateReady
    releaseReady = $releaseReady
    provenanceReady = $provenanceReady
    officialReleaseEligible = $officialReleaseEligible
    requiredReports = $requiredReports
    informationalReports = $informationalReports
}

$report = [pscustomobject]@{
    schemaVersion = "beta-release-gate-report.v1"
    runId = $RunId
    status = $status
    overallStatus = $status
    candidateReady = $candidateReady
    releaseReady = $releaseReady
    provenanceReady = $provenanceReady
    officialReleaseEligible = $officialReleaseEligible
    generatedAt = $generatedAt
    scriptPath = "scripts/quality/run-beta-release-gate.ps1"
    workspaceRoot = $workspaceRoot
    policyPath = $PolicyPath
    maxReportAgeHours = $maxAge
    sourceOfTruth = "scripts/reports/out/beta-release-gate-report.json"
    evidenceManifest = "scripts/reports/out/beta-release-evidence-manifest.json"
    releaseReadySummary = "scripts/reports/out/release-ready-summary.json"
    git = $manifest.git
    requiredReports = $requiredReports
    informationalReports = $informationalReports
    blockers = @($blockers + $officialBlockers)
    officialEligibilityBlockers = @($officialBlockers)
    officialEvidencePlatform = [string]$policy.officialEvidencePlatform
    dockerLinuxEvidence = [string]$policy.dockerLinuxEvidence
    activeContradictoryReports = $activeContradictoryReports
    truthTableEvaluation = @($candidateEvaluation, $releaseEvaluation, $provenanceEvaluation, $officialEvaluation)
    decisionRule = [string]$policy.readinessRule
}

$summary = [pscustomobject]@{
    schemaVersion = "npdev-release-ready-summary.v1"
    runId = $RunId
    generatedAt = $generatedAt
    release = "ai-only-beta-0"
    status = $status
    candidateReady = $candidateReady
    releaseReady = $releaseReady
    provenanceReady = $provenanceReady
    officialReleaseEligible = $officialReleaseEligible
    sourceOfTruth = "scripts/reports/out/beta-release-gate-report.json"
    evidenceManifest = "scripts/reports/out/beta-release-evidence-manifest.json"
    commit = $commit
    workspaceFingerprint = $workspaceFingerprint
    blockerCount = $blockers.Count
    officialEligibilityBlockerCount = $officialBlockers.Count
}

foreach ($path in @($ReportPath, $ManifestPath, $SummaryPath)) {
    $directory = Split-Path -Parent (Resolve-RepoPath $path)
    if (-not [string]::IsNullOrWhiteSpace($directory)) {
        New-Item -ItemType Directory -Force -Path $directory | Out-Null
    }
}

$report | ConvertTo-Json -Depth 30 | Set-Content -LiteralPath (Resolve-RepoPath $ReportPath) -Encoding UTF8
$manifest | ConvertTo-Json -Depth 30 | Set-Content -LiteralPath (Resolve-RepoPath $ManifestPath) -Encoding UTF8
$summary | ConvertTo-Json -Depth 20 | Set-Content -LiteralPath (Resolve-RepoPath $SummaryPath) -Encoding UTF8

$selfSchemaResultPath = "scripts/reports/tmp/report-schema-validation/beta-release-gate-report-self.json"
$ErrorActionPreference = "Continue"
pwsh -NoProfile -File scripts/quality/Invoke-JsonSchemaValidation.ps1 `
    -SchemaPath "schemas/ai/beta-release-gate-report.schema.json" `
    -JsonPath (Resolve-RepoPath $ReportPath) `
    -ReportPath $selfSchemaResultPath 2>$null | Out-Null
$selfSchemaExit = $LASTEXITCODE
$ErrorActionPreference = "Stop"
if ($selfSchemaExit -ne 0) {
    Write-Error ("Beta release gate report failed its report schema. Validation: " + $selfSchemaResultPath)
}

if ($status -eq "passed") {
    Write-Host ("Beta release gate passed. Report: " + $ReportPath)
    exit 0
}

Write-Error ("Beta release gate failed. Report: " + $ReportPath)
