param(
    [Parameter(Mandatory = $true)][string]$Executable,
    [string[]]$Arguments = @(),
    [string]$ArgumentsJson,
    [string]$WorkingDirectory = ".",
    [string]$PolicyPath = "scripts/policy/ai-command-policy.json",
    [string]$ResultPath,
    [int]$TimeoutSeconds = 0
)

$ErrorActionPreference = "Stop"

function Read-JsonFile {
    param([string]$Path)
    return Get-Content -Raw -LiteralPath $Path | ConvertFrom-Json
}

function Get-ExecutableName {
    param([string]$Value)
    $name = [System.IO.Path]::GetFileName($Value).ToLowerInvariant()
    if ($name.EndsWith(".exe")) {
        return [System.IO.Path]::GetFileNameWithoutExtension($name).ToLowerInvariant()
    }
    return $name
}

function Convert-ToResolvedPath {
    param([string]$PathValue)
    $resolved = Resolve-Path -LiteralPath $PathValue -ErrorAction Stop
    return $resolved.Path
}

function Test-IsUnderRoot {
    param([string]$PathValue, [string]$RootValue)
    $fullPath = [System.IO.Path]::GetFullPath($PathValue)
    $fullRoot = [System.IO.Path]::GetFullPath($RootValue)
    $comparison = [System.StringComparison]::OrdinalIgnoreCase
    if ($fullPath.Equals($fullRoot, $comparison)) {
        return $true
    }
    if (-not $fullRoot.EndsWith([System.IO.Path]::DirectorySeparatorChar)) {
        $fullRoot += [System.IO.Path]::DirectorySeparatorChar
    }
    return $fullPath.StartsWith($fullRoot, $comparison)
}

function Redact-Text {
    param([string]$Text, [object]$Policy)
    if ([string]::IsNullOrEmpty($Text)) {
        return ""
    }
    $replacement = [string]$Policy.redaction.replacement
    $redacted = $Text
    foreach ($namePattern in @($Policy.redaction.secretNamePatterns)) {
        $pattern = "(?i)([A-Z0-9_]*" + [regex]::Escape([string]$namePattern) + "[A-Z0-9_]*\s*[=:]\s*)(\S+)"
        $redacted = [regex]::Replace($redacted, $pattern, ('$1' + $replacement))
    }
    return $redacted
}

function Get-CommandErrorCode {
    param([AllowNull()][string]$Reason)
    if ([string]::IsNullOrWhiteSpace($Reason)) {
        return $null
    }
    switch ($Reason) {
        "EXECUTABLE_NOT_ALLOWED" { return "NPDEV_COMMAND_EXECUTABLE_NOT_ALLOWED" }
        "EXECUTABLE_BLOCKED" { return "NPDEV_COMMAND_EXECUTABLE_NOT_ALLOWED" }
        "EXECUTABLE_OUTSIDE_WORKSPACE" { return "NPDEV_COMMAND_EXECUTABLE_OUTSIDE_SANDBOX" }
        "WORKING_DIRECTORY_OUTSIDE_WORKSPACE" { return "NPDEV_COMMAND_WORKDIR_OUTSIDE_SANDBOX" }
        "ARGUMENT_BLOCKED" { return "NPDEV_COMMAND_ARGUMENT_DENIED" }
        "NETWORK_BLOCKED" { return "NPDEV_COMMAND_NETWORK_BLOCKED" }
        "PWSH_COMMAND_MODE_BLOCKED" { return "NPDEV_COMMAND_ARGUMENT_DENIED" }
        "PWSH_FILE_OUTSIDE_ALLOWED_ROOT" { return "NPDEV_COMMAND_ARGUMENT_DENIED" }
        default { return "NPDEV_COMMAND_DENIED" }
    }
}

function Write-Result {
    param([object]$Result)
    if (-not [string]::IsNullOrWhiteSpace($ResultPath)) {
        $resultDirectory = Split-Path -Parent $ResultPath
        if (-not [string]::IsNullOrWhiteSpace($resultDirectory)) {
            New-Item -ItemType Directory -Force -Path $resultDirectory | Out-Null
        }
        $Result | ConvertTo-Json -Depth 20 | Set-Content -LiteralPath $ResultPath -Encoding UTF8
    }
    $Result | ConvertTo-Json -Depth 20
}

