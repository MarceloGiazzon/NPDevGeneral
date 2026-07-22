param(
    [string]$RunId = ""
)

$ErrorActionPreference = "Stop"

$workspaceRoot = (Resolve-Path ".").Path
if ([string]::IsNullOrWhiteSpace($RunId)) {
    $RunId = "runtimehost-staged-jar-preflight-tests-" + (Get-Date).ToUniversalTime().ToString("yyyyMMdd-HHmmssfff")
}

$outsideRoot = Join-Path (Get-Item -LiteralPath $workspaceRoot).Parent.FullName ((Split-Path -Leaf $workspaceRoot) + "__OutsideRepo\temp\runtimehost-staged-jar-preflight-tests")
$tmpRoot = Join-Path $workspaceRoot "scripts\reports\tmp\runtimehost-staged-jar-preflight-tests"
foreach ($root in @($outsideRoot, $tmpRoot)) {
    if (Test-Path -LiteralPath $root) {
        Remove-Item -LiteralPath $root -Recurse -Force
    }
    New-Item -ItemType Directory -Force -Path $root | Out-Null
}

$assertions = @()
$fixtureRequiredJars = @(
    "dsl-0.1.0.jar",
    "kernel-0.1.0.jar",
    "expression-cel-0.1.0.jar",
    "authz-default-0.1.0.jar",
    "persistence-postgres-0.1.0.jar"
)
function Add-Assertion {
    param([string]$Name, [bool]$Passed, [string]$Message)
    $script:assertions += [pscustomobject]@{
        name = $Name
        passed = $Passed
        message = $Message
    }
    if (-not $Passed) {
        throw $Message
    }
}

function Write-FakeRequiredJars {
    param([string]$Root, [string[]]$SkipNames = @())
    New-Item -ItemType Directory -Force -Path $Root | Out-Null
    foreach ($name in $fixtureRequiredJars) {
        if ($SkipNames -contains $name) { continue }
        Set-Content -LiteralPath (Join-Path $Root $name) -Encoding UTF8 -Value ("fake jar fixture: " + $name)
    }
}

function Write-FixtureSyncReport {
    param([string]$Path, [string]$RuntimeHostLibs)
    $manifestPath = Join-Path $RuntimeHostLibs "runtimehost-libs-manifest.json"
    [pscustomobject]@{
        schemaVersion = "npdev-runtimehost-libs-manifest.v1"
        generatedAt = (Get-Date).ToUniversalTime().ToString("o")
        runtimeHostLibsLocation = "external-local-cache"
        requiredStagedJars = $fixtureRequiredJars
        sourceDiscoveredJars = @($fixtureRequiredJars | ForEach-Object { [pscustomobject]@{ name = $_; source = ("fixture/" + $_) } })
    } | ConvertTo-Json -Depth 20 | Set-Content -LiteralPath $manifestPath -Encoding UTF8
    [pscustomobject]@{
        generatedAt = (Get-Date).ToUniversalTime().ToString("o")
        workspaceRoot = $workspaceRoot
        runtimeHostLibs = $RuntimeHostLibs
        runtimeHostLibsLocation = "external-local-cache"
        builtLocalJars = $false
        overallStatus = "passed"
        sourceDiscoveredJars = @($fixtureRequiredJars | ForEach-Object { [pscustomobject]@{ name = $_; source = ("fixture/" + $_) } })
        requiredStagedJars = $fixtureRequiredJars
        runtimeHostLibsManifest = $manifestPath
        copied = @($fixtureRequiredJars | ForEach-Object { [pscustomobject]@{ name = $_; source = ("fixture/" + $_); target = (Join-Path $RuntimeHostLibs $_) } })
        upToDate = @()
        externalOrMissing = @()
        missingRequired = @()
        cleanedSourceBuildOutputs = @()
    } | ConvertTo-Json -Depth 20 | Set-Content -LiteralPath $Path -Encoding UTF8
}

function Read-Json {
    param([string]$Path)
    return Get-Content -Raw -LiteralPath $Path | ConvertFrom-Json
}

$successLibs = Join-Path $outsideRoot "success-libs"
Write-FakeRequiredJars -Root $successLibs
$successReportPath = Join-Path $tmpRoot "success-report.json"
$successSyncReportPath = Join-Path $tmpRoot "success-sync-report.json"
Write-FixtureSyncReport -Path $successSyncReportPath -RuntimeHostLibs $successLibs
$ErrorActionPreference = "Continue"
pwsh -NoProfile -File scripts/quality/run-runtimehost-staged-jar-preflight.ps1 `
    -WorkspaceRoot $workspaceRoot `
    -RunId $RunId `
    -RuntimeHostLibsDir $successLibs `
    -SyncReportPath $successSyncReportPath `
    -ReportPath $successReportPath `
    -SkipSync 2>$null | Out-Null
