package com.finalexec.db;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * STOR-18 (docs/ACCEPTED_BOUNDARIES.md B9): pure tests for {@link SchemaDropSnapshotRestorePlan} --
 * no DataSource, no H2, exactly the "unit-testable without a real database" property the class was
 * written to have. The live-table-existence preflight has its own DataSource-backed coverage on
 * {@link SchemaDropSnapshotRestorer#missingLiveTables}.
 */
class SchemaDropSnapshotRestorePlanTest {

    private static SchemaLifecycleExecutor.ForeignKeyDecl fk(String referencedTable) {
        return new SchemaLifecycleExecutor.ForeignKeyDecl(List.of("ref_id"), referencedTable, List.of("id"));
    }

    @Test
    void nullRequestedResolvesToEveryTableInSnapshot() {
        var plan = SchemaDropSnapshotRestorePlan.resolve(List.of("orders", "customers"), null, Map.of());
        assertEquals(List.of(), plan.requestedButNotInSnapshot());
        assertEquals(Set.copyOf(List.of("orders", "customers")), Set.copyOf(plan.orderedTables()));
    }

    @Test
    void literalAllResolvesToEveryTableInSnapshot() {
        var plan = SchemaDropSnapshotRestorePlan.resolve(
                List.of("orders", "customers"), List.of("ALL"), Map.of());
        assertEquals(List.of(), plan.requestedButNotInSnapshot());
        assertEquals(Set.copyOf(List.of("orders", "customers")), Set.copyOf(plan.orderedTables()));
    }

    @Test
    void explicitSubsetRestoresOnlyWhatWasNamed() {
        var plan = SchemaDropSnapshotRestorePlan.resolve(
                List.of("orders", "customers", "invoices"), List.of("orders", "customers"), Map.of());
        assertEquals(List.of(), plan.requestedButNotInSnapshot());
        assertEquals(Set.copyOf(List.of("orders", "customers")), Set.copyOf(plan.orderedTables()));
    }

    @Test
    void requestedTableAbsentFromSnapshotIsReportedNotSilentlyDropped() {
        var plan = SchemaDropSnapshotRestorePlan.resolve(
                List.of("orders"), List.of("orders", "ghost_table"), Map.of());
        assertEquals(List.of("ghost_table"), plan.requestedButNotInSnapshot());
        assertEquals(List.of("orders"), plan.orderedTables());
    }

    @Test
    void parentTableOrdersBeforeItsForeignKeyChild() {
        Map<String, List<SchemaLifecycleExecutor.ForeignKeyDecl>> fks =
                Map.of("orders", List.of(fk("customers")));
        // Snapshot lists the child before the parent -- the plan must still order parent first.
        var plan = SchemaDropSnapshotRestorePlan.resolve(List.of("orders", "customers"), null, fks);
        assertEquals(List.of("customers", "orders"), plan.orderedTables());
    }

    @Test
    void chainOfThreeOrdersRootFirst() {
        Map<String, List<SchemaLifecycleExecutor.ForeignKeyDecl>> fks = Map.of(
                "line_items", List.of(fk("orders")),
                "orders", List.of(fk("customers")));
        var plan = SchemaDropSnapshotRestorePlan.resolve(
                List.of("line_items", "orders", "customers"), null, fks);
        assertEquals(List.of("customers", "orders", "line_items"), plan.orderedTables());
    }

    @Test
    void foreignKeyToATableOutsideTheRestoreSetIsNotAnOrderingConstraint() {
        // "orders" FKs to "customers", but customers was NOT requested -- assumed already live, so
        // orders must still appear (not blocked/dropped) and the plan must not fail.
        Map<String, List<SchemaLifecycleExecutor.ForeignKeyDecl>> fks =
                Map.of("orders", List.of(fk("customers")));
        var plan = SchemaDropSnapshotRestorePlan.resolve(List.of("orders"), List.of("orders"), fks);
        assertEquals(List.of("orders"), plan.orderedTables());
    }

    @Test
    void selfReferencingForeignKeyDoesNotDeadlockTheOrdering() {
        Map<String, List<SchemaLifecycleExecutor.ForeignKeyDecl>> fks =
                Map.of("categories", List.of(fk("categories")));
        var plan = SchemaDropSnapshotRestorePlan.resolve(List.of("categories"), null, fks);
        assertEquals(List.of("categories"), plan.orderedTables());
    }

    @Test
    void aCycleStillProducesEveryTableRatherThanHangingOrThrowing() {
        Map<String, List<SchemaLifecycleExecutor.ForeignKeyDecl>> fks = Map.of(
                "a", List.of(fk("b")),
                "b", List.of(fk("a")));
        var plan = SchemaDropSnapshotRestorePlan.resolve(List.of("a", "b"), null, fks);
        assertEquals(Set.copyOf(List.of("a", "b")), Set.copyOf(plan.orderedTables()));
        assertEquals(2, plan.orderedTables().size());
    }

    @Test
    void emptySnapshotProducesAnEmptyPlan() {
        var plan = SchemaDropSnapshotRestorePlan.resolve(List.of(), null, Map.of());
        assertTrue(plan.orderedTables().isEmpty());
        assertTrue(plan.requestedButNotInSnapshot().isEmpty());
    }
}
