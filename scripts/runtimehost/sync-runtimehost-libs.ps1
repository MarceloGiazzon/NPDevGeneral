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
# BT-1: runtimehost-core is the app-INDEPENDENT half of RuntimeHost (scripts/proofs/
# classify_runtimehost_sources.py's 262-file split), built as its own independent Gradle project --
# NOT an `include 'adapters:...'` subproject of NPDevKernel, because NPDevRuntimeHost itself is a
# single-project TEMPLATE (its settings.gradle just names `FinalExec`) with no multi-module
# settings.gradle to hang a subproject off. Staged into $runtimeHostLibs exactly like the
# kernel/generator/dsl jars below, so a generated app consumes it via the SAME
# `implementation fileTree(dir: npdevRuntimeHostLibsDir, ...)` mechanism it already uses for those.
$runtimeHostCoreRoot = Resolve-NPDevWorkspacePath $WorkspaceRoot "NPDevRuntimeHost\runtimehost-core"
$kernelGradleWrapper = Get-NPDevGradleWrapperExecutable $kernelRoot
$generatorGradleWrapper = Get-NPDevGradleWrapperExecutable $generatorRoot
$runtimeHostCoreGradleWrapper = Get-NPDevGradleWrapperExecutable $runtimeHostCoreRoot

New-Item -ItemType Directory -Force -Path $runtimeHostLibs | Out-Null
Ensure-NPDevFile $kernelGradleWrapper "Kernel Gradle wrapper"
Ensure-NPDevFile $generatorGradleWrapper "Generator Gradle wrapper"
Ensure-NPDevFile $runtimeHostCoreGradleWrapper "RuntimeHost-core Gradle wrapper"

# Resolve the external build root via the SAME shared Get-NPDevBuildRoot that $runtimeHostLibs
# above already used (Get-NPDevRuntimeHostLibsDir calls it too) -- so this script's jar-discovery
# ($externalGradleBuildRoot) scans the very directory the Kernel/Generator gradle builds actually
# write to, and the sync destination can never again disagree with the build root the way it did
# before this was centralized (see Get-NPDevBuildRoot's own comment in npdev-common.ps1 for the
# live CI failure this caused).
$externalBuildRoot = Get-NPDevBuildRoot $WorkspaceRoot
$externalGradleBuildRoot = Join-Path $externalBuildRoot "gradle"
$env:NPDEV_BUILD_ROOT = $externalBuildRoot

$sourceRoots = @(
    (Resolve-NPDevWorkspacePath $WorkspaceRoot "NPDevContract"),
    (Resolve-NPDevWorkspacePath $WorkspaceRoot "NPDevGenerator"),
    (Resolve-NPDevWorkspacePath $WorkspaceRoot "NPDevKernel")
)

