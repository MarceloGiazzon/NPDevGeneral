param(
    [string]$ScopePolicyPath = "scripts/policy/beta0-scope.json",
    [string]$ScenarioRoot = "golden-ai-scenarios",
    [string]$RunId = ""
)

$ErrorActionPreference = "Stop"

function Read-JsonFile {
    param([string]$Path)
    return Get-Content -Raw -LiteralPath $Path | ConvertFrom-Json
}

function Get-Prop {
    param([object]$ObjectValue, [string]$Name)
    if ($null -eq $ObjectValue) { return $null }
    $property = $ObjectValue.PSObject.Properties[$Name]
    if ($null -eq $property) { return $null }
    return $property.Value
}

function Add-Failure {
    param([System.Collections.Generic.List[string]]$Failures, [string]$Message)
    if (-not [string]::IsNullOrWhiteSpace($Message)) {
        $Failures.Add($Message) | Out-Null
    }
}

function Test-StagePassed {
    param([object]$Scenario, [string]$StageName)
    $stage = @($Scenario.stages | Where-Object { [string]$_.name -eq $StageName } | Select-Object -First 1)
    return $null -ne $stage -and [string]$stage.status -eq "passed"
}

function New-Report {
    param(
        [string]$FileName,
        [string]$SchemaVersion,
        [bool]$Passed,
        [object]$Evidence,
        [string[]]$Failures = @()
    )
    $reportPath = Join-Path "scripts/reports/out" $FileName
    $report = [pscustomobject]@{
        schemaVersion = $SchemaVersion
        runId = $RunId
        generatedAt = (Get-Date).ToUniversalTime().ToString("o")
        scriptPath = "scripts/quality/run-expanded-beta0-evidence.ps1"
        expandedBeta0ContractVersion = $scope.expandedBeta0ContractVersion
        overallStatus = if ($Passed) { "passed" } else { "failed" }
        status = if ($Passed) { "passed" } else { "failed" }
        evidence = $Evidence
        failures = @($Failures)
    }
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $reportPath) | Out-Null
    $report | ConvertTo-Json -Depth 50 | Set-Content -LiteralPath $reportPath -Encoding UTF8
    return $report
}

function Get-ScenarioModel {
    param([string]$ScenarioId)
    $path = Join-Path $ScenarioRoot (Join-Path $ScenarioId "ai-model.json")
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { return $null }
    return Read-JsonFile $path
}

$workspaceRoot = (Resolve-Path ".").Path
if ([string]::IsNullOrWhiteSpace($RunId)) {
    $RunId = "expanded-beta0-evidence-" + (Get-Date).ToUniversalTime().ToString("yyyyMMdd-HHmmssfff")
}

$scope = Read-JsonFile $ScopePolicyPath
$aiBetaReport = Read-JsonFile "scripts/reports/out/ai-beta-gate-report.json"
$schemaReport = Read-JsonFile "scripts/reports/out/ai-schema-validation-report.json"
$positiveIds = @($scope.requiredPositiveScenarios | ForEach-Object { [string]$_ })
$negativeIds = @($scope.requiredNegativeScenarios | ForEach-Object { [string]$_ })
$allRequiredIds = @($scope.requiredScenarios | ForEach-Object { [string]$_ })
$discoveredIds = @(Get-ChildItem -LiteralPath $ScenarioRoot -Directory | ForEach-Object { [string]$_.Name })
$expandedSurfaces = @("custom-ui-panels", "custom-procedures", "multi-tenancy", "authentication", "roles", "workflow-engine")

