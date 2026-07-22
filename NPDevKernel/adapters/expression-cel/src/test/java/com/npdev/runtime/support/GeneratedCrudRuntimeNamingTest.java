package com.npdev.runtime.support;

import com.npdev.dsl.v1.compiled.CompiledConcept;
import com.npdev.dsl.v1.compiled.CompiledConcept;
import com.npdev.dsl.v1.compiled.CompiledField;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.compiled.CompiledReferenceSemantics;
import com.npdev.dsl.v1.compiled.SqlIdentifierSupport;
import com.npdev.kernel.KernelRunner;
import com.npdev.kernel.ports.EventBus;
import com.npdev.kernel.ports.InvariantEngine;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GeneratedCrudRuntimeNamingTest {

    @Test
    void runtimeBusinessTableAndColumnNamesUseSqlIdentifierSupport() throws Exception {
        CompiledConcept product = productConcept();
        CompiledField sku = product.getFields().stream()
                .filter(field -> "skuId".equals(field.getName()))
                .findFirst()
                .orElseThrow();

        assertEquals("catalog_products", invokeStaticString("tableName", CompiledConcept.class, product));
        assertEquals("sku_id", invokeStaticString("columnName", CompiledField.class, sku));
    }

    @Test
    void runtimeBondShapeUsesSharedJunctionNaming() throws Exception {
        CompiledConcept product = productConcept();
        CompiledConcept catalog = catalogConcept();
        Map<String, CompiledConcept> concepts = new LinkedHashMap<>();
        concepts.put(product.getName(), product);
        concepts.put(catalog.getName(), catalog);
        GeneratedCrudRuntimeSupport support = new GeneratedCrudRuntimeSupport(
                new CompiledModel("demo", "1.0.0", "v1", concepts),
                kernelRunner()
        );

        Method method = GeneratedCrudRuntimeSupport.class.getDeclaredMethod(
                "requireBondRuntimeShape",
                String.class,
                String.class
        );
        method.setAccessible(true);
        Object shape = method.invoke(support, "Catalog", "productSkus");

        Method junctionTable = shape.getClass().getDeclaredMethod("junctionTable");
        junctionTable.setAccessible(true);
        assertEquals(
                SqlIdentifierSupport.junctionTableName(catalog, catalog.getFields().get(1)),
                junctionTable.invoke(shape)
        );
    }

    @Test
    void runtimeExistsByIdUsesActualCompiledIdColumn() {
        CompiledConcept order = orderConceptWithCustomId();
        CompiledField orderId = order.getFields().get(0);

        String sql = GeneratedCrudRuntimeSupport.existsByIdSql(order, orderId);

        assertEquals("SELECT 1 FROM orders WHERE CAST(order_id AS VARCHAR) = :id", sql);
    }

    @Test
    void runtimeFetchCurrentStatusUsesActualCompiledIdColumn() {
        CompiledConcept order = orderConceptWithCustomId();
        CompiledField orderId = order.getFields().get(0);

        String sql = GeneratedCrudRuntimeSupport.fetchCurrentStatusSql(order, orderId, "status");

        assertEquals("SELECT status FROM orders WHERE CAST(order_id AS VARCHAR) = :id", sql);
    }

    private static String invokeStaticString(String methodName, Class<?> parameterType, Object argument) throws Exception {
        Method method = GeneratedCrudRuntimeSupport.class.getDeclaredMethod(methodName, parameterType);
        method.setAccessible(true);
        return (String) method.invoke(null, argument);
    }

    private static CompiledConcept productConcept() {
        return new CompiledConcept(
                "catalog::Product", "CatalogProduct", "catalog_products",
                List.of(
                        new CompiledField("id", "uuid", "java.util.UUID", true, true, false),
                        new CompiledField("skuId", "string", "String", false, true, true,
                                List.of(), null, null, null, null, List.of(), null, "anchor")
                )
        );
    }

    private static CompiledConcept catalogConcept() {
        CompiledReferenceSemantics productSkus = new CompiledReferenceSemantics(
                "catalog::Product", true, null, List.of(), List.of(), null, null, List.of(), null, null,
                "skuId", "cascade");
        return new CompiledConcept(
                "Catalog", "Catalog", "catalogs",
                List.of(
                        new CompiledField("id", "uuid", "java.util.UUID", true, true, false),
                        new CompiledField("productSkus", "reference", "String", false, false, false,
                                List.of(), "catalog::Product", productSkus, null, null, List.of(), null, null)
                )
        );
    }

    private static CompiledConcept orderConceptWithCustomId() {
        return new CompiledConcept(
                "Order", "Order", "orders",
                List.of(
                        new CompiledField("orderId", "uuid", "java.util.UUID", true, true, false),
                        new CompiledField("status", "string", "String", false, false, false)
                )
        );
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
