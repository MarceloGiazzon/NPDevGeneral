Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Write-NPDevInfo([string]$Message) {
    Write-Host ("INFO  " + $Message) -ForegroundColor Cyan
}

function Write-NPDevOk([string]$Message) {
    Write-Host ("OK    " + $Message) -ForegroundColor Green
}

function Write-NPDevWarn([string]$Message) {
    Write-Host ("WARN  " + $Message) -ForegroundColor Yellow
}

function Normalize-NPDevPath([string]$PathValue) {
    return [System.IO.Path]::GetFullPath($PathValue)
}

function Get-NPDevWorkspaceRoot([string]$ScriptRoot) {
    $current = Normalize-NPDevPath $ScriptRoot
    while (-not [string]::IsNullOrWhiteSpace($current)) {
        $isWorkspaceRoot = (Test-Path -LiteralPath (Join-Path $current ".npdev-root") -PathType Leaf) `
            -and (Test-Path -LiteralPath (Join-Path $current "NPDevContract") -PathType Container) `
            -and (Test-Path -LiteralPath (Join-Path $current "NPDevEditor") -PathType Container) `
            -and (Test-Path -LiteralPath (Join-Path $current "NPDevGenerator") -PathType Container) `
            -and (Test-Path -LiteralPath (Join-Path $current "NPDevKernel") -PathType Container) `
            -and (Test-Path -LiteralPath (Join-Path $current "NPDevRuntimeHost") -PathType Container) `
            -and (Test-Path -LiteralPath (Join-Path $current "NPDevSamples") -PathType Container)

        if ($isWorkspaceRoot) {
            return $current
        }

        $parent = Split-Path -Parent $current
        if ($parent -eq $current) {
            break
        }
        $current = $parent
    }

    throw ("Could not resolve NPDev workspace root from " + $ScriptRoot)
}

function Resolve-NPDevWorkspacePath([string]$WorkspaceRoot, [string]$RelativePath) {
    return Normalize-NPDevPath (Join-Path $WorkspaceRoot $RelativePath)
}

function Ensure-NPDevFile([string]$PathValue, [string]$Label) {
    if (-not (Test-Path -LiteralPath $PathValue -PathType Leaf)) {
        throw ($Label + " not found: " + $PathValue)
    }
}

function Ensure-NPDevDirectory([string]$PathValue, [string]$Label) {
    if (-not (Test-Path -LiteralPath $PathValue -PathType Container)) {
        throw ($Label + " not found: " + $PathValue)
    }
}

function Test-NPDevCommandAvailable([string]$CommandName) {
    return $null -ne (Get-Command $CommandName -ErrorAction SilentlyContinue)
}

function New-NPDevCheckResult {
    param(
        [string]$Name,
        [ValidateSet("passed", "warning", "failed")]
        [string]$Status,
        [string]$Summary,
        [object]$Data = $null
    )

    return [pscustomobject]@{
        name = $Name
        status = $Status
        summary = $Summary
        data = $Data
        checkedAt = (Get-Date).ToString("o")
    }
}

function Write-NPDevJsonFile([string]$PathValue, [object]$Value) {
    $parent = Split-Path -Parent $PathValue
    if (-not [string]::IsNullOrWhiteSpace($parent)) {
        New-Item -ItemType Directory -Force -Path $parent | Out-Null
    }

    $json = $Value | ConvertTo-Json -Depth 20
    Set-Content -LiteralPath $PathValue -Value $json -Encoding UTF8
}

function New-NPDevRunId(
    [string]$Prefix = "npdev-run"
) {
    return $Prefix + "-" + (Get-Date).ToString("yyyyMMdd-HHmmssfff")
}

function Resolve-NPDevRunId(
    [string]$RunId,
    [string]$Prefix = "npdev-run"
) {
    if ([string]::IsNullOrWhiteSpace($RunId)) {
        return New-NPDevRunId $Prefix
    }
    return $RunId
}

