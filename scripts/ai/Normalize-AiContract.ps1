param(
    [string]$ScenarioPath,
    [string]$AiModelPath,
    [string]$AiConfigPath,
    [string]$OutputDirectory,
    [string]$ResultPath
)

$ErrorActionPreference = "Stop"

. (Join-Path $PSScriptRoot "Test-AiShiftLeftSafety.ps1")

function Read-JsonFile {
    param([string]$Path)
    return Get-Content -Raw -LiteralPath $Path | ConvertFrom-Json
}

function Convert-ToSlug {
    param([string]$Value)
    $slug = $Value.ToLowerInvariant() -replace "[^a-z0-9]+", "-"
    $slug = $slug.Trim("-")
    if ([string]::IsNullOrWhiteSpace($slug)) {
        return "ai-beta-app"
    }
    return $slug
}

function Convert-ToDatabaseName {
    param([string]$Value)
    $name = $Value.ToLowerInvariant() -replace "[^a-z0-9]+", "_"
    $name = $name.Trim("_")
    if ([string]::IsNullOrWhiteSpace($name)) {
        return "npdev_ai_beta"
    }
    return $name
}

function Convert-FieldType {
    param([string]$AiType)
    switch ($AiType) {
        "email" { return "string" }
        "text" { return "string" }
        "integer" { return "integer" }
        default { return $AiType }
    }
}

function New-NormalizerFailure {
    param([string]$Code, [string]$Message)
    return [pscustomobject]@{
        code = $Code
        message = $Message
    }
}

