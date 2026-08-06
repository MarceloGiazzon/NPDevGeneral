param(
    [string]$WorkspaceRoot = "",
    [string]$RunId = "",
    [string]$ReportPath = "scripts/reports/out/manager-harness-report.json",
    [string]$DockerExecutable = "docker",
    [string]$DockerfilePath = "scripts/quality/manager-harness/Dockerfile",
    [int]$BuildTimeoutSeconds = 1800,
    [int]$RunTimeoutSeconds = 900
)

# CLOSEOUT_PLAN.md I4 -- "the same instrument, one layer up" from
# scripts/quality/firstrun-harness: that harness proves the CLI's own README instructions work on
# a bare machine. This proves the MANAGER's install path works on a bare machine -- private JDK
# download+extract, Python resolution, `npdev doctor` green with no system Java -- mechanically,
# on every change, via `npdev-manager --selftest` (see NPDevManager/src/selftest.rs) instead of a
# human clicking through five screens.

$ErrorActionPreference = "Stop"

function Write-ManagerHarnessMessage {
    param([string]$Message)
    Write-Host ("[" + (Get-Date).ToString("HH:mm:ss") + "] manager-harness: " + $Message)
}

function Convert-ToRepoPath {
    param([string]$Root, [string]$PathValue)
    $full = [System.IO.Path]::GetFullPath($PathValue)
    $rootFull = [System.IO.Path]::GetFullPath($Root).TrimEnd([System.IO.Path]::DirectorySeparatorChar, [System.IO.Path]::AltDirectorySeparatorChar)
    if ($full.StartsWith($rootFull, [System.StringComparison]::OrdinalIgnoreCase)) {
        return $full.Substring($rootFull.Length).TrimStart([System.IO.Path]::DirectorySeparatorChar, [System.IO.Path]::AltDirectorySeparatorChar).Replace("\", "/")
    }
    return $full.Replace("\", "/")
}

function Invoke-LoggedCommand {
    param(
        [string]$Name,
        [string]$Executable,
        [string[]]$Arguments,
        [string]$WorkingDirectory,
        [int]$TimeoutSeconds,
        [string]$StdoutPath,
        [string]$StderrPath
    )
    $startedAt = (Get-Date).ToUniversalTime()
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $StdoutPath) | Out-Null
    Remove-Item -LiteralPath $StdoutPath, $StderrPath -Force -ErrorAction SilentlyContinue

    Write-ManagerHarnessMessage ("START " + $Name)
    $process = Start-Process -FilePath $Executable -ArgumentList $Arguments -WorkingDirectory $WorkingDirectory `
        -NoNewWindow -PassThru -RedirectStandardOutput $StdoutPath -RedirectStandardError $StderrPath
    $timedOut = -not $process.WaitForExit($TimeoutSeconds * 1000)
    $exitCode = $null
    if ($timedOut) {
        try { Stop-Process -Id $process.Id -Force -ErrorAction SilentlyContinue } catch {}
    } else {
        $exitCode = $process.ExitCode
    }
    $finishedAt = (Get-Date).ToUniversalTime()
    $durationSeconds = [int]([DateTimeOffset]$finishedAt - [DateTimeOffset]$startedAt).TotalSeconds
    $status = if (-not $timedOut -and $exitCode -eq 0) { "passed" } else { "failed" }
    Write-ManagerHarnessMessage ("END   " + $Name + " => " + $status + " (exit " + $exitCode + ", " + $durationSeconds + "s)")
    return [pscustomobject]@{
        name = $Name
        command = ($Executable + " " + ($Arguments -join " "))
        exitCode = $exitCode
        timedOut = $timedOut
        durationSeconds = $durationSeconds
        status = $status
        stdoutPath = $StdoutPath
        stderrPath = $StderrPath
        stdoutTail = @(if (Test-Path -LiteralPath $StdoutPath) { Get-Content -LiteralPath $StdoutPath -Tail 60 })
        stderrTail = @(if (Test-Path -LiteralPath $StderrPath) { Get-Content -LiteralPath $StderrPath -Tail 60 })
    }
}

if ([string]::IsNullOrWhiteSpace($WorkspaceRoot)) {
    $WorkspaceRoot = (Resolve-Path ".").Path
}
$workspaceRoot = [System.IO.Path]::GetFullPath($WorkspaceRoot)
if ([string]::IsNullOrWhiteSpace($RunId)) {
    $RunId = "manager-harness-" + (Get-Date).ToUniversalTime().ToString("yyyyMMdd-HHmmssfff")
}
$safeRunId = ($RunId.ToLowerInvariant() -replace '[^a-z0-9_.-]', '-')
$imageTag = "npdev-manager-harness:" + $safeRunId
$reportPathFull = [System.IO.Path]::GetFullPath((Join-Path $workspaceRoot $ReportPath))
$logRoot = Join-Path (Split-Path -Parent $reportPathFull) "manager-harness"
New-Item -ItemType Directory -Force -Path $logRoot | Out-Null

Write-ManagerHarnessMessage ("Starting. RunId: " + $RunId + " Image: " + $imageTag)

$commands = @()
$failures = @()

$build = Invoke-LoggedCommand -Name "docker-build" -Executable $DockerExecutable `
    -Arguments @("build", "-f", $DockerfilePath, "-t", $imageTag, ".") `
    -WorkingDirectory $workspaceRoot -TimeoutSeconds $BuildTimeoutSeconds `
    -StdoutPath (Join-Path $logRoot "docker-build.stdout.log") -StderrPath (Join-Path $logRoot "docker-build.stderr.log")
$commands += $build
if ($build.status -ne "passed") {
    $failures += "docker build failed (exit $($build.exitCode), timedOut=$($build.timedOut))"
}

if ($failures.Count -eq 0) {
    $run = Invoke-LoggedCommand -Name "docker-run-selftest" -Executable $DockerExecutable `
        -Arguments @("run", "--rm", "--label", ("npdev.runId=" + $safeRunId), $imageTag) `
        -WorkingDirectory $workspaceRoot -TimeoutSeconds $RunTimeoutSeconds `
        -StdoutPath (Join-Path $logRoot "docker-run.stdout.log") -StderrPath (Join-Path $logRoot "docker-run.stderr.log")
    $commands += $run
    if ($run.status -ne "passed") {
        $failures += "container --selftest failed (exit $($run.exitCode), timedOut=$($run.timedOut))"
    }
}

$overallStatus = if ($failures.Count -eq 0) { "passed" } else { "failed" }
$report = [pscustomobject]@{
    schemaVersion = "npdev-manager-harness-report.v1"
    runId = $RunId
    generatedAt = (Get-Date).ToUniversalTime().ToString("o")
    scriptPath = "scripts/quality/run-manager-harness.ps1"
    workspaceRoot = $workspaceRoot
    overallStatus = $overallStatus
    imageTag = $imageTag
    dockerfile = $DockerfilePath
    commands = @($commands)
    failures = @($failures)
}
New-Item -ItemType Directory -Force -Path (Split-Path -Parent $reportPathFull) | Out-Null
$report | ConvertTo-Json -Depth 20 | Set-Content -LiteralPath $reportPathFull -Encoding UTF8

if ($overallStatus -eq "passed") {
    Write-Host ("OK    Manager harness passed. Report: " + (Convert-ToRepoPath -Root $workspaceRoot -PathValue $reportPathFull))
    exit 0
}
Write-Error ("Manager harness failed. Report: " + (Convert-ToRepoPath -Root $workspaceRoot -PathValue $reportPathFull))
