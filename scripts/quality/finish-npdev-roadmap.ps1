[CmdletBinding()]
param(
    [string]$WorkspaceRoot = 'D:\WorkSpace\NPDev_General',
    [string]$OutDir = 'D:\WorkSpace\NPDev_General__OutsideRepo\state-zips',
    [string]$CommitMessage = 'Establish NPDev official beta traceability baseline',
    [string]$GitUserName = 'NPDev Local Release',
    [string]$GitUserEmail = 'npdev-local@example.invalid',
    [switch]$SkipFullBetaGate,
    [switch]$SkipStateZip
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Write-Step([string]$Message) {
    Write-Host ''
    Write-Host ('== ' + $Message + ' ==') -ForegroundColor Cyan
}
function Write-Ok([string]$Message) { Write-Host ('OK    ' + $Message) -ForegroundColor Green }
function Write-Warn([string]$Message) { Write-Host ('WARN  ' + $Message) -ForegroundColor Yellow }

function Invoke-External {
    param([Parameter(Mandatory=$true)][string]$FilePath, [string[]]$Arguments = @())
    & $FilePath @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw ("Command failed with exit code {0}: {1} {2}" -f $LASTEXITCODE, $FilePath, ($Arguments -join ' '))
    }
}

function Invoke-PwshScript {
    param([Parameter(Mandatory=$true)][string]$ScriptPath, [string[]]$Arguments = @())
    if (-not (Test-Path -LiteralPath $ScriptPath)) { throw "Required script not found: $ScriptPath" }
    $pwsh = 'C:\Program Files (x86)\PowerShell\7\pwsh.exe'
    if (-not (Test-Path -LiteralPath $pwsh)) { $pwsh = 'pwsh' }
    & $pwsh -NoProfile -ExecutionPolicy Bypass -File $ScriptPath @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw ("PowerShell script failed with exit code {0}: {1}" -f $LASTEXITCODE, $ScriptPath)
    }
}

function Get-GitOutput {
    param([string[]]$Arguments)
    $output = & git @Arguments 2>$null
    if ($LASTEXITCODE -ne 0) { return $null }
    return ($output -join "`n").Trim()
}

function Ensure-GitIgnore([string]$Root) {
    $gitIgnorePath = Join-Path $Root '.gitignore'
    $requiredPatterns = @(
        '# NPDev generated/build outputs',
        '.gradle/',
        '**/.gradle/',
        '**/build/',
        '**/out/',
        '**/target/',
        '**/node_modules/',
        '**/dist/',
        '**/.vite/',
        '**/coverage/',
        '**/playwright-report/',
        '**/test-results/',
        '**/Output/',
        '**/ArtifactNP/',
        '**/App/npdev-generated/',
        '**/.idea/',
        '**/.vscode/',
        '*.class',
        '*.jar',
        '*.war',
        '*.log',
        '# Local release/state artifacts',
        'scripts/reports/out/',
        'scripts/reports/releases/',
        'scripts/reports/tmp/',
        'scripts/reports/cache/',
        'NPDev_General__OutsideRepo/'
    )

    $existing = @()
    if (Test-Path -LiteralPath $gitIgnorePath) { $existing = @(Get-Content -LiteralPath $gitIgnorePath) }

    $changed = $false
    foreach ($pattern in $requiredPatterns) {
        if ($existing -notcontains $pattern) {
            $existing += $pattern
            $changed = $true
        }
    }

    if ($changed -or -not (Test-Path -LiteralPath $gitIgnorePath)) {
        Set-Content -LiteralPath $gitIgnorePath -Value $existing -Encoding UTF8
        Write-Ok "Updated .gitignore with NPDev generated-output exclusions."
    } else {
        Write-Ok ".gitignore already contains required NPDev exclusions."
    }
}