function Get-NPDevGradleFailureTaskName(
    [string[]]$OutputLines
) {
    foreach ($line in $OutputLines) {
        if ([string]$line -match '>\s*Task\s+(:[^\s]+)\s+FAILED') {
            return $matches[1]
        }
    }

    foreach ($line in $OutputLines) {
        if ([string]$line -match "Execution failed for task '([^']+)'") {
            return $matches[1]
        }
    }

    return $null
}

function Invoke-NPDevReportedCommand {
    param(
        [string]$WorkspaceRoot,
        [string]$ScriptPath,
        [string]$RunId,
        [string]$ReportPath,
        [string]$GateName,
        [string]$WorkingDirectory,
        [string]$Executable,
        [string[]]$Arguments = @(),
        [object]$Extra = $null
    )

    $RunId = Resolve-NPDevRunId $RunId ($GateName + "-focused")
    $startedAt = Get-Date
    $capture = $null
    $commandError = $null

    try {
        $capture = Invoke-NPDevCommandCapture -WorkingDirectory $WorkingDirectory -Executable $Executable -Arguments $Arguments
        foreach ($line in @($capture.Output)) {
            Write-Host $line
        }
    }
    catch {
        $commandError = $_.Exception.Message
    }

    $endedAt = Get-Date
    $exitCode = if ($null -eq $capture) { $null } else { [int]$capture.ExitCode }
    [string[]]$outputLines = @(if ($null -ne $capture) { @($capture.Output | ForEach-Object { [string]$_ }) })
    $status = if ([string]::IsNullOrWhiteSpace($commandError) -and $null -ne $exitCode -and $exitCode -eq 0) {
        "passed"
    }
    else {
        "failed"
    }

    $failureReasons = [System.Collections.Generic.List[string]]::new()
    if (-not [string]::IsNullOrWhiteSpace($commandError)) {
        [void]$failureReasons.Add($commandError)
    }
    if ($null -eq $exitCode) {
        [void]$failureReasons.Add("Command did not produce an exit code.")
    }
    elseif ($exitCode -ne 0) {
        [void]$failureReasons.Add("Command failed with exit code " + $exitCode + ".")
    }

    $report = [pscustomobject]@{
        generatedAt = (Get-Date).ToString("o")
        runId = $RunId
        scriptPath = Get-NPDevWorkspaceRelativePath $WorkspaceRoot $ScriptPath
        workspaceRoot = $WorkspaceRoot
        overallStatus = $status
        gateName = $GateName
        workingDirectory = Get-NPDevWorkspaceRelativePath $WorkspaceRoot $WorkingDirectory
        command = [pscustomobject]@{
            executable = $Executable
            arguments = $Arguments
            display = $Executable + " " + ($Arguments -join " ")
            exitCode = $exitCode
            startedAt = $startedAt.ToString("o")
            endedAt = $endedAt.ToString("o")
            durationSeconds = [math]::Round(($endedAt - $startedAt).TotalSeconds, 1)
            outputLineCount = $outputLines.Count
            outputTail = @($outputLines | Select-Object -Last 160)
            failingTaskName = Get-NPDevGradleFailureTaskName $outputLines
            error = $commandError
        }
        stderrSummary = if ($failureReasons.Count -eq 0) { $null } else { ($failureReasons -join " ") }
        failureReasons = @($failureReasons)
        extra = $Extra
    }
    Write-NPDevJsonFile $ReportPath $report

    if ($status -eq "passed") {
        return $report
    }

    throw $report.stderrSummary
}

