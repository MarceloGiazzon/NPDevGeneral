param(
    [string]$RunId = "",
    [string]$ReportPath = "scripts/reports/out/scope-policy-enforcement-tests-report.json"
)

$ErrorActionPreference = "Stop"

function Add-TestFailure {
    param(
        [string]$Name,
        [string]$Message
    )
    $script:failures += [pscustomobject]@{
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
        Add-TestFailure -Name $Name -Message $Message
    }
}

function Read-JsonFile {
    param([string]$Path)
    return Get-Content -Raw -LiteralPath $Path | ConvertFrom-Json
}

function Get-ReportFileName {
    param([string]$PathValue)
    if ([string]::IsNullOrWhiteSpace($PathValue)) { return "" }
    return [System.IO.Path]::GetFileName(([string]$PathValue).Replace("\", "/"))
}

function Get-NormalizedReportFileNames {
    param([object[]]$Values)
    return @($Values | ForEach-Object {
            if ($null -eq $_) { return }
            if ($_.PSObject.Properties.Name -contains "path") {
                Get-ReportFileName ([string]$_.path)
            }
            else {
                Get-ReportFileName ([string]$_)
            }
        } | Where-Object { -not [string]::IsNullOrWhiteSpace($_) } | Sort-Object -Unique)
}

function Copy-JsonFile {
    param(
        [object]$Value,
        [string]$Path
    )
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $Path) | Out-Null
    $Value | ConvertTo-Json -Depth 80 | Set-Content -LiteralPath $Path -Encoding UTF8
}

$workspaceRoot = (Resolve-Path ".").Path
if ([string]::IsNullOrWhiteSpace($RunId)) {
    $RunId = "scope-policy-enforcement-tests-" + (Get-Date).ToUniversalTime().ToString("yyyyMMdd-HHmmssfff")
}

$script:failures = @()
$scope = Read-JsonFile "scripts/policy/beta0-scope.json"
$releasePolicy = Read-JsonFile "scripts/policy/beta-release-gate-policy.json"
$scopeBlockingReports = @(Get-NormalizedReportFileNames @($scope.blockingReports))
$releaseRequiredReports = @(Get-NormalizedReportFileNames @($releasePolicy.requiredReports))
$missingFromReleasePolicy = @($scopeBlockingReports | Where-Object { $releaseRequiredReports -notcontains $_ })
$missingFromScopePolicy = @($releaseRequiredReports | Where-Object { $scopeBlockingReports -notcontains $_ })

Assert-Condition -Condition ($scope.schemaVersion -eq "npdev-beta0-scope.v2") -Name "scope-schema-version" -Message "beta0-scope.json must remain v2."
Assert-Condition -Condition ([bool]$scope.scopePolicySingleSource) -Name "scope-single-source" -Message "beta0-scope.json must declare scopePolicySingleSource=true."
Assert-Condition -Condition ($scopeBlockingReports -contains "doc-entrypoint-validation-report.json") -Name "doc-entrypoint-current-name" -Message "beta0-scope.json must use doc-entrypoint-validation-report.json."
Assert-Condition -Condition ($scopeBlockingReports -notcontains "doc-entrypoint-report.json") -Name "doc-entrypoint-stale-name" -Message "beta0-scope.json must not use stale doc-entrypoint-report.json."
Assert-Condition -Condition ($missingFromReleasePolicy.Count -eq 0) -Name "scope-to-release-alignment" -Message ("Scope blockingReports missing from release requiredReports: " + ($missingFromReleasePolicy -join ", "))
Assert-Condition -Condition ($missingFromScopePolicy.Count -eq 0) -Name "release-to-scope-alignment" -Message ("Release requiredReports missing from scope blockingReports: " + ($missingFromScopePolicy -join ", "))

$testRoot = Join-Path $workspaceRoot "scripts/reports/tmp/scope-policy-enforcement-tests"
if (Test-Path -LiteralPath $testRoot -PathType Container) {
    Remove-Item -LiteralPath $testRoot -Recurse -Force
}
New-Item -ItemType Directory -Force -Path $testRoot | Out-Null
$mismatchScope = $scope | ConvertTo-Json -Depth 80 | ConvertFrom-Json
$mismatchReleasePolicy = $releasePolicy | ConvertTo-Json -Depth 80 | ConvertFrom-Json
$firstRequiredReport = [string]$releaseRequiredReports[0]
$mismatchScope.blockingReports = @($scopeBlockingReports | Where-Object { $_ -ne $firstRequiredReport })
$mismatchScope.blockingReports += "stale-scope-only-report.json"
$mismatchScopePath = Join-Path $testRoot "beta0-scope-mismatch.json"
$mismatchPolicyPath = Join-Path $testRoot "beta-release-gate-policy-mismatch.json"
$mismatchReportPath = Join-Path $testRoot "beta-release-gate-report-mismatch.json"
$mismatchManifestPath = Join-Path $testRoot "beta-release-evidence-manifest-mismatch.json"
$mismatchSummaryPath = Join-Path $testRoot "release-ready-summary-mismatch.json"
$mismatchReleasePolicy.scopePolicy = $mismatchScopePath
Copy-JsonFile -Value $mismatchScope -Path $mismatchScopePath
Copy-JsonFile -Value $mismatchReleasePolicy -Path $mismatchPolicyPath

$ErrorActionPreference = "Continue"
pwsh -NoProfile -File scripts/quality/run-beta-release-gate.ps1 `
    -PolicyPath $mismatchPolicyPath `
    -ReportPath $mismatchReportPath `
    -ManifestPath $mismatchManifestPath `
    -SummaryPath $mismatchSummaryPath `
    -RunId $RunId 2>&1 | Out-Null
$mismatchExitCode = $LASTEXITCODE
$ErrorActionPreference = "Stop"
$mismatchReport = Read-JsonFile $mismatchReportPath
Assert-Condition -Condition ($mismatchExitCode -ne 0) -Name "mismatch-gate-fails" -Message "Release gate should fail when scope and release policy reports disagree."
Assert-Condition -Condition ($mismatchReport.scopePolicyEnforcement.status -eq "failed") -Name "mismatch-report-status" -Message "Release gate report should record failed scopePolicyEnforcement."
Assert-Condition -Condition (@($mismatchReport.scopePolicyEnforcement.missingFromReleasePolicy) -contains "stale-scope-only-report.json") -Name "mismatch-extra-scope-report" -Message "Release gate should report scope-only blocking reports."
Assert-Condition -Condition (@($mismatchReport.scopePolicyEnforcement.missingFromScopePolicy) -contains $firstRequiredReport) -Name "mismatch-missing-scope-report" -Message "Release gate should report release required reports missing from scope policy."

$overallStatus = if ($failures.Count -eq 0) { "passed" } else { "failed" }
$report = [pscustomobject]@{
    schemaVersion = "npdev-scope-policy-enforcement-test-report.v1"
    runId = $RunId
    generatedAt = (Get-Date).ToUniversalTime().ToString("o")
    scriptPath = "scripts/quality/run-scope-policy-enforcement-tests.ps1"
    workspaceRoot = $workspaceRoot
    overallStatus = $overallStatus
    currentAlignment = [pscustomobject]@{
        scopeBlockingReportCount = $scopeBlockingReports.Count
        releaseRequiredReportCount = $releaseRequiredReports.Count
        missingFromReleasePolicy = $missingFromReleasePolicy
        missingFromScopePolicy = $missingFromScopePolicy
    }
    mismatchFixture = [pscustomobject]@{
        policyPath = "scripts/reports/tmp/scope-policy-enforcement-tests/beta-release-gate-policy-mismatch.json"
        scopePath = "scripts/reports/tmp/scope-policy-enforcement-tests/beta0-scope-mismatch.json"
        reportPath = "scripts/reports/tmp/scope-policy-enforcement-tests/beta-release-gate-report-mismatch.json"
        exitCode = $mismatchExitCode
        scopePolicyEnforcement = $mismatchReport.scopePolicyEnforcement
    }
    assertions = [pscustomobject]@{
        failed = $failures.Count
    }
    failures = @($failures)
}

New-Item -ItemType Directory -Force -Path (Split-Path -Parent $ReportPath) | Out-Null
$report | ConvertTo-Json -Depth 50 | Set-Content -LiteralPath $ReportPath -Encoding UTF8

if ($overallStatus -eq "passed") {
    Write-Host ("Scope policy enforcement tests passed. Report: " + $ReportPath)
    exit 0
}

Write-Error ("Scope policy enforcement tests failed. Report: " + $ReportPath)
