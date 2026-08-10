package com.npdev.generator.dbconfig;

import com.npdev.dsl.v1.compiled.CompiledConcept;
import com.npdev.dsl.v1.compiled.CompiledField;
import com.npdev.dsl.v1.compiled.CompiledModel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins a real regression found while auditing existing samples against the tenant_id/version
 * platform columns (Phase T1): a model field whose column name collides with one of those (e.g. a
 * hand-modeled "tenantId" reference field, as restaurant-saas-multitenant declares on nearly every
 * concept) used to silently produce a CREATE TABLE listing the same column twice -- invalid SQL that
 * only failed at the database, with no clue what caused it. Must now fail fast at generation time.
 */
final class SchemaRealizationEmitterReservedColumnTest {

    @TempDir
    Path tempDir;

    @Test
    void fieldNamedTenantIdCollidesWithThePlatformColumnAndFailsFast() {
        CompiledConcept order = new CompiledConcept(
                "DiningOrder", "DiningOrder", "dining_orders",
                List.of(
                        new CompiledField("id", "uuid", "java.util.UUID", true, true, false),
                        new CompiledField("tenantId", "uuid", "java.util.UUID", false, true, false)
                )
        );
        CompiledModel model = new CompiledModel("test", "1.0.0", "1.0.0", Map.of(order.getName(), order));

        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> new SchemaRealizationEmitter().emit(model, tempDir.resolve("app"), plan(), tempDir.resolve("model.json")));
        assertTrue(exception.getMessage().contains("DiningOrder"), exception.getMessage());
        assertTrue(exception.getMessage().contains("tenant_id"), exception.getMessage());
    }

    @Test
    void fieldNamedVersionAlsoCollidesAndFailsFast() {
        CompiledConcept order = new CompiledConcept(
                "Order", "Order", "orders",
                List.of(
                        new CompiledField("id", "uuid", "java.util.UUID", true, true, false),
                        new CompiledField("version", "int", "Integer", false, false, false)
                )
        );
        CompiledModel model = new CompiledModel("test", "1.0.0", "1.0.0", Map.of(order.getName(), order));

        assertThrows(IllegalStateException.class,
                () -> new SchemaRealizationEmitter().emit(model, tempDir.resolve("app"), plan(), tempDir.resolve("model.json")));
    }

    @Test
    void ordinaryFieldNamesAreUnaffected() throws Exception {
        CompiledConcept order = new CompiledConcept(
                "Order", "Order", "orders",
                List.of(
                        new CompiledField("id", "uuid", "java.util.UUID", true, true, false),
                        new CompiledField("tenantName", "string", "String", false, false, false)
                )
        );
        CompiledModel model = new CompiledModel("test", "1.0.0", "1.0.0", Map.of(order.getName(), order));

        new SchemaRealizationEmitter().emit(model, tempDir.resolve("app"), plan(), tempDir.resolve("model.json"));
    }

    private GeneratedDatabasePlan plan() {
        return new GeneratedDatabasePlan(
                "reserved-column-test",
                DatabaseEngine.H2_LOCAL,
                DatabaseEngine.H2_LOCAL.storageMode(),
                true,
                false, // externallyProvisioned (STOR-14) -- NPDev provisioned this test's database
                "reserved-column-test",
                "reserved-column-test",
                "test",
                tempDir.resolve("data").toString(),
                "db-instance",
                "",
                "",
                0,
                0,
                "jdbc:h2:mem:reserved-column-test",
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
