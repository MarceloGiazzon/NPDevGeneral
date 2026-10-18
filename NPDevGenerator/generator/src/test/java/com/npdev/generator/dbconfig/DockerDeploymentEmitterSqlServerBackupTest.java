package com.npdev.generator.dbconfig;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * BKP-1: SQL Server apps ship the same {@code backup} compose profile as Postgres and MySQL, in a
 * file-based form. {@code BACKUP DATABASE} can only write a file INSIDE the database container, so
 * the stdout/stdin stream the other engines use does not apply: the scripts run T-SQL in the
 * {@code database} service and move the {@code .bak} out with {@code docker compose cp}
 * (backup.sh/restore.sh) or through a shared data-volume mount (the sidecar and the verify job).
 *
 * <p>This pins what the generator emits. Running the profile against a real SQL Server container
 * (<code>docker compose --profile backup up -d backup</code>, then <code>run --rm backup-verify</code>)
 * is a Docker-machine / CI check: Docker is off locally by policy.
 */
class DockerDeploymentEmitterSqlServerBackupTest {

    private static Path emit(Path tempDir, String engine, int port) throws Exception {
        Files.createDirectories(tempDir);
        Path definition = tempDir.resolve("db.definition.json");
        Files.writeString(definition, """
                {
                  "database": { "engine": "%s", "host": "localhost", "port": %d, "username": "npdev",
                                 "password": "secret", "createInternalTables": true, "createBusinessTables": true,
                                 "externallyProvisioned": false },
                  "schemaLifecycle": { "strategy": "KeepExistingIfCompatible", "scope": "NpdevOwnedTablesOnly" }
                }
                """.formatted(engine, port));
        GeneratedDatabasePlan plan = new UserDatabaseDefinitionLoader().load(definition, null);
        Path appRoot = tempDir.resolve("app-" + engine);
        new DockerDeploymentEmitter().emit(null, appRoot, plan);
        return appRoot;
    }

    private static String read(Path appRoot, String relative) throws Exception {
        return Files.readString(appRoot.resolve(relative));
    }

    @Test
    void theProfileDeclaresABackupAndARestoreStatementWithoutAnyPassword() {
        DockerEngineProfile profile = DockerEngineProfiles.of(DatabaseEngine.SQL_SERVER);

        assertTrue(profile.isServerFileBackup());
        assertTrue(profile.backupCommand().contains("BACKUP DATABASE"), profile.backupCommand());
        assertTrue(profile.restoreCommand().contains("RESTORE DATABASE"), profile.restoreCommand());
        assertTrue(profile.backupCommand().contains("{file}") && profile.restoreCommand().contains("{file}"));
        assertEquals("sa", profile.adminUser());
        // credentials come from the environment, never from the profile
        assertFalse(profile.backupCommand().toLowerCase().contains("password"));
        assertTrue(profile.backupClientEnv().containsKey("SQLCMDPASSWORD"));
        assertTrue(profile.backupClientEnv().get("SQLCMDPASSWORD").contains("{password}"));
    }

    @Test
    void backupScriptRunsBackupDatabaseInTheServiceThenCopiesTheBakOutAndCleansUp(@TempDir Path tempDir) throws Exception {
        String backup = read(emit(tempDir, "SqlServer", 1433), "deploy/backup.sh");

        assertTrue(backup.contains("BACKUP DATABASE [${DB_NAME}] TO DISK = N'${server_path}' WITH INIT, CHECKSUM"), backup);
        assertTrue(backup.contains("docker compose exec -T database /opt/mssql-tools18/bin/sqlcmd -C -b -U sa -P \"$DB_PASSWORD\""),
                backup);
        assertTrue(backup.contains("docker compose cp \"database:${server_path}\" \"backups/${file}\""), backup);
        assertTrue(backup.contains("rm -f \"${server_path}\""), "the staged .bak is removed from the container: " + backup);
        assertTrue(backup.contains("server_path=\"/var/opt/mssql/data/${file}\""), backup);
        assertTrue(backup.contains(".bak\""), backup);
        assertFalse(backup.contains("pg_dump") || backup.contains("mysqldump"), backup);
    }

    @Test
    void restoreScriptRefusesWithoutYesAndRestoresFromTheCopiedBak(@TempDir Path tempDir) throws Exception {
        String restore = read(emit(tempDir, "SqlServer", 1433), "deploy/restore.sh");

        assertTrue(restore.contains("--yes"), restore);
        assertTrue(restore.contains("docker compose cp \"$dump_file\" \"database:${server_path}\""), restore);
        assertTrue(restore.contains("RESTORE DATABASE [${DB_NAME}] FROM DISK = N'${server_path}' WITH REPLACE, CHECKSUM"), restore);
        assertTrue(restore.contains("ALTER DATABASE [${DB_NAME}] SET SINGLE_USER WITH ROLLBACK IMMEDIATE"), restore);
        assertTrue(restore.contains("SET SINGLE_USER WITH ROLLBACK IMMEDIATE") && restore.contains("SET MULTI_USER"),
                "open connections are closed for the restore and reopened after: " + restore);
        assertTrue(restore.contains("rm -f \"${server_path}\""), restore);
    }

