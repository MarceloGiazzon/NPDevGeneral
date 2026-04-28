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

$outDir = Join-Path $WorkspaceRoot 'scripts\reports\out'
New-Item -ItemType Directory -Force -Path $outDir | Out-Null

$stdoutPath = Join-Path $outDir 'ci-beta-release-gate.stdout.log'
$stderrPath = Join-Path $outDir 'ci-beta-release-gate.stderr.log'
Remove-Item -LiteralPath $stdoutPath, $stderrPath -Force -ErrorAction SilentlyContinue
New-Item -ItemType File -Path $stdoutPath -Force | Out-Null
New-Item -ItemType File -Path $stderrPath -Force | Out-Null

Write-Host "INFO  Starting beta release gate with CI file-polling heartbeat wrapper."
Write-Host "INFO  WorkspaceRoot: $WorkspaceRoot"
Write-Host "INFO  Target: $target"
Write-Host "INFO  SourceCommitSha: $SourceCommitSha"
Write-Host "INFO  SourceBranch: $SourceBranch"
Write-Host "INFO  SourceDirty: $dirtyText"
Write-Host "INFO  SourceDirtySwitch: $sourceDirtySwitch"
Write-Host "INFO  TotalTimeoutMinutes: $TotalTimeoutMinutes"
Write-Host "INFO  HeartbeatSeconds: $HeartbeatSeconds"
Write-Host "INFO  StdoutLog: $stdoutPath"
Write-Host "INFO  StderrLog: $stderrPath"

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

$process = Start-Process `
    -FilePath $pwsh `
    -ArgumentList $arguments `
    -WorkingDirectory $WorkspaceRoot `
    -PassThru `
    -NoNewWindow `
    -RedirectStandardOutput $stdoutPath `
    -RedirectStandardError $stderrPath

$start = Get-Date
$heartbeatCount = 0
$stdoutLineCount = 0
$stderrLineCount = 0

function Emit-NewLines {
    param(
        [string]$Path,
        [int]$StartIndex,
        [string]$Prefix
    )

    if (-not (Test-Path -LiteralPath $Path)) {
        return [pscustomobject]@{ Count = $StartIndex; Emitted = 0 }
    }

    $lines = @(Get-Content -LiteralPath $Path -ErrorAction SilentlyContinue)
    if ($lines.Count -le $StartIndex) {
        return [pscustomobject]@{ Count = $StartIndex; Emitted = 0 }
    }

    for ($i = $StartIndex; $i -lt $lines.Count; $i++) {
        if ([string]::IsNullOrWhiteSpace($Prefix)) {
            Write-Host $lines[$i]
        }
        else {
            Write-Host ($Prefix + $lines[$i])
        }
    }

    return [pscustomobject]@{ Count = $lines.Count; Emitted = ($lines.Count - $StartIndex) }
}

while (-not $process.HasExited) {
    Start-Sleep -Seconds $HeartbeatSeconds

    $stdoutResult = Emit-NewLines -Path $stdoutPath -StartIndex $stdoutLineCount -Prefix ''
    $stdoutLineCount = [int]$stdoutResult.Count

    $stderrResult = Emit-NewLines -Path $stderrPath -StartIndex $stderrLineCount -Prefix 'STDERR: '
    $stderrLineCount = [int]$stderrResult.Count

    $heartbeatCount++
    $elapsed = [int]((Get-Date) - $start).TotalMinutes
    Write-Host "INFO  CI heartbeat #$heartbeatCount - beta gate still running. elapsed=${elapsed}m stdoutLines=$stdoutLineCount stderrLines=$stderrLineCount"

    if (((Get-Date) - $start).TotalMinutes -ge $TotalTimeoutMinutes) {
        Write-Host "ERROR Beta gate exceeded total timeout of $TotalTimeoutMinutes minute(s). Killing process."
        Stop-Process -Id $process.Id -Force -ErrorAction SilentlyContinue
        throw "Beta release gate total timeout."
    }
}

$process.WaitForExit()

$stdoutResult = Emit-NewLines -Path $stdoutPath -StartIndex $stdoutLineCount -Prefix ''
$stdoutLineCount = [int]$stdoutResult.Count
$stderrResult = Emit-NewLines -Path $stderrPath -StartIndex $stderrLineCount -Prefix 'STDERR: '
$stderrLineCount = [int]$stderrResult.Count

if ($process.ExitCode -ne 0) {
    Write-Host ''
    Write-Host '==== STDOUT tail ===='
    Get-Content -LiteralPath $stdoutPath -Tail 200 -ErrorAction SilentlyContinue

    Write-Host ''
    Write-Host '==== STDERR tail ===='
    Get-Content -LiteralPath $stderrPath -Tail 200 -ErrorAction SilentlyContinue

    throw "Beta release gate failed with exit code $($process.ExitCode)."
}

Write-Host "OK    Beta release gate completed through CI file-polling heartbeat wrapper."
