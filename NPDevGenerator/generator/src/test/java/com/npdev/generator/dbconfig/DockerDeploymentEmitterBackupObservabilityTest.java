package com.npdev.generator.dbconfig;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R9.9 (scheduled backups + retention + restore-verification) and R9.10 (observability profile):
 * both are opt-in {@code docker-compose.yml} profiles emitted by {@link DockerDeploymentEmitter}.
 *
 * <p>R9.9's {@code backup} profile (nightly sidecar + a disposable scratch-restore verify job)
 * ships ONLY where {@link DockerEngineProfile} declares {@code backupCommand}/{@code restoreCommand}
 * (Postgres, MySQL today -- see {@code DockerDeploymentEmitterTest} for why SQL Server and the
 * embedded engines are exempt). R9.10's {@code observability} profile (Prometheus + a provisioned
 * Grafana dashboard + an Alertmanager liveness alert) ships for EVERY engine -- it only scrapes the
 * {@code app} service's actuator endpoint, never the database.
 */
class DockerDeploymentEmitterBackupObservabilityTest {

    private static Path writeDefinition(Path directory, String engine, String host, int port) throws Exception {
        Path path = directory.resolve("db.definition.json");
        Files.writeString(path, """
                {
                  "database": { "engine": "%s", "host": "%s", "port": %d, "username": "npdev",
                                 "password": "secret", "createInternalTables": true, "createBusinessTables": true },
                  "schemaLifecycle": { "strategy": "KeepExistingIfCompatible", "scope": "NpdevOwnedTablesOnly" }
                }
                """.formatted(engine, host, port));
        return path;
    }

    private static GeneratedDatabasePlan loadPlan(Path tempDir, String engine, int port) throws Exception {
        Path definitionPath = writeDefinition(tempDir, engine, "localhost", port);
        return new UserDatabaseDefinitionLoader().load(definitionPath, null);
    }

    private static Path emit(Path tempDir, String engine, int port) throws Exception {
        GeneratedDatabasePlan plan = loadPlan(tempDir, engine, port);
        Path appRoot = tempDir.resolve("app-" + engine);
        new DockerDeploymentEmitter().emit(null, appRoot, plan);
        return appRoot;
    }

    // ------------------------------------------------------------------------------------------
    // R9.9: backup profile
    // ------------------------------------------------------------------------------------------

    @Test
    void postgresGetsTheBackupProfileWithSidecarVerifyAndScratchDatabase(@TempDir Path tempDir) throws Exception {
        Path appRoot = emit(tempDir, "Postgres", 5432);
        String compose = Files.readString(appRoot.resolve("docker-compose.yml"));

        assertTrue(compose.contains("backup:"), "must declare the nightly backup sidecar service");
        assertTrue(compose.contains("backup-verify-db:"), "must declare a disposable scratch database service");
        assertTrue(compose.contains("backup-verify:"), "must declare the one-shot verify job service");
        assertTrue(compose.contains("profiles: [\"backup\"]"), "backup services must be opt-in via the 'backup' profile");
        assertTrue(compose.contains("backup-verify-scratch:"),
                "must declare the scratch database's OWN volume, separate from dbdata");
        // The scratch database must never be confused with production: it gets its own volume and
        // its own service name, and backup-verify must depend on IT, not `database`.
        assertTrue(compose.contains("PGHOST: backup-verify-db"),
                "backup-verify must connect to the scratch database, never the real one: " + compose);
        assertTrue(compose.contains("PGHOST: database"),
                "the nightly sidecar must connect to the real database service: " + compose);

        assertTrue(Files.exists(appRoot.resolve("deploy").resolve("backup-sidecar.sh")));
        assertTrue(Files.exists(appRoot.resolve("deploy").resolve("backup-verify.sh")));
    }

