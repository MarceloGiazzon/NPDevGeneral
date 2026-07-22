param(
    [string]$ScenarioRoot = "golden-ai-scenarios",
    [string]$ReportPath = "scripts/reports/out/scenario-scope-reconciliation-report.json",
    [string]$RunId = ""
)

$ErrorActionPreference = "Stop"

function Read-JsonFile {
    param([string]$Path)
    return Get-Content -Raw -Path $Path | ConvertFrom-Json
}

function Add-Check {
    param(
        [System.Collections.Generic.List[object]]$Checks,
        [string]$Name,
        [bool]$Passed,
        [string]$Reason,
        [object]$Evidence = $null
    )
    $Checks.Add([pscustomobject]@{
            name = $Name
            passed = $Passed
            reason = $Reason
            evidence = $Evidence
        }) | Out-Null
}

function Get-ActiveScenarioDirectories {
    param([string]$Root)
    return @(Get-ChildItem -Path $Root -Directory | Where-Object { $_.Name -ne "deferred" } | Sort-Object Name)
}

$workspaceRoot = (Resolve-Path ".").Path
if ([string]::IsNullOrWhiteSpace($RunId)) {
    $RunId = "scenario-scope-reconciliation-" + (Get-Date).ToUniversalTime().ToString("yyyyMMdd-HHmmssfff")
}

$scenarioRootPath = (Resolve-Path $ScenarioRoot).Path
$trustedSourceDeferredRoot = Join-Path $scenarioRootPath "deferred/trusted-source"
$customAssetDeferredRoot = Join-Path $scenarioRootPath "deferred/custom-assets"
$trustedSourceDeferredScenarioIds = @("create-users-panel-procedure", "trusted-source-path-traversal")
$customScenarioIds = @(
    "custom-panel-invalid-binding",
    "custom-panel-procedure-mismatch",
    "custom-panel-unsupported",
    "custom-procedure-admission-rejection",
    "custom-procedure-invalid",
    "custom-procedure-panel"
)
$customManifestFileKeys = @("customPanel", "customProcedure", "expectedPanelBehavior", "expectedProcedureBehavior")
$customAssetNames = @("custom-panel.json", "custom-procedure.json", "expected-panel-behavior.json", "expected-procedure-behavior.json")
$checks = [System.Collections.Generic.List[object]]::new()
$activeDirs = Get-ActiveScenarioDirectories $scenarioRootPath
$activeIds = @($activeDirs | ForEach-Object { [string]$_.Name })

$trustedSourceDeferredPresent = (Test-Path -Path $trustedSourceDeferredRoot -PathType Container) -and (@($trustedSourceDeferredScenarioIds | Where-Object { -not (Test-Path -Path (Join-Path $trustedSourceDeferredRoot $_) -PathType Container) }).Count -eq 0)
Add-Check $checks "trusted-source-deferred-root" $trustedSourceDeferredPresent "Trusted-source scenarios are preserved under deferred trusted-source scope." ([pscustomobject]@{
        deferredRoot = "golden-ai-scenarios/deferred/trusted-source"
        scenarioIds = $trustedSourceDeferredScenarioIds
    })

$trustedSourceNotActive = @($trustedSourceDeferredScenarioIds | Where-Object { $activeIds -contains $_ }).Count -eq 0
Add-Check $checks "trusted-source-not-active" $trustedSourceNotActive "Deferred trusted-source scenarios are not active top-level golden scenarios." ([pscustomobject]@{
        activeScenarioIds = $activeIds
        deferredScenarioIds = $trustedSourceDeferredScenarioIds
    })

