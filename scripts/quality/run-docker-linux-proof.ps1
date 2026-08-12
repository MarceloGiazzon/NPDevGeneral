param(
    [string]$WorkspaceRoot = "",
    [string]$RunId = "",
    [string]$ReportPath = "scripts/reports/out/docker-linux-parity-report.json",
    [string]$DockerExecutable = "docker",
    [string]$DockerfilePath = "Dockerfile.ai-beta",
    [int]$BuildTimeoutSeconds = 1800,
    [int]$RunTimeoutSeconds = 3600,
    [int]$ProgressIntervalSeconds = 30
)

$ErrorActionPreference = "Stop"

function Write-DockerProofMessage {
    param([string]$Message)
    Write-Host ("[" + (Get-Date).ToString("HH:mm:ss") + "] docker-linux-proof: " + $Message)
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
        Write-DockerProofMessage ("START " + $Name + " -> " + $Executable + " " + $argumentLine)
        Write-DockerProofMessage ("Logs: " + (Convert-ToRepoPath -Root $workspaceRoot -PathValue $StdoutPath) + " | " + (Convert-ToRepoPath -Root $workspaceRoot -PathValue $StderrPath))
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
                Write-DockerProofMessage ("TIMEOUT " + $Name + " after " + $elapsedSeconds + "s")
                break
            }

            $tail = @(Get-LogTail -PathValue $StderrPath -LineCount 3)
            if ($tail.Count -eq 0) {
                $tail = @(Get-LogTail -PathValue $StdoutPath -LineCount 3)
            }
            $lastLine = if ($tail.Count -gt 0) { [string]$tail[$tail.Count - 1] } else { "no log output yet" }
            Write-DockerProofMessage ("RUNNING " + $Name + " (" + $elapsedSeconds + "s elapsed, timeout " + $TimeoutSeconds + "s). Last log: " + $lastLine)
        }
    }
    catch {
        $exitCode = $null
        $errorMessage = $_.Exception.Message
    }

    $finishedAt = (Get-Date).ToUniversalTime()
    $status = if (-not $timedOut -and [string]::IsNullOrWhiteSpace($errorMessage) -and $null -ne $exitCode -and $exitCode -eq 0) { "passed" } else { "failed" }
    $durationSeconds = [int]([DateTimeOffset]$finishedAt - [DateTimeOffset]$startedAt).TotalSeconds
    Write-DockerProofMessage ("END   " + $Name + " => " + $status + " (exit " + $exitCode + ", " + $durationSeconds + "s)")
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

function Test-WorkflowCompatibility {
    param([string]$WorkflowPath)
    $exists = Test-Path -LiteralPath $WorkflowPath -PathType Leaf
    $text = if ($exists) { Get-Content -Raw -LiteralPath $WorkflowPath } else { "" }
    $checks = @(
        [pscustomobject]@{ name = "workflow-exists"; passed = $exists; reason = "CI workflow file exists." },
        [pscustomobject]@{ name = "ubuntu-runner"; passed = $text -match "runs-on:\s*ubuntu-latest"; reason = "Docker/Linux proof needs an Ubuntu runner with Linux Docker support." },
        [pscustomobject]@{ name = "proof-script-invoked"; passed = $text -match "run-docker-linux-proof\.ps1"; reason = "CI workflow invokes the canonical Docker/Linux proof script." },
        [pscustomobject]@{ name = "timeout-configured"; passed = $text -match "timeout-minutes:\s*\d+"; reason = "CI workflow has an explicit timeout." },
        [pscustomobject]@{ name = "evidence-uploaded"; passed = $text -match "upload-artifact@v4"; reason = "CI workflow uploads report/log artifacts." }
    )
    return [pscustomobject]@{
        workflowPath = Convert-ToRepoPath -Root $workspaceRoot -PathValue $WorkflowPath
        status = if (@($checks | Where-Object { -not [bool]$_.passed }).Count -eq 0) { "passed" } else { "failed" }
        checks = $checks
    }
}

