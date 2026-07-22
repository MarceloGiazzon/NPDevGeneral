param(
    [string]$ScenarioRoot = "golden-ai-scenarios",
    [string]$ReportPath = "scripts/reports/out/scenario-coherence-report.json",
    [string]$RunId = ""
)

$ErrorActionPreference = "Stop"

function Read-JsonFile {
    param([string]$Path)
    return Get-Content -Raw -LiteralPath $Path | ConvertFrom-Json
}

function Add-Violation {
    param(
        [System.Collections.Generic.List[object]]$Violations,
        [string]$ScenarioId,
        [string]$Code,
        [string]$Message,
        [string]$Classification = "current-roadmap-blocker"
    )
    $Violations.Add([pscustomobject]@{
            scenarioId = $ScenarioId
            code = $Code
            classification = $Classification
            message = $Message
        }) | Out-Null
}

function Resolve-ScenarioFile {
    param([System.IO.DirectoryInfo]$ScenarioDir, [string]$RelativePath)
    if ([string]::IsNullOrWhiteSpace($RelativePath)) {
        return $null
    }
    if ([System.IO.Path]::IsPathRooted($RelativePath) -or $RelativePath.Contains("..")) {
        return $null
    }
    $candidate = [System.IO.Path]::GetFullPath((Join-Path $ScenarioDir.FullName $RelativePath))
    $root = [System.IO.Path]::GetFullPath($ScenarioDir.FullName)
    if (-not $candidate.StartsWith($root, [System.StringComparison]::OrdinalIgnoreCase)) {
        return $null
    }
    return $candidate
}

function Split-DomainTokens {
    param([string[]]$Values)
    $stopWords = @(
        "app", "application", "beta", "custom", "demo", "exact", "expected", "flow",
        "invalid", "mismatch", "panel", "portal", "procedure", "scenario", "service",
        "simple", "unsupported", "workflow", "create", "read", "update", "delete",
        "list", "ops", "desk", "approval", "admission", "rejection", "binding"
    )
    $tokens = [System.Collections.Generic.HashSet[string]]::new([System.StringComparer]::OrdinalIgnoreCase)
    foreach ($value in @($Values)) {
        if ([string]::IsNullOrWhiteSpace($value)) {
            continue
        }
        $normalized = ([string]$value) -creplace "([a-z0-9])([A-Z])", '$1 $2'
        $normalized = $normalized -replace "[^A-Za-z0-9]+", " "
        foreach ($part in @($normalized -split "\s+")) {
            $token = $part.ToLowerInvariant()
            if ($token.Length -lt 3 -or $stopWords -contains $token) {
                continue
            }
            $tokens.Add($token) | Out-Null
        }
    }
    return @($tokens | Sort-Object)
}

function Get-AiModelVocabulary {
    param([object]$Model)
    $values = @()
    if ($null -ne $Model.app) {
        $values += [string]$Model.app.name
    }
    foreach ($entity in @($Model.entities)) {
        $values += [string]$entity.name
    }
    foreach ($panel in @($Model.panels)) {
        $values += [string]$panel.panelId
    }
    foreach ($procedure in @($Model.procedures)) {
        $values += [string]$procedure.procedureId
    }
    foreach ($workflow in @($Model.workflows)) {
        $values += [string]$workflow.workflowId
    }
    return Split-DomainTokens $values
}

function Get-OfficialModelVocabulary {
    param([object]$Model)
    $values = @()
    foreach ($concept in @($Model.concepts)) {
        $values += [string]$concept.name
    }
    foreach ($panel in @($Model.panels)) {
        $values += [string]$panel.name
    }
    foreach ($procedure in @($Model.procedures)) {
        $values += [string]$procedure.name
    }
    return Split-DomainTokens $values
}

