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
        Path opsRoot = normalizedFinalAppRoot.getParent().resolve("_ops").toAbsolutePath().normalize();
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

    private static String createEnvironmentScript() {
        return """
$ErrorActionPreference = 'Stop'
$planPath = Join-Path $PSScriptRoot 'resolved-db-plan.json'
$plan = Get-Content -Raw -LiteralPath $planPath | ConvertFrom-Json

function Wait-PostgresReady {
  param([object]$Plan)
  for ($i = 0; $i -lt 45; $i++) {
    docker exec $Plan.containerName pg_isready -U $Plan.username | Out-Null
    if ($LASTEXITCODE -eq 0) { return }
    Start-Sleep -Seconds 2
  }
  throw "Postgres container '$($Plan.containerName)' did not become ready."
}

function Ensure-PostgresDatabase {
  param([object]$Plan)
  for ($i = 0; $i -lt 45; $i++) {
    $running = docker inspect -f "{{.State.Running}}" $Plan.containerName 2>$null
    if ($running -ne 'true') {
      Start-Sleep -Seconds 2
      continue
    }
    docker exec $Plan.containerName pg_isready -U $Plan.username | Out-Null
    if ($LASTEXITCODE -ne 0) {
      Start-Sleep -Seconds 2
      continue
    }
    $databases = docker exec -e PGPASSWORD=$($Plan.password) $Plan.containerName psql -U $Plan.username -d postgres -t -A -c "select datname from pg_database order by datname;" 2>$null
    if ($LASTEXITCODE -ne 0) {
      Start-Sleep -Seconds 2
      continue
    }
    if ($databases -contains $Plan.resolvedDatabaseName) {
      Write-Host "Verified Postgres database: $($Plan.resolvedDatabaseName)"
      return
    }
    docker exec -e PGPASSWORD=$($Plan.password) $Plan.containerName createdb -U $Plan.username $Plan.resolvedDatabaseName 2>$null
    if ($LASTEXITCODE -eq 0) {
      Start-Sleep -Seconds 1
      continue
    }
    Start-Sleep -Seconds 2
  }
  throw "Expected Postgres database '$($Plan.resolvedDatabaseName)' could not be created or verified in '$($Plan.containerName)'."
}

if ($plan.engine -eq 'InMemory') {
  Write-Host 'No physical database to create for InMemory.'
  exit 0
}

New-Item -ItemType Directory -Force -Path $plan.resolvedDataRoot | Out-Null

if ($plan.engine -eq 'Postgres') {
  docker version | Out-Null
  if ($LASTEXITCODE -ne 0) { throw 'Docker is required for generated Postgres environment creation.' }
  $existing = docker ps -a --filter "name=^/$($plan.containerName)$" --format "{{.Names}}"
  if ($existing -eq $plan.containerName) {
    docker start $plan.containerName | Out-Null
  } else {
    docker run -d --name $plan.containerName `
      -e POSTGRES_DB=$($plan.resolvedDatabaseName) `
      -e POSTGRES_USER=$($plan.username) `
      -e POSTGRES_PASSWORD=$($plan.password) `
      -p "$($plan.hostPort):$($plan.containerPort)" `
      -v "$($plan.resolvedDataRoot):/var/lib/postgresql/data" `
      postgres:16-alpine | Out-Null
  }
  Wait-PostgresReady -Plan $plan
  Ensure-PostgresDatabase -Plan $plan
  exit 0
}

if ($plan.engine -eq 'H2Local') {
  Write-Host "H2Local data root ready: $($plan.resolvedDataRoot)"
  exit 0
}

if ($plan.engine -eq 'H2Server') {
  $jar = Get-ChildItem -Path 'D:\\WorkSpace\\NPDev\\Build' -Recurse -Filter 'h2-*.jar' -ErrorAction SilentlyContinue |
    Sort-Object LastWriteTime -Descending |
    Select-Object -First 1
  if ($null -eq $jar) { throw 'Could not find an H2 jar under D:\\WorkSpace\\NPDev\\Build. Build a generated app once or restore runtimehost-libs.' }
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
  $args = @('-cp', $jar.FullName, 'org.h2.tools.Server', '-tcp', '-tcpPort', [string]$plan.hostPort, '-ifNotExists')
  $process = Start-Process -FilePath 'java' -ArgumentList $args -WorkingDirectory $plan.resolvedDataRoot -PassThru -WindowStyle Hidden -RedirectStandardOutput $stdoutLogFile -RedirectStandardError $stderrLogFile
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
if ($plan.engine -eq 'Postgres') {
  $running = docker ps --filter "name=^/$($plan.containerName)$" --format "{{.Names}}" 2>$null
  if ($running -eq $plan.containerName) { docker stop $plan.containerName | Out-Null }
  Write-Host "Postgres environment stopped: $($plan.containerName)"
  exit 0
}
if ($plan.engine -eq 'H2Server') {
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
if ($plan.engine -eq 'Postgres') {
  docker ps -a --filter "name=^/$($plan.containerName)$"
  exit 0
}
if ($plan.engine -eq 'H2Server') {
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
if ($plan.engine -eq 'InMemory') {
  Write-Host 'No physical database. Use /api/admin/storage/summary.'
  exit 0
}
if ($plan.engine -eq 'Postgres') {
  Write-Host 'DBeaver connection'
  Write-Host ''
  Write-Host 'Database type: PostgreSQL'
  Write-Host "Host: $($plan.dbeaver.host)"
  Write-Host "Port: $($plan.dbeaver.port)"
  Write-Host "Database: $($plan.dbeaver.database)"
  Write-Host "Username: $($plan.dbeaver.username)"
  Write-Host "Password: $($plan.password)"
  Write-Host "SSL: $($plan.dbeaver.ssl)"
  exit 0
}
if ($plan.engine -eq 'H2Local') {
  Write-Host 'DBeaver connection'
  Write-Host ''
  Write-Host 'Database type: H2 Embedded'
  Write-Host "JDBC URL: $($plan.jdbcUrl)"
  Write-Host "Database: $($plan.dbeaver.database)"
  Write-Host "Username: $($plan.username)"
  Write-Host "Password: $($plan.password)"
  exit 0
}
if ($plan.engine -eq 'H2Server') {
  Write-Host 'DBeaver connection'
  Write-Host ''
  Write-Host 'Database type: H2 Server'
  Write-Host "Host: $($plan.dbeaver.host)"
  Write-Host "Port: $($plan.dbeaver.port)"
  Write-Host "JDBC URL: $($plan.jdbcUrl)"
  Write-Host "Database: $($plan.dbeaver.database)"
  Write-Host "Username: $($plan.username)"
  Write-Host "Password: $($plan.password)"
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
if ($plan.engine -eq 'Postgres') {
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
        return Path.of("D:/WorkSpace/NPDev/Build").toAbsolutePath().normalize();
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