    @Test
    void mySqlBackupProfileUsesMySqlClientEnvNotPostgresEnv(@TempDir Path tempDir) throws Exception {
        Path appRoot = emit(tempDir, "MySQL", 3306);
        String compose = Files.readString(appRoot.resolve("docker-compose.yml"));

        assertTrue(compose.contains("MYSQL_HOST: database"), "MySQL sidecar must use MYSQL_HOST: " + compose);
        assertTrue(compose.contains("MYSQL_TCP_PORT: 3306"), "MySQL sidecar must use MYSQL_TCP_PORT: " + compose);
        assertTrue(compose.contains("MYSQL_PWD:"), "MySQL sidecar must use MYSQL_PWD: " + compose);
        assertFalse(compose.contains("PGHOST"), "MySQL compose must not leak Postgres client env: " + compose);

        String sidecar = Files.readString(appRoot.resolve("deploy").resolve("backup-sidecar.sh"));
        assertTrue(sidecar.contains("mysqldump"), "MySQL sidecar script must call mysqldump: " + sidecar);
        String verify = Files.readString(appRoot.resolve("deploy").resolve("backup-verify.sh"));
        assertTrue(verify.contains("mysql "), "MySQL verify script must call the mysql client: " + verify);
    }

    @Test
    void sqlServerGetsNoBackupProfileAtAll(@TempDir Path tempDir) throws Exception {
        // SQL Server's backupCommand/restoreCommand are deliberately empty (BACKUP DATABASE writes
        // inside the container) -- the whole backup profile (sidecar + scratch db + verify job)
        // must be absent, not merely the backup.sh/restore.sh scripts DockerDeploymentEmitterTest
        // already covers.
        Path appRoot = emit(tempDir, "SqlServer", 1433);
        String compose = Files.readString(appRoot.resolve("docker-compose.yml"));

        assertFalse(compose.contains("\n  backup:") || compose.contains("backup:\n"),
                "SQL Server must not get the backup sidecar service: " + compose);
        assertFalse(compose.contains("backup-verify"), "SQL Server must not get the verify job or scratch db: " + compose);
        assertFalse(Files.exists(appRoot.resolve("deploy").resolve("backup-sidecar.sh")));
        assertFalse(Files.exists(appRoot.resolve("deploy").resolve("backup-verify.sh")));
        // The observability profile is unrelated to the database engine and must still be present.
        assertTrue(compose.contains("actuator-proxy:"), "observability must still ship for SQL Server: " + compose);
    }

    @Test
    void embeddedEngineGetsNoBackupProfile(@TempDir Path tempDir) throws Exception {
        // H2Local has no `database` compose service at all -- there is nothing for a sidecar to dump.
        Path definitionPath = writeDefinition(tempDir, "H2Local", "", 0);
        GeneratedDatabasePlan plan = new UserDatabaseDefinitionLoader().load(definitionPath, null);
        Path appRoot = tempDir.resolve("app");
        new DockerDeploymentEmitter().emit(null, appRoot, plan);

        String compose = Files.readString(appRoot.resolve("docker-compose.yml"));
        assertFalse(compose.contains("backup-verify"), "embedded engines must not get the backup profile: " + compose);
        assertFalse(Files.exists(appRoot.resolve("deploy").resolve("backup-sidecar.sh")));
        assertTrue(compose.contains("actuator-proxy:"), "observability must still ship for embedded engines: " + compose);
    }

    @Test
    void backupSidecarScriptRetentionAndScheduleAreConfigurableWithDefaults(@TempDir Path tempDir) throws Exception {
        Path appRoot = emit(tempDir, "Postgres", 5432);
        String sidecar = Files.readString(appRoot.resolve("deploy").resolve("backup-sidecar.sh"));

        assertTrue(sidecar.contains("BACKUP_RETENTION_COUNT:-14"), "must default retention, overridable: " + sidecar);
        assertTrue(sidecar.contains("BACKUP_INTERVAL_SECONDS:-86400"), "must default to nightly (86400s), overridable: " + sidecar);
        assertTrue(sidecar.contains("while true"), "must run on a loop, not a single dump: " + sidecar);
        // Retention pruning must actually remove stale dumps, not just log about them.
        assertTrue(sidecar.contains("rm -f"), "must actually prune: " + sidecar);

        String compose = Files.readString(appRoot.resolve("docker-compose.yml"));
        assertTrue(compose.contains("BACKUP_RETENTION_COUNT: ${BACKUP_RETENTION_COUNT:-14}"),
                "compose must expose the retention knob via .env: " + compose);
        assertTrue(compose.contains("BACKUP_INTERVAL_SECONDS: ${BACKUP_INTERVAL_SECONDS:-86400}"),
                "compose must expose the schedule knob via .env: " + compose);
    }

