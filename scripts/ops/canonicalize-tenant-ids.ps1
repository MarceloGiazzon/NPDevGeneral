#requires -Version 5.1
<#
.SYNOPSIS
  REG-25 one-time migration tool: canonicalize (lowercase) stored tenant_id across the tenant
  registry AND every business table's tenant_id column, so pre-REG-25 mixed-case data converges to
  the same isolation bucket the runtime now writes.

.DESCRIPTION
  As of REG-25 the runtime canonicalizes tenant_id to lowercase at a single choke point
  (com.npdev.kernel.ExecutionContext), so ALL NEW writes are already lowercase. This tool is for
  EXISTING data written before that fix. It is NOT run automatically and never runs on boot -- an
  operator invokes it deliberately, once, per deployment, during a maintenance window.

  Safety model:
    * -DryRun (DEFAULT): reports, per tenant_id-bearing table, how many rows would change and lists
      any COLLISION buckets. Writes nothing.
    * -Apply: lowercases tenant_id. A table with collision buckets is SKIPPED (not merged) and
      reported, UNLESS -Force is given -- because merging two casings into one bucket can violate a
      primary key or silently join two tenants' data, which an operator must resolve deliberately.

  Collision detection is primary-key-agnostic: any LOWER(tenant_id) bucket that more than one
  distinct casing maps to (e.g. rows under both "Acme" and "acme") is a collision. That is exactly
  the set of buckets a blind lowercase would merge, so it is the honest signal regardless of each
  table's PK shape.

  Databases: H2 (via the bundled h2-*.jar org.h2.tools.Shell) and PostgreSQL (via psql on PATH) are
  auto-detected from the JDBC URL scheme.

.PARAMETER JdbcUrl
  The app's JDBC URL, e.g. jdbc:h2:tcp://localhost:9092/npdevdb or jdbc:postgresql://host:5432/db.

.PARAMETER User
  DB user (default: sa for H2).

.PARAMETER Password
  DB password (default: empty).

.PARAMETER Apply
  Perform the lowercase UPDATEs. Without this the tool is dry-run only.

.PARAMETER Force
  With -Apply, also lowercase tables that have collision buckets (DANGEROUS: may merge tenants /
  violate PKs). Off by default.

.PARAMETER H2JarPath
  Explicit path to h2-*.jar. Auto-discovered under D:\WorkSpace\NPDev\Build and ~/.gradle if omitted.

.EXAMPLE
  pwsh -File scripts/ops/canonicalize-tenant-ids.ps1 -JdbcUrl 'jdbc:h2:tcp://localhost:9092/npdevdb'
  # dry-run report

.EXAMPLE
  pwsh -File scripts/ops/canonicalize-tenant-ids.ps1 -JdbcUrl 'jdbc:h2:tcp://localhost:9092/npdevdb' -Apply
  # apply, skipping any collision tables (which it lists for manual resolution)
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)] [string] $JdbcUrl,
    [string] $User = 'sa',
    [string] $Password = '',
    [switch] $Apply,
    [switch] $Force,
    [string] $H2JarPath
)

$ErrorActionPreference = 'Stop'

function Resolve-H2Jar {
    param([string] $Explicit)
    if ($Explicit) {
        if (-not (Test-Path $Explicit)) { throw "H2 jar not found at -H2JarPath '$Explicit'." }
        return (Resolve-Path $Explicit).Path
    }
    $roots = @('D:\WorkSpace\NPDev\Build', (Join-Path $env:USERPROFILE '.gradle\caches'))
    $jar = Get-ChildItem -Path $roots -Recurse -Filter 'h2-2*.jar' -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -notmatch 'sources|javadoc' } |
        Sort-Object LastWriteTime -Descending | Select-Object -First 1
    if (-not $jar) {
        throw "No standalone h2-2*.jar found under Build or ~/.gradle. Build an app once to populate the gradle cache, or pass -H2JarPath."
    }
    return $jar.FullName
}

# --- DB access abstraction: returns raw stdout text of a single SQL statement -----------------
$script:Engine = if ($JdbcUrl -like 'jdbc:postgresql:*') { 'postgres' }
                 elseif ($JdbcUrl -like 'jdbc:h2:*') { 'h2' }
                 else { throw "Unsupported JDBC URL scheme (need jdbc:h2:* or jdbc:postgresql:*): $JdbcUrl" }

if ($script:Engine -eq 'h2') { $script:H2Jar = Resolve-H2Jar -Explicit $H2JarPath }

function Invoke-Sql {
    param([string] $Sql)
    if ($script:Engine -eq 'h2') {
        $out = & java -cp $script:H2Jar org.h2.tools.Shell -url $JdbcUrl -user $User -password $Password -sql $Sql 2>&1
        $text = ($out | Out-String)
        if ($text -match '(?im)^\s*(Error|Exception|SQLException)') { throw "H2 SQL failed for [$Sql]:`n$text" }
        return $text
    } else {
        $pgpass = $env:PGPASSWORD
        try {
            $env:PGPASSWORD = $Password
            # jdbc:postgresql://host:port/db -> psql args
            if ($JdbcUrl -notmatch '^jdbc:postgresql://([^:/]+)(?::(\d+))?/([^?;]+)') {
                throw "Cannot parse Postgres JDBC URL: $JdbcUrl"
            }
            $h = $Matches[1]; $p = if ($Matches[2]) { $Matches[2] } else { '5432' }; $db = $Matches[3]
            $out = & psql -h $h -p $p -U $User -d $db -At -c $Sql 2>&1
            $text = ($out | Out-String)
            if ($LASTEXITCODE -ne 0) { throw "psql failed for [$Sql]:`n$text" }
            return $text
        } finally { $env:PGPASSWORD = $pgpass }
    }
}

