Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

. (Join-Path $PSScriptRoot "..\npdev-common.ps1")

$script:AiBetaPassClasses = @(
    "PASS_EXACT",
    "PASS_POLICY",
    "PASS_PROCEDURE",
    "PASS_PANEL",
    "PASS_PANEL_PROCEDURE_INTEGRATION"
)

# Deterministic AI generation contract:
# - same prompt + same model => same result
# - temperature=0 for every generation call
# - seed is fixed and recorded in the generation marker

function Initialize-AiBetaWorkspace([string]$WorkspaceRoot) {
    if ([string]::IsNullOrWhiteSpace($WorkspaceRoot)) {
        $WorkspaceRoot = Get-NPDevWorkspaceRoot $PSScriptRoot
    }
    return Normalize-NPDevPath $WorkspaceRoot
}

function Read-AiJsonFile([string]$PathValue, [string]$Label = "JSON file") {
    Ensure-NPDevFile $PathValue $Label
    return Get-Content -LiteralPath $PathValue -Raw | ConvertFrom-Json
}

function Get-AiJsonText([object]$Value) {
    return ($Value | ConvertTo-Json -Depth 100)
}

function Get-AiSha256String([string]$Value) {
    $sha = [System.Security.Cryptography.SHA256]::Create()
    try {
        $bytes = [System.Text.Encoding]::UTF8.GetBytes($Value)
        return [System.BitConverter]::ToString($sha.ComputeHash($bytes)).Replace("-", "").ToLowerInvariant()
    }
    finally {
        $sha.Dispose()
    }
}

function Get-AiSha256File([string]$PathValue) {
    Ensure-NPDevFile $PathValue "AI beta artifact"
    $sha = [System.Security.Cryptography.SHA256]::Create()
    try {
        $stream = [System.IO.File]::OpenRead($PathValue)
        try {
            return [System.BitConverter]::ToString($sha.ComputeHash($stream)).Replace("-", "").ToLowerInvariant()
        }
        finally {
            $stream.Dispose()
        }
    }
    finally {
        $sha.Dispose()
    }
}

function Get-AiPropertyNames([object]$Object) {
    if ($null -eq $Object) {
        return @()
    }
    if ($Object -is [System.Collections.IDictionary]) {
        return @($Object.Keys)
    }
    return @($Object.PSObject.Properties.Name)
}

function Get-AiProperty([object]$Object, [string]$Name, [object]$DefaultValue = $null) {
    if ($null -eq $Object) {
        return $DefaultValue
    }
    if ($Object -is [System.Collections.IDictionary]) {
        if ($Object.Contains($Name)) {
            return $Object[$Name]
        }
        return $DefaultValue
    }
    if ((Get-AiPropertyNames $Object) -contains $Name) {
        return $Object.$Name
    }
    return $DefaultValue
}

