param(
    [string]$RunId = "",
    [string]$ReportPath = "scripts/reports/out/runbook-workflow-alignment-tests-report.json"
)

$ErrorActionPreference = "Stop"

function Add-TestFailure {
    param([string]$Name, [string]$Message)
    $script:failures += [pscustomobject]@{
        name = $Name
        message = $Message
    }
}

function Assert-Condition {
    param([bool]$Condition, [string]$Name, [string]$Message)
    if (-not $Condition) {
        Add-TestFailure -Name $Name -Message $Message
    }
}

function Write-JsonFile {
    param([object]$Value, [string]$Path)
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $Path) | Out-Null
    $Value | ConvertTo-Json -Depth 80 | Set-Content -LiteralPath $Path -Encoding UTF8
}

function Read-JsonFile {
    param([string]$Path)
    return Get-Content -Raw -LiteralPath $Path | ConvertFrom-Json
}

function Invoke-Script {
    param([string[]]$Arguments)
    $ErrorActionPreference = "Continue"
    & pwsh @Arguments 2>&1 | Out-Null
    $exitCode = $LASTEXITCODE
    $ErrorActionPreference = "Stop"
    return $exitCode
}

function New-CanonicalFixtureScript {
    param([string]$Path, [string]$Status)
    $exitCode = if ($Status -eq "passed") { 0 } else { 1 }
    $overall = $Status
    $eligible = if ($Status -eq "passed") { '$true' } else { '$false' }
    $content = @"
param(
    [string]`$RunId = "",
    [string]`$ReportPath = "scripts/reports/out/beta0-final-release-check-report.json",
    [switch]`$ContinueOnFailure
)
`$report = [pscustomobject]@{
    schemaVersion = "npdev-beta0-final-release-check-report.v1"
    runId = `$RunId
    generatedAt = (Get-Date).ToUniversalTime().ToString("o")
    scriptPath = "fixture-canonical-release.ps1"
    overallStatus = "$overall"
    officialReleaseEligible = $eligible
    beta0TagAllowed = $eligible
}
New-Item -ItemType Directory -Force -Path (Split-Path -Parent `$ReportPath) | Out-Null
`$report | ConvertTo-Json -Depth 20 | Set-Content -LiteralPath `$ReportPath -Encoding UTF8
exit $exitCode
"@
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $Path) | Out-Null
    $content | Set-Content -LiteralPath $Path -Encoding UTF8
}

function New-ClosureFixtureWorkspace {
    param([string]$Root, [string]$RunId, [bool]$AlignedWorkflow)
    New-Item -ItemType Directory -Force -Path (Join-Path $Root "scripts/quality") | Out-Null
    New-Item -ItemType Directory -Force -Path (Join-Path $Root "scripts/policy") | Out-Null
    New-Item -ItemType Directory -Force -Path (Join-Path $Root "scripts/reports/out") | Out-Null
    New-Item -ItemType Directory -Force -Path (Join-Path $Root "docs") | Out-Null
    New-Item -ItemType Directory -Force -Path (Join-Path $Root "docs/maintainers") | Out-Null
    New-Item -ItemType Directory -Force -Path (Join-Path $Root ".github/workflows") | Out-Null
    "" | Set-Content -LiteralPath (Join-Path $Root "scripts/quality/run-traceable-local-release.ps1") -Encoding UTF8
    "" | Set-Content -LiteralPath (Join-Path $Root "scripts/quality/run-roadmap-closure-check.ps1") -Encoding UTF8
    @"
{
  "schemaVersion": "npdev-doc-entrypoint-classification-policy.v1",
  "scriptClassifications": [
    { "path": "scripts/quality/run-traceable-local-release.ps1", "classification": "release-relevant", "releaseRelevant": true, "reason": "fixture" },
    { "path": "scripts/quality/run-roadmap-closure-check.ps1", "classification": "release-relevant", "releaseRelevant": true, "reason": "fixture" }
  ]
}
"@ | Set-Content -LiteralPath (Join-Path $Root "scripts/policy/doc-entrypoint-classification-policy.json") -Encoding UTF8
    "pwsh ./scripts/quality/run-traceable-local-release.ps1`npwsh ./scripts/quality/run-roadmap-closure-check.ps1" | Set-Content -LiteralPath (Join-Path $Root "docs/maintainers/OFFICIAL_BETA_RELEASE_RUNBOOK.md") -Encoding UTF8
    $workflowCommand = if ($AlignedWorkflow) {
        "pwsh ./scripts/quality/run-traceable-local-release.ps1`npwsh ./scripts/quality/run-roadmap-closure-check.ps1"
    }
    else {
        "pwsh ./scripts/quality/run-beta0-final-release-check.ps1"
    }
    $workflowCommand | Set-Content -LiteralPath (Join-Path $Root ".github/workflows/npdev-release-gate.yml") -Encoding UTF8
    $workflowCommand | Set-Content -LiteralPath (Join-Path $Root ".github/workflows/ai-beta-gate.yml") -Encoding UTF8

    Write-JsonFile -Path (Join-Path $Root "scripts/reports/out/traceable-local-release-report.json") -Value ([pscustomobject]@{
            schemaVersion = "npdev-traceable-local-release-report.v1"
            runId = $RunId
            overallStatus = "passed"
        })
    Write-JsonFile -Path (Join-Path $Root "scripts/reports/out/beta0-final-release-check-report.json") -Value ([pscustomobject]@{
            schemaVersion = "npdev-beta0-final-release-check-report.v1"
            runId = $RunId
            overallStatus = "passed"
            officialReleaseEligible = $true
            beta0TagAllowed = $true
        })
    Write-JsonFile -Path (Join-Path $Root "scripts/reports/out/beta0-final-closure-report.json") -Value ([pscustomobject]@{
            schemaVersion = "npdev-beta0-final-closure-report.v1"
            runId = $RunId
            overallStatus = "passed"
            officialReleaseEligible = $true
            beta0TagAllowed = $true
        })
    Write-JsonFile -Path (Join-Path $Root "scripts/reports/out/beta-release-gate-report.json") -Value ([pscustomobject]@{
            schemaVersion = "beta-release-gate-report.v1"
            runId = $RunId
            overallStatus = "passed"
            officialReleaseEligible = $true
        })
}

