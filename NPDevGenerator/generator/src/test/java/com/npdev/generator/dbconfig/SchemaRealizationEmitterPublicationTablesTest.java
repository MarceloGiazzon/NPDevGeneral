package com.npdev.generator.dbconfig;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.npdev.dsl.v1.compiled.CompiledEntity;
import com.npdev.dsl.v1.compiled.CompiledModel;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class SchemaRealizationEmitterPublicationTablesTest {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final Set<String> EXPECTED_PUBLICATION_TABLES = Set.of(
            "npdev_publication_execution",
            "npdev_publication_audit"
    );

    @TempDir
    Path tempDir;

    @Test
    void schemaRealizationEmitsPublicationTablesFromInternalTableRegistry() throws Exception {
        Path outRoot = tempDir.resolve("app");
        GeneratedDatabasePlan plan = new GeneratedDatabasePlan(
                "publication-table-test",
                DatabaseEngine.H2_LOCAL,
                DatabaseEngine.H2_LOCAL.storageMode(),
                true,
                "publication-table-test",
                "publication-table-test",
                "test",
                tempDir.resolve("data").toString(),
                "db-instance",
                "",
                "",
                0,
                0,
                "jdbc:h2:mem:publication-table-test",
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
        for (String tableName : EXPECTED_PUBLICATION_TABLES) {
            assertTrue(sql.contains("CREATE TABLE IF NOT EXISTS " + tableName),
                    "Generated schema SQL must include " + tableName);
        }

        JsonNode manifest = OBJECT_MAPPER.readTree(Files.readString(
                outRoot.resolve("src/main/resources/npdev/db/schema-realization-manifest.json")
        ));
        for (String tableName : EXPECTED_PUBLICATION_TABLES) {
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
