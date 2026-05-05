param(
    [string]$SampleRoot = "NPDevSamples",
    [string]$PolicyPath = "scripts/policy/sample-matrix-policy.json",
    [string]$ReportPath = "scripts/reports/out/sample-matrix-report.json",
    [string]$RunId = ""
)

$ErrorActionPreference = "Stop"

function Read-JsonFile {
    param([string]$Path)
    return Get-Content -Raw -LiteralPath $Path | ConvertFrom-Json
}

function Add-Failure {
    param([System.Collections.Generic.List[string]]$Failures, [string]$Message)
    if (-not [string]::IsNullOrWhiteSpace($Message)) {
        $Failures.Add($Message) | Out-Null
    }
}

function Test-OfficialModelShape {
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
        }
    }
    return $failures
}

function Test-OfficialConfigShape {
    param([object]$Config, [object]$Policy)
    $failures = [System.Collections.Generic.List[string]]::new()
    if ($Config.configVersion -ne "1.0") { Add-Failure $failures "config.configVersion must be 1.0." }
    foreach ($required in @("scenario", "generator", "bootstrap", "artifact", "finalExec", "database", "runtime")) {
        if ($null -eq $Config.$required) { Add-Failure $failures ("config." + $required + " is required.") }
    }
    if ($null -ne $Config.database -and @($Policy.allowedDatabaseProviders) -notcontains [string]$Config.database.provider) {
        Add-Failure $failures ("config.database.provider is not allowed for sample matrix: " + [string]$Config.database.provider)
    }
    if ($null -ne $Config.runtime -and $Config.runtime.gradleTask -ne "bootRun") {
        Add-Failure $failures "config.runtime.gradleTask must be bootRun."
    }
    return $failures
}

