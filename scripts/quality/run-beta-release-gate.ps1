# Beta release gate.
#
# EVIDENCE ORCHESTRATION (REG-3, 2026-07-21)
# ------------------------------------------
# This gate EVALUATES evidence; it does not, by default, PRODUCE it. The 36 reports listed in
# scripts/policy/beta-release-gate-policy.json#requiredReports are written by ~18 separate producer
# scripts. Pass -GenerateReports to run those producers first (via
# scripts/quality/run-beta-release-evidence-orchestration.ps1, which shares this run's RunId across
# every producer so the gate's single-runId rule is satisfiable), or run that orchestration script
# yourself as a documented manual pre-step.
#
# Decision (REG-3 step 4): orchestration is available in-gate but OPT-IN. Producing the full evidence
# set builds and boots applications and can take a long time; making that implicit in every gate
# invocation would make the cheap "evaluate what we have" path unavailable. -GenerateReports makes
# "run the release gate" one command again when that is what you want.
#
# EXIT CODES (REG-3 step 5) - the distinction that matters:
#   0  passed
#   2  PRECONDITION-UNMET  - required evidence was never generated (reports missing/unreadable).
#                            The gate could not evaluate the release; this is NOT a release failure.
#   1  CHECK-FAILED        - evidence exists and something it asserts is actually broken.
# Conflating 2 with 1 is what let GATE-REL-1/REG-3 stand misdiagnosed as a policy conflict for two
# months: 35 of 36 reports were simply absent, and the gate reported that identically to a real
# failure. The first output line always names which of the three states applies.

