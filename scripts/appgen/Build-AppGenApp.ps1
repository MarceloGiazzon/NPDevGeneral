<#
.SYNOPSIS
  Canonical entry point to generate an AppGen FinalApp from its definition.

.DESCRIPTION
  Source-of-truth layering:
    - NPDev_General (this folder) owns the builder *scripts* (code).
    - AppGen\apps\<App>\ owns the app *definition* only (model, config, db, capabilities,
      input, web companion).
    - D:\WorkSpace\NPDev\Build\generated-finalapps\<app> is ephemeral generated output.

  This dispatcher resolves the app to its definition folder and invokes the right builder:
    - Claude   -> Build-ClaudeApp.ps1 (richer showcase builder)
    - others   -> Build-NpdevApp.ps1  (generic builder; reads config/db.definition)

  -App accepts EITHER a short app name (resolved under -AppsRoot, searched up to one
  level deep so it finds apps nested under a grouping folder like '_official') OR a
  full path to the app folder (the folder that contains a 'definition' subfolder).

.EXAMPLE
  .\Build-AppGenApp.ps1 -App Pigmentampa
  .\Build-AppGenApp.ps1 -App 'D:\WorkSpace\NPDev\AppGen\apps\_official\Pigmentampa'
  .\Build-AppGenApp.ps1 -App Claude -GenerateOnly
#>
[CmdletBinding()]
param(
  [Parameter(Mandatory = $true)]
  [string]$App,
  [string]$AppsRoot = 'D:\WorkSpace\NPDev\AppGen\apps',
  [switch]$GenerateOnly,
  [switch]$SkipRuntimeHostLibs
)

$ErrorActionPreference = 'Stop'

# -App may be a full path to the app folder, or a short name resolved under -AppsRoot
# (including one level deeper, e.g. AppsRoot\_official\<App>).
if (Test-Path -LiteralPath (Join-Path $App 'definition')) {
  $appFolder = (Resolve-Path -LiteralPath $App).Path
} else {
  $direct = Join-Path $AppsRoot $App
  if (Test-Path -LiteralPath (Join-Path $direct 'definition')) {
    $appFolder = $direct
  } else {
    $nested = Get-ChildItem -LiteralPath $AppsRoot -Directory -Recurse -Depth 1 -ErrorAction SilentlyContinue |
              Where-Object { $_.Name -ieq $App -and (Test-Path -LiteralPath (Join-Path $_.FullName 'definition')) } |
              Select-Object -First 1
    $appFolder = if ($nested) { $nested.FullName } else { $direct }
  }
}
if (-not (Test-Path -LiteralPath (Join-Path $appFolder 'definition'))) {
  $available = @(Get-ChildItem -LiteralPath $AppsRoot -Directory -Recurse -Depth 1 -ErrorAction SilentlyContinue |
                 Where-Object { Test-Path -LiteralPath (Join-Path $_.FullName 'definition') } |
                 Select-Object -ExpandProperty Name)
  throw "App definition not found under '$App' (tried '$appFolder\definition'). Pass a full app-folder path or one of: $($available -join ', ')"
}

$appName = Split-Path -Leaf $appFolder
$here = $PSScriptRoot
$claudeBuilder  = Join-Path $here 'Build-ClaudeApp.ps1'
$genericBuilder = Join-Path $here 'Build-NpdevApp.ps1'

if ($appName -ieq 'Claude' -and (Test-Path -LiteralPath $claudeBuilder)) {
  & $claudeBuilder -AppFolder $appFolder -GenerateOnly:$GenerateOnly -SkipRuntimeHostLibs:$SkipRuntimeHostLibs
} else {
  & $genericBuilder -AppFolder $appFolder -GenerateOnly:$GenerateOnly -SkipRuntimeHostLibs:$SkipRuntimeHostLibs
}
exit $LASTEXITCODE
