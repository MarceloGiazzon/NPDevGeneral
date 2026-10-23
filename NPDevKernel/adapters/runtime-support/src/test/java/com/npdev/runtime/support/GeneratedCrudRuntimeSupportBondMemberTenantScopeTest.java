package com.npdev.runtime.support;

import com.npdev.dsl.v1.compiled.CompiledConcept;
import com.npdev.dsl.v1.compiled.CompiledField;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.compiled.CompiledReferenceSemantics;
import com.npdev.kernel.KernelRunner;
import com.npdev.kernel.concepts.ConceptRecord;
import com.npdev.kernel.concepts.DefaultConceptGateway;
import com.npdev.kernel.inproc.InMemoryConceptStore;
import com.npdev.kernel.ports.EventBus;
import com.npdev.kernel.ports.InvariantEngine;
import jakarta.servlet.http.HttpServletRequest;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import javax.sql.DataSource;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * REG-294: the many-to-many member endpoints (POST /{id}/{field}/{target}, PUT /{id}/{field}) authorised
 * the SOURCE row only, and addBondMember/replaceBondMembers inserted the target value with no tenant
 * check -- the junction FK checks existence only. So tenant A could link tenant B's row (and use
 * FK-error vs success as a cross-tenant existence oracle), and listBondMembers listed it back. The
 * caller is tenant-a via request claims, exactly as the generated service resolves it.
 */
class GeneratedCrudRuntimeSupportBondMemberTenantScopeTest {

    private static final String CLAIMS_ATTRIBUTE = "npdev.auth.claims";

    private DataSource dataSource;
    private UUID catalogId;

