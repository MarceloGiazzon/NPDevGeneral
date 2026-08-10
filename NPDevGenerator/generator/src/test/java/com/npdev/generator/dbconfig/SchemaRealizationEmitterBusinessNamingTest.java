package com.npdev.generator.dbconfig;

import com.npdev.dsl.v1.compiled.CompiledConcept;
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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class SchemaRealizationEmitterBusinessNamingTest {

    @TempDir
    Path tempDir;

    @Test
    void schemaRealizationUsesSqlIdentifierSupportColumnNamesForBusinessFields() throws Exception {
        String longFieldName = "customerReferenceSegment".repeat(5);
        CompiledField longField = new CompiledField(longFieldName, "string", "String", false, false, false);
        CompiledConcept order = new CompiledConcept(
                "Order", "Order", "orders",
                List.of(
                        new CompiledField("id", "uuid", "java.util.UUID", true, true, false),
                        longField
                )
        );
        CompiledField sku = new CompiledField("skuId", "string", "String", false, false, true);
        CompiledConcept product = new CompiledConcept(
                "catalog::Product", "CatalogProduct", "catalog_products",
                List.of(
                        new CompiledField("id", "uuid", "java.util.UUID", true, true, false),
                        sku
                )
        );
        Map<String, CompiledConcept> concepts = Map.of(
                order.getName(), order,
                product.getName(), product
        );

        String sql = emit(new CompiledModel("test", "1.0.0", "1.0.0", concepts));

        String expectedColumn = SqlIdentifierSupport.columnName(longField);
        String manualSnakeOnlyColumn = SqlIdentifierSupport.toSnake(longFieldName);
        assertTrue(sql.contains(expectedColumn + " VARCHAR(255)"), sql);
        assertFalse(sql.contains(manualSnakeOnlyColumn + " VARCHAR(255)"), sql);
        assertTrue(sql.contains("CREATE TABLE IF NOT EXISTS catalog_products"), sql);
        assertTrue(sql.contains(SqlIdentifierSupport.columnName(sku) + " VARCHAR(255)"), sql);
    }

    private String emit(CompiledModel model) throws Exception {
        Path outRoot = tempDir.resolve("app");
        new SchemaRealizationEmitter().emit(model, outRoot, plan(), tempDir.resolve("model.json"));
        return Files.readString(outRoot.resolve("src/main/resources/db/schema-realization/V1__npdev_schema_realization.sql"));
    }

    private GeneratedDatabasePlan plan() {
        return new GeneratedDatabasePlan(
                "business-naming-test",
                DatabaseEngine.H2_LOCAL,
                DatabaseEngine.H2_LOCAL.storageMode(),
                true,
                false, // externallyProvisioned (STOR-14) -- NPDev provisioned this test's database
                "business-naming-test",
                "business-naming-test",
                "test",
                tempDir.resolve("data").toString(),
                "db-instance",
                "",
                "",
                0,
                0,
                "jdbc:h2:mem:business-naming-test",
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
