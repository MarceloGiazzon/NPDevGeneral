param(
    [string]$ScenarioRoot = "golden-ai-scenarios",
    [string]$ReportPath = "scripts/reports/out/ai-beta-gate-report.json",
    [string]$ScopePolicyPath = "scripts/policy/beta0-scope.json",
    [string]$RunId = "",
    [switch]$SkipGenerator
)

$ErrorActionPreference = "Stop"

function Read-JsonFile {
    param([string]$Path)
    return Get-Content -Raw -LiteralPath $Path | ConvertFrom-Json
}

function New-Stage {
    param(
        [string]$Name,
        [string]$Status,
        [string]$Message = "",
        [object]$Evidence = $null
    )
    return [pscustomobject]@{
        name = $Name
        status = $Status
        message = $Message
        evidence = $Evidence
    }
}

function Add-Failure {
    param([System.Collections.Generic.List[string]]$Failures, [string]$Message)
    if (-not [string]::IsNullOrWhiteSpace($Message)) {
        $Failures.Add($Message) | Out-Null
    }
}

function Resolve-RepoPath {
    param([string]$PathValue)
    if ([System.IO.Path]::IsPathRooted($PathValue)) {
        return [System.IO.Path]::GetFullPath($PathValue)
    }
    return [System.IO.Path]::GetFullPath((Join-Path (Resolve-Path ".").Path $PathValue))
}

