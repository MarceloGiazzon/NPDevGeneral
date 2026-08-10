package com.npdev.generator.dbconfig;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.npdev.dsl.v1.compiled.CompiledConcept;
import com.npdev.dsl.v1.compiled.CompiledModel;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

public final class OperationalRunbookEmitter {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper()
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
    private static final String RESET_CONFIRMATION = "I_UNDERSTAND_DB_DATA_WILL_BE_DELETED";

    /**
     * PORT-1: resolve the plan's APP-RELATIVE data root, identically in every script that touches it.
     *
     * <p>Emitted as one shared block rather than copied per site for the reason QUAL-3 records: two
     * resolutions of "where is this app's data" is one database with two front doors, and the second
     * one is always found by a user rather than by a gate. It is also why this is a FUNCTION and not
     * a one-line expression at each call -- a per-site copy is how the H2Server branch ends up
     * anchored somewhere the Reset branch is not.
     *
     * <p>Twin-pair {@code app-data-root-anchor-three-seams} (token: npdev-app-data-root-anchor).
     * This RESOLVES the root {@code UserDatabaseDefinitionLoader} decided, for the five scripts --
     * one of which recursively deletes it.
     */
    private static final String DATA_ROOT_HELPER = """
# PORT-1: the plan carries an APP-RELATIVE data root ('data', or 'data/<generated name>'), never the
# absolute path of the machine that generated this app -- which is what used to be baked into
# spring.datasource.url, so a generated app opened its database on a drive the recipient may not
# have had. Both halves resolve it against the SAME anchor: the app against its own working
# directory (the FinalApp root), this toolbox against $PSScriptRoot/.. -- the same directory,
# because _ops lives INSIDE the app (QUAL-3). An absolute value is still honoured, so a plan written
# by an older generator keeps working.
function Get-NpdevDataRoot {
  param([object]$Plan)
  $raw = [string]$Plan.resolvedDataRoot
  if ([string]::IsNullOrWhiteSpace($raw)) { return '' }
  if ([System.IO.Path]::IsPathRooted($raw)) { return [System.IO.Path]::GetFullPath($raw) }
  return [System.IO.Path]::GetFullPath((Join-Path (Split-Path -Parent $PSScriptRoot) $raw))
}
""";

    /**
     * STOR-14: the shared notice every operation that would touch someone else's server prints.
     *
     * <p>Data-driven on {@code $plan.externallyProvisioned} and therefore byte-identical across
     * Postgres, MySQL and SQL Server -- which is not a nicety, it is what
     * {@code check-engine-parity.py} enforces, and a hand-written {@code -eq 'Postgres'} here would
     * fail that gate on the first run.
     */
    private static final String EXTERNAL_NOTICE_HELPER = """
# STOR-14: this server is the USER'S. NPDev did not start it, so NPDev does not get to start it,
# stop it, re-create it, or delete anything it stores.
#
# Every operation that would checks this BEFORE it branches on profile.kind, and RETURNS. That
# ordering is the whole item: the obvious partial fix -- make the `docker rm` branch a no-op when the
# server is external -- leaves Reset's `Remove-Item -Recurse -Force` aimed at a data root the user
# chose, because that delete is guarded by `physicalDatabase` and existence and never by WHOSE
# database it is.
function Write-NpdevExternalNotice {
  param([object]$Plan, [string]$Operation)
  Write-Host ''
  Write-Host "This app's database is EXTERNALLY PROVISIONED -- it is yours, not NPDev's."
  Write-Host "  engine    : $($Plan.engine) at $($Plan.host):$($Plan.hostPort)"
  Write-Host "  database  : $($Plan.resolvedDatabaseName)"
  Write-Host '  declared  : database.externallyProvisioned = true, in db.definition.json'
  Write-Host ''
  Write-Host "$Operation is refused. NPDev did not provision this server, so managing its lifecycle"
  Write-Host 'is not its to do. Nothing was started, stopped, removed or deleted.'
  Write-Host ''
}

# The reachability question an external server CAN be asked without owning it. Same probe
# Create-Environment uses for the port-collision check, and the same one `npdev doctor` uses -- one
# answer, not three.
function Test-NpdevServerReachable {
  param([string]$ServerHost, [int]$ServerPort, [int]$TimeoutMs = 1500)
  $probe = New-Object System.Net.Sockets.TcpClient
  try {
    $wait = $probe.BeginConnect($ServerHost, $ServerPort, $null, $null)
    if ($wait.AsyncWaitHandle.WaitOne($TimeoutMs) -and $probe.Connected) { return $true }
    return $false
  } catch { return $false } finally { $probe.Close() }
}
""";

    public Path emit(CompiledModel model, JsonNode config, Path finalAppRoot, GeneratedDatabasePlan plan) throws Exception {
        if (finalAppRoot == null || plan == null) {
            return null;
        }

        Path normalizedFinalAppRoot = finalAppRoot.toAbsolutePath().normalize();
        // QUAL-3: INSIDE the FinalApp, not beside it.
        //
        // This used to be `getParent().resolve("_ops")`, which makes the toolbox a property of the
        // PARENT DIRECTORY rather than of the app. `npdev init D:\Apps\my-app` generates into
        // `D:\Apps\my-app-app`, so the toolbox landed at `D:\Apps\_ops`; a second app in the same
        // folder generated into `D:\Apps\other-app` and wrote THE SAME `D:\Apps\_ops`, silently
        // replacing the first app's `resolved-db-plan.json` -- the file all five scripts read.
        // Reset "for" the first app then removed the second app's container and deleted the second
        // app's data root, and reported success. Measured RED before this change: after generating
        // two apps into one folder, `npdev db status --app <a>` answered about <b>.
        //
        // An app's operational toolbox belongs to the app. Putting it inside the FinalApp removes
        // the shared directory entirely rather than making sharing safe -- two apps can no longer
        // collide because neither has anywhere to collide.
        Path opsRoot = normalizedFinalAppRoot.resolve("_ops").toAbsolutePath().normalize();
        Files.createDirectories(opsRoot);

        int serverPort = readInt(config, 8080, "runtime", "serverPort");
        String apiKey = readText(config, "trialDefaults", "apiKey");
        if (apiKey == null || apiKey.isBlank()) {
            apiKey = "dev-key";
        }
        boolean hasUserConcept = hasConcept(model, "User");
        Path buildRoot = resolveBuildRoot(normalizedFinalAppRoot);
        Path runtimeHostLibs = buildRoot.resolve("runtimehost-libs").toAbsolutePath().normalize();

        Map<String, Object> resolvedPlan = resolvedPlan(model, normalizedFinalAppRoot, opsRoot, runtimeHostLibs,
                plan, serverPort, apiKey, hasUserConcept);
        writeJson(opsRoot.resolve("resolved-db-plan.json"), resolvedPlan);
        write(opsRoot.resolve("Create-Environment.ps1"), createEnvironmentScript());
        write(opsRoot.resolve("Start-Environment.ps1"), startEnvironmentScript());
        write(opsRoot.resolve("Stop-Environment.ps1"), stopEnvironmentScript());
        write(opsRoot.resolve("Status-Environment.ps1"), statusEnvironmentScript());
        write(opsRoot.resolve("Build-FinalApp.ps1"), buildFinalAppScript(normalizedFinalAppRoot, runtimeHostLibs));
        write(opsRoot.resolve("Run-FinalApp.ps1"), runFinalAppScript(normalizedFinalAppRoot, serverPort));
        write(opsRoot.resolve("Smoke-Test.ps1"), smokeTestScript());
        write(opsRoot.resolve("Print-DbConnectionInfo.ps1"), printDbConnectionInfoScript());
        write(opsRoot.resolve("Reset-Environment.ps1"), resetEnvironmentScript());
        write(opsRoot.resolve("README_RUNBOOK.md"), readme(opsRoot));
        return opsRoot;
    }