    @Test
    void backupVerifyScriptReportsPassOrFailByParseableLogLine(@TempDir Path tempDir) throws Exception {
        Path appRoot = emit(tempDir, "Postgres", 5432);
        String verify = Files.readString(appRoot.resolve("deploy").resolve("backup-verify.sh"));

        assertTrue(verify.contains("PASS:"), "must report a parseable PASS line on success: " + verify);
        assertTrue(verify.contains("FAIL:"), "must report a parseable FAIL line on failure: " + verify);
        assertTrue(verify.contains("exit 0") && verify.contains("exit 1"),
                "pass/fail must also be the process exit code, not just log text: " + verify);
        assertTrue(verify.contains("/logs/backup-verify.log"),
                "MON-6: output must land beside the app's own logs, not only docker compose logs: " + verify);
    }

    @Test
    void backupSidecarWritesToTheAppsOwnLogsDirectory(@TempDir Path tempDir) throws Exception {
        // MON-6: "the app keeps its own logs at <app>\\logs\\" -- the sidecar output must be
        // discoverable there, not only via `docker compose logs backup`.
        Path appRoot = emit(tempDir, "Postgres", 5432);
        String sidecar = Files.readString(appRoot.resolve("deploy").resolve("backup-sidecar.sh"));
        assertTrue(sidecar.contains("/logs/backup-sidecar.log"), "must tee into /logs: " + sidecar);

        String compose = Files.readString(appRoot.resolve("docker-compose.yml"));
        assertTrue(compose.contains("./logs:/logs"), "compose must mount the app's logs dir into the sidecar: " + compose);
    }

    @Test
    void backupProfileServicesTargetTheServiceNamesTheComposeFileActuallyDeclares(@TempDir Path tempDir) throws Exception {
        // The exact seam R9.1 found diverged (compose said 'database', the scripts said 'postgres')
        // -- pin it for the NEW backup-verify-db scratch service too.
        Path appRoot = emit(tempDir, "Postgres", 5432);
        String compose = Files.readString(appRoot.resolve("docker-compose.yml"));
        assertTrue(java.util.regex.Pattern.compile("(?m)^\\s*backup-verify-db:\\s*$").matcher(compose).find(),
                "compose must declare a service literally named 'backup-verify-db': " + compose);
    }

    // ------------------------------------------------------------------------------------------
    // R9.10: observability profile
    // ------------------------------------------------------------------------------------------

    @Test
    void observabilityProfileShipsForEveryEngineIncludingEmbeddedOnes(@TempDir Path tempDir) throws Exception {
        for (String engine : new String[] {"Postgres", "MySQL", "SqlServer"}) {
            Path appRoot = emit(tempDir, engine, engine.equals("MySQL") ? 3306 : engine.equals("SqlServer") ? 1433 : 5432);
            assertObservabilityShipped(appRoot, engine);
        }
        Path definitionPath = writeDefinition(tempDir, "H2Local", "", 0);
        GeneratedDatabasePlan plan = new UserDatabaseDefinitionLoader().load(definitionPath, null);
        Path appRoot = tempDir.resolve("app-h2local");
        new DockerDeploymentEmitter().emit(null, appRoot, plan);
        assertObservabilityShipped(appRoot, "H2Local");
    }

