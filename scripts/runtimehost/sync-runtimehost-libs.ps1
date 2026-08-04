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
$kernelGradleWrapper = Get-NPDevGradleWrapperExecutable $kernelRoot
$generatorGradleWrapper = Get-NPDevGradleWrapperExecutable $generatorRoot

New-Item -ItemType Directory -Force -Path $runtimeHostLibs | Out-Null
Ensure-NPDevFile $kernelGradleWrapper "Kernel Gradle wrapper"
Ensure-NPDevFile $generatorGradleWrapper "Generator Gradle wrapper"

# Resolve the external build root by MIRRORING build.gradle's resolveNpdevBuildRoot EXACTLY, so this
# script's jar-discovery ($externalGradleBuildRoot) scans the very directory the Kernel/Generator
# gradle builds actually write to -- whether or not -PnpdevBuildRoot / NPDEV_BUILD_ROOT take effect in
# a given environment. The gradle builds run with rootDir = <workspace>/NPDevKernel (and
# /NPDevGenerator); their build.gradle walks UP for a directory literally named 'NPDev_General' and,
# if found, redirects output to <that>/../Build, else to <rootDir>/../Build (= <workspace>/Build).
# The old <workspace.parent>/Build guess diverged whenever the workspace folder is not named exactly
# 'NPDev_General' or is nested inside one -- e.g. a `git clone` folder named 'NPDevGeneral', or a git
# worktree under .../.claude/worktrees -- so the built jars landed where discovery never scanned
# ("No RuntimeHost jars were discovered"). Precedence matches build.gradle: property/env override,
# else the NPDev_General walk with the same fallback. (See knowledge card runtimehost-libs-dir-mismatch.)
$gradleRootDir = Get-Item -LiteralPath $kernelRoot
$externalBuildRoot = if (-not [string]::IsNullOrWhiteSpace($env:NPDEV_BUILD_ROOT)) {
    Normalize-NPDevPath $env:NPDEV_BUILD_ROOT
}
else {
    $ancestor = $gradleRootDir
    while ($null -ne $ancestor -and $ancestor.Name -ne 'NPDev_General') {
        $ancestor = $ancestor.Parent
    }
    if ($null -ne $ancestor -and $null -ne $ancestor.Parent) {
        Normalize-NPDevPath (Join-Path $ancestor.Parent.FullName "Build")
    }
    else {
        Normalize-NPDevPath (Join-Path $gradleRootDir.Parent.FullName "Build")
    }
}
$externalGradleBuildRoot = Join-Path $externalBuildRoot "gradle"
$env:NPDEV_BUILD_ROOT = $externalBuildRoot

if ($BuildLocalJars) {
    # NPDevKernel/gradle.properties and NPDevGenerator/gradle.properties hardcode
    # org.gradle.projectcachedir to this machine's own D:/WorkSpace/NPDev/Build/... (dev-machine
    # build-output policy, keeps Gradle's own cache out of the repo tree). It is a START PARAMETER
    # read before any -P/env override applies, so on a machine without that exact path Gradle fails
    # before the build even starts ("Cannot convert URL '...' to a file"). --project-cache-dir is the
    # one reliable override; derive it from the SAME $externalBuildRoot this script already computed
    # portably above, so this is a no-op on the author's own machine and portable everywhere else.
    $kernelProjectCacheDir = Join-Path $externalBuildRoot "gradle-project-caches\kernel"
    $generatorProjectCacheDir = Join-Path $externalBuildRoot "gradle-project-caches\generator"

    Write-NPDevInfo "Building local Kernel/Contract runtime jars for RuntimeHost staging (npdevBuildRoot=$externalBuildRoot)"
    Invoke-NPDevCommandStreaming -WorkingDirectory $kernelRoot -Executable $kernelGradleWrapper -Arguments @("jar", "-PnpdevBuildRoot=$externalBuildRoot", "--project-cache-dir", $kernelProjectCacheDir, "--no-daemon", "--console=plain")

    Write-NPDevInfo "Building local Generator and CLI jars for RuntimeHost staging"
    Invoke-NPDevCommandStreaming -WorkingDirectory $generatorRoot -Executable $generatorGradleWrapper -Arguments @(":generator:jar", ":tools:npdev-cli:jar", "-PnpdevBuildRoot=$externalBuildRoot", "--project-cache-dir", $generatorProjectCacheDir, "--no-daemon", "--console=plain")
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
            Where-Object { ($_.FullName -replace "\\", "/") -like "*/build/libs/*" } |
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
if (Test-Path -LiteralPath $externalGradleBuildRoot -PathType Container) {
    $externalJars = @(Get-ChildItem -LiteralPath $externalGradleBuildRoot -Recurse -Filter *.jar -File |
            Where-Object { ($_.FullName -replace "\\", "/") -like "*/libs/*" } |
            Where-Object { $_.Name -notlike "npdev-migrations-*" })

    foreach ($jar in $externalJars) {
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

$sourceDiscoveredJars = @($sourceByName.Keys | Sort-Object | ForEach-Object {
        $sourcePath = [string]$sourceByName[$_]
        [pscustomobject]@{
            name = [string]$_
            source = $sourcePath
        }
    })
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

$requiredLocalJars = @($sourceByName.Keys | Sort-Object)
$discoveryFailures = @()
if ($requiredLocalJars.Count -eq 0) {
    $discoveryFailures += "No RuntimeHost jars were discovered under build/libs after local jar build."
}
$missingRequired = @()
foreach ($required in $requiredLocalJars) {
    if (-not (Test-Path -LiteralPath (Join-Path $runtimeHostLibs $required) -PathType Leaf)) {
        $missingRequired += $required
    }
}

$manifestPath = Join-Path $runtimeHostLibs "runtimehost-libs-manifest.json"
$manifest = [pscustomobject]@{
    schemaVersion = "npdev-runtimehost-libs-manifest.v1"
    generatedAt = (Get-Date).ToUniversalTime().ToString("o")
    runtimeHostLibsLocation = "external-local-cache"
    requiredStagedJars = $requiredLocalJars
    sourceDiscoveredJars = $sourceDiscoveredJars
}
Write-NPDevJsonFile $manifestPath $manifest

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

$overallStatus = if ($missingRequired.Count -gt 0 -or $discoveryFailures.Count -gt 0) { "failed" } else { "passed" }
$report = [pscustomobject]@{
    generatedAt = (Get-Date).ToString("o")
    workspaceRoot = $WorkspaceRoot
    runtimeHostLibs = $runtimeHostLibs
    runtimeHostLibsLocation = "external-local-cache"
    builtLocalJars = [bool]$BuildLocalJars
    overallStatus = $overallStatus
    sourceDiscoveredJars = $sourceDiscoveredJars
    requiredStagedJars = $requiredLocalJars
    runtimeHostLibsManifest = $manifestPath
    copied = $copied
    upToDate = $upToDate
    externalOrMissing = $externalOrMissing
    missingRequired = $missingRequired
    discoveryFailures = $discoveryFailures
    cleanedSourceBuildOutputs = $cleanedSourceBuildOutputs
}
Write-NPDevJsonFile $ReportPath $report

if ($overallStatus -ne "passed") {
    Write-NPDevWarn ("RuntimeHost libs sync failed; missing required jars: " + ($missingRequired -join ", ") + "; discovery failures: " + ($discoveryFailures -join ", "))
    throw "RuntimeHost libs sync failed."
}

Write-NPDevOk ("RuntimeHost libs synced outside workspace. Copied " + $copied.Count + " local jar(s); " + $upToDate.Count + " already current. Path: " + $runtimeHostLibs)