    @Test
    void composeSharesTheDataVolumeWithTheSidecarAndTheScratchVolumeWithTheVerifyJob(@TempDir Path tempDir) throws Exception {
        String compose = read(emit(tempDir, "SqlServer", 1433), "docker-compose.yml");

        int sidecar = compose.indexOf("  backup:");
        int scratchDb = compose.indexOf("  backup-verify-db:");
        int verify = compose.indexOf("  backup-verify:");
        assertTrue(sidecar >= 0 && scratchDb > sidecar && verify > scratchDb, compose);
        assertTrue(compose.substring(sidecar, scratchDb).contains("- dbdata:/var/opt/mssql"),
                "the sidecar mounts the server's data volume to move the .bak out: " + compose);
        assertTrue(compose.substring(verify).contains("- backup-verify-scratch:/var/opt/mssql"),
                "the verify job stages the .bak into the scratch server's volume: " + compose);
        assertTrue(compose.contains("SQLCMDSERVER: database,1433"), compose);
        assertTrue(compose.contains("SQLCMDSERVER: backup-verify-db,1433"), compose);
        assertTrue(compose.contains("SQLCMDPASSWORD: ${DB_PASSWORD:?DB_PASSWORD must be set in .env}"), compose);
        assertTrue(compose.contains("image: mcr.microsoft.com/mssql/server:2022-latest"), "the clients ship sqlcmd");
    }

    @Test
    void sidecarBacksUpMovesTheFileAndPrunesBakFilesByRetention(@TempDir Path tempDir) throws Exception {
        String sidecar = read(emit(tempDir, "SqlServer", 1433), "deploy/backup-sidecar.sh");

        assertTrue(sidecar.contains("/opt/mssql-tools18/bin/sqlcmd -C -b -U sa -Q \"BACKUP DATABASE [${DB_NAME}] TO DISK = N'${server_path}'"),
                sidecar);
        assertTrue(sidecar.contains("mv \"$server_path\" \"$out\""), sidecar);
        assertTrue(sidecar.contains("ls -1t \"$BACKUP_DIR\"/*.bak"), "retention prunes .bak files: " + sidecar);
        assertTrue(sidecar.contains("RETENTION_COUNT=\"${BACKUP_RETENTION_COUNT:-14}\""), sidecar);
        assertFalse(sidecar.contains("*.sql"), sidecar);
        assertTrue(sidecar.contains("FAILED"), "a failed backup is logged and its partial file removed: " + sidecar);
    }

    @Test
    void verifyRestoresTheLatestBakIntoTheScratchServerAndReadsItBack(@TempDir Path tempDir) throws Exception {
        String verify = read(emit(tempDir, "SqlServer", 1433), "deploy/backup-verify.sh");

        assertTrue(verify.contains("ls -1t \"$BACKUP_DIR\"/*.bak"), verify);
        assertTrue(verify.contains("cp \"$latest\" \"$server_path\""), verify);
        assertTrue(verify.contains("RESTORE DATABASE [${DB_NAME}] FROM DISK = N'${server_path}' WITH REPLACE, CHECKSUM"), verify);
        assertTrue(verify.contains("DBCC CHECKDB ([${DB_NAME}]) WITH NO_INFOMSGS"), "the restored database is integrity-checked: " + verify);
        assertTrue(verify.contains("PASS:") && verify.contains("FAIL:"), "parseable result lines: " + verify);
    }

    @Test
    void postgresAndMySqlStayOnTheStreamScripts(@TempDir Path tempDir) throws Exception {
        String postgres = read(emit(tempDir, "Postgres", 5432), "deploy/backup.sh");
        String mysql = read(emit(tempDir, "MySQL", 3306), "deploy/backup.sh");

        assertTrue(postgres.contains("pg_dump") && postgres.contains("> \"$out\""), postgres);
        assertTrue(mysql.contains("mysqldump") && mysql.contains("> \"$out\""), mysql);
        assertFalse(postgres.contains("docker compose cp") || mysql.contains("docker compose cp"));
    }

    @Test
    void sqlServerEmissionIsByteIdenticalAcrossTwoRuns(@TempDir Path tempDir) throws Exception {
        Path first = emit(tempDir.resolve("one"), "SqlServer", 1433);
        Path second = emit(tempDir.resolve("two"), "SqlServer", 1433);

        for (String file : new String[] {"docker-compose.yml", "deploy/backup.sh", "deploy/restore.sh",
                "deploy/backup-sidecar.sh", "deploy/backup-verify.sh"}) {
            assertEquals(read(first, file), read(second, file), file + " must be deterministic");
        }
    }
}
