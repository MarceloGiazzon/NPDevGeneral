param(
  [switch]$KeepGenerated
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Remove-DirectoryWithRetry {
  param(
    [string]$Path,
    [int]$MaxAttempts = 8,
    [int]$DelayMs = 500
  )

  if (-not (Test-Path $Path)) {
    return
  }

  for ($attempt = 1; $attempt -le $MaxAttempts; $attempt++) {
    try {
      Remove-Item -Path $Path -Recurse -Force -ErrorAction Stop
      return
    } catch {
      if ($attempt -eq $MaxAttempts) {
        throw "Unable to remove directory '$Path' after $MaxAttempts attempts. Close processes using this folder and retry."
      }
      Start-Sleep -Milliseconds $DelayMs
    }
  }
}

$uiRoot = [System.IO.Path]::GetFullPath($PSScriptRoot)
$gptRoot = Split-Path $uiRoot -Parent
$sourceRoot = Split-Path $gptRoot -Parent
$buildRoot = if ([string]::IsNullOrWhiteSpace($env:NPDEV_BUILD_ROOT)) {
  Join-Path (Split-Path $sourceRoot -Parent) "Build"
} else {
  $env:NPDEV_BUILD_ROOT
}
$distDir = if ([string]::IsNullOrWhiteSpace($env:NPDEV_UI_DIST_DIR)) {
  Join-Path $buildRoot "ui\npdev-editor-ui-react\dist"
} else {
  $env:NPDEV_UI_DIST_DIR
}
$nodeModulesDir = Join-Path $uiRoot "node_modules"
$npdevTemplatesDir = Join-Path $sourceRoot "NPDevGenerator\generator\src\main\resources\npdev-templates"
$templateDir = Join-Path $npdevTemplatesDir "static-react"
$manifestPath = Join-Path $npdevTemplatesDir "static-react-manifest.json"

if (-not (Test-Path (Join-Path $uiRoot "package.json"))) {
  throw "package.json not found in ui-react root: $uiRoot"
}

Push-Location $uiRoot
try {
  Write-Host "[react-templates] Cleaning node_modules/dist"
  Remove-DirectoryWithRetry -Path $nodeModulesDir
  Remove-DirectoryWithRetry -Path $distDir

  Write-Host "[react-templates] npm ci"
  & npm ci
  if ($LASTEXITCODE -ne 0) {
    throw "npm ci failed with exit code $LASTEXITCODE"
  }

  Write-Host "[react-templates] npm run build"
  $env:NPDEV_BUILD_ROOT = $buildRoot
  $env:NPDEV_UI_DIST_DIR = $distDir
  & npm run build
  if ($LASTEXITCODE -ne 0) {
    throw "npm run build failed with exit code $LASTEXITCODE"
  }
}
finally {
  Pop-Location
}

if (-not (Test-Path $distDir)) {
  throw "React dist directory not found: $distDir"
}

if (Test-Path $templateDir) {
  Remove-Item -Path $templateDir -Recurse -Force
}
New-Item -ItemType Directory -Force -Path $templateDir | Out-Null
Copy-Item -Path (Join-Path $distDir "*") -Destination $templateDir -Recurse -Force

$required = @(
  "index.html",
  "assets\app.js",
  "assets\app.css"
)
foreach ($relativePath in $required) {
  $fullPath = Join-Path $templateDir $relativePath
  if (-not (Test-Path $fullPath)) {
    throw "Required React template artifact missing: $fullPath"
  }
}

# Vite code-splits into a variable, build-dependent set of chunk files (e.g. AuthoringApp.js,
# ReactWorkbenchApp.js -- lazy-loaded from app.js, not referenced by index.html at all). The
# generator's RuntimeApiEmitter.emitOptionalReactUiAssets() used to copy a hardcoded 3-file list
# into every generated app's static resources, which silently dropped every chunk beyond
# index.html/app.js/app.css the moment this build started splitting -- a real app would 404 the
# instant a user opened the authoring or workbench surface. This manifest is the fix: every file
# actually in $templateDir, so the emitter can copy the real set instead of a stale guess.
$manifestEntries = Get-ChildItem -Path $templateDir -Recurse -File |
  ForEach-Object { ($_.FullName.Substring($templateDir.Length + 1)) -replace '\\', '/' } |
  Sort-Object

# Built by hand rather than `ConvertTo-Json` on the array directly: that cmdlet collapses a
# single-element array to a bare JSON string unless `-AsArray` is available, which Windows
# PowerShell 5.1 (as opposed to pwsh 7+) does not have -- this stays correct on either.
$manifestJson = "[`n" + (($manifestEntries | ForEach-Object { "  " + ($_ | ConvertTo-Json) }) -join ",`n") + "`n]`n"
Set-Content -Path $manifestPath -Value $manifestJson -Encoding utf8NoBOM -NoNewline

Write-Host "[react-templates] Export complete: $templateDir"
Write-Host "[react-templates] Wrote asset manifest ($($manifestEntries.Count) file(s)): $manifestPath"

if (-not $KeepGenerated) {
  Write-Host "[react-templates] Cleaning transient npm outputs"
  Remove-DirectoryWithRetry -Path $nodeModulesDir
  Remove-DirectoryWithRetry -Path $distDir
}
