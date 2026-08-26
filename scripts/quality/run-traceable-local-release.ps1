param(
    [string]$WorkspaceRoot = ".",
    [string]$SourceCommitSha = "",
    [string]$SourceBranch = "",
    [string]$RunId = "",
    [string]$ReportPath = "scripts/reports/out/traceable-local-release-report.json",
    [string]$FinalReleaseReportPath = "scripts/reports/out/beta0-final-release-check-report.json",
    [string]$CanonicalReleaseScript = "scripts/quality/run-beta0-final-release-check.ps1",
    [switch]$ContinueOnFailure
)

$ErrorActionPreference = "Stop"

function Convert-ToRepoPath {
    param([string]$Root, [string]$PathValue)
    if ([string]::IsNullOrWhiteSpace($PathValue)) { return "" }
    $resolvedRoot = [System.IO.Path]::GetFullPath($Root)
    $resolvedPath = [System.IO.Path]::GetFullPath((Join-Path $Root $PathValue))
    if ($resolvedPath.StartsWith($resolvedRoot, [System.StringComparison]::OrdinalIgnoreCase)) {
        return ($resolvedPath.Substring($resolvedRoot.Length).TrimStart("\", "/") -replace "\\", "/")
    }
    return ($resolvedPath -replace "\\", "/")
}

function Resolve-WorkspacePath {
    param([string]$Root, [string]$PathValue)
    if ([System.IO.Path]::IsPathRooted($PathValue)) {
        return [System.IO.Path]::GetFullPath($PathValue)
    }
    return [System.IO.Path]::GetFullPath((Join-Path $Root $PathValue))
}

function Invoke-GitText {
    param([string[]]$Arguments)
    try {
        $output = & git @Arguments 2>$null
        if ($LASTEXITCODE -ne 0) { return "" }
        return (($output | Out-String).Trim())
    }
    catch {
        return ""
    }
}

function Invoke-PwshScript {
    param(
        [string]$ScriptPath,
        [string[]]$Arguments,
        [string]$WorkingDirectory,
        [string]$StdoutPath,
        [string]$StderrPath
    )
    # Streams live to the host (visible in a CI log as it happens) instead of buffering the whole
    # child process's output in memory until it exits -- the previous ReadToEnd()-then-WaitForExit()
    # shape made a long-running canonical release script (which itself already emits per-gate
    # [HH:mm:ss] START/END progress lines) show up as ONE opaque hour-plus-silent step. Uses the
    # standard async OutputDataReceived/ErrorDataReceived event pattern rather than a second,
    # naive fix of just adding `| Out-Host` -- this exact area was already stalled once that way
    # (commit 5a10f6dc, "Avoid final release gate output pipeline stall", in the inner
    # run-beta0-final-release-check.ps1) and moved OFF piping for that reason. Async event
    # callbacks read both streams concurrently, so neither can block the other from draining --
    # the classic sequential-ReadToEnd() deadlock risk this replaces.
    $startedAt = (Get-Date).ToUniversalTime()
    $psi = [System.Diagnostics.ProcessStartInfo]::new()
    $psi.FileName = "pwsh"
    $psi.WorkingDirectory = $WorkingDirectory
    $psi.RedirectStandardOutput = $true
    $psi.RedirectStandardError = $true
    $psi.UseShellExecute = $false
    foreach ($arg in @("-NoProfile", "-File", $ScriptPath) + $Arguments) {
        $psi.ArgumentList.Add($arg)
    }

    $process = [System.Diagnostics.Process]::new()
    $process.StartInfo = $psi
    $process.EnableRaisingEvents = $true

    # .NET raises OutputDataReceived/ErrorDataReceived on thread-pool threads, and under a fast
    # burst of lines their PowerShell Register-ObjectEvent handlers can be DEQUEUED out of arrival
    # order (measured directly: a 40-line/200-char burst reordered 2 lines by a few positions,
    # both live on screen and in the captured content). Harmless for a live console glance, but the
    # log FILE is this repo's durable evidence artifact, so each line is tagged at capture time with
    # Stopwatch.GetTimestamp() -- a monotonic, lock-free hardware counter reading, far finer
    # resolution than DateTime.UtcNow -- and the file is written back sorted by that tag,
    # independent of whatever order the handlers happened to run in.
    $captured = [System.Collections.Concurrent.ConcurrentBag[pscustomobject]]::new()

    $stdoutEvent = Register-ObjectEvent -InputObject $process -EventName OutputDataReceived -Action {
        if ($null -ne $EventArgs.Data) {
            $Event.MessageData.Add([pscustomobject]@{
                Ticks = [System.Diagnostics.Stopwatch]::GetTimestamp()
                Stream = "out"
                Text = $EventArgs.Data
            })
            Write-Host $EventArgs.Data
        }
    } -MessageData $captured
    $stderrEvent = Register-ObjectEvent -InputObject $process -EventName ErrorDataReceived -Action {
        if ($null -ne $EventArgs.Data) {
            $Event.MessageData.Add([pscustomobject]@{
                Ticks = [System.Diagnostics.Stopwatch]::GetTimestamp()
                Stream = "err"
                Text = $EventArgs.Data
            })
            Write-Host $EventArgs.Data -ForegroundColor Red
        }
    } -MessageData $captured

    try {
        $process.Start() | Out-Null
        $process.BeginOutputReadLine()
        $process.BeginErrorReadLine()
        $process.WaitForExit()
    }
    finally {
        Unregister-Event -SourceIdentifier $stdoutEvent.Name -ErrorAction SilentlyContinue
        Unregister-Event -SourceIdentifier $stderrEvent.Name -ErrorAction SilentlyContinue
        Remove-Job -Name $stdoutEvent.Name -ErrorAction SilentlyContinue
        Remove-Job -Name $stderrEvent.Name -ErrorAction SilentlyContinue
    }

    $finishedAt = (Get-Date).ToUniversalTime()
    $orderedCaptures = $captured | Sort-Object -Property Ticks
    $stdoutBuilder = [System.Text.StringBuilder]::new()
    $stderrBuilder = [System.Text.StringBuilder]::new()
    foreach ($line in $orderedCaptures) {
        if ($line.Stream -eq "out") { $stdoutBuilder.AppendLine($line.Text) | Out-Null }
        else { $stderrBuilder.AppendLine($line.Text) | Out-Null }
    }
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $StdoutPath) | Out-Null
    $stdoutBuilder.ToString() | Set-Content -LiteralPath $StdoutPath -Encoding UTF8
    $stderrBuilder.ToString() | Set-Content -LiteralPath $StderrPath -Encoding UTF8
    return [pscustomobject]@{
        startedAt = $startedAt.ToString("o")
        finishedAt = $finishedAt.ToString("o")
        durationSeconds = [int]([DateTimeOffset]$finishedAt - [DateTimeOffset]$startedAt).TotalSeconds
        exitCode = [int]$process.ExitCode
        stdoutPath = $StdoutPath
        stderrPath = $StderrPath
    }
}

function Read-JsonFile {
    param([string]$Path)
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) { return $null }
    return Get-Content -Raw -LiteralPath $Path | ConvertFrom-Json
}

