param(
  [Parameter(Mandatory = $false)]
  [string] $WorkspaceRoot = ''
)

$ErrorActionPreference = 'Stop'

# Portable default (REG-144) -- see run-internal-db-schema-source-of-truth-check.ps1's note. This
# script lives at <repo>/scripts/quality/, so the repo root is two levels up.
if ([string]::IsNullOrWhiteSpace($WorkspaceRoot)) {
  $WorkspaceRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
}

$failures = New-Object System.Collections.Generic.List[string]

function Add-Failure {
  param([string] $Message)
  $script:failures.Add($Message)
}

function Get-RelativePath {
  param(
    [Parameter(Mandatory = $true)]
    [string] $Root,
    [Parameter(Mandatory = $true)]
    [string] $Path
  )

  $rootPath = [System.IO.Path]::GetFullPath($Root).TrimEnd('\', '/')
  $fullPath = [System.IO.Path]::GetFullPath($Path)
  if ($fullPath.StartsWith($rootPath, [System.StringComparison]::OrdinalIgnoreCase)) {
    return $fullPath.Substring($rootPath.Length).TrimStart('\', '/')
  }

  return $fullPath
}

$WorkspaceRoot = [System.IO.Path]::GetFullPath($WorkspaceRoot)

if (-not (Test-Path -LiteralPath $WorkspaceRoot)) {
  throw "WorkspaceRoot not found: $WorkspaceRoot"
}

# BT-1: both files are app-independent (no com.npdev.generated. reference) and now live under
# runtimehost-core, RuntimeHost's app-independent module (scripts/proofs/classify_runtimehost_sources.py).
$runtimeFiles = @(
  'NPDevRuntimeHost\runtimehost-core\src\main\java\com\finalexec\npdev\service\internal\RealPublicationExecutorService.java',
  'NPDevRuntimeHost\runtimehost-core\src\main\java\com\finalexec\npdev\service\internal\PublicationStateStore.java'
)

$forbiddenPatterns = @(
  '\?::jsonb',
  'jsonb_set',
  'ON\s+CONFLICT',
  'CAST\s*\(\s*\?\s+AS\s+uuid\s*\)',
  'NOW\s*\(\s*\)',
  '\bJSONB\b',
  'TIMESTAMP\s+WITH\s+TIME\s+ZONE'
)

foreach ($relativePath in $runtimeFiles) {
  $path = Join-Path $WorkspaceRoot $relativePath
  if (-not (Test-Path -LiteralPath $path)) {
    Add-Failure "Publication runtime source file not found: $relativePath"
    continue
  }

  $text = Get-Content -LiteralPath $path -Raw
  foreach ($pattern in $forbiddenPatterns) {
    if ($text -match $pattern) {
      Add-Failure "Forbidden Postgres-only publication SQL token '$pattern' found in $(Get-RelativePath -Root $WorkspaceRoot -Path $path)"
    }
  }
}

if ($failures.Count -gt 0) {
  Write-Host 'FAILURES:'
  foreach ($failure in $failures) {
    Write-Host " - $failure"
  }
  throw "Publication runtime SQL neutrality check FAILED with $($failures.Count) failure(s)."
}

Write-Host 'PASS: Publication runtime SQL neutrality check passed.'
