param(
    [string]$ScenarioRoot = "golden-ai-scenarios",
    [string]$ReportPath = "scripts/reports/out/ai-schema-validation-report.json",
    [string]$ScopePolicyPath = "scripts/policy/beta0-scope.json",
    [string]$RunId = ""
)

$ErrorActionPreference = "Stop"

function Add-Failure {
    param(
        [System.Collections.Generic.List[string]]$Failures,
        [string]$Message
    )
    if ([string]::IsNullOrWhiteSpace($Message)) {
        return
    }
    $Failures.Add($Message) | Out-Null
}

function Test-Identifier {
    param([string]$Value, [string]$Pattern)
    return -not [string]::IsNullOrWhiteSpace($Value) -and $Value -match $Pattern
}

function Read-JsonFile {
    param([string]$Path)
    return Get-Content -Raw -LiteralPath $Path | ConvertFrom-Json
}

function Test-RelativeScenarioFile {
    param([object]$ScenarioDir, [string]$RelativePath)
    if ([string]::IsNullOrWhiteSpace($RelativePath)) {
        return $false
    }
    if ([System.IO.Path]::IsPathRooted($RelativePath) -or $RelativePath.Contains("..")) {
        return $false
    }
    $fullPath = Join-Path $ScenarioDir.FullName $RelativePath
    return Test-Path -LiteralPath $fullPath -PathType Leaf
}