if ([string]::IsNullOrWhiteSpace($WorkspaceRoot)) {
    $WorkspaceRoot = (Resolve-Path ".").Path
}
$workspaceRoot = [System.IO.Path]::GetFullPath($WorkspaceRoot)
if ([string]::IsNullOrWhiteSpace($RunId)) {
    $RunId = "docker-linux-proof-" + (Get-Date).ToUniversalTime().ToString("yyyyMMdd-HHmmssfff")
}
Write-DockerProofMessage ("Starting. RunId: " + $RunId)
Write-DockerProofMessage ("Workspace: " + $workspaceRoot)

$script:failures = @()
$commands = @()
$artifacts = @()
$reportPathFull = Resolve-UnderRoot -Root $workspaceRoot -PathValue $ReportPath
$dockerfileFull = Resolve-UnderRoot -Root $workspaceRoot -PathValue $DockerfilePath
$logRoot = Join-Path (Split-Path -Parent $reportPathFull) "docker-linux-proof"
New-Item -ItemType Directory -Force -Path $logRoot | Out-Null

if (-not (Test-Path -LiteralPath $dockerfileFull -PathType Leaf)) {
    Add-Failure -Code "dockerfile-missing" -Message "Docker/Linux proof Dockerfile is missing." -Path (Convert-ToRepoPath -Root $workspaceRoot -PathValue $dockerfileFull)
}
else {
    $dockerfileText = Get-Content -Raw -LiteralPath $dockerfileFull
    $usesLinuxPowerShellBase = $dockerfileText -match "mcr\.microsoft\.com/powershell:.+debian"
    $installsPowerShellOnDebian = $dockerfileText -match "FROM\s+debian:12-slim" -and
        $dockerfileText -match "packages\.microsoft\.com/config/debian/12/packages-microsoft-prod\.deb" -and
        $dockerfileText -match "powershell=7\.4\."
    if (-not ($usesLinuxPowerShellBase -or $installsPowerShellOnDebian)) {
        Add-Failure -Code "dockerfile-not-linux-powershell" -Message "Dockerfile must either use a Linux PowerShell base image or install pinned PowerShell 7.4 on Debian 12." -Path (Convert-ToRepoPath -Root $workspaceRoot -PathValue $dockerfileFull)
    }
    foreach ($tool in @("openjdk-17-jdk", "nodejs", "npm", "git", "powershell")) {
        if ($dockerfileText -notmatch [regex]::Escape($tool)) {
            Add-Failure -Code "dockerfile-tool-missing" -Message ("Dockerfile does not install required CI/runtime tool: " + $tool) -Path (Convert-ToRepoPath -Root $workspaceRoot -PathValue $dockerfileFull)
        }
    }
}

$workflowCompatibility = Test-WorkflowCompatibility -WorkflowPath (Resolve-UnderRoot -Root $workspaceRoot -PathValue ".github\workflows\ai-beta-gate.yml")
if ([string]$workflowCompatibility.status -ne "passed") {
    Add-Failure -Code "ci-compatibility-failed" -Message "Docker/Linux CI compatibility checks failed." -Path ([string]$workflowCompatibility.workflowPath) -Details $workflowCompatibility
}

$safeRunId = ($RunId.ToLowerInvariant() -replace '[^a-z0-9_.-]', '-')
$imageTag = "npdev-ai-beta:" + $safeRunId
Write-DockerProofMessage ("Image tag: " + $imageTag)

