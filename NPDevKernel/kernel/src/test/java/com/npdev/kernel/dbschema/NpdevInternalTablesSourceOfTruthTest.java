package com.npdev.kernel.dbschema;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

final class NpdevInternalTablesSourceOfTruthTest {
    private static final Set<String> EXPECTED_CURRENT_TABLES = Set.of(
            "npdev_audit_log",
            "npdev_circuit_breaker",
            "npdev_correlation_owner",
            "npdev_event_store",
            "npdev_flow_instance",
            "npdev_idempotency",
            "npdev_publication_audit",
            "npdev_publication_execution",
            "npdev_schema_metadata",
            "npdev_scheduled_event",
            "npdev_trace",
            "npdev_promotion_state",
            "npdev_tenant",
            "npdev_api_credential"
    );

    // npdev_tenant was previously future-scope; it is now in scope as the backbone of the runtime
    // tenant lifecycle (hybrid multitenancy), so it has moved up to EXPECTED_CURRENT_TABLES.
    private static final Set<String> FORBIDDEN_FUTURE_SCOPE_TABLES = Set.of(
            "npdev_tenant_alias",
            "npdev_tenant_app_entitlement",
            "npdev_tenant_coda_entitlement",
            "npdev_tenant_capability_entitlement",
            "npdev_tenant_actor_membership",
            "npdev_tenant_provider_binding",
            "npdev_tenant_data_binding",
            "npdev_tenant_policy_decision",
            "npdev_coda_definition",
            "npdev_coda_execution",
            "npdev_capability_binding",
            "npdev_capability_execution",
            "npdev_flow_definition",
            "npdev_flow_step_definition",
            "npdev_flow_step_execution",
            "npdev_orchestration_definition",
            "npdev_orchestration_action_definition",
            "npdev_orchestration_execution",
            "npdev_orchestration_lock"
    );

    @Test
    void registryContainsCurrentActualInternalTablesForThisItem() {
        List<InternalTableDefinition> tables = NpdevInternalTables.all();

        assertFalse(tables.isEmpty(), "NpdevInternalTables.all() must not be empty");

        Set<String> actualNames = tables.stream()
                .map(table -> normalize(table.name()))
                .collect(Collectors.toCollection(HashSet::new));

        assertEquals(EXPECTED_CURRENT_TABLES, actualNames,
                "Registry must contain exactly the current expected internal tables");
        assertTrue(actualNames.contains("npdev_schema_metadata"),
                "Registry must include npdev_schema_metadata");

        for (String forbidden : FORBIDDEN_FUTURE_SCOPE_TABLES) {
            assertFalse(actualNames.contains(forbidden),
                    "Future-scope table must not be introduced in Item 1: " + forbidden);
        }
    }

    @Test
    void tableNamesAreUnique() {
        Set<String> seen = new HashSet<>();
        for (InternalTableDefinition table : NpdevInternalTables.all()) {
            String normalized = normalize(table.name());
            assertTrue(seen.add(normalized), "Duplicate internal table name: " + normalized);
        }
    }

    @Test
    void columnNamesAreUniqueWithinEachTable() {
        for (InternalTableDefinition table : NpdevInternalTables.all()) {
            Set<String> seen = new HashSet<>();
            for (InternalColumnDefinition column : table.columns()) {
                String normalized = normalize(column.name());
                assertTrue(seen.add(normalized),
                        "Duplicate column '" + normalized + "' in table " + table.name());
            }
        }
    }

    @Test
    void currentRegistryPassesInternalSchemaValidation() {
        InternalSchemaValidationResult result = InternalSchemaValidator.validate(NpdevInternalTables.all());

        assertTrue(result.valid(), "Current internal schema registry must be valid: " + result.errors());
    }

    @Test
    void everyColumnHasLogicalTypeAndNoDialectSpecificTypeName() {
        Set<String> forbiddenDialectTypeNames = Set.of("JSONB", "CLOB", "TIMESTAMP WITH TIME ZONE");

        for (InternalTableDefinition table : NpdevInternalTables.all()) {
            for (InternalColumnDefinition column : table.columns()) {
                assertNotNull(column.type(), "Column must have a logical type: " + table.name() + "." + column.name());
                assertFalse(forbiddenDialectTypeNames.contains(column.sqlType().toUpperCase(Locale.ROOT)),
                        "Internal logical source must not expose dialect-specific SQL type for "
                                + table.name() + "." + column.name());
            }
        }
    }

    @Test
    void everyTableHasPrimaryKeyColumnsDefinedOnTheTable() {
        for (InternalTableDefinition table : NpdevInternalTables.all()) {
            assertFalse(table.primaryKey().columns().isEmpty(),
                    "Internal table must define a primary key: " + table.name());

            Set<String> columnNames = table.columns().stream()
                    .map(column -> normalize(column.name()))
                    .collect(Collectors.toSet());
            for (String primaryKeyColumn : table.primaryKey().columns()) {
                assertTrue(columnNames.contains(normalize(primaryKeyColumn)),
                        "Primary key column '" + primaryKeyColumn + "' is missing from table " + table.name());
            }
        }
    }

    private static String normalize(String value) {
        return value.toLowerCase(Locale.ROOT);
    }
}
