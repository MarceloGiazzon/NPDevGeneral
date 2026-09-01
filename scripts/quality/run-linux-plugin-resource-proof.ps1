param(
    [string]$WorkspaceRoot = "",
    [string]$RunId = "",
    [string]$ReportPath = "scripts/reports/out/linux-plugin-resource-proof-report.json",
    [string]$DockerExecutable = "docker",
    [string]$DockerfilePath = "Dockerfile.linux-plugin-proof",
    [string]$ImageTag = "npdev-linux-plugin-proof:latest",
    [string]$GradleCacheVolume = "npdev-linux-plugin-proof-cache",
    [string]$BuildVolume = "npdev-linux-plugin-proof-build",
    [int]$BuildTimeoutSeconds = 1800,
    [int]$RunTimeoutSeconds = 3600,
    [int]$ProgressIntervalSeconds = 30,
    [switch]$Force
)

# SEC-3 fork (a): prove, on a REAL Linux kernel, that PluginLinuxCgroupResourceLimiter's cgroup v2
# ceiling actually kills a plugin child process that exceeds its memory limit. Windows already has
# that proof (PluginIpcChildProcessWindowsResourceLimitTest, live-fired via a Job Object); this is
# the Linux half, and until it runs for real the design doc's own bar -- "proving out on only one
# OS is not done" -- is not met.
#
# Modelled on scripts/quality/run-docker-linux-proof.ps1, whose conventions this deliberately
# copies (the helper functions below are near-verbatim, log-prefix renamed):
#   * honours scripts/policy/local-test-profile.json -- Docker is OFF for local/agent work by
#     default, so this SKIPS cleanly (exit 0) unless -Force or CI=true;
#   * a Docker daemon in Windows-container mode, or unreachable on Windows, is
#     "skipped-not-applicable" and exits 3, NEVER 0 -- a proof that never ran must never be
#     readable as a proof that passed;
#   * writes one JSON report with every command, exit code, duration and log path.
#
# NOT wired into run-all-gates.ps1 by default: it needs a Docker daemon, which the local test
# profile turns off. Its home is (a) a maintainer running it by hand with -Force, and (b) an
# optional future ubuntu-latest CI job (plan step A9, not added by this script).

$ErrorActionPreference = "Stop"

function Write-LinuxPluginProofMessage {
    param([string]$Message)
    Write-Host ("[" + (Get-Date).ToString("HH:mm:ss") + "] linux-plugin-proof: " + $Message)
}