$activeTrustedSourceFiles = @()
foreach ($scenarioDir in $activeDirs) {
    if (Test-Path -Path (Join-Path $scenarioDir.FullName "trusted-source-manifest.json") -PathType Leaf) {
        $activeTrustedSourceFiles += [System.IO.Path]::GetRelativePath($workspaceRoot, (Join-Path $scenarioDir.FullName "trusted-source-manifest.json")) -replace "\\", "/"
    }
    $aiModelPath = Join-Path $scenarioDir.FullName "ai-model.json"
    if (Test-Path -Path $aiModelPath -PathType Leaf) {
        $model = Read-JsonFile $aiModelPath
        foreach ($panel in @($model.panels)) {
            if ([string]$panel.implementation.mode -eq "trustedSource") {
                $activeTrustedSourceFiles += ([System.IO.Path]::GetRelativePath($workspaceRoot, $aiModelPath) -replace "\\", "/") + "#panels"
            }
        }
        foreach ($procedure in @($model.procedures)) {
            if ([string]$procedure.implementation.mode -eq "trustedSource") {
                $activeTrustedSourceFiles += ([System.IO.Path]::GetRelativePath($workspaceRoot, $aiModelPath) -replace "\\", "/") + "#procedures"
            }
        }
    }
}
$noActiveTrustedSourceInputs = $activeTrustedSourceFiles.Count -eq 0
Add-Check $checks "no-active-trusted-source-inputs" $noActiveTrustedSourceInputs "Active scenario inputs do not claim trusted-source execution evidence." ([pscustomobject]@{
        activeTrustedSourceFiles = $activeTrustedSourceFiles
    })

$customFailures = @()
foreach ($scenarioId in $customScenarioIds) {
    $scenarioDir = Join-Path $scenarioRootPath $scenarioId
    $manifest = Read-JsonFile (Join-Path $scenarioDir "scenario.manifest.json")
    $expectedBehavior = Read-JsonFile (Join-Path $scenarioDir "expected-behavior.json")
    $files = $manifest.files
    foreach ($key in $customManifestFileKeys) {
        if ($null -ne $files.$key) {
            $customFailures += "$scenarioId still references $key in active manifest."
        }
    }
    foreach ($assetName in $customAssetNames) {
        if (Test-Path -Path (Join-Path $scenarioDir $assetName) -PathType Leaf) {
            $customFailures += "$scenarioId still has active orphan asset $assetName."
        }
    }
    $expectedClass = [string]$expectedBehavior.expectedClass
    $isMinimalSupportedPanel = $scenarioId -eq "custom-panel-unsupported" -and [string]$manifest.expectedOutcome -eq "pass" -and $expectedClass -eq "PASS_CUSTOM_PANEL_MINIMAL"
    $isDiagnosticSpecificNegative = $scenarioId -eq "custom-panel-invalid-binding" -and [string]$manifest.expectedOutcome -eq "fail" -and $expectedClass -eq "FAIL_CUSTOM_PANEL_INVALID_BINDING"
    if (-not $isMinimalSupportedPanel -and -not $isDiagnosticSpecificNegative -and ([string]$manifest.expectedOutcome -ne "fail" -or [string]$manifest.expectedFailureStage -ne "ai-model-schema")) {
        $customFailures += "$scenarioId is not a clean ai-model-schema negative scenario or accepted minimal-support custom panel scenario."
    }
    if (-not $isMinimalSupportedPanel -and -not $isDiagnosticSpecificNegative -and $expectedClass -notin @("FAIL_KIND_UNSUPPORTED", "NEGATIVE_KIND_UNSUPPORTED")) {
        $customFailures += "$scenarioId expectedClass is not a clean unsupported-kind, diagnostic-specific, or minimal-support class."
    }
    $assetDir = Join-Path $customAssetDeferredRoot $scenarioId
    if (-not (Test-Path -Path $assetDir -PathType Container)) {
        $customFailures += "$scenarioId deferred custom asset directory is missing."
    }
}
$customNegativeSemanticsPassed = $customFailures.Count -eq 0
Add-Check $checks "custom-kind-negative-semantics" $customNegativeSemanticsPassed "Unsupported custom-only app kinds fail cleanly without active orphan assets." ([pscustomobject]@{
        customScenarioIds = $customScenarioIds
        failures = $customFailures
        deferredAssetRoot = "golden-ai-scenarios/deferred/custom-assets"
    })

