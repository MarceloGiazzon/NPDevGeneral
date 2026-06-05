package com.npdev.generator.dbconfig;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.npdev.dsl.v1.compiled.CompiledEntity;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.kernel.dbschema.InternalTableDefinition;
import com.npdev.kernel.dbschema.NpdevInternalTables;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class SchemaRealizationEmitterInternalLogicalTypesTest {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @TempDir
    Path tempDir;

    @Test
    void schemaRealizationEmitsAllInternalTablesWithNeutralLogicalTypes() throws Exception {
        Path outRoot = tempDir.resolve("app");
        GeneratedDatabasePlan plan = new GeneratedDatabasePlan(
                "internal-logical-types-test",
                DatabaseEngine.H2_LOCAL,
                DatabaseEngine.H2_LOCAL.storageMode(),
                true,
                "internal-logical-types-test",
                "internal-logical-types-test",
                "test",
                tempDir.resolve("data").toString(),
                "db-instance",
                "",
                "",
                0,
                0,
                "jdbc:h2:mem:internal-logical-types-test",
                "org.h2.Driver",
                "sa",
                "",
                "",
                0,
                "",
                "",
                true,
                false,
                new SchemaLifecyclePolicy(
                        SchemaLifecycleStrategy.KEEP_EXISTING_IF_COMPATIBLE,
                        false,
                        "",
                        SchemaLifecyclePolicy.NPDEV_TABLE_SCOPE
                ),
                "test-fingerprint",
                tempDir.resolve("database.json"),
                List.of("test")
        );
        CompiledModel model = new CompiledModel("test", "1.0.0", "1.0.0", Map.<String, CompiledEntity>of());

        new SchemaRealizationEmitter().emit(model, outRoot, plan, tempDir.resolve("model.json"));

        String sql = Files.readString(outRoot.resolve("src/main/resources/db/schema-realization/V1__npdev_schema_realization.sql"));
        Set<String> expectedTables = NpdevInternalTables.all().stream()
                .map(InternalTableDefinition::name)
                .collect(Collectors.toSet());
        for (String tableName : expectedTables) {
            assertTrue(sql.contains("CREATE TABLE IF NOT EXISTS " + tableName),
                    "Generated schema SQL must include " + tableName);
        }
        assertFalse(sql.contains("JSONB"), "Neutral/H2 schema realization must not emit JSONB for JSON_DOCUMENT");
        assertFalse(sql.contains("CLOB"), "Neutral/H2 schema realization must not emit CLOB for LARGE_TEXT");
        assertFalse(sql.contains("TIMESTAMP WITH TIME ZONE"),
                "Neutral/H2 schema realization must not require TIMESTAMP WITH TIME ZONE");
        assertTrue(sql.contains("payload TEXT"), "JSON_DOCUMENT scheduled payload must emit as neutral TEXT");
        assertTrue(sql.contains("execution_payload TEXT"),
                "JSON_DOCUMENT publication execution payload must emit as neutral TEXT");

        JsonNode manifest = OBJECT_MAPPER.readTree(Files.readString(
                outRoot.resolve("src/main/resources/npdev/db/schema-realization-manifest.json")
        ));
        for (String tableName : expectedTables) {
            assertTrue(manifestContains(manifest.path("internalTables"), tableName),
                    "Generated schema manifest must list " + tableName + " as an internal table");
        }
    }

    private static boolean manifestContains(JsonNode internalTables, String tableName) {
        for (JsonNode table : internalTables) {
            if (tableName.equals(table.asText())) {
                return true;
            }
        }
        return false;
    }
}