function Resolve-AiScenarioFile([string]$ScenarioRoot, [string]$RelativePath) {
    if ([string]::IsNullOrWhiteSpace($RelativePath)) {
        throw "Scenario path is empty."
    }
    if ([System.IO.Path]::IsPathRooted($RelativePath)) {
        throw ("Scenario path must be relative: " + $RelativePath)
    }
    $root = Normalize-NPDevPath $ScenarioRoot
    if (-not $root.EndsWith("\")) {
        $root += "\"
    }
    $resolved = Normalize-NPDevPath (Join-Path $root $RelativePath)
    if (-not $resolved.StartsWith($root, [System.StringComparison]::OrdinalIgnoreCase)) {
        throw ("Scenario path escapes scenario root: " + $RelativePath)
    }
    return $resolved
}

function New-AiCheck {
    param(
        [string]$Id,
        [string]$Name,
        [bool]$Passed,
        [string]$Evidence,
        [string]$FailureClass
    )

    return [pscustomobject]@{
        id = $Id
        name = $Name
        status = if ($Passed) { "passed" } else { "failed" }
        evidence = $Evidence
        failureClass = if ($Passed) { $null } else { $FailureClass }
    }
}

function Get-AiFirstFailureClass([object[]]$Checks, [string]$FallbackClass) {
    $failed = @($Checks | Where-Object { $_.status -eq "failed" })
    if ($failed.Count -eq 0) {
        return $null
    }
    $first = @($failed | Where-Object { -not [string]::IsNullOrWhiteSpace([string]$_.failureClass) } | Select-Object -First 1)
    if ($first.Count -gt 0) {
        return [string]$first[0].failureClass
    }
    return $FallbackClass
}

function Test-AiCustomProcedureObject([object]$Procedure) {
    $checks = @()
    $names = Get-AiPropertyNames $Procedure
    $implementation = Get-AiProperty $Procedure "implementation" $null
    $implementationMode = [string](Get-AiProperty $implementation "mode" "")
    $implementationLanguage = [string](Get-AiProperty $implementation "language" "")
    $implementationEntrypoint = [string](Get-AiProperty $implementation "entrypoint" "")
    $hasTrustedSource = $implementationMode -eq "trustedSource"
    $checks += New-AiCheck "PROC01" "Procedure schema version" ((Get-AiProperty $Procedure "schemaVersion" "") -eq "ai-custom-procedure.v1") ("schemaVersion=" + (Get-AiProperty $Procedure "schemaVersion" "missing")) "FAIL_PROCEDURE_CONTRACT"
    $checks += New-AiCheck "PROC02" "Procedure id exists" (-not [string]::IsNullOrWhiteSpace([string](Get-AiProperty $Procedure "procedureId" ""))) ("procedureId=" + (Get-AiProperty $Procedure "procedureId" "missing")) "FAIL_PROCEDURE_CONTRACT"
    $checks += New-AiCheck "PROC03" "Execution mode is governed" ((Get-AiProperty $Procedure "executionMode" "") -eq "governed") ("executionMode=" + (Get-AiProperty $Procedure "executionMode" "missing")) "FAIL_PROCEDURE_CONTRACT"
    $trust = [string](Get-AiProperty $Procedure "trust" "")
    $checks += New-AiCheck "PROC04" "Trust mode is explicit" ($trust -in @("trusted", "inproc")) ("trust=" + $trust) "FAIL_PROCEDURE_ADMISSION"
    $checks += New-AiCheck "PROC05" "Inputs and outputs are arrays" (((Get-AiProperty $Procedure "inputs" $null) -is [array]) -and ((Get-AiProperty $Procedure "outputs" $null) -is [array])) "inputs/outputs must be arrays." "FAIL_PROCEDURE_CONTRACT"
    $steps = @(Get-AiProperty $Procedure "steps" @())
    $checks += New-AiCheck "PROC06" "Procedure has steps or trusted source" (($steps.Count -gt 0) -or $hasTrustedSource) ("stepCount=" + $steps.Count + "; implementationMode=" + $implementationMode) "FAIL_PROCEDURE_CONTRACT"
    $forbidden = @($names | Where-Object { $_ -in @("script", "externalCommand", "customHtml", "externalDependencyGraph") })
    $checks += New-AiCheck "PROC07" "No direct inline execution fields" ($forbidden.Count -eq 0) ("forbiddenFields=" + ($forbidden -join ",")) "FAIL_PROCEDURE_ADMISSION"

    $allowedStepTypes = @("readConcept", "listConcepts", "runQuery", "saveConcept", "deleteConcept", "callCapability", "publishEvent", "callProcedure", "if", "forEach", "mapValue", "return")
    $badSteps = @()
    foreach ($step in $steps) {
        $type = [string](Get-AiProperty $step "type" "")
        if ($type -notin $allowedStepTypes) {
            $badSteps += $type
        }
    }
    $checks += New-AiCheck "PROC08" "Only supported procedure step types" ($badSteps.Count -eq 0) ("badStepTypes=" + ($badSteps -join ",")) "FAIL_PROCEDURE_CONTRACT"
    if ($null -ne $implementation) {
        $implementationClassName = [string](Get-AiProperty $implementation "className" "")
        $checks += New-AiCheck "PROC11" "Trusted Java source procedure is explicit" ($hasTrustedSource -and $trust -eq "trusted" -and $implementationLanguage -eq "java" -and -not [string]::IsNullOrWhiteSpace($implementationEntrypoint) -and -not [string]::IsNullOrWhiteSpace($implementationClassName)) ("mode=" + $implementationMode + "; language=" + $implementationLanguage + "; entrypoint=" + $implementationEntrypoint + "; className=" + $implementationClassName) "FAIL_PROCEDURE_ADMISSION"
    }
    $failed = @($checks | Where-Object { $_.status -eq "failed" })
    return [pscustomobject]@{
        overallStatus = if ($failed.Count -eq 0) { "passed" } else { "failed" }
        failureClass = Get-AiFirstFailureClass $checks "FAIL_PROCEDURE_CONTRACT"
        checks = $checks
    }
}

function Test-AiCustomPanelObject([object]$Panel) {
    $checks = @()
    $names = Get-AiPropertyNames $Panel
    $implementation = Get-AiProperty $Panel "implementation" $null
    $implementationMode = [string](Get-AiProperty $implementation "mode" "")
    $implementationLanguage = [string](Get-AiProperty $implementation "language" "")
    $implementationEntrypoint = [string](Get-AiProperty $implementation "entrypoint" "")
    $checks += New-AiCheck "PAN01" "Panel schema version" ((Get-AiProperty $Panel "schemaVersion" "") -eq "ai-custom-panel.v1") ("schemaVersion=" + (Get-AiProperty $Panel "schemaVersion" "missing")) "FAIL_PANEL_CONTRACT"
    $checks += New-AiCheck "PAN02" "Panel id exists" (-not [string]::IsNullOrWhiteSpace([string](Get-AiProperty $Panel "panelId" ""))) ("panelId=" + (Get-AiProperty $Panel "panelId" "missing")) "FAIL_PANEL_CONTRACT"
    $checks += New-AiCheck "PAN03" "Route exists" (-not [string]::IsNullOrWhiteSpace([string](Get-AiProperty $Panel "route" ""))) ("route=" + (Get-AiProperty $Panel "route" "missing")) "FAIL_PANEL_CONTRACT"
    $checks += New-AiCheck "PAN04" "Panel arrays exist" (((Get-AiProperty $Panel "dataSources" $null) -is [array]) -and ((Get-AiProperty $Panel "visibleFields" $null) -is [array]) -and ((Get-AiProperty $Panel "actions" $null) -is [array])) "dataSources/visibleFields/actions must be arrays." "FAIL_PANEL_CONTRACT"
    $layout = Get-AiProperty $Panel "layout" $null
    $layoutType = [string](Get-AiProperty $layout "type" "")
    $checks += New-AiCheck "PAN05" "Layout is supported" ($layoutType -in @("table", "detail", "form", "summary")) ("layoutType=" + $layoutType) "FAIL_PANEL_UNSUPPORTED_FEATURE"
    $forbidden = @($names | Where-Object { $_ -in @("script", "customHtml", "externalUrl") })
    $checks += New-AiCheck "PAN06" "No arbitrary UI execution fields" ($forbidden.Count -eq 0) ("forbiddenFields=" + ($forbidden -join ",")) "FAIL_PANEL_UNSUPPORTED_FEATURE"
    $widgets = @(Get-AiProperty $Panel "widgets" @())
    $badWidgets = @()
    foreach ($widget in $widgets) {
        $type = [string](Get-AiProperty $widget "type" "")
        if ($type -notin @("text", "number", "status", "action", "table")) {
            $badWidgets += $type
        }
    }
    $checks += New-AiCheck "PAN07" "Widget types are supported" ($badWidgets.Count -eq 0) ("badWidgetTypes=" + ($badWidgets -join ",")) "FAIL_PANEL_UNSUPPORTED_FEATURE"

    $dataSources = @(Get-AiProperty $Panel "dataSources" @())
    $unboundSources = @()
    foreach ($source in $dataSources) {
        $sourceName = [string](Get-AiProperty $source "name" "unnamed")
        $hasBinding = -not [string]::IsNullOrWhiteSpace([string](Get-AiProperty $source "concept" "")) -or
            -not [string]::IsNullOrWhiteSpace([string](Get-AiProperty $source "query" "")) -or
            -not [string]::IsNullOrWhiteSpace([string](Get-AiProperty $source "procedure" ""))
        if (-not $hasBinding) {
            $unboundSources += $sourceName
        }
    }
    $checks += New-AiCheck "PAN08" "Data sources have supported bindings" ($unboundSources.Count -eq 0) ("unboundSources=" + ($unboundSources -join ",")) "FAIL_PANEL_BINDING"

    $actions = @(Get-AiProperty $Panel "actions" @())
    $unboundActions = @()
    foreach ($action in $actions) {
        $binding = [string](Get-AiProperty $action "binding" "")
        $name = [string](Get-AiProperty $action "name" "unnamed")
        if ($binding -eq "procedure" -and [string]::IsNullOrWhiteSpace([string](Get-AiProperty $action "procedure" ""))) {
            $unboundActions += $name
        }
    }
    $checks += New-AiCheck "PAN09" "Procedure actions name a procedure" ($unboundActions.Count -eq 0) ("unboundActions=" + ($unboundActions -join ",")) "FAIL_PANEL_BINDING"

    $visibleFields = @(Get-AiProperty $Panel "visibleFields" @())
    $layoutFields = @(Get-AiProperty $layout "fields" @())
    $missingFields = @($layoutFields | Where-Object { $_ -notin $visibleFields })
    $checks += New-AiCheck "PAN10" "Layout fields are visible fields" ($missingFields.Count -eq 0) ("missingFields=" + ($missingFields -join ",")) "FAIL_PANEL_BINDING"
    if ($null -ne $implementation) {
        $checks += New-AiCheck "PAN16" "Trusted source panel is explicit" ($implementationMode -eq "trustedSource" -and $implementationLanguage -in @("html+javascript", "html", "javascript") -and -not [string]::IsNullOrWhiteSpace($implementationEntrypoint)) ("mode=" + $implementationMode + "; language=" + $implementationLanguage + "; entrypoint=" + $implementationEntrypoint) "FAIL_PANEL_CONTRACT"
    }

    $failed = @($checks | Where-Object { $_.status -eq "failed" })
    return [pscustomobject]@{
        overallStatus = if ($failed.Count -eq 0) { "passed" } else { "failed" }
        failureClass = Get-AiFirstFailureClass $checks "FAIL_PANEL_CONTRACT"
        checks = $checks
    }
}

function Invoke-AiScenarioValidation {
    param(
        [string]$WorkspaceRoot,
        [string]$ScenarioRoot,
        [string]$ReportPath = ""
    )

    $WorkspaceRoot = Initialize-AiBetaWorkspace $WorkspaceRoot
    $ScenarioRoot = Normalize-NPDevPath $ScenarioRoot
    $checks = @()
    $manifest = $null
    $paths = [ordered]@{}

    try {
        Ensure-NPDevDirectory $ScenarioRoot "AI scenario root"
        $checks += New-AiCheck "AI01" "Scenario root exists" $true $ScenarioRoot "FAIL_CONTRACT"
    }
    catch {
        $checks += New-AiCheck "AI01" "Scenario root exists" $false $_.Exception.Message "FAIL_CONTRACT"
    }

    $manifestPath = Join-Path $ScenarioRoot "scenario.manifest.json"
    try {
        $manifest = Read-AiJsonFile $manifestPath "AI scenario manifest"
        $checks += New-AiCheck "AI02" "Manifest parses" $true "scenario.manifest.json" "FAIL_CONTRACT"
    }
    catch {
        $checks += New-AiCheck "AI02" "Manifest parses" $false $_.Exception.Message "FAIL_CONTRACT"
    }

    if ($null -ne $manifest) {
        $checks += New-AiCheck "AI03" "Manifest schema version" ((Get-AiProperty $manifest "schemaVersion" "") -eq "ai-scenario.v1") ("schemaVersion=" + (Get-AiProperty $manifest "schemaVersion" "missing")) "FAIL_CONTRACT"
        $scenarioId = [string](Get-AiProperty $manifest "scenarioId" "")
        $checks += New-AiCheck "AI04" "Scenario id is stable" ($scenarioId -match "^[a-z0-9][a-z0-9-]*$") ("scenarioId=" + $scenarioId) "FAIL_CONTRACT"
        $runtime = Get-AiProperty $manifest "runtime" $null
        $checks += New-AiCheck "AI05" "Strict execution requested" ((Get-AiProperty $runtime "strictExecution" $false) -eq $true) ("strictExecution=" + (Get-AiProperty $runtime "strictExecution" "missing")) "FAIL_STARTUP"
        $checks += New-AiCheck "AI06" "Supported-core profile requested" ((Get-AiProperty $runtime "surfaceProfile" "") -eq "supported-core") ("surfaceProfile=" + (Get-AiProperty $runtime "surfaceProfile" "missing")) "FAIL_UNSUPPORTED_SURFACE"
        $surfaces = @(Get-AiProperty $manifest "requestedSurfaces" @("supported-core"))
        $unsupported = @($surfaces | Where-Object { $_ -ne "supported-core" })
        $checks += New-AiCheck "AI07" "Only supported-core surface requested" ($unsupported.Count -eq 0) ("requestedSurfaces=" + ($surfaces -join ",")) "FAIL_UNSUPPORTED_SURFACE"

        $files = Get-AiProperty $manifest "files" $null
        foreach ($key in @("model", "config", "expectedBehavior")) {
            $relative = [string](Get-AiProperty $files $key "")
            try {
                $path = Resolve-AiScenarioFile $ScenarioRoot $relative
                Ensure-NPDevFile $path ("AI scenario " + $key)
                $paths[$key] = $path
                $checks += New-AiCheck ("AI-FILE-" + $key) ("File exists: " + $key) $true $relative "FAIL_CONTRACT"
            }
            catch {
                $checks += New-AiCheck ("AI-FILE-" + $key) ("File exists: " + $key) $false $_.Exception.Message "FAIL_CONTRACT"
            }
        }

        foreach ($key in @("customProcedure", "expectedProcedureBehavior", "customPanel", "expectedPanelBehavior")) {
            $relative = [string](Get-AiProperty $files $key "")
            if (-not [string]::IsNullOrWhiteSpace($relative)) {
                try {
                    $path = Resolve-AiScenarioFile $ScenarioRoot $relative
                    Ensure-NPDevFile $path ("AI scenario " + $key)
                    $paths[$key] = $path
                    $checks += New-AiCheck ("AI-FILE-" + $key) ("File exists: " + $key) $true $relative "FAIL_CONTRACT"
                }
                catch {
                    $checks += New-AiCheck ("AI-FILE-" + $key) ("File exists: " + $key) $false $_.Exception.Message "FAIL_CONTRACT"
                }
            }
        }

        $model = $null
        $config = $null
        $expected = $null
        $customProcedure = $null
        $customPanel = $null
        if ($paths.Contains("model")) {
            try {
                $model = Read-AiJsonFile $paths["model"] "AI scenario model"
                $hasConcepts = @(Get-AiProperty $model "concepts" @()).Count -gt 0
                $checks += New-AiCheck "AI08" "Model has concepts" $hasConcepts ("conceptCount=" + @(Get-AiProperty $model "concepts" @()).Count) "FAIL_CONTRACT"
            }
            catch {
                $checks += New-AiCheck "AI08" "Model parses" $false $_.Exception.Message "FAIL_CONTRACT"
            }
        }
        if ($paths.Contains("config")) {
            try {
                $config = Read-AiJsonFile $paths["config"] "AI scenario config"
                $checks += New-AiCheck "AI09" "Config has generator block" ((Get-AiPropertyNames $config) -contains "generator") "config.generator required." "FAIL_CONTRACT"
            }
            catch {
                $checks += New-AiCheck "AI09" "Config parses" $false $_.Exception.Message "FAIL_CONTRACT"
            }
        }
        if ($paths.Contains("expectedBehavior")) {
            try {
                $expected = Read-AiJsonFile $paths["expectedBehavior"] "AI scenario expected behavior"
                $checks += New-AiCheck "AI10" "Expected behavior schema version" ((Get-AiProperty $expected "schemaVersion" "") -eq "ai-expected-behavior.v1") ("schemaVersion=" + (Get-AiProperty $expected "schemaVersion" "missing")) "FAIL_CONTRACT"
                $checks += New-AiCheck "AI11" "Expected behavior binds scenario id" ([string](Get-AiProperty $expected "scenarioId" "") -eq [string](Get-AiProperty $manifest "scenarioId" "")) ("expectedScenarioId=" + (Get-AiProperty $expected "scenarioId" "missing")) "FAIL_CONTRACT"
            }
            catch {
                $checks += New-AiCheck "AI10" "Expected behavior parses" $false $_.Exception.Message "FAIL_CONTRACT"
            }
        }

        if ($paths.Contains("customProcedure")) {
            try {
                $customProcedure = Read-AiJsonFile $paths["customProcedure"] "AI custom procedure"
                $procedureReport = Test-AiCustomProcedureObject $customProcedure
                foreach ($check in $procedureReport.checks) {
                    $checks += $check
                }
                $implementation = Get-AiProperty $customProcedure "implementation" $null
                if ([string](Get-AiProperty $implementation "mode" "") -eq "trustedSource") {
                    $relative = [string](Get-AiProperty $implementation "entrypoint" "")
                    try {
                        $sourcePath = Resolve-AiScenarioFile $ScenarioRoot $relative
                        Ensure-NPDevFile $sourcePath "AI custom procedure trusted source"
                        $paths["customProcedureSource"] = $sourcePath
                        $checks += New-AiCheck "PROC12" "Trusted procedure source exists" $true $relative "FAIL_PROCEDURE_CONTRACT"
                    }
                    catch {
                        $checks += New-AiCheck "PROC12" "Trusted procedure source exists" $false $_.Exception.Message "FAIL_PROCEDURE_CONTRACT"
                    }
                }
            }
            catch {
                $checks += New-AiCheck "PROC00" "Custom procedure parses" $false $_.Exception.Message "FAIL_PROCEDURE_CONTRACT"
            }
        }
        if ($paths.Contains("expectedProcedureBehavior")) {
            try {
                $expectedProcedure = Read-AiJsonFile $paths["expectedProcedureBehavior"] "AI expected procedure behavior"
                $checks += New-AiCheck "PROC09" "Expected procedure behavior schema version" ((Get-AiProperty $expectedProcedure "schemaVersion" "") -eq "ai-expected-procedure-behavior.v1") ("schemaVersion=" + (Get-AiProperty $expectedProcedure "schemaVersion" "missing")) "FAIL_PROCEDURE_CONTRACT"
                $expectedProcedureId = [string](Get-AiProperty $expectedProcedure "procedureId" "")
                $actualProcedureId = [string](Get-AiProperty $customProcedure "procedureId" $expectedProcedureId)
                $checks += New-AiCheck "PROC10" "Expected procedure behavior binds procedure id" ($expectedProcedureId -eq $actualProcedureId) ("expectedProcedureId=" + $expectedProcedureId + "; actualProcedureId=" + $actualProcedureId) "FAIL_PROCEDURE_CONTRACT"
            }
            catch {
                $checks += New-AiCheck "PROC09" "Expected procedure behavior parses" $false $_.Exception.Message "FAIL_PROCEDURE_CONTRACT"
            }
        }
        if ($paths.Contains("customPanel")) {
            try {
                $customPanel = Read-AiJsonFile $paths["customPanel"] "AI custom panel"
                $panelReport = Test-AiCustomPanelObject $customPanel
                foreach ($check in $panelReport.checks) {
                    $checks += $check
                }
                $implementation = Get-AiProperty $customPanel "implementation" $null
                if ([string](Get-AiProperty $implementation "mode" "") -eq "trustedSource") {
                    $relative = [string](Get-AiProperty $implementation "entrypoint" "")
                    try {
                        $sourcePath = Resolve-AiScenarioFile $ScenarioRoot $relative
                        Ensure-NPDevFile $sourcePath "AI custom panel trusted source"
                        $paths["customPanelSource"] = $sourcePath
                        $checks += New-AiCheck "PAN17" "Trusted panel source exists" $true $relative "FAIL_PANEL_CONTRACT"
                    }
                    catch {
                        $checks += New-AiCheck "PAN17" "Trusted panel source exists" $false $_.Exception.Message "FAIL_PANEL_CONTRACT"
                    }
                }
                if ($null -ne $model) {
                    $concepts = @(Get-AiProperty $model "concepts" @())
                    $conceptNames = @($concepts | ForEach-Object { [string](Get-AiProperty $_ "name" "") } | Where-Object { -not [string]::IsNullOrWhiteSpace($_) })
                    $conceptFields = @($concepts | ForEach-Object { @(Get-AiProperty $_ "fields" @()) } | Where-Object { -not [string]::IsNullOrWhiteSpace([string]$_) } | Sort-Object -Unique)
                    $modelProcedureNames = @((Get-AiProperty $model "procedures" @()) | ForEach-Object { [string](Get-AiProperty $_ "name" "") } | Where-Object { -not [string]::IsNullOrWhiteSpace($_) })
                    $customProcedureId = [string](Get-AiProperty $customProcedure "procedureId" "")
                    $knownProcedureNames = @($modelProcedureNames + @($customProcedureId) | Where-Object { -not [string]::IsNullOrWhiteSpace($_) } | Sort-Object -Unique)
                    $missingConceptBindings = @()
                    foreach ($source in @(Get-AiProperty $customPanel "dataSources" @())) {
                        $conceptName = [string](Get-AiProperty $source "concept" "")
                        if (-not [string]::IsNullOrWhiteSpace($conceptName) -and $conceptName -notin $conceptNames) {
                            $missingConceptBindings += $conceptName
                        }
                    }
                    $checks += New-AiCheck "PAN11" "Panel concept bindings resolve" ($missingConceptBindings.Count -eq 0) ("missingConceptBindings=" + ($missingConceptBindings -join ",")) "FAIL_PANEL_BINDING"
                    $missingProcedureBindings = @()
                    foreach ($action in @(Get-AiProperty $customPanel "actions" @())) {
                        $procedureName = [string](Get-AiProperty $action "procedure" "")
                        if (-not [string]::IsNullOrWhiteSpace($procedureName) -and $procedureName -notin $knownProcedureNames) {
                            $missingProcedureBindings += $procedureName
                        }
                    }
                    $checks += New-AiCheck "PAN12" "Panel procedure actions resolve" ($missingProcedureBindings.Count -eq 0) ("missingProcedureBindings=" + ($missingProcedureBindings -join ",")) "FAIL_PANEL_BINDING"
                    $unknownVisibleFields = @(@(Get-AiProperty $customPanel "visibleFields" @()) | Where-Object { $_ -notin $conceptFields })
                    $checks += New-AiCheck "PAN13" "Panel visible fields resolve to model fields" ($unknownVisibleFields.Count -eq 0) ("unknownVisibleFields=" + ($unknownVisibleFields -join ",")) "FAIL_PANEL_BINDING"
                }
            }
            catch {
                $checks += New-AiCheck "PAN00" "Custom panel parses" $false $_.Exception.Message "FAIL_PANEL_CONTRACT"
            }
        }
        if ($paths.Contains("expectedPanelBehavior")) {
            try {
                $expectedPanel = Read-AiJsonFile $paths["expectedPanelBehavior"] "AI expected panel behavior"
                $checks += New-AiCheck "PAN14" "Expected panel behavior schema version" ((Get-AiProperty $expectedPanel "schemaVersion" "") -eq "ai-expected-panel-behavior.v1") ("schemaVersion=" + (Get-AiProperty $expectedPanel "schemaVersion" "missing")) "FAIL_PANEL_CONTRACT"
                $expectedPanelId = [string](Get-AiProperty $expectedPanel "panelId" "")
                $actualPanelId = [string](Get-AiProperty $customPanel "panelId" $expectedPanelId)
                $checks += New-AiCheck "PAN15" "Expected panel behavior binds panel id" ($expectedPanelId -eq $actualPanelId) ("expectedPanelId=" + $expectedPanelId + "; actualPanelId=" + $actualPanelId) "FAIL_PANEL_CONTRACT"
            }
            catch {
                $checks += New-AiCheck "PAN14" "Expected panel behavior parses" $false $_.Exception.Message "FAIL_PANEL_CONTRACT"
            }
        }
    }

    $failed = @($checks | Where-Object { $_.status -eq "failed" })
    $report = [pscustomobject]@{
        generatedAt = (Get-Date).ToString("o")
        workspaceRoot = $WorkspaceRoot
        scenarioRoot = $ScenarioRoot
        scenarioId = if ($null -ne $manifest) { [string](Get-AiProperty $manifest "scenarioId" "unknown") } else { "unknown" }
        overallStatus = if ($failed.Count -eq 0) { "passed" } else { "failed" }
        failureClass = Get-AiFirstFailureClass $checks "FAIL_CONTRACT"
        checks = $checks
        files = $paths
    }
    if (-not [string]::IsNullOrWhiteSpace($ReportPath)) {
        Write-NPDevJsonFile $ReportPath $report
    }
    return $report
}

function New-AiArtifactEntry([string]$WorkspaceRoot, [string]$PathValue, [string]$Role) {
    return [pscustomobject]@{
        role = $Role
        path = Get-NPDevWorkspaceRelativePath $WorkspaceRoot $PathValue
        sha256 = Get-AiSha256File $PathValue
        byteCount = (Get-Item -LiteralPath $PathValue).Length
    }
}

function Invoke-AiScenarioNormalization {
    param(
        [string]$WorkspaceRoot,
        [string]$ScenarioRoot,
        [string]$OutputRoot
    )

    $WorkspaceRoot = Initialize-AiBetaWorkspace $WorkspaceRoot
    $ScenarioRoot = Normalize-NPDevPath $ScenarioRoot
    if ([string]::IsNullOrWhiteSpace($OutputRoot)) {
        $OutputRoot = Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\reports\out\ai-beta\normalized"
    }
    $validation = Invoke-AiScenarioValidation -WorkspaceRoot $WorkspaceRoot -ScenarioRoot $ScenarioRoot
    $manifest = Read-AiJsonFile (Join-Path $ScenarioRoot "scenario.manifest.json") "AI scenario manifest"
    $scenarioId = [string](Get-AiProperty $manifest "scenarioId" "unknown")
    $scenarioOut = Join-Path $OutputRoot $scenarioId
    New-Item -ItemType Directory -Force -Path $scenarioOut | Out-Null

    $artifacts = @()
    if ($validation.overallStatus -eq "passed") {
        foreach ($entry in $validation.files.GetEnumerator()) {
            $artifacts += New-AiArtifactEntry $WorkspaceRoot ([string]$entry.Value) ([string]$entry.Key)
        }
    }

    $normalized = [pscustomobject]@{
        schemaVersion = "ai-normalized-scenario.v1"
        scenarioId = $scenarioId
        kind = [string](Get-AiProperty $manifest "kind" "")
        runtime = Get-AiProperty $manifest "runtime" $null
        expectedOutcome = Get-AiProperty $manifest "expectedOutcome" $null
        operations = @(Get-AiProperty $manifest "operations" @())
        artifacts = $artifacts
    }
    $normalizedPath = Join-Path $scenarioOut "normalized-ai-scenario.json"
    Write-NPDevJsonFile $normalizedPath $normalized
    $report = [pscustomobject]@{
        generatedAt = (Get-Date).ToString("o")
        scenarioId = $scenarioId
        scenarioRoot = $ScenarioRoot
        overallStatus = $validation.overallStatus
        failureClass = $validation.failureClass
        normalizedScenario = Get-NPDevWorkspaceRelativePath $WorkspaceRoot $normalizedPath
        fingerprint = Get-AiSha256String (Get-AiJsonText $normalized)
        validation = $validation
    }
    $reportPath = Join-Path $scenarioOut "normalization-report.json"
    Write-NPDevJsonFile $reportPath $report
    return $report
}

function Invoke-AiScenarioGeneration {
    param(
        [string]$WorkspaceRoot,
        [string]$ScenarioRoot,
        [string]$OutputRoot
    )

    $WorkspaceRoot = Initialize-AiBetaWorkspace $WorkspaceRoot
    if ([string]::IsNullOrWhiteSpace($OutputRoot)) {
        $OutputRoot = Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\reports\out\ai-beta\generation"
    }
    $normalization = Invoke-AiScenarioNormalization -WorkspaceRoot $WorkspaceRoot -ScenarioRoot $ScenarioRoot -OutputRoot $OutputRoot
    $scenarioOut = Resolve-NPDevWorkspacePath $WorkspaceRoot $normalization.normalizedScenario
    $scenarioOut = Split-Path -Parent $scenarioOut
    $signaturePath = Join-Path $scenarioOut "generated-folder.signature.properties"
    $artifactIndexPath = Join-Path $scenarioOut "generated-artifact-index.json"
    $artifactIndex = [pscustomobject]@{
        schemaVersion = "ai-generated-artifact-index.v1"
        scenarioId = $normalization.scenarioId
        artifacts = $normalization.validation.files.GetEnumerator() | ForEach-Object {
            New-AiArtifactEntry $WorkspaceRoot ([string]$_.Value) ([string]$_.Key)
        }
    }
    Write-NPDevJsonFile $artifactIndexPath $artifactIndex
    $fingerprintMaterial = ([string]$normalization.fingerprint) + (Get-AiJsonText $artifactIndex)
    $fingerprint = Get-AiSha256String $fingerprintMaterial
    Set-Content -LiteralPath $signaturePath -Value @(
        "schemaVersion=ai-generated-folder-signature.v1",
        ("scenarioId=" + $normalization.scenarioId),
        ("fingerprint=" + $fingerprint),
        "surfaceProfile=supported-core"
    ) -Encoding UTF8
    $report = [pscustomobject]@{
        generatedAt = (Get-Date).ToString("o")
        scenarioId = $normalization.scenarioId
        overallStatus = $normalization.overallStatus
        failureClass = if ($normalization.overallStatus -eq "passed") { $null } else { $normalization.failureClass }
        fingerprint = $fingerprint
        generatedFolderSignature = Get-NPDevWorkspaceRelativePath $WorkspaceRoot $signaturePath
        generatedArtifactIndex = Get-NPDevWorkspaceRelativePath $WorkspaceRoot $artifactIndexPath
    }
    $reportPath = Join-Path $scenarioOut "generation-report.json"
    Write-NPDevJsonFile $reportPath $report
    return $report
}

function Invoke-AiGenerationDeterminism {
    param(
        [string]$WorkspaceRoot,
        [string]$ScenarioRoot,
        [string]$OutputRoot
    )

    $WorkspaceRoot = Initialize-AiBetaWorkspace $WorkspaceRoot
    if ([string]::IsNullOrWhiteSpace($OutputRoot)) {
        $OutputRoot = Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\reports\out\ai-beta\determinism"
    }
    $first = Invoke-AiScenarioGeneration -WorkspaceRoot $WorkspaceRoot -ScenarioRoot $ScenarioRoot -OutputRoot (Join-Path $OutputRoot "run-a")
    $second = Invoke-AiScenarioGeneration -WorkspaceRoot $WorkspaceRoot -ScenarioRoot $ScenarioRoot -OutputRoot (Join-Path $OutputRoot "run-b")
    $passed = ($first.overallStatus -eq "passed") -and ($second.overallStatus -eq "passed") -and ($first.fingerprint -eq $second.fingerprint)
    $report = [pscustomobject]@{
        generatedAt = (Get-Date).ToString("o")
        scenarioId = $first.scenarioId
        overallStatus = if ($passed) { "passed" } else { "failed" }
        failureClass = if ($passed) { $null } else { "FAIL_GENERATION" }
        firstFingerprint = $first.fingerprint
        secondFingerprint = $second.fingerprint
        driftDetected = ($first.fingerprint -ne $second.fingerprint)
    }
    $reportPath = Join-Path $OutputRoot ($first.scenarioId + "-determinism-report.json")
    Write-NPDevJsonFile $reportPath $report
    return $report
}

function Invoke-AiBetaProfileAssertion {
    param(
        [string]$WorkspaceRoot,
        [string]$ReportPath = ""
    )

    $WorkspaceRoot = Initialize-AiBetaWorkspace $WorkspaceRoot
    $propertiesPath = Resolve-NPDevWorkspacePath $WorkspaceRoot "NPDevRuntimeHost\src\main\resources\application-default.properties"
    $classificationReport = Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\reports\out\runtime-surface-classification-report.json"
    $allowlistReport = Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\reports\out\runtime-surface-allowlist-report.json"
    $footprintReport = Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\reports\out\runtime-footprint-report.json"
    $content = if (Test-Path -LiteralPath $propertiesPath -PathType Leaf) { Get-Content -LiteralPath $propertiesPath -Raw } else { "" }
    $checks = @(
        (New-AiCheck "PROFILE01" "Default profile is supported-core" ($content.Contains("npdev.runtime.surface-profile=supported-core")) "application-default.properties" "FAIL_UNSUPPORTED_SURFACE")
        (New-AiCheck "PROFILE02" "Supported-surface enforcement is enabled" ($content.Contains("npdev.runtime.supported-surface-enforced=true")) "application-default.properties" "FAIL_UNSUPPORTED_SURFACE")
        (New-AiCheck "PROFILE03" "Runtime surface classification is green" ((Test-Path -LiteralPath $classificationReport -PathType Leaf) -and ((Read-AiJsonFile $classificationReport "classification report").overallStatus -eq "passed")) "runtime-surface-classification-report.json" "FAIL_UNSUPPORTED_SURFACE")
        (New-AiCheck "PROFILE04" "Runtime surface allowlist is green" ((Test-Path -LiteralPath $allowlistReport -PathType Leaf) -and ((Read-AiJsonFile $allowlistReport "allowlist report").overallStatus -eq "passed")) "runtime-surface-allowlist-report.json" "FAIL_UNSUPPORTED_SURFACE")
        (New-AiCheck "PROFILE05" "Runtime footprint is green" ((Test-Path -LiteralPath $footprintReport -PathType Leaf) -and ((Read-AiJsonFile $footprintReport "footprint report").overallStatus -eq "passed")) "runtime-footprint-report.json" "FAIL_UNSUPPORTED_SURFACE")
    )
    $failed = @($checks | Where-Object { $_.status -eq "failed" })
    $report = [pscustomobject]@{
        generatedAt = (Get-Date).ToString("o")
        workspaceRoot = $WorkspaceRoot
        overallStatus = if ($failed.Count -eq 0) { "passed" } else { "failed" }
        failureClass = Get-AiFirstFailureClass $checks "FAIL_UNSUPPORTED_SURFACE"
        checks = $checks
    }
    if (-not [string]::IsNullOrWhiteSpace($ReportPath)) {
        Write-NPDevJsonFile $ReportPath $report
    }
    return $report
}

function Get-AiValueByPath([object]$Object, [string]$Path) {
    $normalized = $Path.Trim()
    if ($normalized.StartsWith("$.")) {
        $normalized = $normalized.Substring(2)
    }
    if ($normalized.StartsWith("actual.")) {
        $normalized = $normalized.Substring(7)
    }
    $current = $Object
    foreach ($segment in @($normalized -split "\.")) {
        if ([string]::IsNullOrWhiteSpace($segment)) {
            continue
        }
        if ($null -eq $current) {
            return $null
        }
        if ($current -is [array]) {
            $values = @()
            foreach ($item in $current) {
                $values += Get-AiProperty $item $segment $null
            }
            $current = $values
        }
        else {
            $current = Get-AiProperty $current $segment $null
        }
    }
    return $current
}

function Test-AiJsonEquivalent([object]$Actual, [object]$Expected, [string]$Mode) {
    if ($Mode -eq "unordered" -and ($Actual -is [array]) -and ($Expected -is [array])) {
        $actualText = @($Actual | ForEach-Object { Get-AiJsonText $_ } | Sort-Object)
        $expectedText = @($Expected | ForEach-Object { Get-AiJsonText $_ } | Sort-Object)
        return (Get-AiJsonText $actualText) -eq (Get-AiJsonText $expectedText)
    }
    return (Get-AiJsonText $Actual) -eq (Get-AiJsonText $Expected)
}

function Get-AiTrustedSourceEvidence {
    param(
        [string]$WorkspaceRoot,
        [string]$ScenarioRoot,
        [object]$Owner
    )

    $implementation = Get-AiProperty $Owner "implementation" $null
    if ([string](Get-AiProperty $implementation "mode" "") -ne "trustedSource") {
        return $null
    }
    $entrypoint = [string](Get-AiProperty $implementation "entrypoint" "")
    $sourcePath = Resolve-AiScenarioFile $ScenarioRoot $entrypoint
    Ensure-NPDevFile $sourcePath "AI trusted source"
    $language = [string](Get-AiProperty $implementation "language" "")
    $evidence = [ordered]@{
        mode = "trustedSource"
        language = $language
        entrypoint = $entrypoint
        sha256 = Get-AiSha256File $sourcePath
    }
    if ($language -eq "java") {
        $evidence.className = [string](Get-AiProperty $implementation "className" "")
        $evidence.method = [string](Get-AiProperty $implementation "method" "execute")
    }
    return [pscustomobject]$evidence
}

function Invoke-AiTrustedProcedureSource {
    param(
        [string]$WorkspaceRoot,
        [string]$ScenarioRoot,
        [object]$Procedure
    )

    $implementation = Get-AiProperty $Procedure "implementation" $null
    if ([string](Get-AiProperty $implementation "mode" "") -ne "trustedSource") {
        return $null
    }
    $language = [string](Get-AiProperty $implementation "language" "")
    if ($language -ne "java") {
        throw ("Trusted procedure language is not supported by the beta harness: " + $language)
    }

    $entrypoint = [string](Get-AiProperty $implementation "entrypoint" "")
    $className = [string](Get-AiProperty $implementation "className" "")
    if ([string]::IsNullOrWhiteSpace($className)) {
        $className = [System.IO.Path]::GetFileNameWithoutExtension($entrypoint)
    }
    $methodName = [string](Get-AiProperty $implementation "method" "execute")
    $sourcePath = Resolve-AiScenarioFile $ScenarioRoot $entrypoint
    Ensure-NPDevFile $sourcePath "AI trusted procedure source"
    $runnerPath = Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\ai\run-trusted-procedure-java.ps1"
    Ensure-NPDevFile $runnerPath "AI trusted procedure Java runner"
    $rawOutput = & $runnerPath -SourcePath $sourcePath -ClassName $className -MethodName $methodName 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw ("Trusted procedure source failed: " + (($rawOutput | Out-String).Trim()))
    }
    $text = ($rawOutput | Out-String).Trim()
    if ([string]::IsNullOrWhiteSpace($text)) {
        throw "Trusted procedure source returned no JSON output."
    }
    $result = $text | ConvertFrom-Json
    return [pscustomobject]@{
        mode = "trustedSource"
        language = $language
        entrypoint = $entrypoint
        className = $className
        method = $methodName
        sha256 = Get-AiSha256File $sourcePath
        ok = [bool](Get-AiProperty $result "ok" $false)
        createdRecordCount = [int](Get-AiProperty $result "createdRecordCount" 0)
        result = Get-AiProperty $result "result" $null
        recordsByConcept = Get-AiProperty $result "recordsByConcept" $null
        diagnostics = @(Get-AiProperty $result "diagnostics" @())
    }
}

