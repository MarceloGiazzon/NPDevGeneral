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
 * adds new columns to an already-existing table, plus the manifest fields
 * {@code SchemaLifecycleExecutor} uses to classify a fingerprint mismatch as safe.
 *
 * <p>LNCH-1 P5 (5.3): a NULLABLE bond/FK column is now additive-eligible (an FK permits NULLs, so
 * it can be added -- with its own FK constraint -- to an already-existing table exactly like any
 * other nullable field); a REQUIRED bond is not (no automatic literal-default backfill is possible
 * for a foreign key target in v1). This test now pins BOTH halves of that split, replacing its
 * pre-Phase-5 assumption that every bond column was unconditionally excluded.
 */
final class SchemaRealizationEmitterAdditiveColumnsTest {

    @TempDir
    Path tempDir;

    @Test
    void additiveScriptAndManifestIncludeNullableBondsButExcludeRequiredBonds() throws Exception {
        CompiledField customerId = new CompiledField(
                "customerId", "string", "String", false, false, false, List.of(), "Customer");
        CompiledField ownerId = new CompiledField(
                "ownerId", "string", "String", false, true, false, List.of(), "Customer");
        CompiledConcept customer = new CompiledConcept(
                "Customer", "Customer", "customers",
                List.of(new CompiledField("id", "uuid", "java.util.UUID", true, true, false))
        );
        CompiledConcept order = new CompiledConcept(
                "Order", "Order", "orders",
                List.of(
                        new CompiledField("id", "uuid", "java.util.UUID", true, true, false),
                        new CompiledField("name", "string", "String", false, true, false),
                        customerId,
                        ownerId
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
        String ownerIdColumn = SqlIdentifierSupport.columnName(ownerId);

        assertTrue(additiveSql.contains("ALTER TABLE orders ADD COLUMN IF NOT EXISTS " + nameColumn),
                additiveSql);
        assertTrue(additiveSql.contains("ALTER TABLE orders ADD COLUMN IF NOT EXISTS " + customerIdColumn),
                "a NULLABLE bond column must now be additive-eligible (LNCH-1 P5 5.3): " + additiveSql);
        assertTrue(additiveSql.contains("ADD CONSTRAINT") && additiveSql.contains("FOREIGN KEY (" + customerIdColumn + ")"),
                "a nullable bond added via the additive path must get its own FK constraint: " + additiveSql);
        assertFalse(additiveSql.contains("ADD COLUMN IF NOT EXISTS " + ownerIdColumn),
                "a REQUIRED bond column must still be excluded from the additive script: " + additiveSql);

        Path resourcesRoot = outRoot.resolve("src/main/resources");
        JsonNode manifest = new ObjectMapper().readTree(
                resourcesRoot.resolve("npdev/db/schema-realization-manifest.json").toFile());
        JsonNode orderColumns = manifest.path("businessTableColumns").path("orders");
        JsonNode orderAdditive = manifest.path("businessTableAdditiveColumns").path("orders");

        assertTrue(containsText(orderColumns, customerIdColumn), "full column set must still list the bond column: " + orderColumns);
        assertTrue(containsText(orderColumns, ownerIdColumn));
        assertTrue(containsText(orderColumns, nameColumn));
        assertTrue(containsText(orderAdditive, customerIdColumn),
                "additive-eligible set must now include the NULLABLE bond column: " + orderAdditive);
        assertFalse(containsText(orderAdditive, ownerIdColumn),
                "additive-eligible set must still exclude the REQUIRED bond column: " + orderAdditive);
        assertTrue(containsText(orderAdditive, nameColumn));

        JsonNode orderRequired = manifest.path("businessTableRequiredColumns").path("orders");
        assertTrue(containsText(orderRequired, ownerIdColumn), "required-columns manifest key must list the required bond: " + orderRequired);
        assertTrue(containsText(orderRequired, nameColumn));
        assertFalse(containsText(orderRequired, customerIdColumn));
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
