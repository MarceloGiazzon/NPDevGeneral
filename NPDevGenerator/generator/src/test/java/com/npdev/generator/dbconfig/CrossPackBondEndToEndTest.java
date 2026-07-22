package com.npdev.generator.dbconfig;

import com.npdev.dsl.v1.compiled.CompiledConcept;
import com.npdev.dsl.v1.compiled.CompiledField;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.compiled.CompiledReferenceSemantics;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * BOND-B6: a bond from a root model concept to a pack-namespaced concept ({@code catalog::Product})
 * has never been tested at the DDL-emission level. FlywayEmitter (the class the original bond gaps
 * plan targeted for this test) was later deleted as dead code -- SchemaRealizationEmitter is the
 * live generator path, so this exercises that instead.
 */
final class CrossPackBondEndToEndTest {

    @TempDir
    Path tempDir;

    @Test
    void crossPackBondProducesCorrectFkDdlThroughSchemaRealizationEmitter() throws Exception {
        CompiledField skuAnchor = new CompiledField(
                "skuId", "string", "String", false, false, true,
                List.of(), null, null, null, null, List.of(), null, "anchor");
        // Table name derived from "catalog::Product" via SqlIdentifierSupport.tableName():
        // toSnakePlural("catalog::Product") -> "catalog_products".
        CompiledConcept product = new CompiledConcept(
                "catalog::Product", "CatalogProduct", null,
                List.of(
                        new CompiledField("id", "uuid", "java.util.UUID", true, true, false),
                        skuAnchor
                )
        );

        CompiledReferenceSemantics viaSku = new CompiledReferenceSemantics(
                "catalog::Product", false, null, List.of(), List.of(), null, null, List.of(), null, null,
                "skuId", "restrict");
        CompiledConcept order = new CompiledConcept(
                "Order", "Order", "orders",
                List.of(
                        new CompiledField("id", "uuid", "java.util.UUID", true, true, false),
                        new CompiledField("productId", "reference", "java.util.UUID", false, true, false,
                                List.of(), "catalog::Product", viaSku, null, null, List.of(), null, null)
                )
        );

        Map<String, CompiledConcept> concepts = Map.of(
                product.getName(), product,
                order.getName(), order
        );
        String sql = emit(new CompiledModel("catalog-app", "1.0.0", "1.0.0", concepts));

        assertTrue(sql.contains("CREATE TABLE IF NOT EXISTS catalog_products"),
                "catalog::Product must produce SQL table catalog_products. SQL:\n" + sql);
        assertFalse(sql.contains("::"), "No '::' characters should appear in generated SQL. SQL:\n" + sql);
        assertTrue(sql.contains("REFERENCES catalog_products (sku_id)"),
                "FK must reference catalog_products(sku_id). SQL:\n" + sql);
        assertTrue(sql.contains("ON DELETE RESTRICT"), "onDelete=restrict must be honored. SQL:\n" + sql);
    }

    private String emit(CompiledModel model) throws Exception {
        Path outRoot = tempDir.resolve("app");
        new SchemaRealizationEmitter().emit(model, outRoot, plan(), tempDir.resolve("model.json"));
        return Files.readString(outRoot.resolve("src/main/resources/db/schema-realization/V1__npdev_schema_realization.sql"));
    }

    private GeneratedDatabasePlan plan() {
        return new GeneratedDatabasePlan(
                "cross-pack-bond-test",
                DatabaseEngine.H2_LOCAL,
                DatabaseEngine.H2_LOCAL.storageMode(),
                true,
                "cross-pack-bond-test",
                "cross-pack-bond-test",
                "test",
                tempDir.resolve("data").toString(),
                "db-instance",
                "",
                "",
                0,
                0,
                "jdbc:h2:mem:cross-pack-bond-test",
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