function Get-DeclaredReferenceNames {
    param([object]$Manifest, [object]$AiModel, [object]$OfficialModel)
    $names = [System.Collections.Generic.HashSet[string]]::new([System.StringComparer]::OrdinalIgnoreCase)
    $names.Add([string]$Manifest.scenarioId) | Out-Null
    foreach ($entity in @($AiModel.entities)) { $names.Add([string]$entity.name) | Out-Null }
    foreach ($field in @($AiModel.entities | ForEach-Object { @($_.fields) })) { $names.Add([string]$field.name) | Out-Null }
    foreach ($flow in @($AiModel.flows)) {
        $names.Add([string]$flow.name) | Out-Null
        $names.Add([string]$flow.entity) | Out-Null
    }
    foreach ($panel in @($AiModel.panels)) { $names.Add([string]$panel.panelId) | Out-Null }
    foreach ($procedure in @($AiModel.procedures)) { $names.Add([string]$procedure.procedureId) | Out-Null }
    foreach ($workflow in @($AiModel.workflows)) { $names.Add([string]$workflow.workflowId) | Out-Null }
    foreach ($concept in @($OfficialModel.concepts)) { $names.Add([string]$concept.name) | Out-Null }
    foreach ($panel in @($OfficialModel.panels)) { $names.Add([string]$panel.name) | Out-Null }
    foreach ($procedure in @($OfficialModel.procedures)) { $names.Add([string]$procedure.name) | Out-Null }
    return $names
}

function Test-ExpectedClassPrefix {
    param([string]$ExpectedClass)
    return -not [string]::IsNullOrWhiteSpace($ExpectedClass) -and ($ExpectedClass.StartsWith("FAIL_") -or $ExpectedClass.StartsWith("NEGATIVE_"))
}

function Test-PositiveExpectedClass {
    param([string]$ExpectedClass)
    return -not [string]::IsNullOrWhiteSpace($ExpectedClass) -and $ExpectedClass.StartsWith("PASS_")
}

function Get-BehaviorFiles {
    param([object]$Manifest)
    $mapping = [ordered]@{
        expectedBehavior = "expected-behavior.json"
        expectedPanelBehavior = "expected-panel-behavior.json"
        expectedProcedureBehavior = "expected-procedure-behavior.json"
        expectedWorkflowBehavior = "expected-workflow-behavior.json"
        expectedVerificationBehavior = "expected-verification-behavior.json"
    }
    $files = @()
    foreach ($property in $mapping.Keys) {
        $path = if ($null -ne $Manifest.files -and $null -ne $Manifest.files.$property) {
            [string]$Manifest.files.$property
        }
        else {
            [string]$mapping[$property]
        }
        $files += [pscustomobject]@{
            property = $property
            path = $path
            required = ($null -ne $Manifest.files -and $null -ne $Manifest.files.$property)
        }
    }
    return $files
}

$workspaceRoot = (Resolve-Path ".").Path
if ([string]::IsNullOrWhiteSpace($RunId)) {
    $RunId = "scenario-coherence-" + (Get-Date).ToUniversalTime().ToString("yyyyMMdd-HHmmssfff")
}
$scenarioRootPath = (Resolve-Path $ScenarioRoot).Path
$scenarioDirs = @(Get-ChildItem -LiteralPath $scenarioRootPath -Directory | Where-Object { $_.Name -ne "deferred" } | Sort-Object Name)
$results = @()
$allViolations = [System.Collections.Generic.List[object]]::new()
$negativeScenarioPositiveClassCount = 0
$domainMismatchCount = 0

