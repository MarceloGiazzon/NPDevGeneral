package com.npdev.generator.dbconfig;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R9.1: every emitted {@code backup.sh}/{@code restore.sh} failed on first use, on every engine,
 * always -- they exec'd into a compose service named {@code postgres} (the compose file
 * {@link DockerDeploymentEmitter} emits has always called it {@code database}) and read
 * {@code POSTGRES_DB}/{@code POSTGRES_USER}, variables {@code .env.example} never defines. These
 * pin the fix: the script must target the service the compose file actually names, and read the
 * vars {@code .env.example} actually writes -- for every engine whose profile declares the
 * commands, not just Postgres.
 */
class DockerDeploymentEmitterTest {

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

    @Test
    void postgresBackupAndRestoreTargetTheDatabaseServiceAndTheDeclaredEnvVars(@TempDir Path tempDir) throws Exception {
        GeneratedDatabasePlan plan = loadPlan(tempDir, "Postgres", 5432);
        Path appRoot = tempDir.resolve("app");

        new DockerDeploymentEmitter().emit(null, appRoot, plan);

        String backup = Files.readString(appRoot.resolve("deploy").resolve("backup.sh"));
        String restore = Files.readString(appRoot.resolve("deploy").resolve("restore.sh"));

        for (String script : new String[] {backup, restore}) {
            assertTrue(script.contains("docker compose exec -T database"),
                    "must exec into the 'database' service, the one the compose file actually names: " + script);
            assertFalse(script.contains("exec -T postgres"),
                    "must not exec into a service named 'postgres' -- it has never existed in the emitted compose file");
            assertTrue(script.contains("DB_NAME"), "must read DB_NAME, the var .env.example actually defines");
            assertTrue(script.contains("DB_USER"), "must read DB_USER, the var .env.example actually defines");
            assertFalse(script.contains("POSTGRES_DB"), "must not read POSTGRES_DB -- .env.example never defines it");
            assertFalse(script.contains("POSTGRES_USER"), "must not read POSTGRES_USER -- .env.example never defines it");
        }
        assertTrue(backup.contains("pg_dump"), "Postgres backup must use pg_dump: " + backup);
        assertTrue(restore.contains("psql"), "Postgres restore must use psql: " + restore);
    }

    @Test
    void mySqlBackupAndRestoreUseTheMySqlClientToolsAgainstTheDatabaseService(@TempDir Path tempDir) throws Exception {
        GeneratedDatabasePlan plan = loadPlan(tempDir, "MySQL", 3306);
        Path appRoot = tempDir.resolve("app");

        new DockerDeploymentEmitter().emit(null, appRoot, plan);

        String backup = Files.readString(appRoot.resolve("deploy").resolve("backup.sh"));
        String restore = Files.readString(appRoot.resolve("deploy").resolve("restore.sh"));

        assertTrue(backup.contains("docker compose exec -T database"), backup);
        assertTrue(restore.contains("docker compose exec -T database"), restore);
        assertTrue(backup.contains("mysqldump"), "MySQL backup must use mysqldump: " + backup);
        assertTrue(restore.contains("mysql "), "MySQL restore must use the mysql client: " + restore);
    }

    @Test
    void sqlServerShipsNoBackupOrRestoreScript(@TempDir Path tempDir) throws Exception {
        // SQL Server's BACKUP DATABASE writes INSIDE the container -- a script that produced a dump
        // nothing could restore would be worse than no script at all.
        GeneratedDatabasePlan plan = loadPlan(tempDir, "SqlServer", 1433);
        Path appRoot = tempDir.resolve("app");

        new DockerDeploymentEmitter().emit(null, appRoot, plan);

        assertFalse(Files.exists(appRoot.resolve("deploy").resolve("backup.sh")));
        assertFalse(Files.exists(appRoot.resolve("deploy").resolve("restore.sh")));
    }

