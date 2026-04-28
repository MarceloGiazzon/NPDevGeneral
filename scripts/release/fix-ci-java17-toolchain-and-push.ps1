[CmdletBinding()]
param(
    [string]$WorkspaceRoot = 'D:\WorkSpace\NPDev_General',
    [string]$CommitMessage = 'Install Java 17 and 21 toolchains in CI',
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

$workflowPath = Join-Path $WorkspaceRoot '.github\workflows\npdev-release-gate.yml'
if (-not (Test-Path -LiteralPath $workflowPath)) {
    throw "Workflow file not found: $workflowPath"
}

Write-Step 'Patch workflow for Java 17 and Java 21 toolchains'
$workflow = Get-Content -LiteralPath $workflowPath -Raw

$setupJavaPattern = "(?ms)      - name: Setup Java\r?\n        uses: actions/setup-java@v4\r?\n        with:\r?\n          distribution: temurin\r?\n          java-version: '21'"
$setupJavaReplacement = @'
      - name: Setup Java
        uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: |
            17
            21
'@

if ($workflow -match $setupJavaPattern) {
    $workflow = [regex]::Replace($workflow, $setupJavaPattern, $setupJavaReplacement, 1)
    Write-Ok 'Updated Setup Java to install Java 17 and Java 21.'
}
elseif ($workflow -match 'java-version:\s*\|\s*\r?\n\s*17\r?\n\s*21') {
    Write-Ok 'Workflow already installs Java 17 and Java 21.'
}
else {
    throw "Could not find the expected Setup Java block to patch."
}

if ($workflow -notmatch 'name:\s*Configure Gradle Java toolchain discovery') {
    $toolchainStep = @'
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
            "org.gradle.java.installations.auto-detect=true",
            "org.gradle.java.installations.fromEnv=JAVA_HOME_17_X64,JAVA_HOME_21_X64"
          ) | Set-Content -LiteralPath $gradleProperties -Encoding UTF8

          Write-Host "Gradle toolchain discovery properties:"
          Get-Content $gradleProperties

'@

    $marker = '      - name: Setup Node'
    if ($workflow -notlike "*$marker*") {
        throw "Could not find Setup Node marker in workflow."
    }
    $workflow = $workflow.Replace($marker, $toolchainStep + $marker)
    Write-Ok 'Inserted Gradle Java toolchain discovery step.'
}
else {
    Write-Ok 'Gradle Java toolchain discovery step already exists.'
}

Set-Content -LiteralPath $workflowPath -Value $workflow -Encoding UTF8

Write-Step 'Show workflow Java-related diff'
git diff -- .github/workflows/npdev-release-gate.yml

Write-Step 'Commit workflow fix'
Invoke-Git -GitArgs @('add', '.github/workflows/npdev-release-gate.yml')

$statusAfterAdd = (& git status --short)
if ([string]::IsNullOrWhiteSpace(($statusAfterAdd -join "`n"))) {
    Write-Ok 'No changes to commit; workflow already had Java 17 toolchain fix.'
}
else {
    Invoke-Git -GitArgs @('commit', '-m', $CommitMessage)
    Write-Ok 'Committed Java toolchain CI fix.'
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

Write-Ok 'Java 17/21 CI toolchain fix pushed. Watch GitHub Actions next.'
