package com.npdev.generator.dbconfig;

import com.fasterxml.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * LNCH-7: emits a Dockerfile + docker-compose.yml + a Caddy TLS-reverse-proxy recipe into every
 * generated FinalApp, so "deploy this app" has an actual answer beyond a Windows dev machine
 * running H2-over-TCP. Config is entirely environment-variable-driven (never baked into the image),
 * so the same image is promotable across environments.
 *
 * <p>The Dockerfile packages an ALREADY-BUILT bootJar rather than running Gradle inside the image:
 * this platform's generated apps depend on locally-staged NPDev platform jars
 * ({@code runtimehost-libs}, synced via {@code sync-runtimehost-libs.ps1}), not a published Maven
 * repository, so a Docker-internal Gradle build would need that whole local jar cache copied into
 * the build stage too. Building outside Docker (as the existing {@code _ops} scripts already do)
 * and packaging the result is simpler, faster, and matches how every other NPDev build step works
 * today -- Docker's job here is packaging and orchestration, not compilation.</p>
 *
 * <p>R9.2: every LONG-RUNNING service in every emitted compose file (both {@link #dockerComposeServer}
 * and {@link #dockerComposeStandalone}, including the opt-in {@code backup}/{@code observability}
 * profile services added by R9.9/R9.10) also declares {@code logging: driver: json-file, options:
 * {max-size: 10m, max-file: 5}}. The one deliberate exception is {@code backup-verify} -- a ONE-SHOT
 * job meant to be run with {@code docker compose run --rm} and exit, not accumulate logs across
 * restarts it never has. Docker's default json-file driver has NO cap on its own -- before
 * this, a container's captured stdout/stderr (which is everything the app or an engine image logs,
 * regardless of what the runtime host's own rolling {@code logs/app.log} does INSIDE the
 * container) grew without bound on disk for as long as the container existed, the same unbounded
 * growth this item fixed for the bare-launcher path (see {@code OperationalRunbookEmitter}'s
 * {@code app-*.log} retention pruning) and for the runtime host's own file logging
 * (application.properties). 10m x 5 files = 50MB per service before the oldest segment is
 * discarded; {@code docker compose logs -f} still tails live output the same way regardless of
 * driver options. Literal, not sourced from {@link DockerEngineProfile} -- these caps apply to
 * every service (including non-database ones like {@code proxy}/{@code mailhog}/{@code minio})
 * identically regardless of engine, so there is no per-engine value to declare in
 * {@code engine-profiles.json}; putting a uniform literal in a profile would be indirection with
 * nothing behind it.</p>
 */
public final class DockerDeploymentEmitter {

    /** One newline. Built from a char code so no escape layer between here and the file
     *  can turn it into a real line break inside a string literal. */
    private static final String NEWLINE = String.valueOf((char) 10);

    public void emit(JsonNode config, Path finalAppRoot, GeneratedDatabasePlan plan) throws Exception {
        if (finalAppRoot == null) {
            return;
        }
        Path root = finalAppRoot.toAbsolutePath().normalize();
        Files.createDirectories(root);

        int serverPort = readInt(config, 8080, "runtime", "serverPort");
        String appId = plan == null ? "npdev-app" : plan.appId();
        if (appId == null || appId.isBlank()) {
            appId = "npdev-app";
        }
        String jarName = "FinalExec-0.1.0.jar";
        // Only a model generated with db.engine=Postgres actually has the JDBC/DataSource wiring
        // StartupValidator requires for npdev.runtime.mode=postgres (confirmed live: an InMemory-
        // engine app throws "DataSource bean is required when mode=postgres" even with a healthy
        // Postgres container sitting right next to it -- the two engines are baked in at generation
        // time, not swappable via env vars alone). Apps generated with InMemory/H2Local/H2Server
        // get a standalone compose that runs them in their own native (dev/test-only) storage mode.
        // E15/P1: was `plan.engine() == DatabaseEngine.POSTGRES`, which is why a MySQL app got the
        // STANDALONE compose -- no database service at all -- while its _ops toolbox threw. The
        // question is not "is this Postgres" but "does this engine run in a container we can
        // compose", and the profile answers it for every engine at once.
        DockerEngineProfile profile = plan == null ? null : DockerEngineProfiles.of(plan.engine());
        boolean serverEngine = profile != null && profile.isContainerBacked();
        // Backup ships only where the profile declares both commands (validate() enforces
        // both-or-neither). SQL Server's BACKUP DATABASE writes INSIDE the container, so its
        // restore story is a file copy rather than a pipe -- its backupCommand/restoreCommand are
        // deliberately empty, and emitting a script that produced an unusable dump would be worse
        // than emitting none.
        boolean hasBackup = serverEngine && profile.backupCommand() != null && !profile.backupCommand().isBlank()
                && profile.restoreCommand() != null && !profile.restoreCommand().isBlank();

        write(root.resolve("Dockerfile"), dockerfile(jarName, serverPort));
        String compose = serverEngine ? dockerComposeServer(appId, serverPort, profile) : dockerComposeStandalone(appId, serverPort);
        // R9.9/R9.10: both the scheduled-backup profile (server-engine apps only -- there is no
        // `database` service to back up on a standalone/embedded engine) and the observability
        // profile (every engine -- it only scrapes the `app` service) are spliced into the base
        // compose text rather than templated into dockerComposeServer/dockerComposeStandalone
        // directly, so those two methods stay focused on ONE concern (the app + its storage engine)
        // and the two opt-in profiles stay independently readable/testable. The splice point's
        // indentation is DETECTED from the base template (see topLevelIndent) rather than assumed --
        // dockerComposeServer's own top-level 'services:'/'volumes:' keys are NOT at column 0 (a
        // %s placeholder that itself starts at column 0, for the per-engine containerEnv block,
        // forces the whole text block's common-indentation strip to 0), while dockerComposeStandalone
        // has no such placeholder and IS stripped to column 0. Hardcoding either assumption here
        // would silently misindent one of the two flavors' spliced services.
        int baseIndent = topLevelIndent(compose);
        StringBuilder extraServices = new StringBuilder();
        StringBuilder extraVolumes = new StringBuilder();
        if (hasBackup) {
            extraServices.append(backupSidecarComposeBlock(profile, baseIndent));
            extraServices.append(backupVerifyDbComposeBlock(profile, baseIndent));
            extraServices.append(backupVerifyComposeBlock(profile, baseIndent));
            extraVolumes.append(line(baseIndent + 2, "backup-verify-scratch:"));
        }
        extraServices.append(observabilityProxyComposeBlock(serverPort, baseIndent));
        extraServices.append(observabilityPrometheusComposeBlock(baseIndent));
        extraServices.append(observabilityAlertmanagerComposeBlock(baseIndent));
        extraServices.append(observabilityGrafanaComposeBlock(baseIndent));
        extraVolumes.append(line(baseIndent + 2, "prometheus-data:"));
        extraVolumes.append(line(baseIndent + 2, "grafana-data:"));
        compose = insertBeforeTopLevelKey(compose, baseIndent, "volumes", extraServices.toString());
        compose = compose + extraVolumes;
        write(root.resolve("docker-compose.yml"), compose);
        String dbName = serverEngine && plan.resolvedDatabaseName() != null && !plan.resolvedDatabaseName().isBlank()
                ? plan.resolvedDatabaseName()
                : appId.replace('-', '_');
        write(root.resolve(".env.example"), serverEngine ? envExampleServer(dbName, profile) : envExampleStandalone());
        write(root.resolve("deploy").resolve("Caddyfile"), caddyfile(serverPort));
        write(root.resolve(".dockerignore"), dockerIgnore());
        if (hasBackup) {
            // LNCH-9/R9.1: backup/restore ships for every container-backed engine whose profile
            // declares the commands (Postgres, MySQL today) -- InMemory/H2Local/H2Server are
            // dev/test engines embedded in the app process (see the standalone compose comment
            // above); their "backup" story is documented file-copy semantics (docs/DEPLOYMENT.md),
            // not a script, since there's no separate service to dump.
            write(root.resolve("deploy").resolve("backup.sh"), backupScript(profile));
            write(root.resolve("deploy").resolve("restore.sh"), restoreScript(profile));
            // R9.9: the scheduled sidecar (nightly dump + retention pruning) and the scratch-restore
            // verify mode, both opt-in via `docker compose --profile backup ...` -- see the compose
            // blocks above for the services that mount and run these.
            write(root.resolve("deploy").resolve("backup-sidecar.sh"), backupSidecarScript(profile));
            write(root.resolve("deploy").resolve("backup-verify.sh"), backupVerifyScript(profile));
        }
        // R9.10: the observability profile -- opt-in via `docker compose --profile observability
        // ...` -- ships for EVERY engine (it only scrapes the `app` service's actuator endpoint, not
        // the database), so these are emitted unconditionally, unlike the backup profile above.
        // prometheus.yml/alert-rules.yml are mounted read-only VERBATIM (no runtime substitution --
        // they hold no secret): Prometheus scrapes the `actuator-proxy` sidecar, not the app
        // directly, and that sidecar is the only piece holding the per-app SUPER_USER_KEY secret --
        // see actuator-proxy.conf.template's own comment for why IT does need runtime substitution.
        write(root.resolve("deploy").resolve("observability").resolve("prometheus.yml"), observabilityPrometheusConfigYaml());
        write(root.resolve("deploy").resolve("observability").resolve("alert-rules.yml"), observabilityAlertRulesYaml());
        write(root.resolve("deploy").resolve("observability").resolve("alertmanager.yml"), observabilityAlertmanagerYaml());
        write(root.resolve("deploy").resolve("observability").resolve("actuator-proxy.conf.template"),
                observabilityActuatorProxyConfTemplate(serverPort));
        write(root.resolve("deploy").resolve("observability").resolve("grafana").resolve("provisioning")
                .resolve("datasources").resolve("datasource.yml"), observabilityGrafanaDatasourceYaml());
        write(root.resolve("deploy").resolve("observability").resolve("grafana").resolve("provisioning")
                .resolve("dashboards").resolve("dashboards.yml"), observabilityGrafanaDashboardProviderYaml());
        write(root.resolve("deploy").resolve("observability").resolve("grafana").resolve("dashboards")
                .resolve("npdev-app-overview.json"), observabilityGrafanaDashboardJson());
    }

    /** Renders one indented YAML line, ending in {@link #NEWLINE}. */
    private static String line(int indent, String content) {
        return " ".repeat(indent) + content + NEWLINE;
    }

    private static final Pattern TOP_LEVEL_COMPOSE_KEY = Pattern.compile("(?m)^( *)services:$");

    /**
     * The actual left margin of {@code dockerComposeServer}/{@code dockerComposeStandalone}'s
     * top-level {@code services:}/{@code volumes:} keys, DETECTED rather than assumed -- see the
     * caller's comment for why the two flavors do not share one hardcoded value.
     */
    private static int topLevelIndent(String compose) {
        Matcher matcher = TOP_LEVEL_COMPOSE_KEY.matcher(compose);
        if (!matcher.find()) {
            throw new IllegalStateException("compose template has no top-level 'services:' key to anchor on");
        }
        return matcher.group(1).length();
    }

    /**
     * Splices {@code block} in just before the top-level {@code key:} section of an already-rendered
     * compose YAML string, at the SAME left margin that section already uses -- used to add opt-in
     * profile services after the base {@code dockerComposeServer}/{@code dockerComposeStandalone}
     * template without threading more {@code %s} placeholders through those already-large
     * {@code .formatted()} calls.
     */
    private static String insertBeforeTopLevelKey(String yaml, int indent, String key, String block) {
        if (block.isEmpty()) {
            return yaml;
        }
        String marker = NEWLINE + " ".repeat(indent) + key + ":" + NEWLINE;
        int idx = yaml.indexOf(marker);
        if (idx < 0) {
            throw new IllegalStateException(
                    "compose template has no top-level '" + key + ":' section to splice services before");
        }
        return yaml.substring(0, idx + 1) + block + yaml.substring(idx + 1);
    }

    private static String dockerfile(String jarName, int serverPort) {
        return """
                # LNCH-7: packages an already-built bootJar (see class javadoc for why this is not a
                # multi-stage Gradle build). Build the jar first: ./gradlew bootJar (or the existing
                # _ops/Build-FinalApp.ps1), THEN docker compose build/up.
                FROM eclipse-temurin:21-jre-alpine

                # A non-root runtime user: the image never needs root once the jar is copied in.
                RUN addgroup -S npdev && adduser -S npdev -G npdev
                WORKDIR /app
                COPY build/libs/%s app.jar
                # StrictExecutionValidator (governed-mode integrity guard) needs the actual
                # npdev-generated/ source tree present on disk at runtime, not just compiled into
                # the jar -- it hashes this directory against a signature file to detect post-
                # generation tampering (confirmed live: "Strict execution requires a generated root
                # in governed mode" when this COPY was missing). It's small (JSON manifests + a
                # handful of generated .java files), so shipping it alongside the jar is cheap.
                COPY npdev-generated ./npdev-generated
                # Chown the WHOLE directory, not just the jar: docker-compose.yml mounts a named
                # volume over /app to persist SUPER_USER_KEY.txt, and Docker seeds a fresh named
                # volume from the image's existing directory content INCLUDING ownership. WORKDIR
                # creates /app as root before this RUN runs, so without this line the seeded volume
                # root stays root-owned and the non-root npdev user can't create npdev-files/ or
                # data/ under it (confirmed live: AccessDeniedException: /app/npdev-files).
                RUN chown -R npdev:npdev /app
                USER npdev

                EXPOSE %d
                ENV SERVER_PORT=%d

                # Config is entirely environment-variable-driven (see .env.example) -- nothing
                # environment-specific is baked into the image, so the same image promotes across
                # environments unchanged.
                ENTRYPOINT ["java", "-jar", "/app/app.jar"]
                """.formatted(jarName, serverPort, serverPort);
    }

    /**
     * The deployment compose, for ANY container-backed engine.
     *
     * <p>E15/P1. Everything engine-specific -- the service image, its environment, its healthcheck,
     * its data volume path and the app's JDBC URL -- comes from {@link DockerEngineProfile}. The
     * service is still called {@code database} rather than {@code postgres} so the compose file
     * reads the same on every engine, which is the point: a user following the same instructions
     * with a different engine should see the same words.
     */
    private static String dockerComposeServer(String appId, int serverPort, DockerEngineProfile profile) {
        return """
                # LNCH-7: Postgres-first deployment. `docker compose up --build` runs the app +
                # Postgres, both healthchecked, app waiting on Postgres before starting. The optional
                # `proxy` profile (`docker compose --profile proxy up`) adds a Caddy TLS-terminating
                # reverse proxy in front (see deploy/Caddyfile) -- generated apps never terminate TLS
                # themselves. The optional `objectstore` profile
                # (`docker compose --profile objectstore up`) adds a MinIO service for LNCH-14's
                # S3-compatible file-store adapter -- set NPDEV_FILESTORE_PROVIDER=objectstore in
                # .env to point the app at it instead of the default in-process file store (see
                # docs/DEPLOYMENT.md for the one-time bucket-creation step). The optional `smtp`
                # profile (`docker compose --profile smtp up`) adds a MailHog SMTP catcher for
                # LNCH-11's mail-smtp adapter -- which adapter a given app actually uses is decided
                # by the model's own capability binding (adapter: mail-inproc vs mail-smtp,
                # authored at generation time, same as persistence's repository/postgres/memory
                # choice), not by an env var; this profile just gives an app built against
                # mail-smtp a real SMTP endpoint to hit. View caught mail at http://localhost:8025.
                #
                # First run: copy .env.example to .env and set real secrets before `docker compose up`.
                name: %s

                services:
                  app:
                    build: .
                    depends_on:
                      database:
                        condition: service_healthy
                    environment:
                      SPRING_PROFILES_ACTIVE: ${SPRING_PROFILES_ACTIVE:-prod,postgres}
                      # StartupValidator requires this whenever the 'postgres' Spring profile is
                      # active (confirmed live: "Spring profile 'postgres' requires
                      # npdev.runtime.mode=postgres") -- the two are deliberately independent knobs
                      # (Spring profile selects config files, runtime.mode selects the adapter), so
                      # both must agree.
                      # `postgres` here names the JDBC STORAGE MODE, not the engine -- it is the
                      # value CapabilityAdapterResolver and PluginExecutionPolicyEvaluator test for
                      # on every JDBC engine. Which engine this app actually speaks is pinned by
                      # npdev.database.engine in application-npdev-db.properties, baked at
                      # generation time. Renaming the mode to `jdbc` would be a platform-wide
                      # breaking change with its own codemod; it is not smuggled into this one.
                      NPDEV_RUNTIME_MODE: postgres
                      NPDEV_DATABASE_ENGINE: %s
                      SPRING_DATASOURCE_URL: %s
                      SPRING_DATASOURCE_DRIVER_CLASS_NAME: %s
                      SPRING_DATASOURCE_USERNAME: ${DB_USER:-npdev}
                      SPRING_DATASOURCE_PASSWORD: ${DB_PASSWORD:?DB_PASSWORD must be set in .env}
                      NPDEV_AUTH_MODE: ${NPDEV_AUTH_MODE:-apikey}
                      # NOTE the missing underscore before KEYS: Spring Boot's relaxed env-var
                      # binding strips hyphens from the property name entirely rather than mapping
                      # them to underscores, so 'npdev.auth.api-keys' binds from NPDEV_AUTH_APIKEYS,
                      # not the more intuitive NPDEV_AUTH_API_KEYS (which would silently no-op).
                      NPDEV_AUTH_APIKEYS: ${NPDEV_AUTH_APIKEYS:?NPDEV_AUTH_APIKEYS must be set in .env}
                      # REG-9: JWT signing/verification keys, supplied by env var so no key file is
                      # baked into the image. Only consumed when NPDEV_AUTH_MODE=jwt (harmless empty
                      # in the default apikey mode), so these are optional rather than fail-fast.
                      # Same relaxed-binding hyphen-stripping as APIKEYS above: 'private-key-path'
                      # binds from NPDEV_AUTH_JWT_PRIVATEKEYPATH (no underscore before KEYPATH), NOT
                      # NPDEV_AUTH_JWT_PRIVATE_KEY_PATH (which silently no-ops). Point these at files
                      # mounted into the container (e.g. via a secrets volume), not into the image.
                      # A verify-only deployment sets only the PUBLIC key; a full issuer sets both.
                      # StartupValidator fails fast at boot if a path is set but unreadable.
                      NPDEV_AUTH_JWT_ISSUER: ${NPDEV_AUTH_JWT_ISSUER:-}
                      NPDEV_AUTH_JWT_AUDIENCE: ${NPDEV_AUTH_JWT_AUDIENCE:-}
                      NPDEV_AUTH_JWT_PUBLICKEYPATH: ${NPDEV_AUTH_JWT_PUBLICKEYPATH:-}
                      NPDEV_AUTH_JWT_PRIVATEKEYPATH: ${NPDEV_AUTH_JWT_PRIVATEKEYPATH:-}
                      # LNCH-14: defaults to the in-process file store (unchanged behavior) --
                      # set NPDEV_FILESTORE_PROVIDER=objectstore in .env (with the `objectstore`
                      # compose profile active) to switch to the S3-compatible adapter against the
                      # MinIO service below. Blank values are harmless when provider=inproc.
                      NPDEV_FILESTORE_PROVIDER: ${NPDEV_FILESTORE_PROVIDER:-inproc}
                      NPDEV_FILESTORE_OBJECTSTORE_BUCKET: ${NPDEV_FILESTORE_OBJECTSTORE_BUCKET:-npdev-files}
                      NPDEV_FILESTORE_OBJECTSTORE_ENDPOINT: ${NPDEV_FILESTORE_OBJECTSTORE_ENDPOINT:-http://minio:9000}
                      NPDEV_FILESTORE_OBJECTSTORE_REGION: ${NPDEV_FILESTORE_OBJECTSTORE_REGION:-us-east-1}
                      NPDEV_FILESTORE_OBJECTSTORE_ACCESSKEYID: ${MINIO_ROOT_USER:-}
                      NPDEV_FILESTORE_OBJECTSTORE_SECRETACCESSKEY: ${MINIO_ROOT_PASSWORD:-}
                      # LNCH-11: only consumed if this app's model bound the "mail" capability to
                      # adapter mail-smtp -- harmless if it's on mail-inproc (or unbound) instead.
                      # NPDEV_MAIL_SMTP_HOST defaults to the MailHog service below; point it
                      # elsewhere for a real mail provider.
                      NPDEV_MAIL_SMTP_HOST: ${NPDEV_MAIL_SMTP_HOST:-mailhog}
                      NPDEV_MAIL_SMTP_PORT: ${NPDEV_MAIL_SMTP_PORT:-1025}
                      NPDEV_MAIL_SMTP_USERNAME: ${NPDEV_MAIL_SMTP_USERNAME:-}
                      NPDEV_MAIL_SMTP_PASSWORD: ${NPDEV_MAIL_SMTP_PASSWORD:-}
                      NPDEV_MAIL_SMTP_FROM: ${NPDEV_MAIL_SMTP_FROM:-no-reply@example.com}
                      NPDEV_MAIL_SMTP_STARTTLS: ${NPDEV_MAIL_SMTP_STARTTLS:-false}
                    ports:
                      - "${APP_PORT:-%d}:%d"
                    volumes:
                      - npdev-files:/app/data/files
                      # The Super User key has no config-file/env-var equivalent -- SuperUserBootstrapper
                      # generates one on first boot (if none is active yet) and writes it to
                      # SUPER_USER_KEY.txt in the working directory (/app). Mounting a named volume
                      # over /app persists it across container recreation; Docker seeds a fresh named
                      # volume from the image's existing /app content on first use, so app.jar is not
                      # lost. Retrieve it with: docker compose exec app cat SUPER_USER_KEY.txt
                      - app-data:/app
                    healthcheck:
                      test: ["CMD", "wget", "-qO-", "http://localhost:%d/actuator/health"]
                      interval: 10s
                      timeout: 5s
                      retries: 10
                      start_period: 30s
                    logging:
                      driver: json-file
                      options:
                        max-size: "10m"
                        max-file: "5"
                    restart: unless-stopped

                  database:
                    image: %s
                    environment:
%s
                    volumes:
                      - dbdata:%s
                    healthcheck:
                      # The engine's OWN readiness probe, the same one the _ops toolbox uses -- so
                      # "ready" means the same thing in deployment as it does locally. start_period
                      # is per-engine: SQL Server routinely needs 30-60s, and giving it Postgres's
                      # budget reports a healthy engine as broken.
                      test: %s
                      interval: 5s
                      timeout: 5s
                      retries: 20
                      start_period: %ds
                    logging:
                      driver: json-file
                      options:
                        max-size: "10m"
                        max-file: "5"
                    restart: unless-stopped

                  proxy:
                    image: caddy:2-alpine
                    profiles: ["proxy"]
                    depends_on:
                      - app
                    ports:
                      - "80:80"
                      - "443:443"
                    volumes:
                      - ./deploy/Caddyfile:/etc/caddy/Caddyfile:ro
                      - caddy-data:/data
                    logging:
                      driver: json-file
                      options:
                        max-size: "10m"
                        max-file: "5"
                    restart: unless-stopped

                  # LNCH-14: S3-compatible object storage for the file-store-objectstore adapter
                  # (proven against a real MinIO instance in the adapter's own Testcontainers
                  # test -- this service is the same thing, wired into the deployment story).
                  # Opt in with `docker compose --profile objectstore up` AND
                  # NPDEV_FILESTORE_PROVIDER=objectstore in .env; the app ignores this service
                  # entirely otherwise. The bucket is NOT auto-created -- see docs/DEPLOYMENT.md
                  # for the one-time `mc mb` step after first bringing MinIO up.
                  minio:
                    image: minio/minio:RELEASE.2024-08-29T01-40-52Z
                    profiles: ["objectstore"]
                    command: server /data --console-address ":9001"
                    environment:
                      MINIO_ROOT_USER: ${MINIO_ROOT_USER:-npdev}
                      MINIO_ROOT_PASSWORD: ${MINIO_ROOT_PASSWORD:?MINIO_ROOT_PASSWORD must be set in .env to use the objectstore profile}
                    volumes:
                      - minio-data:/data
                    healthcheck:
                      test: ["CMD", "mc", "ready", "local"]
                      interval: 5s
                      timeout: 5s
                      retries: 10
                    logging:
                      driver: json-file
                      options:
                        max-size: "10m"
                        max-file: "5"
                    restart: unless-stopped

                  # LNCH-11: SMTP catcher for the mail-smtp adapter (proven against a real
                  # GreenMail instance in the adapter's own test -- MailHog here is the same idea,
                  # wired into the deployment story). Opt in with `docker compose --profile smtp
                  # up`; only reachable by an app whose model bound "mail" to adapter mail-smtp
                  # (see NPDEV_MAIL_SMTP_HOST above). Caught mail is never actually delivered --
                  # view it at http://localhost:8025.
                  mailhog:
                    image: mailhog/mailhog:v1.0.1
                    profiles: ["smtp"]
                    ports:
                      - "8025:8025"
                    logging:
                      driver: json-file
                      options:
                        max-size: "10m"
                        max-file: "5"
                    restart: unless-stopped

                volumes:
                  dbdata:
                  npdev-files:
                  app-data:
                  caddy-data:
                  minio-data:
                """.formatted(
                appId,
                profile.engine().externalName(),
                composeJdbcUrl(profile),
                profile.driverClass(),
                serverPort, serverPort, serverPort,
                profile.composeImage(),
                composeEnvBlock(profile),
                profile.dataVolumePath(),
                composeHealthcheck(profile),
                profile.readyProbe().timeoutSeconds());
    }

    /**
     * The app's JDBC URL inside the compose network -- the profile's own template, with the service
     * name as host.
     *
     * <p>Built from {@link DockerEngineProfile#jdbcUrlTemplate()} rather than hand-written per
     * engine, because it is the SAME template the runtime uses. Two spellings of one URL is how they
     * come to disagree -- MySQL's needs {@code characterEncoding} and SQL Server's needs
     * {@code encrypt}, and a compose-only copy would quietly lose them.
     */
    private static String composeJdbcUrl(DockerEngineProfile profile) {
        return profile.jdbcUrlTemplate()
                .replace("{host}", "database")
                .replace("{port}", String.valueOf(profile.defaultPort()))
                .replace("{database}", "${DB_NAME:-npdev}");
    }

    /** The engine's container environment, indented for the compose service block. */
    private static String composeEnvBlock(DockerEngineProfile profile) {
        StringBuilder out = new StringBuilder();
        profile.containerEnv().forEach((name, value) -> {
            String resolved = value
                    .replace("{database}", "${DB_NAME:-npdev}")
                    .replace("{username}", "${DB_USER:-npdev}")
                    .replace("{password}", "${DB_PASSWORD:?DB_PASSWORD must be set in .env}");
            out.append("                      ").append(name).append(": ").append(resolved).append("\n");
        });
        // Trailing newline would produce a blank line inside the YAML block; the template supplies
        // the line break after the placeholder.
        return out.length() == 0 ? "" : out.substring(0, out.length() - 1);
    }

    /** The engine's readiness probe as a compose healthcheck exec array. */
    private static String composeHealthcheck(DockerEngineProfile profile) {
        StringBuilder out = new StringBuilder("[\"CMD\"");
        for (String part : profile.readyProbe().exec()) {
            String resolved = part
                    .replace("{username}", "${DB_USER:-npdev}")
                    .replace("{password}", "${DB_PASSWORD}")
                    .replace("{database}", "${DB_NAME:-npdev}");
            out.append(", \"").append(resolved.replace("\"", "\\\"")).append('"');
        }
        return out.append(']').toString();
    }

    private static String dockerComposeStandalone(String appId, int serverPort) {
        return """
                # LNCH-7: this app was generated with a non-Postgres storage engine (InMemory,
                # H2Local, or H2Server) -- those are dev/test engines, embedded in the app process
                # itself, not a separately deployable service, so there is no Postgres container
                # here. This compose packages the app standalone for containerized dev/test use.
                # To deploy this app with the Postgres-first production path (a separate Postgres
                # container, durable across restarts), regenerate it with db.engine=Postgres in
                # db.definition.json -- see docs/DEPLOYMENT.md.
                #
                # The optional `proxy` profile (`docker compose --profile proxy up`) adds a Caddy
                # TLS-terminating reverse proxy in front (see deploy/Caddyfile). The optional
                # `smtp` profile (`docker compose --profile smtp up`) adds a MailHog SMTP catcher
                # for LNCH-11's mail-smtp adapter -- which adapter a given app actually uses is
                # decided by the model's own capability binding, not an env var; view caught mail
                # at http://localhost:8025.
                #
                # First run: copy .env.example to .env and set real secrets before `docker compose up`.
                name: %s

                services:
                  app:
                    build: .
                    environment:
                      SPRING_PROFILES_ACTIVE: ${SPRING_PROFILES_ACTIVE:-prod}
                      NPDEV_AUTH_MODE: ${NPDEV_AUTH_MODE:-apikey}
                      # NOTE the missing underscore before KEYS: Spring Boot's relaxed env-var
                      # binding strips hyphens from the property name entirely rather than mapping
                      # them to underscores, so 'npdev.auth.api-keys' binds from NPDEV_AUTH_APIKEYS,
                      # not the more intuitive NPDEV_AUTH_API_KEYS (which would silently no-op).
                      NPDEV_AUTH_APIKEYS: ${NPDEV_AUTH_APIKEYS:?NPDEV_AUTH_APIKEYS must be set in .env}
                      # REG-9: JWT signing/verification keys, supplied by env var so no key file is
                      # baked into the image. Only consumed when NPDEV_AUTH_MODE=jwt (harmless empty
                      # in the default apikey mode), so these are optional rather than fail-fast.
                      # Same relaxed-binding hyphen-stripping as APIKEYS above: 'private-key-path'
                      # binds from NPDEV_AUTH_JWT_PRIVATEKEYPATH (no underscore before KEYPATH), NOT
                      # NPDEV_AUTH_JWT_PRIVATE_KEY_PATH (which silently no-ops). Point these at files
                      # mounted into the container (e.g. via a secrets volume), not into the image.
                      # A verify-only deployment sets only the PUBLIC key; a full issuer sets both.
                      # StartupValidator fails fast at boot if a path is set but unreadable.
                      NPDEV_AUTH_JWT_ISSUER: ${NPDEV_AUTH_JWT_ISSUER:-}
                      NPDEV_AUTH_JWT_AUDIENCE: ${NPDEV_AUTH_JWT_AUDIENCE:-}
                      NPDEV_AUTH_JWT_PUBLICKEYPATH: ${NPDEV_AUTH_JWT_PUBLICKEYPATH:-}
                      NPDEV_AUTH_JWT_PRIVATEKEYPATH: ${NPDEV_AUTH_JWT_PRIVATEKEYPATH:-}
                      # LNCH-11: only consumed if this app's model bound the "mail" capability to
                      # adapter mail-smtp -- harmless if it's on mail-inproc (or unbound) instead.
                      # NPDEV_MAIL_SMTP_HOST defaults to the MailHog service below; point it
                      # elsewhere for a real mail provider.
                      NPDEV_MAIL_SMTP_HOST: ${NPDEV_MAIL_SMTP_HOST:-mailhog}
                      NPDEV_MAIL_SMTP_PORT: ${NPDEV_MAIL_SMTP_PORT:-1025}
                      NPDEV_MAIL_SMTP_USERNAME: ${NPDEV_MAIL_SMTP_USERNAME:-}
                      NPDEV_MAIL_SMTP_PASSWORD: ${NPDEV_MAIL_SMTP_PASSWORD:-}
                      NPDEV_MAIL_SMTP_FROM: ${NPDEV_MAIL_SMTP_FROM:-no-reply@example.com}
                      NPDEV_MAIL_SMTP_STARTTLS: ${NPDEV_MAIL_SMTP_STARTTLS:-false}
                    ports:
                      - "${APP_PORT:-%d}:%d"
                    volumes:
                      - npdev-files:/app/data/files
                      # The Super User key has no config-file/env-var equivalent -- SuperUserBootstrapper
                      # generates one on first boot (if none is active yet) and writes it to
                      # SUPER_USER_KEY.txt in the working directory (/app). Mounting a named volume
                      # over /app persists it across container recreation; Docker seeds a fresh named
                      # volume from the image's existing /app content on first use, so app.jar is not
                      # lost. Retrieve it with: docker compose exec app cat SUPER_USER_KEY.txt
                      - app-data:/app
                    healthcheck:
                      test: ["CMD", "wget", "-qO-", "http://localhost:%d/actuator/health"]
                      interval: 10s
                      timeout: 5s
                      retries: 10
                      start_period: 30s
                    logging:
                      driver: json-file
                      options:
                        max-size: "10m"
                        max-file: "5"
                    restart: unless-stopped

                  proxy:
                    image: caddy:2-alpine
                    profiles: ["proxy"]
                    depends_on:
                      - app
                    ports:
                      - "80:80"
                      - "443:443"
                    volumes:
                      - ./deploy/Caddyfile:/etc/caddy/Caddyfile:ro
                      - caddy-data:/data
                    logging:
                      driver: json-file
                      options:
                        max-size: "10m"
                        max-file: "5"
                    restart: unless-stopped

                  # LNCH-11: SMTP catcher for the mail-smtp adapter -- opt in with `docker compose
                  # --profile smtp up`; only reachable by an app whose model bound "mail" to
                  # adapter mail-smtp (see NPDEV_MAIL_SMTP_HOST above). Caught mail is never
                  # actually delivered -- view it at http://localhost:8025.
                  mailhog:
                    image: mailhog/mailhog:v1.0.1
                    profiles: ["smtp"]
                    ports:
                      - "8025:8025"
                    logging:
                      driver: json-file
                      options:
                        max-size: "10m"
                        max-file: "5"
                    restart: unless-stopped

                volumes:
                  npdev-files:
                  app-data:
                  caddy-data:
                """.formatted(appId, serverPort, serverPort, serverPort);
    }

    private static String envExampleStandalone() {
        return """
                # LNCH-7: copy this file to .env and fill in real values before `docker compose up`.
                # Never commit a real .env -- it holds secrets.
                # This app uses a non-Postgres (dev/test) storage engine -- see the comment atop
                # docker-compose.yml to switch to the Postgres-first production path.

                APP_PORT=8080
                SPRING_PROFILES_ACTIVE=prod
                NPDEV_AUTH_MODE=apikey
                # Format: key=tenant:actor:ROLE1|ROLE2;another-key=tenant:actor:ROLE
                # NOTE: the env var name has no underscore before "APIKEYS" -- Spring Boot's relaxed
                # binding strips hyphens from 'npdev.auth.api-keys' rather than mapping them to
                # underscores. See the comment in docker-compose.yml if this looks like a typo.
                NPDEV_AUTH_APIKEYS=change-me=prod:deploy:ADMIN

                # REG-9: JWT keys -- only used when NPDEV_AUTH_MODE=jwt (leave blank for apikey mode).
                # Supply keys via mounted files so nothing is baked into the image. Same relaxed-binding
                # hyphen stripping as APIKEYS: use NPDEV_AUTH_JWT_PRIVATEKEYPATH / _PUBLICKEYPATH (no
                # underscore before KEYPATH), NOT ..._PRIVATE_KEY_PATH (silently no-ops). A verify-only
                # deployment sets only the PUBLIC key; a full token issuer sets both. Startup fails fast
                # if a path is set but unreadable.
                # NPDEV_AUTH_JWT_ISSUER=https://issuer.example.com
                # NPDEV_AUTH_JWT_AUDIENCE=npdev-runtime
                # NPDEV_AUTH_JWT_PUBLICKEYPATH=/run/secrets/jwt-public.pem
                # NPDEV_AUTH_JWT_PRIVATEKEYPATH=/run/secrets/jwt-private.pem

                # The ControlPanel Super User key is NOT set here -- it has no config property at all.
                # SuperUserBootstrapper generates one automatically on first boot (if none is active
                # yet) and writes it to SUPER_USER_KEY.txt in the app container's working directory.
                # After first `docker compose up`, retrieve it with:
                #   docker compose exec app cat SUPER_USER_KEY.txt

                # LNCH-11: SMTP config for the mail-smtp adapter -- OPTIONAL, only consumed if this
                # app's model bound the "mail" capability to adapter mail-smtp. Defaults point at
                # the MailHog catcher (`docker compose --profile smtp up`); caught mail is never
                # actually delivered -- view it at http://localhost:8025.
                # NPDEV_MAIL_SMTP_HOST=mailhog
                # NPDEV_MAIL_SMTP_PORT=1025
                # NPDEV_MAIL_SMTP_FROM=no-reply@example.com
                """;
    }

    /**
     * The {@code .env.example} for any container-backed engine.
     *
     * <p>E15/P1. The variables are named {@code DB_*} rather than {@code POSTGRES_*} so the file
     * reads identically on every engine -- that is the parity requirement, not cosmetics: a user
     * following the same instructions with a different engine must see the same words. The engine's
     * own name appears once, as a comment, so they still know what they are running.
     */
    private static String envExampleServer(String dbName, DockerEngineProfile profile) {
        return """
                # LNCH-7: copy this file to .env and fill in real values before `docker compose up`.
                # Never commit a real .env -- it holds secrets.

                # Database (%s)
                # DB_NAME defaults to this app's resolved database name (db.definition.json /
                # resolved-db-plan.json) -- DatabaseIdentityStartupValidator refuses to start if the
                # name it connects to doesn't match what the model was generated against (confirmed
                # live: "Configured database is 'X', but connected database is 'Y'"). If
                # db.definition.json leaves databaseName unset, the generator appends a fresh
                # timestamp on every regeneration -- set an explicit databaseName there for a stable
                # production identity across redeploys.
                DB_NAME=%s
                DB_USER=npdev
                DB_PASSWORD=change-me-to-a-real-secret%s

                # App
                APP_PORT=8080
                SPRING_PROFILES_ACTIVE=prod,postgres
                NPDEV_AUTH_MODE=apikey
                # Format: key=tenant:actor:ROLE1|ROLE2;another-key=tenant:actor:ROLE
                # NOTE: the env var name has no underscore before "APIKEYS" -- Spring Boot's relaxed
                # binding strips hyphens from 'npdev.auth.api-keys' rather than mapping them to
                # underscores. See the comment in docker-compose.yml if this looks like a typo.
                NPDEV_AUTH_APIKEYS=change-me=prod:deploy:ADMIN

                # REG-9: JWT keys -- only used when NPDEV_AUTH_MODE=jwt (leave blank for apikey mode).
                # Supply keys via mounted files so nothing is baked into the image. Same relaxed-binding
                # hyphen stripping as APIKEYS: use NPDEV_AUTH_JWT_PRIVATEKEYPATH / _PUBLICKEYPATH (no
                # underscore before KEYPATH), NOT ..._PRIVATE_KEY_PATH (silently no-ops). A verify-only
                # deployment sets only the PUBLIC key; a full token issuer sets both. Startup fails fast
                # if a path is set but unreadable.
                # NPDEV_AUTH_JWT_ISSUER=https://issuer.example.com
                # NPDEV_AUTH_JWT_AUDIENCE=npdev-runtime
                # NPDEV_AUTH_JWT_PUBLICKEYPATH=/run/secrets/jwt-public.pem
                # NPDEV_AUTH_JWT_PRIVATEKEYPATH=/run/secrets/jwt-private.pem

                # The ControlPanel Super User key is NOT set here -- it has no config property at all.
                # SuperUserBootstrapper generates one automatically on first boot (if none is active
                # yet) and writes it to SUPER_USER_KEY.txt in the app container's working directory.
                # After first `docker compose up`, retrieve it with:
                #   docker compose exec app cat SUPER_USER_KEY.txt

                # LNCH-14: object storage (MinIO) -- OPTIONAL. Leave NPDEV_FILESTORE_PROVIDER unset
                # (defaults to inproc) unless you bring the `objectstore` compose profile up
                # (`docker compose --profile objectstore up`). MINIO_ROOT_USER/PASSWORD double as
                # both the MinIO server's own admin credentials AND the S3 access key/secret the
                # app authenticates with -- see docs/DEPLOYMENT.md for the one-time bucket-creation
                # step MinIO needs after its first boot.
                # NPDEV_FILESTORE_PROVIDER=objectstore
                MINIO_ROOT_USER=npdev
                MINIO_ROOT_PASSWORD=change-me-to-a-real-secret

                # LNCH-11: SMTP config for the mail-smtp adapter -- OPTIONAL, only consumed if this
                # app's model bound the "mail" capability to adapter mail-smtp. Defaults point at
                # the MailHog catcher (`docker compose --profile smtp up`); caught mail is never
                # actually delivered -- view it at http://localhost:8025.
                # NPDEV_MAIL_SMTP_HOST=mailhog
                # NPDEV_MAIL_SMTP_PORT=1025
                # NPDEV_MAIL_SMTP_FROM=no-reply@example.com
                """.formatted(profile.guiLabel(), dbName, quirkNotes(profile));
    }

    private static String caddyfile(int serverPort) {
        return """
                # LNCH-7: TLS-terminating reverse proxy recipe -- generated apps never terminate TLS
                # themselves (see docs/DEPLOYMENT.md). Replace ':443' with your real domain once you
                # have one; Caddy then obtains a real Let's Encrypt certificate automatically with no
                # further config. Until then, 'tls internal' issues Caddy's own locally-trusted
                # certificate, so HTTPS termination itself is provable without owning a domain.

                :443 {
                	tls internal
                	reverse_proxy app:%d
                }
                """.formatted(serverPort);
    }

    /**
     * The engine's measured quirks, as comments in the .env the operator is already editing.
     *
     * <p>Declared at the point of choice rather than discovered later -- MySQL's utf8mb4 is not
     * optional, and SQL Server's SA password policy makes the container EXIT during startup, which
     * surfaces downstream as "connection refused" and reads like a networking fault.
     */
    private static String quirkNotes(DockerEngineProfile profile) {
        if (profile.quirks() == null || profile.quirks().isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder(NEWLINE + NEWLINE + "                # Notes for ")
                .append(profile.guiLabel()).append(":");
        for (String quirk : profile.quirks()) {
            out.append(NEWLINE).append("                #   - ").append(quirk);
        }
        return out.toString();
    }

    /**
     * R9.1: engine-generic -- {@code backupCommand} comes from the profile ({@code pg_dump} for
     * Postgres, {@code mysqldump} for MySQL), and it execs into the {@code database} service, the
     * name every compose file in this class actually emits (see {@link #dockerComposeServer}).
     * Previously hardcoded {@code postgres} (a service name that has never existed in the emitted
     * compose file -- it is always {@code database}) and read {@code POSTGRES_DB}/{@code
     * POSTGRES_USER}, variables {@code .env.example} never defines (it defines {@code DB_NAME}/
     * {@code DB_USER}/{@code DB_PASSWORD}, see {@link #envExampleServer}) -- so every emitted
     * backup.sh failed on first use, on every engine, always.
     */
    private static String backupScript(DockerEngineProfile profile) {
        return """
                #!/usr/bin/env bash
                # LNCH-9/R9.1: dumps the compose stack's %s database, run INSIDE the `database`
                # service (docker compose exec -T), so it works identically regardless of whether
                # the client tools are installed on the host. Run from the FinalApp root (where
                # docker-compose.yml and .env live).
                set -euo pipefail
                cd "$(dirname "$0")/.."

                if [ ! -f .env ]; then
                    echo "backup.sh: .env not found -- copy .env.example to .env first" >&2
                    exit 1
                fi
                set -a
                source .env
                set +a
                DB_NAME="${DB_NAME:-npdev}"
                DB_USER="${DB_USER:-npdev}"

                mkdir -p backups
                out="backups/${DB_NAME}-$(date -u +%%Y%%m%%dT%%H%%M%%SZ).sql"
                echo "Backing up ${DB_NAME} to ${out} ..."
                docker compose exec -T database %s > "$out"
                echo "Done: ${out}"
                """.formatted(profile.guiLabel(), profile.backupCommand());
    }

    /**
     * R9.1: engine-generic restore, the {@code restoreCommand} twin of {@link #backupScript}. See
     * that method's javadoc for what was broken before -- the same service-name and env-var
     * defects, doubled, since restore is destructive and a silently-wrong target is worse than a
     * silently-wrong source.
     */
    private static String restoreScript(DockerEngineProfile profile) {
        return """
                #!/usr/bin/env bash
                # LNCH-9/R9.1: restores a `backup.sh` dump into the compose stack's %s database.
                # DESTRUCTIVE: this replaces the target database's contents. Requires an explicit
                # --yes flag as a deliberate confirmation step -- there is no prompt to bypass by
                # accident in a scripted/CI context.
                set -euo pipefail
                cd "$(dirname "$0")/.."

                dump_file="${1:-}"
                confirm="${2:-}"
                if [ -z "$dump_file" ] || [ "$confirm" != "--yes" ]; then
                    echo "Usage: deploy/restore.sh <dump-file.sql> --yes" >&2
                    echo "  (the --yes flag is required: this REPLACES the target database's data)" >&2
                    exit 1
                fi
                if [ ! -f "$dump_file" ]; then
                    echo "restore.sh: dump file not found: ${dump_file}" >&2
                    exit 1
                fi
                if [ ! -f .env ]; then
                    echo "restore.sh: .env not found -- copy .env.example to .env first" >&2
                    exit 1
                fi
                set -a
                source .env
                set +a
                DB_NAME="${DB_NAME:-npdev}"
                DB_USER="${DB_USER:-npdev}"

                echo "Restoring ${dump_file} into ${DB_NAME} ..."
                docker compose exec -T database %s < "$dump_file"
                echo "Done. Restart the app so it re-validates against the restored data: docker compose restart app"
                """.formatted(profile.guiLabel(), profile.restoreCommand());
    }

    // ------------------------------------------------------------------------------------------
    // R9.9: scheduled backups + retention + restore-verification (compose `backup` profile).
    // ------------------------------------------------------------------------------------------

    private static final int BACKUP_RETENTION_COUNT_DEFAULT = 14;
    private static final int BACKUP_INTERVAL_SECONDS_DEFAULT = 86400; // nightly

    /** The engine's {@code containerEnv} (its OWN init vars -- database/username/password), as
     *  indented compose YAML lines starting at {@code indent}. The generic twin of the fixed-column
     *  {@link #composeEnvBlock}, used where the splice point's column isn't known until runtime. */
    private static String composeContainerEnvLines(DockerEngineProfile profile, int indent) {
        StringBuilder out = new StringBuilder();
        profile.containerEnv().forEach((name, value) -> {
            String resolved = value
                    .replace("{database}", "${DB_NAME:-npdev}")
                    .replace("{username}", "${DB_USER:-npdev}")
                    .replace("{password}", "${DB_PASSWORD:?DB_PASSWORD must be set in .env}");
            out.append(line(indent, name + ": " + resolved));
        });
        return out.toString();
    }

    /**
     * The engine's {@code backupClientEnv} (R9.9) resolved for a specific compose service as the
     * connection target -- {@code hostService} is a compose service name (e.g. {@code database} or
     * {@code backup-verify-db}), never a real hostname, since the backup/verify sidecars only ever
     * reach the engine over the compose network.
     */
    private static String composeClientEnvLines(DockerEngineProfile profile, String hostService, int indent) {
        StringBuilder out = new StringBuilder();
        profile.backupClientEnv().forEach((name, value) -> {
            String resolved = value
                    .replace("{host}", hostService)
                    .replace("{port}", String.valueOf(profile.defaultPort()))
                    .replace("{password}", "${DB_PASSWORD:?DB_PASSWORD must be set in .env}");
            out.append(line(indent, name + ": " + resolved));
        });
        return out.toString();
    }

    /** A {@code driver: json-file} logging block matching {@link #dockerComposeServer}'s per-service
     *  cap (R9.2), at the given property-level indent. */
    private static String composeLoggingBlock(int propIndent, int childIndent, int grandIndent) {
        StringBuilder out = new StringBuilder();
        out.append(line(propIndent, "logging:"));
        out.append(line(childIndent, "driver: json-file"));
        out.append(line(childIndent, "options:"));
        out.append(line(grandIndent, "max-size: \"10m\""));
        out.append(line(grandIndent, "max-file: \"5\""));
        return out.toString();
    }

    /**
     * The nightly backup sidecar (long-running, so it carries {@code restart: unless-stopped} +
     * R9.2's log cap like every other persistent service in this file). Runs {@code backup-sidecar.sh}
     * against the SAME {@code database} service {@code backup.sh} targets, over the compose network
     * (see {@code backupClientEnv}'s javadoc in {@link DockerEngineProfile} for why that needs
     * different env vars than the {@code docker compose exec} path).
     */
    private static String backupSidecarComposeBlock(DockerEngineProfile profile, int base) {
        int svc = base + 2;
        int prop = base + 4;
        int child = base + 6;
        int grand = base + 8;
        StringBuilder out = new StringBuilder();
        out.append(line(svc, "backup:"));
        out.append(line(prop, "# R9.9: nightly dump + retention pruning, opt-in via `docker compose --profile backup up -d backup`."));
        out.append(line(prop, "image: " + profile.composeImage()));
        out.append(line(prop, "profiles: [\"backup\"]"));
        out.append(line(prop, "depends_on:"));
        out.append(line(child, "database:"));
        out.append(line(grand, "condition: service_healthy"));
        out.append(line(prop, "environment:"));
        out.append(line(child, "DB_NAME: ${DB_NAME:-npdev}"));
        out.append(line(child, "DB_USER: ${DB_USER:-npdev}"));
        out.append(line(child, "BACKUP_RETENTION_COUNT: ${BACKUP_RETENTION_COUNT:-" + BACKUP_RETENTION_COUNT_DEFAULT + "}"));
        out.append(line(child, "BACKUP_INTERVAL_SECONDS: ${BACKUP_INTERVAL_SECONDS:-" + BACKUP_INTERVAL_SECONDS_DEFAULT + "}"));
        out.append(composeClientEnvLines(profile, "database", child));
        out.append(line(prop, "entrypoint: [\"/bin/sh\", \"/deploy/backup-sidecar.sh\"]"));
        out.append(line(prop, "volumes:"));
        out.append(line(child, "- ./deploy/backup-sidecar.sh:/deploy/backup-sidecar.sh:ro"));
        out.append(line(child, "- ./backups:/backups"));
        out.append(line(child, "# MON-6: sidecar output lands beside the app's own logs, not only in `docker compose logs`."));
        out.append(line(child, "- ./logs:/logs"));
        out.append(composeLoggingBlock(prop, child, grand));
        out.append(line(prop, "restart: unless-stopped"));
        out.append(NEWLINE);
        return out.toString();
    }

    /**
     * A DISPOSABLE database, never the real {@code database} service, that {@code backup-verify}
     * restores the latest dump into -- proving a dump actually restores without touching production
     * data. Its own volume ({@code backup-verify-scratch}) is separate from {@code dbdata}; wipe it
     * with {@code docker compose --profile backup down -v}.
     */
    private static String backupVerifyDbComposeBlock(DockerEngineProfile profile, int base) {
        int svc = base + 2;
        int prop = base + 4;
        int child = base + 6;
        int grand = base + 8;
        StringBuilder out = new StringBuilder();
        out.append(line(svc, "backup-verify-db:"));
        out.append(line(prop, "# R9.9: a DISPOSABLE database for the restore-verification below -- never the real `database` service."));
        out.append(line(prop, "image: " + profile.composeImage()));
        out.append(line(prop, "profiles: [\"backup\"]"));
        out.append(line(prop, "environment:"));
        out.append(composeContainerEnvLines(profile, child));
        out.append(line(prop, "volumes:"));
        out.append(line(child, "- backup-verify-scratch:" + profile.dataVolumePath()));
        out.append(line(prop, "healthcheck:"));
        out.append(line(child, "test: " + composeHealthcheck(profile)));
        out.append(line(child, "interval: 5s"));
        out.append(line(child, "timeout: 5s"));
        out.append(line(child, "retries: 20"));
        out.append(line(child, "start_period: " + profile.readyProbe().timeoutSeconds() + "s"));
        out.append(composeLoggingBlock(prop, child, grand));
        out.append(line(prop, "restart: unless-stopped"));
        out.append(NEWLINE);
        return out.toString();
    }

    /**
     * The verify-mode job itself -- a ONE-SHOT container (no {@code restart:}, so it is exempt from
     * R9.2's per-service log-cap count in {@code DockerDeploymentEmitterTest}, the same way a job
     * that runs once and exits legitimately differs from every long-running service in this file).
     * Run with {@code docker compose --profile backup run --rm backup-verify}; its exit code (and the
     * PASS/FAIL line in its output -- see {@code backup-verify.sh}) is the verification result.
     */
    private static String backupVerifyComposeBlock(DockerEngineProfile profile, int base) {
        int svc = base + 2;
        int prop = base + 4;
        int child = base + 6;
        int grand = base + 8;
        StringBuilder out = new StringBuilder();
        out.append(line(svc, "backup-verify:"));
        out.append(line(prop, "# R9.9: restores the LATEST dump into backup-verify-db and reports pass/fail. One-shot:"));
        out.append(line(prop, "# `docker compose --profile backup run --rm backup-verify`."));
        out.append(line(prop, "image: " + profile.composeImage()));
        out.append(line(prop, "profiles: [\"backup\"]"));
        out.append(line(prop, "depends_on:"));
        out.append(line(child, "backup-verify-db:"));
        out.append(line(grand, "condition: service_healthy"));
        out.append(line(prop, "environment:"));
        out.append(line(child, "DB_NAME: ${DB_NAME:-npdev}"));
        out.append(line(child, "DB_USER: ${DB_USER:-npdev}"));
        out.append(composeClientEnvLines(profile, "backup-verify-db", child));
        out.append(line(prop, "entrypoint: [\"/bin/sh\", \"/deploy/backup-verify.sh\"]"));
        out.append(line(prop, "volumes:"));
        out.append(line(child, "- ./deploy/backup-verify.sh:/deploy/backup-verify.sh:ro"));
        out.append(line(child, "- ./backups:/backups:ro"));
        out.append(line(child, "- ./logs:/logs"));
        out.append(NEWLINE);
        return out.toString();
    }

    /**
     * R9.9: the nightly loop -- dumps {@code database} on an interval, prunes dumps beyond the
     * retention cap. Engine-generic: {@code %s}/{@code %s} below are {@code profile.guiLabel()} and
     * {@code profile.backupCommand()}, the SAME command {@code backup.sh} uses (see that method's
     * javadoc for what R9.1 fixed) -- only the connection path differs (env vars, not
     * {@code docker compose exec}), per {@code backupClientEnv}.
     */
    private static String backupSidecarScript(DockerEngineProfile profile) {
        return """
                #!/bin/sh
                # R9.9: nightly backup sidecar for the compose `backup` profile -- dumps the %s
                # `database` service on a schedule and prunes dumps beyond the retention cap. Uses the
                # SAME backup command backup.sh uses, connecting over the compose network instead of
                # `docker compose exec` (see DockerEngineProfile#backupClientEnv).
                set -eu

                DB_NAME="${DB_NAME:-npdev}"
                BACKUP_DIR="${BACKUP_DIR:-/backups}"
                RETENTION_COUNT="${BACKUP_RETENTION_COUNT:-14}"
                INTERVAL_SECONDS="${BACKUP_INTERVAL_SECONDS:-86400}"
                LOG_FILE="/logs/backup-sidecar.log"

                mkdir -p "$BACKUP_DIR" /logs

                log() {
                    msg="[backup-sidecar] $(date -u +%%Y-%%m-%%dT%%H:%%M:%%SZ) $1"
                    printf '%%s\\n' "$msg" | tee -a "$LOG_FILE"
                }

                prune() {
                    count=$(ls -1t "$BACKUP_DIR"/*.sql 2>/dev/null | wc -l)
                    if [ "$count" -gt "$RETENTION_COUNT" ]; then
                        ls -1t "$BACKUP_DIR"/*.sql | tail -n "+$((RETENTION_COUNT + 1))" | while IFS= read -r stale; do
                            log "pruning ${stale} (retention: ${RETENTION_COUNT} dumps)"
                            rm -f "$stale"
                        done
                    fi
                }

                run_backup() {
                    out="$BACKUP_DIR/${DB_NAME}-$(date -u +%%Y%%m%%dT%%H%%M%%SZ).sql"
                    log "backing up ${DB_NAME} to ${out} ..."
                    if %s > "$out"; then
                        log "done: ${out}"
                    else
                        log "FAILED: ${out}"
                        rm -f "$out"
                    fi
                    prune
                }

                log "starting -- interval ${INTERVAL_SECONDS}s, retention ${RETENTION_COUNT} dumps"
                while true; do
                    run_backup
                    sleep "$INTERVAL_SECONDS"
                done
                """.formatted(profile.guiLabel(), profile.backupCommand());
    }

    /**
     * R9.9: restores the MOST RECENT dump into the disposable {@code backup-verify-db} service and
     * reports PASS/FAIL by exit code and a parseable log line -- proving a dump actually restores,
     * not just that the dump command exited zero. Uses the SAME restore command {@code restore.sh}
     * uses (see that method's javadoc), against the scratch database rather than production.
     */
    private static String backupVerifyScript(DockerEngineProfile profile) {
        return """
                #!/bin/sh
                # R9.9: restores the LATEST dump from backup.sh/backup-sidecar.sh into the disposable
                # backup-verify-db service (NEVER the real `database` service) and reports pass/fail.
                set -u

                BACKUP_DIR="${BACKUP_DIR:-/backups}"
                DB_NAME="${DB_NAME:-npdev}"
                LOG_FILE="/logs/backup-verify.log"
                mkdir -p /logs

                log() {
                    printf '[backup-verify] %%s\\n' "$1" | tee -a "$LOG_FILE"
                }

                latest=$(ls -1t "$BACKUP_DIR"/*.sql 2>/dev/null | head -n 1)
                if [ -z "$latest" ]; then
                    log "FAIL: no dump found in ${BACKUP_DIR}"
                    exit 1
                fi

                log "restoring ${latest} into scratch database ${DB_NAME} ..."
                if %s < "$latest"; then
                    log "PASS: ${latest} restored successfully into the scratch database"
                    exit 0
                else
                    log "FAIL: restore of ${latest} into the scratch database failed"
                    exit 1
                fi
                """.formatted(profile.restoreCommand());
    }

    // ------------------------------------------------------------------------------------------
    // R9.10: observability profile -- Prometheus + provisioned Grafana + a liveness alert wired
    // to the mail path (compose `observability` profile).
    // ------------------------------------------------------------------------------------------

    private static final String PROMETHEUS_IMAGE = "prom/prometheus:v2.54.1";
    private static final String ALERTMANAGER_IMAGE = "prom/alertmanager:v0.27.0";
    private static final String GRAFANA_IMAGE = "grafana/grafana:11.2.0";
    private static final String ACTUATOR_PROXY_IMAGE = "nginx:1.27-alpine";

    /**
     * {@code /actuator/prometheus} is SUPERUSER-gated ({@code ActuatorAdminGuardFilter}), on
     * purpose -- it exposes internal counts, tenant tags and capability/flow names, not something to
     * open up for scraping. Prometheus itself cannot present the {@code X-Super-User-Key} header (no
     * generic per-app secret templating in its own config), so this sidecar holds that ONE secret
     * and Prometheus scrapes IT instead of the app directly -- nginx's official image auto-renders
     * any {@code /etc/nginx/templates/*.template} file with {@code envsubst} at container start
     * (stable since nginx 1.19, no custom entrypoint needed), which is exactly the "inject a runtime
     * secret into a static config file" problem this profile has. {@code NGINX_ENVSUBST_FILTER}
     * restricts substitution to ONLY the one variable this template intentionally uses, so nginx's
     * OWN {@code $variables} (none are used here, but future edits might add one) can never be
     * mistaken for a substitution target.
     */
    private static String observabilityProxyComposeBlock(int serverPort, int base) {
        int svc = base + 2;
        int prop = base + 4;
        int child = base + 6;
        int grand = base + 8;
        StringBuilder out = new StringBuilder();
        out.append(line(svc, "actuator-proxy:"));
        out.append(line(prop, "# R9.10: holds the SUPER_USER_KEY needed to read /actuator/prometheus so Prometheus itself"));
        out.append(line(prop, "# never has to. Retrieve the key first: docker compose exec app cat SUPER_USER_KEY.txt"));
        out.append(line(prop, "image: " + ACTUATOR_PROXY_IMAGE));
        out.append(line(prop, "profiles: [\"observability\"]"));
        out.append(line(prop, "depends_on:"));
        out.append(line(child, "app:"));
        out.append(line(grand, "condition: service_healthy"));
        out.append(line(prop, "environment:"));
        out.append(line(child, "NPDEV_SUPER_USER_KEY: ${NPDEV_SUPER_USER_KEY:?NPDEV_SUPER_USER_KEY must be set in .env to use the observability profile -- see docker compose exec app cat SUPER_USER_KEY.txt}"));
        out.append(line(child, "NGINX_ENVSUBST_FILTER: NPDEV_SUPER_USER_KEY"));
        out.append(line(prop, "volumes:"));
        out.append(line(child, "- ./deploy/observability/actuator-proxy.conf.template:/etc/nginx/templates/default.conf.template:ro"));
        out.append(composeLoggingBlock(prop, child, grand));
        out.append(line(prop, "restart: unless-stopped"));
        out.append(NEWLINE);
        return out.toString();
    }

    private static String observabilityPrometheusComposeBlock(int base) {
        int svc = base + 2;
        int prop = base + 4;
        int child = base + 6;
        int grand = base + 8;
        StringBuilder out = new StringBuilder();
        out.append(line(svc, "prometheus:"));
        out.append(line(prop, "image: " + PROMETHEUS_IMAGE));
        out.append(line(prop, "profiles: [\"observability\"]"));
        out.append(line(prop, "depends_on:"));
        out.append(line(child, "- actuator-proxy"));
        out.append(line(prop, "volumes:"));
        out.append(line(child, "- ./deploy/observability/prometheus.yml:/etc/prometheus/prometheus.yml:ro"));
        out.append(line(child, "- ./deploy/observability/alert-rules.yml:/etc/prometheus/alert-rules.yml:ro"));
        out.append(line(child, "- prometheus-data:/prometheus"));
        out.append(line(prop, "ports:"));
        out.append(line(child, "- \"${PROMETHEUS_PORT:-9090}:9090\""));
        out.append(composeLoggingBlock(prop, child, grand));
        out.append(line(prop, "restart: unless-stopped"));
        out.append(NEWLINE);
        return out.toString();
    }

    private static String observabilityAlertmanagerComposeBlock(int base) {
        int svc = base + 2;
        int prop = base + 4;
        int child = base + 6;
        int grand = base + 8;
        StringBuilder out = new StringBuilder();
        out.append(line(svc, "alertmanager:"));
        out.append(line(prop, "image: " + ALERTMANAGER_IMAGE));
        out.append(line(prop, "profiles: [\"observability\"]"));
        out.append(line(prop, "volumes:"));
        out.append(line(child, "- ./deploy/observability/alertmanager.yml:/etc/alertmanager/alertmanager.yml:ro"));
        out.append(line(prop, "ports:"));
        out.append(line(child, "- \"${ALERTMANAGER_PORT:-9093}:9093\""));
        out.append(composeLoggingBlock(prop, child, grand));
        out.append(line(prop, "restart: unless-stopped"));
        out.append(NEWLINE);
        return out.toString();
    }

    private static String observabilityGrafanaComposeBlock(int base) {
        int svc = base + 2;
        int prop = base + 4;
        int child = base + 6;
        int grand = base + 8;
        StringBuilder out = new StringBuilder();
        out.append(line(svc, "grafana:"));
        out.append(line(prop, "# R9.10: datasource + dashboard are PROVISIONED (files below), so this works on first `up`"));
        out.append(line(prop, "# with no manual clicking. http://localhost:3000, default admin/admin unless overridden."));
        out.append(line(prop, "image: " + GRAFANA_IMAGE));
        out.append(line(prop, "profiles: [\"observability\"]"));
        out.append(line(prop, "depends_on:"));
        out.append(line(child, "- prometheus"));
        out.append(line(prop, "environment:"));
        out.append(line(child, "GF_SECURITY_ADMIN_USER: ${GRAFANA_ADMIN_USER:-admin}"));
        out.append(line(child, "GF_SECURITY_ADMIN_PASSWORD: ${GRAFANA_ADMIN_PASSWORD:-change-me-to-a-real-secret}"));
        out.append(line(child, "GF_AUTH_ANONYMOUS_ENABLED: \"false\""));
        out.append(line(prop, "volumes:"));
        out.append(line(child, "- ./deploy/observability/grafana/provisioning:/etc/grafana/provisioning:ro"));
        out.append(line(child, "- ./deploy/observability/grafana/dashboards:/var/lib/grafana/dashboards:ro"));
        out.append(line(child, "- grafana-data:/var/lib/grafana"));
        out.append(line(prop, "ports:"));
        out.append(line(child, "- \"${GRAFANA_PORT:-3000}:3000\""));
        out.append(composeLoggingBlock(prop, child, grand));
        out.append(line(prop, "restart: unless-stopped"));
        out.append(NEWLINE);
        return out.toString();
    }

    private static String observabilityPrometheusConfigYaml() {
        return """
                # R9.10: provisioned Prometheus config -- static, no runtime secret needed, since this
                # scrapes the actuator-proxy sidecar (which holds the SUPER_USER_KEY), never the app
                # directly. See docker-compose.yml's `prometheus`/`actuator-proxy` service comments.
                global:
                  scrape_interval: 15s
                  evaluation_interval: 15s

                rule_files:
                  - /etc/prometheus/alert-rules.yml

                alerting:
                  alertmanagers:
                    - static_configs:
                        - targets: ["alertmanager:9093"]

                scrape_configs:
                  - job_name: npdev-app
                    metrics_path: /actuator/prometheus
                    static_configs:
                      - targets: ["actuator-proxy:9091"]
                """;
    }

    private static String observabilityAlertRulesYaml() {
        return """
                # R9.10: liveness/down alert. `up` is Prometheus's own per-target gauge -- 1 if the
                # last scrape succeeded, 0 otherwise (a failed scrape, a non-2xx response from
                # actuator-proxy, or the app itself being down all set it to 0), so this fires whether
                # the app process crashed, its healthcheck fails, or the proxy can't reach it.
                groups:
                  - name: npdev-app
                    rules:
                      - alert: NPDevAppDown
                        expr: up{job="npdev-app"} == 0
                        for: 1m
                        labels:
                          severity: critical
                        annotations:
                          summary: "NPDev app is not responding to metrics scraping"
                          description: "Prometheus has failed to scrape the npdev-app target for at least 1 minute -- the app may be down, or actuator-proxy/NPDEV_SUPER_USER_KEY may be misconfigured."
                """;
    }

    private static String observabilityAlertmanagerYaml() {
        return """
                # R9.10: delivers the NPDevAppDown alert by email. STATIC config, matching the SAME
                # defaults docker-compose.yml's own NPDEV_MAIL_SMTP_* variables use (the `mailhog`
                # service, opt in with `docker compose --profile smtp up`) -- Alertmanager reads its
                # OWN config file, not the app's environment variables, so if you point the app's mail
                # adapter at a real SMTP server (NPDEV_MAIL_SMTP_HOST/_PORT/_FROM in .env) for
                # production, mirror the same values below by hand; there is no shared source for both
                # today. See docs/DEPLOYMENT.md for the mail-smtp adapter this reuses the endpoint of.
                global:
                  smtp_smarthost: "mailhog:1025"
                  smtp_from: "no-reply@example.com"
                  smtp_require_tls: false

                route:
                  receiver: npdev-mail
                  group_wait: 30s
                  group_interval: 5m
                  repeat_interval: 4h

                receivers:
                  - name: npdev-mail
                    email_configs:
                      - to: "ops@example.com"
                        send_resolved: true
                """;
    }

    private static String observabilityActuatorProxyConfTemplate(int serverPort) {
        return """
                # R9.10: nginx renders this into /etc/nginx/conf.d/default.conf at container start,
                # substituting ${NPDEV_SUPER_USER_KEY} via envsubst (NGINX_ENVSUBST_FILTER in
                # docker-compose.yml restricts substitution to that one variable). Deliberately uses
                # NO nginx-native $variables, so nothing else here is ever mistaken for one.
                server {
                    listen 9091;

                    location /actuator/prometheus {
                        proxy_set_header X-Super-User-Key "${NPDEV_SUPER_USER_KEY}";
                        proxy_pass http://app:%d/actuator/prometheus;
                    }

                    location /actuator/health {
                        proxy_pass http://app:%d/actuator/health;
                    }
                }
                """.formatted(serverPort, serverPort);
    }

    private static String observabilityGrafanaDatasourceYaml() {
        return """
                # R9.10: provisioned datasource -- present on first `up`, no manual "Add data source" click.
                apiVersion: 1
                datasources:
                  - name: Prometheus
                    type: prometheus
                    access: proxy
                    url: http://prometheus:9090
                    isDefault: true
                    editable: false
                """;
    }

    private static String observabilityGrafanaDashboardProviderYaml() {
        return """
                # R9.10: tells Grafana to load every dashboard JSON under this directory on boot.
                apiVersion: 1
                providers:
                  - name: npdev
                    orgId: 1
                    folder: ""
                    type: file
                    disableDeletion: false
                    updateIntervalSeconds: 30
                    options:
                      path: /var/lib/grafana/dashboards
                """;
    }

    private static String observabilityGrafanaDashboardJson() {
        return """
                {
                  "uid": "npdev-app-overview",
                  "title": "NPDev App Overview",
                  "schemaVersion": 39,
                  "version": 1,
                  "editable": true,
                  "timezone": "browser",
                  "time": { "from": "now-1h", "to": "now" },
                  "refresh": "30s",
                  "panels": [
                    {
                      "id": 1,
                      "title": "App Up",
                      "type": "stat",
                      "gridPos": { "h": 6, "w": 6, "x": 0, "y": 0 },
                      "targets": [ { "expr": "up{job=\\"npdev-app\\"}", "refId": "A" } ]
                    },
                    {
                      "id": 2,
                      "title": "JVM Heap Used",
                      "type": "timeseries",
                      "gridPos": { "h": 6, "w": 9, "x": 6, "y": 0 },
                      "targets": [ { "expr": "jvm_memory_used_bytes{area=\\"heap\\", job=\\"npdev-app\\"}", "refId": "A" } ]
                    },
                    {
                      "id": 3,
                      "title": "HTTP Request Rate",
                      "type": "timeseries",
                      "gridPos": { "h": 6, "w": 9, "x": 15, "y": 0 },
                      "targets": [ { "expr": "sum(rate(http_server_requests_seconds_count{job=\\"npdev-app\\"}[5m])) by (uri)", "refId": "A" } ]
                    },
                    {
                      "id": 4,
                      "title": "Process CPU Usage",
                      "type": "timeseries",
                      "gridPos": { "h": 6, "w": 12, "x": 0, "y": 6 },
                      "targets": [ { "expr": "process_cpu_usage{job=\\"npdev-app\\"}", "refId": "A" } ]
                    }
                  ]
                }
                """;
    }

    private static String dockerIgnore() {
        return """
                .gradle/
                build/reports/
                build/test-results/
                build/tmp/
                .env
                secrets/
                """;
    }

    private static void write(Path path, String content) throws Exception {
        Files.createDirectories(path.getParent());
        Files.writeString(path, content.replace("\n", System.lineSeparator()), StandardCharsets.UTF_8);
    }

    private static int readInt(JsonNode root, int fallback, String... path) {
        JsonNode current = root;
        if (current == null) {
            return fallback;
        }
        for (String element : path) {
            current = current.path(element);
            if (current == null || current.isMissingNode() || current.isNull()) {
                return fallback;
            }
        }
        return current.canConvertToInt() ? current.asInt() : fallback;
    }
}