$version = Invoke-LoggedCommand `
    -Name "docker-version" `
    -Executable $DockerExecutable `
    -Arguments @("version") `
    -WorkingDirectory $workspaceRoot `
    -TimeoutSeconds 60 `
    -StdoutPath (Join-Path $logRoot "docker-version.stdout.log") `
    -StderrPath (Join-Path $logRoot "docker-version.stderr.log")
$commands += $version
if ([string]$version.status -ne "passed") {
    Add-Failure -Code "docker-version-failed" -Message "Docker CLI/version probe failed." -Path $DockerExecutable -Details @{ exitCode = $version.exitCode; timedOut = $version.timedOut; stdoutPath = $version.stdoutPath; stderrPath = $version.stderrPath }
}

if ($failures.Count -eq 0) {
    # Every Gradle distribution this repo's wrappers ask for, DERIVED rather than restated, and
    # baked into the image so the container never fetches Gradle over the network at run time.
    #
    # This gate lost that coin flip twice on 2026-08-12 (runs 31620633059 and 31623132314), each
    # time on a different distribution, each time with the same
    # `org.gradle.wrapper.Install.forceFetch` SocketException before any of our code compiled.
    #
    # Read from the wrapper files instead of listed in the Dockerfile on purpose: a hardcoded copy
    # goes stale the first time someone bumps a wrapper, and the failure mode of a stale bake is
    # SILENT -- the hash no longer matches, the wrapper downloads again, and the flakiness returns
    # with nothing to point at. That is the same invisible-drift shape that left this whole gate red
    # for six weeks. Note the repo is deliberately NOT single-version today (RuntimeHost is on
    # 9.5.1, everything else on 8.5), so this must handle a set, not one value.
    # Convert-ToRepoPath, not Get-NPDevWorkspaceRelativePath: this script is self-contained and
    # dot-sources npdev-common.ps1 nowhere, so the common helpers do not exist here. Borrowing that
    # idiom from Test-GradleWrapperConsistency.ps1 (which does dot-source it) cost run 31625821322 --
    # a parse check cannot catch an unresolved command name, only running it can.
    $wrapperPropertyFiles = @(Get-ChildItem -LiteralPath $workspaceRoot -Recurse -Force -File -Filter "gradle-wrapper.properties" -ErrorAction SilentlyContinue |
            Where-Object {
                $segments = @((Convert-ToRepoPath -Root $workspaceRoot -PathValue $_.FullName) -split "/")
                @($segments | Where-Object { $_ -in @(".git", ".gradle", "build", "dist", "node_modules", "out", "target") }).Count -eq 0
            })
    $gradleDistributionUrls = @($wrapperPropertyFiles |
            ForEach-Object { Get-Content -LiteralPath $_.FullName } |
            Where-Object { $_ -match "^distributionUrl=" } |
            ForEach-Object { ($_ -replace "^distributionUrl=", "").Trim().Replace("\:", ":") } |
            Where-Object { $_ -like "http*" } |
            Sort-Object -Unique)

    if ($gradleDistributionUrls.Count -eq 0) {
        Add-Failure -Code "gradle-distributions-not-resolved" -Message "No Gradle distributionUrl found in any gradle-wrapper.properties; refusing to build an image that would download Gradle at run time." -Path "gradle/wrapper/gradle-wrapper.properties" -Details @{ wrapperFileCount = $wrapperPropertyFiles.Count }
    }
    else {
        Write-DockerProofMessage ("Baking " + $gradleDistributionUrls.Count + " Gradle distribution(s) into the image: " + ($gradleDistributionUrls -join ", "))
    }
}

if ($failures.Count -eq 0) {
    $build = Invoke-LoggedCommand `
        -Name "docker-build" `
        -Executable $DockerExecutable `
        -Arguments @("build", "-f", (Convert-ToRepoPath -Root $workspaceRoot -PathValue $dockerfileFull), "--build-arg", ("GRADLE_DISTRIBUTION_URLS=" + ($gradleDistributionUrls -join " ")), "-t", $imageTag, ".") `
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
    $containerRunId = $RunId.Replace("'", "'\''")
    $containerCommand = "rm -f scripts/reports/out/docker-linux-ai-beta-gate-report.json scripts/reports/out/runtimehost-libs-sync-report.json && find . -name gradlew -type f -exec chmod +x '{}' + && pwsh -NoProfile -File scripts/quality/run-ai-beta-gate.ps1 -RunId '" + $containerRunId + "' -ReportPath scripts/reports/out/docker-linux-ai-beta-gate-report.json"
    $run = Invoke-LoggedCommand `
        -Name "docker-run-ai-beta-gate" `
        -Executable $DockerExecutable `
        -Arguments @(
            "run",
            "--rm",
            "--label",
            ("npdev.runId=" + $safeRunId),
            "-e",
            "CI=true",
            "-e",
            "NPDEV_LOCAL_CACHE_ROOT=/tmp/npdev-cache",
            "-e",
            "NPDEV_GRADLE_USER_HOME=/tmp/npdev-cache/gradle",
            "-e",
            "GRADLE_USER_HOME=/tmp/npdev-cache/gradle",
            "-e",
            "NPDEV_RUNTIMEHOST_LIBS_DIR=/tmp/npdev-runtimehost-libs",
            "-v",
            ($workspaceRoot + ":/workspace"),
            "-w",
            "/workspace",
            $imageTag,
            "sh",
            "-lc",
            $containerCommand
        ) `
        -WorkingDirectory $workspaceRoot `
        -TimeoutSeconds $RunTimeoutSeconds `
        -StdoutPath (Join-Path $logRoot "docker-run.stdout.log") `
        -StderrPath (Join-Path $logRoot "docker-run.stderr.log")
    $commands += $run
    if ([string]$run.status -ne "passed") {
        Add-Failure -Code "docker-run-ai-beta-gate-failed" -Message "Docker/Linux AI beta gate command failed." -Path "scripts/quality/run-ai-beta-gate.ps1" -Details @{ exitCode = $run.exitCode; timedOut = $run.timedOut; stdoutPath = $run.stdoutPath; stderrPath = $run.stderrPath }
    }
}

