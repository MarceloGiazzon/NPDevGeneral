param(
    [Parameter(Mandatory = $true)][string]$SchemaPath,
    [Parameter(Mandatory = $true)][Alias("JsonPath")][string]$InstancePath,
    [string]$ReportPath
)

$ErrorActionPreference = "Stop"

function Resolve-RepoPath {
    param([string]$PathValue)
    if ([System.IO.Path]::IsPathRooted($PathValue)) {
        return [System.IO.Path]::GetFullPath($PathValue)
    }
    return [System.IO.Path]::GetFullPath((Join-Path (Resolve-Path ".").Path $PathValue))
}

function Convert-ErrorsToFailures {
    param([object[]]$Errors)
    $failures = @()
    foreach ($errorItem in @($Errors)) {
        $failures += (([string]$errorItem.path) + " " + ([string]$errorItem.keyword) + ": " + ([string]$errorItem.message)).Trim()
    }
    return $failures
}

$schemaFullPath = Resolve-RepoPath $SchemaPath
$instanceFullPath = Resolve-RepoPath $InstancePath
$validatorRoot = Resolve-RepoPath "scripts/quality/json-schema-validator"
$validatorSourceScript = Join-Path $validatorRoot "validate-json-schema.mjs"
$validatorPackageJson = Join-Path $validatorRoot "package.json"
$validatorPackageLock = Join-Path $validatorRoot "package-lock.json"
$workspaceRoot = (Resolve-Path ".").Path
$workspace = Get-Item -LiteralPath $workspaceRoot
$outsideRepoRoot = Join-Path $workspace.Parent.FullName ($workspace.Name + "__OutsideRepo")
$validatorRuntimeRoot = Join-Path $outsideRepoRoot "node-tools\json-schema-validator"
$validatorScript = Join-Path $validatorRuntimeRoot "validate-json-schema.mjs"
$nodeModules = Join-Path $validatorRuntimeRoot "node_modules"
$dependencyFingerprintPath = Join-Path $validatorRuntimeRoot ".package-lock.sha256"

if (-not (Test-Path -LiteralPath $validatorSourceScript -PathType Leaf)) {
    throw "AJV validator wrapper is missing: $validatorSourceScript"
}
if (-not (Test-Path -LiteralPath $validatorPackageJson -PathType Leaf)) {
    throw "AJV validator package manifest is missing: $validatorPackageJson"
}

New-Item -ItemType Directory -Force -Path $validatorRuntimeRoot | Out-Null
Copy-Item -LiteralPath $validatorSourceScript -Destination $validatorScript -Force
Copy-Item -LiteralPath $validatorPackageJson -Destination (Join-Path $validatorRuntimeRoot "package.json") -Force
if (Test-Path -LiteralPath $validatorPackageLock -PathType Leaf) {
    Copy-Item -LiteralPath $validatorPackageLock -Destination (Join-Path $validatorRuntimeRoot "package-lock.json") -Force
}

$packageLockFingerprint = if (Test-Path -LiteralPath $validatorPackageLock -PathType Leaf) {
    (Get-FileHash -Algorithm SHA256 -LiteralPath $validatorPackageLock).Hash
}
else {
    (Get-FileHash -Algorithm SHA256 -LiteralPath $validatorPackageJson).Hash
}
$existingFingerprint = if (Test-Path -LiteralPath $dependencyFingerprintPath -PathType Leaf) {
    [string](Get-Content -LiteralPath $dependencyFingerprintPath -Raw).Trim()
}
else {
    ""
}

if (-not (Test-Path -LiteralPath $nodeModules -PathType Container) -or $existingFingerprint -ne $packageLockFingerprint) {
    npm --prefix $validatorRuntimeRoot install --silent | Out-Null
    if ($LASTEXITCODE -ne 0) {
        throw "Failed to install JSON Schema validator dependencies."
    }
    Set-Content -LiteralPath $dependencyFingerprintPath -Value $packageLockFingerprint -Encoding ASCII
}

$ErrorActionPreference = "Continue"
$validatorOutput = node $validatorScript --schema $schemaFullPath --instance $instanceFullPath
$validatorExit = $LASTEXITCODE
$ErrorActionPreference = "Stop"

$validatorReport = $null
try {
    $validatorReport = ($validatorOutput | Out-String) | ConvertFrom-Json
}
catch {
    $validatorReport = [pscustomobject]@{
        status = "failed"
        engine = "ajv"
        schemaPath = $schemaFullPath
        instancePath = $instanceFullPath
        errors = @([pscustomobject]@{
            path = "/"
            keyword = "validator"
            message = "Validator did not return JSON output."
            params = [pscustomobject]@{}
        })
    }
    $validatorExit = 1
}

$errors = @($validatorReport.errors)
$failures = Convert-ErrorsToFailures $errors
$status = if ($validatorExit -eq 0 -and [string]$validatorReport.status -eq "passed") { "passed" } else { "failed" }
$report = [pscustomobject]@{
    schemaVersion = "npdev-json-schema-validation-result.v1"
    generatedAt = (Get-Date).ToUniversalTime().ToString("o")
    scriptPath = "scripts/quality/Invoke-JsonSchemaValidation.ps1"
    status = $status
    engine = "ajv"
    engineVersion = [string]$validatorReport.engineVersion
    schemaPath = $schemaFullPath
    instancePath = $instanceFullPath
    jsonPath = $instanceFullPath
    errorCount = $errors.Count
    failureCount = $failures.Count
    errors = $errors
    failures = $failures
}

if (-not [string]::IsNullOrWhiteSpace($ReportPath)) {
    $reportFullPath = Resolve-RepoPath $ReportPath
    $directory = Split-Path -Parent $reportFullPath
    if (-not [string]::IsNullOrWhiteSpace($directory)) {
        New-Item -ItemType Directory -Force -Path $directory | Out-Null
    }
    $report | ConvertTo-Json -Depth 50 | Set-Content -LiteralPath $reportFullPath -Encoding UTF8
}

$report | ConvertTo-Json -Depth 50
if ($status -eq "passed") { exit 0 }
exit 1