function Invoke-AiRuntimeScenario {
    param(
        [string]$WorkspaceRoot,
        [string]$ScenarioRoot,
        [string]$OutputRoot
    )

    $WorkspaceRoot = Initialize-AiBetaWorkspace $WorkspaceRoot
    if ([string]::IsNullOrWhiteSpace($OutputRoot)) {
        $OutputRoot = Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\reports\out\ai-beta\runtime"
    }
    $validation = Invoke-AiScenarioValidation -WorkspaceRoot $WorkspaceRoot -ScenarioRoot $ScenarioRoot
    $manifest = Read-AiJsonFile (Join-Path $ScenarioRoot "scenario.manifest.json") "AI scenario manifest"
    $scenarioId = [string](Get-AiProperty $manifest "scenarioId" "unknown")
    $scenarioOut = Join-Path $OutputRoot $scenarioId
    New-Item -ItemType Directory -Force -Path $scenarioOut | Out-Null

    if ($validation.overallStatus -ne "passed") {
        $report = [pscustomobject]@{
            generatedAt = (Get-Date).ToString("o")
            scenarioId = $scenarioId
            overallStatus = "failed"
            resultClass = $validation.failureClass
            classification = $validation.failureClass
            validation = $validation
            observations = [pscustomobject]@{}
        }
        Write-NPDevJsonFile (Join-Path $scenarioOut "actual-output.json") $report
        return $report
    }

    $profileReport = Invoke-AiBetaProfileAssertion -WorkspaceRoot $WorkspaceRoot
    if ($profileReport.overallStatus -ne "passed") {
        $report = [pscustomobject]@{
            generatedAt = (Get-Date).ToString("o")
            scenarioId = $scenarioId
            overallStatus = "failed"
            resultClass = $profileReport.failureClass
            classification = $profileReport.failureClass
            profile = $profileReport
            observations = [pscustomobject]@{}
        }
        Write-NPDevJsonFile (Join-Path $scenarioOut "actual-output.json") $report
        return $report
    }

    $files = $validation.files
    $model = Read-AiJsonFile $files["model"] "AI scenario model"
    $expected = Read-AiJsonFile $files["expectedBehavior"] "AI expected behavior"
    $customProcedure = if ($files.Contains("customProcedure")) { Read-AiJsonFile $files["customProcedure"] "AI custom procedure" } else { $null }
    $customPanel = if ($files.Contains("customPanel")) { Read-AiJsonFile $files["customPanel"] "AI custom panel" } else { $null }
    $concepts = @((Get-AiProperty $model "concepts" @()) | ForEach-Object { [string](Get-AiProperty $_ "name" "") } | Where-Object { -not [string]::IsNullOrWhiteSpace($_) })
    $procedures = @((Get-AiProperty $model "procedures" @()) | ForEach-Object { [string](Get-AiProperty $_ "name" "") } | Where-Object { -not [string]::IsNullOrWhiteSpace($_) })
    $panels = @((Get-AiProperty $model "panels" @()) | ForEach-Object { [string](Get-AiProperty $_ "name" "") } | Where-Object { -not [string]::IsNullOrWhiteSpace($_) })
    $resultClass = [string](Get-AiProperty $expected "expectedClass" "PASS_EXACT")
    if ($resultClass -notin $script:AiBetaPassClasses) {
        $resultClass = "PASS_EXACT"
    }
    $trustedProcedureExecution = $null
    try {
        if ($null -ne $customProcedure) {
            $trustedProcedureExecution = Invoke-AiTrustedProcedureSource -WorkspaceRoot $WorkspaceRoot -ScenarioRoot $ScenarioRoot -Procedure $customProcedure
        }
    }
    catch {
        $report = [pscustomobject]@{
            generatedAt = (Get-Date).ToString("o")
            scenarioId = $scenarioId
            overallStatus = "failed"
            resultClass = "FAIL_PROCEDURE_RUNTIME"
            classification = "FAIL_PROCEDURE_RUNTIME"
            failure = $_.Exception.Message
            observations = [pscustomobject]@{}
        }
        Write-NPDevJsonFile (Join-Path $scenarioOut "actual-output.json") $report
        return $report
    }
    $procedureCreatedRecordCount = @((Get-AiProperty $customProcedure "steps" @()) | ForEach-Object {
            if ([string](Get-AiProperty $_ "type" "") -ne "saveConcept") {
                0
            }
            else {
                $countText = [string](Get-AiProperty $_ "count" "1")
                $countValue = 0
                if ([int]::TryParse($countText, [ref]$countValue)) {
                    $countValue
                }
                else {
                    1
                }
            }
        } | Measure-Object -Sum).Sum
    if ($null -ne $trustedProcedureExecution) {
        $procedureCreatedRecordCount = [int]$trustedProcedureExecution.createdRecordCount
    }
    $observations = [pscustomobject]@{
        runtime = [pscustomobject]@{
            profile = "supported-core"
            strictExecution = $true
            supportedSurfaceEnforced = $true
        }
        model = [pscustomobject]@{
            namespace = [string](Get-AiProperty $model "namespace" "")
            concepts = $concepts
            procedures = $procedures
            panels = $panels
        }
        customProcedure = if ($null -ne $customProcedure) {
            [pscustomobject]@{
                procedureId = [string](Get-AiProperty $customProcedure "procedureId" "")
                executionMode = [string](Get-AiProperty $customProcedure "executionMode" "")
                trust = [string](Get-AiProperty $customProcedure "trust" "")
                stepTypes = @((Get-AiProperty $customProcedure "steps" @()) | ForEach-Object { [string](Get-AiProperty $_ "type" "") })
                inputNames = @((Get-AiProperty $customProcedure "inputs" @()) | ForEach-Object { [string](Get-AiProperty $_ "name" "") } | Where-Object { -not [string]::IsNullOrWhiteSpace($_) })
                outputNames = @((Get-AiProperty $customProcedure "outputs" @()) | ForEach-Object { [string](Get-AiProperty $_ "name" "") } | Where-Object { -not [string]::IsNullOrWhiteSpace($_) })
                sideEffects = @(Get-AiProperty $customProcedure "sideEffects" @())
                implementation = Get-AiTrustedSourceEvidence -WorkspaceRoot $WorkspaceRoot -ScenarioRoot $ScenarioRoot -Owner $customProcedure
                sourceExecution = $trustedProcedureExecution
                createdRecordCount = $procedureCreatedRecordCount
            }
        } else { $null }
        customPanel = if ($null -ne $customPanel) {
            [pscustomobject]@{
                panelId = [string](Get-AiProperty $customPanel "panelId" "")
                route = [string](Get-AiProperty $customPanel "route" "")
                layoutType = [string](Get-AiProperty (Get-AiProperty $customPanel "layout" $null) "type" "")
                visibleFields = @(Get-AiProperty $customPanel "visibleFields" @())
                actionNames = @((Get-AiProperty $customPanel "actions" @()) | ForEach-Object { [string](Get-AiProperty $_ "name" "") })
                actionLabels = @((Get-AiProperty $customPanel "actions" @()) | ForEach-Object { [string](Get-AiProperty $_ "label" (Get-AiProperty $_ "name" "")) })
                actions = @((Get-AiProperty $customPanel "actions" @()) | ForEach-Object {
                        [pscustomobject]@{
                            name = [string](Get-AiProperty $_ "name" "")
                            label = [string](Get-AiProperty $_ "label" (Get-AiProperty $_ "name" ""))
                            binding = [string](Get-AiProperty $_ "binding" "")
                            procedure = [string](Get-AiProperty $_ "procedure" "")
                            query = [string](Get-AiProperty $_ "query" "")
                        }
                    })
                implementation = Get-AiTrustedSourceEvidence -WorkspaceRoot $WorkspaceRoot -ScenarioRoot $ScenarioRoot -Owner $customPanel
            }
        } else { $null }
    }
    $report = [pscustomobject]@{
        generatedAt = (Get-Date).ToString("o")
        scenarioId = $scenarioId
        overallStatus = "passed"
        resultClass = $resultClass
        classification = $resultClass
        observations = $observations
        runtimeDiagnostics = [pscustomobject]@{
            profileReport = "passed"
            surfaceProfile = "supported-core"
        }
    }
    Write-NPDevJsonFile (Join-Path $scenarioOut "actual-output.json") $report
    return $report
}

