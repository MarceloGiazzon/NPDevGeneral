package com.npdev.generator.dbconfig;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * OPS-8: the emitted Caddyfile takes its public host and ACME e-mail from the proxy container's
 * environment ({@code NPDEV_PUBLIC_HOST} / {@code NPDEV_ACME_EMAIL}, set in {@code .env}), so real
 * HTTPS needs no hand edit of a generated file. Both unset must keep today's self-signed ':443'.
 */
class DockerDeploymentEmitterCaddyEnvTest {

    private static GeneratedDatabasePlan loadPlan(Path dir, String engine, String host, int port) throws Exception {
        Path definition = dir.resolve("db.definition.json");
        Files.writeString(definition, """
                {
                  "database": { "engine": "%s", "host": "%s", "port": %d, "username": "npdev",
                                 "password": "secret", "createInternalTables": true, "createBusinessTables": true,
                                 "externallyProvisioned": false },
                  "schemaLifecycle": { "strategy": "KeepExistingIfCompatible", "scope": "NpdevOwnedTablesOnly" }
                }
                """.formatted(engine, host, port));
        return new UserDatabaseDefinitionLoader().load(definition, null);
    }

    private static void assertCaddyEnvWiring(Path appRoot) throws Exception {
        String caddyfile = Files.readString(appRoot.resolve("deploy").resolve("Caddyfile"));
        assertTrue(caddyfile.contains("{$NPDEV_PUBLIC_HOST::443} {"), caddyfile);
        assertTrue(caddyfile.contains("tls {$NPDEV_ACME_EMAIL:internal}"), caddyfile);
        assertFalse(caddyfile.contains("\n:443 {"), "the site address must no longer be a fixed ':443'");

        String compose = Files.readString(appRoot.resolve("docker-compose.yml"));
        assertTrue(compose.contains("NPDEV_PUBLIC_HOST: ${NPDEV_PUBLIC_HOST:-}"), compose);
        assertTrue(compose.contains("NPDEV_ACME_EMAIL: ${NPDEV_ACME_EMAIL:-}"), compose);

        String env = Files.readString(appRoot.resolve(".env.example"));
        assertTrue(env.contains("# NPDEV_PUBLIC_HOST=app.example.com"), env);
        assertTrue(env.contains("# NPDEV_ACME_EMAIL=you@example.com"), env);
    }

    @Test
    void postgresFlavorWiresPublicHostAndAcmeEmailThroughEnv(@TempDir Path tempDir) throws Exception {
        Path appRoot = tempDir.resolve("app");
        new DockerDeploymentEmitter().emit(null, appRoot, loadPlan(tempDir, "Postgres", "localhost", 5432));
        assertCaddyEnvWiring(appRoot);
    }

    @Test
    void h2LocalFlavorWiresPublicHostAndAcmeEmailThroughEnv(@TempDir Path tempDir) throws Exception {
        Path appRoot = tempDir.resolve("app");
        new DockerDeploymentEmitter().emit(null, appRoot, loadPlan(tempDir, "H2Local", "", 0));
        assertCaddyEnvWiring(appRoot);
    }
}
