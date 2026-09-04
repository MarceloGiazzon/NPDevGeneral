package com.finalexec.db;

import com.finalexec.db.schemastate.CurrentColumn;
import com.finalexec.db.schemastate.CurrentSchema;
import com.finalexec.db.schemastate.CurrentSchemaReader;
import com.finalexec.db.schemastate.CurrentTable;
import com.finalexec.db.schemastate.DesiredSchema;
import com.finalexec.db.schemastate.DesiredTable;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.List;

/**
 * A3 (REAL_LIFT_PLAN_2026-09-03, B5 "real lift"): the compatibility verdict that replaces a blanket
 * schema-ahead refusal with "run compatibly" for the ADDITIVE case -- the newer database's own extra
 * shape (a column or table this build's manifest has never heard of). This class only ever WIDENS what
 * a schema-ahead boot may tolerate; it never overrides an existing refusal reason.
 *
 * <p><b>Both directions are checked, deliberately.</b> An earlier draft checked only "live has it,
 * desired does not" (the additive case this class exists for) and treated an EMPTY difference list as
 * compatible -- which is vacuously true for the classic REG-8 rollback shape too (a newer build DROPPED
 * a column; live is missing what THIS build still wants), silently letting a boot proceed against a
 * database this build genuinely cannot function against. Caught live by
 * {@code SchemaLifecycleExecutorDatabaseMigratedPastBuildTest}'s own pre-existing regression coverage.
 * So this class also checks "desired wants it, live does not have it" and classifies EVERY such gap
 * INCOMPATIBLE, unconditionally -- that half never had a tolerable case to begin with; a build cannot
 * read or write a column that is not there. This half is not new scope, only a bug fix within the
 * class's original intent: it must never WIDEN what refuses, but it must not accidentally NARROW it
 * either by only ever looking at one of the two directions a diff has.
 *
 * <p>A structural gap where the live schema DOES have the column, but this build ALSO cannot use it
 * as declared (e.g. a live type this build's own column cannot bind) is out of scope for both
 * directions -- SchemaDiffEngine's own type-comparison machinery is not reused here on purpose (see
 * "why not SchemaAheadAnalysis" below); this class answers exactly one question, existence, for exactly
 * the shapes A3's own projection change makes safe to tolerate (STOR-1 dialect-bound type comparisons
 * are a different, already-solved problem elsewhere).
 *
 * <h2>Why not {@link SchemaAheadAnalysis}, despite the similar vocabulary</h2>
 * That class diffs THIS build's desired schema against a STORED SNAPSHOT of the ahead fingerprint's
 * schema, in the INVERTED direction ("if I reconciled the ahead schema DOWN to my own shape, what
 * would I have to destroy") -- exactly the question a destructive DOWNGRADE decision needs, and the
 * wrong question here: booting compatibly never touches the ahead schema's extra shape at all. The
 * only question that matters is "can this build's own reads and writes, scoped to only the columns it
 * knows about (the projection change this same package made in {@code JdbcBusinessConceptStore} and
 * {@code PostgresPersistenceCapabilityAdapter}), coexist with whatever extra shape is there" --
 * answered directly from the REAL live schema ({@link CurrentSchemaReader}), never a snapshot, since
 * that is the actual shape this boot is about to run against.
 */
final class SchemaCompatibilityVerdict {

    private SchemaCompatibilityVerdict() {
    }

    enum Tolerance { TOLERABLE, INCOMPATIBLE }

    /** One piece of live shape this build's manifest does not declare, and the verdict on it.
     *  {@code column} is {@code null} for a whole extra table. */
    record Difference(String table, String column, Tolerance tolerance, String reason) {
        String describe() {
            String location = column == null ? table : table + "." + column;
            return location + " (" + tolerance + ": " + reason + ")";
        }
    }

    record Verdict(List<Difference> differences) {
        boolean compatible() {
            return differences.stream().allMatch(d -> d.tolerance() == Tolerance.TOLERABLE);
        }

        List<Difference> incompatible() {
            return differences.stream().filter(d -> d.tolerance() == Tolerance.INCOMPATIBLE).toList();
        }
    }

    /**
     * Scoped to this app's own business tables ({@link ShadowParityProbe#scopeToOwnedBusinessTables})
     * exactly like every other live-diff consumer in this package -- a platform-internal table (e.g.
     * {@code npdev_schema_history}) is never this build's manifest's concern and must not show up as
     * "extra live shape" noise in every ordinary boot's log.
     */
    static Verdict assess(DataSource dataSource, SchemaLifecycleExecutor.SchemaManifest manifest) {
        CurrentSchema liveCurrent = new CurrentSchemaReader().read(dataSource);
        CurrentSchema current = ShadowParityProbe.scopeToOwnedBusinessTables(liveCurrent, manifest);
        DesiredSchema desired = DesiredSchemaFactory.fromManifest(manifest);

        List<Difference> differences = new ArrayList<>();

        // Direction 1 (the additive case this class exists for): live has something desired does not.
        for (CurrentTable currentTable : current.tables().values()) {
            DesiredTable desiredTable = desired.tables().get(currentTable.name());
            if (desiredTable == null) {
                differences.add(new Difference(currentTable.name(), null, Tolerance.TOLERABLE,
                        "a table this build's manifest does not declare -- never referenced"));
                continue;
            }
            for (CurrentColumn currentColumn : currentTable.columns().values()) {
                if (desiredTable.columns().containsKey(currentColumn.name())) {
                    continue; // this build knows about it -- not a difference at all
                }
                boolean tolerable = currentColumn.nullable() || currentColumn.defaultValueNormalized() != null;
                differences.add(new Difference(currentTable.name(), currentColumn.name(),
                        tolerable ? Tolerance.TOLERABLE : Tolerance.INCOMPATIBLE,
                        tolerable
                                ? "nullable or has a database default -- this build's writes omit it safely"
                                : "NOT NULL with no database default -- this build's writes would omit it and "
                                        + "violate the constraint"));
            }
        }

        // Direction 2 (the bug fix): desired wants something live does not have -- a build cannot read
        // or write a column, or a whole table, that genuinely is not there. Always INCOMPATIBLE; this
        // is what keeps the classic REG-8 shape (a newer build dropped a column this OLDER build still
        // requires) refusing exactly as it always has.
        for (DesiredTable desiredTable : desired.tables().values()) {
            CurrentTable currentTable = current.tables().get(desiredTable.name());
            if (currentTable == null) {
                differences.add(new Difference(desiredTable.name(), null, Tolerance.INCOMPATIBLE,
                        "this build's manifest declares this table, but the live schema does not have it"));
                continue;
            }
            for (var desiredColumn : desiredTable.columns().values()) {
                if (currentTable.columns().containsKey(desiredColumn.name())) {
                    continue;
                }
                differences.add(new Difference(desiredTable.name(), desiredColumn.name(), Tolerance.INCOMPATIBLE,
                        "this build's manifest declares this column, but the live schema does not have it"));
            }
        }

        return new Verdict(List.copyOf(differences));
    }
}
