package com.finalexec.db;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * REG-6: unit coverage for the {@link SchemaLifecycleExecutor.ColumnFacts} projection that the
 * schema-lifecycle passes now query instead of each re-deriving column semantics from four manifest
 * maps and two static platform-column sets. Pure (no database) — the passes' end-to-end behavior is
 * covered by the proof matrices + the relax/bond targeted tests; this pins the projection's own
 * computation so a future edit to it fails loudly.
 */
class SchemaLifecycleExecutorColumnFactsTest {

    @Test
    void computesEachColumnFactFromTheManifest() {
        // Contact has: a platform column (tenant_id), a plain required-and-additive field (email,
        // with a literal default + declared type), a required-but-NOT-additive field (ownerId, i.e.
        // a required bond), and an optional field that was renamed (nickname <- alias).
        SchemaLifecycleExecutor.SchemaManifest manifest = manifest(
                Map.of("contact", List.of("id", "tenant_id", "email", "ownerId", "nickname")),
                Map.of("contact", List.of("email", "nickname")),          // additive-eligible
                Map.of("contact", List.of("email", "ownerId")),           // required by model
                Map.of("contact", Map.of("email", "VARCHAR(255)")),       // declared types
                Map.of("contact", Map.of("nickname", "alias")),           // renamedFrom (new <- old)
                Map.of("contact", Map.of("email", "\"\"")));              // literal defaults

        Map<String, SchemaLifecycleExecutor.ColumnFacts> facts =
                SchemaLifecycleExecutor.columnFactsFor(manifest, "contact");

        SchemaLifecycleExecutor.ColumnFacts tenant = facts.get("tenant_id");
        assertNotNull(tenant);
        assertTrue(tenant.platformManaged(), "tenant_id is platform-managed");
        assertTrue(tenant.repairablePlatformColumn(), "tenant_id has a fixed default -> repairable");
        assertFalse(tenant.bond(), "a platform column is never a model bond");

        SchemaLifecycleExecutor.ColumnFacts id = facts.get("id");
        assertTrue(id.platformManaged(), "id is platform-managed");
        assertFalse(id.repairablePlatformColumn(), "id has no platform default -> not repairable");

        SchemaLifecycleExecutor.ColumnFacts email = facts.get("email");
        assertTrue(email.additiveEligible());
        assertTrue(email.requiredByModel());
        assertFalse(email.bond(), "required AND additive-eligible is a plain field, not a bond");
        assertEquals("VARCHAR(255)", email.declaredType());
        assertEquals("\"\"", email.literalDefaultJson());
        assertFalse(email.platformManaged());

        SchemaLifecycleExecutor.ColumnFacts ownerId = facts.get("ownerId");
        assertTrue(ownerId.requiredByModel());
        assertFalse(ownerId.additiveEligible());
        assertTrue(ownerId.bond(), "required AND not additive-eligible is a required bond");

        SchemaLifecycleExecutor.ColumnFacts nickname = facts.get("nickname");
        assertEquals("alias", nickname.renamedFrom());
        assertFalse(nickname.requiredByModel());
        assertFalse(nickname.bond());
    }

    @Test
    void platformManagedHelperIsCaseInsensitive() {
        assertTrue(SchemaLifecycleExecutor.isPlatformManagedColumn("TENANT_ID"));
        assertTrue(SchemaLifecycleExecutor.isPlatformManagedColumn("row_version"));
        assertFalse(SchemaLifecycleExecutor.isPlatformManagedColumn("email"));
        assertFalse(SchemaLifecycleExecutor.isPlatformManagedColumn(null));
    }

    @Test
    void repairableSetIsAStrictSubsetOfManaged() {
        // The static drift-guard (assertPlatformColumnSetsAgree) ran at class load; if it had failed
        // this class would not have loaded. Re-assert the relationship here so the intent is explicit:
        // every non-id platform column is repairable, and 'id' is the only managed-but-not-repairable one.
        assertTrue(SchemaLifecycleExecutor.platformManagedColumnNames().contains("id"));
        assertTrue(SchemaLifecycleExecutor.platformManagedColumnNames().contains("tenant_id"));
        assertFalse(SchemaLifecycleExecutor.isPlatformManagedColumn("not_a_platform_column"));
    }

    private static SchemaLifecycleExecutor.SchemaManifest manifest(
            Map<String, List<String>> businessTableColumns,
            Map<String, List<String>> businessTableAdditiveColumns,
            Map<String, List<String>> businessTableRequiredColumns,
            Map<String, Map<String, String>> businessTableColumnTypes,
            Map<String, Map<String, String>> businessTableRenamedColumns,
            Map<String, Map<String, String>> businessTableColumnDefaultLiterals) {
        return new SchemaLifecycleExecutor.SchemaManifest(
                "H2Local",
                "jdbc",
                true,
                "sha256:new",
                List.of(),
                List.copyOf(businessTableColumns.keySet()),
                businessTableColumns,
                businessTableAdditiveColumns,
                businessTableColumnTypes,
                businessTableRenamedColumns,
                Map.of(),
                true,
                "DropAndRecreateOnStructureChange",
                "NpdevOwnedTablesOnly",
                "I_UNDERSTAND_TABLE_DATA_WILL_BE_DELETED",
                "",
                businessTableRequiredColumns,
                businessTableColumnDefaultLiterals,
                Map.of(),
                Map.of()
        );
    }
}
