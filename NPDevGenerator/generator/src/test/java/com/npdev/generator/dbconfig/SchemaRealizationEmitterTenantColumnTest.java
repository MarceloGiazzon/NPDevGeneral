package com.npdev.generator.dbconfig;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.npdev.dsl.v1.compiled.CompiledConcept;
import com.npdev.dsl.v1.compiled.CompiledField;
import com.npdev.dsl.v1.compiled.CompiledModel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the tenant-isolation schema contract: every business table gets a {@code tenant_id} column
 * (NOT NULL on the V1 CREATE TABLE; nullable via the additive path for already-existing tables, the
 * same treatment "version" got), and the manifest fields {@code SchemaLifecycleExecutor} uses to
 * classify additive changes include it.
 */
final class SchemaRealizationEmitterTenantColumnTest {

    @TempDir
    Path tempDir;

    @Test
    void v1AndAdditiveScriptAndManifestAllIncludeTenantId() throws Exception {
        CompiledConcept order = new CompiledConcept(
                "Order", "Order", "orders",
                List.of(
                        new CompiledField("id", "uuid", "java.util.UUID", true, true, false),
                        new CompiledField("name", "string", "String", false, true, false),
                        new CompiledField("code", "string", "String", false, true, true)
                )
        );
        CompiledModel model = new CompiledModel("test", "1.0.0", "1.0.0", Map.of(order.getName(), order));

        Path outRoot = tempDir.resolve("app");
        new SchemaRealizationEmitter().emit(model, outRoot, plan(), tempDir.resolve("model.json"));

        Path schemaDir = outRoot.resolve("src/main/resources/db/schema-realization");
        String v1Sql = Files.readString(schemaDir.resolve("V1__npdev_schema_realization.sql"));
        // V1 column is backfill-safe (DEFAULT 'default') so even a path that omits tenant_id never
        // writes NULL, and an ordinary unique field is unique WITHIN a tenant, not globally.
        assertTrue(v1Sql.contains("tenant_id VARCHAR(120) NOT NULL DEFAULT 'default'"), v1Sql);
        assertTrue(v1Sql.contains("ON orders (tenant_id, code)"), v1Sql);

        String additiveSql = Files.readString(schemaDir.resolve("R__npdev_schema_additive_columns.sql"));
        // Additive path backfills pre-existing rows via DEFAULT so in-place upgrades stay visible.
        assertTrue(additiveSql.contains("ADD COLUMN IF NOT EXISTS tenant_id VARCHAR(120) DEFAULT 'default'"), additiveSql);

        JsonNode manifest = new ObjectMapper().readTree(
                outRoot.resolve("src/main/resources/npdev/db/schema-realization-manifest.json").toFile());
        assertTrue(containsText(manifest.path("businessTableColumns").path("orders"), "tenant_id"));
        assertTrue(containsText(manifest.path("businessTableAdditiveColumns").path("orders"), "tenant_id"));
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
                "tenant-column-test",
                DatabaseEngine.H2_LOCAL,
                DatabaseEngine.H2_LOCAL.storageMode(),
                true,
                false, // externallyProvisioned (STOR-14) -- NPDev provisioned this test's database
                "tenant-column-test",
                "tenant-column-test",
                "test",
                tempDir.resolve("data").toString(),
                "db-instance",
                "",
                "",
                0,
                0,
                "jdbc:h2:mem:tenant-column-test",
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
