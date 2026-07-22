param(
    [string]$RunId = "",
    [string]$PolicyPath = "scripts/policy/ai-command-policy.json",
    [string]$SchemaPath = "schemas/ai/ai-command-request.schema.json",
    [string]$RunnerPath = "scripts/security/Invoke-StructuredCommandRequest.ps1",
    [string]$AiBetaReportPath = "scripts/reports/out/ai-beta-gate-report.json",
    [string]$ReportPath = "scripts/reports/out/ai-command-policy-report.json"
)

$ErrorActionPreference = "Stop"

function Read-JsonFile {
    param([string]$Path)
    return Get-Content -Raw -LiteralPath $Path | ConvertFrom-Json
}

function Add-Failure {
    param([System.Collections.Generic.List[string]]$Failures, [string]$Message)
    if (-not [string]::IsNullOrWhiteSpace($Message)) {
        $Failures.Add($Message) | Out-Null
    }
}

function Get-SortedStrings {
    param([object[]]$Values)
    return @($Values | ForEach-Object { [string]$_ } | Where-Object { -not [string]::IsNullOrWhiteSpace($_) } | Sort-Object -Unique)
}

function Test-SameStringSet {
    param([string[]]$Expected, [string[]]$Actual)
    $expectedSorted = @(Get-SortedStrings $Expected)
    $actualSorted = @(Get-SortedStrings $Actual)
    if ($expectedSorted.Count -ne $actualSorted.Count) { return $false }
    for ($i = 0; $i -lt $expectedSorted.Count; $i++) {
        if ($expectedSorted[$i] -ne $actualSorted[$i]) { return $false }
    }
    return $true
}

function New-SetComparison {
    param([string[]]$Expected, [string[]]$Actual)
    $expectedSorted = @(Get-SortedStrings $Expected)
    $actualSorted = @(Get-SortedStrings $Actual)
    return [pscustomobject]@{
        expected = $expectedSorted
        actual = $actualSorted
        missing = @($expectedSorted | Where-Object { $actualSorted -notcontains $_ })
        unexpected = @($actualSorted | Where-Object { $expectedSorted -notcontains $_ })
        passed = Test-SameStringSet $expectedSorted $actualSorted
    }
}

$workspaceRoot = (Resolve-Path ".").Path
if ([string]::IsNullOrWhiteSpace($RunId)) {
    $RunId = "structured-command-surface-alignment-" + (Get-Date).ToUniversalTime().ToString("yyyyMMdd-HHmmssfff")
}

$policy = Read-JsonFile $PolicyPath
$schema = Read-JsonFile $SchemaPath
$runnerText = Get-Content -Raw -LiteralPath $RunnerPath
$failures = [System.Collections.Generic.List[string]]::new()

$allowedRequestTypes = @(Get-SortedStrings @($policy.allowedRequestTypes))
$implementedRequestTypes = @(Get-SortedStrings @($policy.implementedRequestTypes))
$schemaValidBlockedRequestTypes = @(Get-SortedStrings @($policy.schemaValidBlockedRequestTypes))
$schemaEnumRequestTypes = @(Get-SortedStrings @($schema.properties.type.enum))
$deferredRequestTypes = @(Get-SortedStrings @($policy.deferredRequestTypes | ForEach-Object { [string]$_.type }))
$expectedSchemaTypes = @(Get-SortedStrings @($implementedRequestTypes + $schemaValidBlockedRequestTypes))

$allowedVsImplemented = New-SetComparison -Expected $implementedRequestTypes -Actual $allowedRequestTypes
if (-not [bool]$allowedVsImplemented.passed) {
    Add-Failure $failures "allowedRequestTypes must match implementedRequestTypes exactly."
}

$schemaVsExpected = New-SetComparison -Expected $expectedSchemaTypes -Actual $schemaEnumRequestTypes
if (-not [bool]$schemaVsExpected.passed) {
    Add-Failure $failures "ai-command-request schema enum must match implementedRequestTypes plus schemaValidBlockedRequestTypes."
}

$blockedInAllowed = @($schemaValidBlockedRequestTypes | Where-Object { $allowedRequestTypes -contains $_ })
if ($blockedInAllowed.Count -gt 0) {
    Add-Failure $failures ("schema-valid blocked request types must not be allowed: " + ($blockedInAllowed -join ", "))
}

$deferredInAllowed = @($deferredRequestTypes | Where-Object { $allowedRequestTypes -contains $_ })
if ($deferredInAllowed.Count -gt 0) {
    Add-Failure $failures ("deferred request types must not be allowed: " + ($deferredInAllowed -join ", "))
}

$deferredInSchema = @($deferredRequestTypes | Where-Object { $schemaEnumRequestTypes -contains $_ })
if ($deferredInSchema.Count -gt 0) {
    Add-Failure $failures ("deferred request types must not be schema-valid release command requests: " + ($deferredInSchema -join ", "))
}