    private static Map<String, Object> resolvedPlan(
            CompiledModel model,
            Path finalAppRoot,
            Path opsRoot,
            Path runtimeHostLibs,
            GeneratedDatabasePlan plan,
            int serverPort,
            String apiKey,
            boolean hasUserConcept
    ) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("appId", plan.appId());
        out.put("engine", plan.engine().externalName());
        out.put("storageMode", plan.storageMode());
        out.put("physicalDatabase", plan.physicalDatabase());
        // STOR-14. The five scripts branch on THIS, never on the engine's name -- the same reason
        // `profile` exists a few lines below. A plan field is required here, not merely preferred:
        // an `-eq 'Postgres'` implementation of the same behaviour fails check-engine-parity.py
        // immediately, and would have to be written three times to pass it.
        out.put("externallyProvisioned", plan.externallyProvisioned());
        out.put("requestedDatabaseName", plan.requestedDatabaseName());
        out.put("resolvedDatabaseName", plan.resolvedDatabaseName());
        out.put("databaseNameSource", plan.databaseNameSource());
        out.put("resolvedDataRoot", plan.resolvedDataRoot());
        out.put("databaseInstanceId", plan.databaseInstanceId());
        out.put("containerName", plan.containerName());
        out.put("host", plan.host());
        out.put("hostPort", plan.hostPort());
        out.put("containerPort", plan.containerPort());
        out.put("username", plan.username());
        out.put("password", plan.password());
        out.put("jdbcUrl", plan.jdbcUrl());
        out.put("driverClassName", plan.driverClassName());
        out.put("finalAppPath", slash(finalAppRoot));
        out.put("opsRoot", slash(opsRoot));
        out.put("runtimeHostLibsDir", slash(runtimeHostLibs));
        out.put("serverPort", serverPort);
        out.put("apiKey", apiKey);
        out.put("schemaFingerprint", plan.schemaFingerprint());
        // E15/P1: the engine's PROVISIONING facts travel with the plan, so the five _ops scripts can
        // branch on `profile.kind` instead of the engine's NAME. That is the whole parity move --
        // one `if ($plan.profile.kind -eq 'server')` covers Postgres, MySQL and SQL Server
        // identically, where five `-eq 'Postgres'` blocks covered one engine and threw at the rest.
        // validate() has already refused an incomplete SERVER profile at generation time.
        out.put("profile", DockerEngineProfiles.of(plan.engine()).toPlanJson());
        // Insertion-ordered, not Map.of(...): java.util.Map.of with 2+ entries produces an
        // ImmutableCollections.MapN whose iteration order is randomized per-JVM by
        // ImmutableCollections.SALT, which Jackson would otherwise serialize in that varying order
        // -- the GATE-DET-1 byte-nondeterminism mechanism. This emitter's ObjectMapper happens to
        // set ORDER_MAP_ENTRIES_BY_KEYS, which today re-sorts every map (nested ones included) by
        // key at write time and so masks the hazard for resolved-db-plan.json specifically. We do
        // NOT rely on that global flag to compensate for a per-site Map.of: drop the flag and the
        // non-determinism returns silently. Keep each nested object insertion-ordered so the
        // guarantee is local and matches every other emitter in this bug class.
        Map<String, Object> dbeaver = new LinkedHashMap<>();
        dbeaver.put("host", plan.dbeaverHost());
        dbeaver.put("port", plan.dbeaverPort());
        dbeaver.put("database", plan.dbeaverDatabase());
        dbeaver.put("username", plan.dbeaverUsername());
        dbeaver.put("ssl", "disabled");
        out.put("dbeaver", dbeaver);
        Map<String, Object> smoke = new LinkedHashMap<>();
        smoke.put("hasUserConcept", hasUserConcept);
        smoke.put("conceptCount", model == null ? 0 : model.getConcepts().size());
        out.put("smoke", smoke);
        return out;
    }

    /**
     * Create the environment -- ONE form for every engine.
     *
     * <p>E15/P1. This method used to hold an {@code if ($plan.engine -eq 'Postgres')} block and
     * throw for MySQL and SQL Server. It now branches on {@code $plan.profile.kind}, and every
     * engine-specific fact -- image, environment, extra run args, readiness probe, database
     * creation -- comes from the profile the generator wrote into {@code resolved-db-plan.json}.
     * Adding a fourth server engine is a row in {@code engine-profiles.json}, not a sixth branch.
     */
    private static String createEnvironmentScript() {
        return """
$ErrorActionPreference = 'Stop'
$planPath = Join-Path $PSScriptRoot 'resolved-db-plan.json'
$plan = Get-Content -Raw -LiteralPath $planPath | ConvertFrom-Json
""" + DATA_ROOT_HELPER + EXTERNAL_NOTICE_HELPER + """
# STOR-14: "ensure the database exists" LOSES ITS CLIENT here, and the answer is to stop trying.
# This script can guarantee a client for a container it started -- `docker exec <container> createdb`
# runs the client that lives INSIDE the image. A server NPDev did not start guarantees nothing, and
# on Windows psql / mysql / sqlcmd are rarely on PATH. So in external mode Create becomes VERIFY:
# connect with the JDBC driver THE APP ITSELF uses, and report what is actually true.
#
# It degrades honestly rather than guessing. No java, or no driver jar on this machine yet, produces
# "could not verify" and a pointer at `npdev db test-connection` -- never "ready", which is the one
# answer that would be worse than saying nothing.

# The driver's MAVEN COORDINATE, not a file-name glob.
#
# Measured, not assumed: the first version searched the whole Gradle cache for 'postgresql-*.jar'
# and found testcontainers' postgresql-1.21.4.jar, which is not a JDBC driver. The cache is laid out
# by group/artifact, so a name glob matches any artifact whose NAME starts that way -- and the run
# then reported "database does not exist" on the strength of "No suitable driver found". Same
# coordinate list `npdev doctor` uses, for the same reason it uses one.
function Get-NpdevDriverCoordinate {
  param([object]$Plan)
  switch -Wildcard ([string]$Plan.driverClassName) {
    'org.postgresql.*'          { return @{ group = 'org.postgresql';          artifact = 'postgresql' } }
    'com.mysql.*'               { return @{ group = 'com.mysql';               artifact = 'mysql-connector-j' } }
    'com.microsoft.sqlserver.*' { return @{ group = 'com.microsoft.sqlserver'; artifact = 'mssql-jdbc' } }
    'org.h2.*'                  { return @{ group = 'com.h2database';          artifact = 'h2' } }
  }
  return $null
}

function Find-NpdevDriverJar {
  param([object]$Plan)
  $coord = Get-NpdevDriverCoordinate -Plan $Plan
  if ($null -eq $coord) { return $null }
  $gradleGroup = Join-Path $env:USERPROFILE ('.gradle\\caches\\modules-2\\files-2.1\\' + $coord.group + '\\' + $coord.artifact)
  $mavenGroup = Join-Path $env:USERPROFILE ('.m2\\repository\\' + ($coord.group -replace '[.]', '\\') + '\\' + $coord.artifact)
  $roots = @($Plan.runtimeHostLibsDir, (Join-Path $Plan.finalAppPath 'build'), $gradleGroup, $mavenGroup) |
    Where-Object { $_ -and (Test-Path -LiteralPath $_) }
  foreach ($root in $roots) {
    $jar = Get-ChildItem -Path $root -Recurse -Filter ($coord.artifact + '-*.jar') -ErrorAction SilentlyContinue |
      Where-Object { $_.Name -notmatch 'sources|javadoc' } |
      Sort-Object LastWriteTime -Descending | Select-Object -First 1
    if ($null -ne $jar) { return $jar.FullName }
  }
  return $null
}

# SQLStates that mean THIS DATABASE IS NOT THERE, and nothing else.
#
# Deliberately short. A missing database has to be PROVEN, not inferred from "the connection
# failed" -- wrong credentials, a firewall and an unloadable driver all fail too, and this project
# has already shipped a doctor that reported a missing database as a credentials failure. Postgres's
# 3D000 (invalid_catalog_name) and H2's 90149 mean exactly one thing. MySQL's 42000 is the whole
# syntax-error class and SQL Server's are reused across unrelated faults, so they are NOT listed:
# on those engines this reports the driver's own words without a verdict, and points at
# `npdev db test-connection`, which asks the server properly.
$NpdevDatabaseMissingSqlStates = @('3D000', '90149')

function Test-NpdevDatabaseExists {
  param([object]$Plan)
  $java = if ($env:JAVA_HOME -and (Test-Path -LiteralPath (Join-Path $env:JAVA_HOME 'bin\\java.exe'))) {
    Join-Path $env:JAVA_HOME 'bin\\java.exe'
  } else {
    (Get-Command java -ErrorAction SilentlyContinue).Source
  }
  if (-not $java) { return @{ verified = $false; reason = 'no java on this machine' } }
  $jar = Find-NpdevDriverJar -Plan $Plan
  if (-not $jar) { return @{ verified = $false; reason = "no JDBC driver jar for $($Plan.engine) found on this machine yet -- build this app once, or run ``npdev setup``" } }
  $work = Join-Path ([System.IO.Path]::GetTempPath()) ('npdev-verify-' + [guid]::NewGuid().ToString('N').Substring(0, 8))
  New-Item -ItemType Directory -Force -Path $work | Out-Null
  try {
    $src = Join-Path $work 'NpdevVerifyDb.java'
    Set-Content -LiteralPath $src -Encoding UTF8 -Value @'
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
public class NpdevVerifyDb {
  public static void main(String[] args) {
    try (Connection c = DriverManager.getConnection(args[0], args[1], args[2])) {
      System.out.println("NPDEV_DB_OK");
    } catch (SQLException e) {
      // The SQLSTATE, not the sentence: every engine phrases "no such database" differently, and a
      // status that depends on English phrasing is a bug this codebase has already paid for.
      System.out.println("NPDEV_DB_FAIL|" + e.getSQLState() + "|" + e.getMessage());
      System.exit(3);
    } catch (Exception e) {
      System.out.println("NPDEV_DB_FAIL||" + e);
      System.exit(3);
    }
  }
}
'@
    $out = & $java '--class-path' $jar $src $Plan.jdbcUrl $Plan.username $Plan.password 2>&1 | Out-String
    if ($out -match 'NPDEV_DB_OK') { return @{ verified = $true; exists = $true } }
    $state = ''
    $message = $out.Trim()
    if ($out -match 'NPDEV_DB_FAIL\\|([^|]*)\\|([^\\r\\n]*)') {
      $state = $Matches[1].Trim()
      $message = $Matches[2].Trim()
    }
    if ($NpdevDatabaseMissingSqlStates -contains $state) {
      return @{ verified = $true; exists = $false; state = $state; detail = $message }
    }
    return @{ verified = $false; reason = "the driver refused the connection and did not say the database is missing (SQLSTATE '$state'): $message" }
  }
  finally { Remove-Item -LiteralPath $work -Recurse -Force -ErrorAction SilentlyContinue }
}

if ($plan.externallyProvisioned) {
  Write-NpdevExternalNotice -Plan $plan -Operation 'Creating the environment'
  Write-Host 'Checking what is actually there instead:'
  Write-Host ''
  if (-not (Test-NpdevServerReachable -ServerHost $plan.host -ServerPort ([int]$plan.hostPort))) {
    Write-Host "Nothing is listening on $($plan.host):$($plan.hostPort). Start your $($plan.engine)"
    Write-Host 'server, then run this again.'
    exit 1
  }
  Write-Host "Reachable: something is serving on $($plan.host):$($plan.hostPort)."
  $probe = Test-NpdevDatabaseExists -Plan $plan
  if (-not $probe.verified) {
    Write-Host ''
    Write-Host "Could NOT verify database '$($plan.resolvedDatabaseName)' on $($plan.host):$($plan.hostPort)."
    Write-Host "  $($probe.reason)"
    Write-Host ''
    Write-Host 'That is not a verdict on your settings -- nobody checked them. Run'
    Write-Host '`npdev db test-connection`, which tells a missing database apart from a wrong password.'
    exit 1
  }
  if ($probe.exists) {
    Write-Host "Database '$($plan.resolvedDatabaseName)' exists and accepts this app's credentials."
    exit 0
  }
  Write-Host ''
  Write-Host "database '$($plan.resolvedDatabaseName)' does not exist on $($plan.host):$($plan.hostPort) -- create it and re-run"
  Write-Host ''
  Write-Host "  the server said so itself (SQLSTATE $($probe.state)): $($probe.detail)"
  exit 1
}

# Substitute the placeholders a profile uses. Kept in one function so every operation resolves
# them identically -- a per-site copy is how {password} ends up literal in one script and expanded
# in another.
function Expand-ProfileToken {
  param([string]$Value, [object]$Plan)
  if ($null -eq $Value) { return $Value }
  $out = $Value
  $out = $out.Replace('{database}', [string]$Plan.resolvedDatabaseName)
  $out = $out.Replace('{username}', [string]$Plan.username)
  $out = $out.Replace('{password}', [string]$Plan.password)
  $out = $out.Replace('{host}', [string]$Plan.host)
  $out = $out.Replace('{port}', [string]$Plan.hostPort)
  return $out
}

function Expand-ProfileList {
  param([object[]]$Values, [object]$Plan)
  if ($null -eq $Values) { return @() }
  return @($Values | ForEach-Object { Expand-ProfileToken -Value ([string]$_) -Plan $Plan })
}

# Wait until the engine can SERVE, not until the container is running -- those are different
# moments, and the gap is where "connection refused" comes from. timeoutSeconds is per-engine
# because SQL Server routinely needs 30-60s; giving it Postgres's budget reports a healthy engine
# as broken.
function Wait-EngineReady {
  param([object]$Plan)
  $probe = $Plan.profile.readyProbe
  if ($null -eq $probe -or $null -eq $probe.exec -or $probe.exec.Count -eq 0) {
    throw "Engine '$($Plan.engine)' has no readiness probe in its profile. Refusing to report an environment ready when nothing checked it."
  }
  $probeArgs = Expand-ProfileList -Values $probe.exec -Plan $Plan
  $deadline = (Get-Date).AddSeconds([int]$probe.timeoutSeconds)
  while ((Get-Date) -lt $deadline) {
    & docker exec $Plan.containerName @probeArgs *> $null
    if ($LASTEXITCODE -eq [int]$probe.expectExitCode) { return }
    Start-Sleep -Seconds 2
  }
  throw "Engine '$($Plan.engine)' container '$($Plan.containerName)' did not become ready within $($probe.timeoutSeconds)s."
}

# SQL Server has no MSSQL_DATABASE variable (createsDatabaseFromEnv = false), so for it this
# CREATES the database; for Postgres and MySQL, which create theirs from the environment, it
# verifies. Skipping it leaves the app connecting to a database that does not exist -- which this
# project has already watched doctor misreport as a credentials failure.
function Ensure-EngineDatabase {
  param([object]$Plan)
  $ensure = $Plan.profile.ensureDatabase
  if ($null -eq $ensure -or $null -eq $ensure.createExec -or $ensure.createExec.Count -eq 0) {
    throw "Engine '$($Plan.engine)' has no ensureDatabase step in its profile."
  }
  $envArgs = @()
  if ($null -ne $ensure.execEnv) {
    foreach ($name in $ensure.execEnv.PSObject.Properties.Name) {
      $value = Expand-ProfileToken -Value ([string]$ensure.execEnv.$name) -Plan $Plan
      $envArgs += @('-e', "$name=$value")
    }
  }
  $createArgs = Expand-ProfileList -Values $ensure.createExec -Plan $Plan
  & docker exec @envArgs $Plan.containerName @createArgs *> $null
  if ($LASTEXITCODE -eq 0) {
    Write-Host "Database ready: $($Plan.resolvedDatabaseName)"
    return
  }
  # A create that fails because the database is already there is success. Ask, rather than parsing
  # an error message -- every engine phrases that differently, which is exactly how a status code
  # ended up depending on English phrasing elsewhere in this codebase.
  if ($null -ne $ensure.listExec -and $ensure.listExec.Count -gt 0) {
    $listArgs = Expand-ProfileList -Values $ensure.listExec -Plan $Plan
    $existing = & docker exec @envArgs $Plan.containerName @listArgs 2>$null
    if ($LASTEXITCODE -eq 0 -and ($existing -join "`n") -match [regex]::Escape($Plan.resolvedDatabaseName)) {
      Write-Host "Verified database: $($Plan.resolvedDatabaseName)"
      return
    }
  }
  throw "Database '$($Plan.resolvedDatabaseName)' could not be created or verified in '$($Plan.containerName)'."
}

if ($plan.profile.kind -eq 'embedded' -and $plan.engine -eq 'InMemory') {
  Write-Host 'No physical database to create for InMemory.'
  exit 0
}

$dataRoot = Get-NpdevDataRoot -Plan $plan
New-Item -ItemType Directory -Force -Path $dataRoot | Out-Null

if ($plan.profile.kind -eq 'server') {
  docker version | Out-Null
  if ($LASTEXITCODE -ne 0) { throw "Docker is required to create a $($plan.profile.guiLabel) environment." }
  $existing = docker ps -a --filter "name=^/$($plan.containerName)$" --format "{{.Names}}"
  if ($existing -eq $plan.containerName) {
    docker start $plan.containerName | Out-Null
  } else {
    # A machine that ALREADY runs PostgreSQL or SQL Server is the likely case, not the exotic one --
    # people pick the engine they already use. Without this check, `docker run -p 5432:5432` collides
    # with their own server and reports a raw Docker port-binding error, and "the tool fought my
    # database" is a first impression that has nothing to do with whether the tool is any good.
    #
    # DETECT, do not solve. NPDev has no EXTERNAL mode yet -- one where the toolbox knows the server
    # is not its to manage and refuses Start/Stop/Reset. That is STOR-14, and its centrepiece is not
    # the disabling: Reset's recursive delete of the data root is guarded by `physicalDatabase`, not
    # by whose database it is, so a no-op on the `docker rm` half alone would leave a
    # `Remove-Item -Recurse -Force` aimed at a path the user chose. Naming the collision converts a
    # confusing failure into a sentence for a fraction of that, and destroys nothing.
    #
    # Same shape `npdev dev` already uses for the APP port ("Port N is already in use before this run
    # even started"), one layer down, and emitted HERE so the CLI, the Manager and a terminal user all
    # inherit one answer instead of three.
    $probe = New-Object System.Net.Sockets.TcpClient
    $portInUse = $false
    try {
      $wait = $probe.BeginConnect($plan.host, [int]$plan.hostPort, $null, $null)
      if ($wait.AsyncWaitHandle.WaitOne(1500) -and $probe.Connected) { $portInUse = $true }
    } catch { $portInUse = $false } finally { $probe.Close() }
    if ($portInUse) {
      # Write-Host + exit, NOT throw: `throw` wraps the text in a PowerShell exception trace
      # (`Line | 117 | throw @"` and a column of tildes) and the sentence the user needs arrives
      # buried in it. The point of this check is the sentence.
      Write-Host ""
      Write-Host "Something is already listening on $($plan.host):$($plan.hostPort)."
      Write-Host ""
      Write-Host "If that is your own $($plan.profile.guiLabel), you do not need this button -- NPDev will"
      Write-Host "connect to it. Use `"Test connection`" to confirm, then Run."
      Write-Host ""
      Write-Host "If it is a container from another app, stop it first, or give this app a"
      Write-Host "different port in db.definition.json."
      exit 1
    }
    $runArgs = @('run', '-d', '--name', $plan.containerName)
    foreach ($name in $plan.profile.containerEnv.PSObject.Properties.Name) {
      $value = Expand-ProfileToken -Value ([string]$plan.profile.containerEnv.$name) -Plan $plan
      $runArgs += @('-e', "$name=$value")
    }
    $runArgs += @('-p', "$($plan.hostPort):$($plan.containerPort)")
    $runArgs += @($plan.profile.image)
    # AFTER the image, because these are the engine's own arguments, not docker's. MySQL's
    # --character-set-server=utf8mb4 lives here and is NOT optional: the legacy three-byte utf8
    # silently mangles anything outside the BMP, and the insert succeeds.
    $runArgs += Expand-ProfileList -Values $plan.profile.extraRunArgs -Plan $plan
    & docker @runArgs | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "Failed to start the $($plan.profile.guiLabel) container '$($plan.containerName)'." }
  }
  Wait-EngineReady -Plan $plan
  Ensure-EngineDatabase -Plan $plan
  Write-Host "$($plan.profile.guiLabel) environment ready: $($plan.containerName)"
  exit 0
}

if ($plan.engine -eq 'H2Local') {
  Write-Host "H2Local data root ready: $dataRoot"
  exit 0
}

if ($plan.profile.kind -eq 'embedded-server') {
  # E17: search the libs directory THIS app was generated against, never a path from the machine
  # that generated it. This used to read 'D:\\WorkSpace\\NPDev\\Build' -- the author's drive letter,
  # shipped to the user, and named again in the error message telling them where to look.
  $searchRoots = @($plan.runtimeHostLibsDir, (Join-Path $plan.finalAppPath 'build'), $dataRoot) |
    Where-Object { $_ -and (Test-Path -LiteralPath $_) }
  $jar = $null
  foreach ($root in $searchRoots) {
    $jar = Get-ChildItem -Path $root -Recurse -Filter 'h2-*.jar' -ErrorAction SilentlyContinue |
      Sort-Object LastWriteTime -Descending |
      Select-Object -First 1
    if ($null -ne $jar) { break }
  }
  if ($null -eq $jar) {
    throw "Could not find an H2 jar under: $($searchRoots -join ', '). Build this app once, or restore its runtimehost-libs."
  }
  $pidFile = Join-Path $PSScriptRoot 'h2server.pid'
  $stdoutLogFile = Join-Path $PSScriptRoot 'h2server.stdout.log'
  $stderrLogFile = Join-Path $PSScriptRoot 'h2server.stderr.log'
  if (Test-Path -LiteralPath $pidFile) {
    $pidValue = Get-Content -Raw -LiteralPath $pidFile
    $process = Get-Process -Id ([int]$pidValue) -ErrorAction SilentlyContinue
    if ($null -ne $process) {
      Write-Host "H2Server already running with PID $pidValue"
      exit 0
    }
  }
  # PORT-1: -baseDir is the FINALAPP ROOT, not the data root, because the client URL now carries the
  # app-relative path ('./data/<db>') and H2Server resolves that path SERVER-side. Anchoring the
  # server one directory deeper than the client names would open <app>/data/data/<db> -- a second,
  # empty database, created silently, which is the failure mode this whole item is about.
  $appRoot = Split-Path -Parent $PSScriptRoot
  $serverArgs = @('-cp', $jar.FullName, 'org.h2.tools.Server', '-tcp', '-tcpPort', [string]$plan.hostPort, '-baseDir', $appRoot, '-ifNotExists')
  $process = Start-Process -FilePath 'java' -ArgumentList $serverArgs -WorkingDirectory $appRoot -PassThru -WindowStyle Hidden -RedirectStandardOutput $stdoutLogFile -RedirectStandardError $stderrLogFile
  Set-Content -LiteralPath $pidFile -Value $process.Id
  Start-Sleep -Seconds 2
  Write-Host "H2Server started on port $($plan.hostPort), PID $($process.Id)"
  exit 0
}

throw "Unsupported engine '$($plan.engine)' in resolved-db-plan.json."
""";
    }

    private static String startEnvironmentScript() {
        return """
$ErrorActionPreference = 'Stop'
$plan = Get-Content -Raw -LiteralPath (Join-Path $PSScriptRoot 'resolved-db-plan.json') | ConvertFrom-Json
""" + EXTERNAL_NOTICE_HELPER + """
# Start has its own refusal rather than inheriting Create's, because "start" is the word a user
# reaches for and the answer they need names THAT verb. Create's external branch goes on to verify
# the database exists, which is a different (useful) job; this one is a straight no.
if ($plan.externallyProvisioned) {
  Write-NpdevExternalNotice -Plan $plan -Operation 'Starting the environment'
  if (Test-NpdevServerReachable -ServerHost $plan.host -ServerPort ([int]$plan.hostPort)) {
    Write-Host "Something is already serving on $($plan.host):$($plan.hostPort) -- if that is your"
    Write-Host 'server, there is nothing to start. Run the app.'
    exit 0
  }
  Write-Host "Nothing is listening on $($plan.host):$($plan.hostPort). Start your $($plan.engine)"
  Write-Host 'server the way you normally do, then run the app.'
  exit 1
}
& (Join-Path $PSScriptRoot 'Create-Environment.ps1')
""";
    }

    private static String stopEnvironmentScript() {
        return """
$ErrorActionPreference = 'Stop'
$plan = Get-Content -Raw -LiteralPath (Join-Path $PSScriptRoot 'resolved-db-plan.json') | ConvertFrom-Json
""" + EXTERNAL_NOTICE_HELPER + """
if ($plan.externallyProvisioned) {
  Write-NpdevExternalNotice -Plan $plan -Operation 'Stopping the environment'
  Write-Host 'Stop it yourself if you mean to -- other things may be using it.'
  exit 0
}
if ($plan.profile.kind -eq 'server') {
  $running = docker ps --filter "name=^/$($plan.containerName)$" --format "{{.Names}}" 2>$null
  if ($running -eq $plan.containerName) { docker stop $plan.containerName | Out-Null }
  Write-Host "$($plan.profile.guiLabel) environment stopped: $($plan.containerName)"
  exit 0
}
if ($plan.profile.kind -eq 'embedded-server') {
  $pidFile = Join-Path $PSScriptRoot 'h2server.pid'
  if (Test-Path -LiteralPath $pidFile) {
    $pidValue = [int](Get-Content -Raw -LiteralPath $pidFile)
    $process = Get-Process -Id $pidValue -ErrorAction SilentlyContinue
    if ($null -ne $process) { Stop-Process -Id $pidValue -Force }
    Remove-Item -LiteralPath $pidFile -Force
  }
  Write-Host 'H2Server environment stopped.'
  exit 0
}
Write-Host "No background environment service to stop for $($plan.engine)."
""";
    }

    private static String statusEnvironmentScript() {
        return """
$ErrorActionPreference = 'Stop'
$plan = Get-Content -Raw -LiteralPath (Join-Path $PSScriptRoot 'resolved-db-plan.json') | ConvertFrom-Json
""" + DATA_ROOT_HELPER + EXTERNAL_NOTICE_HELPER + """
$dataRoot = Get-NpdevDataRoot -Plan $plan
Write-Host "Engine: $($plan.engine)"
Write-Host "Physical database: $($plan.physicalDatabase)"
Write-Host "Resolved database: $($plan.resolvedDatabaseName)"
Write-Host "Data root: $dataRoot"
# STOR-14: an external server has NO CONTAINER TO INSPECT, so `docker ps` would report "not found"
# about a database that is running perfectly well. Reachability is the question that has an answer
# here, and it is the question the user actually asked.
if ($plan.externallyProvisioned) {
  Write-Host 'Provisioning: EXTERNAL -- this server is yours, not NPDev''s.'
  if (Test-NpdevServerReachable -ServerHost $plan.host -ServerPort ([int]$plan.hostPort)) {
    Write-Host "Reachable: yes -- something is serving on $($plan.host):$($plan.hostPort)."
    Write-Host 'That it is YOUR database, with this app''s credentials, is what'
    Write-Host '`npdev db test-connection` settles; a port probe cannot.'
    exit 0
  }
  Write-Host "Reachable: NO -- nothing is listening on $($plan.host):$($plan.hostPort)."
  exit 1
}
if ($plan.profile.kind -eq 'server') {
  docker ps -a --filter "name=^/$($plan.containerName)$"
  exit 0
}
if ($plan.profile.kind -eq 'embedded-server') {
  $pidFile = Join-Path $PSScriptRoot 'h2server.pid'
  if (Test-Path -LiteralPath $pidFile) {
    $pidValue = [int](Get-Content -Raw -LiteralPath $pidFile)
    $process = Get-Process -Id $pidValue -ErrorAction SilentlyContinue
    if ($null -ne $process) { Write-Host "H2Server running with PID $pidValue"; exit 0 }
  }
  Write-Host 'H2Server is not running.'
  exit 1
}
if ($plan.engine -eq 'H2Local') {
  Write-Host "H2Local data root exists: $(Test-Path -LiteralPath $dataRoot)"
  exit 0
}
Write-Host 'InMemory has no physical database service.'
""";
    }

    private static String buildFinalAppScript(Path finalAppRoot, Path runtimeHostLibs) {
        return """
$ErrorActionPreference = 'Stop'
Set-Location '%s'
& '.\\gradlew.bat' --no-daemon -PnpdevRuntimeHostLibsDir='%s' clean build --stacktrace --console=plain
exit $LASTEXITCODE
""".formatted(ps(finalAppRoot), ps(runtimeHostLibs));
    }

    private static String runFinalAppScript(Path finalAppRoot, int serverPort) {
        return """
$ErrorActionPreference = 'Stop'
Set-Location '%s'
java -jar '%s' --server.port=%d
exit $LASTEXITCODE
""".formatted(ps(finalAppRoot), ps(finalAppRoot.resolve("build").resolve("libs").resolve("FinalExec-0.1.0.jar")), serverPort);
    }

    private static String smokeTestScript() {
        return """
$ErrorActionPreference = 'Stop'
$plan = Get-Content -Raw -LiteralPath (Join-Path $PSScriptRoot 'resolved-db-plan.json') | ConvertFrom-Json
$baseUrl = "http://localhost:$($plan.serverPort)"
$headers = @{ 'X-Api-Key' = $plan.apiKey }
$report = [ordered]@{
  appId = $plan.appId
  engine = $plan.engine
  baseUrl = $baseUrl
  storageSummaryPassed = $false
  sampleCreateListPassed = $false
  status = 'FAIL'
  errors = @()
}
try {
  $summary = Invoke-RestMethod -Method GET -Uri "$baseUrl/api/admin/storage/summary" -Headers $headers
  $report.storageSummary = $summary
  $report.storageSummaryPassed = $true
  if ($plan.smoke.hasUserConcept) {
    $email = "smoke-$([guid]::NewGuid().ToString('N'))@example.test"
    $body = @{
      id = [guid]::NewGuid().ToString()
      name = 'Smoke User'
      email = $email
      active = $true
    } | ConvertTo-Json
    $created = Invoke-RestMethod -Method POST -Uri "$baseUrl/api/users" -Headers $headers -ContentType 'application/json' -Body $body
    $users = Invoke-RestMethod -Method GET -Uri "$baseUrl/api/users" -Headers $headers
    $found = @($users) | Where-Object { $_.email -eq $email }
    if ($null -eq $found -or @($found).Count -lt 1) { throw "Created user '$email' was not returned by GET /api/users." }
    $report.createdUser = $created
    $report.sampleCreateListPassed = $true
  } else {
    $report.sampleCreateListPassed = $true
    $report.sampleCreateListSkipped = 'No User concept exists in this app.'
  }
  $report.status = 'PASS'
} catch {
  $report.errors += $_.Exception.Message
}
$reportPath = Join-Path $PSScriptRoot 'smoke-test-report.json'
$report | ConvertTo-Json -Depth 20 | Set-Content -LiteralPath $reportPath -Encoding UTF8
Write-Host "Smoke test report: $reportPath"
Write-Host "Status: $($report.status)"
if ($report.status -ne 'PASS') { exit 1 }
exit 0
""";
    }

    private static String printDbConnectionInfoScript() {
        return """
$plan = Get-Content -Raw -LiteralPath (Join-Path $PSScriptRoot 'resolved-db-plan.json') | ConvertFrom-Json
""" + DATA_ROOT_HELPER + """
# PORT-1: the H2 rows below name a FILE, and DBeaver needs a path it can open -- so this one screen
# resolves the app-relative form to an absolute one rather than printing 'data/npdev_x' and leaving
# the reader to work out what it is relative to. The app and the toolbox still share one anchor;
# this is the same directory, spelled for a human.
$dataRoot = Get-NpdevDataRoot -Plan $plan
$h2File = if ([string]::IsNullOrWhiteSpace([string]$plan.resolvedDatabaseName)) { $dataRoot }
          else { Join-Path $dataRoot $plan.resolvedDatabaseName }

# The quirks are printed HERE because this is the screen a user already has open when they need
# them -- that MySQL's utf8mb4 is not optional, that SQL Server's 'sa' is not their app's username.
# A limitation an engine has must be declared at the point of choice, not discovered later.
function Show-EngineQuirks {
  param([object]$Plan)
  if ($null -eq $Plan.profile.quirks -or $Plan.profile.quirks.Count -eq 0) { return }
  Write-Host ''
  Write-Host "Notes for $($Plan.profile.guiLabel):"
  foreach ($quirk in $Plan.profile.quirks) { Write-Host "  - $quirk" }
}
if ($plan.engine -eq 'InMemory') {
  Write-Host 'No physical database. Use /api/admin/storage/summary.'
  exit 0
}
if ($plan.profile.kind -eq 'server') {
  Write-Host 'DBeaver connection'
  Write-Host ''
  Write-Host "Database type: $($plan.profile.guiLabel)"
  Write-Host "Host: $($plan.dbeaver.host)"
  Write-Host "Port: $($plan.dbeaver.port)"
  Write-Host "Database: $($plan.dbeaver.database)"
  Write-Host "Username: $($plan.dbeaver.username)"
  Write-Host "Password: $($plan.password)"
  Write-Host "SSL: $($plan.dbeaver.ssl)"
  Write-Host "JDBC URL: $($plan.jdbcUrl)"
  Show-EngineQuirks -Plan $plan
  exit 0
}
if ($plan.engine -eq 'H2Local') {
  Write-Host 'DBeaver connection'
  Write-Host ''
  Write-Host "Database type: $($plan.profile.guiLabel)"
  Write-Host "JDBC URL: jdbc:h2:file:$h2File;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_ON_EXIT=FALSE;WRITE_DELAY=0"
  Write-Host "Database file: $h2File"
  Write-Host "Username: $($plan.username)"
  Write-Host "Password: $($plan.password)"
  Write-Host ''
  Write-Host "The app itself uses the app-relative form ($($plan.jdbcUrl)),"
  Write-Host 'resolved against its own directory. Same file, spelled for a tool that is not the app.'
  Show-EngineQuirks -Plan $plan
  exit 0
}
if ($plan.engine -eq 'H2Server') {
  Write-Host 'DBeaver connection'
  Write-Host ''
  Write-Host "Database type: $($plan.profile.guiLabel)"
  Write-Host "Host: $($plan.dbeaver.host)"
  Write-Host "Port: $($plan.dbeaver.port)"
  Write-Host "JDBC URL: $($plan.jdbcUrl)"
  Write-Host "Database file: $h2File"
  Write-Host "Username: $($plan.username)"
  Write-Host "Password: $($plan.password)"
  Write-Host ''
  Write-Host 'The path in the URL is resolved by the H2 SERVER, against the baseDir'
  Write-Host 'Create-Environment.ps1 starts it with -- this app''s own directory.'
  Show-EngineQuirks -Plan $plan
  exit 0
}
throw "Unsupported engine '$($plan.engine)' in resolved-db-plan.json."
""";
    }

    private static String resetEnvironmentScript() {
        return """
param([string]$Confirm)
$ErrorActionPreference = 'Stop'
$plan = Get-Content -Raw -LiteralPath (Join-Path $PSScriptRoot 'resolved-db-plan.json') | ConvertFrom-Json
""" + DATA_ROOT_HELPER + EXTERNAL_NOTICE_HELPER + """
# STOR-14, THE CENTREPIECE -- and it comes BEFORE the confirmation check on purpose.
#
# Asking for the token first would not be a smaller version of this guard, it would be a worse one:
# the user types it correctly, for the app they mean, and the answer is still no. Refusing first says
# so without first teaching them the token that removes the only other safety here.
#
# It also comes before BOTH destructive halves, and it RETURNS. The two halves below are `docker rm`
# and a `Remove-Item -Recurse -Force` on the data root -- and the second one is guarded by
# `physicalDatabase` and existence, NEVER by whose database it is. Skipping only the Docker branch
# and continuing, which is the obvious partial fix, leaves that recursive delete aimed at a path the
# user chose. That is the difference between an incomplete feature and a destructive one.
if ($plan.externallyProvisioned) {
  Write-NpdevExternalNotice -Plan $plan -Operation 'Resetting the environment'
  Write-Host 'In particular: your data root was NOT deleted, and no container was removed.'
  Write-Host "  data root left intact : $(Get-NpdevDataRoot -Plan $plan)"
  Write-Host ''
  Write-Host 'To start this app from an empty database, drop and re-create the database on your own'
  Write-Host 'server with your own tools -- NPDev will not do it for you on a server it did not'
  Write-Host 'provision.'
  # Non-zero: the destructive thing the caller asked for did NOT happen, and a wrapper that reads
  # exit 0 as "reset done" would then act on a database that still has all its data in it.
  exit 1
}
if ($Confirm -ne 'I_UNDERSTAND_DB_DATA_WILL_BE_DELETED') {
  throw 'Reset refused. Re-run with -Confirm I_UNDERSTAND_DB_DATA_WILL_BE_DELETED'
}
$dataRoot = Get-NpdevDataRoot -Plan $plan
& (Join-Path $PSScriptRoot 'Stop-Environment.ps1')
if ($plan.profile.kind -eq 'server') {
  $existing = docker ps -a --filter "name=^/$($plan.containerName)$" --format "{{.Names}}" 2>$null
  if ($existing -eq $plan.containerName) { docker rm -f $plan.containerName | Out-Null }
}
if ($plan.physicalDatabase -and $dataRoot -and (Test-Path -LiteralPath $dataRoot)) {
  Remove-Item -LiteralPath $dataRoot -Recurse -Force
}
Write-Host "Environment reset for $($plan.appId)."
""";
    }

    private static String readme(Path opsRoot) {
        String create = ps(opsRoot.resolve("Create-Environment.ps1"));
        String build = ps(opsRoot.resolve("Build-FinalApp.ps1"));
        String run = ps(opsRoot.resolve("Run-FinalApp.ps1"));
        String smoke = ps(opsRoot.resolve("Smoke-Test.ps1"));
        String print = ps(opsRoot.resolve("Print-DbConnectionInfo.ps1"));
        String stop = ps(opsRoot.resolve("Stop-Environment.ps1"));
        String reset = ps(opsRoot.resolve("Reset-Environment.ps1"));
        return """
# NPDev Generated FinalApp Runbook

1. Create environment

```powershell
pwsh.exe -NoProfile -ExecutionPolicy Bypass -File '%s'
```

2. Build FinalApp

```powershell
pwsh.exe -NoProfile -ExecutionPolicy Bypass -File '%s'
```

3. Run FinalApp

```powershell
pwsh.exe -NoProfile -ExecutionPolicy Bypass -File '%s'
```

4. Smoke-test FinalApp

```powershell
pwsh.exe -NoProfile -ExecutionPolicy Bypass -File '%s'
```

5. Open DBeaver

```powershell
pwsh.exe -NoProfile -ExecutionPolicy Bypass -File '%s'
```

6. Stop environment

```powershell
pwsh.exe -NoProfile -ExecutionPolicy Bypass -File '%s'
```

7. Reset environment if needed

```powershell
pwsh.exe -NoProfile -ExecutionPolicy Bypass -File '%s' -Confirm I_UNDERSTAND_DB_DATA_WILL_BE_DELETED
```
""".formatted(create, build, run, smoke, print, stop, reset);
    }

    private static void write(Path path, String content) throws Exception {
        Files.writeString(path, content.replace("\n", System.lineSeparator()), StandardCharsets.UTF_8);
    }

    private static void writeJson(Path path, Object value) throws Exception {
        Files.writeString(path,
                OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(value) + System.lineSeparator(),
                StandardCharsets.UTF_8);
    }

    private static boolean hasConcept(CompiledModel model, String conceptName) {
        if (model == null || conceptName == null) {
            return false;
        }
        for (CompiledConcept concept : model.getConcepts()) {
            if (conceptName.equalsIgnoreCase(concept.getName())) {
                return true;
            }
        }
        return false;
    }

    private static Path resolveBuildRoot(Path finalAppRoot) {
        Path current = finalAppRoot;
        while (current != null) {
            if (current.getFileName() != null && "Build".equalsIgnoreCase(current.getFileName().toString())) {
                return current.toAbsolutePath().normalize();
            }
            current = current.getParent();
        }
        // REG-144's family: NEVER a hardcoded author path. This used to answer
        // Path.of("D:/WorkSpace/NPDev/Build"), so an app generated anywhere that is not under a
        // directory called Build carried THIS MACHINE's drive letter to the user -- in the most
        // user-visible file NPDev produces, and in the error message telling them where to look.
        // The app's own parent is the honest answer: the toolbox lives beside the app it operates.
        return finalAppRoot.getParent() == null
                ? finalAppRoot.toAbsolutePath().normalize()
                : finalAppRoot.getParent().resolve("Build").toAbsolutePath().normalize();
    }

    private static int readInt(JsonNode root, int fallback, String... path) {
        JsonNode value = readNode(root, path);
        return value != null && value.canConvertToInt() ? value.asInt() : fallback;
    }

    private static String readText(JsonNode root, String... path) {
        JsonNode value = readNode(root, path);
        return value != null && value.isTextual() ? value.asText("") : "";
    }

    private static JsonNode readNode(JsonNode root, String... path) {
        JsonNode current = root;
        if (current == null) {
            return null;
        }
        for (String element : path) {
            current = current.path(element);
            if (current == null || current.isMissingNode() || current.isNull()) {
                return null;
            }
        }
        return current;
    }

    private static String ps(Path path) {
        return path.toAbsolutePath().normalize().toString().replace("'", "''");
    }

    private static String slash(Path path) {
        return path.toAbsolutePath().normalize().toString().replace('\\', '/');
    }
}
