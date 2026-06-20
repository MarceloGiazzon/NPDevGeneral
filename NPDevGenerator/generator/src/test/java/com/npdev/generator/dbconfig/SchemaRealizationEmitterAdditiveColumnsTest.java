package com.npdev.generator.dbconfig;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.npdev.dsl.v1.compiled.CompiledConcept;
import com.npdev.dsl.v1.compiled.CompiledField;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.compiled.SqlIdentifierSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the Phase 6 "safe-additive fast path" emitter output: a Flyway repeatable migration that
 * adds non-bond columns only, plus the manifest fields {@code SchemaLifecycleExecutor} uses to
 * classify a fingerprint mismatch as safe. Bond/FK columns must stay out of both, since R__ cannot
 * add a foreign key safely and the runtime relies on that exclusion to fall back to the destructive
 * path for structural changes.
 */
final class SchemaRealizationEmitterAdditiveColumnsTest {

    @TempDir
    Path tempDir;

    @Test
    void additiveScriptAndManifestExcludeBondColumnsButIncludeNonBondColumns() throws Exception {
        CompiledField customerId = new CompiledField(
                "customerId", "string", "String", false, false, false, List.of(), "Customer");
        CompiledConcept customer = new CompiledConcept(
                "Customer", "Customer", "customers",
                List.of(new CompiledField("id", "uuid", "java.util.UUID", true, true, false))
        );
        CompiledConcept order = new CompiledConcept(
                "Order", "Order", "orders",
                List.of(
                        new CompiledField("id", "uuid", "java.util.UUID", true, true, false),
                        new CompiledField("name", "string", "String", false, true, false),
                        customerId
                )
        );
        CompiledModel model = new CompiledModel("test", "1.0.0", "1.0.0", Map.of(
                order.getName(), order,
                customer.getName(), customer
        ));

        Path outRoot = tempDir.resolve("app");
        new SchemaRealizationEmitter().emit(model, outRoot, plan(), tempDir.resolve("model.json"));

        Path schemaDir = outRoot.resolve("src/main/resources/db/schema-realization");
        String additiveSql = Files.readString(schemaDir.resolve("R__npdev_schema_additive_columns.sql"));
        String nameColumn = SqlIdentifierSupport.columnName(order.getFields().get(1));
        String customerIdColumn = SqlIdentifierSupport.columnName(customerId);

        assertTrue(additiveSql.contains("ALTER TABLE orders ADD COLUMN IF NOT EXISTS " + nameColumn),
                additiveSql);
        assertFalse(additiveSql.contains(customerIdColumn), "bond column must not appear in the additive script: " + additiveSql);

        Path resourcesRoot = outRoot.resolve("src/main/resources");
        JsonNode manifest = new ObjectMapper().readTree(
                resourcesRoot.resolve("npdev/db/schema-realization-manifest.json").toFile());
        JsonNode orderColumns = manifest.path("businessTableColumns").path("orders");
        JsonNode orderAdditive = manifest.path("businessTableAdditiveColumns").path("orders");

        assertTrue(containsText(orderColumns, customerIdColumn), "full column set must still list the bond column: " + orderColumns);
        assertTrue(containsText(orderColumns, nameColumn));
        assertFalse(containsText(orderAdditive, customerIdColumn), "additive-eligible set must exclude the bond column: " + orderAdditive);
        assertTrue(containsText(orderAdditive, nameColumn));
    }

    private static boolean containsText(JsonNode array, String value) {
        if (array == null || !array.isArray()) {
            return false;
        }
        for (JsonNode item : array) {
            if (value.equals(item.asText())) {
                return true;
            }
        }
        return false;
    }

    private GeneratedDatabasePlan plan() {
        return new GeneratedDatabasePlan(
                "additive-columns-test",
                DatabaseEngine.H2_LOCAL,
                DatabaseEngine.H2_LOCAL.storageMode(),
                true,
                "additive-columns-test",
                "additive-columns-test",
                "test",
                tempDir.resolve("data").toString(),
                "db-instance",
                "",
                "",
                0,
                0,
                "jdbc:h2:mem:additive-columns-test",
                "org.h2.Driver",
                "sa",
                "",
                "",
                0,
                "",
                "",
                false,
                true,
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
    }
}
