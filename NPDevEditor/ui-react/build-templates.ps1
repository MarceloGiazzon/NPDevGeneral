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
$distDir = Join-Path $uiRoot "dist"
$nodeModulesDir = Join-Path $uiRoot "node_modules"
$templateDir = Join-Path $gptRoot "generator\src\main\resources\npdev-templates\static-react"

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

Write-Host "[react-templates] Export complete: $templateDir"

if (-not $KeepGenerated) {
  Write-Host "[react-templates] Cleaning transient npm outputs"
  Remove-DirectoryWithRetry -Path $nodeModulesDir
  Remove-DirectoryWithRetry -Path $distDir
}