$workspaceRoot = (Resolve-Path ".").Path
if ([string]::IsNullOrWhiteSpace($RunId)) {
    $RunId = "runbook-workflow-alignment-tests-" + (Get-Date).ToUniversalTime().ToString("yyyyMMdd-HHmmssfff")
}
$script:failures = @()
$testRoot = Join-Path $workspaceRoot "scripts/reports/tmp/runbook-workflow-alignment-tests"
if (Test-Path -LiteralPath $testRoot -PathType Container) {
    Remove-Item -LiteralPath $testRoot -Recurse -Force
}
New-Item -ItemType Directory -Force -Path $testRoot | Out-Null

$passingCanonical = Join-Path $testRoot "fixture-canonical-release-passed.ps1"
$failingCanonical = Join-Path $testRoot "fixture-canonical-release-failed.ps1"
New-CanonicalFixtureScript -Path $passingCanonical -Status "passed"
New-CanonicalFixtureScript -Path $failingCanonical -Status "failed"

$tracePassReport = Join-Path $testRoot "traceable-pass-report.json"
$tracePassFinalReport = Join-Path $testRoot "traceable-pass-final-report.json"
$tracePassExit = Invoke-Script @("-NoProfile", "-File", "scripts/quality/run-traceable-local-release.ps1", "-RunId", $RunId, "-CanonicalReleaseScript", $passingCanonical, "-FinalReleaseReportPath", $tracePassFinalReport, "-ReportPath", $tracePassReport)
$tracePass = Read-JsonFile $tracePassReport
Assert-Condition -Condition ($tracePassExit -eq 0) -Name "traceable-wrapper-passes-on-canonical-pass" -Message "Traceable local release wrapper should pass when the canonical release script passes."
Assert-Condition -Condition ([string]$tracePass.overallStatus -eq "passed") -Name "traceable-wrapper-records-pass" -Message "Traceable wrapper report must record passed status."
Assert-Condition -Condition ([int]$tracePass.canonicalRelease.exitCode -eq 0) -Name "traceable-wrapper-exit-evidence" -Message "Traceable wrapper must record canonical command exit code evidence."

