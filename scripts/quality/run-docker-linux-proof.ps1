param(
    [string]$WorkspaceRoot = "",
    [string]$RunId = "",
    [string]$ReportPath = "scripts/reports/out/docker-linux-parity-report.json",
    [string]$DockerExecutable = "docker",
    [string]$DockerfilePath = "Dockerfile.ai-beta",
    [int]$BuildTimeoutSeconds = 1800,
    [int]$RunTimeoutSeconds = 3600,
    [int]$ProgressIntervalSeconds = 30,
    [switch]$Force,
    # REG-358 item 2 (owner decision 2026-11-24, "import"): take the report of the latest GREEN CI job
    # "Docker/Linux blocking proof" (ai-beta-gate.yml, ubuntu-latest, which runs THIS script) instead of building
    # the Linux image here. The 16 GB laptop cannot: the local proof wedged WSL at its 7.6 GB memory cap.
    [switch]$FromCi,
    [int]$CiMaxAgeHours = 24
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

if ($FromCi) {
    # Provenance stays honest: generatedAt is the CI run's own (so the gate's freshness rule applies and a report
    # older than -CiMaxAgeHours is refused), the CI run id, URL and commit are recorded under importedFromCi, and
    # ONLY runId is replaced by this run's -RunId, because the gate requires every required report to share
    # exactly one (the original id is kept in importedFromCi.originalRunId). Nothing is written on any failure, so
    # an earlier report is never half-overwritten.
    $fromCiReportFull = Resolve-UnderRoot -Root $workspaceRoot -PathValue $ReportPath
    $fromCiDownload = Join-Path ([System.IO.Path]::GetTempPath()) ("npdev-ci-docker-" + [guid]::NewGuid().ToString("N"))
    $fromCiError = $null
    try {
        if ($null -eq (Get-Command gh -ErrorAction SilentlyContinue)) { throw "the GitHub CLI (gh) is not on PATH." }
        $ciRuns = @(& gh run list --workflow ai-beta-gate.yml --branch main --limit 20 --json databaseId,headSha,url | ConvertFrom-Json)
        if ($LASTEXITCODE -ne 0) { throw "gh run list failed (is gh signed in? try: gh auth status)." }
        $ciPicked = $null
        foreach ($ciRun in $ciRuns) {
            $ciJobs = & gh run view ([string]$ciRun.databaseId) --json jobs | ConvertFrom-Json
            $ciDockerJob = @($ciJobs.jobs | Where-Object { [string]$_.name -eq "Docker/Linux blocking proof" -and [string]$_.conclusion -eq "success" })
            if ($ciDockerJob.Count -gt 0) { $ciPicked = $ciRun; break }
        }
        if ($null -eq $ciPicked) { throw "none of the last 20 ai-beta-gate.yml runs on main has a green 'Docker/Linux blocking proof' job. Dispatch one: gh workflow run ai-beta-gate.yml --ref main" }
        & gh run download ([string]$ciPicked.databaseId) --name docker-linux-proof-reports --dir $fromCiDownload | Out-Null
        if ($LASTEXITCODE -ne 0) { throw ("could not download the artifact docker-linux-proof-reports of CI run " + $ciPicked.databaseId + " (artifacts expire after 14 days; dispatch a new run: gh workflow run ai-beta-gate.yml --ref main).") }
        $ciReportFiles = @(Get-ChildItem -LiteralPath $fromCiDownload -Recurse -File -Filter "docker-linux-parity-report.json")
        if ($ciReportFiles.Count -ne 1) { throw ("expected exactly one docker-linux-parity-report.json in the artifact, found " + $ciReportFiles.Count + ".") }
        $ciNode = [System.Text.Json.Nodes.JsonNode]::Parse((Get-Content -Raw -LiteralPath $ciReportFiles[0].FullName))
        if ($ciNode["schemaVersion"].ToString() -ne "npdev-docker-linux-parity-report.v1") { throw ("unexpected schemaVersion " + $ciNode["schemaVersion"].ToString() + ".") }
        if ($ciNode["overallStatus"].ToString() -ne "passed") { throw ("the CI report says overallStatus " + $ciNode["overallStatus"].ToString() + ", not passed.") }
        $ciGeneratedAt = [DateTimeOffset]::Parse($ciNode["generatedAt"].ToString(), [System.Globalization.CultureInfo]::InvariantCulture, [System.Globalization.DateTimeStyles]::AssumeUniversal)
        $ciAgeHours = ([DateTimeOffset]::UtcNow - $ciGeneratedAt).TotalHours
        if ($ciAgeHours -gt $CiMaxAgeHours) { throw ("the latest green CI proof (run " + $ciPicked.databaseId + ") is " + [math]::Round($ciAgeHours, 1) + " h old, over the " + $CiMaxAgeHours + " h freshness rule. Dispatch a new one: gh workflow run ai-beta-gate.yml --ref main (about 27 min).") }
        $ciOriginalRunId = $ciNode["runId"].ToString()
        $ciProvenance = [pscustomobject]@{
            workflow = ".github/workflows/ai-beta-gate.yml"
            job = "Docker/Linux blocking proof"
            workflowRunId = [string]$ciPicked.databaseId
            url = [string]$ciPicked.url
            headSha = [string]$ciPicked.headSha
            originalRunId = $ciOriginalRunId
            importedAt = (Get-Date).ToUniversalTime().ToString("o")
            note = "Imported by run-docker-linux-proof.ps1 -FromCi. generatedAt is the CI run's own; only runId was replaced with the importing run's id (the gate needs one shared runId)."
        }
        $ciNode["importedFromCi"] = [System.Text.Json.Nodes.JsonNode]::Parse(($ciProvenance | ConvertTo-Json -Depth 5 -Compress))
        if (-not [string]::IsNullOrWhiteSpace($RunId)) { $ciNode["runId"] = [System.Text.Json.Nodes.JsonValue]::Create($RunId) }
        $ciJsonOptions = [System.Text.Json.JsonSerializerOptions]::new()
        $ciJsonOptions.WriteIndented = $true
        $ciJsonOptions.Encoder = [System.Text.Encodings.Web.JavaScriptEncoder]::UnsafeRelaxedJsonEscaping
        New-Item -ItemType Directory -Force -Path (Split-Path -Parent $fromCiReportFull) | Out-Null
        $ciNode.ToJsonString($ciJsonOptions) | Set-Content -LiteralPath $fromCiReportFull -Encoding UTF8
        Write-DockerProofMessage ("imported the CI proof (run " + $ciPicked.databaseId + ", commit " + ([string]$ciPicked.headSha).Substring(0, 8) + ", " + [math]::Round($ciAgeHours, 1) + " h old, original runId " + $ciOriginalRunId + ").")
    }
    catch {
        $fromCiError = $_.Exception.Message
    }
    finally {
        if (Test-Path -LiteralPath $fromCiDownload) { Remove-Item -LiteralPath $fromCiDownload -Recurse -Force -ErrorAction SilentlyContinue }
    }
    if ($null -ne $fromCiError) {
        Write-DockerProofMessage ("from-ci FAILED: " + $fromCiError)
        exit 1
    }
    Write-Host ("Docker/Linux proof imported from CI. Report: " + (Convert-ToRepoPath -Root $workspaceRoot -PathValue $fromCiReportFull))
    exit 0
}

if ($env:CI -ne "true" -and -not $Force) {
    $localProfilePath = Join-Path $workspaceRoot "scripts\policy\local-test-profile.json"
    $localProfile = Get-Content $localProfilePath -Raw | ConvertFrom-Json
    $dockerAllowed = ($localProfile.enabledEngines -contains "postgres") -or ($localProfile.enabledEngines -contains "mysql")
    if (-not $dockerAllowed) {
        Write-DockerProofMessage "SKIPPED: disabled by scripts/policy/local-test-profile.json (pass -Force to override, or add postgres/mysql to enabledEngines)"
        exit 0
    }
}

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

# QUAL-36 follow-up (2026-08-31, run 33421339702): a SECOND not-equipped-for-this-runner case,
# same bucket as the Windows-container-mode skip below. `docker version` itself can fail on a
# windows-latest GH runner because the daemon service isn't reachable at all --
# "failed to connect to the docker API at npipe:////./pipe/docker_engine ... The system cannot find
# the file specified" -- which is a DIFFERENT symptom than defaulting to Windows containers (that
# case gets a clean `docker version` and only diverges at Server.Os), but the same underlying truth:
# a Windows runner cannot prove Linux-container behavior, daemon reachable or not. Matched on the
# Docker CLI's stable connection-failure wording, not the named pipe itself -- the pipe name varies
# by Docker Desktop version/context (observed both `pipe/docker_engine` on a GH windows-latest
# runner and `pipe/dockerDesktopLinuxEngine` locally on a `desktop-linux` context) -- so a genuinely
# missing/broken `docker` executable (empty stderr, a .NET "file not found" exception with no
# docker-daemon wording at all -- exercised by run-docker-linux-proof-tests.ps1's missing-docker
# case) still hard-fails as a real defect.
$stderrJoined = ($version.stderrTail -join "`n")
$daemonUnreachableOnWindows = $IsWindows -and [string]$version.status -ne "passed" -and
    ($stderrJoined -match "failed to connect to the docker API|if the daemon is running|[Cc]annot connect to the Docker daemon")
if ([string]$version.status -ne "passed" -and -not $daemonUnreachableOnWindows) {
    Add-Failure -Code "docker-version-failed" -Message "Docker CLI/version probe failed." -Path $DockerExecutable -Details @{ exitCode = $version.exitCode; timedOut = $version.timedOut; stdoutPath = $version.stdoutPath; stderrPath = $version.stderrPath }
}

# NOT-APPLICABLE, which is deliberately NOT "passed": this proof builds a LINUX image
# (Dockerfile.ai-beta starts `FROM debian:12-slim`), and a Docker daemon in Windows-container mode
# cannot pull or build one at all. The failure it produces is not a defect in NPDev or in the
# Dockerfile -- it is a category error:
#
#   Step 1/8 : FROM debian:12-slim
#   no matching manifest for windows(10.0.26100)/amd64 in the manifest list entries
#
# That is what the Beta 0 release gate hit at gate 10/27 on 2026-08-26 (run 33024568006): the gate
# runs on windows-latest, where the daemon defaults to Windows containers, so a gate literally named
# `docker-linux-proof` could never pass there no matter how healthy the product was.
#
# This records SKIPPED and exits 3, never 0. The distinction matters and is the whole point of doing
# it this way: exit 0 would make run-beta0-final-release-check.ps1 log `=> passed` and the release
# gate treat a proof that never ran as evidence that it succeeded -- the silent-skip conflation this
# repo has been bitten by before. Exit 3 maps to an explicit `skipped-not-applicable` status that is
# visible in the gate sequence, in the report, and in the release-eligibility evidence.
#
# The proof still runs FOR REAL on a Linux daemon -- ai-beta-gate.yml's ubuntu-latest job is its
# proper home, and nothing here weakens that. This only stops a Windows runner reporting a verdict it
# is not equipped to reach.
$dockerServerOs = ""
if ([string]$version.status -eq "passed") {
    $ErrorActionPreference = "Continue"
    $dockerServerOs = (& $DockerExecutable version --format "{{.Server.Os}}" 2>$null | Out-String).Trim()
    $ErrorActionPreference = "Stop"
}
if ($failures.Count -eq 0 -and (($dockerServerOs -and $dockerServerOs -ne "linux") -or $daemonUnreachableOnWindows)) {
    $skipReason = if ($daemonUnreachableOnWindows) {
        ("Docker daemon is not reachable on this Windows host (" + $stderrJoined + "); this proof " +
         "builds a Linux image (Dockerfile.ai-beta: FROM debian:12-slim) and needs a running Linux " +
         "daemon. Its real home is a Linux daemon (ai-beta-gate.yml's ubuntu-latest job).")
    }
    else {
        ("Docker daemon is in '" + $dockerServerOs + "'-container mode; this proof builds a Linux image " +
                   "(Dockerfile.ai-beta: FROM debian:12-slim) and cannot run here. Its real home is a Linux daemon " +
                   "(ai-beta-gate.yml's ubuntu-latest job).")
    }
    Write-DockerProofMessage ("SKIPPED (not applicable): " + $skipReason)
    $skipReport = [pscustomobject]@{
        schemaVersion = "npdev-docker-linux-parity-report.v1"
        runId         = $RunId
        generatedAt   = (Get-Date).ToUniversalTime().ToString("o")
        scriptPath    = "scripts/quality/run-docker-linux-proof.ps1"
        workspaceRoot = $workspaceRoot
        overallStatus = "skipped"
        skipped       = $true
        skipReason    = $skipReason
        dockerServerOs = $dockerServerOs
        platform      = [pscustomobject]@{
            hostOS      = [System.Runtime.InteropServices.RuntimeInformation]::OSDescription
            proofTarget = "linux-container"
            dockerfile  = Convert-ToRepoPath -Root $workspaceRoot -PathValue $dockerfileFull
            imageTag    = $imageTag
        }
        ciCompatibility = $workflowCompatibility
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
        failures  = @()
    }
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $reportPathFull) | Out-Null
    $skipReport | ConvertTo-Json -Depth 50 | Set-Content -LiteralPath $reportPathFull -Encoding UTF8
    Write-Host ("Docker/Linux proof SKIPPED (not applicable on a " + $dockerServerOs + " daemon). Report: " +
                (Convert-ToRepoPath -Root $workspaceRoot -PathValue $reportPathFull))
    exit 3
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