foreach ($scenarioDir in $scenarioDirs) {
    $scenarioViolations = [System.Collections.Generic.List[object]]::new()
    $scenarioId = $scenarioDir.Name
    $manifest = $null
    $aiModel = $null
    $officialModel = $null
    $expectedClass = $null
    $expectedClasses = @()
    $isNegative = $false
    $domainMismatch = $false

    try {
        $manifestPath = Join-Path $scenarioDir.FullName "scenario.manifest.json"
        if (-not (Test-Path -LiteralPath $manifestPath -PathType Leaf)) {
            throw "scenario.manifest.json is missing."
        }
        $manifest = Read-JsonFile $manifestPath
        $scenarioId = [string]$manifest.scenarioId
        $isNegative = ([string]$manifest.kind -eq "negative" -or [string]$manifest.expectedOutcome -eq "fail")

        foreach ($behaviorFile in Get-BehaviorFiles $manifest) {
            $behaviorPath = Resolve-ScenarioFile $scenarioDir $behaviorFile.path
            if ($null -eq $behaviorPath) {
                if ($behaviorFile.required) {
                    Add-Violation $scenarioViolations $scenarioId "behavior-path-unsafe" ("Behavior file path is unsafe: " + $behaviorFile.path)
                }
                continue
            }
            if (-not (Test-Path -LiteralPath $behaviorPath -PathType Leaf)) {
                if ($behaviorFile.required) {
                    Add-Violation $scenarioViolations $scenarioId "behavior-file-missing" ("Declared behavior file is missing: " + $behaviorFile.path)
                }
                continue
            }
            $behavior = Read-JsonFile $behaviorPath
            if ($behaviorFile.property -eq "expectedBehavior" -and [string]$behavior.scenarioId -ne $scenarioId) {
                Add-Violation $scenarioViolations $scenarioId "behavior-scenario-id-mismatch" ("expected-behavior scenarioId does not match manifest scenarioId.")
            }
            $class = [string]$behavior.expectedClass
            if (-not [string]::IsNullOrWhiteSpace($class)) {
                $expectedClasses += [pscustomobject]@{
                    file = $behaviorFile.path
                    property = $behaviorFile.property
                    expectedClass = $class
                }
                if ($behaviorFile.property -eq "expectedBehavior") {
                    $expectedClass = $class
                }
            }
        }

        if ($isNegative -and -not [string]::IsNullOrWhiteSpace($expectedClass) -and -not (Test-ExpectedClassPrefix $expectedClass)) {
            $negativeScenarioPositiveClassCount++
            Add-Violation $scenarioViolations $scenarioId "negative-scenario-positive-class" ("Negative scenario root expectedClass must start with FAIL_ or NEGATIVE_: " + $expectedClass)
        }
        if (-not $isNegative -and -not [string]::IsNullOrWhiteSpace($expectedClass) -and -not (Test-PositiveExpectedClass $expectedClass)) {
            Add-Violation $scenarioViolations $scenarioId "positive-scenario-non-pass-class" ("Positive scenario root expectedClass must start with PASS_: " + $expectedClass)
        }

        foreach ($classInfo in @($expectedClasses | Where-Object { $_.property -ne "expectedBehavior" })) {
            if ($isNegative -and -not (Test-ExpectedClassPrefix ([string]$classInfo.expectedClass)) -and -not [string]::IsNullOrWhiteSpace($expectedClass) -and (Test-ExpectedClassPrefix $expectedClass) -and [string]$expectedClass -notmatch "INTEGRATION|BEHAVIOR_MISMATCH") {
                Add-Violation $scenarioViolations $scenarioId "specialized-behavior-contradicts-root" ("Specialized behavior class contradicts negative root expectedClass: " + $classInfo.file)
            }
            if (-not $isNegative -and -not (Test-PositiveExpectedClass ([string]$classInfo.expectedClass))) {
                Add-Violation $scenarioViolations $scenarioId "specialized-behavior-contradicts-root" ("Specialized behavior class contradicts positive root expectedClass: " + $classInfo.file)
            }
        }

        if ($isNegative -and [string]::IsNullOrWhiteSpace([string]$manifest.expectedFailureStage)) {
            Add-Violation $scenarioViolations $scenarioId "negative-missing-failure-stage" "Negative scenarios must declare expectedFailureStage."
        }
        if (-not $isNegative -and [string]$manifest.expectedOutcome -ne "pass") {
            Add-Violation $scenarioViolations $scenarioId "manifest-kind-outcome-mismatch" "Positive scenario kind must align with expectedOutcome=pass."
        }
        if ($isNegative -and [string]$manifest.expectedOutcome -ne "fail") {
            Add-Violation $scenarioViolations $scenarioId "manifest-kind-outcome-mismatch" "Negative scenario kind must align with expectedOutcome=fail."
        }

        $aiModelPath = if ($null -ne $manifest.files -and $null -ne $manifest.files.aiModel) { Resolve-ScenarioFile $scenarioDir ([string]$manifest.files.aiModel) } else { $null }
        $officialModelPath = if ($null -ne $manifest.files -and $null -ne $manifest.files.model) { Resolve-ScenarioFile $scenarioDir ([string]$manifest.files.model) } else { $null }
        if ($null -ne $aiModelPath -and (Test-Path -LiteralPath $aiModelPath -PathType Leaf)) {
            $aiModel = Read-JsonFile $aiModelPath
        }
        if ($null -ne $officialModelPath -and (Test-Path -LiteralPath $officialModelPath -PathType Leaf)) {
            $officialModel = Read-JsonFile $officialModelPath
        }
        if ($null -ne $aiModel -and $null -ne $officialModel) {
            $aiVocabulary = @(Get-AiModelVocabulary $aiModel)
            $officialVocabulary = @(Get-OfficialModelVocabulary $officialModel)
            $overlap = @($aiVocabulary | Where-Object { $officialVocabulary -contains $_ })
            $explicitlyAllowed = $false
            if ($null -ne $manifest.coherence -and $manifest.coherence.allowDomainMismatch -eq $true) {
                $explicitlyAllowed = $true
            }
            if ($aiVocabulary.Count -gt 0 -and $officialVocabulary.Count -gt 0 -and $overlap.Count -eq 0 -and -not $explicitlyAllowed) {
                $domainMismatch = $true
                $domainMismatchCount++
                Add-Violation $scenarioViolations $scenarioId "domain-mismatch" ("ai-model.json and model.json describe unrelated vocabularies: aiModel=[" + ($aiVocabulary -join ",") + "], model=[" + ($officialVocabulary -join ",") + "]")
            }

            $declaredNames = Get-DeclaredReferenceNames $manifest $aiModel $officialModel
            foreach ($behaviorFile in Get-BehaviorFiles $manifest) {
                $behaviorPath = Resolve-ScenarioFile $scenarioDir $behaviorFile.path
                if ($null -eq $behaviorPath -or -not (Test-Path -LiteralPath $behaviorPath -PathType Leaf)) {
                    continue
                }
                $behavior = Read-JsonFile $behaviorPath
                foreach ($assertion in @($behavior.assertions)) {
                    $assertionPath = [string]$assertion.path
                    $checksDeclaredReference = (
                        $assertionPath -match "observations\.model\.(concepts|procedures|panels)$" -or
                        $assertionPath -match "observations\.customProcedure\.procedureId$" -or
                        $assertionPath -match "observations\.customPanel\.panelId$"
                    )
                    if (-not $checksDeclaredReference) {
                        continue
                    }
                    foreach ($candidate in @($assertion.equals)) {
                        if ($candidate -is [string] -and $candidate -match "^[A-Za-z][A-Za-z0-9-]*$" -and -not $candidate.StartsWith("PASS_") -and -not $candidate.StartsWith("FAIL_") -and -not $candidate.StartsWith("NEGATIVE_")) {
                            if (-not $declaredNames.Contains($candidate)) {
                                Add-Violation $scenarioViolations $scenarioId "expected-reference-unresolved" ("Expected behavior references a value not declared by sibling files: " + $candidate)
                            }
                        }
                    }
                }
            }
        }
    }
    catch {
        Add-Violation $scenarioViolations $scenarioId "scenario-coherence-exception" ([string]$_.Exception.Message)
    }

    foreach ($violation in @($scenarioViolations)) {
        $allViolations.Add($violation) | Out-Null
    }
    $results += [pscustomobject]@{
        scenarioId = $scenarioId
        status = if ($scenarioViolations.Count -eq 0) { "passed" } else { "failed" }
        kind = if ($null -ne $manifest) { [string]$manifest.kind } else { "" }
        expectedOutcome = if ($null -ne $manifest) { [string]$manifest.expectedOutcome } else { "" }
        expectedFailureStage = if ($null -ne $manifest) { [string]$manifest.expectedFailureStage } else { "" }
        expectedClass = $expectedClass
        negativeScenario = $isNegative
        domainMismatch = $domainMismatch
        violations = @($scenarioViolations)
    }
}

