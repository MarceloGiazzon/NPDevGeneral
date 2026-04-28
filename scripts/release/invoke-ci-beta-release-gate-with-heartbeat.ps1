[CmdletBinding()]
param(
    [string]$WorkspaceRoot = '',
    [int]$IdleTimeoutMinutes = 12,
    [int]$TotalTimeoutMinutes = 75,
    [string]$SourceCommitSha = '',
    [string]$SourceBranch = '',
    [bool]$SourceDirty = $false,
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

$pwsh = 'C:\Program Files\PowerShell\7\pwsh.exe'
if (-not (Test-Path -LiteralPath $pwsh)) {
    $pwsh = 'pwsh'
}

$target = Join-Path $WorkspaceRoot 'scripts\quality\run-beta-release-gate.ps1'
if (-not (Test-Path -LiteralPath $target)) {
    throw "Beta release gate script not found: $target"
}

$outDir = Join-Path $WorkspaceRoot 'scripts\reports\out'
New-Item -ItemType Directory -Force -Path $outDir | Out-Null

$stdoutPath = Join-Path $outDir 'ci-beta-release-gate.stdout.log'
$stderrPath = Join-Path $outDir 'ci-beta-release-gate.stderr.log'
Remove-Item -LiteralPath $stdoutPath, $stderrPath -Force -ErrorAction SilentlyContinue

$args = @(
    '-NoProfile',
    '-ExecutionPolicy', 'Bypass',
    '-File', $target,
    '-WorkspaceRoot', $WorkspaceRoot,
    '-SourceCommitSha', $SourceCommitSha,
    '-SourceBranch', $SourceBranch,
    '-SourceDirty', ([string]$SourceDirty),
    '-SourceProvider', $SourceProvider,
    '-SourceRunId', $SourceRunId,
    '-SourceRunAttempt', $SourceRunAttempt,
    '-SourceWorkflow', $SourceWorkflow
)

Write-Host "INFO  Starting beta release gate with heartbeat wrapper."
Write-Host "INFO  Idle timeout minutes: $IdleTimeoutMinutes"
Write-Host "INFO  Total timeout minutes: $TotalTimeoutMinutes"
Write-Host "INFO  Target: $target"

$process = Start-Process `
    -FilePath $pwsh `
    -ArgumentList $args `
    -PassThru `
    -NoNewWindow `
    -RedirectStandardOutput $stdoutPath `
    -RedirectStandardError $stderrPath

$start = Get-Date
$lastOutput = Get-Date
$stdoutLineCount = 0
$stderrLineCount = 0

function Emit-NewLines {
    param(
        [string]$Path,
        [ref]$LineCount,
        [string]$Prefix
    )

    if (-not (Test-Path -LiteralPath $Path)) {
        return $false
    }

    $lines = @(Get-Content -LiteralPath $Path -ErrorAction SilentlyContinue)
    if ($lines.Count -le $LineCount.Value) {
        return $false
    }

    $newLines = $lines[$LineCount.Value..($lines.Count - 1)]
    foreach ($line in $newLines) {
        if ([string]::IsNullOrWhiteSpace($Prefix)) {
            Write-Host $line
        }
        else {
            Write-Host "$Prefix$line"
        }
    }

    $LineCount.Value = $lines.Count
    return $true
}

while (-not $process.HasExited) {
    Start-Sleep -Seconds 20

    $changed = $false
    if (Emit-NewLines -Path $stdoutPath -LineCount ([ref]$stdoutLineCount) -Prefix '') {
        $changed = $true
    }
    if (Emit-NewLines -Path $stderrPath -LineCount ([ref]$stderrLineCount) -Prefix 'STDERR: ') {
        $changed = $true
    }

    if ($changed) {
        $lastOutput = Get-Date
    }
    else {
        $idle = [int]((Get-Date) - $lastOutput).TotalMinutes
        Write-Host "INFO  Beta gate still running. No new output for $idle minute(s)."
    }

    if (((Get-Date) - $lastOutput).TotalMinutes -ge $IdleTimeoutMinutes) {
        Write-Host "ERROR Beta gate produced no output for $IdleTimeoutMinutes minute(s). Killing process."
        Stop-Process -Id $process.Id -Force -ErrorAction SilentlyContinue

        Write-Host ''
        Write-Host '==== STDOUT tail ===='
        if (Test-Path -LiteralPath $stdoutPath) {
            Get-Content -LiteralPath $stdoutPath -Tail 120
        }

        Write-Host ''
        Write-Host '==== STDERR tail ===='
        if (Test-Path -LiteralPath $stderrPath) {
            Get-Content -LiteralPath $stderrPath -Tail 120
        }

        throw "Beta release gate idle timeout."
    }

    if (((Get-Date) - $start).TotalMinutes -ge $TotalTimeoutMinutes) {
        Write-Host "ERROR Beta gate exceeded total timeout of $TotalTimeoutMinutes minute(s). Killing process."
        Stop-Process -Id $process.Id -Force -ErrorAction SilentlyContinue
        throw "Beta release gate total timeout."
    }
}

# Emit remaining lines.
[void](Emit-NewLines -Path $stdoutPath -LineCount ([ref]$stdoutLineCount) -Prefix '')
[void](Emit-NewLines -Path $stderrPath -LineCount ([ref]$stderrLineCount) -Prefix 'STDERR: ')

if ($process.ExitCode -ne 0) {
    Write-Host ''
    Write-Host '==== STDOUT tail ===='
    if (Test-Path -LiteralPath $stdoutPath) {
        Get-Content -LiteralPath $stdoutPath -Tail 120
    }

    Write-Host ''
    Write-Host '==== STDERR tail ===='
    if (Test-Path -LiteralPath $stderrPath) {
        Get-Content -LiteralPath $stderrPath -Tail 120
    }

    throw "Beta release gate failed with exit code $($process.ExitCode)."
}

Write-Host "OK    Beta release gate completed through heartbeat wrapper."