function Get-Scalar {
    param([string] $Sql)
    $text = Invoke-Sql -Sql $Sql
    # both H2 Shell and psql -At print the value; take the first numeric-ish line
    foreach ($line in ($text -split "`r?`n")) {
        $t = $line.Trim()
        if ($t -match '^\d+$') { return [int]$t }
    }
    return 0
}

Write-Host "REG-25 tenant_id canonicalizer" -ForegroundColor Cyan
Write-Host "  engine : $script:Engine"
Write-Host "  url    : $JdbcUrl"
Write-Host "  mode   : $([string]($Apply ? 'APPLY' : 'DRY-RUN')) $([string]($Force ? '(FORCE)' : ''))"
Write-Host ""

# --- discover every table that has a tenant_id column -----------------------------------------
$discoverSql = @"
SELECT table_name FROM information_schema.columns
WHERE LOWER(column_name) = 'tenant_id'
  AND table_schema IN ('PUBLIC','public')
ORDER BY table_name
"@
$discovered = Invoke-Sql -Sql $discoverSql
$tables = @()
foreach ($line in ($discovered -split "`r?`n")) {
    $t = $line.Trim().Trim('|').Trim()
    if ($t -and $t -notmatch '(?i)^(TABLE_NAME|-+|\(\d+ rows?\)|rows?:)$' -and $t -match '^[A-Za-z0-9_]+$') {
        $tables += $t
    }
}
$tables = $tables | Select-Object -Unique
if (-not $tables) { Write-Host "No tables with a tenant_id column found. Nothing to do."; exit 0 }

Write-Host "Tables with a tenant_id column: $($tables.Count)" -ForegroundColor Cyan

$totalToChange = 0
$collisionTables = @()
$appliedTables = @()

foreach ($tbl in $tables) {
    $needing = Get-Scalar "SELECT COUNT(*) FROM $tbl WHERE tenant_id IS NOT NULL AND tenant_id <> LOWER(tenant_id)"
    # collision buckets: one LOWER() value that >1 distinct casing maps to
    $collisions = Get-Scalar @"
SELECT COUNT(*) FROM (
  SELECT LOWER(tenant_id) AS b FROM $tbl WHERE tenant_id IS NOT NULL
  GROUP BY LOWER(tenant_id) HAVING COUNT(DISTINCT tenant_id) > 1
) c
"@
    $totalToChange += $needing
    $flag = if ($collisions -gt 0) { " COLLISIONS=$collisions" } else { "" }
    $color = if ($collisions -gt 0) { 'Yellow' } else { 'Gray' }
    Write-Host ("  {0,-40} rows-to-lowercase={1}{2}" -f $tbl, $needing, $flag) -ForegroundColor $color

    if ($collisions -gt 0) {
        $collisionTables += $tbl
        # show the offending buckets so the operator can resolve them
        $detail = Invoke-Sql @"
SELECT LOWER(tenant_id) AS bucket, COUNT(DISTINCT tenant_id) AS casings
FROM $tbl WHERE tenant_id IS NOT NULL
GROUP BY LOWER(tenant_id) HAVING COUNT(DISTINCT tenant_id) > 1
"@
        Write-Host ("      collision buckets in {0}:" -f $tbl) -ForegroundColor Yellow
        foreach ($l in ($detail -split "`r?`n")) { if ($l.Trim()) { Write-Host "        $($l.Trim())" -ForegroundColor Yellow } }
    }

    if ($Apply -and $needing -gt 0) {
        if ($collisions -gt 0 -and -not $Force) {
            Write-Host ("      SKIPPED (collision; resolve manually or re-run with -Force): {0}" -f $tbl) -ForegroundColor Red
            continue
        }
        Invoke-Sql "UPDATE $tbl SET tenant_id = LOWER(tenant_id) WHERE tenant_id IS NOT NULL AND tenant_id <> LOWER(tenant_id)" | Out-Null
        $appliedTables += $tbl
        Write-Host ("      APPLIED: lowercased {0} row(s) in {1}" -f $needing, $tbl) -ForegroundColor Green
    }
}

Write-Host ""
Write-Host "Summary:" -ForegroundColor Cyan
Write-Host "  rows needing lowercase (all tables): $totalToChange"
Write-Host "  tables with collision buckets      : $($collisionTables.Count) $([string]($collisionTables -join ', '))"
if ($Apply) {
    Write-Host "  tables updated                     : $($appliedTables.Count) $([string]($appliedTables -join ', '))" -ForegroundColor Green
    if ($collisionTables.Count -gt 0 -and -not $Force) {
        Write-Host "  NOTE: collision tables were NOT changed. Resolve the buckets above, then re-run." -ForegroundColor Yellow
        exit 2
    }
} else {
    Write-Host "  (dry-run: nothing was written. Re-run with -Apply to perform the migration.)" -ForegroundColor Yellow
}
exit 0