    @BeforeEach
    void setUp() throws SQLException {
        JdbcDataSource h2 = new JdbcDataSource();
        h2.setURL("jdbc:h2:mem:reg294_bond_" + System.nanoTime() + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        dataSource = h2;
        catalogId = UUID.randomUUID();
        callAsTenant("tenant-a");
    }

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void addAndReplaceRefuseAnotherTenantsTargetAndLeaveMembersIntact() throws SQLException {
        createBusinessSchema();
        execute("INSERT INTO cat_products (id, sku_id, tenant_id) VALUES ('" + UUID.randomUUID() + "', 'SKU-A', 'tenant-a')");
        execute("INSERT INTO cat_products (id, sku_id, tenant_id) VALUES ('" + UUID.randomUUID() + "', 'SKU-A2', 'tenant-a')");
        execute("INSERT INTO cat_products (id, sku_id, tenant_id) VALUES ('" + UUID.randomUUID() + "', 'SKU-B', 'tenant-b')");
        execute("INSERT INTO catalogs (id, tenant_id) VALUES ('" + catalogId + "', 'tenant-a')");
        GeneratedCrudRuntimeSupport support = new GeneratedCrudRuntimeSupport(
                GeneratedCrudRuntimeSupportBondMemberTenantScopeTest::model, kernelRunner(), null, null, null, dataSource);

        support.addBondMember("Catalog", catalogId, "productSkus", "SKU-A");
        assertEquals(List.of("SKU-A"), support.listBondMembers("Catalog", catalogId, "productSkus"));

        GeneratedCrudRuntimeSupport.InvariantViolationException crossTenantAdd = assertThrows(
                GeneratedCrudRuntimeSupport.InvariantViolationException.class,
                () -> support.addBondMember("Catalog", catalogId, "productSkus", "SKU-B"));
        assertEquals("bond_target_not_found", crossTenantAdd.violations().get(0).code());
        // Worded exactly like a missing target: the refusal never confirms the other tenant's row exists.
        GeneratedCrudRuntimeSupport.InvariantViolationException missingAdd = assertThrows(
                GeneratedCrudRuntimeSupport.InvariantViolationException.class,
                () -> support.addBondMember("Catalog", catalogId, "productSkus", "NO-SUCH-SKU"));
        assertEquals(missingAdd.violations().get(0).code(), crossTenantAdd.violations().get(0).code());
        assertEquals(missingAdd.violations().get(0).message(), crossTenantAdd.violations().get(0).message());

        GeneratedCrudRuntimeSupport.InvariantViolationException crossTenantReplace = assertThrows(
                GeneratedCrudRuntimeSupport.InvariantViolationException.class,
                () -> support.replaceBondMembers("Catalog", catalogId, "productSkus", List.of("SKU-A2", "SKU-B")));
        assertEquals("bond_target_not_found", crossTenantReplace.violations().get(0).code());
        assertEquals(List.of("SKU-A"), support.listBondMembers("Catalog", catalogId, "productSkus"),
                "a refused replace must not delete or insert anything");
        assertEquals(1, junctionRowCount());

        support.replaceBondMembers("Catalog", catalogId, "productSkus", List.of("SKU-A2"));
        assertEquals(List.of("SKU-A2"), support.listBondMembers("Catalog", catalogId, "productSkus"));
    }

    @Test
    void listOmitsAMemberWhoseTargetIsInAnotherTenant() throws SQLException {
        createBusinessSchema();
        execute("INSERT INTO cat_products (id, sku_id, tenant_id) VALUES ('" + UUID.randomUUID() + "', 'SKU-A', 'tenant-a')");
        execute("INSERT INTO cat_products (id, sku_id, tenant_id) VALUES ('" + UUID.randomUUID() + "', 'SKU-B', 'tenant-b')");
        execute("INSERT INTO catalogs (id, tenant_id) VALUES ('" + catalogId + "', 'tenant-a')");
        // a cross-tenant link written before the fix
        execute("INSERT INTO catalogs_product_skus VALUES ('" + catalogId + "', 'SKU-A')");
        execute("INSERT INTO catalogs_product_skus VALUES ('" + catalogId + "', 'SKU-B')");
        GeneratedCrudRuntimeSupport support = new GeneratedCrudRuntimeSupport(
                GeneratedCrudRuntimeSupportBondMemberTenantScopeTest::model, kernelRunner(), null, null, null, dataSource);

        assertEquals(List.of("SKU-A"), support.listBondMembers("Catalog", catalogId, "productSkus"));
    }

    /**
     * In-memory engine: business rows live in the tenant-keyed ConceptStore, so the target check goes
     * through the gateway with the caller's context instead of a SELECT against business tables.
     */
    @Test
    void inMemoryEngineRefusesAnotherTenantsTargetThroughTheGateway() throws SQLException {
        execute("CREATE TABLE catalogs_product_skus (source_id UUID NOT NULL, target_sku_id VARCHAR(255) NOT NULL, "
                + "PRIMARY KEY (source_id, target_sku_id))");
        InMemoryConceptStore store = new InMemoryConceptStore();
        store.save(new ConceptRecord("cat::Product", "p-a", "tenant-a", Map.of("id", "p-a", "skuId", "SKU-A")));
        store.save(new ConceptRecord("cat::Product", "p-a2", "tenant-a", Map.of("id", "p-a2", "skuId", "SKU-A2")));
        store.save(new ConceptRecord("cat::Product", "p-b", "tenant-b", Map.of("id", "p-b", "skuId", "SKU-B")));
        GeneratedCrudRuntimeSupport support = new GeneratedCrudRuntimeSupport(
                GeneratedCrudRuntimeSupportBondMemberTenantScopeTest::model, kernelRunner(), null, null, null, dataSource)
                .withConceptGateway(new DefaultConceptGateway(store));
        // caller tenant-a comes from the request claims set in setUp(), as the generated service resolves it

        support.addBondMember("Catalog", catalogId, "productSkus", "SKU-A");
        GeneratedCrudRuntimeSupport.InvariantViolationException crossTenantAdd = assertThrows(
                GeneratedCrudRuntimeSupport.InvariantViolationException.class,
                () -> support.addBondMember("Catalog", catalogId, "productSkus", "SKU-B"));
        assertEquals("bond_target_not_found", crossTenantAdd.violations().get(0).code());

        GeneratedCrudRuntimeSupport.InvariantViolationException crossTenantReplace = assertThrows(
                GeneratedCrudRuntimeSupport.InvariantViolationException.class,
                () -> support.replaceBondMembers("Catalog", catalogId, "productSkus", List.of("SKU-A2", "SKU-B")));
        assertEquals("bond_target_not_found", crossTenantReplace.violations().get(0).code());
        assertEquals(List.of("SKU-A"), support.listBondMembers("Catalog", catalogId, "productSkus"));
        assertEquals(1, junctionRowCount());
    }

    private void createBusinessSchema() throws SQLException {
        execute("CREATE TABLE cat_products (id UUID PRIMARY KEY, sku_id VARCHAR(255) NOT NULL UNIQUE, "
                + "tenant_id VARCHAR(120) NOT NULL DEFAULT 'default')");
        execute("CREATE TABLE catalogs (id UUID PRIMARY KEY, tenant_id VARCHAR(120) NOT NULL DEFAULT 'default')");
        execute("""
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

    private int junctionRowCount() throws SQLException {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT COUNT(*) FROM catalogs_product_skus")) {
            rows.next();
            return rows.getInt(1);
        }
    }

    private void execute(String sql) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static CompiledModel model() {
        CompiledField skuAnchor = new CompiledField(
                "skuId", "string", "String", false, true, true,
                List.of(), null, null, null, null, List.of(), null, "anchor");
        CompiledConcept product = new CompiledConcept(
                "cat::Product", "CatProduct", "cat_products",
                List.of(new CompiledField("id", "uuid", "java.util.UUID", true, true, false), skuAnchor));
        CompiledReferenceSemantics productSkus = new CompiledReferenceSemantics(
                "cat::Product", true, null, List.of(), List.of(), null, null, List.of(), null, null,
                "skuId", "cascade");
        CompiledConcept catalog = new CompiledConcept(
                "Catalog", "Catalog", "catalogs",
                List.of(
                        new CompiledField("id", "uuid", "java.util.UUID", true, true, false),
                        new CompiledField("productSkus", "reference", "String", false, false, false,
                                List.of(), "cat::Product", productSkus, null, null, List.of(), null, null)));
        Map<String, CompiledConcept> concepts = new LinkedHashMap<>();
        concepts.put(product.getName(), product);
        concepts.put(catalog.getName(), catalog);
        return new CompiledModel("reg294", "1.0.0", "v1", concepts);
    }

    private static KernelRunner kernelRunner() {
        return new KernelRunner((EventBus) event -> {
        }, new InvariantEngine() {
            @Override
            public List<String> evaluate(String entityName, Object payload) {
                return List.of();
            }
        });
    }

    private static void callAsTenant(String tenantId) {
        Map<String, Object> claims = Map.of("tenant_id", tenantId);
        HttpServletRequest request = (HttpServletRequest) Proxy.newProxyInstance(
                HttpServletRequest.class.getClassLoader(),
                new Class<?>[]{HttpServletRequest.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getAttribute" -> CLAIMS_ATTRIBUTE.equals(args[0]) ? claims : null;
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> null;
                });
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }
}