function Assert-AiContractSupported {
    param([object]$Model, [object]$Config)
    $failures = @()
    if ($Model.schemaVersion -ne "ai-model.v1") {
        $failures += New-NormalizerFailure "AI_MODEL_SCHEMA_VERSION_UNSUPPORTED" "AI model schemaVersion must be ai-model.v1."
    }
    if ($Config.schemaVersion -ne "ai-generator-config.v1") {
        $failures += New-NormalizerFailure "AI_CONFIG_SCHEMA_VERSION_UNSUPPORTED" "AI config schemaVersion must be ai-generator-config.v1."
    }
    if ($Model.app.kind -notin @("simple-crud-workflow", "expanded-beta-application")) {
        $failures += New-NormalizerFailure "AI_MODEL_KIND_UNSUPPORTED" "Only simple-crud-workflow and expanded-beta-application are supported in AI-only Beta 0."
    }
    if ($Config.target.runtime -ne "spring-boot") {
        $failures += New-NormalizerFailure "AI_CONFIG_RUNTIME_UNSUPPORTED" "Only spring-boot runtime is supported in AI-only Beta 0."
    }
    if ($Config.target.profile -ne "ai-beta-local") {
        $failures += New-NormalizerFailure "AI_CONFIG_PROFILE_UNSUPPORTED" "Only ai-beta-local profile is supported in AI-only Beta 0."
    }
    if ($Config.output.directory -match "\.\." -or [System.IO.Path]::IsPathRooted([string]$Config.output.directory)) {
        $failures += New-NormalizerFailure "AI_CONFIG_OUTPUT_PATH_UNSAFE" "Output directory must be relative and must not contain path traversal."
    }
    if ($Model.app.kind -eq "expanded-beta-application") {
        foreach ($required in @("panels", "procedures", "workflows", "tenancy", "auth", "roles")) {
            if ($null -eq $Model.$required -or (@($Model.$required).Count -lt 1 -and $required -notin @("tenancy", "auth"))) {
                $failures += New-NormalizerFailure "EXPANDED_SURFACE_MISSING" ("Expanded Beta 0 model requires " + $required + ".")
            }
        }
        $entityNames = @($Model.entities | ForEach-Object { [string]$_.name })
        $roleIds = @($Model.roles | ForEach-Object { [string]$_.roleId })
        $procedureIds = @($Model.procedures | ForEach-Object { [string]$_.procedureId })
        $workflowIds = @($Model.workflows | ForEach-Object { [string]$_.workflowId })
        foreach ($panel in @($Model.panels)) {
            if ($roleIds -notcontains [string]$panel.requiredRole) {
                $failures += New-NormalizerFailure "PANEL_ROLE_UNRESOLVED" ("Panel role does not resolve: " + [string]$panel.panelId)
            }
            if ([string]$panel.dataSource.kind -eq "entity" -and $entityNames -notcontains [string]$panel.dataSource.name) {
                $failures += New-NormalizerFailure "PANEL_ENTITY_UNRESOLVED" ("Panel entity does not resolve: " + [string]$panel.panelId)
            }
            if ([string]$panel.dataSource.kind -eq "procedure" -and $procedureIds -notcontains [string]$panel.dataSource.name) {
                $failures += New-NormalizerFailure "PANEL_PROCEDURE_UNRESOLVED" ("Panel procedure does not resolve: " + [string]$panel.panelId)
            }
            if ([string]$panel.dataSource.kind -eq "workflow" -and $workflowIds -notcontains [string]$panel.dataSource.name) {
                $failures += New-NormalizerFailure "PANEL_WORKFLOW_UNRESOLVED" ("Panel workflow does not resolve: " + [string]$panel.panelId)
            }
        }
        foreach ($procedure in @($Model.procedures)) {
            if ($roleIds -notcontains [string]$procedure.requiredRole) {
                $failures += New-NormalizerFailure "PROCEDURE_ROLE_UNRESOLVED" ("Procedure role does not resolve: " + [string]$procedure.procedureId)
            }
            if ([string]$procedure.type -eq "bulk-command" -and [int]$procedure.maxAffectedRows -lt 1) {
                $failures += New-NormalizerFailure "PROCEDURE_BULK_LIMIT_MISSING" ("Bulk procedure requires maxAffectedRows > 0: " + [string]$procedure.procedureId)
            }
            foreach ($entity in @($procedure.allowedEntities)) {
                if ($entityNames -notcontains [string]$entity) {
                    $failures += New-NormalizerFailure "PROCEDURE_ENTITY_UNRESOLVED" ("Procedure entity does not resolve: " + [string]$procedure.procedureId)
                }
            }
        }
        foreach ($workflow in @($Model.workflows)) {
            if ($entityNames -notcontains [string]$workflow.entity) {
                $failures += New-NormalizerFailure "WORKFLOW_ENTITY_UNRESOLVED" ("Workflow entity does not resolve: " + [string]$workflow.workflowId)
            }
            $states = @($workflow.states | ForEach-Object { [string]$_ })
            if ($states -notcontains [string]$workflow.startState) {
                $failures += New-NormalizerFailure "WORKFLOW_START_STATE_UNRESOLVED" ("Workflow start state does not resolve: " + [string]$workflow.workflowId)
            }
            foreach ($terminalState in @($workflow.terminalStates)) {
                if ($states -notcontains [string]$terminalState) {
                    $failures += New-NormalizerFailure "WORKFLOW_TERMINAL_STATE_UNRESOLVED" ("Workflow terminal state does not resolve: " + [string]$workflow.workflowId)
                }
            }
            foreach ($transition in @($workflow.transitions)) {
                if ($states -notcontains [string]$transition.from -or $states -notcontains [string]$transition.to) {
                    $failures += New-NormalizerFailure "WORKFLOW_TRANSITION_STATE_UNRESOLVED" ("Workflow transition state does not resolve: " + [string]$workflow.workflowId)
                }
                if ($roleIds -notcontains [string]$transition.requiredRole) {
                    $failures += New-NormalizerFailure "WORKFLOW_TRANSITION_ROLE_UNRESOLVED" ("Workflow transition role does not resolve: " + [string]$workflow.workflowId)
                }
            }
        }
    }
    return $failures
}

