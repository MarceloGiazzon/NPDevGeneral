param(
    [string]$RunId = "",
    [string]$ReportPath = "scripts/reports/out/frontend-gate-report.json",
    [string]$WorkspaceRoot = "",
    [string]$EditorRoot = "NPDevEditor"
)

$ErrorActionPreference = "Stop"

function Resolve-UnderRoot {
    param(
        [string]$Root,
        [string]$Path
    )
    if ([System.IO.Path]::IsPathRooted($Path)) {
        return $Path
    }
    return Join-Path $Root $Path
}

function Convert-ToRepoPath {
    param(
        [string]$Root,
        [string]$Path
    )
    $full = [System.IO.Path]::GetFullPath($Path)
    $rootFull = [System.IO.Path]::GetFullPath($Root).TrimEnd([System.IO.Path]::DirectorySeparatorChar, [System.IO.Path]::AltDirectorySeparatorChar)
    if ($full.StartsWith($rootFull, [System.StringComparison]::OrdinalIgnoreCase)) {
        return $full.Substring($rootFull.Length).TrimStart([System.IO.Path]::DirectorySeparatorChar, [System.IO.Path]::AltDirectorySeparatorChar).Replace("\", "/")
    }
    return $full.Replace("\", "/")
}

function Add-Failure {
    param(
        [string]$Code,
        [string]$Message,
        [string]$Path = "",
        [object]$Details = $null
    )
    $script:failures += [pscustomobject]@{
        code = $Code
        message = $Message
        path = $Path
        details = $Details
    }
}

function Get-JsonObject {
    param([string]$Path)
    try {
        return Get-Content -Raw -LiteralPath $Path | ConvertFrom-Json
    }
    catch {
        Add-Failure -Code "invalid-json" -Message ("Could not parse JSON: " + $_.Exception.Message) -Path (Convert-ToRepoPath -Root $workspaceRoot -Path $Path)
        return $null
    }
}

function Get-HashRecord {
    param([string]$Path)
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        return [pscustomobject]@{
            path = (Convert-ToRepoPath -Root $workspaceRoot -Path $Path)
            exists = $false
            sha256 = $null
        }
    }
    return [pscustomobject]@{
        path = (Convert-ToRepoPath -Root $workspaceRoot -Path $Path)
        exists = $true
        sha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath $Path).Hash.ToLowerInvariant()
    }
}

function Get-OutputTail {
    param(
        [string]$Path,
        [int]$LineCount = 80
    )
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        return @()
    }
    return @(Get-Content -LiteralPath $Path -Tail $LineCount)
}

function Invoke-VersionProbe {
    param(
        [string]$Name,
        [string]$Executable,
        [string[]]$Arguments
    )
    $startedAt = (Get-Date).ToUniversalTime()
    $output = @()
    $exitCode = $null
    try {
        $ErrorActionPreference = "Continue"
        $output = @(& $Executable @Arguments 2>&1)
        $exitCode = $LASTEXITCODE
        $ErrorActionPreference = "Stop"
    }
    catch {
        $ErrorActionPreference = "Stop"
        $output = @($_.Exception.Message)
        $exitCode = -1
    }
    $finishedAt = (Get-Date).ToUniversalTime()
    return [pscustomobject]@{
        name = $Name
        command = (($Executable + " " + ($Arguments -join " ")).Trim())
        exitCode = $exitCode
        status = if ($exitCode -eq 0) { "passed" } else { "failed" }
        startedAt = $startedAt.ToString("o")
        finishedAt = $finishedAt.ToString("o")
        durationMs = [int]([DateTimeOffset]$finishedAt - [DateTimeOffset]$startedAt).TotalMilliseconds
        output = @($output | ForEach-Object { [string]$_ } | Select-Object -First 20)
    }
}

