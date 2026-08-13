package com.npdev.dsl.v1;

import com.npdev.dsl.v1.compiled.CompiledConcept;
import com.npdev.dsl.v1.compiled.CompiledContext;
import com.npdev.dsl.v1.compiled.CompiledField;
import com.npdev.dsl.v1.compiled.SqlIdentifierSupport;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqlIdentifierSupportTest {

    @Test
    void packNamespacedConceptFallbackBecomesSafePluralTableName() {
        CompiledConcept concept = new CompiledConcept(
                "cat::Product",
                "CatProduct",
                "",
                List.of(new CompiledField("id", "uuid", "java.util.UUID", true, true, false))
        );

        assertEquals("cat_products", SqlIdentifierSupport.tableName(concept));
    }

    @Test
    void catalogProductPackConceptFallsBackToCatalogProductsTableName() {
        // BOND-B7: pins the exact roadmap-cited convention (catalog::Product -> catalog_products),
        // no explicit tableName declared.
        CompiledConcept concept = new CompiledConcept(
                "catalog::Product",
                "CatalogProduct",
                "",
                List.of(new CompiledField("id", "uuid", "java.util.UUID", true, true, false))
        );

        assertEquals("catalog_products", SqlIdentifierSupport.tableName(concept));
    }

    @Test
    void explicitTableNameOnAPackConceptIsPreservedAsIs() {
        // BOND-B7: an author-declared tableName always wins over the toSnakePlural(name) fallback,
        // even for a pack-namespaced concept -- confirms the "::" fallback path is only a fallback.
        CompiledConcept concept = new CompiledConcept(
                "catalog::Product",
                "CatalogProduct",
                "products_catalog",
                List.of(new CompiledField("id", "uuid", "java.util.UUID", true, true, false))
        );

        assertEquals("products_catalog", SqlIdentifierSupport.tableName(concept));
    }

    @Test
    void junctionTableNameForAPackNamespacedSourceContainsNoColons() {
        // BOND-B7: an N:M bond field on a pack-namespaced concept must produce a junction table
        // name that is valid SQL -- no "::" characters, from either the source table or field side.
        CompiledConcept sourceConcept = new CompiledConcept(
                "catalog::Product",
                "CatalogProduct",
                "",
                List.of(new CompiledField("id", "uuid", "java.util.UUID", true, true, false))
        );
        CompiledField tagsField = new CompiledField(
                "catalog::tags", "array", "java.util.List", false, false, false);

        String junctionTable = SqlIdentifierSupport.junctionTableName(sourceConcept, tagsField);

        assertEquals("catalog_products_catalog_tags", junctionTable);
        assertFalse(junctionTable.contains(":"), junctionTable);
    }

    @Test
    void longIdentifiersUseStableHashSuffixWithoutChangingShortNames() {
        assertEquals("invoices_product_id", SqlIdentifierSupport.safeSqlIdentifier("invoices_product_id"));

        String raw = "fk_" + "VeryLongConceptName".repeat(5) + "_productSku";
        String identifier = SqlIdentifierSupport.safeSqlIdentifier(raw);

        assertEquals(63, identifier.length());
        assertTrue(identifier.matches("[a-z0-9_]+_[0-9a-f]{8}"), identifier);
        assertEquals(identifier, SqlIdentifierSupport.safeSqlIdentifier(raw));
    }

    @Test
    void twoLongNamesWithSharedPrefixDoNotCollide() {
        String left = "fk_" + "VeryLongConceptName".repeat(5) + "_left";
        String right = "fk_" + "VeryLongConceptName".repeat(5) + "_right";

        assertNotEquals(
                SqlIdentifierSupport.safeSqlIdentifier(left),
                SqlIdentifierSupport.safeSqlIdentifier(right)
        );
    }

    // LNCH-1 P2 (2.3): tableName(String, String) is the factored-out overload that
    // tableName(CompiledConcept) now delegates to, and that the concept-rename manifest/executor
    // logic reuses to derive the OLD table name -- never re-deriving toSnakePlural/safeSqlIdentifier
    // by hand.

    @Test
    void noOverrideConceptRenameProducesDifferentOldAndNewTableNames() {
        String oldTable = SqlIdentifierSupport.tableName("Widget", null);
        String newTable = SqlIdentifierSupport.tableName("Gadget", null);

        assertEquals("widgets", oldTable);
        assertEquals("gadgets", newTable);
        assertNotEquals(oldTable, newTable);
    }

    @Test
    void explicitOverrideConceptRenameKeepsTheSamePhysicalTableName() {
        // An explicit tableName override is a property of the table's physical identity, not the
        // concept's authoring name -- a rename of the concept's name does not imply a rename of an
        // explicitly-overridden table, so old == new (no ALTER TABLE RENAME TO needed).
        String oldTable = SqlIdentifierSupport.tableName("Widget", "legacy_products");
        String newTable = SqlIdentifierSupport.tableName("Gadget", "legacy_products");

        assertEquals("legacy_products", oldTable);
        assertEquals(oldTable, newTable);
    }

    @Test
    void tableNameOverloadDelegationMatchesCompiledConceptOverload() {
        CompiledConcept withoutOverride = new CompiledConcept(
                "Widget",
                "Widget",
                "",
                List.of(new CompiledField("id", "uuid", "java.util.UUID", true, true, false))
        );
        CompiledConcept withOverride = new CompiledConcept(
                "Widget",
                "Widget",
                "legacy_products",
                List.of(new CompiledField("id", "uuid", "java.util.UUID", true, true, false))
        );

        assertEquals(
                SqlIdentifierSupport.tableName("Widget", null),
                SqlIdentifierSupport.tableName(withoutOverride)
        );
        assertEquals(
                SqlIdentifierSupport.tableName("Widget", "legacy_products"),
                SqlIdentifierSupport.tableName(withOverride)
        );
    }

    // PK-2: physicalTableNameSource/aliasPreservingTableName -- the two new naming derivations that
    // let a pack-derived concept's physical table name depend on the pack's own id + major version,
    // never the importing app's local alias.

    @Test
    void physicalTableNameSourceReplacesTheAliasWithThePackPhysicalQualifier() {
        String source = SqlIdentifierSupport.physicalTableNameSource("auth::User", "identity_v1", Map.of());

        assertEquals("identity_v1::User", source);
    }

    @Test
    void physicalTableNameSourceWithNoQualifierDelegatesToContextAwareIdentifierSourceUnchanged() {
        // A non-pack concept (no physical qualifier entry) must behave EXACTLY as before PK-2 --
        // the physicallyIsolate bounded-context mechanism is completely unaffected.
        Map<String, Boolean> contexts = Map.of("billing", false, "shipping", true);

        assertEquals(
                SqlIdentifierSupport.contextAwareIdentifierSource("billing::Invoice", contexts),
                SqlIdentifierSupport.physicalTableNameSource("billing::Invoice", null, contexts)
        );
        assertEquals(
                SqlIdentifierSupport.contextAwareIdentifierSource("shipping::Order", contexts),
                SqlIdentifierSupport.physicalTableNameSource("shipping::Order", null, contexts)
        );
    }

    @Test
    void twoAliasesOfTheSamePackPhysicalQualifierCollapseToTheIdenticalTableName() {
        CompiledConcept viaAlias1 = new CompiledConcept(
                "auth::User", "AuthUser", "identity_v1_users",
                List.of(new CompiledField("id", "uuid", "java.util.UUID", true, true, false)));
        CompiledConcept viaAlias2 = new CompiledConcept(
                "id::User", "IdUser", "identity_v1_users",
                List.of(new CompiledField("id", "uuid", "java.util.UUID", true, true, false)));

        assertEquals(SqlIdentifierSupport.tableName(viaAlias1), SqlIdentifierSupport.tableName(viaAlias2));
        assertEquals("identity_v1_users", SqlIdentifierSupport.tableName(viaAlias1));
    }

    @Test
    void aliasPreservingTableNameRecomputesThePreP2DerivationIgnoringTheStoredTableName() {
        // The concept's stored tableName already reflects PK-2's physical qualifier
        // ("identity_v1_users"); aliasPreservingTableName must ignore that field entirely and
        // recompute fresh from the concept's own (alias-qualified) name -- this is what keeps REST
        // routes decoupled from a pack version bump.
        CompiledConcept concept = new CompiledConcept(
                "auth::User", "AuthUser", "identity_v1_users",
                List.of(new CompiledField("id", "uuid", "java.util.UUID", true, true, false)));

        assertEquals("auth_users", SqlIdentifierSupport.aliasPreservingTableName(concept, List.of()));
        assertNotEquals(
                SqlIdentifierSupport.tableName(concept),
                SqlIdentifierSupport.aliasPreservingTableName(concept, List.of())
        );
    }

    @Test
    void aliasPreservingTableNameStillHonorsPhysicallyIsolateForNonPackConcepts() {
        CompiledConcept isolating = new CompiledConcept(
                "shipping::Order", "ShippingOrder", "shipping_orders",
                List.of(new CompiledField("id", "uuid", "java.util.UUID", true, true, false)));
        CompiledConcept nonIsolating = new CompiledConcept(
                "billing::Invoice", "BillingInvoice", "invoices",
                List.of(new CompiledField("id", "uuid", "java.util.UUID", true, true, false)));

        List<CompiledContext> contexts = List.of(
                new CompiledContext("shipping", "contexts/shipping.json", true),
                new CompiledContext("billing", "contexts/billing.json", false)
        );

        assertEquals("shipping_orders", SqlIdentifierSupport.aliasPreservingTableName(isolating, contexts));
        assertEquals("invoices", SqlIdentifierSupport.aliasPreservingTableName(nonIsolating, contexts));
    }
}