# Discovers *.jar under each $SourceRoots' own build/libs (in-repo modules) plus anything under the
# external Gradle build root's **/libs (every platform module -- kernel/generator/dsl/runtimehost-core
# -- redirects layout.buildDirectory there, REG-10/portability), then copies whatever is newer/missing
# into $runtimeHostLibs. Called twice: once after the kernel/generator build (so runtimehost-core's
# OWN build can resolve the kernel/dsl jars it needs via its identical npdevRuntimeHostLibsDir fileTree
# dependency), and once more at the end (so runtimehost-core's freshly built jar, now ALSO visible
# under the external Gradle build root, gets staged too) -- the second call's result is authoritative
# for the manifest/report below; the first call exists purely to unblock the second build.
function Sync-NPDevJars {
    param(
        [string[]]$SourceRoots,
        [string]$ExternalGradleBuildRoot,
        [string]$RuntimeHostLibs
    )

    $sourceByName = @{}
    foreach ($sourceRoot in $SourceRoots) {
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
    if (Test-Path -LiteralPath $ExternalGradleBuildRoot -PathType Container) {
        $externalJars = @(Get-ChildItem -LiteralPath $ExternalGradleBuildRoot -Recurse -Filter *.jar -File |
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

    $existingTargetJars = @(Get-ChildItem -LiteralPath $RuntimeHostLibs -Filter *.jar -File | Sort-Object Name)
    $existingTargetByName = @{}
    foreach ($target in $existingTargetJars) {
        $existingTargetByName[$target.Name] = $target
    }

    foreach ($jarName in @($sourceByName.Keys | Sort-Object)) {
        $sourcePath = [string]$sourceByName[$jarName]
        $source = Get-Item -LiteralPath $sourcePath
        $targetPath = Join-Path $RuntimeHostLibs $jarName

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

    return [pscustomobject]@{
        sourceByName = $sourceByName
        sourceDiscoveredJars = $sourceDiscoveredJars
        copied = $copied
        upToDate = $upToDate
        externalOrMissing = $externalOrMissing
    }
}

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
    $runtimeHostCoreProjectCacheDir = Join-Path $externalBuildRoot "gradle-project-caches\runtimehost-core"

    Write-NPDevInfo "Building local Kernel/Contract runtime jars for RuntimeHost staging (npdevBuildRoot=$externalBuildRoot)"
    Invoke-NPDevCommandStreaming -WorkingDirectory $kernelRoot -Executable $kernelGradleWrapper -Arguments @("jar", "-PnpdevBuildRoot=$externalBuildRoot", "--project-cache-dir", $kernelProjectCacheDir, "--no-daemon", "--console=plain")

    Write-NPDevInfo "Building local Generator and CLI jars for RuntimeHost staging"
    Invoke-NPDevCommandStreaming -WorkingDirectory $generatorRoot -Executable $generatorGradleWrapper -Arguments @(":generator:jar", ":tools:npdev-cli:jar", "-PnpdevBuildRoot=$externalBuildRoot", "--project-cache-dir", $generatorProjectCacheDir, "--no-daemon", "--console=plain")

    # Stage kernel/dsl/generator jars NOW (before building runtimehost-core) -- runtimehost-core's
    # own build.gradle depends on them via the identical npdevRuntimeHostLibsDir fileTree mechanism
    # a generated app uses, so they must already be sitting in $runtimeHostLibs before its
    # compileJava runs (its own verifyNpdevRuntimeHostLibs task fails loud otherwise).
    Sync-NPDevJars -SourceRoots $sourceRoots -ExternalGradleBuildRoot $externalGradleBuildRoot -RuntimeHostLibs $runtimeHostLibs | Out-Null

    Write-NPDevInfo "Building local runtimehost-core jar (+ sources jar) for RuntimeHost staging"
    Invoke-NPDevCommandStreaming -WorkingDirectory $runtimeHostCoreRoot -Executable $runtimeHostCoreGradleWrapper -Arguments @("jar", "sourcesJar", "-PnpdevBuildRoot=$externalBuildRoot", "-PnpdevRuntimeHostLibsDir=$runtimeHostLibs", "--project-cache-dir", $runtimeHostCoreProjectCacheDir, "--no-daemon", "--console=plain")
}

$syncResult = Sync-NPDevJars -SourceRoots $sourceRoots -ExternalGradleBuildRoot $externalGradleBuildRoot -RuntimeHostLibs $runtimeHostLibs
$sourceByName = $syncResult.sourceByName
$sourceDiscoveredJars = $syncResult.sourceDiscoveredJars
$copied = $syncResult.copied
$upToDate = $syncResult.upToDate
$externalOrMissing = $syncResult.externalOrMissing

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
    # runtimehost-core redirects layout.buildDirectory to the external Gradle build root (same
    # convention as kernel/generator/dsl), so it normally has no in-repo build/ dir to clean -- this
    # only fires for a stray local build (e.g. an ad-hoc `gradlew build` run directly against it).
    if (Test-Path -LiteralPath $runtimeHostCoreRoot -PathType Container) {
        foreach ($buildDir in @(Get-ChildItem -LiteralPath $runtimeHostCoreRoot -Recurse -Directory -Force -ErrorAction SilentlyContinue | Where-Object { $_.Name -eq "build" } | Sort-Object { $_.FullName.Length } -Descending)) {
            if (-not (Test-Path -LiteralPath $buildDir.FullName -PathType Container)) {
                continue
            }
            $relativeBuildDir = Get-NPDevWorkspaceRelativePath $WorkspaceRoot $buildDir.FullName
            if ($relativeBuildDir -notmatch '^NPDevRuntimeHost\\runtimehost-core\\') {
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