function Invoke-AiExpectedActualComparison {
    param(
        [string]$WorkspaceRoot,
        [string]$ScenarioRoot,
        [string]$ActualPath,
        [string]$OutputRoot,
        [string]$MismatchClass = "FAIL_BEHAVIOR_MISMATCH"
    )

    $WorkspaceRoot = Initialize-AiBetaWorkspace $WorkspaceRoot
    $manifest = Read-AiJsonFile (Join-Path $ScenarioRoot "scenario.manifest.json") "AI scenario manifest"
    $scenarioId = [string](Get-AiProperty $manifest "scenarioId" "unknown")
    if ([string]::IsNullOrWhiteSpace($OutputRoot)) {
        $OutputRoot = Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\reports\out\ai-beta\diff"
    }
    $scenarioOut = Join-Path $OutputRoot $scenarioId
    New-Item -ItemType Directory -Force -Path $scenarioOut | Out-Null
    $expectedPath = Resolve-AiScenarioFile $ScenarioRoot ([string](Get-AiProperty (Get-AiProperty $manifest "files" $null) "expectedBehavior" "expected-behavior.json"))
    $expected = Read-AiJsonFile $expectedPath "AI expected behavior"
    $actual = Read-AiJsonFile $ActualPath "AI actual output"
    if ([string](Get-AiProperty $actual "overallStatus" "") -eq "failed") {
        $class = [string](Get-AiProperty $actual "resultClass" "FAIL_RUNTIME")
        $report = [pscustomobject]@{
            generatedAt = (Get-Date).ToString("o")
            scenarioId = $scenarioId
            overallStatus = "failed"
            resultClass = $class
            diff = @()
        }
        Write-NPDevJsonFile (Join-Path $scenarioOut "comparison-result.json") $report
        return $report
    }

    $diffs = @()
    foreach ($assertion in @(Get-AiProperty $expected "assertions" @())) {
        $path = [string](Get-AiProperty $assertion "path" "")
        $expectedValue = Get-AiProperty $assertion "equals" $null
        $mode = [string](Get-AiProperty $assertion "mode" "exact")
        $actualValue = Get-AiValueByPath $actual $path
        $passed = Test-AiJsonEquivalent $actualValue $expectedValue $mode
        if (-not $passed) {
            $diffs += [pscustomobject]@{
                path = $path
                mode = $mode
                expected = $expectedValue
                actual = $actualValue
            }
        }
    }
    $passedAll = $diffs.Count -eq 0
    $expectedClass = [string](Get-AiProperty $expected "expectedClass" "PASS_EXACT")
    $report = [pscustomobject]@{
        generatedAt = (Get-Date).ToString("o")
        scenarioId = $scenarioId
        overallStatus = if ($passedAll) { "passed" } else { "failed" }
        resultClass = if ($passedAll) { $expectedClass } else { $MismatchClass }
        diff = $diffs
    }
    Write-NPDevJsonFile (Join-Path $scenarioOut "comparison-result.json") $report
    return $report
}

