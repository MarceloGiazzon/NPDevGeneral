[CmdletBinding()]
param(
    [string]$WorkspaceRoot = 'D:\WorkSpace\NPDev_General',
    [string]$CommitMessage = 'Stream CI gate command output and remove duplicate generator preflight',
    [switch]$SkipPush
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Write-Step([string]$Message) {
    Write-Host ''
    Write-Host ('== ' + $Message + ' ==') -ForegroundColor Cyan
}
function Write-Ok([string]$Message) {
    Write-Host ('OK    ' + $Message) -ForegroundColor Green
}
function Invoke-Git {
    param([Parameter(Mandatory = $true)][string[]]$GitArgs)
    & git @GitArgs
    if ($LASTEXITCODE -ne 0) {
        throw ("git failed with exit code {0}: git {1}" -f $LASTEXITCODE, ($GitArgs -join ' '))
    }
}

$WorkspaceRoot = (Resolve-Path $WorkspaceRoot).Path
Set-Location $WorkspaceRoot

Write-Step 'Validate repository state'
$currentBranch = (& git branch --show-current).Trim()
if ($currentBranch -ne 'main') {
    throw "Expected branch 'main', but current branch is '$currentBranch'."
}
$status = (& git status --short)
if (-not [string]::IsNullOrWhiteSpace(($status -join "`n"))) {
    throw "Working tree must be clean before applying this CI fix.`n$($status -join "`n")"
}

$commonPath = Join-Path $WorkspaceRoot 'scripts\npdev-common.ps1'
$workflowPath = Join-Path $WorkspaceRoot '.github\workflows\npdev-release-gate.yml'

if (-not (Test-Path -LiteralPath $commonPath)) {
    throw "Common helper script not found: $commonPath"
}
if (-not (Test-Path -LiteralPath $workflowPath)) {
    throw "Workflow file not found: $workflowPath"
}

Write-Step 'Patch Invoke-NPDevCommandCapture to stream output while capturing'
$common = Get-Content -LiteralPath $commonPath -Raw

$newFunction = @'
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
    $process.StartInfo.CreateNoWindow = $true

    if (Test-NPDevGradleExecutable $Executable) {
        $process.StartInfo.Environment["GRADLE_USER_HOME"] = Get-NPDevGradleUserHome $WorkingDirectory
        if (-not $process.StartInfo.Environment.ContainsKey("GRADLE_OPTS")) {
            $process.StartInfo.Environment["GRADLE_OPTS"] = "-Dorg.gradle.daemon=false -Dorg.gradle.console=plain -Dorg.gradle.workers.max=2 -Dorg.gradle.vfs.watch=false"
        }
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

    $outputLines = [System.Collections.Concurrent.ConcurrentQueue[string]]::new()

    $stdoutHandler = [System.Diagnostics.DataReceivedEventHandler]{
        param($sender, $eventArgs)
        if (-not [string]::IsNullOrWhiteSpace($eventArgs.Data)) {
            $line = [string]$eventArgs.Data
            $outputLines.Enqueue($line)
            Write-Host $line
        }
    }

    $stderrHandler = [System.Diagnostics.DataReceivedEventHandler]{
        param($sender, $eventArgs)
        if (-not [string]::IsNullOrWhiteSpace($eventArgs.Data)) {
            $line = [string]$eventArgs.Data
            $outputLines.Enqueue($line)
            Write-Host $line
        }
    }

    [void]$process.add_OutputDataReceived($stdoutHandler)
    [void]$process.add_ErrorDataReceived($stderrHandler)

    [void]$process.Start()
    $process.BeginOutputReadLine()
    $process.BeginErrorReadLine()
    $process.WaitForExit()

    # Give async output handlers a short chance to flush final lines.
    Start-Sleep -Milliseconds 250

    $captured = [System.Collections.Generic.List[string]]::new()
    $line = $null
    while ($outputLines.TryDequeue([ref]$line)) {
        if (-not [string]::IsNullOrWhiteSpace($line)) {
            [void]$captured.Add([string]$line)
        }
    }

    $exitCode = [int]$process.ExitCode

    return [pscustomobject]@{
        ExitCode = $exitCode
        Output = @($captured | ForEach-Object { [string]$_ })
    }
}
'@

$functionPattern = '(?ms)function Invoke-NPDevCommandCapture \{.*?\r?\n\}\r?\n\r?\nfunction Get-NPDevSampleCatalog'
if ($common -notmatch $functionPattern) {
    throw "Could not locate Invoke-NPDevCommandCapture function boundary in scripts\npdev-common.ps1"
}

$common = [regex]::Replace($common, $functionPattern, ($newFunction + "`r`n`r`nfunction Get-NPDevSampleCatalog"), 1)
Set-Content -LiteralPath $commonPath -Value $common -Encoding UTF8
Write-Ok 'Patched Invoke-NPDevCommandCapture to stream output live.'

Write-Step 'Patch workflow: remove duplicate generator preflight and lengthen heartbeat safely'
$workflow = Get-Content -LiteralPath $workflowPath -Raw

# Remove the dedicated generator preflight block if present. The full beta gate already runs generator,
# and with streaming command capture it will now show Gradle output live.
$generatorPreflightPattern = '(?ms)\r?\n      - name: Preflight NPDevGenerator gate\r?\n        timeout-minutes: 20\r?\n        run: \|\r?\n          & pwsh -NoProfile -ExecutionPolicy Bypass `\r?\n            -File "\$env:NPDEV_WORKSPACE\\scripts\\quality\\run-generator-gate\.ps1" `\r?\n            -WorkspaceRoot "\$env:NPDEV_WORKSPACE"\r?\n'
if ($workflow -match $generatorPreflightPattern) {
    $workflow = [regex]::Replace($workflow, $generatorPreflightPattern, "`r`n", 1)
    Write-Ok 'Removed duplicate Preflight NPDevGenerator gate step.'
}
else {
    Write-Ok 'No duplicate Preflight NPDevGenerator gate step found.'
}

# Keep preflight wrappers and Java toolchain. Make beta gate wrapper more tolerant now that live output streams.
$workflow = $workflow -replace '-IdleTimeoutMinutes 12 `', '-IdleTimeoutMinutes 20 `'
$workflow = $workflow -replace '-TotalTimeoutMinutes 75 `', '-TotalTimeoutMinutes 105 `'
$workflow = $workflow -replace 'timeout-minutes: 80', 'timeout-minutes: 110'

Set-Content -LiteralPath $workflowPath -Value $workflow -Encoding UTF8
Write-Ok 'Updated CI beta gate timeout/heartbeat settings.'

Write-Step 'Commit streaming CI fix'
git diff -- scripts/npdev-common.ps1 .github/workflows/npdev-release-gate.yml

Invoke-Git -GitArgs @('add', 'scripts/npdev-common.ps1', '.github/workflows/npdev-release-gate.yml')

$statusAfterAdd = (& git status --short)
if ([string]::IsNullOrWhiteSpace(($statusAfterAdd -join "`n"))) {
    Write-Ok 'No changes to commit; streaming CI fix already present.'
}
else {
    Invoke-Git -GitArgs @('commit', '-m', $CommitMessage)
    Write-Ok 'Committed streaming CI gate fix.'
}

if ($SkipPush) {
    Write-Ok 'Skipping push because -SkipPush was provided.'
    exit 0
}

Write-Step 'Push main and tags'
Invoke-Git -GitArgs @('push', '-u', 'origin', 'main')
Invoke-Git -GitArgs @('push', 'origin', '--tags')

Write-Step 'Verify remote refs'
git ls-remote --heads origin main
git ls-remote --tags origin 'npdev-official-beta-*'

Write-Ok 'Streaming CI gate fix pushed. Watch GitHub Actions next.'
