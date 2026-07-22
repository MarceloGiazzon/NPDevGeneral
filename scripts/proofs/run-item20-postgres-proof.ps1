param(
  [string]$WorkspaceRoot = 'D:\WorkSpace\NPDev\NPDev_General',
  [string]$EvidenceDir,
  [string]$DockerImage = 'postgres:16-alpine',
  [string]$ContainerName = '',
  [string]$PostgresUser = 'npdev',
  [string]$PostgresPassword = 'npdev',
  [string]$PostgresDatabase = 'npdev_item20',
  [switch]$KeepContainer,
  [switch]$StartDockerDesktop
)

$ErrorActionPreference = 'Stop'

if (-not $EvidenceDir) {
  $EvidenceDir = Join-Path $WorkspaceRoot '__item20_postgres_proof_evidence'
}

New-Item -ItemType Directory -Force -Path $EvidenceDir | Out-Null
$log = Join-Path $EvidenceDir 'item20-postgres-proof-output.txt'

function Log {
  param([string]$Text)
  Write-Host $Text
  Add-Content -Path $log -Value $Text -Encoding UTF8
}

function Invoke-NativeLogged {
  param(
    [string]$Title,
    [string]$FilePath,
    [string[]]$Arguments
  )

  Log ""
  Log ("===== " + $Title + " =====")
  $output = & $FilePath @Arguments 2>&1
  $exitCode = $LASTEXITCODE

  foreach ($line in $output) {
    $text = [string]$line
    Write-Host $text
    Add-Content -Path $log -Value $text -Encoding UTF8
  }

  Log ("EXITCODE: " + $exitCode)

  if ($exitCode -ne 0) {
    throw "Native command failed for $Title with exit code $exitCode."
  }

  return $output
}

function Invoke-NativeAllowFailureLogged {
  param(
    [string]$Title,
    [string]$FilePath,
    [string[]]$Arguments
  )

  Log ""
  Log ("===== " + $Title + " =====")
  $output = & $FilePath @Arguments 2>&1
  $exitCode = $LASTEXITCODE

  foreach ($line in $output) {
    $text = [string]$line
    Write-Host $text
    Add-Content -Path $log -Value $text -Encoding UTF8
  }

  Log ("EXITCODE: " + $exitCode)
  return @{
    ExitCode = $exitCode
    Output = $output
  }
}

function Start-DockerDesktopIfRequested {
  if (-not $StartDockerDesktop) {
    return
  }

  $candidates = @(
    'C:\Program Files\Docker\Docker\Docker Desktop.exe',
    (Join-Path $env:LOCALAPPDATA 'Programs\Docker\Docker\Docker Desktop.exe')
  )

  $dockerDesktop = $null
  foreach ($candidate in $candidates) {
    if (Test-Path $candidate) {
      $dockerDesktop = $candidate
      break
    }
  }

  if (-not $dockerDesktop) {
    throw 'StartDockerDesktop was requested, but Docker Desktop executable was not found in the common install paths.'
  }

  Log ("START: Docker Desktop: " + $dockerDesktop)
  Start-Process -FilePath $dockerDesktop | Out-Null

  for ($i = 0; $i -lt 120; $i++) {
    $probe = Invoke-NativeAllowFailureLogged -Title 'docker readiness probe' -FilePath 'docker' -Arguments @('version')
    if ($probe.ExitCode -eq 0) {
      Log 'OK: Docker daemon is available.'
      return
    }
    Start-Sleep -Seconds 2
  }

  throw 'Docker Desktop was started, but Docker daemon did not become available during the readiness window.'
}

if (-not $ContainerName) {
  $ContainerName = 'npdev-item20-postgres-proof-' + (Get-Date -Format 'yyyyMMddHHmmss')
}

"Item 20 Postgres proof started: $(Get-Date -Format o)" | Set-Content -Path $log -Encoding UTF8
Log ("WorkspaceRoot=" + $WorkspaceRoot)
Log ("EvidenceDir=" + $EvidenceDir)
Log ("DockerImage=" + $DockerImage)
Log ("ContainerName=" + $ContainerName)
Log ("PostgresDatabase=" + $PostgresDatabase)
Log ("StartDockerDesktop=" + $StartDockerDesktop)

Start-DockerDesktopIfRequested

Invoke-NativeLogged -Title 'docker version preflight' -FilePath 'docker' -Arguments @('version') | Out-Null
Invoke-NativeLogged -Title 'docker info preflight' -FilePath 'docker' -Arguments @('info') | Out-Null

Invoke-NativeAllowFailureLogged -Title 'remove old proof container if present' -FilePath 'docker' -Arguments @('rm', '-f', $ContainerName) | Out-Null

Invoke-NativeLogged -Title 'pull Postgres image if needed' -FilePath 'docker' -Arguments @('pull', $DockerImage) | Out-Null

