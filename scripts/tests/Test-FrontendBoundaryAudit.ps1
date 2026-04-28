Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

. (Join-Path $PSScriptRoot "..\npdev-common.ps1")

function Write-JsonFileForTest {
    param(
        [string]$PathValue,
        [object]$Value
    )

    $parent = Split-Path -Parent $PathValue
    if (-not [string]::IsNullOrWhiteSpace($parent)) {
        New-Item -ItemType Directory -Force -Path $parent | Out-Null
    }
    $Value | ConvertTo-Json -Depth 50 | Set-Content -LiteralPath $PathValue -Encoding UTF8
}

function Write-TextFileForTest {
    param(
        [string]$PathValue,
        [string]$Content
    )

    $parent = Split-Path -Parent $PathValue
    if (-not [string]::IsNullOrWhiteSpace($parent)) {
        New-Item -ItemType Directory -Force -Path $parent | Out-Null
    }
    Set-Content -LiteralPath $PathValue -Value $Content -Encoding UTF8
}

function New-FrontendFixture {
    param(
        [string]$RootPath
    )

    if (Test-Path -LiteralPath $RootPath) {
        Remove-Item -LiteralPath $RootPath -Recurse -Force
    }
    New-Item -ItemType Directory -Force -Path $RootPath | Out-Null

    $uiRoot = Join-Path $RootPath "NPDevEditor\ui-react"
    $srcRoot = Join-Path $uiRoot "src"
    $boundaryPath = Join-Path $uiRoot "ui-boundary.json"
    Write-JsonFileForTest -PathValue $boundaryPath -Value @{
        classificationGroups = @{
            authoring = @{
                include = @(
                    "NPDevEditor\ui-react\src\authoring\**\*.ts",
                    "NPDevEditor\ui-react\src\authoring\**\*.tsx"
                )
                exclude = @(
                    "NPDevEditor\ui-react\src\authoring\boundaryEnforcement.ts"
                )
            }
            runtime = @{
                include = @(
                    "NPDevEditor\ui-react\src\workbench\**\*.tsx"
                )
                exclude = @()
            }
            shared = @{
                include = @(
                    "NPDevEditor\ui-react\src\App.tsx",
                    "NPDevEditor\ui-react\src\main.tsx",
                    "NPDevEditor\ui-react\src\authoring\boundaryEnforcement.ts"
                )
                exclude = @()
            }
        }
    }

    Write-TextFileForTest -PathValue (Join-Path $srcRoot "App.tsx") -Content @'
export default function App(): JSX.Element {
  return <div>shared shell</div>;
}
'@
    Write-TextFileForTest -PathValue (Join-Path $srcRoot "main.tsx") -Content @'
import App from "./App";

void App;
'@
    Write-TextFileForTest -PathValue (Join-Path $srcRoot "authoring\boundaryEnforcement.ts") -Content @'
export const boundaryMarker = "shared";
'@
    Write-TextFileForTest -PathValue (Join-Path $srcRoot "authoring\app\AuthoringApp.tsx") -Content @'
import { boundaryMarker } from "../boundaryEnforcement";

void boundaryMarker;

export default function AuthoringApp(): JSX.Element {
  return <section>authoring</section>;
}
'@
    Write-TextFileForTest -PathValue (Join-Path $srcRoot "workbench\ReactWorkbenchApp.tsx") -Content @'
export default function ReactWorkbenchApp(): JSX.Element {
  return <section>runtime</section>;
}
'@

    return [pscustomobject]@{
        root = $RootPath
        uiRoot = $uiRoot
        srcRoot = $srcRoot
        boundaryPath = $boundaryPath
    }
}

$workspaceRoot = Get-NPDevWorkspaceRoot $PSScriptRoot
$boundaryAuditScript = Resolve-NPDevWorkspacePath $workspaceRoot "scripts\quality\run-frontend-boundary-audit.ps1"
$frontendGateScript = Resolve-NPDevWorkspacePath $workspaceRoot "scripts\quality\run-frontend-gate.ps1"

$passFixtureRoot = Join-Path $env:TEMP "npdev-frontend-boundary-pass"
$unclassifiedFixtureRoot = Join-Path $env:TEMP "npdev-frontend-boundary-unclassified"
$violationFixtureRoot = Join-Path $env:TEMP "npdev-frontend-boundary-violation"
$gateFixtureRoot = Join-Path $env:TEMP "npdev-frontend-gate-boundary-fail"

$failures = [System.Collections.Generic.List[string]]::new()
function Assert-True {
    param(
        [bool]$Condition,
        [string]$Message
    )

    if (-not $Condition) {
        [void]$failures.Add($Message)
    }
}

