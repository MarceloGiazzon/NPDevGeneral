package com.finalexec.db;

import com.finalexec.db.schemastate.SafetyClass;
import com.finalexec.db.schemastate.SchemaDiffItem;
import com.npdev.dsl.v1.schemaevolution.DestructiveAckToken;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * B5-B (boundary-lift 2026-09-02, package 4.1). Pure, no-DB coverage of
 * {@link ReverseMigrationPlanner#classify} -- the same isolation {@code SchemaAheadAnalysisTest} uses
 * for {@code resolutionFor}, one layer up: given a hand-built diff item list (as if it came from a real
 * {@code SchemaDiffEngine.diff} call), does classification pick the right {@link ReverseMigrationPlanner.Plan}?
 */
class ReverseMigrationPlannerTest {

    private static final String SCHEMA_FINGERPRINT = "build-n";
    private static final String AHEAD_FINGERPRINT = "build-n-plus-1";

    @Test
    void pureSupersetDiffIsReadyWithTheSameTokenTheForwardPathWouldCompute() {
        SchemaDiffItem droppedColumn = SchemaDiffItem.of("DROP_COLUMN:widgets:notes", "widgets", "notes",
                SafetyClass.DESTRUCTIVE_DROP_COLUMN, "VARCHAR(255)", null);
        SchemaDiffItem droppedTable = SchemaDiffItem.of("DROP_TABLE:audit_log", "audit_log", null,
                SafetyClass.DESTRUCTIVE_DROP_TABLE, "audit_log", null);

        ReverseMigrationPlanner.Plan plan = ReverseMigrationPlanner.classify(
                List.of(droppedColumn, droppedTable), SCHEMA_FINGERPRINT, AHEAD_FINGERPRINT);

        ReverseMigrationPlanner.Ready ready = assertInstanceOf(ReverseMigrationPlanner.Ready.class, plan);
        assertEquals(List.of(droppedColumn, droppedTable), ready.items());
        assertEquals(AHEAD_FINGERPRINT, ready.aheadFingerprint());
        String expectedToken = DestructiveAckToken.compute(SCHEMA_FINGERPRINT,
                List.of(droppedColumn.itemKey(), droppedTable.itemKey()));
        assertEquals(expectedToken, ready.ackToken());
    }

    @Test
    void anythingThisBuildIsMissingBlocksTheWholePlanAsAmbiguous() {
        SchemaDiffItem droppedColumn = SchemaDiffItem.of("DROP_COLUMN:widgets:notes", "widgets", "notes",
                SafetyClass.DESTRUCTIVE_DROP_COLUMN, "VARCHAR(255)", null);
        // Shaped like a rename this build doesn't declare: the live schema has "baz" (destructive drop
        // from this build's angle) but this build also wants "bar" the live schema no longer has --
        // exactly the case a plain drop would silently lose data a rename would have preserved.
        SchemaDiffItem missingColumn = SchemaDiffItem.of("ADD_COLUMN:widgets:bar", "widgets", "bar",
                SafetyClass.SAFE_ADDITIVE, null, "VARCHAR(255)");

        ReverseMigrationPlanner.Plan plan = ReverseMigrationPlanner.classify(
                List.of(droppedColumn, missingColumn), SCHEMA_FINGERPRINT, AHEAD_FINGERPRINT);

        ReverseMigrationPlanner.Blocked blocked = assertInstanceOf(ReverseMigrationPlanner.Blocked.class, plan);
        assertTrue(blocked.reason().contains("widgets.bar"), "reason should name the missing item: " + blocked.reason());
    }

    @Test
    void typeNarrowingBlocksTheWholePlanAsOutOfScope() {
        // BOUNDARY_LIFT_PLAN_2026-09-02.md package 4.1's own done-when: type narrowing "stay[s] out of
        // scope and stay[s] refused" -- unlike a straight drop, it can truncate data on the way down.
        SchemaDiffItem droppedColumn = SchemaDiffItem.of("DROP_COLUMN:widgets:notes", "widgets", "notes",
                SafetyClass.DESTRUCTIVE_DROP_COLUMN, "VARCHAR(255)", null);
        SchemaDiffItem narrowedType = SchemaDiffItem.of("NARROW:widgets:sku", "widgets", "sku",
                SafetyClass.DESTRUCTIVE_NARROW_TYPE, "VARCHAR(255)", "VARCHAR(10)");

        ReverseMigrationPlanner.Plan plan = ReverseMigrationPlanner.classify(
                List.of(droppedColumn, narrowedType), SCHEMA_FINGERPRINT, AHEAD_FINGERPRINT);

        ReverseMigrationPlanner.Blocked blocked = assertInstanceOf(ReverseMigrationPlanner.Blocked.class, plan);
        assertTrue(blocked.reason().contains("widgets.sku"), "reason should name the narrowed item: " + blocked.reason());
        assertTrue(blocked.reason().contains("out of scope"), blocked.reason());
    }

    @Test
    void onlyProceedIgnoringItemsMeansNothingToDo() {
        SchemaDiffItem extraIndex = SchemaDiffItem.of("ADD_INDEX:widgets:sku", "widgets", "sku",
                SafetyClass.SAFE_ADDITIVE, null, "sku");

        ReverseMigrationPlanner.Plan plan = ReverseMigrationPlanner.classify(
                List.of(extraIndex), SCHEMA_FINGERPRINT, AHEAD_FINGERPRINT);

        assertInstanceOf(ReverseMigrationPlanner.NothingToDo.class, plan);
    }

    @Test
    void emptyDiffMeansNothingToDo() {
        ReverseMigrationPlanner.Plan plan = ReverseMigrationPlanner.classify(
                List.of(), SCHEMA_FINGERPRINT, AHEAD_FINGERPRINT);

        assertInstanceOf(ReverseMigrationPlanner.NothingToDo.class, plan);
    }
}
