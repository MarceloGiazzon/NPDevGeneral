package com.npdev.generator.dbconfig;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.npdev.dsl.v1.compiled.CompiledConcept;
import com.npdev.dsl.v1.compiled.CompiledField;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.compiled.CompiledPanel;
import com.npdev.dsl.v1.compiled.CompiledPanelDataSource;
import com.npdev.dsl.v1.compiled.CompiledPanelLayout;
import com.npdev.dsl.v1.compiled.CompiledQuery;
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
 * LNCH-6: fields a compiled panel/query filters, sorts, or joins children by get a tenant-composite
 * secondary index, so the LNCH-5 SQL push-down uses index scans; already-indexed fields (primary key,
 * unique fields) are not re-indexed.
 */
final class SchemaRealizationEmitterSecondaryIndexTest {

    @TempDir
    Path tempDir;

    @Test
    void panelAndQueryPredicatesGetTenantCompositeIndexes() throws Exception {
        CompiledConcept order = new CompiledConcept(
                "Order", "Order", "orders",
                List.of(
                        new CompiledField("id", "uuid", "java.util.UUID", true, true, false),
                        new CompiledField("status", "string", "String", false, true, false),
                        new CompiledField("priority", "int", "Integer", false, true, false),
                        new CompiledField("code", "string", "String", false, true, true)
                )
        );
        CompiledConcept orderItem = new CompiledConcept(
                "OrderItem", "OrderItem", "order_items",
                List.of(
                        new CompiledField("id", "uuid", "java.util.UUID", true, true, false),
                        new CompiledField("orderId", "string", "String", false, true, false),
                        new CompiledField("sku", "string", "String", false, true, false)
                )
        );

        CompiledQuery openOrders = new CompiledQuery(
                "openOrders", "Order", "status == 'open'", List.of("priority desc"),
                null, List.of(), List.of(), null, Map.of(), List.of(), List.of(), null);

        CompiledPanelDataSource ordersSource = new CompiledPanelDataSource(
                "orders", "Order", null, null, Map.of(), null, null, null, List.of(), List.of());
        CompiledPanelDataSource itemsSource = new CompiledPanelDataSource(
                "items", "OrderItem", null, null, Map.of(), "orders", "id", "orderId", List.of(), List.of());
        CompiledPanel panel = new CompiledPanel(
                "OrderPanel", "/orders", "Orders", List.of(ordersSource, itemsSource),
                new CompiledPanelLayout("table", List.of(), List.of(), Map.of()),
                List.of(), null, null, List.of(), Map.of(), Map.of(), null);

        CompiledModel model = new CompiledModel(
                "test", "1.0.0", "1.0.0",
                Map.of(order.getName(), order, orderItem.getName(), orderItem),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(openOrders), List.of(), List.of(), List.of(panel), List.of(), List.of(), List.of());

        Path outRoot = tempDir.resolve("app");
        new SchemaRealizationEmitter().emit(model, outRoot, plan(), tempDir.resolve("model.json"));

        String v1Sql = Files.readString(
                outRoot.resolve("src/main/resources/db/schema-realization/V1__npdev_schema_realization.sql"));

        // Query where-field and orderBy-field on Order -> tenant-composite indexes.
        assertTrue(v1Sql.contains("CREATE INDEX IF NOT EXISTS idx_orders_status ON orders (tenant_id, status);"), v1Sql);
        assertTrue(v1Sql.contains("CREATE INDEX IF NOT EXISTS idx_orders_priority ON orders (tenant_id, priority);"), v1Sql);
        // Panel nested data source's childField on OrderItem -> tenant-composite index.
        assertTrue(v1Sql.contains("CREATE INDEX IF NOT EXISTS idx_order_items_order_id ON order_items (tenant_id, order_id);"), v1Sql);

        // Already-indexed fields are never re-indexed: the primary key and the unique 'code' field
        // (which gets a ux_ tenant-composite unique index instead).
        assertFalse(v1Sql.contains("idx_orders_id "), v1Sql);
        assertFalse(v1Sql.contains("CREATE INDEX IF NOT EXISTS idx_orders_code "), v1Sql);
        assertTrue(v1Sql.contains("ux_orders_code"), "unique field still gets its unique index: " + v1Sql);
    }

    /**
     * REG-145: {@code businessTableIndexes} must describe the SAME index this class's DDL actually
     * creates -- a tenant-composite {@code (tenant_id, column)}, not a bare {@code (column)}. A
     * manifest entry with the wrong column list is invisible to {@code ConstraintSurplusClassifier}'s
     * order-sensitive match (never matches the live composite index), which is exactly how REG-129's
     * fix shipped enumerating these indexes while B3's classifier kept reporting all of them as
     * FOREIGN anyway -- re-measured live against WmsOffice on current code.
     */
    @Test
    void implicitSecondaryIndexManifestEntryIsTenantCompositeNotBareColumn() throws Exception {
        CompiledConcept order = new CompiledConcept(
                "Order", "Order", "orders",
                List.of(
                        new CompiledField("id", "uuid", "java.util.UUID", true, true, false),
                        new CompiledField("status", "string", "String", false, true, false)
                ));

        CompiledQuery openOrders = new CompiledQuery(
                "openOrders", "Order", "status == 'open'", List.of(),
                null, List.of(), List.of(), null, Map.of(), List.of(), List.of(), null);

        CompiledModel model = new CompiledModel(
                "test", "1.0.0", "1.0.0", Map.of(order.getName(), order),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(openOrders), List.of(), List.of(), List.of(), List.of(), List.of(), List.of());

        Path outRoot = tempDir.resolve("app-manifest");
        new SchemaRealizationEmitter().emit(model, outRoot, plan(), tempDir.resolve("model.json"));

        JsonNode manifest = new ObjectMapper().readTree(Files.readString(
                outRoot.resolve("src/main/resources/npdev/db/schema-realization-manifest.json")));
        JsonNode indexes = manifest.path("businessTableIndexes").path("orders");
        assertTrue(indexes.isArray() && indexes.size() >= 1,
                "the implicit secondary index must appear in businessTableIndexes: " + manifest.path("businessTableIndexes"));

        JsonNode statusIndex = null;
        for (JsonNode candidate : indexes) {
            JsonNode columns = candidate.path("columns");
            if (columns.size() >= 1 && "status".equals(columns.get(columns.size() - 1).asText())) {
                statusIndex = candidate;
            }
        }
        assertTrue(statusIndex != null, "no manifest entry named 'status' as a column: " + indexes);
        assertEquals(2, statusIndex.path("columns").size(),
                "must be the tenant-composite pair (tenant_id, status), matching the DDL exactly: " + statusIndex);
        assertEquals("tenant_id", statusIndex.path("columns").get(0).asText(), statusIndex.toString());
        assertEquals("status", statusIndex.path("columns").get(1).asText(), statusIndex.toString());
        assertEquals(false, statusIndex.path("unique").asBoolean(), statusIndex.toString());
    }

    private GeneratedDatabasePlan plan() {
        return new GeneratedDatabasePlan(
                "secondary-index-test",
                DatabaseEngine.H2_LOCAL,
                DatabaseEngine.H2_LOCAL.storageMode(),
                true,
                false, // externallyProvisioned (STOR-14) -- NPDev provisioned this test's database
                "secondary-index-test",
                "secondary-index-test",
                "test",
                tempDir.resolve("data").toString(),
                "db-instance",
                "",
                "",
                0,
                0,
                "jdbc:h2:mem:secondary-index-test",
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