function Ensure-GitRepository([string]$Root) {
    Push-Location $Root
    try {
        $inside = Get-GitOutput @('rev-parse', '--is-inside-work-tree')
        if ($inside -ne 'true') {
            Write-Warn "Workspace is not a Git worktree. Initializing local Git repository."
            Invoke-External git @('init')
        } else {
            Write-Ok "Workspace is already a Git worktree."
        }

        $localName = Get-GitOutput @('config', '--local', 'user.name')
        if ([string]::IsNullOrWhiteSpace($localName)) {
            Invoke-External git @('config', '--local', 'user.name', $GitUserName)
            Write-Ok "Configured local git user.name."
        }

        $localEmail = Get-GitOutput @('config', '--local', 'user.email')
        if ([string]::IsNullOrWhiteSpace($localEmail)) {
            Invoke-External git @('config', '--local', 'user.email', $GitUserEmail)
            Write-Ok "Configured local git user.email."
        }
    } finally {
        Pop-Location
    }
}

function Ensure-TraceableCommit([string]$Root) {
    Push-Location $Root
    try {
        $head = Get-GitOutput @('rev-parse', 'HEAD')
        $statusBefore = Get-GitOutput @('status', '--porcelain')

        if ([string]::IsNullOrWhiteSpace($head)) {
            Write-Warn "Git repository has no HEAD commit. Creating baseline commit."
            Invoke-External git @('add', '-A')
            $staged = Get-GitOutput @('diff', '--cached', '--name-only')
            if ([string]::IsNullOrWhiteSpace($staged)) { throw "No files were staged for the first commit." }
            Invoke-External git @('commit', '-m', $CommitMessage)
            $head = Get-GitOutput @('rev-parse', 'HEAD')
            Write-Ok "Created initial traceability commit: $head"
            return
        }

        if (-not [string]::IsNullOrWhiteSpace($statusBefore)) {
            Write-Warn "Workspace has uncommitted changes. Creating traceability commit."
            Invoke-External git @('add', '-A')
            $staged = Get-GitOutput @('diff', '--cached', '--name-only')
            if (-not [string]::IsNullOrWhiteSpace($staged)) {
                Invoke-External git @('commit', '-m', $CommitMessage)
                $head = Get-GitOutput @('rev-parse', 'HEAD')
                Write-Ok "Created traceability commit: $head"
            } else {
                Write-Warn "Status was non-empty, but no stageable source changes remained after .gitignore filtering."
            }
        } else {
            Write-Ok "Git workspace is clean."
        }
    } finally {
        Pop-Location
    }
}

function Show-GitTraceability([string]$Root) {
    Push-Location $Root
    try {
        $head = Get-GitOutput @('rev-parse', 'HEAD')
        $branch = Get-GitOutput @('rev-parse', '--abbrev-ref', 'HEAD')
        $dirty = Get-GitOutput @('status', '--porcelain')
        if ([string]::IsNullOrWhiteSpace($head)) { throw "Git HEAD still cannot be resolved." }
        Write-Host "Commit: $head"
        Write-Host "Branch: $branch"
        if ([string]::IsNullOrWhiteSpace($dirty)) {
            Write-Host "Dirty:  false"
        } else {
            Write-Host "Dirty:  true"
            Write-Warn "Workspace is dirty before release. Official eligibility may remain false if checks require a clean tree."
            Write-Host $dirty
        }
    } finally {
        Pop-Location
    }
}