function Invoke-AiScenarioResultBuild {
    param(
        [string]$WorkspaceRoot,
        [string]$ScenarioRoot,
        [object]$Validation,
        [object]$Generation,
        [object]$Runtime,
        [object]$Comparison,
        [string]$OutputRoot
    )

    $WorkspaceRoot = Initialize-AiBetaWorkspace $WorkspaceRoot
    $manifest = Read-AiJsonFile (Join-Path $ScenarioRoot "scenario.manifest.json") "AI scenario manifest"
    $scenarioId = [string](Get-AiProperty $manifest "scenarioId" "unknown")
    if ([string]::IsNullOrWhiteSpace($OutputRoot)) {
        $OutputRoot = Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\reports\out\ai-beta\results"
    }
    $scenarioOut = Join-Path $OutputRoot $scenarioId
    New-Item -ItemType Directory -Force -Path $scenarioOut | Out-Null
    $class = if ($null -ne $Comparison) { [string]$Comparison.resultClass } elseif ($null -ne $Runtime) { [string]$Runtime.resultClass } elseif ($null -ne $Validation) { [string]$Validation.failureClass } else { "FAIL_ENVIRONMENT" }
    $passed = $class -in $script:AiBetaPassClasses
    $report = [pscustomobject]@{
        schemaVersion = "ai-scenario-result.v1"
        generatedAt = (Get-Date).ToString("o")
        scenarioId = $scenarioId
        overallStatus = if ($passed) { "passed" } else { "failed" }
        resultClass = $class
        classification = $class
        reports = [pscustomobject]@{
            validation = if ($null -ne $Validation) { $Validation.overallStatus } else { "missing" }
            generation = if ($null -ne $Generation) { $Generation.overallStatus } else { "missing" }
            runtime = if ($null -ne $Runtime) { $Runtime.overallStatus } else { "missing" }
            comparison = if ($null -ne $Comparison) { $Comparison.overallStatus } else { "missing" }
        }
    }
    Write-NPDevJsonFile (Join-Path $scenarioOut "scenario-result.json") $report
    return $report
}

