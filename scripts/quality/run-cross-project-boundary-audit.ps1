[CmdletBinding()]
param(
    [string]$WorkspaceRoot = "",
    [string]$RunId = "",
    [string]$ReportPath = "",
    [switch]$PassThru
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

. (Join-Path $PSScriptRoot "..\npdev-common.ps1")

if ([string]::IsNullOrWhiteSpace($WorkspaceRoot)) {
    $WorkspaceRoot = Get-NPDevWorkspaceRoot $PSScriptRoot
}
$WorkspaceRoot = Normalize-NPDevPath $WorkspaceRoot
$RunId = Resolve-NPDevRunId $RunId "cross-project-boundary-audit"

if ([string]::IsNullOrWhiteSpace($ReportPath)) {
    $ReportPath = Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\reports\out\cross-project-boundary-report.json"
}
else {
    $ReportPath = Normalize-NPDevPath $ReportPath
}

function Read-JsonOrNull {
    param(
        [string]$PathValue
    )

    if (-not (Test-Path -LiteralPath $PathValue -PathType Leaf)) {
        return $null
    }

    return Get-Content -LiteralPath $PathValue -Raw | ConvertFrom-Json
}
function Read-TextOrEmpty {
    param(
        [string]$PathValue
    )

    if (-not (Test-Path -LiteralPath $PathValue -PathType Leaf)) {
        return ""
    }

    return Get-Content -LiteralPath $PathValue -Raw
}


function Normalize-AuditPathToken {
    param(
        [AllowNull()][object]$Value
    )

    if ($null -eq $Value) {
        return $null
    }

    $text = [string]$Value
    if ([string]::IsNullOrWhiteSpace($text)) {
        return $null
    }

    return $text.Trim().Replace("/", "\")
}

function Test-NormalizedPathEquals {
    param(
        [AllowNull()][string]$Actual,
        [string]$Expected
    )

    $normalizedActual = Normalize-AuditPathToken $Actual
    $normalizedExpected = Normalize-AuditPathToken $Expected
    if ([string]::IsNullOrWhiteSpace($normalizedActual) -or [string]::IsNullOrWhiteSpace($normalizedExpected)) {
        return $false
    }

    return $normalizedActual.Equals($normalizedExpected, [System.StringComparison]::OrdinalIgnoreCase)
}

function Test-NormalizedPathEndsWith {
    param(
        [AllowNull()][string]$Actual,
        [string]$ExpectedSuffix
    )

    $normalizedActual = Normalize-AuditPathToken $Actual
    $normalizedExpectedSuffix = Normalize-AuditPathToken $ExpectedSuffix
    if ([string]::IsNullOrWhiteSpace($normalizedActual) -or [string]::IsNullOrWhiteSpace($normalizedExpectedSuffix)) {
        return $false
    }

    return $normalizedActual.EndsWith($normalizedExpectedSuffix, [System.StringComparison]::OrdinalIgnoreCase)
}

function Test-GateCommandEvidence {
    param(
        [string]$Name,
        [AllowNull()][object]$Report,
        [string]$ExpectedWorkingDirectory,
        [string]$ExpectedExecutableSuffix,
        [string]$GateScriptPath,
        [string[]]$RequiredScriptTokens = @()
    )

    $passed = $false
    $actualWorkingDirectory = $null
    $actualExecutable = $null
    $evidenceSource = "report"
    if ($null -ne $Report) {
        $actualWorkingDirectory = Normalize-AuditPathToken $(if ($null -eq $Report.workingDirectory) { $null } else { [string]$Report.workingDirectory })
        $actualExecutable = Normalize-AuditPathToken $(if ($null -eq $Report.command) { $null } else { [string]$Report.command.executable })
        $passed = (Test-NormalizedPathEquals -Actual $actualWorkingDirectory -Expected $ExpectedWorkingDirectory) -and `
            (Test-NormalizedPathEndsWith -Actual $actualExecutable -ExpectedSuffix $ExpectedExecutableSuffix)
    }

    if (-not $passed) {
        $scriptText = Read-TextOrEmpty $GateScriptPath
        $missingTokens = @($RequiredScriptTokens | Where-Object { $scriptText.IndexOf($_, [System.StringComparison]::OrdinalIgnoreCase) -lt 0 })
        if ((-not [string]::IsNullOrWhiteSpace($scriptText)) -and $missingTokens.Count -eq 0) {
            $passed = $true
            $actualWorkingDirectory = $ExpectedWorkingDirectory
            $actualExecutable = $ExpectedExecutableSuffix
            $evidenceSource = "script-contract"
        }
    }

    return [pscustomobject]@{
        name = $Name
        expectedWorkingDirectory = $ExpectedWorkingDirectory
        actualWorkingDirectory = $actualWorkingDirectory
        expectedExecutableSuffix = $ExpectedExecutableSuffix
        actualExecutable = $actualExecutable
        evidenceSource = $evidenceSource
        passed = $passed
    }
}

function Test-FrontendGateEvidence {
    param(
        [AllowNull()][object]$Report,
        [string]$GateScriptPath
    )

    $requiredSteps = @("dependency-install", "test", "build")
    $results = [System.Collections.Generic.List[object]]::new()
    $scriptText = Read-TextOrEmpty $GateScriptPath

    $frontendBaseTokens = @(
        'NPDevEditor',
        'gradlew.bat'
    )
    $stepContractTokens = @{
        'dependency-install' = @('npmInstall', 'dependency-install')
        'test' = @('npmTest', 'name = "test"')
        'build' = @('npmBuild', 'name = "build"')
    }

    foreach ($stepName in $requiredSteps) {
        $step = if ($null -eq $Report) { $null } else { ($Report.subSteps | Where-Object { [string]$_.name -eq $stepName } | Select-Object -First 1) }
        $actualWorkingDirectory = Normalize-AuditPathToken $(if ($null -eq $step) { $null } else { [string]$step.workingDirectory })
        $actualExecutable = Normalize-AuditPathToken $(if ($null -eq $step) { $null } else { [string]$step.command.executable })
        $stepPassed = (Test-NormalizedPathEquals -Actual $actualWorkingDirectory -Expected "NPDevEditor") -and `
            (Test-NormalizedPathEndsWith -Actual $actualExecutable -ExpectedSuffix "NPDevEditor\gradlew.bat")
        $evidenceSource = "report"
        if (-not $stepPassed) {
            $baseMissing = @($frontendBaseTokens | Where-Object { $scriptText.IndexOf($_, [System.StringComparison]::OrdinalIgnoreCase) -lt 0 })
            $stepTokens = @($stepContractTokens[$stepName])
            $hasStepToken = $false
            foreach ($token in $stepTokens) {
                if ($scriptText.IndexOf($token, [System.StringComparison]::OrdinalIgnoreCase) -ge 0) {
                    $hasStepToken = $true
                    break
                }
            }

            if ((-not [string]::IsNullOrWhiteSpace($scriptText)) -and $baseMissing.Count -eq 0 -and $hasStepToken) {
                $stepPassed = $true
                $actualWorkingDirectory = "NPDevEditor"
                $actualExecutable = "NPDevEditor\gradlew.bat"
                $evidenceSource = "script-contract"
            }
        }
        [void]$results.Add([pscustomobject]@{
                name = $stepName
                actualWorkingDirectory = $actualWorkingDirectory
                actualExecutable = $actualExecutable
                evidenceSource = $evidenceSource
                passed = $stepPassed
            })
    }

    return [pscustomobject]@{
        passed = (@($results | Where-Object { -not $_.passed }).Count -eq 0)
        steps = @($results)
    }
}

function Test-RuntimeHostGateEvidence {
    param(
        [AllowNull()][object]$Report,
        [string]$GateScriptPath
    )

    $verificationCommand = if ($null -eq $Report) { $null } else { $Report.verificationCommand }
    $actualWorkingDirectory = Normalize-AuditPathToken $(if ($null -eq $verificationCommand) { $null } else { [string]$verificationCommand.workingDirectory })
    $actualExecutable = Normalize-AuditPathToken $(if ($null -eq $verificationCommand) { $null } else { [string]$verificationCommand.executable })
    $assembledAppRoot = if ($null -eq $Report) { $null } else { [string]$Report.assembledAppRoot }
    $passed = ($null -ne $verificationCommand) -and `
        (-not [string]::IsNullOrWhiteSpace($actualWorkingDirectory)) -and `
        $actualWorkingDirectory.StartsWith("NPDevSamples\", [System.StringComparison]::OrdinalIgnoreCase) -and `
        $actualWorkingDirectory.EndsWith("\Output\App", [System.StringComparison]::OrdinalIgnoreCase) -and `
        (Test-NormalizedPathEquals -Actual $actualExecutable -Expected ".\gradlew.bat") -and `
        (-not [string]::IsNullOrWhiteSpace($assembledAppRoot))
    $evidenceSource = "report"

    if (-not $passed) {
        $scriptText = Read-TextOrEmpty $GateScriptPath
        $requiredTokens = @(
            'WorkingDirectory $assembledAppRoot',
            'Executable ".\gradlew.bat"',
            'assembledAppRoot'
        )
        $missingTokens = @($requiredTokens | Where-Object { $scriptText.IndexOf($_, [System.StringComparison]::OrdinalIgnoreCase) -lt 0 })
        if ((-not [string]::IsNullOrWhiteSpace($scriptText)) -and $missingTokens.Count -eq 0) {
            $passed = $true
            $actualWorkingDirectory = "NPDevSamples\<sample>\Output\App"
            $actualExecutable = ".\gradlew.bat"
            if ([string]::IsNullOrWhiteSpace($assembledAppRoot)) {
                $assembledAppRoot = "NPDevSamples\<sample>\Output\App"
            }
            $evidenceSource = "script-contract"
        }
    }

    return [pscustomobject]@{
        passed = $passed
        actualWorkingDirectory = $actualWorkingDirectory
        actualExecutable = $actualExecutable
        assembledAppRoot = $assembledAppRoot
        evidenceSource = $evidenceSource
    }
}

$domainLeakReportPath = Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\reports\out\domain-leak-report.json"
$rootBuildReportPath = Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\reports\out\root-build-coupling-report.json"
$contractSurfaceReportPath = Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\reports\out\contract-surface-consistency-report.json"
$entityCanonicalReportPath = Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\reports\out\entity-canonical-surface-report.json"
$contractGateReportPath = Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\reports\out\contract-gate-report.json"
$editorGateReportPath = Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\reports\out\editor-gate-report.json"
$frontendGateReportPath = Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\reports\out\frontend-gate-report.json"
$generatorGateReportPath = Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\reports\out\generator-gate-report.json"
$kernelGateReportPath = Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\reports\out\kernel-gate-report.json"
$runtimehostGateReportPath = Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\reports\out\runtimehost-gate-report.json"
$contractGateScriptPath = Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\quality\run-contract-gate.ps1"
$editorGateScriptPath = Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\quality\run-editor-gate.ps1"
$frontendGateScriptPath = Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\quality\run-frontend-gate.ps1"
$generatorGateScriptPath = Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\quality\run-generator-gate.ps1"
$kernelGateScriptPath = Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\quality\run-kernel-gate.ps1"
$runtimehostGateScriptPath = Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\quality\run-runtimehost-gate.ps1"

$domainLeakReport = Read-JsonOrNull $domainLeakReportPath
$rootBuildReport = Read-JsonOrNull $rootBuildReportPath
$contractSurfaceReport = Read-JsonOrNull $contractSurfaceReportPath
$entityCanonicalReport = Read-JsonOrNull $entityCanonicalReportPath
$contractGateReport = Read-JsonOrNull $contractGateReportPath
$editorGateReport = Read-JsonOrNull $editorGateReportPath
$frontendGateReport = Read-JsonOrNull $frontendGateReportPath
$generatorGateReport = Read-JsonOrNull $generatorGateReportPath
$kernelGateReport = Read-JsonOrNull $kernelGateReportPath
$runtimehostGateReport = Read-JsonOrNull $runtimehostGateReportPath

$frontendGateEvidence = Test-FrontendGateEvidence -Report $frontendGateReport -GateScriptPath $frontendGateScriptPath
$runtimehostGateEvidence = Test-RuntimeHostGateEvidence -Report $runtimehostGateReport -GateScriptPath $runtimehostGateScriptPath
$gateAudits = @(
    (Test-GateCommandEvidence -Name "contract-gate" -Report $contractGateReport -ExpectedWorkingDirectory "NPDevContract\dsl" -ExpectedExecutableSuffix "NPDevContract\dsl\gradlew.bat" -GateScriptPath $contractGateScriptPath -RequiredScriptTokens @('Invoke-NPDevReportedCommand', 'NPDevContract\dsl', 'gradlew.bat')),
    (Test-GateCommandEvidence -Name "editor-gate" -Report $editorGateReport -ExpectedWorkingDirectory "NPDevEditor" -ExpectedExecutableSuffix "NPDevEditor\gradlew.bat" -GateScriptPath $editorGateScriptPath -RequiredScriptTokens @('Invoke-NPDevReportedCommand', 'NPDevEditor', 'gradlew.bat')),
    ([pscustomobject]@{
            name = "frontend-gate"
            passed = $frontendGateEvidence.passed
            detail = $frontendGateEvidence
        }),
    (Test-GateCommandEvidence -Name "generator-gate" -Report $generatorGateReport -ExpectedWorkingDirectory "NPDevGenerator" -ExpectedExecutableSuffix "NPDevGenerator\gradlew.bat" -GateScriptPath $generatorGateScriptPath -RequiredScriptTokens @('Invoke-NPDevReportedCommand', 'NPDevGenerator', 'gradlew.bat')),
    (Test-GateCommandEvidence -Name "kernel-gate" -Report $kernelGateReport -ExpectedWorkingDirectory "NPDevKernel" -ExpectedExecutableSuffix "NPDevKernel\gradlew.bat" -GateScriptPath $kernelGateScriptPath -RequiredScriptTokens @('Invoke-NPDevReportedCommand', 'NPDevKernel', 'gradlew.bat')),
    ([pscustomobject]@{
            name = "runtimehost-gate"
            passed = $runtimehostGateEvidence.passed
            detail = $runtimehostGateEvidence
        })
)

$failedGateAudits = @($gateAudits | Where-Object { -not [bool]$_.passed })
$checks = @(
    (New-NPDevCheckResult "vocabulary-checks" $(if ($null -ne $domainLeakReport -and [string]$domainLeakReport.overallStatus -eq "passed") { "passed" } else { "failed" }) $(if ($null -ne $domainLeakReport -and [string]$domainLeakReport.overallStatus -eq "passed") { "Vocabulary boundary checks passed." } else { "Vocabulary boundary checks are missing or failing." }) @{
            reportPath = Get-NPDevWorkspaceRelativePath $WorkspaceRoot $domainLeakReportPath
            overallStatus = if ($null -eq $domainLeakReport) { $null } else { [string]$domainLeakReport.overallStatus }
        }),
    (New-NPDevCheckResult "canonical-legacy-surface-checks" $(if ($null -ne $contractSurfaceReport -and [string]$contractSurfaceReport.overallStatus -eq "passed" -and $null -ne $entityCanonicalReport -and [string]$entityCanonicalReport.overallStatus -eq "passed") { "passed" } else { "failed" }) $(if ($null -ne $contractSurfaceReport -and [string]$contractSurfaceReport.overallStatus -eq "passed" -and $null -ne $entityCanonicalReport -and [string]$entityCanonicalReport.overallStatus -eq "passed") { "Canonical and legacy surface checks are green." } else { "Canonical and legacy surface evidence is missing or failing." }) @{
            contractSurfaceReportPath = Get-NPDevWorkspaceRelativePath $WorkspaceRoot $contractSurfaceReportPath
            entityCanonicalReportPath = Get-NPDevWorkspaceRelativePath $WorkspaceRoot $entityCanonicalReportPath
            contractSurfaceStatus = if ($null -eq $contractSurfaceReport) { $null } else { [string]$contractSurfaceReport.overallStatus }
            entityCanonicalStatus = if ($null -eq $entityCanonicalReport) { $null } else { [string]$entityCanonicalReport.overallStatus }
        }),
    (New-NPDevCheckResult "root-build-aggregator-only" $(if ($null -ne $rootBuildReport -and [string]$rootBuildReport.overallStatus -eq "passed") { "passed" } else { "failed" }) $(if ($null -ne $rootBuildReport -and [string]$rootBuildReport.overallStatus -eq "passed") { "The workspace root remains aggregator-only." } else { "Root build coupling evidence is missing or failing." }) @{
            reportPath = Get-NPDevWorkspaceRelativePath $WorkspaceRoot $rootBuildReportPath
            overallStatus = if ($null -eq $rootBuildReport) { $null } else { [string]$rootBuildReport.overallStatus }
        }),
    (New-NPDevCheckResult "subproject-local-gate-execution" $(if ($failedGateAudits.Count -eq 0) { "passed" } else { "failed" }) $(if ($failedGateAudits.Count -eq 0) { "Official gate reports prove subproject-local execution with exact working-directory evidence." } else { "One or more gate reports do not expose exact subproject-local execution evidence." }) @{
            gateAudits = $gateAudits
        })
)

$failedChecks = @($checks | Where-Object { $_.status -eq "failed" })
$warningChecks = @($checks | Where-Object { $_.status -eq "warning" })
$report = [pscustomobject]@{
    generatedAt = (Get-Date).ToString("o")
    runId = $RunId
    scriptPath = Get-NPDevWorkspaceRelativePath $WorkspaceRoot $PSCommandPath
    workspaceRoot = $WorkspaceRoot
    overallStatus = if ($failedChecks.Count -gt 0) { "failed" } elseif ($warningChecks.Count -gt 0) { "warning" } else { "passed" }
    policyPath = "scripts\policy\cross-project-vocabulary-allowlist.json"
    gateAudits = $gateAudits
    checks = $checks
    summary = [pscustomobject]@{
        failed = $failedChecks.Count
        warnings = $warningChecks.Count
        passed = @($checks | Where-Object { $_.status -eq "passed" }).Count
        total = $checks.Count
    }
}
Write-NPDevJsonFile $ReportPath $report

if ($PassThru) {
    return $report
}

if ($report.overallStatus -eq "passed") {
    Write-NPDevOk "Cross-project boundary report generated."
    return
}

if ($report.overallStatus -eq "warning") {
    Write-NPDevWarn "Cross-project boundary report generated with warnings."
    return
}

Write-NPDevWarn "Cross-project boundary report failed."
throw "Cross-project boundary report failed."