function Invoke-SchemaValidation {
    param(
        [string]$SchemaPath,
        [string]$InstancePath,
        [string]$ResultPath
    )
    $ErrorActionPreference = "Continue"
    pwsh -NoProfile -File scripts/quality/Invoke-JsonSchemaValidation.ps1 `
        -SchemaPath $SchemaPath `
        -InstancePath $InstancePath `
        -ReportPath $ResultPath 2>$null | Out-Null
    $exitCode = $LASTEXITCODE
    $ErrorActionPreference = "Stop"
    $result = if (Test-Path -LiteralPath $ResultPath -PathType Leaf) { Read-JsonFile $ResultPath } else { $null }
    return [pscustomobject]@{
        schemaPath = $SchemaPath
        instancePath = $InstancePath
        resultPath = $ResultPath
        status = if ($exitCode -eq 0 -and $null -ne $result -and [string]$result.status -eq "passed") { "passed" } else { "failed" }
        errors = if ($null -ne $result) { @($result.errors) } else { @() }
        failures = if ($null -ne $result) { @($result.failures) } else { @("schema validation did not write a result") }
    }
}

function Test-AiModel {
    param([object]$Model, [string]$ScenarioId)
    $failures = [System.Collections.Generic.List[string]]::new()
    if ($Model.schemaVersion -ne "ai-model.v1") { Add-Failure $failures "schemaVersion must be ai-model.v1." }
    if ($null -eq $Model.app) {
        Add-Failure $failures "app is required."
    }
    else {
        if ([string]::IsNullOrWhiteSpace([string]$Model.app.name)) { Add-Failure $failures "app.name is required." }
        if ($Model.app.kind -ne "simple-crud-workflow") { Add-Failure $failures "app.kind must be simple-crud-workflow." }
    }

    $entityNames = [System.Collections.Generic.HashSet[string]]::new()
    if ($null -eq $Model.entities -or @($Model.entities).Count -lt 1) {
        Add-Failure $failures "entities must contain at least one entity."
    }
    else {
        foreach ($entity in @($Model.entities)) {
            if (-not (Test-Identifier ([string]$entity.name) "^[A-Z][A-Za-z0-9]*$")) {
                Add-Failure $failures ("entity name is invalid: " + [string]$entity.name)
            }
            else {
                $entityNames.Add([string]$entity.name) | Out-Null
            }
            if ($null -eq $entity.fields -or @($entity.fields).Count -lt 1) {
                Add-Failure $failures ("entity " + [string]$entity.name + " must contain at least one field.")
            }
            else {
                foreach ($field in @($entity.fields)) {
                    if (-not (Test-Identifier ([string]$field.name) "^[a-z][A-Za-z0-9]*$")) {
                        Add-Failure $failures ("field name is invalid: " + [string]$field.name)
                    }
                    if (@("string", "text", "email", "integer", "number", "boolean", "date", "datetime", "uuid") -notcontains [string]$field.type) {
                        Add-Failure $failures ("field type is unsupported: " + [string]$field.type)
                    }
                    if ($null -eq $field.required -or $field.required.GetType().Name -ne "Boolean") {
                        Add-Failure $failures ("field required must be boolean: " + [string]$field.name)
                    }
                }
            }
        }
    }

    if ($null -eq $Model.flows -or @($Model.flows).Count -lt 1) {
        Add-Failure $failures "flows must contain at least one flow."
    }
    else {
        foreach ($flow in @($Model.flows)) {
            if (-not (Test-Identifier ([string]$flow.name) "^[A-Z][A-Za-z0-9]*$")) {
                Add-Failure $failures ("flow name is invalid: " + [string]$flow.name)
            }
            if (-not $entityNames.Contains([string]$flow.entity)) {
                Add-Failure $failures ("flow entity reference is unresolved: " + [string]$flow.entity)
            }
            if (@("create", "read", "update", "delete", "list") -notcontains [string]$flow.operation) {
                Add-Failure $failures ("flow operation is unsupported: " + [string]$flow.operation)
            }
        }
    }

    return $failures
}

function Test-AiConfig {
    param([object]$Config, [string]$ScenarioId)
    $failures = [System.Collections.Generic.List[string]]::new()
    if ($Config.schemaVersion -ne "ai-generator-config.v1") { Add-Failure $failures "schemaVersion must be ai-generator-config.v1." }
    if ($Config.scenario -ne $ScenarioId) { Add-Failure $failures "scenario must match scenarioId." }
    if ($null -eq $Config.target) {
        Add-Failure $failures "target is required."
    }
    else {
        if ($Config.target.runtime -ne "spring-boot") { Add-Failure $failures "target.runtime must be spring-boot." }
        if ($Config.target.profile -ne "ai-beta-local") { Add-Failure $failures "target.profile must be ai-beta-local." }
    }
    if ($null -eq $Config.database -or @("embedded-test", "docker-postgres") -notcontains [string]$Config.database.mode) {
        Add-Failure $failures "database.mode must be embedded-test or docker-postgres."
    }
    $directory = [string]$Config.output.directory
    if ([string]::IsNullOrWhiteSpace($directory) -or [System.IO.Path]::IsPathRooted($directory) -or $directory.Contains("..") -or $directory -notmatch "^out/generated/[a-z0-9][a-z0-9-]*(/[a-z0-9][a-z0-9-]*)*$") {
        Add-Failure $failures "output.directory must be a relative path under out/generated with no traversal."
    }
    return $failures
}

function Test-AiVerification {
    param([object]$Verification, [string]$ScenarioId)
    $failures = [System.Collections.Generic.List[string]]::new()
    if ($Verification.schemaVersion -ne "ai-verification-report.v1") { Add-Failure $failures "schemaVersion must be ai-verification-report.v1." }
    if ($Verification.scenarioId -ne $ScenarioId) { Add-Failure $failures "scenarioId must match manifest scenarioId." }
    if ($Verification.baseUrlVariable -ne "NPDEV_GENERATED_APP_BASE_URL") { Add-Failure $failures "baseUrlVariable must be NPDEV_GENERATED_APP_BASE_URL." }
    if ($null -eq $Verification.checks -or @($Verification.checks).Count -lt 2) {
        Add-Failure $failures "checks must contain at least a health check and a behavior check."
        return $failures
    }

    $hasHealth = $false
    $hasBehavior = $false
    foreach ($check in @($Verification.checks)) {
        if ([string]::IsNullOrWhiteSpace([string]$check.id) -or [string]$check.id -notmatch "^[a-z0-9][a-z0-9-]*$") {
            Add-Failure $failures "check.id is required and must be kebab-case."
        }
        if ($check.type -ne "http") { Add-Failure $failures ("check " + [string]$check.id + " type must be http.") }
        if (@("GET", "POST", "PUT", "PATCH", "DELETE") -notcontains [string]$check.method) {
            Add-Failure $failures ("check " + [string]$check.id + " method is unsupported.")
        }
        $path = [string]$check.path
        if ([string]::IsNullOrWhiteSpace($path) -or -not $path.StartsWith("/") -or $path -match "^https?://") {
            Add-Failure $failures ("check " + [string]$check.id + " path must be a local absolute path.")
        }
        if ($null -eq $check.expectedStatus -or [int]$check.expectedStatus -lt 100 -or [int]$check.expectedStatus -gt 599) {
            Add-Failure $failures ("check " + [string]$check.id + " expectedStatus must be 100-599.")
        }
        if ($path -eq "/actuator/health") {
            $hasHealth = $true
        }
        else {
            $hasBehavior = $true
        }
    }
    if (-not $hasHealth) { Add-Failure $failures "checks must include /actuator/health." }
    if (-not $hasBehavior) { Add-Failure $failures "checks must include at least one behavior check." }
    return $failures
}

function Test-CommandRequest {
    param([object]$CommandRequest)
    $failures = [System.Collections.Generic.List[string]]::new()
    if ($CommandRequest.schemaVersion -ne "npdev-ai-command-request.v1") { Add-Failure $failures "command request schemaVersion must be npdev-ai-command-request.v1." }
    if ([string]::IsNullOrWhiteSpace([string]$CommandRequest.executable)) { Add-Failure $failures "command request executable is required." }
    if ($null -eq $CommandRequest.arguments -or $CommandRequest.arguments.GetType().Name -eq "String") {
        Add-Failure $failures "command request arguments must be an array."
    }
    if ([string]::IsNullOrWhiteSpace([string]$CommandRequest.workingDirectory)) { Add-Failure $failures "command request workingDirectory is required." }
    if ([string]::IsNullOrWhiteSpace([string]$CommandRequest.expectedErrorCode)) { Add-Failure $failures "command request expectedErrorCode is required." }
    return $failures
}

$workspaceRoot = (Resolve-Path ".").Path
if ([string]::IsNullOrWhiteSpace($RunId)) {
    $RunId = "ai-schema-validation-" + (Get-Date).ToUniversalTime().ToString("yyyyMMdd-HHmmssfff")
}
$scenarioRootPath = (Resolve-Path $ScenarioRoot).Path
$scenarioDirs = @(Get-ChildItem -LiteralPath $scenarioRootPath -Directory | Sort-Object Name)
$scopePolicy = Read-JsonFile $ScopePolicyPath
$requiredScenarios = @($scopePolicy.requiredScenarios | ForEach-Object { [string]$_ })
$discoveredScenarioIds = @($scenarioDirs | ForEach-Object { [string]$_.Name })
$missingRequiredScenarios = @($requiredScenarios | Where-Object { $discoveredScenarioIds -notcontains $_ })
$schemaValidationRoot = Join-Path $workspaceRoot "scripts/reports/tmp/ai-schema-validation/schema"
if (Test-Path -LiteralPath $schemaValidationRoot) {
    Remove-Item -LiteralPath $schemaValidationRoot -Recurse -Force
}
New-Item -ItemType Directory -Force -Path $schemaValidationRoot | Out-Null
$results = @()
$overallStatus = if ($missingRequiredScenarios.Count -eq 0) { "passed" } else { "failed" }

foreach ($scenarioDir in $scenarioDirs) {
    $scenarioFailures = [System.Collections.Generic.List[string]]::new()
    $semanticFailures = [System.Collections.Generic.List[string]]::new()
    $schemaValidations = @()
    $manifestPath = Join-Path $scenarioDir.FullName "scenario.manifest.json"
    $expectedStage = $null
    $actualFailureStage = $null
    $expectedOutcome = $null
    $scenarioId = $scenarioDir.Name

    try {
        if (-not (Test-Path -LiteralPath $manifestPath -PathType Leaf)) {
            throw "scenario.manifest.json is missing."
        }
        $manifestSchemaValidation = Invoke-SchemaValidation `
            -SchemaPath "schemas/ai/scenario-manifest.schema.json" `
            -InstancePath $manifestPath `
            -ResultPath (Join-Path $schemaValidationRoot ($scenarioDir.Name + "-manifest.json"))
        $schemaValidations += $manifestSchemaValidation
        if ($manifestSchemaValidation.status -ne "passed") {
            Add-Failure $scenarioFailures ("manifest schema validation failed: " + (@($manifestSchemaValidation.failures) -join "; "))
        }
        $manifest = Read-JsonFile $manifestPath
        $scenarioId = [string]$manifest.scenarioId
        $expectedOutcome = [string]$manifest.expectedOutcome
        $expectedStage = [string]$manifest.expectedFailureStage
        if ($manifest.schemaVersion -ne "ai-scenario-manifest.v1") {
            Add-Failure $semanticFailures "manifest schemaVersion must be ai-scenario-manifest.v1."
        }
        if ($scenarioId -ne $scenarioDir.Name) {
            Add-Failure $semanticFailures "manifest scenarioId must match directory name."
        }
        if (@("positive", "negative") -notcontains [string]$manifest.kind) {
            Add-Failure $semanticFailures "manifest kind must be positive or negative."
        }
        if (@("pass", "fail") -notcontains $expectedOutcome) {
            Add-Failure $semanticFailures "manifest expectedOutcome must be pass or fail."
        }
        if ($expectedOutcome -eq "fail" -and [string]::IsNullOrWhiteSpace($expectedStage)) {
            Add-Failure $semanticFailures "negative scenarios must declare expectedFailureStage."
        }

        if ($expectedStage -eq "command-policy") {
            if (-not (Test-RelativeScenarioFile $scenarioDir ([string]$manifest.files.commandRequest))) {
                Add-Failure $scenarioFailures "manifest file is missing or unsafe: commandRequest"
            }
            elseif ($scenarioFailures.Count -eq 0) {
                $commandRequestPath = Join-Path $scenarioDir.FullName ([string]$manifest.files.commandRequest)
                $schemaValidations += Invoke-SchemaValidation `
                    -SchemaPath "schemas/ai/ai-command-request.schema.json" `
                    -InstancePath $commandRequestPath `
                    -ResultPath (Join-Path $schemaValidationRoot ($scenarioId + "-command-request.json"))
                $commandRequest = Read-JsonFile $commandRequestPath
                $commandFailures = Test-CommandRequest $commandRequest
                foreach ($failure in @($commandFailures)) {
                    Add-Failure $semanticFailures $failure
                }
            }
        }
        else {
            foreach ($fileProperty in @("aiModel", "aiConfig", "verification", "expectedBehavior")) {
                if (-not (Test-RelativeScenarioFile $scenarioDir ([string]$manifest.files.$fileProperty))) {
                    Add-Failure $scenarioFailures ("manifest file is missing or unsafe: " + $fileProperty)
                }
            }

            if ($scenarioFailures.Count -eq 0) {
                $modelPath = Join-Path $scenarioDir.FullName ([string]$manifest.files.aiModel)
                $configPath = Join-Path $scenarioDir.FullName ([string]$manifest.files.aiConfig)
                $verificationPath = Join-Path $scenarioDir.FullName ([string]$manifest.files.verification)
                $modelSchemaValidation = Invoke-SchemaValidation `
                    -SchemaPath "schemas/ai/ai-model.schema.json" `
                    -InstancePath $modelPath `
                    -ResultPath (Join-Path $schemaValidationRoot ($scenarioId + "-ai-model.json"))
                $configSchemaValidation = Invoke-SchemaValidation `
                    -SchemaPath "schemas/ai/ai-generator-config.schema.json" `
                    -InstancePath $configPath `
                    -ResultPath (Join-Path $schemaValidationRoot ($scenarioId + "-ai-config.json"))
                $verificationSchemaValidation = Invoke-SchemaValidation `
                    -SchemaPath "schemas/ai/ai-verification-report.schema.json" `
                    -InstancePath $verificationPath `
                    -ResultPath (Join-Path $schemaValidationRoot ($scenarioId + "-verification.json"))
                $schemaValidations += $modelSchemaValidation
                $schemaValidations += $configSchemaValidation
                $schemaValidations += $verificationSchemaValidation
                if ($modelSchemaValidation.status -ne "passed" -and $null -eq $actualFailureStage) { $actualFailureStage = "ai-model-schema" }
                if ($configSchemaValidation.status -ne "passed" -and $null -eq $actualFailureStage) { $actualFailureStage = "ai-config-schema" }
                if ($verificationSchemaValidation.status -ne "passed" -and $null -eq $actualFailureStage) { $actualFailureStage = "verification-schema" }

                $model = Read-JsonFile $modelPath
                $modelFailures = Test-AiModel $model $scenarioId
                if ($modelFailures.Count -gt 0 -and $null -eq $actualFailureStage) { $actualFailureStage = "ai-model-schema" }

                $config = Read-JsonFile $configPath
                $configFailures = Test-AiConfig $config $scenarioId
                if ($configFailures.Count -gt 0 -and $null -eq $actualFailureStage) { $actualFailureStage = "ai-config-schema" }

                $verification = Read-JsonFile $verificationPath
                $verificationFailures = Test-AiVerification $verification $scenarioId
                if ($verificationFailures.Count -gt 0 -and $null -eq $actualFailureStage) { $actualFailureStage = "verification-schema" }

                if ($expectedOutcome -eq "pass") {
                    foreach ($schemaValidation in @($modelSchemaValidation, $configSchemaValidation, $verificationSchemaValidation)) {
                        if ($schemaValidation.status -ne "passed") {
                            Add-Failure $scenarioFailures ("schema validation failed for " + [string]$schemaValidation.instancePath + ": " + (@($schemaValidation.failures) -join "; "))
                        }
                    }
                    foreach ($failure in @($modelFailures + $configFailures + $verificationFailures)) {
                        Add-Failure $scenarioFailures $failure
                    }
                }
                elseif (-not [string]::IsNullOrWhiteSpace($actualFailureStage)) {
                    if ($actualFailureStage -ne $expectedStage) {
                        Add-Failure $scenarioFailures ("expected failure at " + $expectedStage + " but failed at " + $actualFailureStage + ".")
                        foreach ($failure in @($modelFailures + $configFailures + $verificationFailures)) {
                            Add-Failure $scenarioFailures $failure
                        }
                    }
                }
                elseif ($expectedStage -notin @("scope-policy", "smoke-verification")) {
                    Add-Failure $scenarioFailures ("expected failure at " + $expectedStage + " but AI schema validation passed.")
                }
            }
        }
    }
    catch {
        Add-Failure $scenarioFailures ([string]$_.Exception.Message)
    }

    foreach ($failure in @($semanticFailures)) {
        if ($expectedOutcome -eq "pass") {
            Add-Failure $scenarioFailures $failure
        }
    }

    $status = if ($scenarioFailures.Count -eq 0) { "passed" } else { "failed" }
    if ($status -eq "failed") { $overallStatus = "failed" }
    $results += [pscustomobject]@{
        scenarioId = $scenarioId
        status = $status
        expectedOutcome = $expectedOutcome
        expectedFailureStage = $expectedStage
        actualFailureStage = $actualFailureStage
        schemaValidation = [pscustomobject]@{
            status = if (@($schemaValidations | Where-Object { $_.status -ne "passed" }).Count -eq 0) { "passed" } else { "failed" }
            schemas = $schemaValidations
        }
        semanticValidation = [pscustomobject]@{
            status = if ($semanticFailures.Count -eq 0) { "passed" } else { "failed" }
            failures = @($semanticFailures)
        }
        failures = @($scenarioFailures)
    }
}

