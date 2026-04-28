[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$GitHubRemoteUrl,

    [string]$WorkspaceRoot = 'D:\WorkSpace\NPDev_General',
    [string]$BranchName = 'main',
    [string]$TagPattern = 'npdev-official-beta-*',
    [switch]$ForceBranchRename
)

$ErrorActionPreference = 'Stop'

function Invoke-Git {
    param([string[]]$Args)

    & git @Args
    if ($LASTEXITCODE -ne 0) {
        throw ("git failed with exit code {0}: git {1}" -f $LASTEXITCODE, ($Args -join ' '))
    }
}

Set-Location $WorkspaceRoot

$head = (& git rev-parse HEAD 2>$null)
if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($head)) {
    throw "Git HEAD could not be resolved. Create a commit before pushing."
}

$status = (& git status --short)
if (-not [string]::IsNullOrWhiteSpace(($status -join "`n"))) {
    throw "Working tree is not clean. Commit or stash changes before pushing.`n$($status -join "`n")"
}

$tags = (& git tag --list $TagPattern)
if ([string]::IsNullOrWhiteSpace(($tags -join "`n"))) {
    throw "No release tag matching $TagPattern was found."
}

$currentBranch = (& git branch --show-current).Trim()
if ([string]::IsNullOrWhiteSpace($currentBranch)) {
    throw "Could not determine current branch."
}

if ($currentBranch -ne $BranchName) {
    if ($ForceBranchRename) {
        Invoke-Git @('branch', '-M', $BranchName)
    }
    else {
        throw "Current branch is '$currentBranch', expected '$BranchName'. Rerun with -ForceBranchRename to rename it."
    }
}

$remote = (& git remote get-url origin 2>$null)
if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($remote)) {
    Invoke-Git @('remote', 'add', 'origin', $GitHubRemoteUrl)
}
else {
    Invoke-Git @('remote', 'set-url', 'origin', $GitHubRemoteUrl)
}

Invoke-Git @('push', '-u', 'origin', $BranchName)
Invoke-Git @('push', 'origin', '--tags')

Write-Host ''
Write-Host 'OK    NPDev monorepo and release tags pushed.'
Write-Host ('      Remote: ' + $GitHubRemoteUrl)
Write-Host ('      Branch: ' + $BranchName)
Write-Host '      Tags:'
git tag --list $TagPattern