function Invoke-NPDevCommandEvidence {
    param(
        [string]$WorkspaceRoot,
        [string]$WorkingDirectory,
        [string]$Executable,
        [string[]]$Arguments = @(),
        [string]$LogPath = ""
    )

    $startedAt = Get-Date
    $capture = $null
    $commandError = $null

    try {
        $capture = Invoke-NPDevCommandCapture -WorkingDirectory $WorkingDirectory -Executable $Executable -Arguments $Arguments
        foreach ($line in @($capture.Output)) {
            Write-Host $line
        }
    }
    catch {
        $commandError = $_.Exception.Message
    }

    $endedAt = Get-Date
    $exitCode = if ($null -eq $capture) { $null } else { [int]$capture.ExitCode }
    [string[]]$outputLines = @(if ($null -ne $capture) { @($capture.Output | ForEach-Object { [string]$_ }) })

    if (-not [string]::IsNullOrWhiteSpace($LogPath)) {
        $logDirectory = Split-Path -Parent $LogPath
        if (-not [string]::IsNullOrWhiteSpace($logDirectory)) {
            New-Item -ItemType Directory -Force -Path $logDirectory | Out-Null
        }
        Set-Content -LiteralPath $LogPath -Value $outputLines -Encoding UTF8
    }

    $status = if ([string]::IsNullOrWhiteSpace($commandError) -and $null -ne $exitCode -and $exitCode -eq 0) {
        "passed"
    }
    else {
        "failed"
    }

    $failureReasons = [System.Collections.Generic.List[string]]::new()
    if (-not [string]::IsNullOrWhiteSpace($commandError)) {
        [void]$failureReasons.Add($commandError)
    }
    if ($null -eq $exitCode) {
        [void]$failureReasons.Add("Command did not produce an exit code.")
    }
    elseif ($exitCode -ne 0) {
        [void]$failureReasons.Add("Command failed with exit code " + $exitCode + ".")
    }

    return [pscustomobject]@{
        status = $status
        workingDirectory = Get-NPDevWorkspaceRelativePath $WorkspaceRoot $WorkingDirectory
        executable = $Executable
        arguments = $Arguments
        display = $Executable + " " + ($Arguments -join " ")
        exitCode = $exitCode
        startedAt = $startedAt.ToString("o")
        endedAt = $endedAt.ToString("o")
        durationSeconds = [math]::Round(($endedAt - $startedAt).TotalSeconds, 1)
        outputLineCount = $outputLines.Count
        outputTail = @($outputLines | Select-Object -Last 160)
        failingTaskName = Get-NPDevGradleFailureTaskName $outputLines
        logPath = if ([string]::IsNullOrWhiteSpace($LogPath)) { $null } else { Get-NPDevWorkspaceRelativePath $WorkspaceRoot $LogPath }
        error = $commandError
        failureReasons = @($failureReasons)
    }
}

function Test-NPDevGradleExecutable([string]$Executable) {
    $name = [System.IO.Path]::GetFileName($Executable)
    return $name -match '^(gradle|gradlew)(\.bat)?$'
}

function Get-NPDevGradleWrapperExecutable([string]$ProjectRoot) {
    $windowsWrapper = Join-Path $ProjectRoot "gradlew.bat"
    $posixWrapper = Join-Path $ProjectRoot "gradlew"
    if ($IsWindows) {
        if (Test-Path -LiteralPath $windowsWrapper -PathType Leaf) {
            return $windowsWrapper
        }
        if (Test-Path -LiteralPath $posixWrapper -PathType Leaf) {
            return $posixWrapper
        }
    }
    else {
        if (Test-Path -LiteralPath $posixWrapper -PathType Leaf) {
            return $posixWrapper
        }
        if (Test-Path -LiteralPath $windowsWrapper -PathType Leaf) {
            return $windowsWrapper
        }
    }

    throw ("Gradle wrapper not found in " + $ProjectRoot)
}