    private static void assertObservabilityShipped(Path appRoot, String engine) throws Exception {
        String compose = Files.readString(appRoot.resolve("docker-compose.yml"));
        assertTrue(compose.contains("actuator-proxy:"), engine + ": missing actuator-proxy service");
        assertTrue(compose.contains("prometheus:"), engine + ": missing prometheus service");
        assertTrue(compose.contains("alertmanager:"), engine + ": missing alertmanager service");
        assertTrue(compose.contains("grafana:"), engine + ": missing grafana service");
        assertTrue(compose.contains("profiles: [\"observability\"]"), engine + ": observability services must be opt-in");

        Path obs = appRoot.resolve("deploy").resolve("observability");
        assertTrue(Files.exists(obs.resolve("prometheus.yml")), engine + ": missing prometheus.yml");
        assertTrue(Files.exists(obs.resolve("alert-rules.yml")), engine + ": missing alert-rules.yml");
        assertTrue(Files.exists(obs.resolve("alertmanager.yml")), engine + ": missing alertmanager.yml");
        assertTrue(Files.exists(obs.resolve("actuator-proxy.conf.template")), engine + ": missing actuator-proxy.conf.template");
        assertTrue(Files.exists(obs.resolve("grafana").resolve("provisioning").resolve("datasources").resolve("datasource.yml")),
                engine + ": missing Grafana datasource provisioning");
        assertTrue(Files.exists(obs.resolve("grafana").resolve("provisioning").resolve("dashboards").resolve("dashboards.yml")),
                engine + ": missing Grafana dashboard provider config");
        assertTrue(Files.exists(obs.resolve("grafana").resolve("dashboards").resolve("npdev-app-overview.json")),
                engine + ": missing the provisioned dashboard JSON itself");
    }

    @Test
    void prometheusScrapesTheActuatorProxyNotTheAppDirectly(@TempDir Path tempDir) throws Exception {
        // /actuator/prometheus is SUPERUSER-gated (ActuatorAdminGuardFilter) -- Prometheus cannot
        // present that header itself, so it must scrape the sidecar that holds the secret.
        Path appRoot = emit(tempDir, "Postgres", 5432);
        String prometheusYaml = Files.readString(appRoot.resolve("deploy").resolve("observability").resolve("prometheus.yml"));
        assertTrue(prometheusYaml.contains("actuator-proxy:9091"), prometheusYaml);
        assertFalse(prometheusYaml.contains("app:8080"), "must not scrape the app directly: " + prometheusYaml);
        assertFalse(prometheusYaml.contains("X-Super-User-Key") || prometheusYaml.contains("NPDEV_SUPER_USER_KEY"),
                "prometheus.yml itself must hold no secret: " + prometheusYaml);
    }

    @Test
    void actuatorProxyInjectsTheSuperUserKeyAtRuntimeNotAtGenerationTime(@TempDir Path tempDir) throws Exception {
        Path appRoot = emit(tempDir, "Postgres", 5432);
        String nginxTemplate = Files.readString(
                appRoot.resolve("deploy").resolve("observability").resolve("actuator-proxy.conf.template"));
        assertTrue(nginxTemplate.contains("X-Super-User-Key"), nginxTemplate);
        assertTrue(nginxTemplate.contains("${NPDEV_SUPER_USER_KEY}"),
                "must reference the runtime env var, never a baked-in key: " + nginxTemplate);
        assertTrue(nginxTemplate.contains("/actuator/prometheus"), nginxTemplate);

        String compose = Files.readString(appRoot.resolve("docker-compose.yml"));
        assertTrue(compose.contains("NGINX_ENVSUBST_FILTER: NPDEV_SUPER_USER_KEY"),
                "must restrict envsubst to only the one intended variable: " + compose);
        assertTrue(compose.contains("NPDEV_SUPER_USER_KEY:?"),
                "must fail loudly if the operator hasn't supplied the key yet: " + compose);
    }

    @Test
    void alertRuleFiresOnScrapeFailureAndIsWiredToAlertmanager(@TempDir Path tempDir) throws Exception {
        Path appRoot = emit(tempDir, "Postgres", 5432);
        String rules = Files.readString(appRoot.resolve("deploy").resolve("observability").resolve("alert-rules.yml"));
        assertTrue(rules.contains("up{job=\"npdev-app\"} == 0"), rules);
        assertTrue(rules.contains("alert: NPDevAppDown"), rules);

        String prometheusYaml = Files.readString(appRoot.resolve("deploy").resolve("observability").resolve("prometheus.yml"));
        assertTrue(prometheusYaml.contains("alert-rules.yml"), "prometheus must load the rule file: " + prometheusYaml);
        assertTrue(prometheusYaml.contains("alertmanager:9093"), "prometheus must be wired to alertmanager: " + prometheusYaml);

        String alertmanagerYaml = Files.readString(appRoot.resolve("deploy").resolve("observability").resolve("alertmanager.yml"));
        assertTrue(alertmanagerYaml.contains("email_configs"), "alertmanager must deliver by email: " + alertmanagerYaml);
        assertTrue(alertmanagerYaml.contains("smtp_smarthost"), alertmanagerYaml);
    }