$report = [pscustomobject]@{
    schemaVersion = "npdev-ai-schema-validation-report.v1"
    runId = $RunId
    generatedAt = (Get-Date).ToUniversalTime().ToString("o")
    scriptPath = "scripts/quality/run-ai-schema-validation.ps1"
    workspaceRoot = $workspaceRoot
    scenarioRoot = $scenarioRootPath
    overallStatus = $overallStatus
    scenarioCount = $results.Count
    scenarioCoverage = [pscustomobject]@{
        policyPath = $ScopePolicyPath
        requiredScenarios = $requiredScenarios
        discoveredScenarios = $discoveredScenarioIds
        missingRequiredScenarios = $missingRequiredScenarios
        requiredScenarioCoveragePassed = ($missingRequiredScenarios.Count -eq 0)
    }
    scenarios = $results
}

$reportDirectory = Split-Path -Parent $ReportPath
if (-not [string]::IsNullOrWhiteSpace($reportDirectory)) {
    New-Item -ItemType Directory -Force -Path $reportDirectory | Out-Null
}
$report | ConvertTo-Json -Depth 20 | Set-Content -LiteralPath $ReportPath -Encoding UTF8

if ($overallStatus -ne "passed") {
    Write-Error ("AI schema validation failed. See " + $ReportPath)
}
else {
    Write-Host ("AI schema validation passed. Report: " + $ReportPath)
}
