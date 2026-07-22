param(
    [Parameter(Mandatory = $true)][string]$Version,
    [switch]$DryRun,
    [string]$FinalReportPath = "scripts/reports/out/beta0-final-release-check-report.json"
)

$ErrorActionPreference = "Stop"

function Read-JsonFile {
    param([string]$Path)
    return Get-Content -Raw -LiteralPath $Path | ConvertFrom-Json
}

if ($Version -ne "beta0") {
    throw "Only -Version beta0 is supported by the Beta 0 tag gate."
}

if (-not (Test-Path -LiteralPath $FinalReportPath -PathType Leaf)) {
    throw "Final release check report is missing: $FinalReportPath"
}

$report = Read-JsonFile $FinalReportPath
$requiredBooleans = @("candidateReady", "releaseReady", "provenanceReady", "officialReleaseEligible", "beta0TagAllowed")
if ([string]$report.overallStatus -ne "passed") {
    throw "Final release check is not passing."
}
foreach ($name in $requiredBooleans) {
    if (-not [bool]$report.$name) {
        throw "Final release check does not allow tagging because $name is not true."
    }
}

$dirty = (& git status --porcelain=v1 2>$null | Out-String).Trim()
if (-not [string]::IsNullOrWhiteSpace($dirty)) {
    throw "Workspace is dirty; Beta 0 tag is blocked."
}

$currentCommit = (& git rev-parse HEAD 2>$null | Out-String).Trim()
if ([string]::IsNullOrWhiteSpace($currentCommit)) {
    throw "Current git commit could not be resolved."
}

$releaseReportPath = "scripts/reports/out/beta-release-gate-report.json"
if (-not (Test-Path -LiteralPath $releaseReportPath -PathType Leaf)) {
    throw "Beta release gate report is missing."
}
$releaseReport = Read-JsonFile $releaseReportPath
if ([string]$releaseReport.git.commit -ne $currentCommit) {
    throw "Release evidence commit does not match current commit."
}

$tagName = "beta0"
$result = [pscustomobject]@{
    schemaVersion = "npdev-beta0-tag-gate-result.v1"
    version = $Version
    tag = $tagName
    dryRun = [bool]$DryRun
    status = "passed"
    tagDryRunAllowed = [bool]$DryRun
    commit = $currentCommit
    finalReport = $FinalReportPath
}

if ($DryRun) {
    $result | ConvertTo-Json -Depth 10
    exit 0
}

& git tag $tagName $currentCommit
if ($LASTEXITCODE -ne 0) {
    throw "git tag failed."
}
$result | ConvertTo-Json -Depth 10
