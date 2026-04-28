[CmdletBinding()]
param(
    [string]$WorkspaceRoot = "",
    [string]$RunId = "",
    [string]$ReportPath = "",
    [string]$FirstZipPath = "",
    [string]$SecondZipPath = ""
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

. (Join-Path $PSScriptRoot "..\npdev-common.ps1")
. (Join-Path $PSScriptRoot "..\statezip-common.ps1")

function Get-StringSha256 {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Value
    )

    $bytes = [System.Text.Encoding]::UTF8.GetBytes($Value)
    $sha256 = [System.Security.Cryptography.SHA256]::Create()
    try {
        return ([System.BitConverter]::ToString($sha256.ComputeHash($bytes))).Replace("-", "").ToLowerInvariant()
    }
    finally {
        $sha256.Dispose()
    }
}

function Get-NormalizedArchiveHash {
    param(
        [Parameter(Mandatory = $true)]
        [string]$PathValue,
        [Parameter(Mandatory = $true)]
        [string]$ScratchRoot
    )

    if (-not $PathValue.EndsWith(".zip", [System.StringComparison]::OrdinalIgnoreCase)) {
        return (Get-FileHash -LiteralPath $PathValue -Algorithm SHA256).Hash.ToLowerInvariant()
    }

    $extractRoot = Join-Path $ScratchRoot ([System.Guid]::NewGuid().ToString("N"))
    Ensure-Directory -PathValue $extractRoot
    try {
        Expand-Archive -LiteralPath $PathValue -DestinationPath $extractRoot -Force
        $entries = @(
            Get-ChildItem -LiteralPath $extractRoot -Recurse -File -Force |
            Sort-Object FullName |
            ForEach-Object {
                $relativePath = $_.FullName.Substring($extractRoot.Length + 1).Replace('\', '/')
                $entryHash = Get-NormalizedArchiveHash -PathValue $_.FullName -ScratchRoot $ScratchRoot
                $relativePath + "=" + $entryHash
            }
        )
        return Get-StringSha256 -Value ($entries -join "`n")
    }
    finally {
        Remove-DirectorySafe -PathValue $extractRoot
    }
}

if ([string]::IsNullOrWhiteSpace($WorkspaceRoot)) {
    $WorkspaceRoot = Get-NPDevWorkspaceRoot $PSScriptRoot
}
$WorkspaceRoot = Normalize-NPDevPath $WorkspaceRoot
$RunId = Resolve-NPDevRunId $RunId "statezip-determinism"

if ([string]::IsNullOrWhiteSpace($ReportPath)) {
    $ReportPath = Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\reports\out\statezip-determinism-report.json"
}
else {
    $ReportPath = Normalize-NPDevPath $ReportPath
}

$stateZipOutDir = Get-DefaultStateZipOutDir -WorkspaceRoot $WorkspaceRoot
$existingZips = if (Test-Path -LiteralPath $stateZipOutDir -PathType Container) {
    @(Get-ChildItem -LiteralPath $stateZipOutDir -File -Filter "*.zip" -ErrorAction SilentlyContinue | Sort-Object LastWriteTime -Descending)
}
else {
    @()
}

function Invoke-DeterministicStateZipGeneration {
    param(
        [Parameter(Mandatory = $true)]
        [string]$OutDir,
        [Parameter(Mandatory = $true)]
        [string]$StampValue
    )

    $stateZipScript = Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\statezip-npdev-general.ps1"
    Ensure-NPDevFile $stateZipScript "statezip-npdev-general.ps1"
    & $stateZipScript -WorkspaceRoot $WorkspaceRoot -OutDir $OutDir -Stamp $StampValue -Quiet | Out-Null

    $zipPath = Join-Path $OutDir ("NPDev_General_State_ALL_" + $StampValue + ".zip")
    Assert-PathExists -PathValue $zipPath -Label "Deterministic state zip"
    return $zipPath
}

$generatedPair = $false
$generatedRoot = $null
if ([string]::IsNullOrWhiteSpace($FirstZipPath) -and [string]::IsNullOrWhiteSpace($SecondZipPath)) {
    $generatedPair = $true
    $generatedRoot = Join-Path $env:TEMP ("statezip-determinism-" + $RunId)
    $firstOutDir = Join-Path $generatedRoot "first"
    $secondOutDir = Join-Path $generatedRoot "second"
    Remove-DirectorySafe -PathValue $generatedRoot
    Ensure-Directory -PathValue $firstOutDir
    Ensure-Directory -PathValue $secondOutDir

    $fixedStamp = "DETERMINISM_CHECK"
    $FirstZipPath = Invoke-DeterministicStateZipGeneration -OutDir $firstOutDir -StampValue $fixedStamp
    $SecondZipPath = Invoke-DeterministicStateZipGeneration -OutDir $secondOutDir -StampValue $fixedStamp
}
elseif ([string]::IsNullOrWhiteSpace($FirstZipPath) -and $existingZips.Count -ge 1) {
    $FirstZipPath = $existingZips[0].FullName
}
elseif ([string]::IsNullOrWhiteSpace($SecondZipPath) -and $existingZips.Count -ge 2) {
    $SecondZipPath = $existingZips[1].FullName
}

$pairsAvailable = (-not [string]::IsNullOrWhiteSpace($FirstZipPath)) -and (-not [string]::IsNullOrWhiteSpace($SecondZipPath)) -and (Test-Path -LiteralPath $FirstZipPath -PathType Leaf) -and (Test-Path -LiteralPath $SecondZipPath -PathType Leaf)
$comparisonScratchRoot = if ($generatedPair) { Join-Path $generatedRoot "compare" } else { Join-Path $env:TEMP ("statezip-compare-" + $RunId) }
if ($pairsAvailable) {
    Ensure-Directory -PathValue $comparisonScratchRoot
}
$firstRawHash = if ($pairsAvailable) { (Get-FileHash -LiteralPath $FirstZipPath -Algorithm SHA256).Hash.ToLowerInvariant() } else { $null }
$secondRawHash = if ($pairsAvailable) { (Get-FileHash -LiteralPath $SecondZipPath -Algorithm SHA256).Hash.ToLowerInvariant() } else { $null }
$firstHash = if ($pairsAvailable) { Get-NormalizedArchiveHash -PathValue $FirstZipPath -ScratchRoot $comparisonScratchRoot } else { $null }
$secondHash = if ($pairsAvailable) { Get-NormalizedArchiveHash -PathValue $SecondZipPath -ScratchRoot $comparisonScratchRoot } else { $null }
$overallStatus = if ($pairsAvailable -and $firstHash -eq $secondHash) { "passed" } elseif ($pairsAvailable) { "failed" } else { "warning" }

$report = [pscustomobject]@{
    generatedAt = (Get-Date).ToString("o")
    runId = $RunId
    scriptPath = Get-NPDevWorkspaceRelativePath $WorkspaceRoot $PSCommandPath
    workspaceRoot = $WorkspaceRoot
    overallStatus = $overallStatus
    firstZip = if ([string]::IsNullOrWhiteSpace($FirstZipPath)) { $null } else { $FirstZipPath }
    secondZip = if ([string]::IsNullOrWhiteSpace($SecondZipPath)) { $null } else { $SecondZipPath }
    firstSha256 = $firstHash
    secondSha256 = $secondHash
    firstRawSha256 = $firstRawHash
    secondRawSha256 = $secondRawHash
    generatedPair = $generatedPair
    generatedRoot = $generatedRoot
    summary = [pscustomobject]@{
        pairAvailable = $pairsAvailable
        matched = ($pairsAvailable -and $firstHash -eq $secondHash)
        zipCount = @($existingZips).Count
    }
}
Write-NPDevJsonFile $ReportPath $report

if ($overallStatus -eq "passed") {
    Write-NPDevOk "State zip determinism check passed."
    return
}

if ($overallStatus -eq "warning") {
    Write-NPDevWarn "State zip determinism check has insufficient evidence."
    return
}

Write-NPDevWarn "State zip determinism check failed."
throw "State zip determinism check failed."
