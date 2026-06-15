package com.npdev.dsl.v1;

import com.npdev.dsl.v1.compiled.CompiledConcept;
import com.npdev.dsl.v1.compiled.CompiledField;
import com.npdev.dsl.v1.compiled.SqlIdentifierSupport;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
}