$runnerImplemented = @()
foreach ($typeName in $implementedRequestTypes) {
    $pattern = 'if\s*\(\$requestType\s+-eq\s+"' + [regex]::Escape($typeName) + '"\)'
    if ($runnerText -match $pattern) {
        $runnerImplemented += $typeName
    }
}
$runnerImplemented = @(Get-SortedStrings $runnerImplemented)
$runnerVsImplemented = New-SetComparison -Expected $implementedRequestTypes -Actual $runnerImplemented
if (-not [bool]$runnerVsImplemented.passed) {
    Add-Failure $failures "Structured command runner must have an implementation branch for every implemented request type."
}

$trustedScriptAbsent = ($allowedRequestTypes -notcontains "trusted-script") -and
    ($schemaEnumRequestTypes -notcontains "trusted-script") -and
    ($runnerText -notmatch 'if\s*\(\$requestType\s+-eq\s+"trusted-script"\)')
if (-not $trustedScriptAbsent) {
    Add-Failure $failures "trusted-script must remain absent from the release structured command surface until its design checkpoint is approved."
}

$rawCommandBlocked = ($schemaEnumRequestTypes -contains "raw-command") -and
    ($allowedRequestTypes -notcontains "raw-command") -and
    ([bool]$policy.rawShellCommandsAllowed -eq $false)
if (-not $rawCommandBlocked) {
    Add-Failure $failures "raw-command must remain schema-valid for negative tests, disallowed by policy, and blocked by rawShellCommandsAllowed=false."
}

$commandScenarioEvidence = @()
$commandScenarioEvidenceSource = [pscustomobject]@{
    path = $AiBetaReportPath
    exists = $false
    runId = $null
    generatedAt = $null
}
$commandScenariosPassed = $true
if (Test-Path -LiteralPath $AiBetaReportPath -PathType Leaf) {
    try {
        $aiBetaReport = Read-JsonFile $AiBetaReportPath
        $commandScenarioEvidenceSource = [pscustomobject]@{
            path = $AiBetaReportPath
            exists = $true
            runId = [string]$aiBetaReport.runId
            generatedAt = [string]$aiBetaReport.generatedAt
        }
        $commandScenarioEvidence = @($aiBetaReport.scenarios | Where-Object { [string]$_.scenarioId -like "command-policy-*" } | ForEach-Object {
            $passed = [string]$_.status -eq "passed"
            [pscustomobject]@{
                scenarioId = [string]$_.scenarioId
                status = [string]$_.status
                expectedFailureMatched = [bool]$_.expectedFailureMatched
                expectedErrorCode = [string]$_.expectedErrorCode
            }
        })
        $commandScenariosPassed = @($commandScenarioEvidence | Where-Object { [string]$_.status -ne "passed" }).Count -eq 0
    }
    catch {
        Add-Failure $failures ("Could not read AI beta command scenario evidence: " + $_.Exception.Message)
        $commandScenariosPassed = $false
    }
}

if (-not $commandScenariosPassed) {
    Add-Failure $failures "One or more command-policy negative scenarios did not pass fail-closed verification."
}

$overallStatus = if ($failures.Count -eq 0) { "passed" } else { "failed" }
$report = [pscustomobject]@{
    schemaVersion = "npdev-ai-command-policy-report.v1"
    runId = $RunId
    generatedAt = (Get-Date).ToUniversalTime().ToString("o")
    scriptPath = "scripts/quality/run-structured-command-surface-alignment.ps1"
    workspaceRoot = $workspaceRoot
    overallStatus = $overallStatus
    policyPath = $PolicyPath
    schemaPath = $SchemaPath
    runnerPath = $RunnerPath
    allowedRequestTypes = $allowedRequestTypes
    implementedRequestTypes = $implementedRequestTypes
    schemaValidBlockedRequestTypes = $schemaValidBlockedRequestTypes
    schemaEnumRequestTypes = $schemaEnumRequestTypes
    deferredRequestTypes = @($policy.deferredRequestTypes)
    alignment = [pscustomobject]@{
        allowedRequestTypesMatchImplemented = $allowedVsImplemented
        schemaEnumMatchesReleaseSurface = $schemaVsExpected
        runnerBranchesMatchImplemented = $runnerVsImplemented
        rawCommandSchemaValidButBlocked = $rawCommandBlocked
        trustedScriptAbsentUntilDesignCheckpoint = $trustedScriptAbsent
        deferredTypesExcludedFromAllowedSurface = ($deferredInAllowed.Count -eq 0)
        deferredTypesExcludedFromSchemaSurface = ($deferredInSchema.Count -eq 0)
    }
    commandScenarioEvidence = $commandScenarioEvidence
    commandScenarioEvidenceSource = $commandScenarioEvidenceSource
    failures = @($failures)
}

New-Item -ItemType Directory -Force -Path (Split-Path -Parent $ReportPath) | Out-Null
$report | ConvertTo-Json -Depth 50 | Set-Content -LiteralPath $ReportPath -Encoding UTF8

if ($overallStatus -eq "passed") {
    Write-Host ("Structured command surface alignment passed. Report: " + $ReportPath)
    exit 0
}

Write-Error ("Structured command surface alignment failed. Report: " + $ReportPath)