$successExit = $LASTEXITCODE
$ErrorActionPreference = "Stop"
$successReport = Read-Json $successReportPath
Add-Assertion -Name "success-fixture-exits-zero" -Passed ($successExit -eq 0) -Message "RuntimeHost preflight success fixture did not exit 0."
Add-Assertion -Name "success-fixture-report-passed" -Passed ([string]$successReport.overallStatus -eq "passed") -Message "RuntimeHost preflight success fixture did not report passed."
Add-Assertion -Name "success-fixture-outside-workspace" -Passed ([bool]$successReport.runtimeHostLibs.outsideWorkspace) -Message "RuntimeHost preflight did not require outside-workspace staging."
Add-Assertion -Name "success-fixture-verifies-full-fixture-set" -Passed ([int]$successReport.requiredJarSet.count -eq $fixtureRequiredJars.Count -and @($successReport.requiredJars).Count -eq $fixtureRequiredJars.Count) -Message "RuntimeHost preflight did not verify the full fixture jar set."
Add-Assertion -Name "success-fixture-verifies-manifest-set" -Passed ([int]$successReport.requiredJarSet.manifestCount -eq $fixtureRequiredJars.Count) -Message "RuntimeHost preflight did not verify the staged manifest jar set."
$oldThreeJarList = "def requiredJars = ['dsl-0.1.0.jar', 'kernel-0.1.0.jar', 'expression-cel-0.1.0.jar']"
Add-Assertion -Name "template-does-not-hardcode-three-jars" -Passed (-not (Get-Content -Raw -LiteralPath "NPDevRuntimeHost/build.gradle.template").Contains($oldThreeJarList)) -Message "RuntimeHost template still hard-codes the old three-jar preflight."

$missingLibs = Join-Path $outsideRoot "missing-libs"
Write-FakeRequiredJars -Root $missingLibs -SkipNames @("authz-default-0.1.0.jar")
$missingReportPath = Join-Path $tmpRoot "missing-report.json"
$missingSyncReportPath = Join-Path $tmpRoot "missing-sync-report.json"
Write-FixtureSyncReport -Path $missingSyncReportPath -RuntimeHostLibs $missingLibs
$ErrorActionPreference = "Continue"
pwsh -NoProfile -File scripts/quality/run-runtimehost-staged-jar-preflight.ps1 `
    -WorkspaceRoot $workspaceRoot `
    -RunId $RunId `
    -RuntimeHostLibsDir $missingLibs `
    -SyncReportPath $missingSyncReportPath `
    -ReportPath $missingReportPath `
    -SkipSync 2>$null | Out-Null
$missingExit = $LASTEXITCODE
$ErrorActionPreference = "Stop"
$missingReport = Read-Json $missingReportPath
$missingCodes = @($missingReport.failures | ForEach-Object { [string]$_.code })
Add-Assertion -Name "missing-fixture-exits-nonzero" -Passed ($missingExit -ne 0) -Message "RuntimeHost preflight missing-jar fixture unexpectedly passed."
Add-Assertion -Name "missing-fixture-report-failed" -Passed ([string]$missingReport.overallStatus -eq "failed") -Message "RuntimeHost preflight missing-jar fixture did not report failed."
Add-Assertion -Name "missing-fixture-records-code" -Passed ($missingCodes -contains "required-jar-missing") -Message "RuntimeHost preflight missing-jar fixture did not record required-jar-missing."
Add-Assertion -Name "missing-non-core-jar-fails" -Passed (@($missingReport.failures | Where-Object { [string]$_.code -eq "required-jar-missing" -and [string]$_.message -match "authz-default-0.1.0.jar" }).Count -eq 1) -Message "RuntimeHost preflight did not fail on a missing non-core staged jar."

$report = [pscustomobject]@{
    schemaVersion = "npdev-runtimehost-staged-jar-preflight-test-report.v1"
    runId = $RunId
    generatedAt = (Get-Date).ToUniversalTime().ToString("o")
    scriptPath = "scripts/quality/run-runtimehost-staged-jar-preflight-tests.ps1"
    workspaceRoot = $workspaceRoot
    overallStatus = "passed"
    cases = @(
        [pscustomobject]@{ name = "success-fixture"; exitCode = $successExit; reportPath = "scripts/reports/tmp/runtimehost-staged-jar-preflight-tests/success-report.json"; overallStatus = [string]$successReport.overallStatus },
        [pscustomobject]@{ name = "missing-jar-fixture"; exitCode = $missingExit; reportPath = "scripts/reports/tmp/runtimehost-staged-jar-preflight-tests/missing-report.json"; overallStatus = [string]$missingReport.overallStatus }
    )
    assertions = @($assertions)
}

$reportPath = "scripts/reports/out/runtimehost-staged-jar-preflight-tests-report.json"
New-Item -ItemType Directory -Force -Path (Split-Path -Parent $reportPath) | Out-Null
$report | ConvertTo-Json -Depth 30 | Set-Content -LiteralPath $reportPath -Encoding UTF8
Write-Host ("RuntimeHost staged-jar preflight tests passed. Report: " + $reportPath)