function Resolve-UnderRoot {
    param([string]$Root, [string]$PathValue)
    if ([System.IO.Path]::IsPathRooted($PathValue)) {
        return [System.IO.Path]::GetFullPath($PathValue)
    }
    return [System.IO.Path]::GetFullPath((Join-Path $Root $PathValue))
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

function Add-Failure {
    param([string]$Code, [string]$Message, [string]$Path = "", [object]$Details = $null)
    $script:failures += [pscustomobject]@{
        code = $Code
        message = $Message
        path = $Path
        details = $Details
    }
}

function Get-LogTail {
    param([string]$PathValue, [int]$LineCount = 120)
    if (-not (Test-Path -LiteralPath $PathValue -PathType Leaf)) {
        return @()
    }
    return @(Get-Content -LiteralPath $PathValue -Tail $LineCount | ForEach-Object { [string]$_ })
}

function Join-ProcessArguments {
    param([string[]]$Arguments)
    return (@($Arguments) | ForEach-Object {
            $argument = [string]$_
            if ($argument -match '[\s"]') {
                '"' + ($argument -replace '"', '\"') + '"'
            }
            else {
                $argument
            }
        }) -join " "
}

function New-Artifact {
    param([string]$Type, [string]$PathValue)
    $exists = Test-Path -LiteralPath $PathValue -PathType Leaf
    return [pscustomobject]@{
        type = $Type
        path = Convert-ToRepoPath -Root $workspaceRoot -PathValue $PathValue
        exists = $exists
        sizeBytes = if ($exists) { [int64](Get-Item -LiteralPath $PathValue).Length } else { $null }
        sha256 = if ($exists) { (Get-FileHash -Algorithm SHA256 -LiteralPath $PathValue).Hash.ToLowerInvariant() } else { $null }
    }
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
    $exitCode = $null
    $timedOut = $false
    $errorMessage = $null
    $progressIntervalSeconds = [Math]::Max(1, $ProgressIntervalSeconds)
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $StdoutPath) | Out-Null
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $StderrPath) | Out-Null
    Remove-Item -LiteralPath $StdoutPath, $StderrPath -Force -ErrorAction SilentlyContinue

    try {
        $argumentLine = Join-ProcessArguments $Arguments
        Write-LinuxPluginProofMessage ("START " + $Name + " -> " + $Executable + " " + $argumentLine)
        Write-LinuxPluginProofMessage ("Logs: " + (Convert-ToRepoPath -Root $workspaceRoot -PathValue $StdoutPath) + " | " + (Convert-ToRepoPath -Root $workspaceRoot -PathValue $StderrPath))
        $process = Start-Process `
            -FilePath $Executable `
            -ArgumentList $argumentLine `
            -WorkingDirectory $WorkingDirectory `
            -NoNewWindow `
            -PassThru `
            -RedirectStandardOutput $StdoutPath `
            -RedirectStandardError $StderrPath
        while ($true) {
            $elapsedBeforeWaitSeconds = [int]([DateTimeOffset](Get-Date).ToUniversalTime() - [DateTimeOffset]$startedAt).TotalSeconds
            $remainingSeconds = [Math]::Max(1, $TimeoutSeconds - $elapsedBeforeWaitSeconds)
            $waitSeconds = [Math]::Min($progressIntervalSeconds, $remainingSeconds)
            if ($process.WaitForExit($waitSeconds * 1000)) {
                $exitCode = [int]$process.ExitCode
                break
            }

            $elapsedSeconds = [int]([DateTimeOffset](Get-Date).ToUniversalTime() - [DateTimeOffset]$startedAt).TotalSeconds
            if ($elapsedSeconds -ge $TimeoutSeconds) {
                $timedOut = $true
                try {
                    Stop-Process -Id $process.Id -Force -ErrorAction SilentlyContinue
                }
                catch {
                }
                $exitCode = $null
                $errorMessage = "Command timed out after " + $TimeoutSeconds + " second(s)."
                Write-LinuxPluginProofMessage ("TIMEOUT " + $Name + " after " + $elapsedSeconds + "s")
                break
            }

            $tail = @(Get-LogTail -PathValue $StderrPath -LineCount 3)
            if ($tail.Count -eq 0) {
                $tail = @(Get-LogTail -PathValue $StdoutPath -LineCount 3)
            }
            $lastLine = if ($tail.Count -gt 0) { [string]$tail[$tail.Count - 1] } else { "no log output yet" }
            Write-LinuxPluginProofMessage ("RUNNING " + $Name + " (" + $elapsedSeconds + "s elapsed, timeout " + $TimeoutSeconds + "s). Last log: " + $lastLine)
        }
    }
    catch {
        $exitCode = $null
        $errorMessage = $_.Exception.Message
    }

    $finishedAt = (Get-Date).ToUniversalTime()
    $status = if (-not $timedOut -and [string]::IsNullOrWhiteSpace($errorMessage) -and $null -ne $exitCode -and $exitCode -eq 0) { "passed" } else { "failed" }
    $durationSeconds = [int]([DateTimeOffset]$finishedAt - [DateTimeOffset]$startedAt).TotalSeconds
    Write-LinuxPluginProofMessage ("END   " + $Name + " => " + $status + " (exit " + $exitCode + ", " + $durationSeconds + "s)")
    return [pscustomobject]@{
        name = $Name
        executable = $Executable
        arguments = @($Arguments)
        command = ($Executable + " " + (Join-ProcessArguments $Arguments))
        workingDirectory = Convert-ToRepoPath -Root $workspaceRoot -PathValue $WorkingDirectory
        timeoutSeconds = $TimeoutSeconds
        timedOut = $timedOut
        exitCode = $exitCode
        status = $status
        startedAt = $startedAt.ToString("o")
        finishedAt = $finishedAt.ToString("o")
        durationSeconds = $durationSeconds
        stdoutPath = Convert-ToRepoPath -Root $workspaceRoot -PathValue $StdoutPath
        stderrPath = Convert-ToRepoPath -Root $workspaceRoot -PathValue $StderrPath
        stdoutTail = Get-LogTail -PathValue $StdoutPath
        stderrTail = Get-LogTail -PathValue $StderrPath
        error = $errorMessage
    }
}

if ([string]::IsNullOrWhiteSpace($WorkspaceRoot)) {
    $WorkspaceRoot = (Resolve-Path ".").Path
}
$workspaceRoot = [System.IO.Path]::GetFullPath($WorkspaceRoot)

if ($env:CI -ne "true" -and -not $Force) {
    $localProfilePath = Join-Path $workspaceRoot "scripts\policy\local-test-profile.json"
    $localProfile = Get-Content $localProfilePath -Raw | ConvertFrom-Json
    $dockerAllowed = ($localProfile.enabledEngines -contains "postgres") -or ($localProfile.enabledEngines -contains "mysql")
    if (-not $dockerAllowed) {
        Write-LinuxPluginProofMessage "SKIPPED: disabled by scripts/policy/local-test-profile.json (pass -Force to override, or add postgres/mysql to enabledEngines)"
        exit 0
    }
}

if ([string]::IsNullOrWhiteSpace($RunId)) {
    $RunId = "linux-plugin-proof-" + (Get-Date).ToUniversalTime().ToString("yyyyMMdd-HHmmssfff")
}
Write-LinuxPluginProofMessage ("Starting. RunId: " + $RunId)
Write-LinuxPluginProofMessage ("Workspace: " + $workspaceRoot)

$script:failures = @()
$commands = @()
$artifacts = @()
$reportPathFull = Resolve-UnderRoot -Root $workspaceRoot -PathValue $ReportPath
$dockerfileFull = Resolve-UnderRoot -Root $workspaceRoot -PathValue $DockerfilePath
$logRoot = Join-Path (Split-Path -Parent $reportPathFull) "linux-plugin-resource-proof"
New-Item -ItemType Directory -Force -Path $logRoot | Out-Null

if (-not (Test-Path -LiteralPath $dockerfileFull -PathType Leaf)) {
    Add-Failure -Code "dockerfile-missing" -Message "Linux plugin proof Dockerfile is missing." -Path (Convert-ToRepoPath -Root $workspaceRoot -PathValue $dockerfileFull)
}

$version = Invoke-LoggedCommand `
    -Name "docker-version" `
    -Executable $DockerExecutable `
    -Arguments @("version") `
    -WorkingDirectory $workspaceRoot `
    -TimeoutSeconds 60 `
    -StdoutPath (Join-Path $logRoot "docker-version.stdout.log") `
    -StderrPath (Join-Path $logRoot "docker-version.stderr.log")
$commands += $version

# Same not-equipped-for-this-runner reasoning as run-docker-linux-proof.ps1 (QUAL-36 follow-up):
# a Windows runner/daemon can report either an unreachable daemon or a Windows-container daemon.
# Neither is a product defect; both are a category error this proof cannot run through.
$stderrJoined = ($version.stderrTail -join "`n")
$daemonUnreachableOnWindows = $IsWindows -and [string]$version.status -ne "passed" -and
    ($stderrJoined -match "failed to connect to the docker API|if the daemon is running|[Cc]annot connect to the Docker daemon")
if ([string]$version.status -ne "passed" -and -not $daemonUnreachableOnWindows) {
    Add-Failure -Code "docker-version-failed" -Message "Docker CLI/version probe failed." -Path $DockerExecutable -Details @{ exitCode = $version.exitCode; timedOut = $version.timedOut; stdoutPath = $version.stdoutPath; stderrPath = $version.stderrPath }
}

$dockerServerOs = ""
if ([string]$version.status -eq "passed") {
    $ErrorActionPreference = "Continue"
    $dockerServerOs = (& $DockerExecutable version --format "{{.Server.Os}}" 2>$null | Out-String).Trim()
    $ErrorActionPreference = "Stop"
}
if ($failures.Count -eq 0 -and (($dockerServerOs -and $dockerServerOs -ne "linux") -or $daemonUnreachableOnWindows)) {
    $skipReason = if ($daemonUnreachableOnWindows) {
        ("Docker daemon is not reachable on this Windows host (" + $stderrJoined + "); this proof " +
         "builds a Linux image (Dockerfile.linux-plugin-proof: FROM debian:12-slim) and needs a " +
         "running Linux daemon.")
    }
    else {
        ("Docker daemon is in '" + $dockerServerOs + "'-container mode; this proof builds a Linux image " +
                   "(Dockerfile.linux-plugin-proof: FROM debian:12-slim) and cannot run here.")
    }
    Write-LinuxPluginProofMessage ("SKIPPED (not applicable): " + $skipReason)
    $skipReport = [pscustomobject]@{
        schemaVersion = "npdev-linux-plugin-resource-proof-report.v1"
        runId         = $RunId
        generatedAt   = (Get-Date).ToUniversalTime().ToString("o")
        scriptPath    = "scripts/quality/run-linux-plugin-resource-proof.ps1"
        workspaceRoot = $workspaceRoot
        overallStatus = "skipped"
        skipped       = $true
        skipReason    = $skipReason
        dockerServerOs = $dockerServerOs
        platform      = [pscustomobject]@{
            hostOS      = [System.Runtime.InteropServices.RuntimeInformation]::OSDescription
            proofTarget = "linux-container"
            dockerfile  = Convert-ToRepoPath -Root $workspaceRoot -PathValue $dockerfileFull
            imageTag    = $ImageTag
        }
        timeoutPolicy = [pscustomobject]@{
            dockerVersionTimeoutSeconds = 60
            dockerBuildTimeoutSeconds   = $BuildTimeoutSeconds
            dockerRunTimeoutSeconds     = $RunTimeoutSeconds
        }
        commands  = @($commands)
        exitCodes = @($commands | ForEach-Object { [pscustomobject]@{ name = $_.name; exitCode = $_.exitCode; status = $_.status; timedOut = $_.timedOut } })
        durations = [pscustomobject]@{
            totalCommandDurationSeconds = [int](($commands | Measure-Object -Property durationSeconds -Sum).Sum)
            commands = @($commands | ForEach-Object { [pscustomobject]@{ name = $_.name; durationSeconds = $_.durationSeconds } })
        }
        artifacts = @($artifacts)
        cgroupEnvironmentBefore = $null
        cgroupEnvironmentAfter = $null
        failures  = @()
    }
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $reportPathFull) | Out-Null
    $skipReport | ConvertTo-Json -Depth 50 | Set-Content -LiteralPath $reportPathFull -Encoding UTF8
    Write-Host ("Linux plugin resource proof SKIPPED (not applicable on a " + $dockerServerOs + " daemon). Report: " +
                (Convert-ToRepoPath -Root $workspaceRoot -PathValue $reportPathFull))
    exit 3
}

