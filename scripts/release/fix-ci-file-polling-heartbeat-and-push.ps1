[CmdletBinding()]
param(
    [string]$WorkspaceRoot = 'D:\WorkSpace\NPDev_General',
    [string]$CommitMessage = 'Use robust file polling heartbeat for CI beta gate',
    [switch]$SkipPush
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Write-Step([string]$Message) {
    Write-Host ''
    Write-Host ('== ' + $Message + ' ==') -ForegroundColor Cyan
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

$wrapperPath = Join-Path $WorkspaceRoot 'scripts\release\invoke-ci-beta-release-gate-with-heartbeat.ps1'
$workflowPath = Join-Path $WorkspaceRoot '.github\workflows\npdev-release-gate.yml'

if (-not (Test-Path -LiteralPath $workflowPath)) {
    throw "Workflow file not found: $workflowPath"
}

Write-Step 'Replace CI heartbeat wrapper with file-polling implementation'

@'
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
'@ | Set-Content -LiteralPath $wrapperPath -Encoding UTF8

Write-Step 'Replace workflow with stable version'
@'
name: NPDev Release Gate

on:
  pull_request:
  push:
    branches:
      - main
    tags:
      - 'npdev-official-beta-*'
  workflow_dispatch:

concurrency:
  group: npdev-release-gate-${{ github.ref }}
  cancel-in-progress: true

jobs:
  release-gate:
    name: NPDev release gate
    runs-on: windows-latest
    timeout-minutes: 240

    defaults:
      run:
        shell: pwsh

    env:
      NPDEV_WORKSPACE: ${{ github.workspace }}
      NPDEV_STATE_ZIP_OUT: ${{ github.workspace }}\_state-zips
      CI: true
      GRADLE_OPTS: -Dorg.gradle.daemon=false -Dorg.gradle.console=plain -Dorg.gradle.workers.max=2 -Dorg.gradle.vfs.watch=false

    steps:
      - name: Checkout
        uses: actions/checkout@v4
        with:
          fetch-depth: 0

      - name: Setup Java
        uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: |
            17
            21

      - name: Configure Gradle Java toolchain discovery
        run: |
          Write-Host "JAVA_HOME=$env:JAVA_HOME"
          Write-Host "JAVA_HOME_17_X64=$env:JAVA_HOME_17_X64"
          Write-Host "JAVA_HOME_21_X64=$env:JAVA_HOME_21_X64"
          java -version

          if ([string]::IsNullOrWhiteSpace($env:JAVA_HOME_17_X64)) {
            throw "JAVA_HOME_17_X64 is not available after setup-java."
          }
          if ([string]::IsNullOrWhiteSpace($env:JAVA_HOME_21_X64)) {
            throw "JAVA_HOME_21_X64 is not available after setup-java."
          }

          $gradleUserHome = Join-Path $env:USERPROFILE ".gradle"
          New-Item -ItemType Directory -Force -Path $gradleUserHome | Out-Null
          $gradleProperties = Join-Path $gradleUserHome "gradle.properties"

          @(
            "org.gradle.daemon=false",
            "org.gradle.console=plain",
            "org.gradle.workers.max=2",
            "org.gradle.vfs.watch=false",
            "org.gradle.java.installations.auto-detect=true",
            "org.gradle.java.installations.fromEnv=JAVA_HOME_17_X64,JAVA_HOME_21_X64"
          ) | Set-Content -LiteralPath $gradleProperties -Encoding UTF8

          Write-Host "Gradle CI properties:"
          Get-Content $gradleProperties

      - name: Setup Node
        uses: actions/setup-node@v4
        with:
          node-version: '22'
          cache: npm
          cache-dependency-path: |
            NPDevEditor/ui-react/package-lock.json

      - name: Cache Gradle
        uses: actions/cache@v4
        with:
          path: |
            ~/.gradle/caches
            ~/.gradle/wrapper
          key: gradle-${{ runner.os }}-${{ hashFiles('**/*.gradle*', '**/gradle-wrapper.properties') }}
          restore-keys: |
            gradle-${{ runner.os }}-

      - name: Cache Playwright browsers
        uses: actions/cache@v4
        with:
          path: ~\AppData\Local\ms-playwright
          key: playwright-${{ runner.os }}-${{ hashFiles('NPDevEditor/ui-react/package-lock.json') }}
          restore-keys: |
            playwright-${{ runner.os }}-

      - name: Install Playwright Chromium
        working-directory: NPDevEditor/ui-react
        timeout-minutes: 15
        run: |
          npm ci
          npm exec playwright install chromium

      - name: Preflight Gradle wrappers
        timeout-minutes: 15
        run: |
          $projects = @(
            "NPDevContract\dsl",
            "NPDevEditor",
            "NPDevGenerator",
            "NPDevKernel",
            "NPDevRuntimeHost"
          )

          foreach ($project in $projects) {
            $projectRoot = Join-Path $env:NPDEV_WORKSPACE $project
            Write-Host "Checking Gradle wrapper in $projectRoot"

            $required = @(
              "gradle\wrapper\gradle-wrapper.properties",
              "gradle\wrapper\gradle-wrapper.jar",
              "gradlew",
              "gradlew.bat"
            )

            foreach ($relative in $required) {
              $path = Join-Path $projectRoot $relative
              if (-not (Test-Path -LiteralPath $path)) {
                throw "Missing Gradle wrapper artifact: $path"
              }
            }

            Get-Content (Join-Path $projectRoot "gradle\wrapper\gradle-wrapper.properties")
          }

      - name: Run beta release gate with GitHub traceability
        timeout-minutes: 180
        run: |
          $dirty = $false
          $status = git status --porcelain
          if (-not [string]::IsNullOrWhiteSpace($status)) {
            $dirty = $true
          }

          & pwsh -NoProfile -ExecutionPolicy Bypass `
            -File "$env:NPDEV_WORKSPACE\scripts\release\invoke-ci-beta-release-gate-with-heartbeat.ps1" `
            -WorkspaceRoot "$env:NPDEV_WORKSPACE" `
            -TotalTimeoutMinutes 160 `
            -HeartbeatSeconds 60 `
            -SourceCommitSha "${{ github.sha }}" `
            -SourceBranch "${{ github.ref_name }}" `
            -SourceDirty "$dirty" `
            -SourceProvider "github-actions" `
            -SourceRunId "${{ github.run_id }}" `
            -SourceRunAttempt "${{ github.run_attempt }}" `
            -SourceWorkflow "${{ github.workflow }}"

      - name: Validate release traceability
        timeout-minutes: 10
        run: |
          & pwsh -NoProfile -ExecutionPolicy Bypass `
            -File "$env:NPDEV_WORKSPACE\scripts\tests\Test-ReleaseTraceability.ps1" `
            -WorkspaceRoot "$env:NPDEV_WORKSPACE" `
            -RequireOfficialEligibility

      - name: Generate release-ready state zip
        timeout-minutes: 30
        run: |
          New-Item -ItemType Directory -Force -Path "$env:NPDEV_STATE_ZIP_OUT" | Out-Null

          & pwsh -NoProfile -ExecutionPolicy Bypass `
            -File "$env:NPDEV_WORKSPACE\scripts\statezip-npdev-general.ps1" `
            -WorkspaceRoot "$env:NPDEV_WORKSPACE" `
            -OutDir "$env:NPDEV_STATE_ZIP_OUT" `
            -ReleaseReady `
            -ExistingEvidenceRoot last

      - name: Fix visible state zip timestamps
        timeout-minutes: 5
        run: |
          & pwsh -NoProfile -ExecutionPolicy Bypass `
            -File "$env:NPDEV_WORKSPACE\scripts\quality\fix-statezip-visible-timestamps.ps1" `
            -ZipRoot "$env:NPDEV_STATE_ZIP_OUT"

      - name: Validate CI release evidence freshness
        timeout-minutes: 10
        run: |
          & pwsh -NoProfile -ExecutionPolicy Bypass `
            -File "$env:NPDEV_WORKSPACE\scripts\tests\Test-CiReleaseEvidenceFreshness.ps1" `
            -WorkspaceRoot "$env:NPDEV_WORKSPACE" `
            -StateZipOut "$env:NPDEV_STATE_ZIP_OUT"

      - name: Upload release evidence
        uses: actions/upload-artifact@v4
        with:
          name: npdev-release-evidence
          path: |
            scripts/reports/out
            scripts/reports/releases
            _state-zips
          if-no-files-found: error
          retention-days: 30
'@ | Set-Content -LiteralPath $workflowPath -Encoding UTF8

Write-Step 'Parser check wrapper'
& pwsh -NoProfile -Command "[scriptblock]::Create((Get-Content '$wrapperPath' -Raw)) | Out-Null; 'OK wrapper parser check'"

Write-Step 'Commit and push'
Invoke-Git -GitArgs @('add', '.github/workflows/npdev-release-gate.yml', 'scripts/release/invoke-ci-beta-release-gate-with-heartbeat.ps1')
Invoke-Git -GitArgs @('commit', '-m', $CommitMessage)
if (-not $SkipPush) {
    Invoke-Git -GitArgs @('push', 'origin', 'main')
}
Write-Host ''
Write-Host 'OK    Robust CI file-polling heartbeat fix applied.'