function Get-NPDevLocalCacheRoot([string]$WorkspaceRoot) {
    if (-not [string]::IsNullOrWhiteSpace($env:NPDEV_LOCAL_CACHE_ROOT)) {
        return Normalize-NPDevPath $env:NPDEV_LOCAL_CACHE_ROOT
    }

    $localApplicationData = [Environment]::GetFolderPath([Environment+SpecialFolder]::LocalApplicationData)
    if (-not [string]::IsNullOrWhiteSpace($localApplicationData)) {
        return Normalize-NPDevPath (Join-Path $localApplicationData "NPDev")
    }

    if (-not [string]::IsNullOrWhiteSpace($env:XDG_CACHE_HOME)) {
        return Normalize-NPDevPath (Join-Path $env:XDG_CACHE_HOME "npdev")
    }

    if (-not [string]::IsNullOrWhiteSpace($env:HOME)) {
        return Normalize-NPDevPath (Join-Path (Join-Path $env:HOME ".cache") "npdev")
    }

    return Normalize-NPDevPath (Join-Path $WorkspaceRoot ".npdev-cache")
}

function Get-NPDevGradleUserHome([string]$WorkingDirectory) {
    $workspaceRoot = Get-NPDevWorkspaceRoot $WorkingDirectory
    $gradleUserHome = if (-not [string]::IsNullOrWhiteSpace($env:NPDEV_GRADLE_USER_HOME)) {
        Normalize-NPDevPath $env:NPDEV_GRADLE_USER_HOME
    }
    else {
        Join-Path (Get-NPDevLocalCacheRoot $workspaceRoot) "gradle"
    }
    New-Item -ItemType Directory -Force -Path $gradleUserHome | Out-Null
    return $gradleUserHome
}

# LC-C4 (MASTER_AI_PLATFORM_PROGRAMME_v2.md Wave 1.4): ONE staged-jar directory, and the two
# scripts that use it now agree on it by DEFAULT.
#
# This used to return "<repo>__OutsideRepo/runtimehost-libs" while Build-NpdevApp.ps1's own
# -RuntimeHostLibsDir defaulted to "D:\WorkSpace\NPDev\Build\runtimehost-libs". Running the two
# steps by hand and letting each default meant the sync wrote jars where the build never looked, so
# the app silently kept a stale jar -- the failure CLAUDE.md warns about in bold ("the sync default
# dir does NOT match Build-NpdevApp.ps1's default, so pass -RuntimeHostLibsDir to both"), and the
# programme's own risk R7. A convention that only works if you remember it is not a convention.
#
# Build\runtimehost-libs wins over the OutsideRepo location because staged jars ARE build output,
# and the standing policy is that build output lives under the Build root (never in the repo, and
# OutsideRepo is for evidence/scratch). NPDEV_RUNTIMEHOST_LIBS_DIR still overrides, and every
# caller's explicit -RuntimeHostLibsDir still wins over both.
function Get-NPDevRuntimeHostLibsDir([string]$WorkspaceRoot) {
    if (-not [string]::IsNullOrWhiteSpace($env:NPDEV_RUNTIMEHOST_LIBS_DIR)) {
        return Normalize-NPDevPath $env:NPDEV_RUNTIMEHOST_LIBS_DIR
    }

    return Normalize-NPDevPath (Join-Path (Get-NPDevBuildRoot $WorkspaceRoot) "runtimehost-libs")
}