function Show-ReleaseSummary([string]$Root) {
    $summaryPath = Join-Path $Root 'scripts\reports\out\release-ready-summary.json'
    if (-not (Test-Path -LiteralPath $summaryPath)) {
        Write-Warn "release-ready-summary.json was not found: $summaryPath"
        return
    }

    $summary = Get-Content -LiteralPath $summaryPath -Raw | ConvertFrom-Json
    Write-Host ''
    Write-Host 'Release summary:' -ForegroundColor Cyan
    Write-Host ('  releaseReady:             ' + $summary.releaseReady)
    Write-Host ('  officialReleaseEligible:  ' + $summary.officialReleaseEligible)
    Write-Host ('  provenanceGrade:          ' + $summary.provenanceGrade)
    Write-Host ('  traceabilitySatisfied:    ' + $summary.traceabilitySatisfied)

    if ($summary.releaseReady -ne $true) { throw "ReleaseReady is not true after finish script." }
    if ($summary.officialReleaseEligible -ne $true -or $summary.traceabilitySatisfied -ne $true) {
        Write-Warn "Diagnostic beta is green, but official release eligibility is still not satisfied."
        Write-Warn "Inspect release-ready-summary.json and evidence-manifest.json for the exact traceability reason."
        return
    }

    Write-Ok "Official release traceability is satisfied."
}

$WorkspaceRoot = (Resolve-Path $WorkspaceRoot).Path
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null

$requiredScripts = @{
    BetaGate = Join-Path $WorkspaceRoot 'scripts\quality\run-beta-release-gate.ps1'
    TraceableRelease = Join-Path $WorkspaceRoot 'scripts\quality\run-traceable-local-release.ps1'
    TraceabilityTest = Join-Path $WorkspaceRoot 'scripts\tests\Test-ReleaseTraceability.ps1'
    StateZip = Join-Path $WorkspaceRoot 'scripts\statezip-npdev-general.ps1'
    RuntimeSurface = Join-Path $WorkspaceRoot 'scripts\quality\run-runtime-surface-evidence.ps1'
    Hygiene = Join-Path $WorkspaceRoot 'scripts\quality\run-hygiene-gate.ps1'
}

Write-Step "Validating required scripts"
foreach ($entry in $requiredScripts.GetEnumerator()) {
    if (-not (Test-Path -LiteralPath $entry.Value)) { throw "Missing required script [$($entry.Key)]: $($entry.Value)" }
    Write-Ok "$($entry.Key): $($entry.Value)"
}

Write-Step "Preparing Git traceability"
Ensure-GitIgnore $WorkspaceRoot
Ensure-GitRepository $WorkspaceRoot
Ensure-TraceableCommit $WorkspaceRoot
Show-GitTraceability $WorkspaceRoot

Write-Step "Verifying clean diagnostic layers"
Invoke-PwshScript $requiredScripts.RuntimeSurface @('-WorkspaceRoot', $WorkspaceRoot)
Invoke-PwshScript $requiredScripts.Hygiene @('-WorkspaceRoot', $WorkspaceRoot)

if (-not $SkipFullBetaGate) {
    Write-Step "Running full beta gate"
    Invoke-PwshScript $requiredScripts.BetaGate @('-WorkspaceRoot', $WorkspaceRoot)
} else {
    Write-Warn "Skipping full beta gate because -SkipFullBetaGate was provided."
}

Write-Step "Running traceable local release"
Invoke-PwshScript $requiredScripts.TraceableRelease @('-WorkspaceRoot', $WorkspaceRoot)

Write-Step "Validating official release traceability"
Invoke-PwshScript $requiredScripts.TraceabilityTest @('-WorkspaceRoot', $WorkspaceRoot, '-RequireOfficialEligibility')

if (-not $SkipStateZip) {
    Write-Step "Generating final release-ready state zip from latest evidence"
    Invoke-PwshScript $requiredScripts.StateZip @(
        '-WorkspaceRoot', $WorkspaceRoot,
        '-OutDir', $OutDir,
        '-ReleaseReady',
        '-ExistingEvidenceRoot', 'last'
    )
} else {
    Write-Warn "Skipping state zip because -SkipStateZip was provided."
}

Write-Step "Final release summary"
Show-ReleaseSummary $WorkspaceRoot

Write-Host ''
Write-Ok "NPDev roadmap finish script completed."
Write-Host "Next optional step: push this monorepo to GitHub and wire this release path into CI."
