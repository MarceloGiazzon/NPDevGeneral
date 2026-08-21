package com.npdev.runtime.support;

import com.npdev.dsl.v1.compiled.CompiledConcept;
import com.npdev.dsl.v1.compiled.CompiledField;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.compiled.CompiledReferenceSemantics;
import com.npdev.kernel.KernelRunner;
import com.npdev.kernel.ports.EventBus;
import com.npdev.kernel.ports.InvariantEngine;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class GeneratedCrudRuntimeSupportBondJdbcTest {

    @Test
    void bondMembershipOperationsUseRealJunctionTableAndMapForeignKeyViolation() throws Exception {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:runtime_bond_membership;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        initializeSchema(dataSource);

        UUID bundleId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("INSERT INTO cat_products (id, sku_id) VALUES ('" + productId + "', 'SKU-1')");
            statement.executeUpdate("INSERT INTO catalogs (id) VALUES ('" + bundleId + "')");
        }

        GeneratedCrudRuntimeSupport support = new GeneratedCrudRuntimeSupport(
                compiledModel(),
                kernelRunner(),
                null,
                null,
                null,
                dataSource
        );

        support.addBondMember("Catalog", bundleId, "productSkus", "SKU-1");
        assertEquals(List.of("SKU-1"), support.listBondMembers("Catalog", bundleId, "productSkus"));

        GeneratedCrudRuntimeSupport.InvariantViolationException violation = assertThrows(
                GeneratedCrudRuntimeSupport.InvariantViolationException.class,
                () -> support.addBondMember("Catalog", bundleId, "productSkus", "MISSING-SKU")
        );
        assertEquals(422, violation.statusCode());
        assertEquals("reference_integrity_failed", violation.violations().get(0).code());

        support.removeBondMember("Catalog", bundleId, "productSkus", "SKU-1");
        assertEquals(List.of(), support.listBondMembers("Catalog", bundleId, "productSkus"));
    }

    private static void initializeSchema(DataSource dataSource) throws Exception {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE cat_products (
                      id UUID PRIMARY KEY,
                      sku_id VARCHAR(255) NOT NULL UNIQUE
                    )
                    """);
            statement.execute("""
                    CREATE TABLE catalogs (
                      id UUID PRIMARY KEY
                    )
                    """);
            statement.execute("""
                    CREATE TABLE catalogs_product_skus (
                      source_id UUID NOT NULL,
                      target_sku_id VARCHAR(255) NOT NULL,
                      PRIMARY KEY (source_id, target_sku_id),
                      CONSTRAINT fk_catalog_product_source
                        FOREIGN KEY (source_id) REFERENCES catalogs (id) ON DELETE CASCADE,
                      CONSTRAINT fk_catalog_product_target
                        FOREIGN KEY (target_sku_id) REFERENCES cat_products (sku_id)
                    )
                    """);
        }
    }

    private static CompiledModel compiledModel() {
        CompiledField skuAnchor = new CompiledField(
                "skuId", "string", "String", false, true, true,
                List.of(), null, null, null, null, List.of(), null, "anchor");
        CompiledConcept product = new CompiledConcept(
                "cat::Product", "CatProduct", "cat_products",
                List.of(
                        new CompiledField("id", "uuid", "java.util.UUID", true, true, false),
                        skuAnchor
                )
        );

        CompiledReferenceSemantics productSkus = new CompiledReferenceSemantics(
                "cat::Product", true, null, List.of(), List.of(), null, null, List.of(), null, null,
                "skuId", "cascade");
        CompiledConcept catalog = new CompiledConcept(
                "Catalog", "Catalog", "catalogs",
                List.of(
                        new CompiledField("id", "uuid", "java.util.UUID", true, true, false),
                        new CompiledField("productSkus", "reference", "String", false, false, false,
                                List.of(), "cat::Product", productSkus, null, null, List.of(), null, null)
                )
        );

        Map<String, CompiledConcept> concepts = new LinkedHashMap<>();
        concepts.put(product.getName(), product);
        concepts.put(catalog.getName(), catalog);
        return new CompiledModel("demo", "1.0.0", "v1", concepts);
    }

    private static KernelRunner kernelRunner() {
        return new KernelRunner(
                (EventBus) event -> {
                },
                new InvariantEngine() {
                    @Override
                    public List<String> evaluate(String entityName, Object payload) {
                        return List.of();
                    }
                }
        );
    }
}