# The Build root every generated artifact lives under. Kept beside the libs dir above so the two
# cannot drift apart again: they are now one definition, not two conventions.
#
# npdev-build-root-resolution. Walks UP from the workspace root for the directory that CONTAINS
# NPDevContract + NPDevGenerator + NPDevKernel -- the repo root identified by its contents, never by
# its name. Its parent holds Build. Mirrors resolveNpdevBuildRoot in all five build.gradle files,
# which use the same predicate, and WorkspaceRootLocator.java, which established it.
#
# THIS WALK USED TO MATCH ON THE NAME 'NPDev_General', and the comment below already knew a clone
# could be named 'NPDevGeneral' -- it just assumed the fallback handled that. It did not. When the
# name did not match, this function and Gradle BOTH fell through, to fallbacks computed from
# different starting points:
#     Gradle      rootDir.parentFile/Build   rootDir = NPDevKernel  -> <workspace>/Build
#     PowerShell  workspace.Parent/Build     workspace = checkout   -> <workspace>/../Build
# One directory apart. Gradle wrote 30 adapter jars to the first; sync-runtimehost-libs.ps1 searched
# the second, found zero, and three packaged-app proof tests failed on Linux CI with "No RuntimeHost
# jars were discovered under build/libs after local jar build."
#
# Measured against simulated checkouts before and after the fix:
#     folder 'NPDevGeneral'   OLD: PS .../NPDevGeneral/Build vs Gradle .../NPDevGeneral/NPDevGeneral/Build  DISAGREE
#     folder 'NPDev_General'  OLD: both .../NPDev/Build                                                     agree
# That second line is the whole story: on the author's machine the directory really is named
# NPDev_General, so the two agreed by coincidence and the bug could only ever appear on CI or in
# someone else's clone. Content-based detection makes them agree by construction, under any name.
#
# Found live (2026-08-06, CI_RED_PLAN.md follow-up): this function's OLD body was a bare
# <workspace.parent>/Build guess, which diverged from a SEPARATE copy of this exact walk that
# scripts/runtimehost/sync-runtimehost-libs.ps1 maintained on its own. On a GitHub Actions runner
# (checkout at .../NPDevGeneral/NPDevGeneral, so the walk never finds 'NPDev_General' either) the
# two fallbacks disagreed: this function's old guess landed one level too high
# (<checkout-parent>/Build), while the generated sample app's own build.gradle resolution (which
# the OTHER copy correctly mirrored) landed at <checkout>/Build -- so `npdev setup`'s jar sync
# reported success while writing to a directory the generated app's own
# `verifyNpdevRuntimeHostLibs` task never looked in ("Missing NPDev RuntimeHost libs manifest").
# Centralized here, with sync-runtimehost-libs.ps1's own duplicate removed, so the two mechanisms
# cannot diverge again -- the same "one place updated, its twin forgotten" shape this project
# already tracks via scripts/quality/check-twin-pair-consistency.py, fixed by deleting the twin
# instead of registering it.
# REG-162 (2026-08-14): a git worktree checked out UNDER this repo (.claude\worktrees\<id>, this
# platform's normal way of running an isolated agent session) is a FULL checkout -- it legitimately
# contains its own NPDevContract/NPDevGenerator/NPDevKernel subdirectories, so it satisfies the
# predicate below just as validly as the canonical checkout does, one level "too early". The walk
# used to stop at the FIRST (innermost/closest) ancestor satisfying the predicate, which for a
# worktree IS the worktree itself -- so this returned <worktree>\..\Build (a throwaway,
# worktree-scoped shadow directory, e.g. "...\.claude\worktrees\Build") instead of the ONE shared
# "D:\WorkSpace\NPDev\Build" every other checkout (and every other tool: Get-NPDevRuntimeHostLibsDir,
# sync-runtimehost-libs.ps1's own -PnpdevBuildRoot gradle invocations, and the env var it leaks into
# the rest of the session) actually reads/writes. Reproduced live: from a real worktree, this
# function returned "<repo>\.claude\worktrees\Build" byte-for-byte -- exactly the shadow path a
# rebuild-and-restage run found a freshly-built kernel jar sitting in while reporting success against
# the correct, explicitly-requested target.
#
# Fix: keep walking PAST a match to find the OUTERMOST ancestor that still satisfies the predicate,
# not the innermost. A worktree nested under the canonical checkout has exactly one further matching
# ancestor (the canonical checkout itself) and climbing to it recovers the single shared Build root
# by construction, under any worktree name or depth -- no special-casing "am I a worktree" needed.
# Running directly from the canonical checkout (no nested match above it) or from a CI checkout
# nested one level under its own container (REG-144; no NPDevContract et al. at that outer level
# either) is unaffected: exactly one match is found either way, same as before this fix.
function Get-NPDevBuildRoot([string]$WorkspaceRoot) {
    if (-not [string]::IsNullOrWhiteSpace($env:NPDEV_BUILD_ROOT)) {
        return Normalize-NPDevPath $env:NPDEV_BUILD_ROOT
    }

    $workspace = Get-Item -LiteralPath (Normalize-NPDevPath $WorkspaceRoot)
    $ancestor = $workspace
    $matched = $null
    while ($null -ne $ancestor) {
        $isCandidateRoot = (Test-Path -LiteralPath (Join-Path $ancestor.FullName "NPDevContract") -PathType Container) -and
            (Test-Path -LiteralPath (Join-Path $ancestor.FullName "NPDevGenerator") -PathType Container) -and
            (Test-Path -LiteralPath (Join-Path $ancestor.FullName "NPDevKernel") -PathType Container)
        if ($isCandidateRoot) {
            $matched = $ancestor
        }
        $ancestor = $ancestor.Parent
    }
    if ($null -ne $matched -and $null -ne $matched.Parent) {
        return Normalize-NPDevPath (Join-Path $matched.Parent.FullName "Build")
    }
    return Normalize-NPDevPath (Join-Path $workspace.Parent.FullName "Build")
}