    @Test
    void grafanaDashboardAndDatasourceAreProvisionedNotManual(@TempDir Path tempDir) throws Exception {
        Path appRoot = emit(tempDir, "Postgres", 5432);
        Path grafana = appRoot.resolve("deploy").resolve("observability").resolve("grafana");

        String datasource = Files.readString(grafana.resolve("provisioning").resolve("datasources").resolve("datasource.yml"));
        assertTrue(datasource.contains("type: prometheus"), datasource);
        assertTrue(datasource.contains("http://prometheus:9090"), datasource);

        String dashboardProvider = Files.readString(grafana.resolve("provisioning").resolve("dashboards").resolve("dashboards.yml"));
        assertTrue(dashboardProvider.contains("/var/lib/grafana/dashboards"), dashboardProvider);

        String dashboardJson = Files.readString(grafana.resolve("dashboards").resolve("npdev-app-overview.json"));
        assertTrue(dashboardJson.contains("\"up{job=\\\"npdev-app\\\"}\""), "dashboard must show the app's up/down state: " + dashboardJson);

        String compose = Files.readString(appRoot.resolve("docker-compose.yml"));
        assertTrue(compose.contains("/etc/grafana/provisioning"), "grafana service must mount the provisioning dir: " + compose);
        assertTrue(compose.contains("/var/lib/grafana/dashboards"), "grafana service must mount the dashboards dir: " + compose);
    }

    // ------------------------------------------------------------------------------------------
    // Determinism + parity
    // ------------------------------------------------------------------------------------------

    @Test
    void emissionIsByteIdenticalAcrossTwoRuns(@TempDir Path tempDir) throws Exception {
        GeneratedDatabasePlan plan = loadPlan(tempDir, "Postgres", 5432);
        Path first = tempDir.resolve("first");
        Path second = tempDir.resolve("second");
        new DockerDeploymentEmitter().emit(null, first, plan);
        new DockerDeploymentEmitter().emit(null, second, plan);

        assertEquals(Files.readString(first.resolve("docker-compose.yml")), Files.readString(second.resolve("docker-compose.yml")));
        assertEquals(Files.readString(first.resolve("deploy").resolve("backup-sidecar.sh")),
                Files.readString(second.resolve("deploy").resolve("backup-sidecar.sh")));
        assertEquals(
                Files.readString(first.resolve("deploy").resolve("observability").resolve("prometheus.yml")),
                Files.readString(second.resolve("deploy").resolve("observability").resolve("prometheus.yml")));
        assertEquals(
                Files.readString(first.resolve("deploy").resolve("observability").resolve("grafana")
                        .resolve("dashboards").resolve("npdev-app-overview.json")),
                Files.readString(second.resolve("deploy").resolve("observability").resolve("grafana")
                        .resolve("dashboards").resolve("npdev-app-overview.json")));
    }

    @Test
    void backupAndObservabilityServicesAreCountedInTheLogCapParityInvariant(@TempDir Path tempDir) throws Exception {
        // Every long-running service (backup, backup-verify-db, actuator-proxy, prometheus,
        // alertmanager, grafana) must carry BOTH restart:unless-stopped and the R9.2 log cap;
        // one-shot jobs (backup-verify) must carry NEITHER -- see DockerDeploymentEmitterTest's
        // everyComposeServiceDeclaresACappedLogDriver for the invariant this must not violate.
        Path appRoot = emit(tempDir, "Postgres", 5432);
        String compose = Files.readString(appRoot.resolve("docker-compose.yml"));

        int restartCount = countOccurrences(compose, "restart: unless-stopped");
        int loggingCount = countOccurrences(compose, "driver: json-file");
        assertEquals(restartCount, loggingCount,
                "every service with restart:unless-stopped must have exactly one matching logging block: " + compose);
        // backup-verify is the one-shot job: present, but contributing to neither count.
        assertTrue(compose.contains("backup-verify:"));
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int index = 0;
        while ((index = haystack.indexOf(needle, index)) != -1) {
            count++;
            index += needle.length();
        }
        return count;
    }
}
