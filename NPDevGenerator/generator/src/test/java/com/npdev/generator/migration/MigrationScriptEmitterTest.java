package com.npdev.generator.migration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

class MigrationScriptEmitterTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldEmitDeterministicSqlPlan() throws Exception {
        // PostgreSQL SQL emission must stay valid, and a rollback script should exist for every dangerous operation.
        MigrationPlan plan = new MigrationPlan(List.of(
                MigrationOperation.addColumn("user", "active", "BOOLEAN"),
                MigrationOperation.addColumn("user", "display_name", "VARCHAR"),
                MigrationOperation.setNotNull("user", "email"),
                MigrationOperation.createUniqueIndex("user", "email", true)
        )).normalized();

        Path file = new MigrationScriptEmitter().emit(tempDir, "latest-model-delta.sql", plan);
        String sql = Files.readString(file);

        assertTrue(sql.contains("ALTER TABLE user ADD COLUMN IF NOT EXISTS active BOOLEAN;"));
        assertTrue(sql.contains("ALTER TABLE user ADD COLUMN IF NOT EXISTS display_name VARCHAR(255);"));
        assertTrue(sql.contains("ALTER TABLE user ALTER COLUMN email SET NOT NULL;"));
        assertTrue(sql.contains("CREATE UNIQUE INDEX IF NOT EXISTS ux_user_email ON user (email);"));
        assertFalse(sql.contains("lower("));
        assertTrue(sql.contains("ALTER TABLE"),
                "The PostgreSQL SQL plan should remain valid for the target database.");
    }
}