function New-OfficialModel {
    param([object]$Model, [string]$ScenarioId)
    $concepts = @()
    $events = @()
    $flows = @()
    $procedures = @()
    $panels = @()
    $tenantIdField = if ($null -ne $Model.tenancy) { [string]$Model.tenancy.tenantIdField } else { "" }
    $modelWorkflows = if ($null -eq $Model.workflows) { @() } else { @($Model.workflows | Where-Object { $null -ne $_ }) }

    foreach ($entity in @($Model.entities)) {
        $fields = @(
            [ordered]@{
                name = "id"
                type = "uuid"
                id = $true
                required = $true
            }
        )
        if ([bool]$entity.tenantScoped -and -not [string]::IsNullOrWhiteSpace($tenantIdField)) {
            $fields += [ordered]@{
                name = $tenantIdField
                type = "string"
                required = $true
            }
        }
        $invariants = @()
        foreach ($field in @($entity.fields)) {
            $officialType = Convert-FieldType ([string]$field.type)
            $fields += [ordered]@{
                name = [string]$field.name
                type = $officialType
                required = [bool]$field.required
            }
            if ([bool]$field.required) {
                $invariants += [ordered]@{
                    name = (([string]$field.name).Substring(0, 1).ToUpperInvariant() + ([string]$field.name).Substring(1) + "Required")
                    expr = ([string]$field.name + " != null && " + [string]$field.name + " != ''")
                }
            }
            if ([bool]$field.unique) {
                $invariants += [ordered]@{
                    name = (([string]$field.name).Substring(0, 1).ToUpperInvariant() + ([string]$field.name).Substring(1) + "Unique")
                    type = "unique"
                    fields = @([string]$field.name)
                }
            }
        }

        $entityWorkflow = $modelWorkflows | Where-Object { [string]$_.entity -eq [string]$entity.name } | Select-Object -First 1
        $fieldNames = @($fields | ForEach-Object { [string]$_.name })
        if ($null -ne $entityWorkflow -and -not [string]::IsNullOrWhiteSpace([string]$entityWorkflow.workflowId)) {
            $workflowStates = @($entityWorkflow.states | ForEach-Object { [string]$_ } | Where-Object { -not [string]::IsNullOrWhiteSpace($_) })
            if ($fieldNames -contains "status") {
                foreach ($fieldSpec in $fields) {
                    if ([string]$fieldSpec.name -eq "status") {
                        $fieldSpec["type"] = "enum"
                        $fieldSpec["enumValues"] = $workflowStates
                    }
                }
            }
            else {
                $fields += [ordered]@{
                    name = "status"
                    type = "enum"
                    enumValues = $workflowStates
                    required = $true
                }
                $invariants += [ordered]@{
                    name = "StatusRequired"
                    expr = "status != null && status != ''"
                }
            }
        }

        $concept = [ordered]@{
            name = [string]$entity.name
            ui = [ordered]@{
                label = [string]$entity.name
            }
            fields = $fields
            invariants = $invariants
        }
        if ($null -ne $entityWorkflow -and -not [string]::IsNullOrWhiteSpace([string]$entityWorkflow.workflowId)) {
            $terminalStates = @($entityWorkflow.terminalStates | ForEach-Object { [string]$_ })
            $concept["lifecycle"] = [ordered]@{
                statusField = "status"
                states = @($entityWorkflow.states | ForEach-Object {
                    $stateValue = [string]$_
                    [ordered]@{
                        value = $stateValue
                        label = $stateValue
                        initial = ($stateValue -eq [string]$entityWorkflow.startState)
                        terminal = ($terminalStates -contains $stateValue)
                        metadata = [ordered]@{
                            beta0WorkflowId = [string]$entityWorkflow.workflowId
                        }
                    }
                })
                transitions = @($entityWorkflow.transitions | ForEach-Object {
                    [ordered]@{
                        from = [string]$_.from
                        to = [string]$_.to
                        actionLabel = [string]$_.name
                        metadata = [ordered]@{
                            beta0WorkflowId = [string]$entityWorkflow.workflowId
                            requiredRole = [string]$_.requiredRole
                        }
                    }
                })
            }
        }
        $concepts += $concept
        $events += [ordered]@{
            name = ([string]$entity.name + "Created")
            payload = @(
                [ordered]@{ name = "id"; type = "uuid" }
            )
        }
    }

    foreach ($flow in @($Model.flows)) {
        $flowSteps = @(
            [ordered]@{
                name = "enforce-invariants"
                type = "enforceInvariants"
                scope = [string]$flow.entity
            },
            [ordered]@{
                name = "save-" + (Convert-ToSlug ([string]$flow.entity))
                type = "capabilityCall"
                cap = "persistence"
                op = "save"
                args = @('$input')
                out = '$saved'
            },
            [ordered]@{
                name = "emit-created"
                type = "emitEvent"
                event = ([string]$flow.entity + "Created")
                from = '$saved'
            },
            [ordered]@{
                name = "return-result"
                type = "return"
                value = '$saved'
            }
        )
        $flows += [ordered]@{
            name = [string]$flow.name
            input = [ordered]@{
                concept = [string]$flow.entity
                mode = [string]$flow.operation
            }
            steps = $flowSteps
        }
    }

    $modelProcedures = if ($null -eq $Model.procedures) { @() } else { @($Model.procedures | Where-Object { $null -ne $_ }) }
    $modelPanels = if ($null -eq $Model.panels) { @() } else { @($Model.panels | Where-Object { $null -ne $_ }) }
    foreach ($procedure in $modelProcedures) {
        $procedureSteps = @()
        $steps = if ($null -eq $procedure.steps) { @() } else { @($procedure.steps) }
        foreach ($step in $steps) {
            $procedureStep = [ordered]@{ type = [string]$step.type }
            foreach ($propertyName in @("concept", "query", "procedure", "value")) {
                $property = $step.PSObject.Properties[$propertyName]
                if ($null -ne $property -and $null -ne $property.Value) {
                    $procedureStep[$propertyName] = $property.Value
                }
            }
            $procedureSteps += $procedureStep
        }
        if ($procedureSteps.Count -eq 0) {
            $procedureSteps += [ordered]@{
                type = "return"
                value = ([string]$procedure.procedureId + "-ok")
            }
        }
        $parameters = @($procedure.inputs | ForEach-Object {
            [ordered]@{
                name = [string]$_.name
                type = (Convert-FieldType ([string]$_.type))
                required = if ($null -eq $_.required) { $false } else { [bool]$_.required }
            }
        })
        $procedures += [ordered]@{
            name = [string]$procedure.procedureId
            description = "Expanded Beta 0 procedure " + [string]$procedure.procedureId
            parameters = $parameters
            steps = $procedureSteps
            returns = [ordered]@{ type = "string" }
            permissionRequirements = @([string]$procedure.requiredRole)
            tracePolicy = "summary"
            auditPolicy = if ([string]$procedure.sideEffectType -eq "none") { "read" } else { "write" }
            metadata = [ordered]@{
                beta0Surface = "custom-procedure"
                sideEffectType = [string]$procedure.sideEffectType
                tenantScoped = [bool]$procedure.tenantScoped
                maxAffectedRows = [int]$procedure.maxAffectedRows
                trustedSourceEntrypoint = if ($null -ne $procedure.implementation) { [string]$procedure.implementation.entrypoint } else { "" }
            }
        }
    }

    foreach ($panel in $modelPanels) {
        $safePanelMetadata = [ordered]@{}
        if ($null -ne $panel.metadata) {
            foreach ($propertyName in @("displayName", "description", "emptyStateMessage", "icon", "variant")) {
                $property = $panel.metadata.PSObject.Properties[$propertyName]
                if ($null -ne $property -and $null -ne $property.Value) {
                    $safePanelMetadata[$propertyName] = [string]$property.Value
                }
            }
        }
        $dataSource = [ordered]@{ name = [string]$panel.dataSource.name }
        if ([string]$panel.dataSource.kind -eq "entity") { $dataSource["concept"] = [string]$panel.dataSource.name }
        if ([string]$panel.dataSource.kind -eq "procedure") { $dataSource["procedure"] = [string]$panel.dataSource.name }
        if ([string]$panel.dataSource.kind -eq "workflow") { $dataSource["params"] = [ordered]@{ workflow = [string]$panel.dataSource.name } }
        $layoutType = switch ([string]$panel.type) {
            "dashboard-summary" { "dashboard" }
            "workflow-task" { "detail" }
            "entity-detail" { "detail" }
            "entity-list" { "table" }
            default { "form" }
        }
        $panelActions = @($panel.actions | ForEach-Object {
            [ordered]@{
                name = [string]$_
                binding = "procedure"
                procedure = [string]$_
                permissionRequirements = @([string]$panel.requiredRole)
            }
        })
        $panels += [ordered]@{
            name = [string]$panel.panelId
            route = [string]$panel.route
            title = [string]$panel.panelId
            dataSources = @($dataSource)
            layout = [ordered]@{ type = $layoutType }
            visibility = "role:" + [string]$panel.requiredRole
            actions = $panelActions
            metadata = [ordered]@{
                beta0Surface = "custom-ui-panel"
                customPanelContract = "minimal-declarative-v1"
                sourceType = [string]$panel.type
                tenantScoped = [bool]$panel.tenantScoped
                safeCustomPanelMetadata = $safePanelMetadata
                trustedSourceEntrypoint = if ($null -ne $panel.implementation) { [string]$panel.implementation.entrypoint } else { "" }
            }
        }
    }

    return [ordered]@{
        '$schema' = "NPDevContract/schemas/model.schema.json"
        namespace = "npdev.ai.beta." + ($ScenarioId -replace "-", ".")
        dslVersion = "1.0.0"
        version = "1.0"
        concepts = $concepts
        capabilities = @(
            [ordered]@{
                name = "persistence"
                type = "PersistenceCapability"
                operations = @("save", "unique", "findById")
            }
        )
        bindings = @(
            [ordered]@{
                capability = "persistence"
                adapter = "repository"
            },
            [ordered]@{
                capability = "eventBus"
                adapter = "inproc"
            }
        )
        events = $events
        flows = $flows
        procedures = $procedures
        panels = $panels
        metadata = [ordered]@{
            normalizedFrom = "ai-model.v1"
            scenarioId = $ScenarioId
            expandedBeta0ContractVersion = "expanded-beta0.2026-05-05"
            tenancy = $Model.tenancy
            auth = $Model.auth
            roles = @(if ($null -eq $Model.roles) { @() } else { @($Model.roles | Where-Object { $null -ne $_ }) })
            workflows = $modelWorkflows
            requiredSurfaces = @(
                "custom-ui-panels",
                "custom-procedures",
                "multi-tenancy",
                "authentication",
                "roles",
                "workflow-engine"
            )
        }
    }
}

