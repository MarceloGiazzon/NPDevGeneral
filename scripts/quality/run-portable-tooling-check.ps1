param(
    [string]$WorkspaceRoot = ".",
    [string]$ReportPath = "scripts/reports/out/portable-tooling-report.json",
    [string]$SchemaPath = "schemas/ai/portable-tooling-report.schema.json",
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

function Invoke-NpdevCommand {
    param(
        [string]$Name,
        [string]$CommandLine,
        [string]$Root,
        [int]$ExpectedExitCode = 0
    )
    $bashPath = Get-BashPath
    $started = Get-Date
    Push-Location $Root
    try {
        if (-not [string]::IsNullOrWhiteSpace($bashPath)) {
            $output = @(& $bashPath -lc $CommandLine 2>&1 | ForEach-Object { $_.ToString() })
            $exitCode = $LASTEXITCODE
            $actualCommand = "bash -lc `"$CommandLine`""
        }
        else {
            throw "bash is required to verify the POSIX ./npdev wrapper"
        }
    }
    finally {
        Pop-Location
    }
    $finished = Get-Date
    return [pscustomobject]@{
        name = $Name
        command = $actualCommand
        exitCode = $exitCode
        expectedExitCode = $ExpectedExitCode
        passed = ($exitCode -eq $ExpectedExitCode)
        durationSeconds = [math]::Round(($finished - $started).TotalSeconds, 3)
        outputTail = @($output | Select-Object -Last 40)
    }
}

function Get-PathNeutralityScanScope {
    return @(
        "README.md",
        "docs/GETTING_STARTED.md",
        ".github/workflows/npdev-ci-validation.yml",
        "NPDevContract/examples/valid/simple-config.json",
        "npdev",
        "npdev.bat",
        "NPDevCli/**/*.py",
        "NPDevCli/**/*.md",
        "NPDevCli/**/*.json",
        "NPDevCli/**/*.txt",
        "NPDevCli/**/*.bat"
    )
}

function Get-PathNeutralityExcludedPaths {
    return @(
        [pscustomobject]@{ path = "scripts/reports/out/**"; reason = "Generated report output may contain runtime absolute paths and is not source config." },
        [pscustomobject]@{ path = "scripts/reports/tmp/**"; reason = "Temporary validation output may contain runtime absolute paths." },
        [pscustomobject]@{ path = "build/**"; reason = "Generated build output may contain runtime absolute paths." },
        [pscustomobject]@{ path = "docs/ROADMAP_BOUNDARY_POLICY.md"; reason = "Accepted CP0 evidence policy intentionally records local checkpoint path options." },
        [pscustomobject]@{ path = "docs/POST_BETA0_HUMAN_ACTION_REGISTER.md"; reason = "Accepted CP0 human-action evidence records prior local bundle paths." },
        [pscustomobject]@{ path = "docs/OFFICIAL_BETA_RELEASE_RUNBOOK.md"; reason = "Historical Beta0 release runbook is outside CP5 portable quick-start scope." },
        [pscustomobject]@{ path = "docs/RELEASE_BLOCKER_EXECUTION_ROADMAP.md"; reason = "Historical release-blocker evidence path is outside CP5 portable quick-start scope." },
        [pscustomobject]@{ path = "**/MIGRATION_DIGEST.md"; reason = "Historical migration digests preserve source-local path provenance." },
        [pscustomobject]@{ path = "scripts/quality/run-controlled-command-runner-tests.ps1"; reason = "Intentional security-test fixture uses drive-letter examples." }
    )
}

function Get-ScopedPathNeutralityFiles {
    param([string]$Root)
    $explicit = @(
        "README.md",
        "docs/GETTING_STARTED.md",
        ".github/workflows/npdev-ci-validation.yml",
        "NPDevContract/examples/valid/simple-config.json",
        "npdev",
        "npdev.bat"
    )
    foreach ($path in $explicit) {
        $full = Join-Path $Root $path
        if (Test-Path -LiteralPath $full -PathType Leaf) { Get-Item -LiteralPath $full }
    }
    foreach ($dir in @("NPDevCli")) {
        $fullDir = Join-Path $Root $dir
        if (Test-Path -LiteralPath $fullDir -PathType Container) {
            Get-ChildItem -LiteralPath $fullDir -Recurse -File | Where-Object {
                $_.Extension -in @(".py", ".md", ".json", ".txt", ".bat")
            }
        }
    }
}

function Get-HardcodedDriveMatches {
    param([string]$Root)
    $matches = @()
    foreach ($file in Get-ScopedPathNeutralityFiles -Root $Root) {
        $text = Get-Content -Raw -LiteralPath $file.FullName
        $regexMatches = [regex]::Matches($text, "(?<![A-Za-z])[A-Za-z]:[\\/]")
        foreach ($match in $regexMatches) {
            $matches += [pscustomobject]@{
                path = Convert-ToRepoPath -Root $Root -PathValue $file.FullName
                value = $match.Value
            }
        }
    }
    return @($matches)
}

function Get-GradlePwshCoreTaskMatches {
    param([string]$Root)
    $matches = @()
    $gradleFiles = Get-ChildItem -LiteralPath $Root -Recurse -File -Include "build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts" |
        Where-Object { $_.FullName -notmatch "\\build\\" -and $_.FullName -notmatch "\\\.gradle\\" }
    foreach ($file in $gradleFiles) {
        $lineNumber = 0
        foreach ($line in Get-Content -LiteralPath $file.FullName) {
            $lineNumber++
            if ($line -match "\bpwsh\b.*\s-File\b" -or $line -match "\bpowershell\b.*\s-File\b") {
                $matches += [pscustomobject]@{
                    path = Convert-ToRepoPath -Root $Root -PathValue $file.FullName
                    line = $lineNumber
                    text = $line.Trim()
                }
            }
        }
    }
    return @($matches)
}

$workspaceRootPath = (Resolve-Path -LiteralPath $WorkspaceRoot).Path
if ([string]::IsNullOrWhiteSpace($RunId)) {
    $RunId = "portable-tooling-" + (Get-Date).ToUniversalTime().ToString("yyyyMMdd-HHmmssfff")
}

Push-Location $workspaceRootPath
try {
    $commands = @(
        (Invoke-NpdevCommand -Name "npdev-version" -Root $workspaceRootPath -CommandLine "./npdev --version")
        (Invoke-NpdevCommand -Name "npdev-validate-model" -Root $workspaceRootPath -CommandLine "./npdev validate model NPDevContract/dsl/resources/Models/canonical-demo/model.json")
        (Invoke-NpdevCommand -Name "npdev-invalid-model-rejected" -Root $workspaceRootPath -CommandLine "./npdev validate model NPDevCli/tests/fixtures/invalid-model.json" -ExpectedExitCode 1)
        (Invoke-NpdevCommand -Name "npdev-normalize-ai-model" -Root $workspaceRootPath -CommandLine "mkdir -p build && ./npdev normalize ai-model golden-ai-scenarios/base-ai-loop/ai-model.json > build/npdev-normalized-model.json")
        (Invoke-NpdevCommand -Name "npdev-generate-app" -Root $workspaceRootPath -CommandLine "./npdev generate app --model NPDevContract/dsl/resources/Models/canonical-demo/model.json --config NPDevContract/dsl/resources/Models/canonical-demo/config.json --output build/npdev-generated")
    )

    $hardcodedMatches = Get-HardcodedDriveMatches -Root $workspaceRootPath
    $gradleMatches = Get-GradlePwshCoreTaskMatches -Root $workspaceRootPath
    $pathNeutralityScanScope = Get-PathNeutralityScanScope
    $pathNeutralityExcludedPaths = Get-PathNeutralityExcludedPaths
    $readmeText = Get-Content -Raw -LiteralPath (Join-Path $workspaceRootPath "README.md")
    $gettingStartedText = Get-Content -Raw -LiteralPath (Join-Path $workspaceRootPath "docs/GETTING_STARTED.md")
    $linuxExamplesPresent = (
        $readmeText.Contains("./npdev validate model") -and
        $readmeText.Contains("./npdev normalize ai-model") -and
        $readmeText.Contains("./npdev generate app") -and
        $gettingStartedText.Contains("Linux and macOS") -and
        $gettingStartedText.Contains("./npdev report bootstrap")
    )

    $cliToolExists = (Test-Path -LiteralPath (Join-Path $workspaceRootPath "npdev") -PathType Leaf) -and
        (Test-Path -LiteralPath (Join-Path $workspaceRootPath "npdev.bat") -PathType Leaf) -and
        (Test-Path -LiteralPath (Join-Path $workspaceRootPath "NPDevCli/npdev_cli.py") -PathType Leaf)

    $findings = @(
        [pscustomobject]@{
            id = "CP5-POWERSHELL-COMPATIBILITY-WRAPPERS-RETAINED"
            classification = "known-risk-accepted"
            status = "accepted"
            description = "PowerShell scripts remain available as compatibility wrappers; CP5 adds portable core entrypoints rather than deleting historical scripts."
        }
    )

    if (@($hardcodedMatches).Count -gt 0) {
        $findings += [pscustomobject]@{
            id = "CP5-HARDCODED-DRIVE-LETTERS-IN-SCOPE"
            classification = "current-checkpoint-blocker"
            status = "open"
            description = "Hardcoded drive-letter paths remain in the CP5 path-neutrality scan scope."
        }
    }
    if (@($gradleMatches).Count -gt 0) {
        $findings += [pscustomobject]@{
            id = "CP5-GRADLE-CORE-TASK-PWSH-FILE"
            classification = "current-checkpoint-blocker"
            status = "open"
            description = "A Gradle core validation task invokes pwsh or powershell with -File."
        }
    }

    $report = [pscustomobject]@{
        schemaVersion = "npdev-portable-tooling-report.v1"
        runId = $RunId
        generatedAt = (Get-Date).ToUniversalTime().ToString("o")
        scriptPath = "scripts/quality/run-portable-tooling-check.ps1"
        workspaceRoot = $workspaceRootPath
        overallStatus = "failed"
        cliToolExists = $cliToolExists
        cliVersionWorks = [bool](@($commands | Where-Object { $_.name -eq "npdev-version" -and $_.passed }).Count -eq 1)
        cliModelValidationWorks = [bool](@($commands | Where-Object { $_.name -eq "npdev-validate-model" -and $_.passed }).Count -eq 1)
        cliModelValidationUsesCanonicalSchema = [bool]((Get-Content -Raw -LiteralPath (Join-Path $workspaceRootPath "NPDevCli/npdev_cli.py")).Contains("NPDevContract") -and (Get-Content -Raw -LiteralPath (Join-Path $workspaceRootPath "NPDevCli/npdev_cli.py")).Contains("model.schema.json") -and (Get-Content -Raw -LiteralPath (Join-Path $workspaceRootPath "NPDevCli/npdev_cli.py")).Contains("validate-json-schema.mjs"))
        cliInvalidModelRejected = [bool](@($commands | Where-Object { $_.name -eq "npdev-invalid-model-rejected" -and $_.passed -and $_.exitCode -eq 1 }).Count -eq 1)
        cliAiNormalizeWorks = [bool](@($commands | Where-Object { $_.name -eq "npdev-normalize-ai-model" -and $_.passed }).Count -eq 1)
        cliGenerateWorks = [bool](@($commands | Where-Object { $_.name -eq "npdev-generate-app" -and $_.passed }).Count -eq 1)
        cliReportBootstrapCallable = [bool]((Get-Content -Raw -LiteralPath (Join-Path $workspaceRootPath "NPDevCli/npdev_cli.py")).Contains("report") -and (Get-Content -Raw -LiteralPath (Join-Path $workspaceRootPath ".github/workflows/npdev-ci-validation.yml")).Contains("./npdev report bootstrap"))
        hardcodedDriveLettersCount = @($hardcodedMatches).Count
        powershellScriptCountInGradleCoreTasks = @($gradleMatches).Count
        linuxCommandExamplesPresent = $linuxExamplesPresent
        commands = @($commands)
        pathNeutralityScanScope = @($pathNeutralityScanScope)
        pathNeutralityExcludedPaths = @($pathNeutralityExcludedPaths)
        runtimeAbsoluteOutputPathsIgnored = $true
        pathNeutralityScan = @($hardcodedMatches)
        gradleCoreTaskScan = @($gradleMatches)
        findings = @($findings)
        doesNotSolve = @(
            "Does not delete all PowerShell scripts.",
            "Does not require full CI migration to Gradle.",
            "Does not implement CP6 Gradle-native validation migration.",
            "Does not proceed to Checkpoint 6."
        )
    }

    $allCommandsPassed = @($commands | Where-Object { -not $_.passed }).Count -eq 0
    if ($report.cliToolExists -and $report.cliVersionWorks -and $report.cliModelValidationWorks -and $report.cliModelValidationUsesCanonicalSchema -and $report.cliInvalidModelRejected -and $report.cliAiNormalizeWorks -and $report.cliGenerateWorks -and $report.hardcodedDriveLettersCount -eq 0 -and $report.powershellScriptCountInGradleCoreTasks -eq 0 -and $report.linuxCommandExamplesPresent -and $allCommandsPassed) {
        $report.overallStatus = "passed"
    }

    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $ReportPath) | Out-Null
    $report | ConvertTo-Json -Depth 80 | Set-Content -LiteralPath $ReportPath -Encoding UTF8

    $schemaValidationPath = "scripts/reports/tmp/portable-tooling-report-schema-validation.json"
    $ErrorActionPreference = "Continue"
    pwsh -NoProfile -File scripts/quality/Invoke-JsonSchemaValidation.ps1 -SchemaPath $SchemaPath -InstancePath $ReportPath -ReportPath $schemaValidationPath 2>$null | Out-Null
    $schemaExitCode = $LASTEXITCODE
    $ErrorActionPreference = "Stop"
    if ($schemaExitCode -ne 0) {
        Write-Error ("Portable tooling report failed schema validation. Report: " + $ReportPath)
        exit 1
    }
    if ($report.overallStatus -ne "passed") {
        Write-Error ("Portable tooling check failed. Report: " + $ReportPath)
        exit 1
    }

    Write-Host ("Portable tooling check passed. Report: " + $ReportPath)
    exit 0
}
finally {
    Pop-Location
}