param(
    [string]$PolicyPath = "scripts/policy/beta-release-gate-policy.json",
    [string]$ReportPath = "scripts/reports/out/beta-release-gate-report.json",
    [string]$ManifestPath = "scripts/reports/out/beta-release-evidence-manifest.json",
    [string]$SummaryPath = "scripts/reports/out/release-ready-summary.json",
    [string]$RunId = "",
    [int]$MaxReportAgeHours = 0,
    [switch]$GenerateReports,
    [string]$OrchestrationScriptPath = "scripts/quality/run-beta-release-evidence-orchestration.ps1",
    [string[]]$SkipProducers = @()
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

function Resolve-OutsideRepoScratchPath {
    param([string]$Name)
    if (-not [string]::IsNullOrWhiteSpace($env:NPDEV_WORKSPACE_SCRATCH_ROOT)) {
        return [System.IO.Path]::GetFullPath((Join-Path $env:NPDEV_WORKSPACE_SCRATCH_ROOT $Name))
    }
    $workspace = Get-Item -LiteralPath $workspaceRoot
    $outsideRepoRoot = Join-Path $workspace.Parent.FullName ($workspace.Name + "__OutsideRepo")
    return [System.IO.Path]::GetFullPath((Join-Path (Join-Path $outsideRepoRoot "temp") $Name))
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

function Get-ReportFileName {
    param([string]$PathValue)
    if ([string]::IsNullOrWhiteSpace($PathValue)) {
        return ""
    }
    return [System.IO.Path]::GetFileName(([string]$PathValue).Replace("\", "/"))
}

function Get-NormalizedReportFileNames {
    param([object[]]$Values)
    return @($Values | ForEach-Object {
            if ($null -eq $_) {
                return
            }
            if ($_.PSObject.Properties.Name -contains "path") {
                Get-ReportFileName ([string]$_.path)
            }
            else {
                Get-ReportFileName ([string]$_)
            }
        } | Where-Object { -not [string]::IsNullOrWhiteSpace($_) } | Sort-Object -Unique)
}

function Test-ScopePolicyReportAlignment {
    param(
        [object]$ScopePolicy,
        [object]$ReleasePolicy
    )
    $scopeBlockingReports = @(Get-NormalizedReportFileNames @($ScopePolicy.blockingReports))
    $releaseRequiredReports = @(Get-NormalizedReportFileNames @($ReleasePolicy.requiredReports))
    $missingFromReleasePolicy = @($scopeBlockingReports | Where-Object { $releaseRequiredReports -notcontains $_ })
    $missingFromScopePolicy = @($releaseRequiredReports | Where-Object { $scopeBlockingReports -notcontains $_ })
    $duplicatesInScopePolicy = @(@($ScopePolicy.blockingReports | ForEach-Object { Get-ReportFileName ([string]$_) }) |
        Group-Object | Where-Object { $_.Count -gt 1 } | ForEach-Object { $_.Name })
    $duplicatesInReleasePolicy = @(@($ReleasePolicy.requiredReports | ForEach-Object { Get-ReportFileName ([string]$_.path) }) |
        Group-Object | Where-Object { $_.Count -gt 1 } | ForEach-Object { $_.Name })
    $passed = $missingFromReleasePolicy.Count -eq 0 -and
        $missingFromScopePolicy.Count -eq 0 -and
        $duplicatesInScopePolicy.Count -eq 0 -and
        $duplicatesInReleasePolicy.Count -eq 0
    return [pscustomobject]@{
        status = if ($passed) { "passed" } else { "failed" }
        authoritativeScopePolicy = "scripts/policy/beta0-scope.json"
        comparedField = "blockingReports"
        releasePolicyField = "requiredReports.path"
        scopeBlockingReports = $scopeBlockingReports
        releaseRequiredReports = $releaseRequiredReports
        missingFromReleasePolicy = $missingFromReleasePolicy
        missingFromScopePolicy = $missingFromScopePolicy
        duplicatesInScopePolicy = $duplicatesInScopePolicy
        duplicatesInReleasePolicy = $duplicatesInReleasePolicy
    }
}

function New-TruthEvaluation {
    param(
        [string]$Name,
        [string[]]$Requires,
        [hashtable]$Inputs,
        [hashtable]$InputEvidence = @{}
    )
    $requirementResults = @()
    $passed = $true
    foreach ($requirement in @($Requires)) {
        $value = [bool]$Inputs[$requirement]
        if (-not $value) { $passed = $false }
        $requirementResults += [pscustomobject]@{
            name = $requirement
            passed = $value
            evidence = if ($InputEvidence.ContainsKey($requirement)) { $InputEvidence[$requirement] } else { [pscustomobject]@{ evidenceType = "diagnostic-inferred"; releaseBlocking = $false; reason = "No direct evidence mapping was provided for this diagnostic input." } }
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
    if ($normalized -match "^scripts/reports/releases/") { return $true }
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

function Get-NestedJsonValue {
    param([object]$ObjectValue, [string]$Path)
    if ($null -eq $ObjectValue -or [string]::IsNullOrWhiteSpace($Path)) {
        return $null
    }
    $current = $ObjectValue
    foreach ($part in @($Path -split "\.")) {
        if ($null -eq $current) {
            return $null
        }
        $property = $current.PSObject.Properties[$part]
        if ($null -eq $property) {
            return $null
        }
        $current = $property.Value
    }
    return $current
}

function Test-EvidenceRequirement {
    param(
        [object]$Requirement,
        [object]$Report,
        [string]$ReportPath
    )
    $path = [string]$Requirement.path
    $actual = Get-NestedJsonValue $Report $path
    $expected = $Requirement.expected
    $matches = $false
    if ($expected -is [bool]) {
        $matches = $null -ne $actual -and [bool]$actual -eq [bool]$expected
    }
    elseif ($expected -is [int] -or $expected -is [long] -or $expected -is [double]) {
        $matches = $null -ne $actual -and [double]$actual -eq [double]$expected
    }
    else {
        $matches = [string]$actual -eq [string]$expected
    }
    return [pscustomobject]@{
        path = $path
        expected = $expected
        actual = $actual
        passed = $matches
        releaseBlocking = [bool]$Requirement.releaseBlocking
        classification = if ([string]::IsNullOrWhiteSpace([string]$Requirement.classification)) { "blocking-direct-evidence" } else { [string]$Requirement.classification }
        evidence = [pscustomobject]@{
            type = "report-field"
            path = $ReportPath
            field = $path
            reason = [string]$Requirement.reason
        }
    }
}

function New-ReportInputEvidence {
    param(
        [object]$ReportResult,
        [string]$Reason,
        [bool]$ReleaseBlocking = $true,
        [string]$EvidenceClassification = "blocking-direct-evidence"
    )
    return [pscustomobject]@{
        evidenceType = "required-report"
        evidenceClassification = $EvidenceClassification
        releaseBlocking = $ReleaseBlocking
        reportName = if ($null -eq $ReportResult) { "" } else { [string]$ReportResult.name }
        reportPath = if ($null -eq $ReportResult) { "" } else { [string]$ReportResult.path }
        reportStatus = if ($null -eq $ReportResult) { "missing" } else { [string]$ReportResult.status }
        reportValid = if ($null -eq $ReportResult) { $false } else { [bool]$ReportResult.valid }
        reportFresh = if ($null -eq $ReportResult) { $false } else { [bool]$ReportResult.fresh }
        reportHash = if ($null -eq $ReportResult) { $null } else { [string]$ReportResult.contentSha256 }
        blockers = if ($null -eq $ReportResult) { @("required report result was not produced") } else { @($ReportResult.blockers) }
        reason = $Reason
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
        $schemaValidationPath = Join-Path $schemaValidationRoot (([string]$Definition.name) + ".json")
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

    $evidenceRequirementResults = @()
    $definitionEvidenceRequirements = if ($Definition.PSObject.Properties.Name -contains "evidenceRequirements" -and $null -ne $Definition.evidenceRequirements) { @($Definition.evidenceRequirements) } else { @() }
    foreach ($evidenceRequirement in $definitionEvidenceRequirements) {
        if ($null -eq $report) {
            continue
        }
        $evidenceResult = Test-EvidenceRequirement -Requirement $evidenceRequirement -Report $report -ReportPath $relativePath
        $evidenceRequirementResults += $evidenceResult
        if (-not [bool]$evidenceResult.passed -and [bool]$evidenceResult.releaseBlocking) {
            Add-Blocker $reportBlockers ("Blocking evidence requirement failed: " + [string]$evidenceResult.path + " expected " + [string]$evidenceResult.expected + " but got " + [string]$evidenceResult.actual + ". " + [string]$evidenceResult.evidence.reason)
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
        evidenceRequirements = @($evidenceRequirementResults)
    }
}

function Test-PreconditionBlocker {
    # A "precondition" blocker means the gate could not evaluate that piece of evidence at all,
    # because it was never produced. It is categorically different from a blocker that says an
    # existing report asserts something is broken. See the exit-code note at the top of this file.
    param([string]$Message, [bool]$EvidenceIncomplete)
    if ([string]::IsNullOrWhiteSpace($Message)) { return $false }
    if ($Message -like "Required report is missing:*") { return $true }
    if ($Message -like "*: missing") { return $true }
    if ($Message -like "*Report is not valid JSON*") { return $true }
    if ($Message -like "*report could not be read*") { return $true }
    if ($Message -like "Failed to refresh reproducibility report:*") { return $true }
    # runId coherence blockers are a *symptom* of absent evidence while evidence is incomplete, and a
    # genuine check failure once every report actually exists.
    if ($EvidenceIncomplete -and $Message -like "*runId*") { return $true }
    return $false
}

$workspaceRoot = (Resolve-Path ".").Path
$schemaValidationRoot = Resolve-OutsideRepoScratchPath "report-schema-validation"
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
$orchestration = $null

if ($GenerateReports) {
    $orchestrationScript = Resolve-RepoPath $OrchestrationScriptPath
    if (-not (Test-Path -LiteralPath $orchestrationScript -PathType Leaf)) {
        Add-Blocker $blockers ("Evidence orchestration script not found: " + $OrchestrationScriptPath)
    }
    else {
        Write-Host ("Generating release evidence (runId " + $RunId + ") via " + $OrchestrationScriptPath + " ...")
        $orchestrationReportPath = "scripts/reports/out/beta-release-evidence-orchestration-report.json"
        $orchestrationArgs = @("-NoProfile", "-File", $orchestrationScript, "-RunId", $RunId, "-ReportPath", $orchestrationReportPath)
        if (@($SkipProducers).Count -gt 0) {
            # -File passes arguments as strings; a comma-joined value binds to the [string[]] param.
            $orchestrationArgs += @("-SkipProducers", (@($SkipProducers) -join ","))
        }
        $ErrorActionPreference = "Continue"
        & pwsh @orchestrationArgs
        $ErrorActionPreference = "Stop"
        $orchestrationFull = Resolve-RepoPath $orchestrationReportPath
        if (Test-Path -LiteralPath $orchestrationFull -PathType Leaf) {
            $orchestration = Read-JsonFile $orchestrationFull
        }
        else {
            Add-Blocker $blockers "Evidence orchestration did not write its report."
        }
    }
}

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

$scopePolicyEnforcement = Test-ScopePolicyReportAlignment -ScopePolicy $scopePolicy -ReleasePolicy $policy
if ($scopePolicyEnforcement.status -ne "passed") {
    if (@($scopePolicyEnforcement.missingFromReleasePolicy).Count -gt 0) {
        Add-Blocker $blockers ("Scope policy blockingReports missing from release policy requiredReports: " + (@($scopePolicyEnforcement.missingFromReleasePolicy) -join ", "))
    }
    if (@($scopePolicyEnforcement.missingFromScopePolicy).Count -gt 0) {
        Add-Blocker $blockers ("Release policy requiredReports missing from scope policy blockingReports: " + (@($scopePolicyEnforcement.missingFromScopePolicy) -join ", "))
    }
    if (@($scopePolicyEnforcement.duplicatesInScopePolicy).Count -gt 0) {
        Add-Blocker $blockers ("Scope policy blockingReports contains duplicates: " + (@($scopePolicyEnforcement.duplicatesInScopePolicy) -join ", "))
    }
    if (@($scopePolicyEnforcement.duplicatesInReleasePolicy).Count -gt 0) {
        Add-Blocker $blockers ("Release policy requiredReports contains duplicate report paths: " + (@($scopePolicyEnforcement.duplicatesInReleasePolicy) -join ", "))
    }
}

$requiredReports = @()
if (Test-Path -LiteralPath $schemaValidationRoot) {
    Remove-Item -LiteralPath $schemaValidationRoot -Recurse -Force
}
New-Item -ItemType Directory -Force -Path $schemaValidationRoot | Out-Null
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

$aiBetaGateReport = Get-ReportByName $requiredReports "ai-beta-gate"
$sampleMatrixReport = Get-ReportByName $requiredReports "sample-matrix"
$normalizerReport = Get-ReportByName $requiredReports "ai-contract-normalizer-tests"
$restSmokeVerifierReport = Get-ReportByName $requiredReports "ai-rest-smoke-verifier-tests"
$controlledCommandRunnerReport = Get-ReportByName $requiredReports "controlled-command-runner-tests"
$schemaValidatorReport = Get-ReportByName $requiredReports "json-schema-validator-tests"
$reportSchemaValidationReport = Get-ReportByName $requiredReports "report-schema-validation"
$inputEvidence = @{
    aiBetaGatePassed = New-ReportInputEvidence $aiBetaGateReport "AI beta gate report must be passed, fresh, schema-valid, and hash-addressed."
    sampleMatrixPassed = New-ReportInputEvidence $sampleMatrixReport "Sample matrix is release evidence only when the report is valid and releaseEvidence.eligible is true; inputContractEvidence alone is diagnostic for release eligibility."
    normalizerTestsPassed = New-ReportInputEvidence $normalizerReport "Normalizer tests report must be passed, fresh, and hash-addressed."
    restSmokeVerifierTestsPassed = New-ReportInputEvidence $restSmokeVerifierReport "REST smoke verifier tests report must be passed, fresh, and hash-addressed."
    controlledCommandRunnerTestsPassed = New-ReportInputEvidence $controlledCommandRunnerReport "Controlled command runner tests report must be passed, fresh, and hash-addressed."
    schemaValidatorTestsPassed = New-ReportInputEvidence $schemaValidatorReport "JSON schema validator tests report must be passed, fresh, and hash-addressed."
    reportSchemaValidationPassed = New-ReportInputEvidence $reportSchemaValidationReport "Report schema validation report must be passed, fresh, and hash-addressed."
    workspaceClean = [pscustomobject]@{ evidenceType = "command"; evidenceClassification = "blocking-direct-evidence"; releaseBlocking = $true; command = "git status --porcelain=v1"; dirty = [bool]$dirty.dirty; dirtyFileCount = [int]$dirty.dirtyFileCount; dirtyHash = $dirty.dirtyHash; reason = "Workspace cleanliness is derived from the git porcelain output fingerprint." }
    commitIdentityAvailable = [pscustomobject]@{ evidenceType = "command"; evidenceClassification = "blocking-direct-evidence"; releaseBlocking = $true; command = "git rev-parse HEAD"; commit = $commit; reason = "Release eligibility requires a concrete git commit identity." }
    reportsFresh = [pscustomobject]@{ evidenceType = "required-report-freshness"; evidenceClassification = "blocking-direct-evidence"; releaseBlocking = $true; maxReportAgeHours = $maxAge; staleReports = @($requiredReports | Where-Object { -not [bool]$_.fresh } | ForEach-Object { [pscustomobject]@{ name = $_.name; path = $_.path; generatedAt = $_.generatedAt; ageSeconds = $_.ageSeconds } }); reason = "Freshness is evaluated from each required report generatedAt timestamp." }
    approvedEvidencePlatform = [pscustomobject]@{ evidenceType = "policy"; evidenceClassification = "blocking-direct-evidence"; releaseBlocking = $true; policyPath = [string]$policy.scopePolicy; officialEvidencePlatform = [string]$scopePolicy.officialEvidencePlatform; dockerLinuxEvidence = [string]$scopePolicy.dockerLinuxEvidence; dockerRequiredForBeta0 = [bool]$scopePolicy.dockerRequiredForBeta0; reason = "Docker/Linux proof remains blocking through the machine-authoritative scope policy." }
    noStaleContradictoryReports = [pscustomobject]@{ evidenceType = "report-scan"; evidenceClassification = "blocking-direct-evidence"; releaseBlocking = $true; scannedPath = "scripts/reports/out/*.json"; activeContradictoryReports = @($activeContradictoryReports); reason = "Active failed reports outside the required set are scanned as contradictory release evidence." }
}
$inputs = @{
    aiBetaGatePassed = [bool]$aiBetaGateReport.valid
    sampleMatrixPassed = [bool]$sampleMatrixReport.valid
    normalizerTestsPassed = [bool]$normalizerReport.valid
    restSmokeVerifierTestsPassed = [bool]$restSmokeVerifierReport.valid
    controlledCommandRunnerTestsPassed = [bool]$controlledCommandRunnerReport.valid
    schemaValidatorTestsPassed = [bool]$schemaValidatorReport.valid
    reportSchemaValidationPassed = [bool]$reportSchemaValidationReport.valid
    workspaceClean = -not [bool]$dirty.dirty
    commitIdentityAvailable = -not [string]::IsNullOrWhiteSpace($commit)
    reportsFresh = $reportsFresh
    approvedEvidencePlatform = ([string]$scopePolicy.officialEvidencePlatform -eq "windows-ci+docker-linux-ci" -and [string]$scopePolicy.dockerLinuxEvidence -eq "blocking-release-evidence" -and [bool]$scopePolicy.dockerRequiredForBeta0)
    noStaleContradictoryReports = $noStaleContradictoryReports
}
$candidateEvaluation = New-TruthEvaluation "candidateReady" @($truthTable.candidateReady.requires) $inputs $inputEvidence
$inputs["candidateReady"] = [bool]$candidateEvaluation.passed -and $blockers.Count -eq 0
$inputEvidence["candidateReady"] = [pscustomobject]@{ evidenceType = "truth-table-evaluation"; evidenceClassification = "blocking-direct-evidence"; releaseBlocking = $true; evaluationName = "candidateReady"; passed = [bool]$candidateEvaluation.passed; blockersAtEvaluation = $blockers.Count; reason = "candidateReady is computed from direct-evidenced prerequisite booleans and current blocker count." }
$releaseEvaluation = New-TruthEvaluation "releaseReady" @($truthTable.releaseReady.requires) $inputs $inputEvidence
$inputs["releaseReady"] = [bool]$releaseEvaluation.passed -and $blockers.Count -eq 0
$inputEvidence["releaseReady"] = [pscustomobject]@{ evidenceType = "truth-table-evaluation"; evidenceClassification = "blocking-direct-evidence"; releaseBlocking = $true; evaluationName = "releaseReady"; passed = [bool]$releaseEvaluation.passed; blockersAtEvaluation = $blockers.Count; reason = "releaseReady is computed from candidateReady and direct-evidenced release checks." }
$provenanceEvaluation = New-TruthEvaluation "provenanceReady" @($truthTable.provenanceReady.requires) $inputs $inputEvidence
$inputs["provenanceReady"] = [bool]$provenanceEvaluation.passed -and $officialBlockers.Count -eq 0
$inputEvidence["provenanceReady"] = [pscustomobject]@{ evidenceType = "truth-table-evaluation"; evidenceClassification = "blocking-direct-evidence"; releaseBlocking = $true; evaluationName = "provenanceReady"; passed = [bool]$provenanceEvaluation.passed; officialBlockersAtEvaluation = $officialBlockers.Count; reason = "provenanceReady is computed from git, freshness, platform, and contradictory-report evidence." }
$officialEvaluation = New-TruthEvaluation "officialReleaseEligible" @($truthTable.officialReleaseEligible.requires) $inputs $inputEvidence
$candidateReady = [bool]$inputs["candidateReady"]
$releaseReady = [bool]$inputs["releaseReady"]
$provenanceReady = [bool]$inputs["provenanceReady"]
$officialReleaseEligible = [bool]$officialEvaluation.passed -and $releaseReady -and $provenanceReady
$status = if ($releaseReady -and $officialReleaseEligible) { "passed" } else { "failed" }
$generatedAt = (Get-Date).ToUniversalTime().ToString("o")

# --- REG-3: separate "evidence was never produced" from "evidence says something is broken" -------
$missingReportNames = @($requiredReports | Where-Object { -not [bool]$_.exists } | ForEach-Object { [string]$_.name })
$unreadableReportNames = @($requiredReports | Where-Object {
        [bool]$_.exists -and @($_.blockers | Where-Object { [string]$_ -like "*not valid JSON*" }).Count -gt 0
    } | ForEach-Object { [string]$_.name })
$evidenceIncomplete = ($missingReportNames.Count + $unreadableReportNames.Count) -gt 0
$allBlockers = @($blockers + $officialBlockers)
$preconditionBlockers = @($allBlockers | Where-Object { Test-PreconditionBlocker -Message ([string]$_) -EvidenceIncomplete $evidenceIncomplete })
$checkBlockers = @($allBlockers | Where-Object { -not (Test-PreconditionBlocker -Message ([string]$_) -EvidenceIncomplete $evidenceIncomplete) })

$gateOutcome = if ($status -eq "passed") { "passed" }
    elseif ($checkBlockers.Count -eq 0 -and $preconditionBlockers.Count -gt 0) { "precondition-unmet" }
    else { "check-failed" }
$gateExitCode = switch ($gateOutcome) { "passed" { 0 } "precondition-unmet" { 2 } default { 1 } }

$preconditionSummary = [pscustomobject]@{
    outcome = $gateOutcome
    exitCode = $gateExitCode
    evidenceComplete = -not $evidenceIncomplete
    requiredReportCount = @($requiredReports).Count
    presentReportCount = @($requiredReports | Where-Object { [bool]$_.exists }).Count
    missingReports = $missingReportNames
    unreadableReports = $unreadableReportNames
    preconditionBlockerCount = $preconditionBlockers.Count
    checkBlockerCount = $checkBlockers.Count
    preconditionBlockers = $preconditionBlockers
    checkBlockers = $checkBlockers
    generateReportsRequested = [bool]$GenerateReports
    orchestrationStatus = if ($null -eq $orchestration) { "not-run" } else { [string]$orchestration.overallStatus }
    orchestrationReport = if ($null -eq $orchestration) { $null } else { "scripts/reports/out/beta-release-evidence-orchestration-report.json" }
    interpretation = "outcome=precondition-unmet means required evidence was never generated and the release was NOT evaluated; it is not a release failure. Re-run with -GenerateReports (or run scripts/quality/run-beta-release-evidence-orchestration.ps1) before treating any result as a release verdict."
}

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
        generatedReportDirtinessPolicy = "dirty paths under scripts/reports/out/*.json, scripts/reports/out/*.log, and scripts/reports/releases/** are generated release evidence and do not block official eligibility; workspace temp output must live outside NPDev_General."
        workspaceFingerprint = $workspaceFingerprint
    }
    candidateReady = $candidateReady
    releaseReady = $releaseReady
    provenanceReady = $provenanceReady
    officialReleaseEligible = $officialReleaseEligible
    requiredReports = $requiredReports
    informationalReports = $informationalReports
    directEvidenceSummary = [pscustomobject]@{
        evidenceContractVersion = "npdev-direct-release-evidence.v1"
        blockingBooleansAreDirectlyEvidenced = $true
        inferredFieldsPolicy = "Fields marked diagnostic-inferred are not release-blocking and must not drive officialReleaseEligible."
        sampleInputContractPolicy = "sample-matrix inputContractEvidence.eligible is diagnostic for release readiness unless releaseEvidence.eligible is true."
        inputEvidence = $inputEvidence
    }
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
    gateOutcome = $gateOutcome
    evidencePreconditions = $preconditionSummary
    directEvidenceSummary = [pscustomobject]@{
        evidenceContractVersion = "npdev-direct-release-evidence.v1"
        blockingBooleansAreDirectlyEvidenced = $true
        inferredFieldsPolicy = "Diagnostic/inferred fields may explain decisions but do not satisfy blocking release booleans."
        sampleInputContractPolicy = "sample-matrix overallStatus/inputContractEvidence success is not full release evidence unless releaseEvidence.eligible is true."
        inputEvidenceKeys = @($inputEvidence.Keys | Sort-Object)
    }
    officialEvidencePlatform = [string]$policy.officialEvidencePlatform
    dockerLinuxEvidence = [string]$policy.dockerLinuxEvidence
    activeContradictoryReports = $activeContradictoryReports
    scopePolicyEnforcement = $scopePolicyEnforcement
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
    gateOutcome = $gateOutcome
    evidenceComplete = -not $evidenceIncomplete
    preconditionBlockerCount = $preconditionBlockers.Count
    checkBlockerCount = $checkBlockers.Count
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

$selfSchemaResultPath = Join-Path $schemaValidationRoot "beta-release-gate-report-self.json"
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

if ($gateOutcome -eq "passed") {
    Write-Host ("PASSED: beta release gate passed. Report: " + $ReportPath)
    exit 0
}

if ($gateOutcome -eq "precondition-unmet") {
    # Exit 2, NOT 1. Nothing is asserted to be broken - the evidence to judge by was never produced.
    Write-Host ("PRECONDITION-UNMET: beta release gate did not evaluate the release. " +
        [string]$preconditionSummary.missingReports.Count + " of " + [string]$preconditionSummary.requiredReportCount +
        " required reports were never generated (" + [string]$preconditionSummary.unreadableReports.Count + " unreadable). " +
        "This is NOT a release failure. Generate evidence first: " +
        "pwsh -File scripts/quality/run-beta-release-gate.ps1 -GenerateReports")
    Write-Host ("Report: " + $ReportPath)
    Write-Host ("Missing: " + (@($preconditionSummary.missingReports) -join ", "))
    exit 2
}

Write-Host ("CHECK-FAILED: beta release gate evaluated the release and " + [string]$checkBlockers.Count +
    " check(s) failed on real evidence. Report: " + $ReportPath)
if ($preconditionBlockers.Count -gt 0) {
    Write-Host ("Note: " + [string]$preconditionBlockers.Count + " precondition blocker(s) are also present; see evidencePreconditions in the report.")
}
Write-Error ("Beta release gate failed. Report: " + $ReportPath)
exit 1