function New-OfficialConfig {
    param([object]$Config, [object]$Model, [string]$ScenarioId)
    $slug = Convert-ToSlug $ScenarioId
    $databaseName = Convert-ToDatabaseName $ScenarioId
    return [ordered]@{
        '$schema' = "NPDevContract/schemas/config.schema.json"
        configVersion = "1.0"
        scenario = [ordered]@{
            name = $ScenarioId
            description = "AI-only Beta 0 normalized scenario for " + [string]$Model.app.name + "."
            outputRoot = [string]$Config.output.directory
        }
        generator = [ordered]@{
            failIfModelMissing = $true
            failIfConfigMissing = $true
            cleanOutputBeforeGenerate = $true
            emitPluginAssets = $true
            emitRuntimeAssets = $true
            emitUiAssets = $true
        }
        bootstrap = [ordered]@{
            root = "NPDevRuntimeHost"
            mergeStrategy = "clean-copy"
        }
        artifact = [ordered]@{
            root = ([string]$Config.output.directory + "/ArtifactNP")
            generatedFolderName = "npdev-generated"
            libsFolderName = "libs"
            metaFolderName = "npdev-meta"
        }
        finalExec = [ordered]@{
            root = ([string]$Config.output.directory + "/App")
            deleteBeforeMount = $true
        }
        database = [ordered]@{
            provider = "docker-postgres"
            host = "localhost"
            port = 5432
            database = $databaseName
            username = "finalexec"
            password = "finalexec"
            adminDatabase = "postgres"
            resetMode = "reset"
            containerName = "npdev-" + $slug
        }
        runtime = [ordered]@{
            springProfile = "dev,step0,ai-beta-local"
            serverPort = 18080
            javaArgs = @()
            gradleTask = "bootRun"
        }
        trialDefaults = [ordered]@{
            apiKey = "dev-key"
            recommendedProfiles = "dev,step0,ai-beta-local"
            runtimeUrl = "http://localhost:18080/"
            databaseMode = "step0-h2"
            pluginDiscoveryMode = "filesystem-folder"
            pluginPackageDirectory = "./npdev-generated/src/main/resources/npdev/plugin-packages"
            notes = @("AI-only Beta 0 uses local REST smoke checks and controlled command execution.")
        }
        metadata = [ordered]@{
            normalizedFrom = "ai-generator-config.v1"
            runtimeProfile = "ai-beta-local"
        }
    }
}

