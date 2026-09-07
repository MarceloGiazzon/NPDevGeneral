package com.finalexec.db;

import com.finalexec.db.schemastate.ConstraintSurplusDropPlan;
import com.npdev.kernel.storage.sql.SqlDialect;
import com.npdev.kernel.storage.sql.SqlDialects;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * STOR-31 (boundary B3, POSTURAL_LIFT_PLAN_2026-09-07.md package P3): executes a
 * {@link ConstraintSurplusDropPlan.Plan} against the live database. For each droppable constraint:
 * (a) FIRST records its full current shape into {@code npdev_schema_history} as a
 * {@code SURPLUS_DROPPED} row -- the DDL to re-create the constraint by hand travels in the audit
 * trail, so a dropped DBA index is never a one-way door with no record -- then (b) issues the
 * dialect-guarded drop (one statement per constraint, through {@link SqlDialect}, never inline SQL).
 *
 * <p>Stops at the first failure and reports which constraints had ALREADY been dropped before it
 * — the same honesty every destructive pass in this codebase is held to (a partial execution is
 * reported as partial, never as done). History rows are written with a custom {@code SURPLUS_DROPPED}
 * outcome and NULL fingerprints on purpose: readers like {@code atOrPastFingerprint} must never
 * mistake an operator's itemized drop for a schema migration advancing the build fingerprint, and
 * {@code DatabaseMetaData} introspection is unchanged by a constraint fewer.
 */
public final class ConstraintSurplusDropExecutor {

    /** What the run did: every constraint successfully dropped, the re-create hints recorded for
     *  it, and the first failure, if any (which also means {@code dropped} is everything that went
     *  before it). */
    public record DropOutcome(List<String> dropped, List<String> recreateHints, String failure) {

        public boolean hadFailure() {
            return failure != null;
        }
    }

    private ConstraintSurplusDropExecutor() {
    }

    public static DropOutcome execute(DataSource dataSource, ConstraintSurplusDropPlan.Plan plan) {
        List<String> dropped = new ArrayList<>();
        List<String> recreateHints = new ArrayList<>();
        for (ConstraintSurplusDropPlan.Droppable droppable : plan.droppable()) {
            String hint = recreateHint(droppable);
            // Record BEFORE dropping -- the audit trail must exist even if the drop then fails.
            SchemaHistoryStore.insertRawHistoryRow(dataSource, null, null, "SURPLUS_DROPPED",
                    List.of(hint), "SURPLUS_DROPPED");
            try {
                dropOne(dataSource, droppable);
            } catch (Exception failure) {
                return new DropOutcome(List.copyOf(dropped), List.copyOf(recreateHints),
                        "drop of " + droppable.liveName() + " on " + droppable.table() + " failed: "
                        + failure.getMessage() + " -- " + dropped.size() + " constraint(s) were already "
                        + "dropped (their SURPLUS_DROPPED history rows carry the re-create DDL)");
            }
            dropped.add(droppable.stableKey());
            recreateHints.add(hint);
        }
        return new DropOutcome(List.copyOf(dropped), List.copyOf(recreateHints), null);
    }

    private static void dropOne(DataSource dataSource, ConstraintSurplusDropPlan.Droppable droppable)
            throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            SqlDialect dialect = SqlDialects.forConnection(connection);
            String sql = "FOREIGN_KEY".equals(droppable.kind())
                    ? dialect.guardedDropConstraint(droppable.liveName(), droppable.table())
                    : dialect.guardedDropIndexIfExists(droppable.liveName(), droppable.table());
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.executeUpdate();
            }
        }
    }

    /** The human-readable, re-create-by-hand text that rides in the history row's items_json. */
    private static String recreateHint(ConstraintSurplusDropPlan.Droppable droppable) {
        String columns = String.join(", ", droppable.columns());
        if ("FOREIGN_KEY".equals(droppable.kind())) {
            return "SURPLUS_DROPPED foreign key " + droppable.table() + "." + droppable.liveName()
                    + " columns=[" + columns + "] referencedTable=" + droppable.referencedTable()
                    + "; re-create by hand: ALTER TABLE " + droppable.table() + " ADD CONSTRAINT "
                    + droppable.liveName() + " FOREIGN KEY (" + columns + ") REFERENCES "
                    + droppable.referencedTable();
        }
        return "SURPLUS_DROPPED index " + droppable.table() + "." + droppable.liveName()
                + " columns=[" + columns + "] unique=" + droppable.unique()
                + "; re-create by hand: CREATE " + (droppable.unique() ? "UNIQUE " : "")
                + "INDEX " + droppable.liveName() + " ON " + droppable.table() + " (" + columns + ")";
    }
}