[CmdletBinding()]
param(
    [string]$WorkspaceRoot = "",
    [string]$ExpectedVersion = "",
    [string]$ReportPath = ""
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

. (Join-Path $PSScriptRoot "..\npdev-common.ps1")

if ([string]::IsNullOrWhiteSpace($WorkspaceRoot)) {
    $WorkspaceRoot = Get-NPDevWorkspaceRoot $PSScriptRoot
}
$WorkspaceRoot = Normalize-NPDevPath $WorkspaceRoot

if ([string]::IsNullOrWhiteSpace($ReportPath)) {
    $ReportPath = Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\reports\out\release-checklist-gate-report.json"
}

<#
LNCH-23: the mechanical half of docs/RELEASE_PROCESS.md -- LICENSE present, CHANGELOG.md has an
entry for the version being released, and HEAD is tagged v<version>. Deliberately narrow: this
does NOT re-run test suites (run-generator-gate.ps1/run-runtimehost-gate.ps1 own that) and cannot
check trademark clearance (a human step, see docs/adr/ADR-0007-distribution-model.md) -- it only
refuses an untagged/unchangelogged release, per LNCH-23's own DoD wording.
#>

$violations = New-Object System.Collections.Generic.List[string]

$licensePath = Resolve-NPDevWorkspacePath $WorkspaceRoot "LICENSE"
if (-not (Test-Path -LiteralPath $licensePath)) {
    $violations.Add("LICENSE file is missing at repo root.") | Out-Null
}

$changelogPath = Resolve-NPDevWorkspacePath $WorkspaceRoot "CHANGELOG.md"
$changelogHasEntry = $false
if (-not (Test-Path -LiteralPath $changelogPath)) {
    $violations.Add("CHANGELOG.md is missing at repo root.") | Out-Null
} elseif (-not [string]::IsNullOrWhiteSpace($ExpectedVersion)) {
    $changelogText = Get-Content -LiteralPath $changelogPath -Raw
    $escapedVersion = [Regex]::Escape($ExpectedVersion)
    if ($changelogText -match ("(?m)^\s*##\s*\[?v?" + $escapedVersion + "\]?")) {
        $changelogHasEntry = $true
    } else {
        $violations.Add("CHANGELOG.md has no entry for version '$ExpectedVersion' (expected a heading like '## [$ExpectedVersion]').") | Out-Null
    }
}

$headTagged = $false
$headTagName = $null
if (-not [string]::IsNullOrWhiteSpace($ExpectedVersion)) {
    Push-Location $WorkspaceRoot
    try {
        $expectedTag = "v$ExpectedVersion"
        $tagCommit = git rev-parse --verify --quiet "$expectedTag^{commit}" 2>$null
        $headCommit = git rev-parse HEAD 2>$null
        if ($LASTEXITCODE -eq 0 -and $tagCommit -and $headCommit -and ($tagCommit.Trim() -eq $headCommit.Trim())) {
            $headTagged = $true
            $headTagName = $expectedTag
        } else {
            $violations.Add("HEAD is not tagged '$expectedTag' (release tags are immutable once pushed -- see docs/RELEASE_PROCESS.md).") | Out-Null
        }
    } finally {
        Pop-Location
    }
}

if ([string]::IsNullOrWhiteSpace($ExpectedVersion)) {
    Write-NPDevWarn "No -ExpectedVersion supplied -- checked LICENSE/CHANGELOG presence only, skipped the version-specific CHANGELOG-entry and git-tag checks."
}

$status = if ($violations.Count -eq 0) { "passed" } else { "failed" }

$report = [pscustomobject]@{
    schemaVersion    = "npdev-release-checklist-gate-report.v1"
    generatedAt      = (Get-Date).ToUniversalTime().ToString("o")
    workspaceRoot    = $WorkspaceRoot
    expectedVersion  = $ExpectedVersion
    overallStatus    = $status
    licensePresent   = (Test-Path -LiteralPath $licensePath)
    changelogPresent = (Test-Path -LiteralPath $changelogPath)
    changelogHasEntry = $changelogHasEntry
    headTagged       = $headTagged
    headTagName      = $headTagName
    violations       = $violations
}

$reportDir = Split-Path -Parent $ReportPath
if (-not (Test-Path -LiteralPath $reportDir)) {
    New-Item -ItemType Directory -Path $reportDir -Force | Out-Null
}
$report | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath $ReportPath -Encoding UTF8

if ($status -eq "passed") {
    Write-NPDevOk "Release checklist passed. Report: $ReportPath"
    exit 0
} else {
    Write-NPDevWarn "Release checklist failed with $($violations.Count) violation(s). Report: $ReportPath"
    foreach ($violation in $violations) {
        Write-Host ("  - " + $violation) -ForegroundColor Yellow
    }
    exit 1
}
