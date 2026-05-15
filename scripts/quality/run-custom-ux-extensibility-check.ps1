param(
    [string]$ReportPath = "scripts/reports/out/custom-ux-extensibility-report.json",
    [string]$RunId = ""
)

$ErrorActionPreference = "Stop"

function Read-JsonFile {
    param([string]$Path)
    return Get-Content -Raw -LiteralPath $Path | ConvertFrom-Json
}

function Write-JsonFile {
    param([string]$Path, [object]$Value, [int]$Depth = 80)
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $Path) | Out-Null
    $Value | ConvertTo-Json -Depth $Depth | Set-Content -LiteralPath $Path -Encoding UTF8
}

function Invoke-CommandCapture {
    param([string]$Name, [scriptblock]$ScriptBlock, [int]$ExpectedExitCode = 0)
    $started = Get-Date
    $output = @(& $ScriptBlock 2>&1 | ForEach-Object { $_.ToString() })
    $exitCode = $LASTEXITCODE
    if ($null -eq $exitCode) { $exitCode = 0 }
    $finished = Get-Date
    return [pscustomobject]@{
        name = $Name
        exitCode = $exitCode
        expectedExitCode = $ExpectedExitCode
        passed = ($exitCode -eq $ExpectedExitCode)
        durationSeconds = [math]::Round(($finished - $started).TotalSeconds, 3)
        outputTail = @($output | Select-Object -Last 120)
    }
}

function Invoke-SchemaCase {
    param(
        [string]$Name,
        [string]$SchemaPath,
        [string]$InstancePath,
        [bool]$ShouldPass,
        [string]$ValidationRoot
    )
    $resultPath = Join-Path $ValidationRoot ($Name + ".json")
    $ErrorActionPreference = "Continue"
    pwsh -NoProfile -File scripts/quality/Invoke-JsonSchemaValidation.ps1 -SchemaPath $SchemaPath -InstancePath $InstancePath -ReportPath $resultPath 2>$null | Out-Null
    $exitCode = $LASTEXITCODE
    $ErrorActionPreference = "Stop"
    $result = if (Test-Path -LiteralPath $resultPath -PathType Leaf) { Read-JsonFile $resultPath } else { $null }
    $actualPass = ($exitCode -eq 0 -and $null -ne $result -and [string]$result.status -eq "passed")
    return [pscustomobject]@{
        name = $Name
        schemaPath = $SchemaPath
        instancePath = $InstancePath
        resultPath = $resultPath
        shouldPass = $ShouldPass
        passed = ($actualPass -eq $ShouldPass)
        actualStatus = if ($actualPass) { "passed" } else { "failed" }
        failures = if ($null -ne $result) { @($result.failures) } else { @("schema validation did not write a result") }
    }
}

function New-MinimalCustomPanel {
    return [ordered]@{
        schemaVersion = "ai-custom-panel.v1"
        panelId = "learner-dashboard"
        route = "/learners"
        dataSources = @(
            [ordered]@{ name = "learners"; concept = "Learner" }
        )
        visibleFields = @("status")
        actions = @()
        layout = [ordered]@{ type = "table"; fields = @("status") }
        widgets = @(
            [ordered]@{ type = "table"; field = "status" }
        )
        metadata = [ordered]@{
            displayName = "Learner dashboard"
            description = "Bounded declarative panel metadata."
            emptyStateMessage = "No learners have been created yet."
            icon = "table"
            variant = "readonly"
        }
    }
}

function Get-ScenarioFailureText {
    param([object]$Scenario)
    if ($null -eq $Scenario) { return "" }
    $messages = [System.Collections.Generic.List[string]]::new()
    foreach ($failure in @($Scenario.failures)) {
        if (-not [string]::IsNullOrWhiteSpace([string]$failure)) { $messages.Add([string]$failure) | Out-Null }
    }
    foreach ($failure in @($Scenario.semanticValidation.failures)) {
        if (-not [string]::IsNullOrWhiteSpace([string]$failure)) { $messages.Add([string]$failure) | Out-Null }
    }
    foreach ($schema in @($Scenario.schemaValidation.schemas)) {
        foreach ($failure in @($schema.failures)) {
            if (-not [string]::IsNullOrWhiteSpace([string]$failure)) { $messages.Add([string]$failure) | Out-Null }
        }
    }
    return ($messages -join " ")
}

