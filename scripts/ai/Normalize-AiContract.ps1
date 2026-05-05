param(
    [string]$ScenarioPath,
    [string]$AiModelPath,
    [string]$AiConfigPath,
    [string]$OutputDirectory,
    [string]$ResultPath
)

$ErrorActionPreference = "Stop"

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
        "number" { return "long" }
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
    if ($Model.app.kind -ne "simple-crud-workflow") {
        $failures += New-NormalizerFailure "AI_MODEL_KIND_UNSUPPORTED" "Only simple-crud-workflow is supported in AI-only Beta 0."
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
    return $failures
}

function New-OfficialModel {
    param([object]$Model, [string]$ScenarioId)
    $concepts = @()
    $events = @()
    $flows = @()

    foreach ($entity in @($Model.entities)) {
        $fields = @(
            [ordered]@{
                name = "id"
                type = "uuid"
                id = $true
                required = $true
            }
        )
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
            if ([string]$field.type -eq "email") {
                $invariants += [ordered]@{
                    name = (([string]$field.name).Substring(0, 1).ToUpperInvariant() + ([string]$field.name).Substring(1) + "Unique")
                    type = "unique"
                    fields = @([string]$field.name)
                }
            }
        }

        $concepts += [ordered]@{
            name = [string]$entity.name
            ui = [ordered]@{
                label = [string]$entity.name
            }
            fields = $fields
            invariants = $invariants
        }
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
        metadata = [ordered]@{
            normalizedFrom = "ai-model.v1"
            scenarioId = $ScenarioId
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
$failures = @(Assert-AiContractSupported $aiModel $aiConfig)

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
$modelPath = Join-Path $OutputDirectory "model.json"
$configPath = Join-Path $OutputDirectory "config.json"
$officialModel | ConvertTo-Json -Depth 30 | Set-Content -LiteralPath $modelPath -Encoding UTF8
$officialConfig | ConvertTo-Json -Depth 30 | Set-Content -LiteralPath $configPath -Encoding UTF8

$result = [pscustomobject]@{
    schemaVersion = "npdev-ai-contract-normalizer-result.v1"
    status = "passed"
    scenarioId = $scenarioId
    outputs = [pscustomobject]@{
        model = $modelPath
        config = $configPath
    }
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
