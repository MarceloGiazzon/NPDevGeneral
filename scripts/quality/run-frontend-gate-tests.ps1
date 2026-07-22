param(
    [string]$RunId = "",
    [string]$ReportPath = "scripts/reports/out/frontend-gate-tests-report.json"
)

$ErrorActionPreference = "Stop"

function New-TestFailure {
    param(
        [string]$Name,
        [string]$Message
    )
    return [pscustomobject]@{
        name = $Name
        message = $Message
    }
}

function Assert-Condition {
    param(
        [bool]$Condition,
        [string]$Name,
        [string]$Message
    )
    if (-not $Condition) {
        $script:failures += New-TestFailure -Name $Name -Message $Message
    }
}

function Write-TextFile {
    param(
        [string]$Path,
        [string]$Content
    )
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $Path) | Out-Null
    Set-Content -LiteralPath $Path -Encoding UTF8 -Value $Content
}

function New-FrontendFixture {
    param(
        [string]$Root,
        [int]$ExitCode
    )
    $editorRoot = Join-Path $Root "NPDevEditor"
    $uiRoot = Join-Path $editorRoot "ui-react"
    New-Item -ItemType Directory -Force -Path $uiRoot | Out-Null

    Write-TextFile -Path (Join-Path $editorRoot "build.gradle") -Content @'
tasks.register('npmInstall', Exec) {
}

tasks.register('npmTest', Exec) {
}

tasks.register('npmBuild', Exec) {
}
'@

    Write-TextFile -Path (Join-Path $uiRoot "package.json") -Content @'
{
  "scripts": {
    "test": "echo test",
    "build": "echo build"
  },
  "devDependencies": {}
}
'@

    Write-TextFile -Path (Join-Path $uiRoot "package-lock.json") -Content @'
{
  "name": "frontend-gate-fixture",
  "lockfileVersion": 3,
  "packages": {}
}
'@

    Write-TextFile -Path (Join-Path $editorRoot "gradlew.bat") -Content @"
@echo off
echo fixture gradle %*
exit /b $ExitCode
"@
}

function Invoke-FrontendGateForTest {
    param(
        [string]$WorkspaceRoot,
        [string]$ReportPath
    )
    $ErrorActionPreference = "Continue"
    pwsh -NoProfile -File scripts/quality/run-frontend-gate.ps1 -WorkspaceRoot $WorkspaceRoot -EditorRoot "NPDevEditor" -ReportPath $ReportPath -RunId $RunId 2>&1 | Out-Null
    $exitCode = $LASTEXITCODE
    $ErrorActionPreference = "Stop"
    $report = Get-Content -Raw -LiteralPath $ReportPath | ConvertFrom-Json
    return [pscustomobject]@{
        exitCode = $exitCode
        report = $report
    }
}

$workspaceRoot = (Resolve-Path ".").Path
if ([string]::IsNullOrWhiteSpace($RunId)) {
    $RunId = "frontend-gate-tests-" + (Get-Date).ToUniversalTime().ToString("yyyyMMdd-HHmmssfff")
}

$script:failures = @()
$testRoot = Join-Path $workspaceRoot "scripts/reports/tmp/frontend-gate-tests"
if (Test-Path -LiteralPath $testRoot -PathType Container) {
    Remove-Item -LiteralPath $testRoot -Recurse -Force
}
New-Item -ItemType Directory -Force -Path $testRoot | Out-Null

$successRoot = Join-Path $testRoot "success"
New-FrontendFixture -Root $successRoot -ExitCode 0
$successReportPath = Join-Path $testRoot "success-report.json"
$success = Invoke-FrontendGateForTest -WorkspaceRoot $successRoot -ReportPath $successReportPath
Assert-Condition -Condition ($success.exitCode -eq 0) -Name "frontend-gate-success-exit" -Message "Frontend gate should exit 0 when required tasks exist and the Gradle/npm command succeeds."
Assert-Condition -Condition ($success.report.overallStatus -eq "passed") -Name "frontend-gate-success-report" -Message "Frontend gate success fixture should produce overallStatus passed."
Assert-Condition -Condition ($success.report.verifiedTasks.Count -ge 4) -Name "frontend-gate-task-verification" -Message "Frontend gate should record Gradle and npm task verification."

$failureRoot = Join-Path $testRoot "failure"
New-FrontendFixture -Root $failureRoot -ExitCode 7
$failureReportPath = Join-Path $testRoot "failure-report.json"
$failure = Invoke-FrontendGateForTest -WorkspaceRoot $failureRoot -ReportPath $failureReportPath
Assert-Condition -Condition ($failure.exitCode -ne 0) -Name "frontend-gate-failure-exit" -Message "Frontend gate should exit nonzero when the Gradle/npm command fails."
Assert-Condition -Condition ($failure.report.overallStatus -eq "failed") -Name "frontend-gate-failure-report" -Message "Frontend gate failure fixture should produce overallStatus failed."
Assert-Condition -Condition (@($failure.report.commands)[0].exitCode -eq 7) -Name "frontend-gate-failure-exit-code" -Message "Frontend gate report should record the failing command exit code."
Assert-Condition -Condition (@($failure.report.failures | Where-Object { $_.code -eq "frontend-command-failed" }).Count -eq 1) -Name "frontend-gate-failure-record" -Message "Frontend gate report should record a frontend-command-failed finding."

$policy = Get-Content -Raw -LiteralPath "scripts/policy/beta-release-gate-policy.json" | ConvertFrom-Json
$frontendRequiredReport = @($policy.requiredReports | Where-Object {
    $_.name -eq "frontend-gate" -and
    $_.path -eq "scripts/reports/out/frontend-gate-report.json" -and
    $_.schemaVersion -eq "npdev-frontend-gate-report.v1" -and
    $_.statusProperty -eq "overallStatus" -and
    $_.passValue -eq "passed"
})
Assert-Condition -Condition ($frontendRequiredReport.Count -eq 1) -Name "frontend-policy-required-report" -Message "Release policy should consume frontend-gate-report.json as a blocking required report."

$overallStatus = if ($failures.Count -eq 0) { "passed" } else { "failed" }
$report = [pscustomobject]@{
    schemaVersion = "npdev-frontend-gate-test-report.v1"
    runId = $RunId
    generatedAt = (Get-Date).ToUniversalTime().ToString("o")
    scriptPath = "scripts/quality/run-frontend-gate-tests.ps1"
    workspaceRoot = $workspaceRoot
    overallStatus = $overallStatus
    testedReports = @(
        "scripts/reports/tmp/frontend-gate-tests/success-report.json",
        "scripts/reports/tmp/frontend-gate-tests/failure-report.json"
    )
    assertions = [pscustomobject]@{
        total = 8
        failed = $failures.Count
    }
    failures = @($failures)
}

New-Item -ItemType Directory -Force -Path (Split-Path -Parent $ReportPath) | Out-Null
$report | ConvertTo-Json -Depth 30 | Set-Content -LiteralPath $ReportPath -Encoding UTF8

if ($overallStatus -eq "passed") {
    Write-Host ("Frontend gate tests passed. Report: " + $ReportPath)
    exit 0
}

Write-Error ("Frontend gate tests failed. Report: " + $ReportPath)