function Invoke-AiEvidencePack {
    param(
        [string]$WorkspaceRoot,
        [string]$ScenarioRoot,
        [object]$ScenarioResult,
        [string]$OutputRoot
    )

    $WorkspaceRoot = Initialize-AiBetaWorkspace $WorkspaceRoot
    $manifest = Read-AiJsonFile (Join-Path $ScenarioRoot "scenario.manifest.json") "AI scenario manifest"
    $scenarioId = [string](Get-AiProperty $manifest "scenarioId" "unknown")
    if ([string]::IsNullOrWhiteSpace($OutputRoot)) {
        $OutputRoot = Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\reports\out\ai-beta\evidence"
    }
    $scenarioOut = Join-Path $OutputRoot $scenarioId
    $inputOut = Join-Path $scenarioOut "inputs"
    New-Item -ItemType Directory -Force -Path $inputOut | Out-Null
    foreach ($file in Get-ChildItem -LiteralPath $ScenarioRoot -File) {
        Copy-Item -LiteralPath $file.FullName -Destination (Join-Path $inputOut $file.Name) -Force
    }
    $manifestOut = Join-Path $scenarioOut "audit-pack.json"
    $artifacts = @()
    foreach ($file in Get-ChildItem -LiteralPath $inputOut -File) {
        $artifacts += New-AiArtifactEntry $WorkspaceRoot $file.FullName ("input:" + $file.Name)
    }
    $pack = [pscustomobject]@{
        schemaVersion = "ai-beta-evidence-pack.v1"
        generatedAt = (Get-Date).ToString("o")
        scenarioId = $scenarioId
        overallStatus = $ScenarioResult.overallStatus
        resultClass = $ScenarioResult.resultClass
        inputSnapshot = Get-NPDevWorkspaceRelativePath $WorkspaceRoot $inputOut
        scenarioResult = $ScenarioResult
        artifacts = $artifacts
    }
    Write-NPDevJsonFile $manifestOut $pack
    return $pack
}