function Test-GradleTaskDeclaration {
    param(
        [string]$BuildGradlePath,
        [string]$TaskName
    )
    if (-not (Test-Path -LiteralPath $BuildGradlePath -PathType Leaf)) {
        return $false
    }
    $content = Get-Content -Raw -LiteralPath $BuildGradlePath
    return ($content -match ("tasks\.register\(\s*['""]" + [regex]::Escape($TaskName) + "['""]"))
}

function Find-GeneratedResidue {
    param([string]$UiRoot)
    $relativePaths = @(
        "node_modules",
        "dist",
        "coverage",
        "playwright-static",
        "playwright-report",
        "test-results",
        ".vite",
        ".vitest"
    )
    $found = @()
    foreach ($relativePath in $relativePaths) {
        $candidate = Join-Path $UiRoot $relativePath
        if (Test-Path -LiteralPath $candidate) {
            $found += [pscustomobject]@{
                path = (Convert-ToRepoPath -Root $workspaceRoot -Path $candidate)
                kind = if (Test-Path -LiteralPath $candidate -PathType Container) { "directory" } else { "file" }
            }
        }
    }
    $patterns = @("*.tsbuildinfo", "npm-debug.log*", "yarn-debug.log*", "yarn-error.log*")
    foreach ($pattern in $patterns) {
        if (Test-Path -LiteralPath $UiRoot -PathType Container) {
            $found += @(Get-ChildItem -LiteralPath $UiRoot -Filter $pattern -File -ErrorAction SilentlyContinue | ForEach-Object {
                [pscustomobject]@{
                    path = (Convert-ToRepoPath -Root $workspaceRoot -Path $_.FullName)
                    kind = "file"
                }
            })
        }
    }
    return @($found)
}

if ([string]::IsNullOrWhiteSpace($WorkspaceRoot)) {
    $WorkspaceRoot = (Resolve-Path ".").Path
}
$workspaceRoot = [System.IO.Path]::GetFullPath($WorkspaceRoot)
if ([string]::IsNullOrWhiteSpace($RunId)) {
    $RunId = "frontend-gate-" + (Get-Date).ToUniversalTime().ToString("yyyyMMdd-HHmmssfff")
}

$script:failures = @()
$commands = @()
$verifiedTasks = @()
$artifacts = @()
$toolchain = @()

$reportPathFull = Resolve-UnderRoot -Root $workspaceRoot -Path $ReportPath
$reportDirectory = Split-Path -Parent $reportPathFull
$logDirectory = Join-Path $reportDirectory "frontend-gate"
New-Item -ItemType Directory -Force -Path $logDirectory | Out-Null

$editorRootFull = Resolve-UnderRoot -Root $workspaceRoot -Path $EditorRoot
$uiRoot = Join-Path $editorRootFull "ui-react"
$buildGradlePath = Join-Path $editorRootFull "build.gradle"
$packageJsonPath = Join-Path $uiRoot "package.json"
$packageLockPath = Join-Path $uiRoot "package-lock.json"
$gradlewBatPath = Join-Path $editorRootFull "gradlew.bat"
$gradlewPath = Join-Path $editorRootFull "gradlew"
$gradleExecutable = if ($IsWindows) {
    if (Test-Path -LiteralPath $gradlewBatPath -PathType Leaf) { $gradlewBatPath } elseif (Test-Path -LiteralPath $gradlewPath -PathType Leaf) { $gradlewPath } else { $null }
}
else {
    if (Test-Path -LiteralPath $gradlewPath -PathType Leaf) { $gradlewPath } elseif (Test-Path -LiteralPath $gradlewBatPath -PathType Leaf) { $gradlewBatPath } else { $null }
}

foreach ($requiredPath in @($editorRootFull, $uiRoot, $buildGradlePath, $packageJsonPath, $packageLockPath)) {
    if (-not (Test-Path -LiteralPath $requiredPath)) {
        Add-Failure -Code "missing-required-path" -Message "Required frontend path is missing." -Path (Convert-ToRepoPath -Root $workspaceRoot -Path $requiredPath)
    }
}
if ($null -eq $gradleExecutable) {
    Add-Failure -Code "missing-gradle-wrapper" -Message "NPDevEditor Gradle wrapper is missing." -Path (Convert-ToRepoPath -Root $workspaceRoot -Path $editorRootFull)
}

