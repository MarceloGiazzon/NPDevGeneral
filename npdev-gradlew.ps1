#!/usr/bin/env pwsh
# NPDev Gradle wrapper.
#
# Runs the nearest gradlew with Gradle's PROJECT CACHE (.gradle/) relocated OUT of the
# NPDev_General source tree. Gradle only supports relocating the project cache via the
# --project-cache-dir command-line flag (settings.gradle / init scripts run too late), so
# this wrapper injects it. Mirrors the external build-output policy already applied to build/.
#
# Usage (from any NPDev build root, e.g. NPDevKernel, NPDevGenerator, NPDevContract/dsl, or the
# repo root itself):
#     <repo>\npdev-gradlew.ps1 :generator:test --console=plain
#
# Cache location: $env:NPDEV_BUILD_ROOT (if set) else <parent-of-NPDev_General>\Build,
# under gradle-cache\<build-root-name>.
$ErrorActionPreference = 'Stop'

function Find-Up([string]$start, [scriptblock]$predicate) {
    $dir = Get-Item -LiteralPath $start
    while ($null -ne $dir) {
        if (& $predicate $dir.FullName) { return $dir.FullName }
        $dir = $dir.Parent
    }
    return $null
}

$cwd = (Get-Location).Path
$buildRoot = Find-Up $cwd { param($d) (Test-Path (Join-Path $d 'gradlew.bat')) -or (Test-Path (Join-Path $d 'gradlew')) }
if (-not $buildRoot) { throw "No gradlew found from '$cwd' upward; run this from inside an NPDev Gradle build root." }

$srcRoot = Find-Up $buildRoot { param($d) (Split-Path $d -Leaf) -eq 'NPDev_General' }

if ($env:NPDEV_BUILD_ROOT) {
    $externalRoot = $env:NPDEV_BUILD_ROOT
} elseif ($srcRoot) {
    $externalRoot = Join-Path (Split-Path $srcRoot -Parent) 'Build'
} else {
    $externalRoot = Join-Path (Split-Path $buildRoot -Parent) 'Build'
}

$cacheDir = Join-Path $externalRoot ('gradle-cache/' + (Split-Path $buildRoot -Leaf))
# REG-11: Find-Up above already accepts either wrapper, so pick the OS-appropriate one here rather
# than hardcoding gradlew.bat (which would be found-then-fail on a Linux/macOS build root).
$gradlew = if ($IsWindows) { Join-Path $buildRoot 'gradlew.bat' } else { Join-Path $buildRoot 'gradlew' }

& $gradlew --project-cache-dir $cacheDir @args
exit $LASTEXITCODE