if ($failures.Count -eq 0) {
    $build = Invoke-LoggedCommand `
        -Name "docker-build" `
        -Executable $DockerExecutable `
        -Arguments @("build", "-f", (Convert-ToRepoPath -Root $workspaceRoot -PathValue $dockerfileFull), "-t", $ImageTag, ".") `
        -WorkingDirectory $workspaceRoot `
        -TimeoutSeconds $BuildTimeoutSeconds `
        -StdoutPath (Join-Path $logRoot "docker-build.stdout.log") `
        -StderrPath (Join-Path $logRoot "docker-build.stderr.log")
    $commands += $build
    if ([string]$build.status -ne "passed") {
        Add-Failure -Code "docker-build-failed" -Message "Docker image build failed." -Path (Convert-ToRepoPath -Root $workspaceRoot -PathValue $dockerfileFull) -Details @{ exitCode = $build.exitCode; timedOut = $build.timedOut; stdoutPath = $build.stdoutPath; stderrPath = $build.stderrPath }
    }
}

if ($failures.Count -eq 0) {
    # THE FLAGS ARE THE POINT. --privileged and --cgroupns=private are what make /sys/fs/cgroup
    # writable and container-private; without BOTH, cgroup-delegate-init.sh exits 64 and this is a
    # setup failure, not a product verdict. The container command is ONE bash -lc string with no
    # nested quoting: run-live-fire.sh (a real script file) is what cgroup-delegate-init.sh execs,
    # not a second inline shell string.
    $containerCommand = "find . -name gradlew -type f -exec chmod +x '{}' + && " +
        "chmod +x scripts/quality/linux-plugin-proof/*.sh && " +
        "scripts/quality/linux-plugin-proof/probe-cgroup-environment.sh > /npdev-build/cgroup-before.json; " +
        "exec scripts/quality/linux-plugin-proof/cgroup-delegate-init.sh scripts/quality/linux-plugin-proof/run-live-fire.sh"
    $run = Invoke-LoggedCommand `
        -Name "docker-run-linux-plugin-proof" `
        -Executable $DockerExecutable `
        -Arguments @(
            "run", "--rm",
            "--privileged",
            "--cgroupns=private",
            "--label", ("npdev.runId=" + ($RunId -replace "[^A-Za-z0-9_.-]", "-")),
            "-v", ($workspaceRoot + ":/workspace"),
            "-v", ($GradleCacheVolume + ":/npdev-cache"),
            "-v", ($BuildVolume + ":/npdev-build"),
            "-w", "/workspace",
            $ImageTag,
            "bash", "-lc", $containerCommand
        ) `
        -WorkingDirectory $workspaceRoot `
        -TimeoutSeconds $RunTimeoutSeconds `
        -StdoutPath (Join-Path $logRoot "docker-run.stdout.log") `
        -StderrPath (Join-Path $logRoot "docker-run.stderr.log")
    $commands += $run
    if ([string]$run.status -ne "passed") {
        Add-Failure -Code "docker-run-failed" -Message "Docker Linux plugin resource-limit test run failed." -Path "NPDevRuntimeHost/runtimehost-core" -Details @{ exitCode = $run.exitCode; timedOut = $run.timedOut; stdoutPath = $run.stdoutPath; stderrPath = $run.stderrPath }
    }
}