foreach ($taskName in @("npmTest", "npmBuild")) {
    $exists = Test-GradleTaskDeclaration -BuildGradlePath $buildGradlePath -TaskName $taskName
    $verifiedTasks += [pscustomobject]@{
        name = $taskName
        type = "gradle-task"
        source = (Convert-ToRepoPath -Root $workspaceRoot -Path $buildGradlePath)
        exists = [bool]$exists
    }
    if (-not $exists) {
        Add-Failure -Code "missing-gradle-task" -Message ("Required Gradle frontend task is missing: " + $taskName) -Path (Convert-ToRepoPath -Root $workspaceRoot -Path $buildGradlePath)
    }
}

$packageJson = if (Test-Path -LiteralPath $packageJsonPath -PathType Leaf) { Get-JsonObject -Path $packageJsonPath } else { $null }
foreach ($scriptName in @("test", "build")) {
    $exists = $false
    if ($null -ne $packageJson -and $null -ne $packageJson.scripts) {
        $exists = $null -ne $packageJson.scripts.PSObject.Properties[$scriptName]
    }
    $verifiedTasks += [pscustomobject]@{
        name = $scriptName
        type = "npm-script"
        source = (Convert-ToRepoPath -Root $workspaceRoot -Path $packageJsonPath)
        exists = [bool]$exists
    }
    if (-not $exists) {
        Add-Failure -Code "missing-npm-script" -Message ("Required npm script is missing: " + $scriptName) -Path (Convert-ToRepoPath -Root $workspaceRoot -Path $packageJsonPath)
    }
}

$toolchain += Invoke-VersionProbe -Name "node" -Executable "node" -Arguments @("--version")
$toolchain += Invoke-VersionProbe -Name "npm" -Executable "npm" -Arguments @("--version")
$toolchain += Invoke-VersionProbe -Name "java" -Executable "java" -Arguments @("-version")

$fingerprints = @(
    Get-HashRecord -Path $buildGradlePath
    Get-HashRecord -Path $packageJsonPath
    Get-HashRecord -Path $packageLockPath
)

if ($failures.Count -eq 0) {
    $stdoutPath = Join-Path $logDirectory "frontend-gate.stdout.log"
    $stderrPath = Join-Path $logDirectory "frontend-gate.stderr.log"
    $gradleArguments = @("npmTest", "npmBuild", "--no-daemon", "--console=plain")
    $startedAt = (Get-Date).ToUniversalTime()
    $exitCode = $null
    try {
        $process = Start-Process -FilePath $gradleExecutable -ArgumentList $gradleArguments -WorkingDirectory $editorRootFull -NoNewWindow -Wait -PassThru -RedirectStandardOutput $stdoutPath -RedirectStandardError $stderrPath
        $exitCode = $process.ExitCode
    }
    catch {
        $exitCode = -1
        Set-Content -LiteralPath $stderrPath -Encoding UTF8 -Value $_.Exception.Message
    }
    $finishedAt = (Get-Date).ToUniversalTime()
    $commandRecord = [pscustomobject]@{
        name = "frontend-gradle-npm"
        command = ((Convert-ToRepoPath -Root $workspaceRoot -Path $gradleExecutable) + " " + ($gradleArguments -join " "))
        workingDirectory = (Convert-ToRepoPath -Root $workspaceRoot -Path $editorRootFull)
        startedAt = $startedAt.ToString("o")
        finishedAt = $finishedAt.ToString("o")
        durationMs = [int]([DateTimeOffset]$finishedAt - [DateTimeOffset]$startedAt).TotalMilliseconds
        exitCode = $exitCode
        status = if ($exitCode -eq 0) { "passed" } else { "failed" }
        stdoutPath = (Convert-ToRepoPath -Root $workspaceRoot -Path $stdoutPath)
        stderrPath = (Convert-ToRepoPath -Root $workspaceRoot -Path $stderrPath)
        stdoutTail = @(Get-OutputTail -Path $stdoutPath)
        stderrTail = @(Get-OutputTail -Path $stderrPath)
    }
    $commands += $commandRecord
    $artifacts += [pscustomobject]@{
        type = "stdout-log"
        path = (Convert-ToRepoPath -Root $workspaceRoot -Path $stdoutPath)
        exists = (Test-Path -LiteralPath $stdoutPath -PathType Leaf)
    }
    $artifacts += [pscustomobject]@{
        type = "stderr-log"
        path = (Convert-ToRepoPath -Root $workspaceRoot -Path $stderrPath)
        exists = (Test-Path -LiteralPath $stderrPath -PathType Leaf)
    }
    if ($exitCode -ne 0) {
        Add-Failure -Code "frontend-command-failed" -Message ("Frontend Gradle/npm command failed with exit code " + $exitCode + ".") -Path (Convert-ToRepoPath -Root $workspaceRoot -Path $gradleExecutable) -Details @{ exitCode = $exitCode }
    }
}

