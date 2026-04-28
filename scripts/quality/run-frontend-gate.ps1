[CmdletBinding()]
param(
    [string]$WorkspaceRoot = "",
    [string]$RunId = "",
    [string]$ReportPath = ""
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

. (Join-Path $PSScriptRoot "..\npdev-common.ps1")

if ([string]::IsNullOrWhiteSpace($WorkspaceRoot)) {
    $WorkspaceRoot = Get-NPDevWorkspaceRoot $PSScriptRoot
}
$WorkspaceRoot = Normalize-NPDevPath $WorkspaceRoot
$RunId = Resolve-NPDevRunId $RunId "frontend-gate"

if ([string]::IsNullOrWhiteSpace($ReportPath)) {
    $ReportPath = Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\reports\out\frontend-gate-report.json"
}
else {
    $ReportPath = Normalize-NPDevPath $ReportPath
}

$projectRoot = Resolve-NPDevWorkspacePath $WorkspaceRoot "NPDevEditor"
$uiRoot = Join-Path $projectRoot "ui-react"
$packageJsonPath = Join-Path $uiRoot "package.json"
$packageLockPath = Join-Path $uiRoot "package-lock.json"
$buildGradlePath = Join-Path $projectRoot "build.gradle"
$gradleWrapperPath = Join-Path $projectRoot "gradlew.bat"
$frontendBoundaryAuditScript = Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\quality\run-frontend-boundary-audit.ps1"
$frontendBoundaryReportPath = Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\reports\out\frontend-boundary-report.json"

Ensure-NPDevDirectory $uiRoot "NPDevEditor React UI"
Ensure-NPDevFile $packageJsonPath "NPDevEditor package.json"
Ensure-NPDevFile $packageLockPath "NPDevEditor package-lock.json"
Ensure-NPDevFile $buildGradlePath "NPDevEditor build.gradle"
Ensure-NPDevFile $gradleWrapperPath "NPDevEditor Gradle wrapper"
Ensure-NPDevFile $frontendBoundaryAuditScript "Frontend boundary audit script"

function Get-FrontendFileFingerprint(
    [string]$PathValue
) {
    $item = Get-Item -LiteralPath $PathValue
    return [pscustomobject]@{
        path = Get-NPDevWorkspaceRelativePath $WorkspaceRoot $PathValue
        sha256 = (Get-FileHash -LiteralPath $PathValue -Algorithm SHA256).Hash.ToLowerInvariant()
        sizeBytes = $item.Length
    }
}

function Get-FrontendCommandVersion(
    [string]$ToolName,
    [string[]]$Arguments
) {
    $executable = "cmd.exe"
    $commandArguments = @("/d", "/c", $ToolName) + $Arguments
    try {
        $capture = Invoke-NPDevCommandCapture -WorkingDirectory $uiRoot -Executable $executable -Arguments $commandArguments
        $firstLine = @($capture.Output | Where-Object { -not [string]::IsNullOrWhiteSpace([string]$_) } | Select-Object -First 1)
        return [pscustomobject]@{
            tool = $ToolName
            available = $capture.ExitCode -eq 0
            exitCode = $capture.ExitCode
            versionText = if ($firstLine.Count -eq 0) { $null } else { [string]$firstLine[0] }
            error = $null
        }
    }
    catch {
        return [pscustomobject]@{
            tool = $ToolName
            available = $false
            exitCode = $null
            versionText = $null
            error = $_.Exception.Message
        }
    }
}

function Get-FrontendGeneratedResidue(
    [string]$RelativePath
) {
    $pathValue = Join-Path $uiRoot $RelativePath
    $exists = Test-Path -LiteralPath $pathValue
    $files = @()
    if ($exists) {
        $files = @(Get-ChildItem -LiteralPath $pathValue -Recurse -File -Force -ErrorAction SilentlyContinue)
    }
    $sizeBytes = 0
    if ($files.Count -gt 0) {
        $sizeMeasure = $files | Measure-Object -Property Length -Sum
        if ($null -ne $sizeMeasure -and $null -ne $sizeMeasure.Sum) {
            $sizeBytes = [int64]$sizeMeasure.Sum
        }
    }

    return [pscustomobject]@{
        path = Get-NPDevWorkspaceRelativePath $WorkspaceRoot $pathValue
        exists = [bool]$exists
        fileCount = $files.Count
        sizeBytes = $sizeBytes
    }
}

function Invoke-FrontendSubStep(
    [string]$Name,
    [string[]]$GradleTasks
) {
    $arguments = @($GradleTasks + @("--no-daemon", "--console=plain"))
    $startedAt = Get-Date
    $capture = $null
    $errorMessage = $null

    Write-NPDevInfo ("Running frontend sub-step: " + $Name)
    try {
        $capture = Invoke-NPDevCommandCapture -WorkingDirectory $projectRoot -Executable $gradleWrapperPath -Arguments $arguments
        foreach ($line in @($capture.Output)) {
            Write-Host $line
        }
    }
    catch {
        $errorMessage = $_.Exception.Message
    }

    $endedAt = Get-Date
    $exitCode = if ($null -eq $capture) { $null } else { [int]$capture.ExitCode }
    $outputLines = if ($null -eq $capture) { @() } else { @($capture.Output | ForEach-Object { [string]$_ }) }
    $status = if ([string]::IsNullOrWhiteSpace($errorMessage) -and $null -ne $exitCode -and $exitCode -eq 0) {
        "passed"
    }
    else {
        "failed"
    }

    $reasons = [System.Collections.Generic.List[string]]::new()
    if (-not [string]::IsNullOrWhiteSpace($errorMessage)) {
        [void]$reasons.Add($errorMessage)
    }
    if ($null -eq $exitCode) {
        [void]$reasons.Add("Frontend sub-step did not produce an exit code.")
    }
    elseif ($exitCode -ne 0) {
        [void]$reasons.Add("Frontend sub-step failed with exit code " + $exitCode + ".")
    }

    return [pscustomobject]@{
        name = $Name
        status = $status
        workingDirectory = Get-NPDevWorkspaceRelativePath $WorkspaceRoot $projectRoot
        command = [pscustomobject]@{
            executable = Get-NPDevWorkspaceRelativePath $WorkspaceRoot $gradleWrapperPath
            arguments = $arguments
            display = (Get-NPDevWorkspaceRelativePath $WorkspaceRoot $gradleWrapperPath) + " " + ($arguments -join " ")
            exitCode = $exitCode
            startedAt = $startedAt.ToString("o")
            endedAt = $endedAt.ToString("o")
            durationSeconds = [math]::Round(($endedAt - $startedAt).TotalSeconds, 1)
            outputLineCount = $outputLines.Count
            outputTail = @($outputLines | Select-Object -Last 120)
            failingTaskName = Get-NPDevGradleFailureTaskName $outputLines
            error = $errorMessage
        }
        failureReasons = @($reasons)
    }
}

function Invoke-FrontendBoundaryAuditSubStep {
    $startedAt = Get-Date
    $errorMessage = $null
    $boundaryReport = $null

    Write-NPDevInfo "Running frontend sub-step: boundary-audit"
    try {
        $boundaryReport = & $frontendBoundaryAuditScript `
            -WorkspaceRoot $WorkspaceRoot `
            -RunId ($RunId + "-boundary") `
            -ReportPath $frontendBoundaryReportPath `
            -PassThru
    }
    catch {
        $errorMessage = $_.Exception.Message
        if (Test-Path -LiteralPath $frontendBoundaryReportPath -PathType Leaf) {
            try {
                $boundaryReport = Get-Content -LiteralPath $frontendBoundaryReportPath -Raw | ConvertFrom-Json
            }
            catch {
                $boundaryReport = $null
            }
        }
    }

    $endedAt = Get-Date
    $status = if ($null -ne $boundaryReport -and [string]$boundaryReport.overallStatus -eq "passed" -and [string]::IsNullOrWhiteSpace($errorMessage)) {
        "passed"
    }
    else {
        "failed"
    }
    $failureReasons = [System.Collections.Generic.List[string]]::new()
    if (-not [string]::IsNullOrWhiteSpace($errorMessage)) {
        [void]$failureReasons.Add($errorMessage)
    }
    if ($status -eq "failed" -and $null -ne $boundaryReport) {
        [void]$failureReasons.Add("Frontend boundary audit returned status " + [string]$boundaryReport.overallStatus + ".")
    }

    return [pscustomobject]@{
        name = "boundary-audit"
        status = $status
        workingDirectory = Get-NPDevWorkspaceRelativePath $WorkspaceRoot $WorkspaceRoot
        command = [pscustomobject]@{
            executable = "pwsh"
            arguments = @(
                "-NoProfile",
                "-File",
                (Get-NPDevWorkspaceRelativePath $WorkspaceRoot $frontendBoundaryAuditScript)
            )
            display = "pwsh -NoProfile -File " + (Get-NPDevWorkspaceRelativePath $WorkspaceRoot $frontendBoundaryAuditScript)
            exitCode = if ($status -eq "passed") { 0 } else { 1 }
            startedAt = $startedAt.ToString("o")
            endedAt = $endedAt.ToString("o")
            durationSeconds = [math]::Round(($endedAt - $startedAt).TotalSeconds, 1)
            outputLineCount = 0
            outputTail = @()
            failingTaskName = $null
            error = $errorMessage
        }
        reportPath = Get-NPDevWorkspaceRelativePath $WorkspaceRoot $frontendBoundaryReportPath
        report = $boundaryReport
        failureReasons = @($failureReasons)
    }
}

$plannedSubSteps = @(
    @{ name = "dependency-install"; gradleTasks = @("clean", "npmInstall") },
    @{ name = "test"; gradleTasks = @("npmTest") },
    @{ name = "build"; gradleTasks = @("npmBuild") }
)
$subSteps = [System.Collections.Generic.List[object]]::new()
[void]$subSteps.Add((Invoke-FrontendBoundaryAuditSubStep))
foreach ($subStep in $plannedSubSteps) {
    if (@($subSteps | Where-Object { $_.status -eq "failed" }).Count -gt 0) {
        [void]$subSteps.Add([pscustomobject]@{
                name = [string]$subStep.name
                status = "skipped"
                workingDirectory = Get-NPDevWorkspaceRelativePath $WorkspaceRoot $projectRoot
                command = [pscustomobject]@{
                    executable = Get-NPDevWorkspaceRelativePath $WorkspaceRoot $gradleWrapperPath
                    arguments = @($subStep.gradleTasks + @("--no-daemon", "--console=plain"))
                    display = (Get-NPDevWorkspaceRelativePath $WorkspaceRoot $gradleWrapperPath) + " " + (@($subStep.gradleTasks + @("--no-daemon", "--console=plain")) -join " ")
                    exitCode = $null
                    startedAt = $null
                    endedAt = $null
                    durationSeconds = 0
                    outputLineCount = 0
                    outputTail = @()
                    failingTaskName = $null
                    error = "Skipped after earlier frontend sub-step failure."
                }
                failureReasons = @("Skipped after earlier frontend sub-step failure.")
            })
        continue
    }

    [void]$subSteps.Add((Invoke-FrontendSubStep -Name ([string]$subStep.name) -GradleTasks @($subStep.gradleTasks)))
}

$residue = @(
    "node_modules",
    "dist",
    "playwright-static",
    "test-results",
    "playwright-report",
    "coverage",
    ".vite",
    ".vitest"
) | ForEach-Object {
    Get-FrontendGeneratedResidue $_
}
$remainingResidue = @($residue | Where-Object { $_.exists })

$failedSubSteps = @($subSteps | Where-Object { $_.status -eq "failed" })
$skippedSubSteps = @($subSteps | Where-Object { $_.status -eq "skipped" })
$failureReasons = [System.Collections.Generic.List[string]]::new()
foreach ($failedSubStep in $failedSubSteps) {
    [void]$failureReasons.Add("Frontend sub-step failed: " + [string]$failedSubStep.name)
}
if ($remainingResidue.Count -gt 0 -and $failedSubSteps.Count -eq 0) {
    [void]$failureReasons.Add("Frontend gate left generated UI residue after Gradle finalizers.")
}

$status = if ($failureReasons.Count -eq 0) { "passed" } else { "failed" }

$report = [pscustomobject]@{
    generatedAt = (Get-Date).ToString("o")
    runId = $RunId
    scriptPath = Get-NPDevWorkspaceRelativePath $WorkspaceRoot $PSCommandPath
    workspaceRoot = $WorkspaceRoot
    overallStatus = $status
    phase = "frontend-reproducibility"
    uiRoot = Get-NPDevWorkspaceRelativePath $WorkspaceRoot $uiRoot
    boundaryAudit = [pscustomobject]@{
        reportPath = Get-NPDevWorkspaceRelativePath $WorkspaceRoot $frontendBoundaryReportPath
        overallStatus = if ($subSteps.Count -gt 0) { [string]$subSteps[0].status } else { "failed" }
    }
    inputs = [pscustomobject]@{
        packageJson = Get-FrontendFileFingerprint $packageJsonPath
        packageLock = Get-FrontendFileFingerprint $packageLockPath
        buildGradle = Get-FrontendFileFingerprint $buildGradlePath
    }
    toolchain = [pscustomobject]@{
        node = Get-FrontendCommandVersion "node" @("--version")
        npm = Get-FrontendCommandVersion "npm" @("--version")
        java = Get-FrontendCommandVersion "java" @("-version")
    }
    legacyAggregateCommand = [pscustomobject]@{
        display = (Get-NPDevWorkspaceRelativePath $WorkspaceRoot $gradleWrapperPath) + " npmTest npmBuild --no-daemon --console=plain"
        note = "Historical aggregate command reproduced by explicit install/test/build sub-steps for clearer evidence."
    }
    subSteps = @($subSteps)
    failingSubStep = if ($failedSubSteps.Count -eq 0) { $null } else { [string]$failedSubSteps[0].name }
    environment = [pscustomobject]@{
        gradleUserHome = Get-NPDevWorkspaceRelativePath $WorkspaceRoot (Resolve-NPDevWorkspacePath $WorkspaceRoot ".npdev-gradle")
        npmCache = Get-NPDevWorkspaceRelativePath $WorkspaceRoot (Join-Path $projectRoot "build\npm-cache")
        npmInstallMode = "npm ci --no-audit --fund=false --foreground-scripts"
    }
    cleanup = [pscustomobject]@{
        expectedGeneratedResidueCount = 0
        remainingGeneratedResidueCount = $remainingResidue.Count
        remainingGeneratedResidue = $remainingResidue
        checkedPaths = $residue
    }
    summary = [pscustomobject]@{
        failed = $failedSubSteps.Count
        skipped = $skippedSubSteps.Count
        passed = @($subSteps | Where-Object { $_.status -eq "passed" }).Count
        total = $subSteps.Count
    }
    failureReasons = @($failureReasons)
    error = if ($failureReasons.Count -eq 0) { $null } else { ($failureReasons -join " ") }
}
Write-NPDevJsonFile $ReportPath $report

if ($status -eq "passed") {
    Write-NPDevOk "NPDevEditor frontend gate passed."
    return
}

Write-NPDevWarn "NPDevEditor frontend gate failed."
throw $report.error