function New-Result {
    param(
        [string]$Status,
        [AllowNull()][object]$ExitCode,
        [AllowNull()][string]$BlockedReason,
        [bool]$TimedOut,
        [int]$DurationMs,
        [string]$Stdout,
        [string]$Stderr
    )
    return [pscustomobject]@{
        schemaVersion = "npdev-controlled-command-result.v1"
        status = $Status
        executable = $Executable
        arguments = @($Arguments)
        workingDirectory = $resolvedWorkingDirectory
        exitCode = $ExitCode
        blockedReason = $BlockedReason
        errorCode = Get-CommandErrorCode $BlockedReason
        timedOut = $TimedOut
        durationMs = $DurationMs
        stdout = $Stdout
        stderr = $Stderr
    }
}

function Initialize-MinimalEnvironment {
    param([System.Diagnostics.ProcessStartInfo]$StartInfo, [object]$Policy)
    if (-not [bool]$Policy.minimalEnvironment.enabled) {
        return
    }
    $allowedVariables = @($Policy.minimalEnvironment.allowedVariables | ForEach-Object { [string]$_ })
    $currentEnvironment = [Environment]::GetEnvironmentVariables()
    $captured = @{}
    foreach ($entry in $currentEnvironment.GetEnumerator()) {
        $name = [string]$entry.Key
        if (@($allowedVariables | Where-Object { [string]::Equals($_, $name, [System.StringComparison]::OrdinalIgnoreCase) }).Count -gt 0) {
            $captured[$name] = [string]$entry.Value
        }
    }
    $StartInfo.Environment.Clear()
    foreach ($entry in $captured.GetEnumerator()) {
        $StartInfo.Environment[$entry.Key] = [string]$entry.Value
    }
}

$workspaceRoot = (Resolve-Path ".").Path
$policy = Read-JsonFile $PolicyPath
if (-not [string]::IsNullOrWhiteSpace($ArgumentsJson)) {
    $Arguments = @(ConvertFrom-Json -InputObject $ArgumentsJson)
}
$resolvedWorkingDirectory = Convert-ToResolvedPath $WorkingDirectory
$timeoutLimit = if ($TimeoutSeconds -gt 0) { $TimeoutSeconds } else { [int]$policy.defaultTimeoutSeconds }
if ($timeoutLimit -gt [int]$policy.maxTimeoutSeconds) {
    $timeoutLimit = [int]$policy.maxTimeoutSeconds
}

$blockedReason = $null
$executableName = Get-ExecutableName $Executable
if (@($policy.allowedExecutables) -notcontains $executableName) {
    $blockedReason = "EXECUTABLE_NOT_ALLOWED"
}
elseif (@($policy.blockedExecutables) -contains $executableName) {
    $blockedReason = "EXECUTABLE_BLOCKED"
}
elseif ([System.IO.Path]::IsPathRooted($Executable) -and -not (Test-IsUnderRoot ([System.IO.Path]::GetFullPath($Executable)) $workspaceRoot)) {
    $blockedReason = "EXECUTABLE_OUTSIDE_WORKSPACE"
}
elseif (-not (Test-IsUnderRoot $resolvedWorkingDirectory $workspaceRoot)) {
    $blockedReason = "WORKING_DIRECTORY_OUTSIDE_WORKSPACE"
}
else {
    $argumentText = (@($Arguments) -join " ")
    if ($argumentText -match "https?://(?!localhost(?::|/|$)|127\.0\.0\.1(?::|/|$)|\[::1\](?::|/|$))") {
        $blockedReason = "NETWORK_BLOCKED"
    }
    foreach ($pattern in @($policy.blockedArgumentPatterns)) {
        if ($null -ne $blockedReason) {
            break
        }
        if ($argumentText -match [string]$pattern) {
            $blockedReason = "ARGUMENT_BLOCKED"
            break
        }
    }
}