$residue = Find-GeneratedResidue -UiRoot $uiRoot
if ($residue.Count -gt 0) {
    Add-Failure -Code "generated-residue-present" -Message "Frontend gate left generated files or directories behind." -Path (Convert-ToRepoPath -Root $workspaceRoot -Path $uiRoot) -Details @{ residue = @($residue) }
}

$overallStatus = if ($failures.Count -eq 0) { "passed" } else { "failed" }
$artifacts += [pscustomobject]@{
    type = "report"
    path = (Convert-ToRepoPath -Root $workspaceRoot -Path $reportPathFull)
    exists = $true
}

$report = [pscustomobject]@{
    schemaVersion = "npdev-frontend-gate-report.v1"
    runId = $RunId
    generatedAt = (Get-Date).ToUniversalTime().ToString("o")
    scriptPath = "scripts/quality/run-frontend-gate.ps1"
    workspaceRoot = $workspaceRoot
    editorRoot = (Convert-ToRepoPath -Root $workspaceRoot -Path $editorRootFull)
    uiRoot = (Convert-ToRepoPath -Root $workspaceRoot -Path $uiRoot)
    overallStatus = $overallStatus
    commands = @($commands)
    exitCodes = @($commands | ForEach-Object { [pscustomobject]@{ name = $_.name; exitCode = $_.exitCode; status = $_.status } })
    durations = [pscustomobject]@{
        totalCommandDurationMs = [int](($commands | Measure-Object -Property durationMs -Sum).Sum)
        commands = @($commands | ForEach-Object { [pscustomobject]@{ name = $_.name; durationMs = $_.durationMs } })
    }
    artifacts = @($artifacts)
    failures = @($failures)
    verifiedTasks = @($verifiedTasks)
    toolchain = @($toolchain)
    fingerprints = @($fingerprints)
    residueCheck = [pscustomobject]@{
        checkedRoot = (Convert-ToRepoPath -Root $workspaceRoot -Path $uiRoot)
        status = if ($residue.Count -eq 0) { "passed" } else { "failed" }
        findings = @($residue)
    }
}

New-Item -ItemType Directory -Force -Path (Split-Path -Parent $reportPathFull) | Out-Null
$report | ConvertTo-Json -Depth 50 | Set-Content -LiteralPath $reportPathFull -Encoding UTF8

if ($overallStatus -eq "passed") {
    Write-Host ("Frontend gate passed. Report: " + (Convert-ToRepoPath -Root $workspaceRoot -Path $reportPathFull))
    exit 0
}

Write-Error ("Frontend gate failed. Report: " + (Convert-ToRepoPath -Root $workspaceRoot -Path $reportPathFull))
