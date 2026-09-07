package com.finalexec.db.schemastate;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * STOR-31 (boundary B3, POSTURAL_LIFT_PLAN_2026-09-07.md package P3): the pure planning half of
 * the surplus drop path. The B3 rule, pinned executable: only FOREIGN-classified surplus
 * constraints are droppable, everything else is refused by its CLASSIFICATION (never by name), and
 * a name not on the live database at all is refused rather than guessed at. Vectors 3/4 (H2's
 * {@code PRIMARY_KEY_5}, Postgres's {@code orders_pkey} -- the two that exist specifically to pin
 * the failure this boundary prevents) must come back refused with their IMPLICIT classification in
 * the code, which is the visible proof the drop path cannot propose dropping a primary key.
 */
class ConstraintSurplusDropPlanTest {

    private static final ConstraintSurplusReport SURPLUS_REPORT = new ConstraintSurplusReport(List.of(
            new SurplusConstraint("orders", "INDEX", "idx_dba_created_at", List.of("created_at"), false, null),
            new SurplusConstraint("orders", "FOREIGN_KEY", "fk_legacy_customer", List.of("customer_id"), false, "customers")
    ), List.of());

    private static final Map<String, ConstraintSurplusClassifier.Classification> LIVE_BY_NAME = Map.of(
            "primary_key_5", ConstraintSurplusClassifier.Classification.IMPLICIT,
            "orders_pkey", ConstraintSurplusClassifier.Classification.IMPLICIT,
            "ord_refund_uk", ConstraintSurplusClassifier.Classification.IMPLICIT,
            "idx_dba_created_at", ConstraintSurplusClassifier.Classification.FOREIGN,
            "fk_legacy_customer", ConstraintSurplusClassifier.Classification.FOREIGN,
            "orders_pkey_backup", ConstraintSurplusClassifier.Classification.FOREIGN
    );

    @Test
    void vector3And4_primaryKeyBackingIndexesAreRefusedByClassificationNotByName() {
        // H2's PRIMARY_KEY_5 and Postgres's orders_pkey: the pin that a drop path must refuse an
        // IMPLICIT primary-key backing index BY CLASSIFICATION -- a name-based matcher would pass
        // them straight through to a drop, which is the exact failure B3 exists to prevent.
        ConstraintSurplusDropPlan.Plan plan = ConstraintSurplusDropPlan.plan(
                SURPLUS_REPORT, LIVE_BY_NAME, List.of("PRIMARY_KEY_5", "orders_pkey"));

        assertTrue(plan.droppable().isEmpty(), "nothing may be dropped: " + plan.droppable());
        assertEquals(2, plan.refused().size(), plan.refused().toString());
        ConstraintSurplusDropPlan.Refusal pk5 = plan.refused().get(0);
        assertEquals("PRIMARY_KEY_5", pk5.name());
        assertEquals("B3:surplus_not_foreign:PRIMARY_KEY_5:IMPLICIT", pk5.code(),
                "the refusal must carry the classification, so the operator can see WHY it is not droppable");
        assertEquals("B3:surplus_not_foreign:orders_pkey:IMPLICIT", plan.refused().get(1).code());
    }

    @Test
    void implicitUniqueBackingIndexIsRefusedByClassification() {
        ConstraintSurplusDropPlan.Plan plan = ConstraintSurplusDropPlan.plan(
                SURPLUS_REPORT, LIVE_BY_NAME, List.of("ord_refund_uk"));

        assertTrue(plan.droppable().isEmpty());
        assertEquals("B3:surplus_not_foreign:ord_refund_uk:IMPLICIT", plan.refused().get(0).code());
    }

    @Test
    void unclassifiableNameIsRefusedNotGuessedAt() {
        ConstraintSurplusDropPlan.Plan plan = ConstraintSurplusDropPlan.plan(
                SURPLUS_REPORT, Map.of("idx_anything", ConstraintSurplusClassifier.Classification.UNCLASSIFIABLE),
                List.of("idx_anything"));

        assertTrue(plan.droppable().isEmpty());
        assertEquals("B3:surplus_not_foreign:idx_anything:UNCLASSIFIABLE", plan.refused().get(0).code(),
                "an abstention is never a licence -- the X0 rule holds on the drop path too");
    }

