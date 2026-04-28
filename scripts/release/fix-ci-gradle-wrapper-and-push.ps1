[CmdletBinding()]
param(
    [string]$WorkspaceRoot = 'D:\WorkSpace\NPDev_General',
    [string]$CommitMessage = 'Stabilize CI Gradle wrappers and release gate timeout',
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

function Write-Warn([string]$Message) {
    Write-Host ('WARN  ' + $Message) -ForegroundColor Yellow
}

function Invoke-Git {
    param([Parameter(Mandatory = $true)][string[]]$GitArgs)

    & git @GitArgs
    if ($LASTEXITCODE -ne 0) {
        throw ("git failed with exit code {0}: git {1}" -f $LASTEXITCODE, ($GitArgs -join ' '))
    }
}

function Add-LineIfMissing {
    param(
        [Parameter(Mandatory = $true)][System.Collections.Generic.List[string]]$Lines,
        [Parameter(Mandatory = $true)][string]$Line
    )

    if ($Lines -notcontains $Line) {
        [void]$Lines.Add($Line)
        return $true
    }
    return $false
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

$gitIgnorePath = Join-Path $WorkspaceRoot '.gitignore'
if (-not (Test-Path -LiteralPath $gitIgnorePath)) {
    New-Item -ItemType File -Path $gitIgnorePath -Force | Out-Null
}

Write-Step 'Update .gitignore so Gradle wrapper jars/scripts can be tracked'
$gitIgnoreLines = [System.Collections.Generic.List[string]]::new()
foreach ($line in (Get-Content -LiteralPath $gitIgnorePath -ErrorAction SilentlyContinue)) {
    [void]$gitIgnoreLines.Add($line)
}

$changedGitIgnore = $false
$changedGitIgnore = (Add-LineIfMissing -Lines $gitIgnoreLines -Line '') -or $changedGitIgnore
$changedGitIgnore = (Add-LineIfMissing -Lines $gitIgnoreLines -Line '# Required Gradle wrapper artifacts for CI') -or $changedGitIgnore
$changedGitIgnore = (Add-LineIfMissing -Lines $gitIgnoreLines -Line '!**/gradle/wrapper/gradle-wrapper.jar') -or $changedGitIgnore
$changedGitIgnore = (Add-LineIfMissing -Lines $gitIgnoreLines -Line '!**/gradlew') -or $changedGitIgnore
$changedGitIgnore = (Add-LineIfMissing -Lines $gitIgnoreLines -Line '!**/gradlew.bat') -or $changedGitIgnore

if ($changedGitIgnore) {
    Set-Content -LiteralPath $gitIgnorePath -Value $gitIgnoreLines -Encoding UTF8
    Write-Ok 'Updated .gitignore Gradle wrapper exceptions.'
}
else {
    Write-Ok '.gitignore already has Gradle wrapper exceptions.'
}

Write-Step 'Ensure Gradle wrappers exist locally'
$doctorWrapper = Join-Path $WorkspaceRoot 'scripts\doctor\check-gradle-wrapper.ps1'
if (-not (Test-Path -LiteralPath $doctorWrapper)) {
    throw "Gradle wrapper doctor script not found: $doctorWrapper"
}

& 'C:\Program Files (x86)\PowerShell\7\pwsh.exe' -NoProfile -ExecutionPolicy Bypass -File $doctorWrapper -WorkspaceRoot $WorkspaceRoot
if ($LASTEXITCODE -ne 0) {
    throw "Gradle wrapper doctor failed."
}

$requiredProjects = @(
    'NPDevContract\dsl',
    'NPDevEditor',
    'NPDevGenerator',
    'NPDevKernel',
    'NPDevRuntimeHost'
)

$requiredWrapperFiles = [System.Collections.Generic.List[string]]::new()
$missingWrapperFiles = [System.Collections.Generic.List[string]]::new()

foreach ($project in $requiredProjects) {
    foreach ($relative in @(
        (Join-Path $project 'gradle\wrapper\gradle-wrapper.properties'),
        (Join-Path $project 'gradle\wrapper\gradle-wrapper.jar'),
        (Join-Path $project 'gradlew'),
        (Join-Path $project 'gradlew.bat')
    )) {
        $full = Join-Path $WorkspaceRoot $relative
        [void]$requiredWrapperFiles.Add($relative)
        if (-not (Test-Path -LiteralPath $full)) {
            [void]$missingWrapperFiles.Add($relative)
        }
    }
}

if ($missingWrapperFiles.Count -gt 0) {
    throw ("Missing Gradle wrapper file(s) after repair: " + ($missingWrapperFiles -join ', '))
}

Write-Ok ('All required Gradle wrapper files exist: ' + $requiredWrapperFiles.Count)

Write-Step 'Patch GitHub Actions workflow with preflight and timeout'
$workflow = Get-Content -LiteralPath $workflowPath -Raw

if ($workflow -notmatch 'name:\s*Preflight Gradle wrappers') {
    $preflight = @'
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

'@

    $marker = '      - name: Run beta release gate with GitHub traceability'
    if ($workflow -notlike "*$marker*") {
        throw "Could not find beta release gate step marker in workflow."
    }
    $workflow = $workflow.Replace($marker, $preflight + $marker)
    Write-Ok 'Inserted Preflight Gradle wrappers step.'
}
else {
    Write-Ok 'Preflight Gradle wrappers step already present.'
}

if ($workflow -match '(?m)^      - name: Run beta release gate with GitHub traceability\r?\n(?!        timeout-minutes:)') {
    $workflow = $workflow -replace '(?m)^      - name: Run beta release gate with GitHub traceability\r?\n', "      - name: Run beta release gate with GitHub traceability`r`n        timeout-minutes: 90`r`n"
    Write-Ok 'Added timeout-minutes: 90 to beta release gate step.'
}
elseif ($workflow -match 'Run beta release gate with GitHub traceability' -and $workflow -match 'timeout-minutes:\s*90') {
    Write-Ok 'Beta release gate timeout already present.'
}
else {
    Write-Warn 'Could not determine whether beta release gate timeout was needed; leaving workflow unchanged for timeout.'
}

Set-Content -LiteralPath $workflowPath -Value $workflow -Encoding UTF8

Write-Step 'Force-add Gradle wrapper files and workflow changes'
Invoke-Git -GitArgs @('add', '.gitignore', '.github/workflows/npdev-release-gate.yml')

foreach ($relative in $requiredWrapperFiles) {
    $normalized = $relative -replace '\\', '/'
    Invoke-Git -GitArgs @('add', '-f', '--', $normalized)
}

$statusAfterAdd = (& git status --short)
if ([string]::IsNullOrWhiteSpace(($statusAfterAdd -join "`n"))) {
    Write-Ok 'No changes to commit; repository already had this CI fix.'
}
else {
    Write-Host ($statusAfterAdd -join "`n")
    Invoke-Git -GitArgs @('commit', '-m', $CommitMessage)
    Write-Ok 'Committed CI Gradle wrapper fix.'
}

Write-Step 'Verify local repository before push'
git status --short
git log --oneline -5

if ($SkipPush) {
    Write-Warn 'Skipping push because -SkipPush was provided.'
    exit 0
}

Write-Step 'Push main branch'
Invoke-Git -GitArgs @('push', '-u', 'origin', 'main')

Write-Step 'Push tags'
Invoke-Git -GitArgs @('push', 'origin', '--tags')

Write-Step 'Verify remote refs'
git ls-remote --heads origin main
git ls-remote --tags origin 'npdev-official-beta-*'

Write-Ok 'CI Gradle wrapper fix pushed. Watch GitHub Actions next.'