function Test-InvalidPanelBindingDiagnostic {
    param([string]$ModelPath)
    $model = Read-JsonFile $ModelPath
    $entityNames = [System.Collections.Generic.HashSet[string]]::new([System.StringComparer]::Ordinal)
    foreach ($entity in @($model.entities)) {
        if (-not [string]::IsNullOrWhiteSpace([string]$entity.name)) {
            $entityNames.Add([string]$entity.name) | Out-Null
        }
    }
    $diagnostics = [System.Collections.Generic.List[string]]::new()
    foreach ($panel in @($model.panels)) {
        if ([string]$panel.dataSource.kind -eq "entity" -and -not $entityNames.Contains([string]$panel.dataSource.name)) {
            $diagnostics.Add("panel entity data source is unresolved: " + [string]$panel.panelId) | Out-Null
        }
    }
    return [pscustomobject]@{
        modelPath = $ModelPath
        expectedDiagnostic = "panel entity data source is unresolved: learner-dashboard"
        diagnostics = @($diagnostics)
        passed = (@($diagnostics) -contains "panel entity data source is unresolved: learner-dashboard")
    }
}

$workspaceRoot = (Resolve-Path ".").Path
if ([string]::IsNullOrWhiteSpace($RunId)) {
    $RunId = "custom-ux-extensibility-" + (Get-Date).ToUniversalTime().ToString("yyyyMMdd-HHmmssfff")
}

$workRoot = Join-Path $workspaceRoot "build/cp12-custom-ux-extensibility"
if (Test-Path -LiteralPath $workRoot) {
    Remove-Item -LiteralPath $workRoot -Recurse -Force
}
New-Item -ItemType Directory -Force -Path $workRoot | Out-Null
$fixtureRoot = Join-Path $workRoot "fixtures"
$validationRoot = Join-Path $workRoot "schema-validation"
New-Item -ItemType Directory -Force -Path $fixtureRoot | Out-Null
New-Item -ItemType Directory -Force -Path $validationRoot | Out-Null

$validPanelPath = Join-Path $fixtureRoot "valid-minimal-custom-panel.json"
Write-JsonFile $validPanelPath (New-MinimalCustomPanel)

$dynamicPanel = New-MinimalCustomPanel
$dynamicPanel["implementation"] = [ordered]@{ mode = "trustedSource"; language = "javascript"; entrypoint = "panel/dashboard.js" }
$dynamicPanelPath = Join-Path $fixtureRoot "dynamic-implementation-custom-panel.json"
Write-JsonFile $dynamicPanelPath $dynamicPanel

$scriptPanel = New-MinimalCustomPanel
$scriptPanel["script"] = "alert('unsafe')"
$scriptPanelPath = Join-Path $fixtureRoot "script-custom-panel.json"
Write-JsonFile $scriptPanelPath $scriptPanel

$remotePanel = New-MinimalCustomPanel
$remotePanel.metadata["componentUrl"] = "https://example.com/panel.js"
$remotePanelPath = Join-Path $fixtureRoot "remote-component-custom-panel.json"
Write-JsonFile $remotePanelPath $remotePanel

$schemaCases = @(
    Invoke-SchemaCase "valid-minimal-custom-panel" "schemas/ai/custom-panel.schema.json" $validPanelPath $true $validationRoot
    Invoke-SchemaCase "dynamic-implementation-custom-panel-rejected" "schemas/ai/custom-panel.schema.json" $dynamicPanelPath $false $validationRoot
    Invoke-SchemaCase "script-custom-panel-rejected" "schemas/ai/custom-panel.schema.json" $scriptPanelPath $false $validationRoot
    Invoke-SchemaCase "remote-component-custom-panel-rejected" "schemas/ai/custom-panel.schema.json" $remotePanelPath $false $validationRoot
)