# Pull the JUnit report and both cgroup evidence JSONs out of the (gitignored, external) build
# volume onto the host -- best-effort, run regardless of the main run's own status, since a failed
# run's evidence is exactly what a maintainer needs to see.
$evidenceHostDir = Join-Path $logRoot "evidence"
New-Item -ItemType Directory -Force -Path $evidenceHostDir | Out-Null
$copyCommand = "cp -r /npdev-build/gradle/runtimehost-core/root/test-results/test " + '"/out/test-results"' + " 2>/dev/null; " +
    "cp /npdev-build/cgroup-before.json /npdev-build/cgroup-after.json /out/ 2>/dev/null; true"
$copy = Invoke-LoggedCommand `
    -Name "docker-run-copy-evidence" `
    -Executable $DockerExecutable `
    -Arguments @(
        "run", "--rm",
        "-v", ($BuildVolume + ":/npdev-build"),
        "-v", ($evidenceHostDir + ":/out"),
        $ImageTag,
        "bash", "-lc", $copyCommand
    ) `
    -WorkingDirectory $workspaceRoot `
    -TimeoutSeconds 120 `
    -StdoutPath (Join-Path $logRoot "docker-copy-evidence.stdout.log") `
    -StderrPath (Join-Path $logRoot "docker-copy-evidence.stderr.log")
$commands += $copy

foreach ($log in @(Get-ChildItem -LiteralPath $logRoot -Filter "*.log" -File -ErrorAction SilentlyContinue)) {
    $artifacts += New-Artifact -Type "command-log" -PathValue $log.FullName
}
foreach ($xml in @(Get-ChildItem -LiteralPath (Join-Path $evidenceHostDir "test-results") -Filter "*.xml" -File -ErrorAction SilentlyContinue)) {
    $artifacts += New-Artifact -Type "junit-report" -PathValue $xml.FullName
}

# The two probe outputs, inlined as objects (not just artifact paths) -- they are the evidence
# that decides overallStatus below, so the report must carry them, not just point at them.
$cgroupBeforePath = Join-Path $evidenceHostDir "cgroup-before.json"
$cgroupAfterPath = Join-Path $evidenceHostDir "cgroup-after.json"
$cgroupBefore = $null
$cgroupAfter = $null
if (Test-Path -LiteralPath $cgroupBeforePath -PathType Leaf) {
    $artifacts += New-Artifact -Type "cgroup-probe-before" -PathValue $cgroupBeforePath
    try { $cgroupBefore = Get-Content -Raw -LiteralPath $cgroupBeforePath | ConvertFrom-Json } catch { $cgroupBefore = $null }
}
if (Test-Path -LiteralPath $cgroupAfterPath -PathType Leaf) {
    $artifacts += New-Artifact -Type "cgroup-probe-after" -PathValue $cgroupAfterPath
    try { $cgroupAfter = Get-Content -Raw -LiteralPath $cgroupAfterPath | ConvertFrom-Json } catch { $cgroupAfter = $null }
}

# ONE HARD RULE: overallStatus is "failed", never "passed", when the after-delegation probe could
# not create a sibling cgroup with a real memory.max -- even if the JUnit run itself exited 0. A
# test that passes because the child died for an unrelated reason (or because the environment
# silently degraded to the no-op limiter) is not a proof of containment.
if ($failures.Count -eq 0) {
    $canApplyCeiling = $null -ne $cgroupAfter -and [bool]$cgroupAfter.canCreateSiblingWithMemoryMax
    if (-not $canApplyCeiling) {
        Add-Failure -Code "cgroup-ceiling-not-provable" -Message "cgroup-after.json reports canCreateSiblingWithMemoryMax=false (or is missing) -- the JUnit run cannot be trusted as a proof of containment even if it reported success." -Path (Convert-ToRepoPath -Root $workspaceRoot -PathValue $cgroupAfterPath) -Details $cgroupAfter
    }
}

$overallStatus = if ($failures.Count -eq 0) { "passed" } else { "failed" }
$report = [pscustomobject]@{
    schemaVersion = "npdev-linux-plugin-resource-proof-report.v1"
    runId = $RunId
    generatedAt = (Get-Date).ToUniversalTime().ToString("o")
    scriptPath = "scripts/quality/run-linux-plugin-resource-proof.ps1"
    workspaceRoot = $workspaceRoot
    overallStatus = $overallStatus
    skipped = $false
    platform = [pscustomobject]@{
        hostOS = [System.Runtime.InteropServices.RuntimeInformation]::OSDescription
        proofTarget = "linux-container"
        dockerfile = Convert-ToRepoPath -Root $workspaceRoot -PathValue $dockerfileFull
        imageTag = $ImageTag
    }
    timeoutPolicy = [pscustomobject]@{
        dockerVersionTimeoutSeconds = 60
        dockerBuildTimeoutSeconds = $BuildTimeoutSeconds
        dockerRunTimeoutSeconds = $RunTimeoutSeconds
    }
    commands = @($commands)
    exitCodes = @($commands | ForEach-Object { [pscustomobject]@{ name = $_.name; exitCode = $_.exitCode; status = $_.status; timedOut = $_.timedOut } })
    durations = [pscustomobject]@{
        totalCommandDurationSeconds = [int](($commands | Measure-Object -Property durationSeconds -Sum).Sum)
        commands = @($commands | ForEach-Object { [pscustomobject]@{ name = $_.name; durationSeconds = $_.durationSeconds } })
    }
    artifacts = @($artifacts)
    cgroupEnvironmentBefore = $cgroupBefore
    cgroupEnvironmentAfter = $cgroupAfter
    failures = @($failures)
}

New-Item -ItemType Directory -Force -Path (Split-Path -Parent $reportPathFull) | Out-Null
$report | ConvertTo-Json -Depth 50 | Set-Content -LiteralPath $reportPathFull -Encoding UTF8

if ($overallStatus -eq "passed") {
    Write-Host ("Linux plugin resource proof passed. Report: " + (Convert-ToRepoPath -Root $workspaceRoot -PathValue $reportPathFull))
    exit 0
}

Write-Error ("Linux plugin resource proof failed. Report: " + (Convert-ToRepoPath -Root $workspaceRoot -PathValue $reportPathFull))