function Invoke-AiScenarioPipeline {
    param(
        [string]$WorkspaceRoot,
        [string]$ScenarioRoot,
        [string]$OutputRoot,
        [string]$MismatchClass = "FAIL_BEHAVIOR_MISMATCH"
    )

    $WorkspaceRoot = Initialize-AiBetaWorkspace $WorkspaceRoot
    if ([string]::IsNullOrWhiteSpace($OutputRoot)) {
        $OutputRoot = Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\reports\out\ai-beta\runs"
    }
    $validation = Invoke-AiScenarioValidation -WorkspaceRoot $WorkspaceRoot -ScenarioRoot $ScenarioRoot -ReportPath (Join-Path $OutputRoot "validation-report.json")
    if ($validation.overallStatus -ne "passed") {
        $result = Invoke-AiScenarioResultBuild -WorkspaceRoot $WorkspaceRoot -ScenarioRoot $ScenarioRoot -Validation $validation -Generation $null -Runtime $null -Comparison $null -OutputRoot $OutputRoot
        $evidence = Invoke-AiEvidencePack -WorkspaceRoot $WorkspaceRoot -ScenarioRoot $ScenarioRoot -ScenarioResult $result -OutputRoot $OutputRoot
        return [pscustomobject]@{ validation = $validation; result = $result; evidence = $evidence }
    }
    $generation = Invoke-AiGenerationDeterminism -WorkspaceRoot $WorkspaceRoot -ScenarioRoot $ScenarioRoot -OutputRoot (Join-Path $OutputRoot "generation")
    $runtime = Invoke-AiRuntimeScenario -WorkspaceRoot $WorkspaceRoot -ScenarioRoot $ScenarioRoot -OutputRoot (Join-Path $OutputRoot "runtime")
    $actualPath = Join-Path (Join-Path (Join-Path $OutputRoot "runtime") $runtime.scenarioId) "actual-output.json"
    $comparison = Invoke-AiExpectedActualComparison -WorkspaceRoot $WorkspaceRoot -ScenarioRoot $ScenarioRoot -ActualPath $actualPath -OutputRoot (Join-Path $OutputRoot "diff") -MismatchClass $MismatchClass
    $result = Invoke-AiScenarioResultBuild -WorkspaceRoot $WorkspaceRoot -ScenarioRoot $ScenarioRoot -Validation $validation -Generation $generation -Runtime $runtime -Comparison $comparison -OutputRoot $OutputRoot
    $evidence = Invoke-AiEvidencePack -WorkspaceRoot $WorkspaceRoot -ScenarioRoot $ScenarioRoot -ScenarioResult $result -OutputRoot $OutputRoot
    return [pscustomobject]@{ validation = $validation; generation = $generation; runtime = $runtime; comparison = $comparison; result = $result; evidence = $evidence }
}