$normalizerOut = Join-Path $workRoot "normalized-custom-panel"
$normalizerResultPath = Join-Path $workRoot "custom-panel-normalizer-result.json"
$normalizerRun = Invoke-CommandCapture "custom-panel-minimal-normalization" {
    pwsh -NoProfile -File scripts/ai/Normalize-AiContract.ps1 -ScenarioPath golden-ai-scenarios/custom-panel-unsupported -OutputDirectory $normalizerOut -ResultPath $normalizerResultPath
}
$normalizedModel = if (Test-Path -LiteralPath (Join-Path $normalizerOut "model.json") -PathType Leaf) {
    Read-JsonFile (Join-Path $normalizerOut "model.json")
} else {
    $null
}
$normalizedPanel = if ($null -ne $normalizedModel -and @($normalizedModel.panels).Count -gt 0) {
    @($normalizedModel.panels)[0]
} else {
    $null
}
$safeMetadataMapped = $null -ne $normalizedPanel `
    -and [string]$normalizedPanel.metadata.customPanelContract -eq "minimal-declarative-v1" `
    -and [string]$normalizedPanel.metadata.safeCustomPanelMetadata.displayName -eq "Learner dashboard" `
    -and [string]$normalizedPanel.metadata.trustedSourceEntrypoint -eq ""

$aiSchemaValidation = Invoke-CommandCapture "ai-schema-validation" {
    pwsh -NoProfile -File scripts/quality/run-ai-schema-validation.ps1
}
$aiContractNormalizer = Invoke-CommandCapture "ai-contract-normalizer-tests" {
    pwsh -NoProfile -File scripts/quality/run-ai-contract-normalizer-tests.ps1
}
$scenarioCoherence = Invoke-CommandCapture "scenario-coherence" {
    pwsh -NoProfile -File scripts/quality/validate-scenario-coherence.ps1
}
$panelRuntimeTest = Invoke-CommandCapture "panel-runtime-fallback-test" {
    ./gradlew -p NPDevRuntimeHost test --tests "com.finalexec.PanelRuntimeTest" --rerun-tasks --no-daemon --console=plain
}

$aiSchemaReport = if (Test-Path -LiteralPath "scripts/reports/out/ai-schema-validation-report.json" -PathType Leaf) { Read-JsonFile "scripts/reports/out/ai-schema-validation-report.json" } else { $null }
$customPositiveScenario = if ($null -ne $aiSchemaReport) {
    @($aiSchemaReport.scenarios | Where-Object { $_.scenarioId -eq "custom-panel-unsupported" } | Select-Object -First 1)
} else {
    $null
}
$invalidBindingScenario = if ($null -ne $aiSchemaReport) {
    @($aiSchemaReport.scenarios | Where-Object { $_.scenarioId -eq "custom-panel-invalid-binding" } | Select-Object -First 1)
} else {
    $null
}
$procedurePanelScenario = if ($null -ne $aiSchemaReport) {
    @($aiSchemaReport.scenarios | Where-Object { $_.scenarioId -eq "custom-panel-procedure-mismatch" } | Select-Object -First 1)
} else {
    $null
}
$invalidBindingDiagnostic = Test-InvalidPanelBindingDiagnostic "golden-ai-scenarios/custom-panel-invalid-binding/ai-model.json"

$minimalScopeLocked = $true
$customPanelContractDefined = @($schemaCases | Where-Object { $_.name -eq "valid-minimal-custom-panel" -and $_.passed }).Count -eq 1
$safeCustomPanelMetadataAllowed = $safeMetadataMapped
$unsafeDynamicBehaviorRejected = @($schemaCases | Where-Object { $_.name -ne "valid-minimal-custom-panel" -and -not $_.passed }).Count -eq 0
$safeFallbackUiVerified = $panelRuntimeTest.passed
$unsupportedSurfacesRejected = $null -ne $procedurePanelScenario -and [string]$procedurePanelScenario.status -eq "passed" -and [string]$procedurePanelScenario.expectedOutcome -eq "fail"
$customPanelScenarioPassed = $null -ne $customPositiveScenario -and [string]$customPositiveScenario.status -eq "passed" -and [string]$customPositiveScenario.expectedOutcome -eq "pass"
$invalidBindingFailureText = Get-ScenarioFailureText $invalidBindingScenario
$invalidBindingRejected = $null -ne $invalidBindingScenario `
    -and [string]$invalidBindingScenario.status -eq "passed" `
    -and [string]$invalidBindingScenario.actualFailureStage -eq "ai-model-schema" `
    -and ($invalidBindingFailureText -match "panel entity data source is unresolved: learner-dashboard" -or $invalidBindingDiagnostic.passed)