try {
    $passFixture = New-FrontendFixture -RootPath $passFixtureRoot
    $passReport = & $boundaryAuditScript -WorkspaceRoot $passFixture.root -PassThru
    Assert-True ([string]$passReport.overallStatus -eq "passed") ("Expected the valid frontend fixture to pass the boundary audit. Actual: " + [string]$passReport.overallStatus)

    $unclassifiedFixture = New-FrontendFixture -RootPath $unclassifiedFixtureRoot
    Write-TextFileForTest -PathValue (Join-Path $unclassifiedFixture.srcRoot "Unclassified.tsx") -Content @'
export default function Unclassified(): JSX.Element {
  return <div>unclassified</div>;
}
'@
    $unclassifiedReport = & $boundaryAuditScript -WorkspaceRoot $unclassifiedFixture.root -PassThru
    Assert-True ([string]$unclassifiedReport.overallStatus -eq "failed") ("Expected an unclassified .tsx file to fail the boundary audit. Actual: " + [string]$unclassifiedReport.overallStatus)
    Assert-True ($unclassifiedReport.summary.unclassifiedSourceFiles -gt 0) "Expected the unclassified fixture to report at least one unclassified source file."

    $violationFixture = New-FrontendFixture -RootPath $violationFixtureRoot
    Write-TextFileForTest -PathValue (Join-Path $violationFixture.srcRoot "workbench\ReactWorkbenchApp.tsx") -Content @'
import AuthoringApp from "../authoring/app/AuthoringApp";

void AuthoringApp;

export default function ReactWorkbenchApp(): JSX.Element {
  return <section>runtime</section>;
}
'@
    $violationReport = & $boundaryAuditScript -WorkspaceRoot $violationFixture.root -PassThru
    Assert-True ([string]$violationReport.overallStatus -eq "failed") ("Expected a runtime->authoring import to fail the boundary audit. Actual: " + [string]$violationReport.overallStatus)
    Assert-True (@($violationReport.imports.violations).Count -gt 0) "Expected the forbidden-import fixture to report an import boundary violation."

    $gateFixture = New-FrontendFixture -RootPath $gateFixtureRoot
    Write-TextFileForTest -PathValue (Join-Path $gateFixture.srcRoot "Unclassified.tsx") -Content @'
export default function Unclassified(): JSX.Element {
  return <div>unclassified</div>;
}
'@
    Write-TextFileForTest -PathValue (Join-Path $gateFixture.uiRoot "package.json") -Content "{}"
    Write-TextFileForTest -PathValue (Join-Path $gateFixture.uiRoot "package-lock.json") -Content "{}"
    Write-TextFileForTest -PathValue (Join-Path $gateFixture.root "NPDevEditor\build.gradle") -Content "// fixture"
    Write-TextFileForTest -PathValue (Join-Path $gateFixture.root "NPDevEditor\gradlew.bat") -Content "@echo off`r`nexit /b 0`r`n"
    New-Item -ItemType Directory -Force -Path (Join-Path $gateFixture.root "scripts\quality") | Out-Null
    Copy-Item -LiteralPath $boundaryAuditScript -Destination (Join-Path $gateFixture.root "scripts\quality\run-frontend-boundary-audit.ps1") -Force
    Copy-Item -LiteralPath (Resolve-NPDevWorkspacePath $workspaceRoot "scripts\npdev-common.ps1") -Destination (Join-Path $gateFixture.root "scripts\npdev-common.ps1") -Force

    $frontendGateFailed = $false
    try {
        & $frontendGateScript -WorkspaceRoot $gateFixture.root
    }
    catch {
        $frontendGateFailed = $true
    }
    $gateReportPath = Join-Path $gateFixture.root "scripts\reports\out\frontend-gate-report.json"
    $gateReport = Get-Content -LiteralPath $gateReportPath -Raw | ConvertFrom-Json
    Assert-True $frontendGateFailed "Expected the frontend gate to fail when the boundary audit fails."
    Assert-True ([string]$gateReport.overallStatus -eq "failed") ("Expected the frontend gate report to fail on a boundary-audit error. Actual: " + [string]$gateReport.overallStatus)
    Assert-True ([string]$gateReport.failingSubStep -eq "boundary-audit") ("Expected the frontend gate to fail on boundary-audit. Actual: " + [string]$gateReport.failingSubStep)
    Assert-True (@($gateReport.subSteps | Where-Object { $_.status -eq "skipped" }).Count -ge 1) "Expected the frontend gate to skip later sub-steps after the boundary audit failed."
}
catch {
    [void]$failures.Add($_.Exception.Message)
}
finally {
    foreach ($pathValue in @($passFixtureRoot, $unclassifiedFixtureRoot, $violationFixtureRoot, $gateFixtureRoot)) {
        if (Test-Path -LiteralPath $pathValue) {
            Remove-Item -LiteralPath $pathValue -Recurse -Force
        }
    }
}

if ($failures.Count -eq 0) {
    Write-NPDevOk "Frontend boundary audit tests passed."
    exit 0
}

foreach ($failure in $failures) {
    Write-NPDevWarn $failure
}
throw "Frontend boundary audit tests failed."