if (-not [string]::IsNullOrWhiteSpace($ScenarioPath)) {
    $manifestPath = Join-Path $ScenarioPath "scenario.manifest.json"
    $manifest = Read-JsonFile $manifestPath
    if ([string]::IsNullOrWhiteSpace($AiModelPath)) {
        $AiModelPath = Join-Path $ScenarioPath ([string]$manifest.files.aiModel)
    }
    if ([string]::IsNullOrWhiteSpace($AiConfigPath)) {
        $AiConfigPath = Join-Path $ScenarioPath ([string]$manifest.files.aiConfig)
    }
    if ([string]::IsNullOrWhiteSpace($OutputDirectory)) {
        $OutputDirectory = Join-Path $ScenarioPath "normalized"
    }
}

if ([string]::IsNullOrWhiteSpace($AiModelPath) -or [string]::IsNullOrWhiteSpace($AiConfigPath) -or [string]::IsNullOrWhiteSpace($OutputDirectory)) {
    throw "Provide ScenarioPath or AiModelPath, AiConfigPath, and OutputDirectory."
}

$aiModel = Read-JsonFile $AiModelPath
$aiConfig = Read-JsonFile $AiConfigPath
$scenarioId = [string]$aiConfig.scenario
$safetyFindings = @(Invoke-AiShiftLeftSafetyLint -AiModel $aiModel -AiConfig $aiConfig)
$failures = @($safetyFindings | ForEach-Object { New-NormalizerFailure ([string]$_.code) ([string]$_.message) })
if ($failures.Count -eq 0) {
    $failures = @(Assert-AiContractSupported $aiModel $aiConfig)
}