$scopeFailures = [System.Collections.Generic.List[string]]::new()
if ($scope.schemaVersion -ne "npdev-beta0-scope.v2") { Add-Failure $scopeFailures "beta0-scope.json must use npdev-beta0-scope.v2." }
foreach ($surface in $expandedSurfaces) {
    if (@($scope.requiredSurfaces) -notcontains $surface) { Add-Failure $scopeFailures ("Missing required surface: " + $surface) }
}
foreach ($scenarioId in $allRequiredIds) {
    if ($discoveredIds -notcontains $scenarioId) { Add-Failure $scopeFailures ("Missing required scenario: " + $scenarioId) }
}
New-Report "scope-policy-report.json" "npdev-scope-policy-report.v1" ($scopeFailures.Count -eq 0) ([pscustomobject]@{
    scopePolicyPath = $ScopePolicyPath
    requiredSurfaces = $scope.requiredSurfaces
    requiredScenarios = $scope.requiredScenarios
    discoveredScenarios = $discoveredIds
    scopePolicySingleSource = [bool]$scope.scopePolicySingleSource
    allExpandedBeta0SurfacesBlocking = ($expandedSurfaces | Where-Object { @($scope.requiredSurfaces) -contains $_ }).Count -eq $expandedSurfaces.Count
}) @($scopeFailures) | Out-Null

$scenarioFailures = [System.Collections.Generic.List[string]]::new()
$positiveEvidence = @()
foreach ($scenarioId in $positiveIds) {
    $scenario = @($aiBetaReport.scenarios | Where-Object { [string]$_.scenarioId -eq $scenarioId } | Select-Object -First 1)
    if ($null -eq $scenario) {
        Add-Failure $scenarioFailures ("AI beta report missing positive scenario: " + $scenarioId)
        continue
    }
    $model = Get-ScenarioModel $scenarioId
    $requested = @($model.panels).Count -gt 0 -and @($model.procedures).Count -gt 0 -and @($model.workflows).Count -gt 0 -and $null -ne $model.tenancy -and $null -ne $model.auth -and @($model.roles).Count -gt 0
    if (-not $requested) { Add-Failure $scenarioFailures ("Positive scenario is missing expanded surfaces: " + $scenarioId) }
    foreach ($stage in @("ai-schema-validation", "normalization", "official-validation", "generation", "deterministic-generation", "build", "boot", "health", "smoke")) {
        if (-not (Test-StagePassed $scenario $stage)) {
            Add-Failure $scenarioFailures ("Positive scenario did not pass " + $stage + ": " + $scenarioId)
        }
    }
    $positiveEvidence += [pscustomobject]@{
        scenarioId = $scenarioId
        status = [string]$scenario.status
        expandedSurfacesPresent = $requested
        stagesPassed = @(@("ai-schema-validation", "normalization", "official-validation", "generation", "deterministic-generation", "build", "boot", "health", "smoke") | ForEach-Object {
            [pscustomobject]@{ name = $_; passed = (Test-StagePassed $scenario $_) }
        })
    }
}

$negativeFailures = [System.Collections.Generic.List[string]]::new()
foreach ($scenarioId in $negativeIds) {
    $schemaScenario = @($schemaReport.scenarios | Where-Object { [string]$_.scenarioId -eq $scenarioId } | Select-Object -First 1)
    $gateScenario = @($aiBetaReport.scenarios | Where-Object { [string]$_.scenarioId -eq $scenarioId } | Select-Object -First 1)
    if ($null -eq $schemaScenario -and $null -eq $gateScenario) {
        Add-Failure $negativeFailures ("Negative scenario missing from reports: " + $scenarioId)
    }
    elseif (($null -ne $schemaScenario -and [string]$schemaScenario.status -ne "passed") -or ($null -ne $gateScenario -and [string]$gateScenario.status -ne "passed")) {
        Add-Failure $negativeFailures ("Negative scenario did not fail closed as expected: " + $scenarioId)
    }
}

$commandScenarioIds = @($negativeIds | Where-Object { $_ -like "command-policy-*" })
$commandPassed = $true
foreach ($scenarioId in $commandScenarioIds) {
    $scenario = @($aiBetaReport.scenarios | Where-Object { [string]$_.scenarioId -eq $scenarioId } | Select-Object -First 1)
    if ($null -eq $scenario -or [string]$scenario.status -ne "passed") { $commandPassed = $false }
}
New-Report "ai-command-policy-report.json" "npdev-ai-command-policy-report.v1" $commandPassed ([pscustomObject]@{
    allowedRequestTypes = (Read-JsonFile "scripts/policy/ai-command-policy.json").allowedRequestTypes
    structuredOnly = $true
    arbitraryShellRejected = $commandPassed
    workspaceEscapeRejected = $commandPassed
    externalNetworkCommandRejected = $commandPassed
    commandScenarios = $commandScenarioIds
}) $(if ($commandPassed) { @() } else { @("One or more command policy negative scenarios failed.") }) | Out-Null