$traceFailReport = Join-Path $testRoot "traceable-fail-report.json"
$traceFailFinalReport = Join-Path $testRoot "traceable-fail-final-report.json"
$traceFailExit = Invoke-Script @("-NoProfile", "-File", "scripts/quality/run-traceable-local-release.ps1", "-RunId", $RunId, "-CanonicalReleaseScript", $failingCanonical, "-FinalReleaseReportPath", $traceFailFinalReport, "-ReportPath", $traceFailReport)
$traceFail = Read-JsonFile $traceFailReport
Assert-Condition -Condition ($traceFailExit -ne 0) -Name "traceable-wrapper-fails-on-canonical-fail" -Message "Traceable local release wrapper should fail when the canonical release script fails."
Assert-Condition -Condition ([string]$traceFail.overallStatus -eq "failed") -Name "traceable-wrapper-records-fail" -Message "Traceable wrapper report must record failed status."
Assert-Condition -Condition (@($traceFail.blockers).Count -gt 0) -Name "traceable-wrapper-records-blockers" -Message "Traceable wrapper must record blockers for failed canonical release."

$alignedRoot = Join-Path $testRoot "aligned-workspace"
New-ClosureFixtureWorkspace -Root $alignedRoot -RunId $RunId -AlignedWorkflow:$true
$alignedReport = Join-Path $alignedRoot "scripts/reports/out/roadmap-closure-check-report.json"
$alignedExit = Invoke-Script @("-NoProfile", "-File", (Join-Path $workspaceRoot "scripts/quality/run-roadmap-closure-check.ps1"), "-WorkspaceRoot", $alignedRoot, "-RunId", $RunId, "-ReportPath", $alignedReport)
$aligned = Read-JsonFile $alignedReport
Assert-Condition -Condition ($alignedExit -eq 0) -Name "roadmap-closure-passes-on-aligned-workflow" -Message "Roadmap closure check should pass when runbook, workflow, helpers, and reports are aligned."
Assert-Condition -Condition ([string]$aligned.overallStatus -eq "passed") -Name "roadmap-closure-records-pass" -Message "Roadmap closure report must record passed status for aligned fixtures."

$mismatchRoot = Join-Path $testRoot "mismatch-workspace"
New-ClosureFixtureWorkspace -Root $mismatchRoot -RunId $RunId -AlignedWorkflow:$false
$mismatchReport = Join-Path $mismatchRoot "scripts/reports/out/roadmap-closure-check-report.json"
$mismatchExit = Invoke-Script @("-NoProfile", "-File", (Join-Path $workspaceRoot "scripts/quality/run-roadmap-closure-check.ps1"), "-WorkspaceRoot", $mismatchRoot, "-RunId", $RunId, "-ReportPath", $mismatchReport)
$mismatch = Read-JsonFile $mismatchReport
Assert-Condition -Condition ($mismatchExit -ne 0) -Name "roadmap-closure-fails-on-workflow-drift" -Message "Roadmap closure check should fail when workflow bypasses the traceable release wrapper."
Assert-Condition -Condition (@($mismatch.blockers | Where-Object { [string]$_ -match "Workflow calls the same traceable release entrypoint" }).Count -gt 0) -Name "roadmap-closure-records-workflow-blocker" -Message "Roadmap closure report must record workflow drift as a blocker."

$overallStatus = if ($failures.Count -eq 0) { "passed" } else { "failed" }
$report = [pscustomobject]@{
    schemaVersion = "npdev-runbook-workflow-alignment-test-report.v1"
    runId = $RunId
    generatedAt = (Get-Date).ToUniversalTime().ToString("o")
    scriptPath = "scripts/quality/run-runbook-workflow-alignment-tests.ps1"
    workspaceRoot = $workspaceRoot
    overallStatus = $overallStatus
    assertions = [pscustomobject]@{
        failed = $failures.Count
        names = @(
            "traceable-wrapper-passes-on-canonical-pass",
            "traceable-wrapper-fails-on-canonical-fail",
            "roadmap-closure-passes-on-aligned-workflow",
            "roadmap-closure-fails-on-workflow-drift"
        )
    }
    fixtureReports = @(
        $tracePassReport,
        $traceFailReport,
        $alignedReport,
        $mismatchReport
    )
    failures = @($failures)
}

New-Item -ItemType Directory -Force -Path (Split-Path -Parent $ReportPath) | Out-Null
$report | ConvertTo-Json -Depth 60 | Set-Content -LiteralPath $ReportPath -Encoding UTF8

if ($overallStatus -eq "passed") {
    Write-Host ("Runbook/workflow alignment tests passed. Report: " + $ReportPath)
    exit 0
}

Write-Error ("Runbook/workflow alignment tests failed. Report: " + $ReportPath)
