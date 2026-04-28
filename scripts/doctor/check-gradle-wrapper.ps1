[CmdletBinding()]
param(
    [string]$WorkspaceRoot = "",
    [switch]$PassThru,
    [switch]$RepairSafeGaps = $true
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

. (Join-Path $PSScriptRoot "..\npdev-common.ps1")

function Get-WrapperVersion([string]$PropertiesPath) {
    $content = Get-Content -LiteralPath $PropertiesPath -Raw
    $match = [regex]::Match($content, "gradle-([0-9.]+)-")
    if ($match.Success) {
        return $match.Groups[1].Value
    }
    return $null
}

function Repair-Wrapper([string]$ProjectRoot, [string]$Version) {
    if (-not (Test-NPDevCommandAvailable "gradle")) {
        throw ("Cannot repair wrapper without a global gradle command: " + $ProjectRoot)
    }

    Write-NPDevInfo ("Repairing Gradle wrapper in " + $ProjectRoot + " using Gradle " + $Version)
    Invoke-NPDevCommandStreaming -WorkingDirectory $ProjectRoot -Executable "gradle" -Arguments @("wrapper", "--gradle-version", $Version)
}

if ([string]::IsNullOrWhiteSpace($WorkspaceRoot)) {
    $WorkspaceRoot = Get-NPDevWorkspaceRoot $PSScriptRoot
}
$WorkspaceRoot = Normalize-NPDevPath $WorkspaceRoot

$projects = @(
    @{ name = "NPDevContract/dsl"; path = "NPDevContract\dsl" },
    @{ name = "NPDevEditor"; path = "NPDevEditor" },
    @{ name = "NPDevGenerator"; path = "NPDevGenerator" },
    @{ name = "NPDevKernel"; path = "NPDevKernel" },
    @{ name = "NPDevRuntimeHost"; path = "NPDevRuntimeHost" }
)

$projectResults = @()
$hasFailure = $false

foreach ($project in $projects) {
    $projectRoot = Resolve-NPDevWorkspacePath $WorkspaceRoot $project.path
    $propertiesPath = Join-Path $projectRoot "gradle\wrapper\gradle-wrapper.properties"
    $jarPath = Join-Path $projectRoot "gradle\wrapper\gradle-wrapper.jar"
    $shellPath = Join-Path $projectRoot "gradlew"
    $batPath = Join-Path $projectRoot "gradlew.bat"

    $missing = @()
    foreach ($pathValue in @($propertiesPath, $jarPath, $shellPath, $batPath)) {
        if (-not (Test-Path -LiteralPath $pathValue)) {
            $missing += (Split-Path -Leaf $pathValue)
        }
    }

    if ($missing.Count -gt 0 -and $RepairSafeGaps -and (Test-Path -LiteralPath $propertiesPath)) {
        $version = Get-WrapperVersion $propertiesPath
        if (-not [string]::IsNullOrWhiteSpace($version)) {
            Repair-Wrapper -ProjectRoot $projectRoot -Version $version
            $missing = @()
            foreach ($pathValue in @($propertiesPath, $jarPath, $shellPath, $batPath)) {
                if (-not (Test-Path -LiteralPath $pathValue)) {
                    $missing += (Split-Path -Leaf $pathValue)
                }
            }
        }
    }

    $projectResults += [pscustomobject]@{
        project = $project.name
        missing = $missing
        wrapperVersion = if (Test-Path -LiteralPath $propertiesPath) { Get-WrapperVersion $propertiesPath } else { $null }
    }

    if ($missing.Count -gt 0) {
        $hasFailure = $true
    }
}

$result = if (-not $hasFailure) {
    New-NPDevCheckResult "gradle-wrapper" "passed" "Gradle wrappers are present for all supported projects." @{
        projects = $projectResults
    }
}
else {
    New-NPDevCheckResult "gradle-wrapper" "failed" "One or more Gradle wrappers are incomplete." @{
        projects = $projectResults
    }
}

if ($PassThru) {
    return $result
}

if ($result.status -eq "passed") {
    Write-NPDevOk $result.summary
    return
}

Write-NPDevWarn $result.summary
throw $result.summary