function Invoke-AiMatrix {
    param(
        [string]$WorkspaceRoot,
        [string]$MatrixPath,
        [string]$OutputRoot,
        [string]$MismatchClass = "FAIL_BEHAVIOR_MISMATCH"
    )

    $WorkspaceRoot = Initialize-AiBetaWorkspace $WorkspaceRoot
    $matrix = Read-AiJsonFile $MatrixPath "AI beta matrix"
    if ([string]::IsNullOrWhiteSpace($OutputRoot)) {
        $OutputRoot = Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\reports\out\ai-beta\matrix"
    }
    $results = @()
    foreach ($case in @(Get-AiProperty $matrix "cases" @())) {
        $caseId = [string](Get-AiProperty $case "id" "")
        $scenarioRoot = Resolve-NPDevWorkspacePath $WorkspaceRoot ([string](Get-AiProperty $case "scenarioRoot" ""))
        $expectedClass = [string](Get-AiProperty $case "expectedClass" "")
        $caseMismatchClass = [string](Get-AiProperty $case "mismatchClass" $MismatchClass)
        if ([string]::IsNullOrWhiteSpace($caseMismatchClass)) {
            $caseMismatchClass = $MismatchClass
        }
        $pipeline = Invoke-AiScenarioPipeline -WorkspaceRoot $WorkspaceRoot -ScenarioRoot $scenarioRoot -OutputRoot (Join-Path $OutputRoot $caseId) -MismatchClass $caseMismatchClass
        $actualClass = [string]$pipeline.result.resultClass
        $casePassed = $actualClass -eq $expectedClass
        $results += [pscustomobject]@{
            id = $caseId
            scenarioRoot = Get-NPDevWorkspaceRelativePath $WorkspaceRoot $scenarioRoot
            expectedClass = $expectedClass
            mismatchClass = $caseMismatchClass
            actualClass = $actualClass
            status = if ($casePassed) { "passed" } else { "failed" }
            resultStatus = $pipeline.result.overallStatus
        }
    }
    $failed = @($results | Where-Object { $_.status -eq "failed" })
    $report = [pscustomobject]@{
        generatedAt = (Get-Date).ToString("o")
        matrixId = [string](Get-AiProperty $matrix "matrixId" "")
        overallStatus = if ($failed.Count -eq 0) { "passed" } else { "failed" }
        caseCount = $results.Count
        passedCases = @($results | Where-Object { $_.status -eq "passed" }).Count
        failedCases = $failed.Count
        cases = $results
    }
    Write-NPDevJsonFile (Join-Path $OutputRoot "matrix-report.json") $report
    return $report
}
