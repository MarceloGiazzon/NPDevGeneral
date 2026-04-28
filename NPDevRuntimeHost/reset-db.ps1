param(
  # Docker container name or id
  [string]$Container = "finalexec-postgres",

  # DB settings inside the container
  [string]$DbName = "finalexec",
  [string]$DbUser = "finalexec",
  [string]$DbPassword = "finalexec",

  # Schema to reset (public is default)
  [string]$Schema = "public"
)

function Require-Command([string]$cmd) {
  if (-not (Get-Command $cmd -ErrorAction SilentlyContinue)) {
    throw "Required command '$cmd' not found. Install it or add it to PATH."
  }
}

Require-Command "docker"

Write-Host "============================================="
Write-Host " Resetting Postgres schema via Docker"
Write-Host " Container: $Container"
Write-Host " DB:        $DbName"
Write-Host " User:      $DbUser"
Write-Host " Schema:    $Schema"
Write-Host "============================================="

# Verify container exists and is running
$running = docker ps --format "{{.Names}}" | Select-String -SimpleMatch $Container
if (-not $running) {
  Write-Host "`nRunning containers:"
  docker ps --format " - {{.Names}}  ({{.Image}})"
  throw "Container '$Container' not found running. Set -Container to the correct name."
}

# SQL: drop and recreate schema + privileges
$sql = @"
DROP SCHEMA IF EXISTS $Schema CASCADE;
CREATE SCHEMA $Schema;
GRANT ALL ON SCHEMA $Schema TO $DbUser;
GRANT ALL ON SCHEMA $Schema TO public;
"@

Write-Host "`nDropping and recreating schema '$Schema'..."

# Run psql in the container and pass password via env var
docker exec -i `
  -e PGPASSWORD="$DbPassword" `
  $Container `
  psql -U $DbUser -d $DbName -v ON_ERROR_STOP=1 -c "$sql"

if ($LASTEXITCODE -ne 0) { throw "Reset failed (psql returned non-zero exit)." }

Write-Host "`nVerifying schema is empty..."
docker exec -i `
  -e PGPASSWORD="$DbPassword" `
  $Container `
  psql -U $DbUser -d $DbName -c "\dt"

if ($LASTEXITCODE -ne 0) { throw "Verification failed." }

Write-Host "`n============================================="
Write-Host " Database reset complete."
Write-Host " Next step: run regen-copy-run.ps1"
Write-Host "============================================="