foreach ($log in @(Get-ChildItem -LiteralPath $logRoot -Filter "*.log" -File -ErrorAction SilentlyContinue)) {
    $artifacts += New-Artifact -Type "command-log" -PathValue $log.FullName
}
$dockerAiReport = Resolve-UnderRoot -Root $workspaceRoot -PathValue "scripts/reports/out/docker-linux-ai-beta-gate-report.json"
if (Test-Path -LiteralPath $dockerAiReport -PathType Leaf) {
    $artifacts += New-Artifact -Type "container-ai-beta-gate-report" -PathValue $dockerAiReport
}

$overallStatus = if ($failures.Count -eq 0) { "passed" } else { "failed" }
$report = [pscustomobject]@{
    schemaVersion = "npdev-docker-linux-parity-report.v1"
    runId = $RunId
    generatedAt = (Get-Date).ToUniversalTime().ToString("o")
    scriptPath = "scripts/quality/run-docker-linux-proof.ps1"
    workspaceRoot = $workspaceRoot
    overallStatus = $overallStatus
    platform = [pscustomobject]@{
        hostOS = [System.Runtime.InteropServices.RuntimeInformation]::OSDescription
        proofTarget = "linux-container"
        dockerfile = Convert-ToRepoPath -Root $workspaceRoot -PathValue $dockerfileFull
        imageTag = $imageTag
    }
    ciCompatibility = $workflowCompatibility
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
    failures = @($failures)
}

New-Item -ItemType Directory -Force -Path (Split-Path -Parent $reportPathFull) | Out-Null
$report | ConvertTo-Json -Depth 50 | Set-Content -LiteralPath $reportPathFull -Encoding UTF8

if ($overallStatus -eq "passed") {
    Write-Host ("Docker/Linux proof passed. Report: " + (Convert-ToRepoPath -Root $workspaceRoot -PathValue $reportPathFull))
    exit 0
}

Write-Error ("Docker/Linux proof failed. Report: " + (Convert-ToRepoPath -Root $workspaceRoot -PathValue $reportPathFull))