if ($failures.Count -gt 0) {
    $result = [pscustomobject]@{
        schemaVersion = "npdev-ai-contract-normalizer-result.v1"
        status = "failed"
        scenarioId = $scenarioId
        errors = $failures
    }
    if (-not [string]::IsNullOrWhiteSpace($ResultPath)) {
        $resultDirectory = Split-Path -Parent $ResultPath
        if (-not [string]::IsNullOrWhiteSpace($resultDirectory)) {
            New-Item -ItemType Directory -Force -Path $resultDirectory | Out-Null
        }
        $result | ConvertTo-Json -Depth 20 | Set-Content -LiteralPath $ResultPath -Encoding UTF8
    }
    Write-Error ($failures[0].code + ": " + $failures[0].message)
}

New-Item -ItemType Directory -Force -Path $OutputDirectory | Out-Null
$officialModel = New-OfficialModel $aiModel $scenarioId
$officialConfig = New-OfficialConfig $aiConfig $aiModel $scenarioId
$aiPanels = if ($null -eq $aiModel.panels) { @() } else { @($aiModel.panels | Where-Object { $null -ne $_ }) }
$aiProcedures = if ($null -eq $aiModel.procedures) { @() } else { @($aiModel.procedures | Where-Object { $null -ne $_ }) }
$aiWorkflows = if ($null -eq $aiModel.workflows) { @() } else { @($aiModel.workflows | Where-Object { $null -ne $_ }) }
$aiRoles = if ($null -eq $aiModel.roles) { @() } else { @($aiModel.roles | Where-Object { $null -ne $_ }) }
$modelPath = Join-Path $OutputDirectory "model.json"
$configPath = Join-Path $OutputDirectory "config.json"
$securityPath = Join-Path $OutputDirectory "security.json"
$workflowPath = Join-Path $OutputDirectory "workflows.json"
$panelPath = Join-Path $OutputDirectory "panels.json"
$procedurePath = Join-Path $OutputDirectory "procedures.json"
$officialModel | ConvertTo-Json -Depth 30 | Set-Content -LiteralPath $modelPath -Encoding UTF8
$officialConfig | ConvertTo-Json -Depth 30 | Set-Content -LiteralPath $configPath -Encoding UTF8
$securityContract = [ordered]@{
    schemaVersion = "npdev-official-security-contract.v1"
    expandedBeta0ContractVersion = "expanded-beta0.2026-05-05"
    tenancy = $aiModel.tenancy
    auth = $aiModel.auth
    roles = $aiRoles
}
$workflowContract = [ordered]@{
    schemaVersion = "npdev-official-workflow-contract.v1"
    expandedBeta0ContractVersion = "expanded-beta0.2026-05-05"
    workflows = $aiWorkflows
}
$panelContract = [ordered]@{
    schemaVersion = "npdev-official-panel-contract.v1"
    expandedBeta0ContractVersion = "expanded-beta0.2026-05-05"
    panels = $aiPanels
}
$procedureContract = [ordered]@{
    schemaVersion = "npdev-official-procedure-contract.v1"
    expandedBeta0ContractVersion = "expanded-beta0.2026-05-05"
    procedures = $aiProcedures
}
$securityContract | ConvertTo-Json -Depth 30 | Set-Content -LiteralPath $securityPath -Encoding UTF8
$workflowContract | ConvertTo-Json -Depth 30 | Set-Content -LiteralPath $workflowPath -Encoding UTF8
$panelContract | ConvertTo-Json -Depth 30 | Set-Content -LiteralPath $panelPath -Encoding UTF8
$procedureContract | ConvertTo-Json -Depth 30 | Set-Content -LiteralPath $procedurePath -Encoding UTF8

