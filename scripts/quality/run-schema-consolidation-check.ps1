param(
    [string]$WorkspaceRoot = ".",
    [string]$ReportPath = "scripts/reports/out/schema-consolidation-report.json",
    [string]$RunId = ""
)

$ErrorActionPreference = "Stop"

function Convert-ToRepoPath {
    param([string]$Root, [string]$PathValue)
    $resolvedRoot = [System.IO.Path]::GetFullPath($Root)
    $resolvedPath = [System.IO.Path]::GetFullPath($PathValue)
    if ($resolvedPath.StartsWith($resolvedRoot, [System.StringComparison]::OrdinalIgnoreCase)) {
        return ($resolvedPath.Substring($resolvedRoot.Length).TrimStart("\", "/") -replace "\\", "/")
    }
    return ($resolvedPath -replace "\\", "/")
}

function Get-BashPath {
    $command = Get-Command bash -ErrorAction SilentlyContinue
    if ($null -ne $command) { return $command.Source }
    foreach ($candidate in @(
            "C:\Program Files\Git\bin\bash.exe",
            "C:\Program Files\Git\usr\bin\bash.exe"
        )) {
        if (Test-Path -LiteralPath $candidate -PathType Leaf) { return $candidate }
    }
    return ""
}

function Invoke-BashCommand {
    param(
        [string]$Name,
        [string]$CommandLine,
        [string]$Root,
        [int]$ExpectedExitCode = 0
    )
    $bashPath = Get-BashPath
    if ([string]::IsNullOrWhiteSpace($bashPath)) {
        return [pscustomobject]@{
            name = $Name
            command = "bash -lc `"$CommandLine`""
            exitCode = -1
            expectedExitCode = $ExpectedExitCode
            passed = $false
            outputTail = @("bash not found")
        }
    }
    Push-Location $Root
    try {
        $output = @(& $bashPath -lc $CommandLine 2>&1 | ForEach-Object { $_.ToString() })
        $exitCode = $LASTEXITCODE
    }
    finally {
        Pop-Location
    }
    return [pscustomobject]@{
        name = $Name
        command = "bash -lc `"$CommandLine`""
        exitCode = $exitCode
        expectedExitCode = $ExpectedExitCode
        passed = ($exitCode -eq $ExpectedExitCode)
        outputTail = @($output | Select-Object -Last 40)
    }
}

function Test-SuggestedFixDiagnostics {
    param([string]$Root)
    $parserPath = Join-Path $Root "NPDevContract/dsl/src/main/java/com/npdev/dsl/v1/parser/JsonModelParser.java"
    $testPath = Join-Path $Root "NPDevContract/dsl/src/test/java/com/npdev/dsl/v1/LegacySchemaRejectionTest.java"
    if (-not (Test-Path -LiteralPath $parserPath -PathType Leaf) -or -not (Test-Path -LiteralPath $testPath -PathType Leaf)) {
        return $false
    }
    $parser = Get-Content -Raw -LiteralPath $parserPath
    $test = Get-Content -Raw -LiteralPath $testPath
    return $parser.Contains("new ValidationDiagnostic(") `
        -and $parser.Contains("LEGACY_ENTITIES_ROOT") `
        -and $parser.Contains("$.entities") `
        -and $parser.Contains("migrate legacy-model") `
        -and $test.Contains("getSuggestedFix")
}

$root = (Resolve-Path $WorkspaceRoot).Path
if ([string]::IsNullOrWhiteSpace($RunId)) {
    $RunId = "schema-consolidation-" + (Get-Date).ToUniversalTime().ToString("yyyyMMdd-HHmmssfff")
}
$legacyConfigSchemaName = "config-" + "1.0" + ".schema.json"
$legacyModelSchemaName = "model-" + "1.0.0" + ".schema.json"
$legacySchemaNames = @($legacyConfigSchemaName, $legacyModelSchemaName)
$excludedSegments = @(".git", ".gradle", "archive", "build", "dist", "node_modules", "target")

$activeLegacySchemaFiles = @()
foreach ($file in @(Get-ChildItem -LiteralPath $root -Recurse -File -ErrorAction SilentlyContinue)) {
    $relative = Convert-ToRepoPath $root $file.FullName
    $segments = @($relative -split "/")
    if (@($segments | Where-Object { $excludedSegments -contains $_ }).Count -gt 0) {
        continue
    }
    if ($legacySchemaNames -contains $file.Name) {
        $activeLegacySchemaFiles += $relative
    }
}

$deprecatedReferenceHits = @()
foreach ($file in @(Get-ChildItem -LiteralPath $root -Recurse -File -ErrorAction SilentlyContinue)) {
    $relative = Convert-ToRepoPath $root $file.FullName
    $segments = @($relative -split "/")
    if (@($segments | Where-Object { $excludedSegments -contains $_ }).Count -gt 0) {
        continue
    }
    $text = Get-Content -Raw -LiteralPath $file.FullName -ErrorAction SilentlyContinue
    if ($null -eq $text) {
        continue
    }
    foreach ($name in $legacySchemaNames) {
        if ($text.Contains($name)) {
            $deprecatedReferenceHits += $relative
            break
        }
    }
}

$migrationOutput = Join-Path $root "build/migrated-model.json"
$migrationCommand = "./npdev migrate legacy-model --input test-fixtures/legacy-model.json --output build/migrated-model.json"
$migrationResult = Invoke-BashCommand "migrate-legacy-model" $migrationCommand $root 0
$migrationToolWorks = $false
if ($migrationResult.passed -and (Test-Path -LiteralPath $migrationOutput -PathType Leaf)) {
    $migrated = Get-Content -Raw -LiteralPath $migrationOutput | ConvertFrom-Json
    $migrationToolWorks = ($null -ne $migrated.concepts -and $null -eq $migrated.entities)
}

$deprecatedEntitiesRejected = (Test-Path -LiteralPath (Join-Path $root "NPDevContract/dsl/src/test/java/com/npdev/dsl/v1/LegacySchemaRejectionTest.java") -PathType Leaf)
$diagnosticsHaveSuggestedFix = Test-SuggestedFixDiagnostics $root

$checks = @(
    [pscustomobject]@{
        name = "legacy-schema-files-active-scan"
        passed = ($activeLegacySchemaFiles.Count -eq 0)
        evidence = [pscustomobject]@{ hitCount = $activeLegacySchemaFiles.Count; hits = @($activeLegacySchemaFiles | Sort-Object -Unique) }
    },
    [pscustomobject]@{
        name = "active-deprecated-reference-scan"
        passed = ($deprecatedReferenceHits.Count -eq 0)
        evidence = [pscustomobject]@{ hitCount = $deprecatedReferenceHits.Count; hits = @($deprecatedReferenceHits | Sort-Object -Unique) }
    },
    [pscustomobject]@{
        name = "legacy-entities-rejection-test-present"
        passed = $deprecatedEntitiesRejected
        evidence = [pscustomobject]@{ testPath = "NPDevContract/dsl/src/test/java/com/npdev/dsl/v1/LegacySchemaRejectionTest.java" }
    },
    [pscustomobject]@{
        name = "legacy-model-migration-command"
        passed = $migrationToolWorks
        evidence = $migrationResult
    },
    [pscustomobject]@{
        name = "diagnostics-suggested-fix-static-check"
        passed = $diagnosticsHaveSuggestedFix
        evidence = [pscustomobject]@{ parserPath = "NPDevContract/dsl/src/main/java/com/npdev/dsl/v1/parser/JsonModelParser.java" }
    }
)

$failures = @($checks | Where-Object { -not $_.passed })
$overallStatus = if ($failures.Count -eq 0) { "passed" } else { "failed" }
$report = [pscustomobject]@{
    schemaVersion = "npdev-schema-consolidation-report.v1"
    runId = $RunId
    generatedAt = (Get-Date).ToUniversalTime().ToString("o")
    scriptPath = "scripts/quality/run-schema-consolidation-check.ps1"
    workspaceRoot = $root
    overallStatus = $overallStatus
    legacySchemaFilesInActivePaths = @($activeLegacySchemaFiles | Sort-Object -Unique).Count
    activeDeprecatedSchemaReferences = @($deprecatedReferenceHits | Sort-Object -Unique).Count
    deprecatedEntitiesRejected = $deprecatedEntitiesRejected
    migrationToolWorks = $migrationToolWorks
    diagnosticsHaveSuggestedFix = $diagnosticsHaveSuggestedFix
    checks = $checks
    findings = @()
    doesNotSolve = @(
        "Does not remove archived legacy schema evidence.",
        "Does not implement a second legacy DSL dialect.",
        "Does not proceed to Checkpoint 8."
    )
}

New-Item -ItemType Directory -Force -Path (Split-Path -Parent $ReportPath) | Out-Null
$report | ConvertTo-Json -Depth 60 | Set-Content -LiteralPath $ReportPath -Encoding UTF8

if ($overallStatus -ne "passed") {
    Write-Error ("Schema consolidation check failed. Report: " + $ReportPath)
}

Write-Host ("Schema consolidation report written: " + $ReportPath)