function Stop-RunRootProcesses {
    param([string]$RootPath)
    $pathVariants = @(
        [System.IO.Path]::GetFullPath($RootPath),
        ([System.IO.Path]::GetFullPath($RootPath) -replace "/", "\"),
        ([System.IO.Path]::GetFullPath($RootPath) -replace "\\", "/")
    ) | Select-Object -Unique
    $targets = @(Get-CimInstance Win32_Process | Where-Object {
        $process = $_
        $process.ProcessId -ne $PID -and
        $process.Name -match "java|cmd|pwsh|powershell" -and
        ($pathVariants | Where-Object { -not [string]::IsNullOrWhiteSpace($_) -and $null -ne $process.CommandLine -and $process.CommandLine.Contains($_) }).Count -gt 0
    })
    foreach ($target in $targets) {
        Stop-Process -Id $target.ProcessId -Force -ErrorAction SilentlyContinue
    }
}

function Test-OfficialModel {
    param([object]$Model)
    $failures = [System.Collections.Generic.List[string]]::new()
    if ($Model.dslVersion -ne "1.0.0") { Add-Failure $failures "model.dslVersion must be 1.0.0." }
    if ([string]::IsNullOrWhiteSpace([string]$Model.version)) { Add-Failure $failures "model.version is required." }
    if ([string]::IsNullOrWhiteSpace([string]$Model.namespace) -and [string]::IsNullOrWhiteSpace([string]$Model.model)) {
        Add-Failure $failures "model.namespace or model.model is required."
    }
    if ($null -eq $Model.concepts -or @($Model.concepts).Count -lt 1) {
        Add-Failure $failures "model.concepts must contain at least one concept."
    }
    else {
        foreach ($concept in @($Model.concepts)) {
            if ([string]::IsNullOrWhiteSpace([string]$concept.name)) { Add-Failure $failures "concept.name is required." }
            if ($null -eq $concept.fields -or @($concept.fields).Count -lt 1) {
                Add-Failure $failures ("concept " + [string]$concept.name + " must contain fields.")
            }
            foreach ($field in @($concept.fields)) {
                if ([string]::IsNullOrWhiteSpace([string]$field.name)) { Add-Failure $failures "field.name is required." }
                if ([string]::IsNullOrWhiteSpace([string]$field.type)) { Add-Failure $failures ("field " + [string]$field.name + " type is required.") }
            }
        }
    }
    return $failures
}

function Test-OfficialConfig {
    param([object]$Config)
    $failures = [System.Collections.Generic.List[string]]::new()
    if ($Config.configVersion -ne "1.0") { Add-Failure $failures "config.configVersion must be 1.0." }
    foreach ($required in @("scenario", "generator", "bootstrap", "artifact", "finalExec", "database", "runtime")) {
        if ($null -eq $Config.$required) { Add-Failure $failures ("config." + $required + " is required.") }
    }
    if ($null -ne $Config.runtime) {
        if ([string]$Config.runtime.springProfile -notmatch "ai-beta-local") { Add-Failure $failures "runtime.springProfile must include ai-beta-local." }
        if ([int]$Config.runtime.serverPort -lt 1) { Add-Failure $failures "runtime.serverPort must be set." }
        if ($Config.runtime.gradleTask -ne "bootRun") { Add-Failure $failures "runtime.gradleTask must be bootRun." }
    }
    return $failures
}

function Join-ArgsForGradleRun {
    param([string[]]$ArgumentValues)
    return ($ArgumentValues | ForEach-Object {
        if ($_ -match "\s") {
            '"' + ($_ -replace '"', '\"') + '"'
        }
        else {
            $_
        }
    }) -join " "
}

function Get-FileHashMap {
    param([string]$RootPath)
    $rootFull = [System.IO.Path]::GetFullPath($RootPath)
    $map = [ordered]@{}
    if (-not (Test-Path -LiteralPath $rootFull -PathType Container)) {
        return $map
    }
    foreach ($file in @(Get-ChildItem -LiteralPath $rootFull -Recurse -File | Sort-Object FullName)) {
        $relative = [System.IO.Path]::GetRelativePath($rootFull, $file.FullName) -replace "\\", "/"
        $map[$relative] = (Get-FileHash -Algorithm SHA256 -LiteralPath $file.FullName).Hash
    }
    return $map
}

function Compare-HashMaps {
    param([object]$Expected, [object]$Actual)
    $failures = [System.Collections.Generic.List[string]]::new()
    $expectedKeys = @($Expected.Keys | Sort-Object)
    $actualKeys = @($Actual.Keys | Sort-Object)
    foreach ($key in $expectedKeys) {
        if ($actualKeys -notcontains $key) {
            Add-Failure $failures ("Missing generated file in repeat run: " + $key)
        }
        elseif ([string]$Expected[$key] -ne [string]$Actual[$key]) {
            Add-Failure $failures ("Generated file hash differs: " + $key)
        }
    }
    foreach ($key in $actualKeys) {
        if ($expectedKeys -notcontains $key) {
            Add-Failure $failures ("Unexpected generated file in repeat run: " + $key)
        }
    }
    return $failures
}

function Invoke-OfficialSchemaValidation {
    param(
        [string]$Name,
        [string]$SchemaPath,
        [string]$JsonPath,
        [string]$ResultPath
    )
    $ErrorActionPreference = "Continue"
    pwsh -NoProfile -File scripts/quality/Invoke-JsonSchemaValidation.ps1 `
        -SchemaPath $SchemaPath `
        -JsonPath $JsonPath `
        -ReportPath $ResultPath 2>$null | Out-Null
    $exitCode = $LASTEXITCODE
    $ErrorActionPreference = "Stop"
    $result = if (Test-Path -LiteralPath $ResultPath -PathType Leaf) { Read-JsonFile $ResultPath } else { $null }
    $failures = @()
    if ($exitCode -ne 0 -or $null -eq $result -or [string]$result.status -ne "passed") {
        $failures = if ($null -ne $result) { @($result.failures) } else { @("Validation did not write a result.") }
    }
    return [pscustomobject]@{
        name = $Name
        schemaPath = $SchemaPath
        jsonPath = $JsonPath
        resultPath = $ResultPath
        status = if ($failures.Count -eq 0) { "passed" } else { "failed" }
        failures = $failures
    }
}

function Invoke-ControlledGradleGenerator {
    param(
        [string]$ModelPath,
        [string]$ConfigPath,
        [string]$ArtifactRoot,
        [string]$MigrationsRoot,
        [string]$FinalAppRoot,
        [string]$ResultPath,
        [bool]$AssembleFinalApp
    )
    New-Item -ItemType Directory -Force -Path $MigrationsRoot | Out-Null
    $generatorArgs = @(
        "--config", $ConfigPath,
        "--model", $ModelPath,
        "--out", $ArtifactRoot,
        "--migrationsDir", $MigrationsRoot,
        "--runtimeHostTemplate", (Join-Path $workspaceRoot "NPDevRuntimeHost"),
        "--finalAppOut", $FinalAppRoot,
        "--clean"
    )
    if ($AssembleFinalApp) {
        $generatorArgs += @("--assembleFinalApp", "--cleanFinalApp")
    }
    else {
        $generatorArgs += "--no-assembleFinalApp"
    }
    $gradleArgLine = Join-ArgsForGradleRun $generatorArgs
    $gradlew = Join-Path $workspaceRoot "NPDevGenerator/gradlew.bat"
    $runnerArguments = @(":generator:run", ("--args=" + $gradleArgLine), "--no-daemon", "--console=plain")
    $runnerArgumentsJson = @($runnerArguments) | ConvertTo-Json -Compress
    $ErrorActionPreference = "Continue"
    pwsh -NoProfile -File scripts/security/Invoke-ControlledCommand.ps1 `
        -Executable $gradlew `
        -ArgumentsJson $runnerArgumentsJson `
        -WorkingDirectory (Join-Path $workspaceRoot "NPDevGenerator") `
        -TimeoutSeconds 600 `
        -ResultPath $ResultPath 2>$null | Out-Null
    $runnerExit = $LASTEXITCODE
    $ErrorActionPreference = "Stop"
    $runnerResult = if (Test-Path -LiteralPath $ResultPath -PathType Leaf) { Read-JsonFile $ResultPath } else { $null }
    return [pscustomobject]@{
        exitCode = $runnerExit
        result = $runnerResult
    }
}

function Invoke-ControlledCommandRequest {
    param(
        [string]$ScenarioDir,
        [object]$Manifest,
        [string]$ResultPath
    )
    $requestPath = Join-Path $ScenarioDir ([string]$Manifest.files.commandRequest)
    $request = Read-JsonFile $requestPath
    $ErrorActionPreference = "Continue"
    pwsh -NoProfile -File scripts/security/Invoke-StructuredCommandRequest.ps1 `
        -RequestPath $requestPath `
        -ResultPath $ResultPath 2>$null | Out-Null
    $runnerExit = $LASTEXITCODE
    $ErrorActionPreference = "Stop"
    $runnerResult = if (Test-Path -LiteralPath $ResultPath -PathType Leaf) { Read-JsonFile $ResultPath } else { $null }
    return [pscustomobject]@{
        exitCode = $runnerExit
        requestPath = $requestPath
        request = $request
        result = $runnerResult
    }
}

$workspaceRoot = (Resolve-Path ".").Path
if ([string]::IsNullOrWhiteSpace($RunId)) {
    $RunId = "ai-beta-gate-" + (Get-Date).ToUniversalTime().ToString("yyyyMMdd-HHmmssfff")
}
$scenarioRootPath = Resolve-RepoPath $ScenarioRoot
$reportPathFull = Resolve-RepoPath $ReportPath
$runRoot = Join-Path $workspaceRoot "scripts/reports/tmp/ai-beta-gate"
Stop-RunRootProcesses -RootPath $runRoot
if (Test-Path -LiteralPath $runRoot) {
    Remove-Item -LiteralPath $runRoot -Recurse -Force
}
New-Item -ItemType Directory -Force -Path $runRoot | Out-Null
New-Item -ItemType Directory -Force -Path (Split-Path -Parent $reportPathFull) | Out-Null
$reproducibilityReportPath = "scripts/reports/out/ai-beta-reproducibility-report.json"
try {
    pwsh -NoProfile -File scripts/quality/write-ai-beta-reproducibility-report.ps1 -ReportPath $reproducibilityReportPath -RunId $RunId | Out-Null
}
catch {
    Write-Warning ("Failed to write reproducibility report: " + $_.Exception.Message)
}
$reproducibilityReport = if (Test-Path -LiteralPath $reproducibilityReportPath -PathType Leaf) { Read-JsonFile $reproducibilityReportPath } else { $null }

$policyPath = Join-Path $workspaceRoot "scripts/policy/beta-scope.json"
$policyStageStatus = "passed"
$policyStageMessage = "Beta scope policy loaded."
try {
    $policy = Read-JsonFile $policyPath
    if ($policy.schemaVersion -ne "npdev-beta-scope.v1" -or $policy.release -ne "ai-only-beta-0") {
        $policyStageStatus = "failed"
        $policyStageMessage = "Beta scope policy is malformed or not ai-only-beta-0."
    }
}
catch {
    $policyStageStatus = "failed"
    $policyStageMessage = $_.Exception.Message
}

$schemaReportPath = "scripts/reports/out/ai-schema-validation-report.json"
$schemaExit = 0
try {
    pwsh -NoProfile -File scripts/quality/run-ai-schema-validation.ps1 -ScenarioRoot $ScenarioRoot -ReportPath $schemaReportPath -ScopePolicyPath $ScopePolicyPath -RunId $RunId | Out-Null
    $schemaExit = $LASTEXITCODE
}
catch {
    $schemaExit = 1
}
$schemaReport = if (Test-Path -LiteralPath $schemaReportPath -PathType Leaf) { Read-JsonFile $schemaReportPath } else { $null }

$scenarioDirs = @(Get-ChildItem -LiteralPath $scenarioRootPath -Directory | Sort-Object Name)
$scopePolicy = Read-JsonFile (Resolve-RepoPath $ScopePolicyPath)
$requiredScenarios = @($scopePolicy.requiredScenarios | ForEach-Object { [string]$_ })
$discoveredScenarioIds = @($scenarioDirs | ForEach-Object { [string]$_.Name })
$missingRequiredScenarios = @($requiredScenarios | Where-Object { $discoveredScenarioIds -notcontains $_ })
$scenarioResults = @()
$overallStatus = if ($policyStageStatus -eq "passed" -and $null -ne $schemaReport -and $schemaExit -eq 0 -and $missingRequiredScenarios.Count -eq 0) { "passed" } else { "failed" }

foreach ($scenarioDir in $scenarioDirs) {
    $scenarioFailureReasons = [System.Collections.Generic.List[string]]::new()
    $stages = @()
    $manifestPath = Join-Path $scenarioDir.FullName "scenario.manifest.json"
    $manifest = Read-JsonFile $manifestPath
    $scenarioId = [string]$manifest.scenarioId
    $expectedOutcome = [string]$manifest.expectedOutcome
    $expectedFailureStage = if ($null -eq $manifest.expectedFailureStage) { $null } else { [string]$manifest.expectedFailureStage }
    $scenarioRunRoot = Join-Path $runRoot $scenarioId
    New-Item -ItemType Directory -Force -Path $scenarioRunRoot | Out-Null

    $stages += New-Stage "scenario-discovery" "passed" "Scenario manifest discovered." ([pscustomobject]@{
        manifestPath = $manifestPath
        files = $manifest.files
    })
    $stages += New-Stage "scope-policy" $policyStageStatus $policyStageMessage ([pscustomobject]@{
        policyPath = $policyPath
    })
    if ($policyStageStatus -ne "passed") {
        Add-Failure $scenarioFailureReasons $policyStageMessage
    }

    $schemaScenario = $null
    if ($null -ne $schemaReport) {
        $schemaScenario = @($schemaReport.scenarios | Where-Object { $_.scenarioId -eq $scenarioId } | Select-Object -First 1)
    }
    if ($null -eq $schemaScenario) {
        $stages += New-Stage "ai-schema-validation" "failed" "Scenario was missing from AI schema validation report." $null
        Add-Failure $scenarioFailureReasons "Scenario missing from AI schema validation report."
    }
    elseif ([string]$schemaScenario.status -eq "passed") {
        $stages += New-Stage "ai-schema-validation" "passed" "AI schema validation matched expected outcome for this scenario." $schemaScenario
    }
    else {
        $stages += New-Stage "ai-schema-validation" "failed" "AI schema validation failed unexpectedly." $schemaScenario
        Add-Failure $scenarioFailureReasons "AI schema validation failed unexpectedly."
    }

    if ($expectedOutcome -eq "fail" -and $expectedFailureStage -eq "command-policy" -and $scenarioFailureReasons.Count -eq 0) {
        $commandResultPath = Join-Path $scenarioRunRoot "command-policy-result.json"
        $commandRun = Invoke-ControlledCommandRequest -ScenarioDir $scenarioDir.FullName -Manifest $manifest -ResultPath $commandResultPath
        $expectedErrorCode = if (-not [string]::IsNullOrWhiteSpace([string]$manifest.expectedErrorCode)) {
            [string]$manifest.expectedErrorCode
        }
        else {
            [string]$commandRun.request.expectedErrorCode
        }
        $actualErrorCode = if ($null -ne $commandRun.result) { [string]$commandRun.result.errorCode } else { "" }
        $commandStatus = if ($commandRun.exitCode -eq 2 -and $null -ne $commandRun.result -and [string]$commandRun.result.status -eq "blocked" -and $actualErrorCode -eq $expectedErrorCode) { "passed" } else { "failed" }
        $stages += New-Stage "command-policy" $commandStatus ($(if ($commandStatus -eq "passed") { "Unsafe AI command was blocked with the expected error code." } else { "Unsafe AI command did not match the expected block result." })) ([pscustomobject]@{
            requestPath = $commandRun.requestPath
            expectedErrorCode = $expectedErrorCode
            commandResult = $commandRun.result
        })
        if ($commandStatus -ne "passed") {
            Add-Failure $scenarioFailureReasons ("Command policy expected " + $expectedErrorCode + " but got " + $actualErrorCode + ".")
        }
        $stages += New-Stage "normalization" "skipped" "Command-policy negative scenario stopped before normalization." $null
        $stages += New-Stage "official-validation" "skipped" "Command-policy negative scenario stopped before official validation." $null
        $stages += New-Stage "generation" "skipped" "Command-policy negative scenario stopped before generation." $null
        $stages += New-Stage "deterministic-generation" "skipped" "Command-policy negative scenario stopped before deterministic generation." $null
        $stages += New-Stage "build" "skipped" "Command-policy negative scenario stopped before build." $null
        $stages += New-Stage "boot" "skipped" "Command-policy negative scenario stopped before runtime boot." $null
        $stages += New-Stage "health" "skipped" "Command-policy negative scenario stopped before health check." $null
        $stages += New-Stage "smoke" "skipped" "Command-policy negative scenario stopped before REST smoke execution." $null
        $scenarioResults += [pscustomobject]@{
            scenarioId = $scenarioId
            kind = [string]$manifest.kind
            expectedOutcome = $expectedOutcome
            expectedFailureStage = $expectedFailureStage
            expectedFailureMatched = ($expectedOutcome -eq "fail" -and $expectedFailureStage -eq "command-policy" -and $scenarioFailureReasons.Count -eq 0)
            expectedErrorCode = $expectedErrorCode
            status = if ($scenarioFailureReasons.Count -eq 0) { "passed" } else { "failed" }
            stages = $stages
            failureReasons = @($scenarioFailureReasons)
        }
        if ($scenarioFailureReasons.Count -gt 0) { $overallStatus = "failed" }
        continue
    }

    $terminalExpectedNegative = $expectedOutcome -eq "fail" -and $expectedFailureStage -in @("ai-model-schema", "ai-config-schema", "verification-schema", "scope-policy")
    if ($terminalExpectedNegative -and $scenarioFailureReasons.Count -eq 0) {
        $stages += New-Stage "normalization" "skipped" "Expected negative scenario stopped before normalization." $null
        $stages += New-Stage "official-validation" "skipped" "Expected negative scenario stopped before official validation." $null
        $stages += New-Stage "generation" "skipped" "Expected negative scenario stopped before generation." $null
        $stages += New-Stage "deterministic-generation" "skipped" "Expected negative scenario stopped before deterministic generation." $null
        $stages += New-Stage "build" "skipped" "Expected negative scenario stopped before build." $null
        $stages += New-Stage "boot" "skipped" "Expected negative scenario stopped before runtime boot." $null
        $stages += New-Stage "health" "skipped" "Expected negative scenario stopped before health check." $null
        $stages += New-Stage "smoke" "skipped" "Expected negative scenario stopped before REST smoke execution." $null
        $scenarioResults += [pscustomobject]@{
            scenarioId = $scenarioId
            kind = [string]$manifest.kind
            expectedOutcome = $expectedOutcome
            expectedFailureStage = $expectedFailureStage
            expectedFailureMatched = $true
            status = "passed"
            stages = $stages
            failureReasons = @()
        }
        continue
    }

    $normalizedRoot = Join-Path $scenarioRunRoot "normalized"
    $normalizerResultPath = Join-Path $scenarioRunRoot "normalizer-result.json"
    $normalizerStatus = "passed"
    $normalizerMessage = "AI contract normalized to official model/config."
    try {
        pwsh -NoProfile -File scripts/ai/Normalize-AiContract.ps1 -ScenarioPath $scenarioDir.FullName -OutputDirectory $normalizedRoot -ResultPath $normalizerResultPath | Out-Null
        if ($LASTEXITCODE -ne 0) { throw "Normalizer exited with code " + $LASTEXITCODE }
    }
    catch {
        $normalizerStatus = "failed"
        $normalizerMessage = $_.Exception.Message
        Add-Failure $scenarioFailureReasons $normalizerMessage
    }
    $normalizerEvidence = if (Test-Path -LiteralPath $normalizerResultPath -PathType Leaf) { Read-JsonFile $normalizerResultPath } else { $null }
    $stages += New-Stage "normalization" $normalizerStatus $normalizerMessage $normalizerEvidence

    $modelPath = Join-Path $normalizedRoot "model.json"
    $configPath = Join-Path $normalizedRoot "config.json"
    $officialStatus = "passed"
    $officialFailures = [System.Collections.Generic.List[string]]::new()
    if ($normalizerStatus -eq "passed") {
        try {
            $modelValidation = Invoke-OfficialSchemaValidation `
                -Name "official-model" `
                -SchemaPath "NPDevContract/schemas/model.schema.json" `
                -JsonPath $modelPath `
                -ResultPath (Join-Path $scenarioRunRoot "official-model-schema-validation.json")
            $configValidation = Invoke-OfficialSchemaValidation `
                -Name "official-config" `
                -SchemaPath "NPDevContract/schemas/config.schema.json" `
                -JsonPath $configPath `
                -ResultPath (Join-Path $scenarioRunRoot "official-config-schema-validation.json")
            foreach ($failure in @($modelValidation.failures)) { Add-Failure $officialFailures ("model: " + [string]$failure) }
            foreach ($failure in @($configValidation.failures)) { Add-Failure $officialFailures ("config: " + [string]$failure) }
        }
        catch {
            Add-Failure $officialFailures $_.Exception.Message
        }
    }
    else {
        Add-Failure $officialFailures "Skipped because normalization failed."
    }
    if ($officialFailures.Count -gt 0) {
        $officialStatus = "failed"
        foreach ($failure in $officialFailures) { Add-Failure $scenarioFailureReasons $failure }
    }
    $stages += New-Stage "official-validation" $officialStatus ($(if ($officialStatus -eq "passed") { "Normalized official model/config passed strict official JSON Schema validation." } else { "Normalized official model/config failed strict official JSON Schema validation." })) ([pscustomobject]@{
        modelPath = $modelPath
        configPath = $configPath
        failures = @($officialFailures)
    })

    $artifactRoot = Join-Path $scenarioRunRoot "generated/ArtifactNP"
    $migrationsRoot = Join-Path $scenarioRunRoot "generated/db/migration"
    $finalAppRoot = Join-Path $scenarioRunRoot "generated/App"
    $verificationPath = Join-Path $scenarioDir.FullName ([string]$manifest.files.verification)

    $generationStatus = "pending"
    $generationMessage = "Generator execution was skipped."
    $generationEvidence = $null
    if ($SkipGenerator) {
        $generationStatus = "skipped"
        $generationMessage = "Generator execution skipped by -SkipGenerator."
    }
    elseif ($officialStatus -eq "passed") {
        $runnerResultPath = Join-Path $scenarioRunRoot "generator-command-result.json"
        $generatorRun = Invoke-ControlledGradleGenerator `
            -ModelPath $modelPath `
            -ConfigPath $configPath `
            -ArtifactRoot $artifactRoot `
            -MigrationsRoot $migrationsRoot `
            -FinalAppRoot $finalAppRoot `
            -ResultPath $runnerResultPath `
            -AssembleFinalApp $true
        $runnerResult = $generatorRun.result
        if ($generatorRun.exitCode -eq 0 -and $null -ne $runnerResult -and [string]$runnerResult.status -eq "passed") {
            $generationStatus = "passed"
            $generationMessage = "Generator completed and assembled final app through controlled runner."
        }
        else {
            $generationStatus = "failed"
            $generationMessage = "Generator failed or was blocked by controlled runner."
            Add-Failure $scenarioFailureReasons $generationMessage
        }
        $generationEvidence = [pscustomobject]@{
            artifactRoot = $artifactRoot
            migrationsRoot = $migrationsRoot
            finalAppRoot = $finalAppRoot
            commandResult = $runnerResult
        }
    }
    $stages += New-Stage "generation" $generationStatus $generationMessage $generationEvidence

    $determinismStatus = "skipped"
    $determinismMessage = "Determinism check skipped because generation did not pass."
    $determinismEvidence = $null
    if ($generationStatus -eq "passed") {
        $repeatArtifactRoot = Join-Path $scenarioRunRoot "determinism/ArtifactNP"
        $repeatMigrationsRoot = Join-Path $scenarioRunRoot "determinism/db/migration"
        $repeatFinalAppRoot = Join-Path $scenarioRunRoot "determinism/App"
        $determinismRunnerPath = Join-Path $scenarioRunRoot "determinism-command-result.json"
        $repeatRun = Invoke-ControlledGradleGenerator `
            -ModelPath $modelPath `
            -ConfigPath $configPath `
            -ArtifactRoot $repeatArtifactRoot `
            -MigrationsRoot $repeatMigrationsRoot `
            -FinalAppRoot $repeatFinalAppRoot `
            -ResultPath $determinismRunnerPath `
            -AssembleFinalApp $false
        if ($repeatRun.exitCode -eq 0 -and $null -ne $repeatRun.result -and [string]$repeatRun.result.status -eq "passed") {
            $firstHashes = Get-FileHashMap $artifactRoot
            $secondHashes = Get-FileHashMap $repeatArtifactRoot
            $determinismFailures = Compare-HashMaps $firstHashes $secondHashes
            if ($determinismFailures.Count -eq 0) {
                $determinismStatus = "passed"
                $determinismMessage = "Repeated generation produced identical ArtifactNP file hashes."
            }
            else {
                $determinismStatus = "failed"
                $determinismMessage = "Repeated generation differed."
                foreach ($failure in $determinismFailures) { Add-Failure $scenarioFailureReasons $failure }
            }
            $determinismEvidence = [pscustomobject]@{
                fileCount = $firstHashes.Count
                repeatArtifactRoot = $repeatArtifactRoot
                commandResult = $repeatRun.result
                failures = @($determinismFailures)
            }
        }
        else {
            $determinismStatus = "failed"
            $determinismMessage = "Repeat generation failed or was blocked by controlled runner."
            Add-Failure $scenarioFailureReasons $determinismMessage
            $determinismEvidence = [pscustomobject]@{
                repeatArtifactRoot = $repeatArtifactRoot
                commandResult = $repeatRun.result
            }
        }
    }
    $stages += New-Stage "deterministic-generation" $determinismStatus $determinismMessage $determinismEvidence

    $buildStage = New-Stage "build" "skipped" "Build skipped because generation or determinism did not pass." $null
    $bootStage = New-Stage "boot" "skipped" "Boot skipped because generation or determinism did not pass." $null
    $healthStage = New-Stage "health" "skipped" "Health skipped because generation or determinism did not pass." $null
    $smokeStage = New-Stage "smoke" "skipped" "Smoke skipped because generation or determinism did not pass." $null
    if ($generationStatus -eq "passed" -and $determinismStatus -eq "passed") {
        $port = 18080 + [array]::IndexOf($scenarioDirs.Name, $scenarioDir.Name)
        $appSmokeReportPath = Join-Path $scenarioRunRoot "app-smoke-result.json"
        $appSmokeRunnerPath = Join-Path $scenarioRunRoot "app-smoke-command-result.json"
        $appSmokeArgs = @(
            "-NoProfile",
            "-File",
            "scripts/quality/invoke-ai-beta-app-smoke.ps1",
            "-AppRoot", $finalAppRoot,
            "-VerificationPath", $verificationPath,
            "-ReportPath", $appSmokeReportPath,
            "-Port", [string]$port,
            "-Profiles", "dev,step0,ai-beta-local",
            "-BootTimeoutSeconds", "180"
        )
        $appSmokeArgsJson = @($appSmokeArgs) | ConvertTo-Json -Compress
        $ErrorActionPreference = "Continue"
        pwsh -NoProfile -File scripts/security/Invoke-ControlledCommand.ps1 `
            -Executable "pwsh" `
            -ArgumentsJson $appSmokeArgsJson `
            -WorkingDirectory $workspaceRoot `
            -TimeoutSeconds 600 `
            -ResultPath $appSmokeRunnerPath 2>$null | Out-Null
        $appSmokeExit = $LASTEXITCODE
        $ErrorActionPreference = "Stop"
        $appSmokeRunner = if (Test-Path -LiteralPath $appSmokeRunnerPath -PathType Leaf) { Read-JsonFile $appSmokeRunnerPath } else { $null }
        $appSmoke = if (Test-Path -LiteralPath $appSmokeReportPath -PathType Leaf) { Read-JsonFile $appSmokeReportPath } else { $null }
        $buildStage = New-Stage "build" ($(if ($null -ne $appSmoke -and $appSmoke.build.status -eq "passed") { "passed" } else { "failed" })) "Controlled generated app build stage." ([pscustomobject]@{ appSmoke = $appSmoke; commandResult = $appSmokeRunner })
        $bootStage = New-Stage "boot" ($(if ($null -ne $appSmoke -and $appSmoke.boot.status -in @("running", "stopped")) { "passed" } else { "failed" })) "Generated app boot process stage." ([pscustomobject]@{ appSmoke = $appSmoke; commandResult = $appSmokeRunner })
        $healthStage = New-Stage "health" ($(if ($null -ne $appSmoke -and $appSmoke.health.status -eq "passed") { "passed" } else { "failed" })) "Generated app health endpoint stage." ([pscustomobject]@{ appSmoke = $appSmoke; commandResult = $appSmokeRunner })
        $smokeActualStatus = if ($null -ne $appSmoke -and $appSmoke.smoke.status -eq "passed") { "passed" } else { "failed" }
        $smokeStage = New-Stage "smoke" $smokeActualStatus "Formal REST smoke verification stage." ([pscustomobject]@{ appSmoke = $appSmoke; commandResult = $appSmokeRunner })

        if ($buildStage.status -ne "passed") { Add-Failure $scenarioFailureReasons "Generated app build failed." }
        if ($bootStage.status -ne "passed") { Add-Failure $scenarioFailureReasons "Generated app boot failed." }
        if ($healthStage.status -ne "passed") { Add-Failure $scenarioFailureReasons "Generated app health check failed." }
        if ($smokeActualStatus -ne "passed") {
            if ($expectedOutcome -eq "fail" -and $expectedFailureStage -eq "smoke-verification") {
                $smokeStage.message = "Formal REST smoke verification failed as expected."
            }
            else {
                Add-Failure $scenarioFailureReasons "Formal REST smoke verification failed."
            }
        }
        elseif ($expectedOutcome -eq "fail" -and $expectedFailureStage -eq "smoke-verification") {
            Add-Failure $scenarioFailureReasons "Expected smoke verification failure, but smoke passed."
        }
    }
    $stages += $buildStage
    $stages += $bootStage
    $stages += $healthStage
    $stages += $smokeStage

    $scenarioStatus = if ($scenarioFailureReasons.Count -eq 0) { "passed" } else { "failed" }
    if ($scenarioStatus -eq "failed") { $overallStatus = "failed" }
    $scenarioResults += [pscustomobject]@{
        scenarioId = $scenarioId
        kind = [string]$manifest.kind
        expectedOutcome = $expectedOutcome
        expectedFailureStage = $expectedFailureStage
        expectedFailureMatched = ($expectedOutcome -eq "fail" -and $expectedFailureStage -eq "smoke-verification" -and $scenarioStatus -eq "passed")
        status = $scenarioStatus
        stages = $stages
        failureReasons = @($scenarioFailureReasons)
    }
}

$report = [pscustomobject]@{
    schemaVersion = "npdev-ai-beta-gate-report.v1"
    runId = $RunId
    overallStatus = $overallStatus
    generatedAt = (Get-Date).ToUniversalTime().ToString("o")
    scriptPath = "scripts/quality/run-ai-beta-gate.ps1"
    workspaceRoot = $workspaceRoot
    scenarioRoot = $scenarioRootPath
    scenarioCount = $scenarioResults.Count
    sourceOfTruth = "scripts/reports/out/ai-beta-gate-report.json"
    reproducibilityReport = $reproducibilityReportPath
    scenarioCoverage = [pscustomobject]@{
        policyPath = $ScopePolicyPath
        requiredScenarios = $requiredScenarios
        discoveredScenarios = $discoveredScenarioIds
        missingRequiredScenarios = $missingRequiredScenarios
        requiredScenarioCoveragePassed = ($missingRequiredScenarios.Count -eq 0)
    }
    workspaceCleanliness = if ($null -ne $reproducibilityReport) {
        [pscustomobject]@{
            gitCommit = $reproducibilityReport.git.commit
            dirty = [bool]$reproducibilityReport.git.dirty
            dirtyFileCount = [int]$reproducibilityReport.git.dirtyFileCount
            dirtyHash = $reproducibilityReport.git.dirtyHash
        }
    }
    else {
        $null
    }
    cacheMode = if ($null -ne $reproducibilityReport) { $reproducibilityReport.cache } else { $null }
    batchCoverage = @(
        "scenario-discovery",
        "ai-schema-validation",
        "normalization",
        "official-validation",
        "generation",
        "deterministic-generation",
        "command-policy",
        "controlled-build",
        "boot",
        "health",
        "smoke"
    )
    pendingForBatch7 = @()
    scenarios = $scenarioResults
}

$report | ConvertTo-Json -Depth 50 | Set-Content -LiteralPath $reportPathFull -Encoding UTF8

if ($overallStatus -eq "passed") {
    Write-Host ("AI beta gate passed. Report: " + $ReportPath)
    exit 0
}

Write-Error ("AI beta gate failed. Report: " + $ReportPath)
