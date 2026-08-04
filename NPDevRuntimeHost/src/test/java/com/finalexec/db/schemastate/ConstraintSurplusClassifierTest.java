package com.finalexec.db.schemastate;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * S8 Wave 2 (B3 FK/index surplus detection): the 15 classification vectors from
 * {@code __OutsideRepo/wave2-helpers/b3-classification-vectors.json}, mirrored here verbatim as JUnit
 * cases (vector ids kept in test names for cross-reference). Vector 2 (missing-only regression) is not
 * a classifier concern — it is already pinned by {@code SchemaDiffEngineTest#extraLiveIndexesAreNeverReported}
 * and {@code #declaredForeignKeyMissingLiveIsReportedAsSafeAdditive}, since {@link
 * ConstraintSurplusClassifier} only ever classifies a LIVE constraint, never a missing one.
 *
 * <p>Vectors 3, 4 and 6 are written first, deliberately: 3/4 are the headline failure (reporting a
 * primary-key-backing index proposes dropping a primary key); 6 is the one that decides whether the
 * classifier is real — a FOREIGN index whose name happens to look implicit. {@link
 * #vector6_nameOnlyClassifierWouldWronglyPassThisAsImplicit_regressionGuard()} pins that a
 * name-pattern-only implementation fails exactly here, which is why {@link ConstraintSurplusClassifier}
 * classifies by structure (does this back a declared PK/UNIQUE on the SAME columns) instead.
 */
class ConstraintSurplusClassifierTest {

    // ---- 3/4/6 first: the vectors that decide whether the classifier is real ----

    @Test
    void vector3_h2PrimaryKeyBackingIndexIsImplicit() {
        CurrentTable orders = table(List.of("id"), List.of(), List.of(), List.of());
        DesiredTable desired = desiredTable(List.of(), List.of());
        CurrentIndex live = new CurrentIndex("PRIMARY_KEY_5", List.of("id"), true);

        assertEquals(ConstraintSurplusClassifier.Classification.IMPLICIT,
                ConstraintSurplusClassifier.classifyIndex(live, desired, orders, true));
    }

    @Test
    void vector4_postgresPrimaryKeyBackingIndexIsImplicit() {
        CurrentTable orders = table(List.of("id"), List.of(), List.of(), List.of());
        DesiredTable desired = desiredTable(List.of(), List.of());
        CurrentIndex live = new CurrentIndex("orders_pkey", List.of("id"), true);

        assertEquals(ConstraintSurplusClassifier.Classification.IMPLICIT,
                ConstraintSurplusClassifier.classifyIndex(live, desired, orders, true),
                "same as vector 3, different engine naming -- must not be name-pattern-only (see vector 6)");
    }

    @Test
    void vector6_foreignIndexWhoseNameLooksImplicitIsClassifiedForeign() {
        CurrentTable orders = table(List.of("id"), List.of(), List.of(), List.of());
        DesiredTable desired = desiredTable(List.of(), List.of());
        CurrentIndex live = new CurrentIndex("orders_pkey_backup", List.of("created_at"), false);

        assertEquals(ConstraintSurplusClassifier.Classification.FOREIGN,
                ConstraintSurplusClassifier.classifyIndex(live, desired, orders, true),
                "classify by STRUCTURE: this does not back the declared PK (id) or any unique -- its "
                        + "name alone must not save it from being reported");
    }

    /** Regression guard (Wave 2 DoD): a naive name-pattern classifier passes vectors 3/4 and fails 6.
     *  Kept as an executable pin against ever "simplifying" {@link ConstraintSurplusClassifier} back
     *  into a name check. */
    @Test
    void vector6_nameOnlyClassifierWouldWronglyPassThisAsImplicit_regressionGuard() {
        Pattern looksLikePrimaryKey = Pattern.compile("(?i).*p(rimary)?_?key.*");
        boolean namePatternSaysImplicit = looksLikePrimaryKey.matcher("orders_pkey_backup").matches();
        assertEquals(true, namePatternSaysImplicit,
                "the naive classifier this guard exists to reject WOULD match this name");

        CurrentTable orders = table(List.of("id"), List.of(), List.of(), List.of());
        DesiredTable desired = desiredTable(List.of(), List.of());
        CurrentIndex live = new CurrentIndex("orders_pkey_backup", List.of("created_at"), false);
        assertNotEquals(ConstraintSurplusClassifier.Classification.IMPLICIT,
                ConstraintSurplusClassifier.classifyIndex(live, desired, orders, true),
                "the REAL (structural) classifier must disagree with the naive name-pattern verdict");
    }

    // ---- the rest, in vector order ----

    @Test
    void vector1_declaredIndexPresentInTheDbIsPlatformDeclared() {
        DesiredTable desired = desiredTable(List.of(new DesiredIndex(List.of("tenant_id", "status"), false)), List.of());
        CurrentTable orders = table(List.of("id"), List.of(), List.of(), List.of());
        CurrentIndex live = new CurrentIndex("idx_order_tenant_status", List.of("tenant_id", "status"), false);

        assertEquals(ConstraintSurplusClassifier.Classification.PLATFORM_DECLARED,
                ConstraintSurplusClassifier.classifyIndex(live, desired, orders, true));
    }

    @Test
    void vector5_postgresUniqueConstraintBackingIndexIsImplicit() {
        DesiredTable desired = desiredTable(List.of(), List.of());
        CurrentTable orders = table(List.of("id"), List.of(new CurrentUniqueConstraint("orders_external_ref_key", List.of("external_ref"))),
                List.of(), List.of());
        CurrentIndex live = new CurrentIndex("orders_external_ref_key", List.of("external_ref"), true);

        assertEquals(ConstraintSurplusClassifier.Classification.IMPLICIT,
                ConstraintSurplusClassifier.classifyIndex(live, desired, orders, true),
                "the model declared a unique constraint; the DB-created backing index is not surplus");
    }

    @Test
    void vector7_dbaPerformanceIndexIsGenuineDriftAndReportedAsForeign() {
        DesiredTable desired = desiredTable(List.of(), List.of());
        CurrentTable orders = table(List.of("id"), List.of(), List.of(), List.of());
        CurrentIndex live = new CurrentIndex("idx_orders_created_at", List.of("created_at"), false);

        assertEquals(ConstraintSurplusClassifier.Classification.FOREIGN,
                ConstraintSurplusClassifier.classifyIndex(live, desired, orders, true));
    }

    @Test
    void vector8_sameColumnsDifferentNameIsPlatformDeclaredNotDrift() {
        DesiredTable desired = desiredTable(List.of(new DesiredIndex(List.of("tenant_id", "status"), false)), List.of());
        CurrentTable orders = table(List.of("id"), List.of(), List.of(), List.of());
        CurrentIndex live = new CurrentIndex("some_other_name", List.of("tenant_id", "status"), false);

        assertEquals(ConstraintSurplusClassifier.Classification.PLATFORM_DECLARED,
                ConstraintSurplusClassifier.classifyIndex(live, desired, orders, true),
                "match on (columns, unique), never on the generated name");
    }

    @Test
    void vector9_sameColumnsDifferentOrderIsADifferentIndexAndReportedAsForeign() {
        DesiredTable desired = desiredTable(List.of(new DesiredIndex(List.of("tenant_id", "status"), false)), List.of());
        CurrentTable orders = table(List.of("id"), List.of(), List.of(), List.of());
        CurrentIndex live = new CurrentIndex("idx_x", List.of("status", "tenant_id"), false);

        assertEquals(ConstraintSurplusClassifier.Classification.FOREIGN,
                ConstraintSurplusClassifier.classifyIndex(live, desired, orders, true),
                "composite index column ORDER is semantically load-bearing");
    }

    @Test
    void vector10_sameColumnsUniquenessDiffersIsReportedAsForeign() {
        DesiredTable desired = desiredTable(List.of(new DesiredIndex(List.of("email"), true)), List.of());
        CurrentTable orders = table(List.of("id"), List.of(), List.of(), List.of());
        CurrentIndex live = new CurrentIndex("idx_email", List.of("email"), false);

        assertEquals(ConstraintSurplusClassifier.Classification.FOREIGN,
                ConstraintSurplusClassifier.classifyIndex(live, desired, orders, true),
                "the declared UNIQUE index is missing and a non-unique one exists -- both facts matter");
    }

    @Test
    void vector11_caseDifferenceOnlyIsPlatformDeclared() {
        DesiredTable desired = desiredTable(List.of(new DesiredIndex(List.of("Tenant_Id"), false)), List.of());
        CurrentTable orders = table(List.of("id"), List.of(), List.of(), List.of());
        CurrentIndex live = new CurrentIndex("IDX_X", List.of("tenant_id"), false);

        assertEquals(ConstraintSurplusClassifier.Classification.PLATFORM_DECLARED,
                ConstraintSurplusClassifier.classifyIndex(live, desired, orders, true),
                "compare lower-cased or every app phantom-reports");
    }

    @Test
    void vector12_preG8ManifestIsUnclassifiableNotForeign() {
        DesiredTable desired = desiredTable(List.of(), List.of());
        CurrentTable orders = table(List.of("id"), List.of(), List.of(), List.of());
        CurrentIndex live = new CurrentIndex("idx_anything", List.of("x"), false);

        assertEquals(ConstraintSurplusClassifier.Classification.UNCLASSIFIABLE,
                ConstraintSurplusClassifier.classifyIndex(live, desired, orders, false),
                "a pre-SER-G8 manifest cannot express a single index -- abstain, never default to foreign");
    }

    @Test
    void vector13_manifestKeysPresentButEmptyIsUnclassifiableNotForeign() {
        DesiredTable desired = desiredTable(List.of(), List.of());
        CurrentTable orders = table(List.of("id"), List.of(), List.of(), List.of());
        CurrentIndex live = new CurrentIndex("idx_anything", List.of("x"), false);

        assertEquals(ConstraintSurplusClassifier.Classification.UNCLASSIFIABLE,
                ConstraintSurplusClassifier.classifyIndex(live, desired, orders, false),
                "'the model declares no indexes' and 'generation did not populate them' are "
                        + "indistinguishable here -- abstain");
    }

    @Test
    void vector14_declaredForeignKeyPresentIsPlatformDeclared() {
        DesiredTable desired = desiredTable(List.of(),
                List.of(new DesiredForeignKey(List.of("customer_id"), "customers", List.of("id"))));
        CurrentForeignKey live = new CurrentForeignKey("fk_orders_customer", List.of("customer_id"), "customers", List.of("id"), null);

        assertEquals(ConstraintSurplusClassifier.Classification.PLATFORM_DECLARED,
                ConstraintSurplusClassifier.classifyForeignKey(live, desired, true));
    }

    @Test
    void vector15_foreignKeyTheDbHasAndTheModelDoesNotIsReportedAsForeign() {
        DesiredTable desired = desiredTable(List.of(), List.of());
        CurrentForeignKey live = new CurrentForeignKey("fk_legacy", List.of("legacy_id"), "legacy", List.of("id"), null);

        assertEquals(ConstraintSurplusClassifier.Classification.FOREIGN,
                ConstraintSurplusClassifier.classifyForeignKey(live, desired, true),
                "genuine drift -- report it, never propose dropping it");
    }

    // ---- helpers ----

    private static DesiredTable desiredTable(List<DesiredIndex> indexes, List<DesiredForeignKey> foreignKeys) {
        return new DesiredTable("orders", Map.of(), List.of(), null, foreignKeys, indexes);
    }

    private static CurrentTable table(List<String> primaryKeyColumns, List<CurrentUniqueConstraint> uniques,
            List<CurrentForeignKey> foreignKeys, List<CurrentIndex> indexes) {
        return new CurrentTable("orders", Map.of(), primaryKeyColumns, uniques, foreignKeys, indexes);
    }
}