    @Test
    void foreignSurplusNamesAreDroppableWithTheirExactLiveShape() {
        ConstraintSurplusDropPlan.Plan plan = ConstraintSurplusDropPlan.plan(
                SURPLUS_REPORT, LIVE_BY_NAME, List.of("idx_dba_created_at", "fk_legacy_customer"));

        assertTrue(plan.refused().isEmpty(), plan.refused().toString());
        assertEquals(2, plan.droppable().size());
        ConstraintSurplusDropPlan.Droppable index = plan.droppable().get(0);
        assertEquals("INDEX", index.kind());
        assertEquals("orders", index.table());
        assertEquals("idx_dba_created_at", index.liveName());
        assertEquals(List.of("created_at"), index.columns());
        ConstraintSurplusDropPlan.Droppable foreignKey = plan.droppable().get(1);
        assertEquals("FOREIGN_KEY", foreignKey.kind());
        assertEquals("customers", foreignKey.referencedTable());
        assertEquals("FOREIGN_KEY orders.fk_legacy_customer", foreignKey.stableKey(),
                "the stable key the dropToken hashes -- lower-cased, kind + table + name");
    }

    @Test
    void nameNotPresentAnywhereIsRefusedAsNotPresent() {
        ConstraintSurplusDropPlan.Plan plan = ConstraintSurplusDropPlan.plan(
                SURPLUS_REPORT, LIVE_BY_NAME, List.of("some_index_from_a_hand_written_sql_console"));

        assertTrue(plan.droppable().isEmpty());
        assertEquals("B3:surplus_not_present:some_index_from_a_hand_written_sql_console",
                plan.refused().get(0).code());
    }

    @Test
    void nameMatchingIsCaseInsensitiveLikeTheClassifierItself() {
        ConstraintSurplusDropPlan.Plan plan = ConstraintSurplusDropPlan.plan(
                SURPLUS_REPORT, LIVE_BY_NAME, List.of("IDX_DBA_CREATED_AT"));

        assertTrue(plan.refused().isEmpty(), plan.refused().toString());
        assertEquals(1, plan.droppable().size());
        assertEquals("idx_dba_created_at", plan.droppable().get(0).liveName());
    }

    @Test
    void aSurplusMatchWinsOverAnImplicitClassificationOfTheSameName() {
        // The operator proves the name is a DBA index on a specific table; the surplus list is
        // table-scoped, so naming it while it IS in the surplus list drops exactly that object.
        ConstraintSurplusReport report = new ConstraintSurplusReport(List.of(
                new SurplusConstraint("orders", "INDEX", "dup", List.of("created_at"), false, null)
        ), List.of());
        ConstraintSurplusDropPlan.Plan plan = ConstraintSurplusDropPlan.plan(
                report, Map.of("dup", ConstraintSurplusClassifier.Classification.IMPLICIT), List.of("dup"));

        assertEquals(1, plan.droppable().size());
        assertEquals("orders", plan.droppable().get(0).table());
        assertTrue(plan.refused().isEmpty());
    }

    @Test
    void emptyOrBlankRequestIsAnEmptyPlan() {
        ConstraintSurplusDropPlan.Plan none = ConstraintSurplusDropPlan.plan(SURPLUS_REPORT, LIVE_BY_NAME, List.of());
        assertTrue(none.droppable().isEmpty() && none.refused().isEmpty());
        assertTrue(ConstraintSurplusDropPlan.plan(SURPLUS_REPORT, LIVE_BY_NAME, null).refused().isEmpty());
        ConstraintSurplusDropPlan.Plan blanks =
                ConstraintSurplusDropPlan.plan(SURPLUS_REPORT, LIVE_BY_NAME, List.of("  ", ""));
        assertTrue(blanks.droppable().isEmpty() && blanks.refused().isEmpty(), "blank names are skipped, "
                + "never refused -- a typo'd empty entry is not a diagnostic");
    }
}