function Invoke-OfficialSchemaValidation {
    param(
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
    if ($exitCode -eq 0 -and $null -ne $result -and [string]$result.status -eq "passed") {
        return @()
    }
    if ($null -ne $result) {
        return @($result.failures)
    }
    return @("JSON Schema validation did not write a result.")
}

function Get-SampleClassification {
    param([string]$SampleId, [object]$Policy)
    if (@($Policy.requiredBeta0Samples) -contains $SampleId) { return "beta0-required" }
    if (@($Policy.optionalBeta0Samples) -contains $SampleId) { return "beta0-optional" }
    if (@($Policy.postBeta0Samples) -contains $SampleId) { return "post-beta0" }
    return "unclassified"
}

$workspaceRoot = (Resolve-Path ".").Path
if ([string]::IsNullOrWhiteSpace($RunId)) {
    $RunId = "sample-matrix-" + (Get-Date).ToUniversalTime().ToString("yyyyMMdd-HHmmssfff")
}
$sampleRootPath = (Resolve-Path -LiteralPath $SampleRoot).Path
$policy = Read-JsonFile $PolicyPath
$catalogPath = Join-Path $sampleRootPath "sample-catalog.json"
$catalog = Read-JsonFile $catalogPath
$sampleResults = @()
$overallStatus = "passed"
$validationRoot = Join-Path $workspaceRoot "scripts/reports/tmp/sample-matrix-schema-validation"
if (Test-Path -LiteralPath $validationRoot) {
    Remove-Item -LiteralPath $validationRoot -Recurse -Force
}
New-Item -ItemType Directory -Force -Path $validationRoot | Out-Null

$negativeFixturePath = "scripts/tests/fixtures/sample-matrix-invalid-required/Input/model.json"
$negativeFixtureFailures = @(Invoke-OfficialSchemaValidation `
    -SchemaPath "NPDevContract/schemas/model.schema.json" `
    -JsonPath $negativeFixturePath `
    -ResultPath (Join-Path $validationRoot "negative-required-sample-model.json"))
if ($negativeFixtureFailures.Count -eq 0) {
    throw "Negative required-sample fixture unexpectedly passed official model schema validation."
}

foreach ($sample in @($catalog.samples | Sort-Object id)) {
    $failures = [System.Collections.Generic.List[string]]::new()
    $warnings = [System.Collections.Generic.List[string]]::new()
    $sampleId = [string]$sample.id
    $classification = Get-SampleClassification $sampleId $policy
    $inputRoot = Join-Path $sampleRootPath ([string]$sample.inputRoot)
    $isBlocking = $classification -eq "beta0-required"

    if (-not (Test-Path -LiteralPath $inputRoot -PathType Container)) {
        Add-Failure $failures "inputRoot is missing."
    }
    else {
        foreach ($file in @($policy.requiredInputFiles)) {
            $filePath = Join-Path $inputRoot ([string]$file)
            if (-not (Test-Path -LiteralPath $filePath -PathType Leaf)) {
                if ($isBlocking) {
                    Add-Failure $failures ("required file is missing: " + [string]$file)
                }
                else {
                    Add-Failure $warnings ("non-required sample file is missing: " + [string]$file)
                }
            }
        }

        $manifestPath = Join-Path $inputRoot "manifest.json"
        if (Test-Path -LiteralPath $manifestPath -PathType Leaf) {
            try {
                $manifest = Read-JsonFile $manifestPath
                if ([string]$manifest.id -ne $sampleId) {
                    Add-Failure $failures "manifest.id must match sample catalog id."
                }
            }
            catch {
                Add-Failure $failures ("manifest.json is not valid JSON: " + $_.Exception.Message)
            }
        }

        $modelPath = Join-Path $inputRoot "model.json"
        if (Test-Path -LiteralPath $modelPath -PathType Leaf) {
            try {
                foreach ($failure in @(Invoke-OfficialSchemaValidation `
                    -SchemaPath "NPDevContract/schemas/model.schema.json" `
                    -JsonPath $modelPath `
                    -ResultPath (Join-Path $validationRoot ($sampleId + "-model.json")))) {
                    Add-Failure $failures ("model schema: " + [string]$failure)
                }
            }
            catch {
                Add-Failure $failures ("model.json is not valid JSON: " + $_.Exception.Message)
            }
        }

        $configPath = Join-Path $inputRoot "config.json"
        if (Test-Path -LiteralPath $configPath -PathType Leaf) {
            try {
                foreach ($failure in @(Invoke-OfficialSchemaValidation `
                    -SchemaPath "NPDevContract/schemas/config.schema.json" `
                    -JsonPath $configPath `
                    -ResultPath (Join-Path $validationRoot ($sampleId + "-config.json")))) {
                    Add-Failure $failures ("config schema: " + [string]$failure)
                }
                $config = Read-JsonFile $configPath
                if ($null -ne $config.database -and @($policy.allowedDatabaseProviders) -notcontains [string]$config.database.provider) {
                    Add-Failure $failures ("config.database.provider is not allowed for sample matrix: " + [string]$config.database.provider)
                }
            }
            catch {
                Add-Failure $failures ("config.json is not valid JSON: " + $_.Exception.Message)
            }
        }
    }

    $sampleStatus = if ($failures.Count -eq 0) { "passed" } else { if ($isBlocking) { "failed" } else { "non-blocking-issues" } }
    if ($sampleStatus -eq "failed") { $overallStatus = "failed" }
    $sampleResults += [pscustomobject]@{
        sampleId = $sampleId
        classification = $classification
        status = $sampleStatus
        inputRoot = $inputRoot
        aiBetaScenarios = @($policy.aiBetaScenarioLinks.$sampleId)
        failures = @($failures)
        warnings = @($warnings)
    }
}

$report = [pscustomobject]@{
    schemaVersion = "npdev-sample-matrix-report.v1"
    runId = $RunId
    generatedAt = (Get-Date).ToUniversalTime().ToString("o")
    scriptPath = "scripts/quality/run-sample-matrix.ps1"
    workspaceRoot = $workspaceRoot
    sampleRoot = $sampleRootPath
    policyPath = $PolicyPath
    overallStatus = $overallStatus
    sampleCount = $sampleResults.Count
    requiredBeta0Samples = @($policy.requiredBeta0Samples)
    negativeFixture = [pscustomobject]@{
        path = $negativeFixturePath
        expectedStatus = "failed"
        failureCount = $negativeFixtureFailures.Count
    }
    samples = $sampleResults
}

$reportDirectory = Split-Path -Parent $ReportPath
if (-not [string]::IsNullOrWhiteSpace($reportDirectory)) {
    New-Item -ItemType Directory -Force -Path $reportDirectory | Out-Null
}
$report | ConvertTo-Json -Depth 30 | Set-Content -LiteralPath $ReportPath -Encoding UTF8

if ($overallStatus -eq "passed") {
    Write-Host ("Sample matrix passed. Report: " + $ReportPath)
    exit 0
}

Write-Error ("Sample matrix failed. Report: " + $ReportPath)
