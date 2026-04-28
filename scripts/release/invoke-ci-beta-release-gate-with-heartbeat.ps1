[CmdletBinding()]
param(
    [string]$WorkspaceRoot = '',
    [int]$TotalTimeoutMinutes = 160,
    [int]$HeartbeatSeconds = 60,
    [string]$SourceCommitSha = '',
    [string]$SourceBranch = '',
    [string]$SourceDirty = 'false',
    [string]$SourceProvider = 'github-actions',
    [string]$SourceRunId = '',
    [string]$SourceRunAttempt = '',
    [string]$SourceWorkflow = ''
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

if ([string]::IsNullOrWhiteSpace($WorkspaceRoot)) {
    $WorkspaceRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
}
else {
    $WorkspaceRoot = (Resolve-Path $WorkspaceRoot).Path
}

$target = Join-Path $WorkspaceRoot 'scripts\quality\run-beta-release-gate.ps1'
if (-not (Test-Path -LiteralPath $target)) {
    throw "Beta release gate script not found: $target"
}

$pwsh = 'C:\Program Files\PowerShell\7\pwsh.exe'
if (-not (Test-Path -LiteralPath $pwsh)) {
    $pwsh = 'pwsh'
}

$dirtyText = ([string]$SourceDirty).Trim().ToLowerInvariant()
switch ($dirtyText) {
    'true'  { $sourceDirtySwitch = '-SourceDirty:$true' }
    '1'     { $sourceDirtySwitch = '-SourceDirty:$true' }
    'yes'   { $sourceDirtySwitch = '-SourceDirty:$true' }
    'false' { $sourceDirtySwitch = '-SourceDirty:$false' }
    '0'     { $sourceDirtySwitch = '-SourceDirty:$false' }
    'no'    { $sourceDirtySwitch = '-SourceDirty:$false' }
    default { throw "Invalid SourceDirty value: $SourceDirty" }
}

Write-Host "INFO  Starting beta release gate with CI heartbeat wrapper."
Write-Host "INFO  WorkspaceRoot: $WorkspaceRoot"
Write-Host "INFO  Target: $target"
Write-Host "INFO  SourceCommitSha: $SourceCommitSha"
Write-Host "INFO  SourceBranch: $SourceBranch"
Write-Host "INFO  SourceDirty: $dirtyText"
Write-Host "INFO  SourceDirtySwitch: $sourceDirtySwitch"
Write-Host "INFO  TotalTimeoutMinutes: $TotalTimeoutMinutes"
Write-Host "INFO  HeartbeatSeconds: $HeartbeatSeconds"

$arguments = @(
    '-NoProfile',
    '-ExecutionPolicy', 'Bypass',
    '-File', $target,
    '-WorkspaceRoot', $WorkspaceRoot,
    '-SourceCommitSha', $SourceCommitSha,
    '-SourceBranch', $SourceBranch,
    $sourceDirtySwitch,
    '-SourceProvider', $SourceProvider,
    '-SourceRunId', $SourceRunId,
    '-SourceRunAttempt', $SourceRunAttempt,
    '-SourceWorkflow', $SourceWorkflow
)

$psi = [System.Diagnostics.ProcessStartInfo]::new()
$psi.FileName = $pwsh
$psi.UseShellExecute = $false
$psi.RedirectStandardOutput = $true
$psi.RedirectStandardError = $true
$psi.CreateNoWindow = $true

foreach ($arg in $arguments) {
    [void]$psi.ArgumentList.Add($arg)
}

$process = [System.Diagnostics.Process]::new()
$process.StartInfo = $psi

$script:CiLastOutput = Get-Date
$script:CiStdoutQueue = [System.Collections.Concurrent.ConcurrentQueue[string]]::new()
$script:CiStderrQueue = [System.Collections.Concurrent.ConcurrentQueue[string]]::new()

$stdoutHandler = [System.Diagnostics.DataReceivedEventHandler]{
    param($sender, $eventArgs)
    if ($null -ne $eventArgs.Data) {
        $script:CiLastOutput = Get-Date
        $script:CiStdoutQueue.Enqueue([string]$eventArgs.Data)
        Write-Host $eventArgs.Data
    }
}

$stderrHandler = [System.Diagnostics.DataReceivedEventHandler]{
    param($sender, $eventArgs)
    if ($null -ne $eventArgs.Data) {
        $script:CiLastOutput = Get-Date
        $script:CiStderrQueue.Enqueue([string]$eventArgs.Data)
        Write-Host ("STDERR: " + $eventArgs.Data)
    }
}

[void]$process.add_OutputDataReceived($stdoutHandler)
[void]$process.add_ErrorDataReceived($stderrHandler)

$started = $process.Start()
if (-not $started) {
    throw "Failed to start beta release gate process."
}

$process.BeginOutputReadLine()
$process.BeginErrorReadLine()

$start = Get-Date
$heartbeatCount = 0

while (-not $process.WaitForExit($HeartbeatSeconds * 1000)) {
    $heartbeatCount++
    $elapsed = [int]((Get-Date) - $start).TotalMinutes
    $idle = [int]((Get-Date) - $script:CiLastOutput).TotalMinutes

    Write-Host "INFO  CI heartbeat #$heartbeatCount - beta gate still running. elapsed=${elapsed}m idleOutput=${idle}m"

    if (((Get-Date) - $start).TotalMinutes -ge $TotalTimeoutMinutes) {
        Write-Host "ERROR Beta gate exceeded total timeout of $TotalTimeoutMinutes minute(s). Killing process."
        Stop-Process -Id $process.Id -Force -ErrorAction SilentlyContinue
        break
    }
}

$process.WaitForExit()
Start-Sleep -Milliseconds 300

function Show-CiTail {
    param(
        [string]$Title,
        [System.Collections.Concurrent.ConcurrentQueue[string]]$Queue
    )

    Write-Host ''
    Write-Host $Title
    $items = @($Queue.ToArray())
    if ($items.Count -eq 0) {
        Write-Host '(no lines captured)'
        return
    }

    $items | Select-Object -Last 200 | ForEach-Object {
        Write-Host $_
    }
}

if ($process.ExitCode -ne 0) {
    Show-CiTail -Title '==== STDOUT tail ====' -Queue $script:CiStdoutQueue
    Show-CiTail -Title '==== STDERR tail ====' -Queue $script:CiStderrQueue
    throw "Beta release gate failed with exit code $($process.ExitCode)."
}

Write-Host "OK    Beta release gate completed through CI heartbeat wrapper."
