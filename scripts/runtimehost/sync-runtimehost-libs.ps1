[CmdletBinding()]
param(
    [string]$WorkspaceRoot = "",
    [switch]$BuildLocalJars,
    [string]$RuntimeHostLibsDir = "",
    [string]$ReportPath = ""
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

. (Join-Path $PSScriptRoot "..\npdev-common.ps1")

if ([string]::IsNullOrWhiteSpace($WorkspaceRoot)) {
    $WorkspaceRoot = Get-NPDevWorkspaceRoot $PSScriptRoot
}
$WorkspaceRoot = Normalize-NPDevPath $WorkspaceRoot

if ([string]::IsNullOrWhiteSpace($ReportPath)) {
    $ReportPath = Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\reports\out\runtimehost-libs-sync-report.json"
}
else {
    $ReportPath = Normalize-NPDevPath $ReportPath
}

$runtimeHostLibs = if ([string]::IsNullOrWhiteSpace($RuntimeHostLibsDir)) {
    Get-NPDevRuntimeHostLibsDir $WorkspaceRoot
}
else {
    Normalize-NPDevPath $RuntimeHostLibsDir
}
$kernelRoot = Resolve-NPDevWorkspacePath $WorkspaceRoot "NPDevKernel"
$generatorRoot = Resolve-NPDevWorkspacePath $WorkspaceRoot "NPDevGenerator"

New-Item -ItemType Directory -Force -Path $runtimeHostLibs | Out-Null
Ensure-NPDevFile (Join-Path $kernelRoot "gradlew.bat") "Kernel Gradle wrapper"
Ensure-NPDevFile (Join-Path $generatorRoot "gradlew.bat") "Generator Gradle wrapper"

if ($BuildLocalJars) {
    Write-NPDevInfo "Building local Kernel/Contract runtime jars for RuntimeHost staging"
    Invoke-NPDevCommandStreaming -WorkingDirectory $kernelRoot -Executable ".\gradlew.bat" -Arguments @("jar", "--no-daemon", "--console=plain")

    Write-NPDevInfo "Building local Generator and CLI jars for RuntimeHost staging"
    Invoke-NPDevCommandStreaming -WorkingDirectory $generatorRoot -Executable ".\gradlew.bat" -Arguments @(":generator:jar", ":tools:npdev-cli:jar", "--no-daemon", "--console=plain")
}

$sourceRoots = @(
    (Resolve-NPDevWorkspacePath $WorkspaceRoot "NPDevContract"),
    (Resolve-NPDevWorkspacePath $WorkspaceRoot "NPDevGenerator"),
    (Resolve-NPDevWorkspacePath $WorkspaceRoot "NPDevKernel")
)

$sourceByName = @{}
foreach ($sourceRoot in $sourceRoots) {
    if (-not (Test-Path -LiteralPath $sourceRoot -PathType Container)) {
        continue
    }

    $jars = @(Get-ChildItem -LiteralPath $sourceRoot -Recurse -Filter *.jar -File |
            Where-Object { $_.FullName -like "*\build\libs\*" } |
            Where-Object { $_.Name -notlike "npdev-migrations-*" })

    foreach ($jar in $jars) {
        if (-not $sourceByName.ContainsKey($jar.Name)) {
            $sourceByName[$jar.Name] = $jar.FullName
            continue
        }

        $current = Get-Item -LiteralPath $sourceByName[$jar.Name]
        if ($jar.LastWriteTimeUtc -gt $current.LastWriteTimeUtc) {
            $sourceByName[$jar.Name] = $jar.FullName
        }
    }
}

$copied = @()
$upToDate = @()
$externalOrMissing = @()

$existingTargetJars = @(Get-ChildItem -LiteralPath $runtimeHostLibs -Filter *.jar -File | Sort-Object Name)
$existingTargetByName = @{}
foreach ($target in $existingTargetJars) {
    $existingTargetByName[$target.Name] = $target
}

foreach ($jarName in @($sourceByName.Keys | Sort-Object)) {
    $sourcePath = [string]$sourceByName[$jarName]
    $source = Get-Item -LiteralPath $sourcePath
    $targetPath = Join-Path $runtimeHostLibs $jarName

    if (-not $existingTargetByName.ContainsKey($jarName)) {
        Copy-Item -LiteralPath $source.FullName -Destination $targetPath -Force
        $copied += [pscustomobject]@{
            name = $jarName
            source = $source.FullName
            target = $targetPath
        }
        continue
    }

    $target = $existingTargetByName[$jarName]
    $sameSize = $source.Length -eq $target.Length
    $sameWriteTime = [math]::Abs(($source.LastWriteTimeUtc - $target.LastWriteTimeUtc).TotalSeconds) -lt 1

    if ($sameSize -and $sameWriteTime) {
        $upToDate += [pscustomobject]@{
            name = $jarName
            source = $source.FullName
            target = $target.FullName
        }
        continue
    }

    Copy-Item -LiteralPath $source.FullName -Destination $target.FullName -Force
    $copied += [pscustomobject]@{
        name = $jarName
        source = $source.FullName
        target = $target.FullName
    }
}

foreach ($target in $existingTargetJars) {
    if (-not $sourceByName.ContainsKey($target.Name)) {
        $externalOrMissing += [pscustomobject]@{
            name = $target.Name
            target = $target.FullName
        }
    }
}

$requiredLocalJars = @(
    "dsl-0.1.0.jar",
    "kernel-0.1.0.jar",
    "expression-cel-0.1.0.jar"
)
$missingRequired = @()
foreach ($required in $requiredLocalJars) {
    if (-not (Test-Path -LiteralPath (Join-Path $runtimeHostLibs $required) -PathType Leaf)) {
        $missingRequired += $required
    }
}

$cleanedSourceBuildOutputs = @()
if ($BuildLocalJars) {
    foreach ($sourceRoot in $sourceRoots) {
        if (-not (Test-Path -LiteralPath $sourceRoot -PathType Container)) {
            continue
        }

        foreach ($buildDir in @(Get-ChildItem -LiteralPath $sourceRoot -Recurse -Directory -Force -ErrorAction SilentlyContinue | Where-Object { $_.Name -eq "build" } | Sort-Object { $_.FullName.Length } -Descending)) {
            if (-not (Test-Path -LiteralPath $buildDir.FullName -PathType Container)) {
                continue
            }
            $relativeBuildDir = Get-NPDevWorkspaceRelativePath $WorkspaceRoot $buildDir.FullName
            if ($relativeBuildDir -notmatch '^(NPDevContract|NPDevGenerator|NPDevKernel)\\') {
                throw ("Refusing to clean unexpected source build output: " + $relativeBuildDir)
            }
            Remove-Item -LiteralPath $buildDir.FullName -Recurse -Force
            $cleanedSourceBuildOutputs += $relativeBuildDir
        }
    }
}

$overallStatus = if ($missingRequired.Count -gt 0) { "failed" } else { "passed" }
$report = [pscustomobject]@{
    generatedAt = (Get-Date).ToString("o")
    workspaceRoot = $WorkspaceRoot
    runtimeHostLibs = $runtimeHostLibs
    runtimeHostLibsLocation = "external-local-cache"
    builtLocalJars = [bool]$BuildLocalJars
    overallStatus = $overallStatus
    copied = $copied
    upToDate = $upToDate
    externalOrMissing = $externalOrMissing
    missingRequired = $missingRequired
    cleanedSourceBuildOutputs = $cleanedSourceBuildOutputs
}
Write-NPDevJsonFile $ReportPath $report

if ($overallStatus -ne "passed") {
    Write-NPDevWarn ("RuntimeHost libs sync failed; missing required jars: " + ($missingRequired -join ", "))
    throw "RuntimeHost libs sync failed."
}

Write-NPDevOk ("RuntimeHost libs synced outside workspace. Copied " + $copied.Count + " local jar(s); " + $upToDate.Count + " already current. Path: " + $runtimeHostLibs)
