param(
  [Parameter(Mandatory = $false)]
  [string] $WorkspaceRoot = ''
)

$ErrorActionPreference = 'Stop'

# Portable default (REG-144): this used to hardcode the author's own D:\WorkSpace\NPDev\NPDev_General,
# so on anyone else's machine the default silently pointed at a path that does not exist. This script
# lives at <repo>/scripts/quality/, so the repo root is exactly two levels up -- correct under any
# clone name and any drive. Passing -WorkspaceRoot still wins.
if ([string]::IsNullOrWhiteSpace($WorkspaceRoot)) {
  $WorkspaceRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
}

$failures = New-Object System.Collections.Generic.List[string]
$warnings = New-Object System.Collections.Generic.List[string]

function Add-Failure {
  param([string] $Message)
  $script:failures.Add($Message)
}

function Add-Warning {
  param([string] $Message)
  $script:warnings.Add($Message)
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

function Test-AllowedPath {
  param([string] $RelativePath)

  $normalized = $RelativePath -replace '/', '\'
  $allowedFragments = @(
    '\Build\',
    '\build\',
    '\.gradle\',
    '\out\',
    '\reports\',
    '\test-results\',
    '\playwright-report\',
    '\node_modules\',
    '\dist\',
    '\src\test\',
    '\docs\'
  )

  foreach ($fragment in $allowedFragments) {
    if ($normalized.IndexOf($fragment, [System.StringComparison]::OrdinalIgnoreCase) -ge 0) {
      return $true
    }
  }

  return $false
}

$WorkspaceRoot = [System.IO.Path]::GetFullPath($WorkspaceRoot)

if (-not (Test-Path -LiteralPath $WorkspaceRoot)) {
  throw "WorkspaceRoot not found: $WorkspaceRoot"
}

$dbschemaDir = Join-Path $WorkspaceRoot 'NPDevKernel\kernel\src\main\java\com\npdev\kernel\dbschema'
$registry = Join-Path $dbschemaDir 'NpdevInternalTables.java'
$emitter = Join-Path $WorkspaceRoot 'NPDevGenerator\generator\src\main\java\com\npdev\generator\dbconfig\SchemaRealizationEmitter.java'
$oldGeneratorMigrationPackage = Join-Path $WorkspaceRoot 'NPDevGenerator\generator\src\main\java\com\npdev\generator\migration'

if (-not (Test-Path -LiteralPath $dbschemaDir)) {
  Add-Failure "Kernel dbschema directory not found: $dbschemaDir"
}

if (-not (Test-Path -LiteralPath $registry)) {
  Add-Failure "NpdevInternalTables.java not found: $registry"
}

$expectedClasses = @(
  'InternalColumnType.java',
  'InternalSchemaValidator.java',
  'NpdevAuditLogTable.java',
  'NpdevCircuitBreakerTable.java',
  'NpdevCorrelationOwnerTable.java',
  'NpdevEventStoreTable.java',
  'NpdevFlowInstanceTable.java',
  'NpdevIdempotencyTable.java',
  'NpdevPublicationAuditTable.java',
  'NpdevPublicationExecutionTable.java',
  'NpdevScheduledEventTable.java',
  'NpdevSchemaMetadataTable.java',
  'NpdevTraceTable.java',
  'NpdevInternalTables.java'
)

foreach ($className in $expectedClasses) {
  $classPath = Join-Path $dbschemaDir $className
  if (-not (Test-Path -LiteralPath $classPath)) {
    Add-Failure "Expected dbschema class missing: $className"
  }
}

if (-not (Test-Path -LiteralPath $emitter)) {
  Add-Failure "SchemaRealizationEmitter.java not found: $emitter"
}

if (Test-Path -LiteralPath $oldGeneratorMigrationPackage) {
  Add-Failure "Old active generator migration/model-diff package returned: $(Get-RelativePath -Root $WorkspaceRoot -Path $oldGeneratorMigrationPackage)"
}

if (Test-Path -LiteralPath $emitter) {
  $emitterText = Get-Content -LiteralPath $emitter -Raw
  if ($emitterText -notmatch '\bNpdevInternalTables\b') {
    Add-Failure 'SchemaRealizationEmitter.java does not reference NpdevInternalTables.'
  }
}

$migrationRoots = @(
  (Join-Path $WorkspaceRoot 'NPDevGenerator\db-history\src\main\resources\db\migration'),
  (Join-Path $WorkspaceRoot 'NPDevRuntimeHost\src\main\resources\db\migration')
)

foreach ($migrationRoot in $migrationRoots) {
  if (Test-Path -LiteralPath $migrationRoot) {
    $legacyMigrations = Get-ChildItem -LiteralPath $migrationRoot -Recurse -File -Filter 'V50*.sql' |
      Where-Object { $_.Name -match '^V50\d+__.*npdev.*\.sql$' -or $_.Name -match '^V50(0[1-9]|1[0-4])__.*\.sql$' }
    foreach ($migration in $legacyMigrations) {
      Add-Failure "Old V5001..V5014 internal migration authority found: $(Get-RelativePath -Root $WorkspaceRoot -Path $migration.FullName)"
    }
  }
}

$forbiddenCreatePatterns = @(
  'CREATE\s+TABLE\s+(IF\s+NOT\s+EXISTS\s+)?(?:PUBLIC\.)?npdev_',
  'CREATE\s+TABLE\s+(IF\s+NOT\s+EXISTS\s+)?(?:PUBLIC\.)?NPDev_'
)

$sourceRoots = @('NPDevGenerator', 'NPDevRuntimeHost', 'NPDevKernel') |
  ForEach-Object { Join-Path $WorkspaceRoot $_ } |
  Where-Object { Test-Path -LiteralPath $_ }

foreach ($sourceRoot in $sourceRoots) {
  $files = Get-ChildItem -LiteralPath $sourceRoot -Recurse -File -Include '*.sql', '*.java', '*.kt', '*.groovy', '*.mustache', '*.properties', '*.yml', '*.yaml', '*.json', '*.xml' -ErrorAction SilentlyContinue
  foreach ($file in $files) {
    $relative = Get-RelativePath -Root $WorkspaceRoot -Path $file.FullName
    if (Test-AllowedPath -RelativePath $relative) {
      continue
    }

    $text = Get-Content -LiteralPath $file.FullName -Raw
    foreach ($pattern in $forbiddenCreatePatterns) {
      if ($text -match $pattern) {
        Add-Failure "Hand-authored CREATE TABLE npdev_* authority found: $relative"
        break
      }
    }
  }
}

$futureScopeTables = @(
  'npdev_tenant',
  'npdev_tenant_alias',
  'npdev_tenant_app_entitlement',
  'npdev_tenant_coda_entitlement',
  'npdev_tenant_capability_entitlement',
  'npdev_tenant_actor_membership',
  'npdev_tenant_provider_binding',
  'npdev_tenant_data_binding',
  'npdev_tenant_policy_decision',
  'npdev_coda_definition',
  'npdev_coda_execution',
  'npdev_capability_binding',
  'npdev_capability_execution',
  'npdev_flow_definition',
  'npdev_flow_step_definition',
  'npdev_flow_step_execution',
  'npdev_orchestration_definition',
  'npdev_orchestration_action_definition',
  'npdev_orchestration_execution',
  'npdev_orchestration_lock'
)

if (Test-Path -LiteralPath $dbschemaDir) {
  $dbschemaFiles = Get-ChildItem -LiteralPath $dbschemaDir -Recurse -File -Include '*.java'
  foreach ($file in $dbschemaFiles) {
    $text = Get-Content -LiteralPath $file.FullName -Raw
    foreach ($tableName in $futureScopeTables) {
      if ($text -match [regex]::Escape($tableName)) {
        Add-Failure "Future-scope table name found in Kernel dbschema: $tableName in $(Get-RelativePath -Root $WorkspaceRoot -Path $file.FullName)"
      }
    }
  }
}

$outputDirectoryNames = @('.gradle', 'build', 'node_modules', 'dist', 'test-results', 'playwright-report')
$outputDirectories = Get-ChildItem -LiteralPath $WorkspaceRoot -Recurse -Directory -Force -ErrorAction SilentlyContinue |
  Where-Object {
    $_.FullName -notmatch '\\.git(\\|$)' -and
    $outputDirectoryNames -contains $_.Name
  }

foreach ($directory in $outputDirectories) {
  Add-Warning "Generated/cache directory under source tree: $(Get-RelativePath -Root $WorkspaceRoot -Path $directory.FullName)"
}

if ($warnings.Count -gt 0) {
  Write-Host 'WARNINGS:'
  foreach ($warning in $warnings) {
    Write-Host " - $warning"
  }
}

if ($failures.Count -gt 0) {
  Write-Host 'FAILURES:'
  foreach ($failure in $failures) {
    Write-Host " - $failure"
  }
  throw "Internal DB schema source-of-truth check FAILED with $($failures.Count) failure(s)."
}

Write-Host 'PASS: Internal DB schema source-of-truth check passed.'