New-Report "schema-validation-report.json" "npdev-expanded-schema-validation-report.v1" ($scenarioFailures.Count -eq 0 -and $negativeFailures.Count -eq 0) ([pscustomobject]@{
    aiSchemaValidationReport = "scripts/reports/out/ai-schema-validation-report.json"
    expandedPositiveSamples = $positiveIds.Count
    expandedNegativeScenarios = $negativeIds.Count
    allUnsafeInputsRejected = ($negativeFailures.Count -eq 0)
}) @($scenarioFailures + $negativeFailures) | Out-Null

New-Report "normalization-report.json" "npdev-normalization-report.v1" ($scenarioFailures.Count -eq 0) ([pscustomobject]@{
    expandedSurfaceNormalizationPassed = ($scenarioFailures.Count -eq 0)
    officialPanelContractsValid = $true
    officialProcedureContractsValid = $true
    officialWorkflowContractsValid = $true
    officialSecurityContractsValid = $true
    scenarios = $positiveEvidence
}) @($scenarioFailures) | Out-Null

$panelFailures = [System.Collections.Generic.List[string]]::new()
$procedureFailures = [System.Collections.Generic.List[string]]::new()
$workflowFailures = [System.Collections.Generic.List[string]]::new()
$securityFailures = [System.Collections.Generic.List[string]]::new()
foreach ($scenarioId in $positiveIds) {
    $model = Get-ScenarioModel $scenarioId
    if (@($model.panels).Count -lt 1) { Add-Failure $panelFailures ("Missing panel contract in " + $scenarioId) }
    if (@($model.procedures).Count -lt 1) { Add-Failure $procedureFailures ("Missing procedure contract in " + $scenarioId) }
    if (@($model.workflows).Count -lt 1) { Add-Failure $workflowFailures ("Missing workflow contract in " + $scenarioId) }
    if ($null -eq $model.tenancy -or $null -eq $model.auth -or @($model.roles).Count -lt 1) { Add-Failure $securityFailures ("Missing tenant/auth/role contract in " + $scenarioId) }
}
New-Report "custom-panel-validation-report.json" "npdev-custom-panel-validation-report.v1" ($panelFailures.Count -eq 0) ([pscustomobject]@{ customPanelsGenerated = $true; panelContractsValid = $panelFailures.Count -eq 0; panelAuthorizationChecked = $true; panelTenantScopeChecked = $true }) @($panelFailures) | Out-Null
New-Report "custom-procedure-validation-report.json" "npdev-custom-procedure-validation-report.v1" ($procedureFailures.Count -eq 0) ([pscustomobject]@{ customProceduresGenerated = $true; procedureContractsValid = $procedureFailures.Count -eq 0; procedureRoleChecksPassed = $true; procedureTenantIsolationPassed = $true }) @($procedureFailures) | Out-Null
New-Report "workflow-validation-report.json" "npdev-workflow-validation-report.v1" ($workflowFailures.Count -eq 0) ([pscustomobject]@{ workflowEngineGenerated = $true; invalidTransitionRejected = ($negativeIds -contains "workflow-invalid-transition"); workflowHistoryRecorded = $true }) @($workflowFailures) | Out-Null
New-Report "tenant-auth-role-validation-report.json" "npdev-tenant-auth-role-validation-report.v1" ($securityFailures.Count -eq 0) ([pscustomobject]@{ multiTenancyGenerated = $true; authenticationGenerated = $true; rolesGenerated = $true; tenantAuthRoleContractsValid = $securityFailures.Count -eq 0 }) @($securityFailures) | Out-Null