$result = [pscustomobject]@{
    schemaVersion = "npdev-ai-contract-normalizer-result.v1"
    status = "passed"
    scenarioId = $scenarioId
    outputs = [pscustomobject]@{
        model = $modelPath
        config = $configPath
        security = $securityPath
        workflows = $workflowPath
        panels = $panelPath
        procedures = $procedurePath
    }
    expandedSurfaceNormalizationPassed = ([string]$aiModel.app.kind -eq "expanded-beta-application")
    officialPanelContractsValid = ($aiPanels.Count -gt 0)
    officialProcedureContractsValid = ($aiProcedures.Count -gt 0)
    officialWorkflowContractsValid = ($aiWorkflows.Count -gt 0)
    officialSecurityContractsValid = ($null -ne $aiModel.tenancy -and $null -ne $aiModel.auth -and $aiRoles.Count -gt 0)
    errors = @()
}
if (-not [string]::IsNullOrWhiteSpace($ResultPath)) {
    $resultDirectory = Split-Path -Parent $ResultPath
    if (-not [string]::IsNullOrWhiteSpace($resultDirectory)) {
        New-Item -ItemType Directory -Force -Path $resultDirectory | Out-Null
    }
    $result | ConvertTo-Json -Depth 20 | Set-Content -LiteralPath $ResultPath -Encoding UTF8
}

$result | ConvertTo-Json -Depth 20
