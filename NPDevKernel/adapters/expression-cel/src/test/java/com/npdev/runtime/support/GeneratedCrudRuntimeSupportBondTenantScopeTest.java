package com.npdev.runtime.support;

import com.npdev.dsl.v1.compiled.CompiledConcept;
import com.npdev.dsl.v1.compiled.CompiledField;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.compiled.CompiledReferenceSemantics;
import com.npdev.kernel.ExecutionContext;
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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Pins a real cross-tenant data-integrity gap found while live-verifying the
 * restaurant-saas-multitenant sample: a scalar bond's FK constraint only checks that the target ROW
 * exists, never that it belongs to the CALLER's own tenant -- so tenant A could create a row whose
 * bond field pointed at tenant B's private business data (confirmed live before this fix: a
 * StaffMember create with a cross-tenant tenantRef succeeded with 200). enforceBondTargetTenant must
 * reject that, and must not block an ordinary same-tenant reference.
 */
class GeneratedCrudRuntimeSupportBondTenantScopeTest {

    @Test
    void rejectsABondFieldThatReferencesAnotherTenantsRow() throws Exception {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:runtime_bond_tenant_scope;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        initializeSchema(dataSource);

        UUID tenantARowId = UUID.randomUUID();
        UUID tenantBRowId = UUID.randomUUID();
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("INSERT INTO tenants (id, tenant_id) VALUES ('" + tenantARowId + "', 'tenant-a')");
            statement.executeUpdate("INSERT INTO tenants (id, tenant_id) VALUES ('" + tenantBRowId + "', 'tenant-b')");
        }

        GeneratedCrudRuntimeSupport support = new GeneratedCrudRuntimeSupport(
                compiledModel(), kernelRunner(), null, null, null, dataSource);

        // tenant-a referencing its OWN tenant row: allowed.
        assertDoesNotThrow(() -> support.enforceBondTargetTenant(
                "StaffMember", Map.of("tenantRef", tenantARowId), ExecutionContext.of("tenant-a", "alice")));

        // tenant-a referencing tenant-b's row: rejected, worded as not-found (never confirms existence).
        GeneratedCrudRuntimeSupport.InvariantViolationException violation = assertThrows(
                GeneratedCrudRuntimeSupport.InvariantViolationException.class,
                () -> support.enforceBondTargetTenant(
                        "StaffMember", Map.of("tenantRef", tenantBRowId), ExecutionContext.of("tenant-a", "alice")));
        assertEquals("bond_target_not_found", violation.violations().get(0).code());

        // A null/absent reference (optional bond, not set) is not this check's concern.
        assertDoesNotThrow(() -> support.enforceBondTargetTenant(
                "StaffMember", Map.of(), ExecutionContext.of("tenant-a", "alice")));
    }

    private static void initializeSchema(DataSource dataSource) throws Exception {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE tenants (
                      id UUID PRIMARY KEY,
                      tenant_id VARCHAR(120) NOT NULL
                    )
                    """);
        }
    }

    private static CompiledModel compiledModel() {
        CompiledConcept tenant = new CompiledConcept(
                "Tenant", "Tenant", "tenants",
                List.of(new CompiledField("id", "uuid", "java.util.UUID", true, true, false))
        );

        CompiledReferenceSemantics tenantRefSemantics = new CompiledReferenceSemantics(
                "Tenant", false, null, List.of(), List.of(), null, null, List.of(), null, null, null, "restrict");
        CompiledConcept staffMember = new CompiledConcept(
                "StaffMember", "StaffMember", "staff_members",
                List.of(
                        new CompiledField("id", "uuid", "java.util.UUID", true, true, false),
                        new CompiledField("tenantRef", "reference", "java.util.UUID", false, true, false,
                                List.of(), "Tenant", tenantRefSemantics, null, null, List.of(), null, null)
                )
        );

        Map<String, CompiledConcept> concepts = new LinkedHashMap<>();
        concepts.put(tenant.getName(), tenant);
        concepts.put(staffMember.getName(), staffMember);
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