function Invoke-NPDevCommandStreaming {
    param(
        [string]$WorkingDirectory,
        [string]$Executable,
        [string[]]$Arguments = @()
    )

    $previousGradleUserHome = $env:GRADLE_USER_HOME
    $overrideGradleUserHome = Test-NPDevGradleExecutable $Executable
    if ($overrideGradleUserHome) {
        $env:GRADLE_USER_HOME = Get-NPDevGradleUserHome $WorkingDirectory
    }

    Push-Location $WorkingDirectory
    try {
        & $Executable @Arguments
        $exitCode = if ($null -eq $LASTEXITCODE) { 0 } else { [int]$LASTEXITCODE }
    }
    finally {
        Pop-Location
        if ($overrideGradleUserHome) {
            if ($null -eq $previousGradleUserHome) {
                Remove-Item Env:GRADLE_USER_HOME -ErrorAction SilentlyContinue
            }
            else {
                $env:GRADLE_USER_HOME = $previousGradleUserHome
            }
        }
    }

    if ($exitCode -ne 0) {
        throw ("Command failed with exit code " + $exitCode + ": " + $Executable + " " + ($Arguments -join " "))
    }
}

function Invoke-NPDevCommandCapture {
    param(
        [string]$WorkingDirectory,
        [string]$Executable,
        [string[]]$Arguments = @()
    )

    $resolvedExecutable = $Executable
    if (-not [System.IO.Path]::IsPathRooted($resolvedExecutable)) {
        $hasRelativePathSegment = $resolvedExecutable.Contains("\") -or $resolvedExecutable.Contains("/")
        if ($hasRelativePathSegment) {
            $candidateExecutable = Normalize-NPDevPath (Join-Path $WorkingDirectory $resolvedExecutable)
            if (Test-Path -LiteralPath $candidateExecutable -PathType Leaf) {
                $resolvedExecutable = $candidateExecutable
            }
            else {
                throw ("Executable not found: " + $candidateExecutable)
            }
        }
        else {
            $commandInfo = Get-Command $resolvedExecutable -ErrorAction Stop
            $resolvedExecutable = $commandInfo.Source
        }
    }

    $process = New-Object System.Diagnostics.Process
    $process.StartInfo = New-Object System.Diagnostics.ProcessStartInfo
    $process.StartInfo.FileName = $resolvedExecutable
    $process.StartInfo.WorkingDirectory = $WorkingDirectory
    $process.StartInfo.UseShellExecute = $false
    $process.StartInfo.RedirectStandardOutput = $true
    $process.StartInfo.RedirectStandardError = $true
    if (Test-NPDevGradleExecutable $Executable) {
        $process.StartInfo.Environment["GRADLE_USER_HOME"] = Get-NPDevGradleUserHome $WorkingDirectory
    }

    $quotedArguments = @($Arguments | ForEach-Object {
            if ($_ -match '[\s"]') {
                '"' + ($_ -replace '"', '\"') + '"'
            }
            else {
                $_
            }
        })
    $process.StartInfo.Arguments = $quotedArguments -join " "

    [void]$process.Start()
    $stdout = $process.StandardOutput.ReadToEnd()
    $stderr = $process.StandardError.ReadToEnd()
    $process.WaitForExit()

    $output = @()
    if (-not [string]::IsNullOrWhiteSpace($stdout)) {
        $output += ($stdout -split "(`r`n|`n|`r)")
    }
    if (-not [string]::IsNullOrWhiteSpace($stderr)) {
        $output += ($stderr -split "(`r`n|`n|`r)")
    }

    $output = @($output | Where-Object { -not [string]::IsNullOrWhiteSpace($_) })
    $exitCode = [int]$process.ExitCode

    return [pscustomobject]@{
        ExitCode = $exitCode
        Output = @($output | ForEach-Object { [string]$_ })
    }
}

function Get-NPDevSampleCatalog([string]$WorkspaceRoot) {
    $catalogPath = Resolve-NPDevWorkspacePath $WorkspaceRoot "NPDevSamples\sample-catalog.json"
    Ensure-NPDevFile $catalogPath "NPDev sample catalog"
    return Get-Content -LiteralPath $catalogPath -Raw | ConvertFrom-Json
}

function Get-NPDevSampleEntries([string]$WorkspaceRoot) {
    $catalog = Get-NPDevSampleCatalog $WorkspaceRoot
    return @($catalog.samples)
}

function Get-NPDevDefaultSampleId([string]$WorkspaceRoot = "") {
    $samples = Get-NPDevSampleEntries $WorkspaceRoot
    $golden = @($samples | Where-Object { [string]$_.verificationTarget -eq "runtimehost-golden-regression" } | Select-Object -First 1)
    if ($golden.Count -gt 0) {
        return [string]$golden[0].id
    }
    return [string](@($samples | Select-Object -First 1)[0].id)
}

function Get-NPDevCanonicalSampleId([string]$WorkspaceRoot = "") {
    $samples = Get-NPDevSampleEntries $WorkspaceRoot
    $canonical = @($samples | Where-Object { [string]$_.verificationTarget -eq "canonical-contract-regression" } | Select-Object -First 1)
    if ($canonical.Count -gt 0) {
        return [string]$canonical[0].id
    }
    return $null
}

function Get-NPDevReleaseSampleIds([string]$WorkspaceRoot = "") {
    $samples = Get-NPDevSampleEntries $WorkspaceRoot
    $releaseTargets = @(
        "canonical-contract-regression",
        "official-headless-regression",
        "runtimehost-golden-regression",
        "official-flow-regression",
        "tenant-isolation-regression"
    )
    return @(
        $samples |
        Where-Object { [string]$_.verificationTarget -in $releaseTargets } |
        ForEach-Object { [string]$_.id }
    )
}

function Get-NPDevOfficialSampleIds([string]$WorkspaceRoot = "") {
    $samples = Get-NPDevSampleEntries $WorkspaceRoot
    return @(
        $samples |
        Where-Object { [string]$_.kind -eq "official-sample" } |
        ForEach-Object { [string]$_.id }
    )
}

function Get-NPDevWorkspaceRelativePath([string]$WorkspaceRoot, [string]$TargetPath) {
    $normalizedRoot = Normalize-NPDevPath $WorkspaceRoot
    $normalizedTarget = Normalize-NPDevPath $TargetPath
    $relativePath = [System.IO.Path]::GetRelativePath($normalizedRoot, $normalizedTarget)
    return $relativePath.Replace("/", "\")
}