Invoke-NativeLogged -Title 'start temporary Postgres container' -FilePath 'docker' -Arguments @(
  'run',
  '-d',
  '--name',
  $ContainerName,
  '-e',
  ('POSTGRES_USER=' + $PostgresUser),
  '-e',
  ('POSTGRES_PASSWORD=' + $PostgresPassword),
  '-e',
  ('POSTGRES_DB=' + $PostgresDatabase),
  $DockerImage
) | Out-Null

try {
  $ready = $false
  for ($i = 0; $i -lt 90; $i++) {
    $probe = Invoke-NativeAllowFailureLogged -Title 'postgres readiness probe' -FilePath 'docker' -Arguments @(
      'exec',
      $ContainerName,
      'pg_isready',
      '-U',
      $PostgresUser,
      '-d',
      $PostgresDatabase
    )

    $outputText = [string]::Join([Environment]::NewLine, [string[]]$probe.Output)
    if ($probe.ExitCode -eq 0 -and $outputText -like '*accepting connections*') {
      $ready = $true
      break
    }

    Start-Sleep -Seconds 1
  }

  if (-not $ready) {
    throw 'Postgres container did not become ready.'
  }

  $sqlPath = Join-Path $EvidenceDir 'item20-postgres-proof.sql'
  $sql = @'
\set ON_ERROR_STOP on

BEGIN;

CREATE TABLE npdev_flow_instance (
  flow_instance_id text PRIMARY KEY,
  flow_name text NOT NULL,
  correlation_id text NOT NULL,
  execution_id text NOT NULL,
  status text NOT NULL,
  version bigint NOT NULL DEFAULT 0,
  state jsonb NOT NULL DEFAULT '{}'::jsonb,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX ux_npdev_flow_instance_correlation
  ON npdev_flow_instance(correlation_id);

CREATE TABLE npdev_correlation_owner (
  correlation_id text PRIMARY KEY,
  owner_type text NOT NULL,
  owner_id text NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE npdev_event_store (
  event_id text PRIMARY KEY,
  flow_instance_id text NOT NULL REFERENCES npdev_flow_instance(flow_instance_id),
  event_type text NOT NULL,
  event_status text NOT NULL,
  payload jsonb NOT NULL DEFAULT '{}'::jsonb,
  sequence_no bigint NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX ix_npdev_event_store_flow_sequence
  ON npdev_event_store(flow_instance_id, sequence_no);

CREATE TABLE npdev_idempotency_record (
  idempotency_key text PRIMARY KEY,
  response jsonb NOT NULL DEFAULT '{}'::jsonb,
  created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE npdev_audit_log (
  audit_id text PRIMARY KEY,
  subject_id text NOT NULL,
  action_name text NOT NULL,
  details jsonb NOT NULL DEFAULT '{}'::jsonb,
  created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE npdev_trace_record (
  trace_id text PRIMARY KEY,
  correlation_id text NOT NULL,
  span_name text NOT NULL,
  details jsonb NOT NULL DEFAULT '{}'::jsonb,
  created_at timestamptz NOT NULL DEFAULT now()
);

INSERT INTO npdev_flow_instance(
  flow_instance_id,
  flow_name,
  correlation_id,
  execution_id,
  status,
  state
) VALUES (
  'flow-inst-item20-1',
  'CreateItem12UserFlow',
  'corr-item20-1',
  'exec-item20-1',
  'COMPLETED',
  '{"item":"item20","proof":"postgres-jsonb-flow-state"}'::jsonb
);

INSERT INTO npdev_correlation_owner(
  correlation_id,
  owner_type,
  owner_id
) VALUES (
  'corr-item20-1',
  'flow-instance',
  'flow-inst-item20-1'
);

INSERT INTO npdev_event_store(
  event_id,
  flow_instance_id,
  event_type,
  event_status,
  payload,
  sequence_no
) VALUES (
  'evt-item20-1',
  'flow-inst-item20-1',
  'FLOW_STARTED',
  'RECORDED',
  '{"executionId":"exec-item20-1","capability":"postgres-proof"}'::jsonb,
  1
), (
  'evt-item20-2',
  'flow-inst-item20-1',
  'FLOW_COMPLETED',
  'RECORDED',
  '{"executionId":"exec-item20-1","createdCount":1}'::jsonb,
  2
);

INSERT INTO npdev_idempotency_record(
  idempotency_key,
  response
) VALUES (
  'CreateItem12UserFlow::idem-item20-1',
  '{"executionId":"exec-item20-1","flowInstanceId":"flow-inst-item20-1","flowStartIdempotencyStatus":"recorded: postgres proof"}'::jsonb
);

INSERT INTO npdev_idempotency_record(
  idempotency_key,
  response
) VALUES (
  'CreateItem12UserFlow::idem-item20-1',
  '{"executionId":"exec-item20-duplicate","flowInstanceId":"duplicate","flowStartIdempotencyStatus":"should-not-replace"}'::jsonb
)
ON CONFLICT (idempotency_key) DO NOTHING;

INSERT INTO npdev_audit_log(
  audit_id,
  subject_id,
  action_name,
  details
) VALUES (
  'audit-item20-1',
  'exec-item20-1',
  'flow-start',
  '{"status":"recorded","storage":"postgres"}'::jsonb
);

INSERT INTO npdev_trace_record(
  trace_id,
  correlation_id,
  span_name,
  details
) VALUES (
  'trace-item20-1',
  'corr-item20-1',
  'flow-start',
  '{"status":"recorded","storage":"postgres"}'::jsonb
);

UPDATE npdev_flow_instance
SET
  version = version + 1,
  state = jsonb_set(state, '{verified}', 'true'::jsonb),
  updated_at = now()
WHERE flow_instance_id = 'flow-inst-item20-1'
RETURNING flow_instance_id, version, state->>'verified' AS verified_jsonb_update;

SELECT
  flow_instance_id,
  flow_name,
  status,
  state->>'proof' AS state_jsonb_read
FROM npdev_flow_instance;

SELECT
  response->>'executionId' AS idempotency_replay_execution_id,
  response->>'flowInstanceId' AS idempotency_replay_flow_instance_id,
  response->>'flowStartIdempotencyStatus' AS idempotency_status
FROM npdev_idempotency_record
WHERE idempotency_key = 'CreateItem12UserFlow::idem-item20-1';

SELECT count(*) AS flow_instance_rows FROM npdev_flow_instance;
SELECT count(*) AS event_store_rows FROM npdev_event_store;
SELECT count(*) AS idempotency_rows FROM npdev_idempotency_record;
SELECT count(*) AS audit_rows FROM npdev_audit_log;
SELECT count(*) AS trace_rows FROM npdev_trace_record;

SELECT event_id
FROM npdev_event_store
WHERE event_status = 'RECORDED'
ORDER BY sequence_no
FOR UPDATE SKIP LOCKED;

COMMIT;
'@

  Set-Content -Path $sqlPath -Value $sql -Encoding UTF8

  Invoke-NativeLogged -Title 'copy proof sql into container' -FilePath 'docker' -Arguments @(
    'cp',
    $sqlPath,
    ($ContainerName + ':/tmp/item20-postgres-proof.sql')
  ) | Out-Null

  Invoke-NativeLogged -Title 'execute Postgres proof SQL' -FilePath 'docker' -Arguments @(
    'exec',
    $ContainerName,
    'psql',
    '-U',
    $PostgresUser,
    '-d',
    $PostgresDatabase,
    '-v',
    'ON_ERROR_STOP=1',
    '-f',
    '/tmp/item20-postgres-proof.sql'
  ) | Out-Null

  Invoke-NativeLogged -Title 'list proof tables' -FilePath 'docker' -Arguments @(
    'exec',
    $ContainerName,
    'psql',
    '-U',
    $PostgresUser,
    '-d',
    $PostgresDatabase,
    '-v',
    'ON_ERROR_STOP=1',
    '-c',
    '\dt npdev_*'
  ) | Out-Null

  Invoke-NativeLogged -Title 'Postgres proof summary query' -FilePath 'docker' -Arguments @(
    'exec',
    $ContainerName,
    'psql',
    '-U',
    $PostgresUser,
    '-d',
    $PostgresDatabase,
    '-v',
    'ON_ERROR_STOP=1',
    '-c',
    "select 'postgres-proof-ok' as proof_status, count(*) as flow_instance_rows from npdev_flow_instance;"
  ) | Out-Null

  $summary = @(
    '# Item 20 Postgres Proof Output',
    '',
    ('Generated: ' + (Get-Date -Format o)),
    '',
    'Proof result: PASS',
    '',
    'This proof started a temporary real Postgres container and executed transactional SQL covering:',
    '- flow instance row persistence',
    '- correlation owner row persistence',
    '- event store row persistence',
    '- idempotency replay record with ON CONFLICT DO NOTHING',
    '- audit row persistence',
    '- trace row persistence',
    '- JSONB insert/read/update',
    '- FOR UPDATE SKIP LOCKED query compatibility',
    '',
    'This script is an executable proof harness. It is not the internal table source of truth and it does not create migration authority.'
  )
  $summary | Set-Content -Path (Join-Path $EvidenceDir 'item20-postgres-proof-summary.md') -Encoding UTF8
}
finally {
  Invoke-NativeAllowFailureLogged -Title 'docker logs for proof container' -FilePath 'docker' -Arguments @('logs', $ContainerName) | Out-Null

  if (-not $KeepContainer) {
    Invoke-NativeAllowFailureLogged -Title 'remove temporary Postgres container' -FilePath 'docker' -Arguments @('rm', '-f', $ContainerName) | Out-Null
  }

  if ($KeepContainer) {
    Log ("KEEP: container retained: " + $ContainerName)
  }
}
