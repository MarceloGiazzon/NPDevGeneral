<#
.SYNOPSIS
    Builds the NPDev Manager installer and, with -Install, installs it on this computer.

.DESCRIPTION
    One command for what used to be typed by hand after every Manager change:

      1. reads the version from NPDevManager/Cargo.toml (the version bump check keeps Cargo.toml,
         tauri.conf.json and main.rs in step, so Cargo.toml is enough);
      2. runs `cargo tauri build` for this platform's bundle (NSIS on Windows, AppImage on Linux);
      3. finds the installer under the cargo target dir, which is read from NPDevManager/.cargo/config.toml
         (or CARGO_TARGET_DIR) -- this script holds no build-root path of its own;
      4. with -Install (Windows only): runs the installer silently (/S) and confirms that the version
         Windows now lists for "NPDev Manager" is the one just built.

    The installer refuses to overwrite a running Manager, so -Install stops first if one is running.
    Pass -StopRunning to let the script close it for you.

    Long output: run it through the digest runner,
        python scripts/ai/run_digest.py -- pwsh -NoProfile -File scripts/release/New-ManagerInstaller.ps1 -Install

.PARAMETER Install
    After building, install silently and verify the installed version (Windows).

.PARAMETER StopRunning
    With -Install: close a running NPDev Manager before installing instead of refusing.

.PARAMETER Launch
    With -Install: start the installed Manager once the install is verified.

.PARAMETER DryRun
    Print what would be built and installed, and the installer path, without doing it.

.EXAMPLE
    pwsh -NoProfile -File scripts/release/New-ManagerInstaller.ps1
.EXAMPLE
    pwsh -NoProfile -File scripts/release/New-ManagerInstaller.ps1 -Install -StopRunning -Launch
#>
[CmdletBinding()]
param(
    [switch]$Install,
    [switch]$StopRunning,
    [switch]$Launch,
    [switch]$DryRun
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
$managerDir = Join-Path $repoRoot "NPDevManager"
$productName = "NPDev Manager"
$processName = "npdev-manager"

function Fail([string]$message) {
    Write-Host "FAIL  $message" -ForegroundColor Red
    exit 1
}

# ---- 1. Version ------------------------------------------------------------------------------------
$cargoToml = Get-Content -LiteralPath (Join-Path $managerDir "Cargo.toml") -Raw
if ($cargoToml -notmatch '(?m)^version\s*=\s*"([^"]+)"') { Fail "no version in NPDevManager/Cargo.toml" }
$version = $Matches[1]

# ---- 2. Where cargo puts the bundle ------------------------------------------------------------------
if (-not [string]::IsNullOrWhiteSpace($env:CARGO_TARGET_DIR)) {
    $targetDir = $env:CARGO_TARGET_DIR
} else {
    $targetDir = Join-Path $managerDir "target"
    $cargoConfig = Join-Path $managerDir ".cargo\config.toml"
    if (Test-Path -LiteralPath $cargoConfig) {
        $cfg = Get-Content -LiteralPath $cargoConfig -Raw
        if ($cfg -match '(?m)^\s*target-dir\s*=\s*"([^"]+)"') {
            # cargo resolves a config-file target-dir relative to the directory holding .cargo/
            $targetDir = if ([System.IO.Path]::IsPathRooted($Matches[1])) { $Matches[1] } else { Join-Path $managerDir $Matches[1] }
        }
    }
}
$targetDir = [System.IO.Path]::GetFullPath($targetDir)

$onWindows = $IsWindows -or ($PSVersionTable.PSEdition -eq "Desktop")
if ($onWindows) {
    $bundle = "nsis"
    $installer = Join-Path $targetDir "release\bundle\nsis\${productName}_${version}_x64-setup.exe"
} else {
    $bundle = "appimage"
    $installer = Join-Path $targetDir "release/bundle/appimage/${productName}_${version}_amd64.AppImage"
}
if ($Install -and -not $onWindows) { Fail "-Install is Windows-only; on Linux the AppImage is the app -- run it directly." }

Write-Host "NPDev Manager $version  ->  $bundle"
Write-Host "  installer: $installer"
if ($DryRun) {
    Write-Host "DRY RUN: would run 'cargo tauri build --bundles $bundle' in $managerDir$(if ($Install) { ', then install silently' })."
    exit 0
}

# ---- 3. Build --------------------------------------------------------------------------------------
& cargo tauri --version *> $null
if ($LASTEXITCODE -ne 0) { Fail "the Tauri CLI is missing -- install it with: cargo install tauri-cli --version '^2' --locked" }

$started = Get-Date
Push-Location $managerDir
try {
    & cargo tauri build --bundles $bundle
    $buildExit = $LASTEXITCODE
} finally {
    Pop-Location
}
if ($buildExit -ne 0) { Fail "cargo tauri build exited $buildExit" }
if (-not (Test-Path -LiteralPath $installer)) { Fail "build succeeded but the installer is not at $installer" }
$built = Get-Item -LiteralPath $installer
if ($built.LastWriteTime -lt $started) { Fail "the installer at $installer is older than this build -- it was not rebuilt" }
Write-Host ("OK    built {0} ({1:N1} MB) in {2:N0}s" -f $built.Name, ($built.Length / 1MB), ((Get-Date) - $started).TotalSeconds) -ForegroundColor Green

if (-not $Install) { exit 0 }

# ---- 4. Install (Windows) --------------------------------------------------------------------------
$running = @(Get-Process -Name $processName -ErrorAction SilentlyContinue)
if ($running.Count -gt 0) {
    if (-not $StopRunning) { Fail "$productName is running (pid $($running.Id -join ', ')). Close it, or re-run with -StopRunning." }
    Write-Host "Stopping the running $productName (pid $($running.Id -join ', '))..."
    $running | Stop-Process -Force
    $running | Wait-Process -Timeout 20 -ErrorAction SilentlyContinue
}

Write-Host "Installing silently..."
$proc = Start-Process -FilePath $installer -ArgumentList "/S" -Wait -PassThru
if ($proc.ExitCode -ne 0) { Fail "installer exited $($proc.ExitCode)" }

$uninstallKey = "HKCU:\Software\Microsoft\Windows\CurrentVersion\Uninstall\$productName"
$entry = Get-ItemProperty -LiteralPath $uninstallKey -ErrorAction SilentlyContinue
if ($null -eq $entry) { Fail "installer finished but Windows lists no '$productName' (looked in $uninstallKey)" }
if ($entry.DisplayVersion -ne $version) { Fail "Windows lists $productName $($entry.DisplayVersion), expected $version" }
$exe = ([string]$entry.DisplayIcon).Trim('"')
Write-Host "OK    installed $productName $version at $($entry.InstallLocation.Trim('"'))" -ForegroundColor Green

if ($Launch) {
    if (-not (Test-Path -LiteralPath $exe)) { Fail "installed, but $exe does not exist to launch" }
    Start-Process -FilePath $exe | Out-Null
    Write-Host "OK    launched $exe" -ForegroundColor Green
}
exit 0