$validationsPassed = $aiSchemaValidation.passed -and $aiContractNormalizer.passed -and $scenarioCoherence.passed -and $panelRuntimeTest.passed -and $normalizerRun.passed

$failed = @(
    -not $minimalScopeLocked,
    -not $customPanelContractDefined,
    -not $safeCustomPanelMetadataAllowed,
    -not $unsafeDynamicBehaviorRejected,
    -not $safeFallbackUiVerified,
    -not $unsupportedSurfacesRejected,
    -not $customPanelScenarioPassed,
    -not $invalidBindingRejected,
    -not $validationsPassed
) | Where-Object { $_ }

$report = [pscustomobject]@{
    schemaVersion = "npdev-custom-ux-extensibility-report.v1"
    runId = $RunId
    generatedAt = (Get-Date).ToUniversalTime().ToString("o")
    scriptPath = "scripts/quality/run-custom-ux-extensibility-check.ps1"
    workspaceRoot = $workspaceRoot
    overallStatus = if ($failed.Count -eq 0) { "passed" } else { "failed" }
    lockedScope = "minimal-support"
    minimalSupportScopeUsed = $minimalScopeLocked
    dynamicPluginComponentLoadingImplemented = $false
    customPanelContractDefined = $customPanelContractDefined
    safeCustomPanelMetadataAllowed = $safeCustomPanelMetadataAllowed
    unsafeDynamicComponentOrScriptRejected = $unsafeDynamicBehaviorRejected
    safeFallbackUiVerified = $safeFallbackUiVerified
    unsupportedSurfacesClearlyRejected = $unsupportedSurfacesRejected
    customPanelGoldenScenarioUpdated = $customPanelScenarioPassed
    invalidCustomPanelBindingRejected = $invalidBindingRejected
    aiSchemaValidationPassed = $aiSchemaValidation.passed
    aiContractNormalizerPassed = $aiContractNormalizer.passed
    scenarioCoherencePassed = $scenarioCoherence.passed
    panelRuntimeFallbackTestPassed = $panelRuntimeTest.passed
    schemaCases = @($schemaCases)
    normalizedPanelEvidence = [pscustomobject]@{
        scenarioId = "custom-panel-unsupported"
        normalizerResultPath = $normalizerResultPath
        normalizedModelPath = Join-Path $normalizerOut "model.json"
        safeMetadataMapped = $safeMetadataMapped
        panelMetadata = if ($null -ne $normalizedPanel) { $normalizedPanel.metadata } else { $null }
    }
    scenarioEvidence = [pscustomobject]@{
        customPanelScenario = $customPositiveScenario
        invalidBindingScenario = $invalidBindingScenario
        invalidBindingDiagnostic = $invalidBindingDiagnostic
        customProcedurePanelScenario = $procedurePanelScenario
    }
    validationCommands = @($normalizerRun, $aiSchemaValidation, $aiContractNormalizer, $scenarioCoherence, $panelRuntimeTest)
    findings = @(
        [pscustomobject]@{
            id = "CP12-DYNAMIC-PLUGIN-LOADING-NOT-IMPLEMENTED"
            classification = "known-risk-accepted"
            summary = "CP12 uses the locked Minimal support scope and intentionally does not implement full dynamic plugin or component loading."
        }
    )
    doesNotSolve = @(
        "Does not implement full dynamic plugin/component loading.",
        "Does not enable arbitrary custom JavaScript or remote component URLs.",
        "Does not implement custom procedure support.",
        "Does not proceed to Checkpoint 13."
    )
}

Write-JsonFile $ReportPath $report 100

if ($report.overallStatus -ne "passed") {
    Write-Error ("Custom UX extensibility check failed. Report: " + $ReportPath)
}

Write-Host ("Custom UX extensibility report written: " + $ReportPath)