$report = [pscustomobject]@{
    schemaVersion = "npdev-scenario-coherence-report.v1"
    runId = $RunId
    generatedAt = (Get-Date).ToUniversalTime().ToString("o")
    scriptPath = "scripts/quality/validate-scenario-coherence.ps1"
    workspaceRoot = $workspaceRoot
    scenarioRoot = $scenarioRootPath
    overallStatus = if ($allViolations.Count -eq 0) { "passed" } else { "failed" }
    scenarioCount = $results.Count
    violationCount = $allViolations.Count
    negativeScenarioPositiveClassCount = $negativeScenarioPositiveClassCount
    domainMismatchCount = $domainMismatchCount
    scenarios = $results
    violations = @($allViolations)
}

$reportDirectory = Split-Path -Parent $ReportPath
if (-not [string]::IsNullOrWhiteSpace($reportDirectory)) {
    New-Item -ItemType Directory -Force -Path $reportDirectory | Out-Null
}
$report | ConvertTo-Json -Depth 40 | Set-Content -LiteralPath $ReportPath -Encoding UTF8

if ($report.overallStatus -ne "passed") {
    Write-Error ("Scenario coherence validation failed. See " + $ReportPath)
}
else {
    Write-Host ("Scenario coherence validation passed. Report: " + $ReportPath)
}
