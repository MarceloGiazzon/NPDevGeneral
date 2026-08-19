package com.npdev.kernel.ports;

import com.npdev.kernel.concepts.ConceptRecord;
import com.npdev.kernel.inproc.InMemoryConceptStore;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R5.2 (closes RUN-1 item 4): pins {@link ConceptStore#uniqueValuesCollide} and the default
 * {@link ConceptStore#existsUnique} it backs -- the EXACT comparison rule
 * service-base.mustache's generated {@code uniqueValuesEqual} has used since LIFT-UNIQUE-P3, now
 * hoisted to one canonical place. These tests run against {@link InMemoryConceptStore}, which does
 * not override {@code existsUnique}, so they exercise the interface DEFAULT directly -- the same
 * algorithm {@code JdbcBusinessConceptStoreExistsUniqueTest} proves the JDBC pushdown reproduces
 * exactly, engine-side, via {@link ConceptStore#uniqueValuesCollide} itself.
 */
class ConceptStoreExistsUniqueTest {

    private static final String TENANT_A = "tenant-a";
    private static final String CONCEPT = "Widget";

    // ------------------------------------------------------------------ uniqueValuesCollide (pure)

    @Test
    void nullOnEitherSideNeverCollides() {
        assertFalse(ConceptStore.uniqueValuesCollide(null, "abc"));
        assertFalse(ConceptStore.uniqueValuesCollide("abc", null));
        assertFalse(ConceptStore.uniqueValuesCollide(null, null));
    }

    @Test
    void stringComparisonTrimsAndFoldsCaseOnBothSides() {
        assertTrue(ConceptStore.uniqueValuesCollide("ABC", "abc"), "case must be folded");
        assertTrue(ConceptStore.uniqueValuesCollide("  abc  ", "abc"), "left side must be trimmed");
        assertTrue(ConceptStore.uniqueValuesCollide("abc", "  ABC  "), "right side must be trimmed and folded");
        assertFalse(ConceptStore.uniqueValuesCollide("abc", "abd"), "genuinely different text must not collide");
    }

    @Test
    void crossTypeCollisionWhenEitherSideIsAString() {
        // The documented, relied-upon behavior (ledger RUN-1): a numeric DB value collides with a
        // JSON-string candidate carrying the same digits.
        assertTrue(ConceptStore.uniqueValuesCollide(42, "42"), "numeric existing value vs String candidate must collide");
        assertTrue(ConceptStore.uniqueValuesCollide("42", 42), "String existing value vs numeric candidate must collide");
        assertFalse(ConceptStore.uniqueValuesCollide(42, "42.0"), "different text representations must not collide");
    }

    @Test
    void neitherSideAStringUsesEqualsOrToStringFallback() {
        assertTrue(ConceptStore.uniqueValuesCollide(5, 5L), "cross-numeric-wrapper same value must collide (toString fallback)");
        assertTrue(ConceptStore.uniqueValuesCollide(Boolean.TRUE, Boolean.TRUE), "equal non-String objects must collide");
        assertFalse(ConceptStore.uniqueValuesCollide(Boolean.TRUE, 1), "true vs 1 has no toString overlap, must not collide");
        assertFalse(ConceptStore.uniqueValuesCollide(new java.math.BigDecimal("42.0000"), 42),
                "a padded decimal's toString must NOT match a bare integer's toString -- this is the pre-existing, "
                        + "pinned (if surprising) semantics R5.2 preserves exactly rather than silently normalizing");
    }

    // ------------------------------------------------------------------ existsUnique (default, via InMemoryConceptStore)

    @Test
    void detectsACaseAndWhitespaceInsensitiveCollisionTheDbsRawUniqueIndexWouldMiss() {
        ConceptStore store = new InMemoryConceptStore();
        store.save(new ConceptRecord(CONCEPT, UUID.randomUUID().toString(), TENANT_A, Map.of("email", "User@Example.com")));

        boolean collides = store.existsUnique(TENANT_A, CONCEPT, List.of("email"), List.of("  user@example.com  "), null);

        assertTrue(collides, "trim+case-insensitive collision must be detected even though the DB's raw unique index is case-sensitive");
    }

    @Test
    void aFieldValueOfNullNeverCollidesAndNeedsNoScan() {
        ConceptStore store = new InMemoryConceptStore();
        store.save(new ConceptRecord(CONCEPT, UUID.randomUUID().toString(), TENANT_A, Map.of("email", "a@b.com")));

        assertFalse(store.existsUnique(TENANT_A, CONCEPT, List.of("email"), java.util.Collections.singletonList(null), null));
    }

    @Test
    void excludingTheCurrentRowsOwnIdMeansUpdatingToItsOwnValueIsNotAConflict() {
        ConceptStore store = new InMemoryConceptStore();
        String id = UUID.randomUUID().toString();
        store.save(new ConceptRecord(CONCEPT, id, TENANT_A, Map.of("sku", "ABC-1")));

        boolean collidesWithSelfExcluded = store.existsUnique(TENANT_A, CONCEPT, List.of("sku"), List.of("ABC-1"), id);
        boolean collidesWithoutExclusion = store.existsUnique(TENANT_A, CONCEPT, List.of("sku"), List.of("ABC-1"), null);

        assertFalse(collidesWithSelfExcluded, "a row must never conflict with its own pre-existing value on update");
        assertTrue(collidesWithoutExclusion, "the same query without excludeId must still see the row as a match (sanity check)");
    }

    @Test
    void anotherTenantsMatchingValueNeverCollides() {
        ConceptStore store = new InMemoryConceptStore();
        store.save(new ConceptRecord(CONCEPT, UUID.randomUUID().toString(), "tenant-b", Map.of("sku", "ABC-1")));

        assertFalse(store.existsUnique(TENANT_A, CONCEPT, List.of("sku"), List.of("ABC-1"), null));
    }

    @Test
    void compoundUniqueRequiresEveryFieldToMatchTheSameRow() {
        ConceptStore store = new InMemoryConceptStore();
        store.save(new ConceptRecord(CONCEPT, UUID.randomUUID().toString(), TENANT_A,
                Map.of("region", "west", "sku", "ABC-1")));

        boolean bothMatch = store.existsUnique(TENANT_A, CONCEPT,
                List.of("region", "sku"), List.of("west", "ABC-1"), null);
        boolean onlyOneMatches = store.existsUnique(TENANT_A, CONCEPT,
                List.of("region", "sku"), List.of("east", "ABC-1"), null);

        assertTrue(bothMatch, "a candidate matching every compound field must collide");
        assertFalse(onlyOneMatches, "a candidate matching only ONE of the compound fields must not collide (AND, not OR)");
    }

    @Test
    void malformedInputReturnsFalseRatherThanThrowing() {
        ConceptStore store = new InMemoryConceptStore();
        store.save(new ConceptRecord(CONCEPT, UUID.randomUUID().toString(), TENANT_A, Map.of("sku", "ABC-1")));

        assertFalse(store.existsUnique(TENANT_A, CONCEPT, List.of(), List.of(), null), "empty field list is a no-op, not a match");
        assertFalse(store.existsUnique(TENANT_A, CONCEPT, null, null, null), "null field list is a no-op, not a match");
        assertFalse(store.existsUnique(TENANT_A, CONCEPT, List.of("sku", "region"), List.of("ABC-1"), null),
                "mismatched fieldNames/values sizes is a no-op, not a match");
    }
}
