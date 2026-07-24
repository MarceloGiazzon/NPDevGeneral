package com.finalexec.db;

import com.finalexec.db.schemastate.DesiredColumn;
import com.finalexec.db.schemastate.DesiredSchema;
import com.finalexec.db.schemastate.DesiredTable;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure unit test (schema-engine rebuild, P2.1): {@link DesiredSchemaFactory} turns a {@code SchemaManifest}
 * + {@code ColumnFacts} into a {@link DesiredSchema} with the right per-column provenance. No DB.
 */
class DesiredSchemaFactoryTest {

    @Test
    void buildsDesiredSchemaFromManifestAndColumnFacts() {
        SchemaLifecycleExecutor.SchemaManifest manifest = new SchemaLifecycleExecutor.SchemaManifest(
                "H2Local", "jdbc", true, "sha256:x",
                List.of(),                                          // internalTables
                List.of("orders", "clients"),                       // businessTables
                Map.of("orders", List.of("id", "name", "qty", "email"), "clients", List.of("id")),  // columns
                Map.of("orders", List.of("name", "qty", "email"), "clients", List.of()),             // additive
                Map.of("orders", Map.of("id", "UUID", "name", "VARCHAR(120)", "qty", "INTEGER", "email", "VARCHAR(200)"),
                        "clients", Map.of("id", "UUID")),           // types
                Map.of("orders", Map.of("email", "email_addr")),    // renamedColumns (email <- email_addr)
                Map.of("clients", "old_clients"),                   // table renames (clients <- old_clients)
                false, "KeepExistingIfCompatible", "NpdevOwnedTablesOnly", "", "",
                Map.of("orders", List.of("id", "name"), "clients", List.of("id")),  // required
                Map.of("orders", Map.of("name", "\"x\"")),          // literal defaults
                Map.of(),                                           // expression defaults
                Map.of("orders", List.of(new SchemaLifecycleExecutor.UniqueConstraintDecl(
                        "uq_orders_email", List.of("email"), false)))  // unique constraints
        );

        DesiredSchema desired = DesiredSchemaFactory.fromManifest(manifest);

        assertEquals(2, desired.tables().size());
        DesiredTable orders = desired.tables().get("orders");
        assertNotNull(orders, "orders table must be built");

        DesiredColumn name = orders.columns().get("name");
        assertNotNull(name);
        assertTrue(name.requiredByModel(), "name is required by the model");
        assertFalse(name.nullable(), "a required model field is NOT NULL");
        assertEquals("\"x\"", name.literalDefault(), "literal default must carry through");
        assertNotNull(name.normalizedSqlType(), "type must be normalized, not null");
        assertFalse(name.platformManaged(), "name is a model field, not platform-managed");

        assertTrue(orders.columns().get("qty").nullable(), "qty is not required -> nullable");
        assertTrue(orders.columns().get("id").platformManaged(), "id is a platform-managed column");
        assertEquals("email_addr", orders.columns().get("email").renamedFromColumn(),
                "declared column rename must carry through");

        assertEquals(1, orders.uniques().size());
        assertEquals(List.of("email"), orders.uniques().get(0).columns(), "unique on [email]");

        assertEquals("old_clients", desired.tables().get("clients").renamedFromTable(),
                "declared table rename must carry through");
    }
}