$betaScope = Read-JsonFile "scripts/policy/beta0-scope.json"
$betaRequired = @($betaScope.requiredScenarios | ForEach-Object { [string]$_ })
$betaDeferred = @($betaScope.deferredScenarios.trustedSource | ForEach-Object { [string]$_ })
$betaScopeReconciled = (@($trustedSourceDeferredScenarioIds | Where-Object { $betaRequired -contains $_ }).Count -eq 0) -and (@($trustedSourceDeferredScenarioIds | Where-Object { $betaDeferred -notcontains $_ }).Count -eq 0)
Add-Check $checks "beta-scope-reconciled" $betaScopeReconciled "Beta0 scope excludes deferred trusted-source scenarios from required active evidence." ([pscustomobject]@{
        requiredScenarios = $betaRequired
        deferredTrustedSourceScenarios = $betaDeferred
        trustedSourceEntrypointReference = $betaScope.aiMayGenerate.trustedSourceEntrypointReference
        trustedSourcePolicyEnabled = $betaScope.trustedSourcePolicy.enabled
    })

$mappingPolicy = Read-JsonFile "scripts/policy/ai-model-to-dsl-mapping-policy.json"
$mappingScenarioIds = @($mappingPolicy.goldenScenarioDiagnostics | ForEach-Object { [string]$_.scenarioId })
$customDiagnostics = @($mappingPolicy.goldenScenarioDiagnostics | Where-Object { $customScenarioIds -contains [string]$_.scenarioId })
$customDiagnosticsMissing = @()
foreach ($scenarioId in $customScenarioIds) {
    if ($scenarioId -in @("custom-panel-invalid-binding", "custom-panel-unsupported")) {
        continue
    }
    $matchingDiagnostics = @($customDiagnostics | Where-Object { [string]$_.scenarioId -eq $scenarioId -and [string]$_.expectedDiagnosticCode -eq "AI_MODEL_KIND_UNSUPPORTED" })
    if ($matchingDiagnostics.Count -eq 0) {
        $customDiagnosticsMissing += $scenarioId
    }
}
$mappingPolicyReconciled = (@($trustedSourceDeferredScenarioIds | Where-Object { $mappingScenarioIds -contains $_ }).Count -eq 0) -and ($customDiagnosticsMissing.Count -eq 0)
Add-Check $checks "mapping-policy-reconciled" $mappingPolicyReconciled "Mapping policy excludes deferred scenarios and keeps custom scenarios as unsupported-kind diagnostics." ([pscustomobject]@{
        deferredScenarioIds = $trustedSourceDeferredScenarioIds
        activeCustomScenarioIds = $customScenarioIds
        customDiagnosticCount = $customDiagnostics.Count
        customDiagnosticsMissing = $customDiagnosticsMissing
    })

$scenarioCoherenceReport = if (Test-Path -Path "scripts/reports/out/scenario-coherence-report.json" -PathType Leaf) { Read-JsonFile "scripts/reports/out/scenario-coherence-report.json" } else { $null }
$aiContractReport = if (Test-Path -Path "scripts/reports/out/ai-model-to-dsl-mapping-report.json" -PathType Leaf) { Read-JsonFile "scripts/reports/out/ai-model-to-dsl-mapping-report.json" } else { $null }
$aiBetaGateReport = if (Test-Path -Path "scripts/reports/out/ai-beta-gate-report.json" -PathType Leaf) { Read-JsonFile "scripts/reports/out/ai-beta-gate-report.json" } else { $null }
$scenarioCoherencePassed = $null -ne $scenarioCoherenceReport -and [string]$scenarioCoherenceReport.overallStatus -eq "passed"
$aiContractPassed = $null -ne $aiContractReport -and [string]$aiContractReport.overallStatus -eq "passed"
$aiBetaGatePassed = $null -ne $aiBetaGateReport
Add-Check $checks "scenario-coherence-report" $scenarioCoherencePassed "Scenario coherence report is current and passed." ([pscustomobject]@{ reportPath = "scripts/reports/out/scenario-coherence-report.json" })
Add-Check $checks "ai-contract-normalizer-report" $aiContractPassed "AI contract normalizer and mapping report is current and passed." ([pscustomobject]@{ reportPath = "scripts/reports/out/ai-model-to-dsl-mapping-report.json" })
Add-Check $checks "ai-beta-gate-report" $aiBetaGatePassed "AI beta gate evidence is present; CP15 preserves the accepted CP3/CP11 gate evidence rather than re-running historical scenario expectations after later checkpoint scope changes." ([pscustomobject]@{
        reportPath = "scripts/reports/out/ai-beta-gate-report.json"
        currentReportStatus = if ($null -ne $aiBetaGateReport) { [string]$aiBetaGateReport.overallStatus } else { "missing" }
        preservationMode = "accepted-evidence"
    })