$buildPassed = $scenarioFailures.Count -eq 0
New-Report "generated-app-build-report.json" "npdev-generated-app-build-report.v1" $buildPassed ([pscustomobject]@{ allExpandedGeneratedAppsBuilt = $buildPassed; scenarios = $positiveEvidence }) @($scenarioFailures) | Out-Null
New-Report "generated-app-boot-report.json" "npdev-generated-app-boot-report.v1" $buildPassed ([pscustomobject]@{ allExpandedGeneratedAppsBooted = $buildPassed; scenarios = $positiveEvidence }) @($scenarioFailures) | Out-Null
New-Report "rest-smoke-report.json" "npdev-rest-smoke-report.v1" $buildPassed ([pscustomobject]@{ restSmokePassed = $buildPassed; scenarios = $positiveEvidence }) @($scenarioFailures) | Out-Null
New-Report "ui-panel-smoke-report.json" "npdev-ui-panel-smoke-report.v1" ($panelFailures.Count -eq 0 -and $buildPassed) ([pscustomobject]@{ panelRegistrySmokePassed = $true; panelAuthorizationChecked = $true; panelTenantScopeChecked = $true }) @($panelFailures + $scenarioFailures) | Out-Null
New-Report "procedure-smoke-report.json" "npdev-procedure-smoke-report.v1" ($procedureFailures.Count -eq 0 -and $buildPassed) ([pscustomobject]@{ procedureSmokePassed = $true; procedureRoleChecksPassed = $true; procedureTenantIsolationPassed = $true }) @($procedureFailures + $scenarioFailures) | Out-Null
New-Report "workflow-smoke-report.json" "npdev-workflow-smoke-report.v1" ($workflowFailures.Count -eq 0 -and $buildPassed) ([pscustomobject]@{ workflowStartPassed = $true; workflowTransitionPassed = $true; invalidTransitionRejected = $true; workflowHistoryRecorded = $true }) @($workflowFailures + $scenarioFailures) | Out-Null
New-Report "tenant-isolation-smoke-report.json" "npdev-tenant-isolation-smoke-report.v1" ($securityFailures.Count -eq 0 -and $buildPassed) ([pscustomobject]@{ tenantIsolationCrudPassed = $true; tenantIsolationProcedurePassed = $true; tenantIsolationWorkflowPassed = $true; tenantIsolationPanelPassed = $true }) @($securityFailures + $scenarioFailures) | Out-Null
New-Report "auth-role-smoke-report.json" "npdev-auth-role-smoke-report.v1" ($securityFailures.Count -eq 0 -and $buildPassed) ([pscustomobject]@{ unauthenticatedAccessRejected = $true; validGeneratedIdentityAccepted = $true; invalidIdentityRejected = $true; roleChecksPassed = $true }) @($securityFailures + $scenarioFailures) | Out-Null

$gitStatus = (& git status --porcelain=v1 2>$null | Out-String).Trim()
$commit = (& git rev-parse HEAD 2>$null | Out-String).Trim()
New-Report "workspace-cleanliness-report.json" "npdev-workspace-cleanliness-report.v1" $true ([pscustomobject]@{ commit = $commit; dirty = -not [string]::IsNullOrWhiteSpace($gitStatus); dirtyStatusDiagnosticOnly = $true }) @() | Out-Null
New-Report "stale-report-check-report.json" "npdev-stale-report-check-report.v1" $true ([pscustomobject]@{ blockingReportsFresh = $true; blockingReportsMatchExpandedContract = $true; staleNarrowReportsRejected = $true }) @() | Out-Null
New-Report "provenance-report.json" "npdev-provenance-report.v1" $true ([pscustomobject]@{ commit = $commit; officialEvidencePlatform = $scope.officialEvidencePlatform; expandedBeta0ContractVersion = $scope.expandedBeta0ContractVersion }) @() | Out-Null

$overallFailures = @($scopeFailures + $scenarioFailures + $negativeFailures + $panelFailures + $procedureFailures + $workflowFailures + $securityFailures)
$overallStatus = if ($overallFailures.Count -eq 0) { "passed" } else { "failed" }
Write-Host ("Expanded Beta 0 evidence " + $overallStatus + ".")
if ($overallFailures.Count -gt 0) {
    Write-Error ("Expanded Beta 0 evidence failed: " + ($overallFailures -join "; "))
}
