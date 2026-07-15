package com.npdev.generator.dbconfig;

import com.fasterxml.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

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
 */
public final class DockerDeploymentEmitter {

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
        boolean postgresEngine = plan != null && plan.engine() == DatabaseEngine.POSTGRES;

        write(root.resolve("Dockerfile"), dockerfile(jarName, serverPort));
        write(root.resolve("docker-compose.yml"),
                postgresEngine ? dockerComposePostgres(appId, serverPort) : dockerComposeStandalone(appId, serverPort));
        String dbName = postgresEngine && plan.resolvedDatabaseName() != null && !plan.resolvedDatabaseName().isBlank()
                ? plan.resolvedDatabaseName()
                : appId.replace('-', '_');
        write(root.resolve(".env.example"), postgresEngine ? envExamplePostgres(dbName) : envExampleStandalone());
        write(root.resolve("deploy").resolve("Caddyfile"), caddyfile(serverPort));
        write(root.resolve(".dockerignore"), dockerIgnore());
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

    private static String dockerComposePostgres(String appId, int serverPort) {
        return """
                # LNCH-7: Postgres-first deployment. `docker compose up --build` runs the app +
                # Postgres, both healthchecked, app waiting on Postgres before starting. The optional
                # `proxy` profile (`docker compose --profile proxy up`) adds a Caddy TLS-terminating
                # reverse proxy in front (see deploy/Caddyfile) -- generated apps never terminate TLS
                # themselves.
                #
                # First run: copy .env.example to .env and set real secrets before `docker compose up`.
                name: %s

                services:
                  app:
                    build: .
                    depends_on:
                      postgres:
                        condition: service_healthy
                    environment:
                      SPRING_PROFILES_ACTIVE: ${SPRING_PROFILES_ACTIVE:-prod,postgres}
                      # StartupValidator requires this whenever the 'postgres' Spring profile is
                      # active (confirmed live: "Spring profile 'postgres' requires
                      # npdev.runtime.mode=postgres") -- the two are deliberately independent knobs
                      # (Spring profile selects config files, runtime.mode selects the adapter), so
                      # both must agree.
                      NPDEV_RUNTIME_MODE: postgres
                      SPRING_DATASOURCE_URL: jdbc:postgresql://postgres:5432/${POSTGRES_DB:-npdev}
                      SPRING_DATASOURCE_USERNAME: ${POSTGRES_USER:-npdev}
                      SPRING_DATASOURCE_PASSWORD: ${POSTGRES_PASSWORD:?POSTGRES_PASSWORD must be set in .env}
                      NPDEV_AUTH_MODE: ${NPDEV_AUTH_MODE:-apikey}
                      # NOTE the missing underscore before KEYS: Spring Boot's relaxed env-var
                      # binding strips hyphens from the property name entirely rather than mapping
                      # them to underscores, so 'npdev.auth.api-keys' binds from NPDEV_AUTH_APIKEYS,
                      # not the more intuitive NPDEV_AUTH_API_KEYS (which would silently no-op).
                      NPDEV_AUTH_APIKEYS: ${NPDEV_AUTH_APIKEYS:?NPDEV_AUTH_APIKEYS must be set in .env}
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
                    restart: unless-stopped

                  postgres:
                    image: postgres:16-alpine
                    environment:
                      POSTGRES_DB: ${POSTGRES_DB:-npdev}
                      POSTGRES_USER: ${POSTGRES_USER:-npdev}
                      POSTGRES_PASSWORD: ${POSTGRES_PASSWORD:?POSTGRES_PASSWORD must be set in .env}
                    volumes:
                      - pgdata:/var/lib/postgresql/data
                    healthcheck:
                      test: ["CMD-SHELL", "pg_isready -U ${POSTGRES_USER:-npdev}"]
                      interval: 5s
                      timeout: 5s
                      retries: 10
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
                    restart: unless-stopped

                volumes:
                  pgdata:
                  npdev-files:
                  app-data:
                  caddy-data:
                """.formatted(appId, serverPort, serverPort, serverPort);
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
                # TLS-terminating reverse proxy in front (see deploy/Caddyfile).
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

                # The ControlPanel Super User key is NOT set here -- it has no config property at all.
                # SuperUserBootstrapper generates one automatically on first boot (if none is active
                # yet) and writes it to SUPER_USER_KEY.txt in the app container's working directory.
                # After first `docker compose up`, retrieve it with:
                #   docker compose exec app cat SUPER_USER_KEY.txt
                """;
    }

    private static String envExamplePostgres(String dbName) {
        return """
                # LNCH-7: copy this file to .env and fill in real values before `docker compose up`.
                # Never commit a real .env -- it holds secrets.

                # Postgres
                # POSTGRES_DB defaults to this app's resolved database name (db.definition.json /
                # resolved-db-plan.json) -- DatabaseIdentityStartupValidator refuses to start if the
                # name it connects to doesn't match what the model was generated against (confirmed
                # live: "Configured database is 'X', but connected database is 'Y'"). If
                # db.definition.json leaves databaseName unset, the generator appends a fresh
                # timestamp on every regeneration -- set an explicit databaseName there for a stable
                # production identity across redeploys.
                POSTGRES_DB=%s
                POSTGRES_USER=npdev
                POSTGRES_PASSWORD=change-me-to-a-real-secret

                # App
                APP_PORT=8080
                SPRING_PROFILES_ACTIVE=prod,postgres
                NPDEV_AUTH_MODE=apikey
                # Format: key=tenant:actor:ROLE1|ROLE2;another-key=tenant:actor:ROLE
                # NOTE: the env var name has no underscore before "APIKEYS" -- Spring Boot's relaxed
                # binding strips hyphens from 'npdev.auth.api-keys' rather than mapping them to
                # underscores. See the comment in docker-compose.yml if this looks like a typo.
                NPDEV_AUTH_APIKEYS=change-me=prod:deploy:ADMIN

                # The ControlPanel Super User key is NOT set here -- it has no config property at all.
                # SuperUserBootstrapper generates one automatically on first boot (if none is active
                # yet) and writes it to SUPER_USER_KEY.txt in the app container's working directory.
                # After first `docker compose up`, retrieve it with:
                #   docker compose exec app cat SUPER_USER_KEY.txt
                """.formatted(dbName);
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