if ($null -eq $blockedReason -and $executableName -in @("pwsh", "powershell")) {
    $hasCommandMode = @($Arguments | Where-Object { $_ -in @("-Command", "-EncodedCommand", "/c") }).Count -gt 0
    if ($hasCommandMode -and -not [bool]$policy.pwsh.allowCommandMode) {
        $blockedReason = "PWSH_COMMAND_MODE_BLOCKED"
    }
    $fileIndex = [array]::IndexOf([object[]]$Arguments, "-File")
    if ($null -eq $blockedReason -and $fileIndex -ge 0 -and $fileIndex -lt ($Arguments.Count - 1)) {
        $scriptPath = [string]$Arguments[$fileIndex + 1]
        if ([System.IO.Path]::IsPathRooted($scriptPath)) {
            $candidateScriptPath = [System.IO.Path]::GetFullPath($scriptPath)
        }
        else {
            $candidateScriptPath = [System.IO.Path]::GetFullPath((Join-Path $workspaceRoot $scriptPath))
        }
        $allowedFileRoot = $false
        foreach ($root in @($policy.pwsh.allowedFileRoots)) {
            $candidateRoot = [System.IO.Path]::GetFullPath((Join-Path $workspaceRoot ([string]$root)))
            if (Test-IsUnderRoot $candidateScriptPath $candidateRoot) {
                $allowedFileRoot = $true
                break
            }
        }
        if (-not $allowedFileRoot) {
            $blockedReason = "PWSH_FILE_OUTSIDE_ALLOWED_ROOT"
        }
    }
}

if ($null -ne $blockedReason) {
    $result = New-Result -Status "blocked" -ExitCode $null -BlockedReason $blockedReason -TimedOut $false -DurationMs 0 -Stdout "" -Stderr ""
    Write-Result $result | Out-Host
    exit 2
}

$tempRoot = Join-Path $workspaceRoot "scripts/reports/tmp/controlled-command-runner"
New-Item -ItemType Directory -Force -Path $tempRoot | Out-Null
$stdoutPath = Join-Path $tempRoot ([guid]::NewGuid().ToString() + ".stdout.txt")
$stderrPath = Join-Path $tempRoot ([guid]::NewGuid().ToString() + ".stderr.txt")
$stopwatch = [System.Diagnostics.Stopwatch]::StartNew()
$process = [System.Diagnostics.Process]::new()
$process.StartInfo = [System.Diagnostics.ProcessStartInfo]::new()
$process.StartInfo.FileName = $Executable
$process.StartInfo.WorkingDirectory = $resolvedWorkingDirectory
$process.StartInfo.UseShellExecute = $false
$process.StartInfo.RedirectStandardOutput = $true
$process.StartInfo.RedirectStandardError = $true
foreach ($argument in @($Arguments)) {
    [void]$process.StartInfo.ArgumentList.Add([string]$argument)
}
Initialize-MinimalEnvironment -StartInfo $process.StartInfo -Policy $policy
[void]$process.Start()
$stdoutTask = $process.StandardOutput.ReadToEndAsync()
$stderrTask = $process.StandardError.ReadToEndAsync()
$timedOut = -not $process.WaitForExit($timeoutLimit * 1000)
if ($timedOut) {
    try {
        $process.Kill($true)
    }
    catch {
        $process.Kill()
    }
}
$process.WaitForExit()
$stopwatch.Stop()

$stdout = $stdoutTask.GetAwaiter().GetResult()
$stderr = $stderrTask.GetAwaiter().GetResult()
$stdout | Set-Content -LiteralPath $stdoutPath -Encoding UTF8
$stderr | Set-Content -LiteralPath $stderrPath -Encoding UTF8
$stdout = Redact-Text $stdout $policy
$stderr = Redact-Text $stderr $policy
$exitCode = if ($timedOut) { $null } else { $process.ExitCode }
$status = if ($timedOut) { "timed-out" } elseif ($process.ExitCode -eq 0) { "passed" } else { "failed" }

$result = New-Result -Status $status -ExitCode $exitCode -BlockedReason $null -TimedOut $timedOut -DurationMs ([int]$stopwatch.ElapsedMilliseconds) -Stdout $stdout -Stderr $stderr
Write-Result $result | Out-Host
if ($status -eq "passed") { exit 0 }
if ($status -eq "timed-out") { exit 3 }
exit 1