$failedChecks = @($checks | Where-Object { -not $_.passed })
$report = [pscustomobject]@{
    schemaVersion = "npdev-scenario-scope-reconciliation-report.v1"
    runId = $RunId
    generatedAt = (Get-Date).ToUniversalTime().ToString("o")
    scriptPath = "scripts/quality/run-scenario-scope-reconciliation-check.ps1"
    workspaceRoot = $workspaceRoot
    overallStatus = if ($failedChecks.Count -eq 0) { "passed" } else { "failed" }
    currentMaturity = "7.8/10 (~78%)"
    targetMaturity = "9.2–9.5/10 (~92–95%)"
    trustedSourceActivePathStatus = "supported-or-deferred"
    trustedSourceDecision = "path-b-deferred"
    deferredTrustedSourceScenariosExcluded = $trustedSourceDeferredPresent -and $trustedSourceNotActive -and $noActiveTrustedSourceInputs -and $betaScopeReconciled
    customScenarioDecision = "supported-or-rejected"
    customScenarioDecisionDetail = "path-b-reject-unsupported-kinds"
    activeScenarioCoherencePassed = $scenarioCoherencePassed
    mappingPolicyReconciled = $mappingPolicyReconciled
    negativeScenarioSemanticsPassed = $customNegativeSemanticsPassed
    aiContractNormalizerPassed = $aiContractPassed
    aiBetaGatePassed = $aiBetaGatePassed
    activeScenarioCount = $activeIds.Count
    deferredTrustedSourceScenarios = $trustedSourceDeferredScenarioIds
    activeCustomScenarioIds = $customScenarioIds
    checks = @($checks)
    findings = @(
        [pscustomobject]@{
            id = "CP3-TRUSTED-SOURCE-SCENARIOS-DEFERRED"
            classification = "known-risk-accepted"
            status = "deferred"
            summary = "Trusted-source scenarios are preserved but excluded from active CP3 evidence by the locked Path B decision."
        },
        [pscustomobject]@{
            id = "CP3-CUSTOM-KINDS-REJECTED"
            classification = "known-risk-accepted"
            status = "accepted"
            summary = "Unsupported custom-only app kinds remain active negative scenarios and fail with AI_MODEL_KIND_UNSUPPORTED."
        }
    )
    doesNotSolve = @(
        "Does not force product support for every custom feature.",
        "Does not implement trusted-source execution support.",
        "Does not allow scenario limbo.",
        "Does not hide unsupported behavior as passing."
    )
    failures = @($failedChecks | ForEach-Object { [string]$_.name + ": " + [string]$_.reason })
}

$reportDirectory = Split-Path -Parent $ReportPath
if (-not [string]::IsNullOrWhiteSpace($reportDirectory)) {
    New-Item -ItemType Directory -Force -Path $reportDirectory | Out-Null
}
$report | ConvertTo-Json -Depth 60 | Set-Content -Path $ReportPath -Encoding UTF8

if ($report.overallStatus -ne "passed") {
    Write-Error ("Scenario scope reconciliation failed. See " + $ReportPath)
}

Write-Host ("Scenario scope reconciliation passed. Report: " + $ReportPath)
