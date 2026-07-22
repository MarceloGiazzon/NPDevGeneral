package com.npdev.generator.dbconfig;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.npdev.dsl.v1.compiled.CompiledConcept;
import com.npdev.dsl.v1.compiled.CompiledModel;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class SchemaRealizationEmitterScheduledEventTest {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @TempDir
    Path tempDir;

    @Test
    void schemaRealizationEmitsScheduledEventFromInternalTableRegistry() throws Exception {
        Path outRoot = tempDir.resolve("app");
        GeneratedDatabasePlan plan = new GeneratedDatabasePlan(
                "scheduled-event-test",
                DatabaseEngine.H2_LOCAL,
                DatabaseEngine.H2_LOCAL.storageMode(),
                true,
                "scheduled-event-test",
                "scheduled-event-test",
                "test",
                tempDir.resolve("data").toString(),
                "db-instance",
                "",
                "",
                0,
                0,
                "jdbc:h2:mem:scheduled-event-test",
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
        CompiledModel model = new CompiledModel("test", "1.0.0", "1.0.0", Map.<String, CompiledConcept>of());

        new SchemaRealizationEmitter().emit(model, outRoot, plan, tempDir.resolve("model.json"));

        String sql = Files.readString(outRoot.resolve("src/main/resources/db/schema-realization/V1__npdev_schema_realization.sql"));
        assertTrue(sql.contains("CREATE TABLE IF NOT EXISTS npdev_scheduled_event"),
                "Generated schema SQL must include npdev_scheduled_event");

        JsonNode manifest = OBJECT_MAPPER.readTree(Files.readString(
                outRoot.resolve("src/main/resources/npdev/db/schema-realization-manifest.json")
        ));
        boolean scheduledEventListed = false;
        for (JsonNode table : manifest.path("internalTables")) {
            if ("npdev_scheduled_event".equals(table.asText())) {
                scheduledEventListed = true;
            }
        }
        assertTrue(scheduledEventListed,
                "Generated schema manifest must list npdev_scheduled_event as an internal table");
    }
}