    @Test
    void embeddedEngineShipsNoBackupOrRestoreScript(@TempDir Path tempDir) throws Exception {
        // H2Local runs inside the app process itself -- there is no separate service to dump.
        Path definitionPath = writeDefinition(tempDir, "H2Local", "", 0);
        GeneratedDatabasePlan plan = new UserDatabaseDefinitionLoader().load(definitionPath, null);
        Path appRoot = tempDir.resolve("app");

        new DockerDeploymentEmitter().emit(null, appRoot, plan);

        assertFalse(Files.exists(appRoot.resolve("deploy").resolve("backup.sh")));
        assertFalse(Files.exists(appRoot.resolve("deploy").resolve("restore.sh")));
    }

    @Test
    void everyComposeServiceDeclaresACappedLogDriver(@TempDir Path tempDir) throws Exception {
        // R9.2: Docker's default json-file log driver has no cap of its own -- an emitted compose
        // service with no `logging:` block grows its container's captured stdout/stderr without
        // bound for as long as the container exists. Every service in both compose flavors (the
        // server-engine one with database/proxy/minio/mailhog, and the standalone one used for
        // InMemory/H2Local/H2Server apps) must declare one. Counted against `restart:
        // unless-stopped`, which every service block already carries one-for-one, rather than
        // parsing YAML structure or hardcoding indentation.
        Path serverSrc = Files.createDirectories(tempDir.resolve("server-src"));
        GeneratedDatabasePlan serverPlan = loadPlan(serverSrc, "Postgres", 5432);
        String serverCompose = Files.readString(emitCompose(tempDir.resolve("server-app"), serverPlan));
        assertLogCapPerService(serverCompose);

        Path standaloneSrc = Files.createDirectories(tempDir.resolve("standalone-src"));
        Path definitionPath = writeDefinition(standaloneSrc, "H2Local", "", 0);
        GeneratedDatabasePlan standalonePlan = new UserDatabaseDefinitionLoader().load(definitionPath, null);
        String standaloneCompose = Files.readString(emitCompose(tempDir.resolve("standalone-app"), standalonePlan));
        assertLogCapPerService(standaloneCompose);
    }

    private static Path emitCompose(Path appRoot, GeneratedDatabasePlan plan) throws Exception {
        new DockerDeploymentEmitter().emit(null, appRoot, plan);
        return appRoot.resolve("docker-compose.yml");
    }

    private static void assertLogCapPerService(String compose) {
        int serviceCount = countOccurrences(compose, "restart: unless-stopped");
        assertTrue(serviceCount >= 3, "expected at least app/proxy/mailhog to declare restart: " + compose);
        assertTrue(compose.contains("driver: json-file"), "must cap logs with the json-file driver: " + compose);
        assertTrue(compose.contains("max-size: \"10m\""), "must set a log size cap: " + compose);
        assertTrue(compose.contains("max-file: \"5\""), "must set a rolled-file count cap: " + compose);
        assertTrue(countOccurrences(compose, "driver: json-file") == serviceCount,
                "every service ('restart: unless-stopped', " + serviceCount
                        + " of them) must carry its own log cap: " + compose);
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

    @Test
    void theComposeFileNamesTheServiceThatBackupAndRestoreTarget(@TempDir Path tempDir) throws Exception {
        // Locks the two together: whatever service name docker-compose.yml actually emits for the
        // database is the one backup.sh/restore.sh must exec into. This is the exact seam R9.1 found
        // diverged (compose said "database", the scripts said "postgres").
        GeneratedDatabasePlan plan = loadPlan(tempDir, "Postgres", 5432);
        Path appRoot = tempDir.resolve("app");

        new DockerDeploymentEmitter().emit(null, appRoot, plan);

        String compose = Files.readString(appRoot.resolve("docker-compose.yml"));
        assertTrue(Pattern.compile("(?m)^[ \\t]*database:[ \\t]*$").matcher(compose).find(),
                "compose file must declare a service literally named 'database': " + compose);
    }
}
