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

New-Item -ItemType Directory -Force -Path $plan.resolvedDataRoot | Out-Null

if ($plan.profile.kind -eq 'server') {
  docker version | Out-Null
  if ($LASTEXITCODE -ne 0) { throw "Docker is required to create a $($plan.profile.guiLabel) environment." }
  $existing = docker ps -a --filter "name=^/$($plan.containerName)$" --format "{{.Names}}"
  if ($existing -eq $plan.containerName) {
    docker start $plan.containerName | Out-Null
  } else {
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
  Write-Host "H2Local data root ready: $($plan.resolvedDataRoot)"
  exit 0
}

if ($plan.profile.kind -eq 'embedded-server') {
  # E17: search the libs directory THIS app was generated against, never a path from the machine
  # that generated it. This used to read 'D:\\WorkSpace\\NPDev\\Build' -- the author's drive letter,
  # shipped to the user, and named again in the error message telling them where to look.
  $searchRoots = @($plan.runtimeHostLibsDir, (Join-Path $plan.finalAppPath 'build'), $plan.resolvedDataRoot) |
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
  $serverArgs = @('-cp', $jar.FullName, 'org.h2.tools.Server', '-tcp', '-tcpPort', [string]$plan.hostPort, '-ifNotExists')
  $process = Start-Process -FilePath 'java' -ArgumentList $serverArgs -WorkingDirectory $plan.resolvedDataRoot -PassThru -WindowStyle Hidden -RedirectStandardOutput $stdoutLogFile -RedirectStandardError $stderrLogFile
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
& (Join-Path $PSScriptRoot 'Create-Environment.ps1')
""";
    }

    private static String stopEnvironmentScript() {
        return """
$ErrorActionPreference = 'Stop'
$plan = Get-Content -Raw -LiteralPath (Join-Path $PSScriptRoot 'resolved-db-plan.json') | ConvertFrom-Json
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
Write-Host "Engine: $($plan.engine)"
Write-Host "Physical database: $($plan.physicalDatabase)"
Write-Host "Resolved database: $($plan.resolvedDatabaseName)"
Write-Host "Data root: $($plan.resolvedDataRoot)"
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
  Write-Host "H2Local data root exists: $(Test-Path -LiteralPath $plan.resolvedDataRoot)"
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
  Write-Host "JDBC URL: $($plan.jdbcUrl)"
  Write-Host "Database: $($plan.dbeaver.database)"
  Write-Host "Username: $($plan.username)"
  Write-Host "Password: $($plan.password)"
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
  Write-Host "Database: $($plan.dbeaver.database)"
  Write-Host "Username: $($plan.username)"
  Write-Host "Password: $($plan.password)"
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
if ($Confirm -ne 'I_UNDERSTAND_DB_DATA_WILL_BE_DELETED') {
  throw 'Reset refused. Re-run with -Confirm I_UNDERSTAND_DB_DATA_WILL_BE_DELETED'
}
$plan = Get-Content -Raw -LiteralPath (Join-Path $PSScriptRoot 'resolved-db-plan.json') | ConvertFrom-Json
& (Join-Path $PSScriptRoot 'Stop-Environment.ps1')
if ($plan.profile.kind -eq 'server') {
  $existing = docker ps -a --filter "name=^/$($plan.containerName)$" --format "{{.Names}}" 2>$null
  if ($existing -eq $plan.containerName) { docker rm -f $plan.containerName | Out-Null }
}
if ($plan.physicalDatabase -and (Test-Path -LiteralPath $plan.resolvedDataRoot)) {
  Remove-Item -LiteralPath $plan.resolvedDataRoot -Recurse -Force
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