function Get-FileHashOrNull {
    param([string]$Path)
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) { return $null }
    return (Get-FileHash -Algorithm SHA256 -LiteralPath $Path).Hash.ToLowerInvariant()
}

$workspaceRootPath = (Resolve-Path -LiteralPath $WorkspaceRoot).Path
Push-Location $workspaceRootPath
try {
    if ([string]::IsNullOrWhiteSpace($RunId)) {
        $RunId = "traceable-local-release-" + (Get-Date).ToUniversalTime().ToString("yyyyMMdd-HHmmssfff")
    }
    if ([string]::IsNullOrWhiteSpace($SourceCommitSha)) {
        $SourceCommitSha = Invoke-GitText @("rev-parse", "HEAD")
    }
    if ([string]::IsNullOrWhiteSpace($SourceBranch)) {
        $SourceBranch = Invoke-GitText @("branch", "--show-current")
    }

    $canonicalScriptPath = Resolve-WorkspacePath -Root $workspaceRootPath -PathValue $CanonicalReleaseScript
    $finalReportFullPath = Resolve-WorkspacePath -Root $workspaceRootPath -PathValue $FinalReleaseReportPath
    $reportFullPath = Resolve-WorkspacePath -Root $workspaceRootPath -PathValue $ReportPath
    $logRoot = Join-Path $workspaceRootPath "scripts/reports/out/traceable-local-release"
    $stdoutPath = Join-Path $logRoot ($RunId + "-stdout.log")
    $stderrPath = Join-Path $logRoot ($RunId + "-stderr.log")

    $arguments = @("-RunId", $RunId, "-ReportPath", $finalReportFullPath)
    if ($ContinueOnFailure) {
        $arguments += "-ContinueOnFailure"
    }

    $commandText = "pwsh -NoProfile -File " + (Convert-ToRepoPath -Root $workspaceRootPath -PathValue $canonicalScriptPath) + " " + ($arguments -join " ")
    $commandResult = Invoke-PwshScript -ScriptPath $canonicalScriptPath -Arguments $arguments -WorkingDirectory $workspaceRootPath -StdoutPath $stdoutPath -StderrPath $stderrPath
    $finalReport = Read-JsonFile $finalReportFullPath

    $blockers = [System.Collections.Generic.List[string]]::new()
    if ($commandResult.exitCode -ne 0) {
        $blockers.Add("Canonical release script exited nonzero.") | Out-Null
    }
    if ($null -eq $finalReport) {
        $blockers.Add("Canonical final release report was not produced.") | Out-Null
    }
    elseif ([string]$finalReport.overallStatus -ne "passed") {
        $blockers.Add("Canonical final release report overallStatus is not passed.") | Out-Null
    }
    if ($null -ne $finalReport -and -not [bool]$finalReport.officialReleaseEligible) {
        $blockers.Add("Canonical final release report officialReleaseEligible is false.") | Out-Null
    }
    if ($null -ne $finalReport -and -not [bool]$finalReport.beta0TagAllowed) {
        $blockers.Add("Canonical final release report beta0TagAllowed is false.") | Out-Null
    }

    $overallStatus = if ($blockers.Count -eq 0) { "passed" } else { "failed" }
    $report = [pscustomobject]@{
        schemaVersion = "npdev-traceable-local-release-report.v1"
        runId = $RunId
        generatedAt = (Get-Date).ToUniversalTime().ToString("o")
        scriptPath = "scripts/quality/run-traceable-local-release.ps1"
        workspaceRoot = $workspaceRootPath
        overallStatus = $overallStatus
        source = [pscustomobject]@{
            commitSha = $SourceCommitSha
            branch = $SourceBranch
            commitAvailable = -not [string]::IsNullOrWhiteSpace($SourceCommitSha)
            branchAvailable = -not [string]::IsNullOrWhiteSpace($SourceBranch)
            statusCommand = "git status --porcelain=v1"
            dirtyLineCount = @((Invoke-GitText @("status", "--porcelain=v1")) -split "`r?`n" | Where-Object { -not [string]::IsNullOrWhiteSpace($_) }).Count
        }
        canonicalRelease = [pscustomobject]@{
            scriptPath = Convert-ToRepoPath -Root $workspaceRootPath -PathValue $canonicalScriptPath
            command = $commandText
            exitCode = $commandResult.exitCode
            startedAt = $commandResult.startedAt
            finishedAt = $commandResult.finishedAt
            durationSeconds = $commandResult.durationSeconds
            stdoutLog = Convert-ToRepoPath -Root $workspaceRootPath -PathValue $stdoutPath
            stderrLog = Convert-ToRepoPath -Root $workspaceRootPath -PathValue $stderrPath
            finalReleaseReportPath = Convert-ToRepoPath -Root $workspaceRootPath -PathValue $finalReportFullPath
            finalReleaseReportSha256 = Get-FileHashOrNull $finalReportFullPath
            finalReleaseOverallStatus = if ($null -ne $finalReport) { [string]$finalReport.overallStatus } else { "missing" }
            officialReleaseEligible = $null -ne $finalReport -and [bool]$finalReport.officialReleaseEligible
            beta0TagAllowed = $null -ne $finalReport -and [bool]$finalReport.beta0TagAllowed
        }
        blockers = @($blockers)
    }

    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $reportFullPath) | Out-Null
    $report | ConvertTo-Json -Depth 40 | Set-Content -LiteralPath $reportFullPath -Encoding UTF8

    if ($overallStatus -eq "passed") {
        Write-Host ("Traceable local release passed. Report: " + (Convert-ToRepoPath -Root $workspaceRootPath -PathValue $reportFullPath))
        exit 0
    }

    Write-Error ("Traceable local release failed. Report: " + (Convert-ToRepoPath -Root $workspaceRootPath -PathValue $reportFullPath))
}
finally {
    Pop-Location
}
