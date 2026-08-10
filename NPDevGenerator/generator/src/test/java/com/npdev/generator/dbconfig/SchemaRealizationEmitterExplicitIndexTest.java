package com.npdev.generator.dbconfig;

import com.npdev.dsl.v1.compiled.CompiledConcept;
import com.npdev.dsl.v1.compiled.CompiledField;
import com.npdev.dsl.v1.compiled.CompiledIndex;
import com.npdev.dsl.v1.compiled.CompiledModel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * LNCH-6 (2/2): author-declared {@code indexes:[]} on a concept -- the escape hatch for query
 * patterns the implicit panel/query-predicate indexing can't express (multi-column indexes, or a
 * field only ever touched by hand-authored SQL/procedures).
 */
final class SchemaRealizationEmitterExplicitIndexTest {

    @TempDir
    Path tempDir;

    @Test
    void explicitIndexesEmitPlainAndUniqueTenantCompositeVariants() throws Exception {
        CompiledConcept shipment = new CompiledConcept(
                "Shipment", "Shipment", "shipments",
                List.of(
                        new CompiledField("id", "uuid", "java.util.UUID", true, true, false),
                        new CompiledField("carrier", "string", "String", false, true, false),
                        new CompiledField("trackingCode", "string", "String", false, true, false),
                        new CompiledField("warehouseId", "string", "String", false, true, false)
                ),
                List.of(),
                List.of(),
                null,
                null,
                null,
                null,
                List.of(
                        // Multi-column, unnamed -> name derived from the joined columns.
                        new CompiledIndex(null, List.of("carrier", "warehouseId"), false),
                        // Named, unique -> tenant-composite UNIQUE constraint, not a plain index.
                        new CompiledIndex("ux_shipment_tracking", List.of("trackingCode"), true),
                        // A field that does not exist on the concept is dropped, not fatal.
                        new CompiledIndex("dead", List.of("doesNotExist"), false)
                )
        );
        CompiledModel model = new CompiledModel("test", "1.0.0", "1.0.0", Map.of(shipment.getName(), shipment));

        Path outRoot = tempDir.resolve("app");
        new SchemaRealizationEmitter().emit(model, outRoot, plan(), tempDir.resolve("model.json"));

        String v1Sql = Files.readString(
                outRoot.resolve("src/main/resources/db/schema-realization/V1__npdev_schema_realization.sql"));

        assertTrue(v1Sql.contains(
                "CREATE INDEX IF NOT EXISTS idxx_shipments_carrier_warehouse_id ON shipments (tenant_id, carrier, warehouse_id);"),
                v1Sql);
        assertTrue(v1Sql.contains(
                "ADD CONSTRAINT uqx_shipments_ux_shipment_tracking UNIQUE (tenant_id, tracking_code)"), v1Sql);
        assertFalse(v1Sql.contains("dead"), "an index with no resolvable fields must be skipped entirely: " + v1Sql);
    }

    private GeneratedDatabasePlan plan() {
        return new GeneratedDatabasePlan(
                "explicit-index-test",
                DatabaseEngine.H2_LOCAL,
                DatabaseEngine.H2_LOCAL.storageMode(),
                true,
                false, // externallyProvisioned (STOR-14) -- NPDev provisioned this test's database
                "explicit-index-test",
                "explicit-index-test",
                "test",
                tempDir.resolve("data").toString(),
                "db-instance",
                "",
                "",
                0,
                0,
                "jdbc:h2:mem:explicit-index-test",
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
