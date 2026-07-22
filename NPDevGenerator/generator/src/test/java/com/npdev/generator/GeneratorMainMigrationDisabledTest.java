package com.npdev.generator;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GeneratorMainMigrationDisabledTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void rejectsEnabledMigrationManagementConfig() throws Exception {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                GeneratorMain.rejectUnsupportedMigrationManagement(MAPPER.readTree("""
                        {
                          "migrationManagement": {
                            "enabled": true
                          }
                        }
                        """))
        );

        assertTrue(ex.getMessage().contains(GeneratorMain.CONFIG_MIGRATIONS_DISABLED));
        assertTrue(ex.getMessage().contains("schema realization"));
    }

    @Test
    void allowsExplicitlyDisabledMigrationManagementConfig() throws Exception {
        assertDoesNotThrow(() ->
                GeneratorMain.rejectUnsupportedMigrationManagement(MAPPER.readTree("""
                        {
                          "migrationManagement": {
                            "enabled": false,
                            "mode": "disabled"
                          }
                        }
                        """))
        );
    }

    @Test
    void rejectsActiveSchemaEvolutionMode() throws Exception {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                GeneratorMain.rejectUnsupportedMigrationManagement(MAPPER.readTree("""
                        {
                          "schemaEvolution": {
                            "mode": "managed"
                          }
                        }
                        """))
        );

        assertTrue(ex.getMessage().contains(GeneratorMain.CONFIG_MIGRATIONS_DISABLED));
        assertTrue(ex.getMessage().contains("schema realization"));
    }

    @Test
    void acceptsCp8AdditiveOnlyCommandLineMode() {
        assertDoesNotThrow(() -> Class.forName("com.npdev.generator.GeneratorMain"));
    }
}
