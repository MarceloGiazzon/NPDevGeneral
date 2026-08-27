package com.finalexec.db.datamobility;

import com.finalexec.db.schemastate.CurrentColumn;
import com.finalexec.db.schemastate.CurrentSchema;
import com.finalexec.db.schemastate.CurrentTable;
import com.finalexec.db.schemastate.DesiredColumn;
import com.finalexec.db.schemastate.DesiredSchema;
import com.finalexec.db.schemastate.DesiredTable;
import com.finalexec.db.schemastate.SafetyClass;
import com.finalexec.db.schemastate.SchemaDiff;
import com.finalexec.db.schemastate.SchemaDiffEngine;
import com.finalexec.db.schemastate.SchemaDiffItem;
import com.npdev.kernel.storage.sql.SqlDialect;
import com.npdev.kernel.storage.sql.SqlDialects;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Can a TARGET's live schema receive a SOURCE's data as-is, before any row is written?
 *
 * <p><b>Reuses {@code SchemaDiffEngine} rather than re-implementing type/shape comparison.</b> That
 * engine already answers "moving from one schema to another -- is this safe (widening/additive) or
 * destructive (narrowing/dropping)?" for the boot-time schema-evolution case (a live database moving
 * to match a newly-compiled model). This class asks almost the same question about two SNAPSHOTS --
 * "if I write SOURCE's rows into TARGET's existing tables, does TARGET's shape accommodate them?" --
 * which turns out to be the SAME diff, run in a specific direction, with a different reduction from
 * {@code SafetyClass} to a verdict. See the two subsections below; getting the direction backwards
 * silently inverts every WIDENING/NARROWING verdict, which is exactly the mistake this class's first
 * draft made and that a compile-only review could not have caught (both directions compile; only one
 * is correct).
 *
 * <h2>Which schema is {@code desired} and which is {@code current}</h2>
 *
 * <p>{@code SchemaDiffEngine.diff(desired, current)} calls {@code compareColumn}, which classifies a
 * shared column via {@code TypeChangeMatrix.classify(from = current's type, to = desired's type)}.
 * {@code WIDENING} means "{@code to} has at least the capacity of {@code from}" (e.g.
 * {@code VARCHAR(50) -> VARCHAR(100)}). We want to know "does TARGET's type have at least the
 * capacity of SOURCE's type" -- i.e. we want {@code classify(SOURCE, TARGET) == WIDENING} to mean
 * compatible. That requires passing <b>{@code current = SOURCE}</b> and
 * <b>{@code desired = TARGET}</b> (as a {@link DesiredSchema}, via {@link #adaptToDesired}) --
 * NOT the other way around. With that assignment every other {@code SafetyClass} this engine
 * produces also reduces cleanly (worked out per-case in {@link #bucket}):
 *
 * <pre>
 *   SAFE_TABLE_CREATE / SAFE_ADDITIVE   desired(=TARGET) has something current(=SOURCE) lacks
 *                                       -&gt; TARGET has a harmless EXTRA table/column
 *   DESTRUCTIVE_DROP_TABLE/COLUMN       current(=SOURCE) has something desired(=TARGET) lacks
 *                                       -&gt; TARGET is missing something SOURCE needs
 *   SAFE_WIDEN / DESTRUCTIVE_NARROW_TYPE   classify(SOURCE type, TARGET type)
 * </pre>
 *
 * <h2>The ambient-dialect coupling, and why this class works around it rather than fixing it</h2>
 *
 * <p>{@code SchemaDiffEngine}'s column-type comparison rewrites the desired side's declared type
 * through {@code SqlDialects.active().portableColumnType(...)} before comparing -- a process-wide
 * static, not a parameter. That is correct and load-bearing for the real boot-time caller (one
 * app, one engine, one dialect for the process's whole life) and this class deliberately does NOT
 * change that method: it is on the live migration-refusal path and destabilizing it is a much
 * bigger risk than anything this feature needs. Instead, {@link #check} pins
 * {@code SqlDialects.active()} to the TARGET's dialect for the duration of ONE call (save the
 * previous value, set the target's, run the diff, restore in a {@code finally}) -- correct because
 * the desired side really is TARGET's shape, so TARGET's own rewriting rules are exactly the ones
 * that should apply to it.
 *
 * <p><b>This is a real, narrow limitation, not a silent one: {@code SqlDialects.active()} is a
 * single JVM-wide field, so two concurrent {@link #check} calls for two DIFFERENT target engines on
 * two different threads race on it.</b> Correct for the common case (one process checking one
 * source/target pair at a time, or many pairs sharing the same target engine); a caller that
 * genuinely needs concurrent checks against different target engines in one process must serialize
 * calls to this method (e.g. one lock per JVM), because nothing in {@code SqlDialects} makes the
 * active dialect thread-local. Fixing that properly means threading an explicit {@code SqlDialect}
 * through {@code SchemaDiffEngine.compareColumn} instead of reading a static -- out of scope here
 * because that method is shared with the live migration-refusal path.
 *
 * <p>In practice this rewrite is a near no-op for our inputs anyway: both sides' {@code
 * normalizedSqlType} already come from LIVE catalog introspection (via {@link
 * com.finalexec.db.schemastate.CurrentSchemaReader}), not from an abstract DSL-declared type, so
 * none of the four dialects' {@code portableColumnType} special cases (JSON/JSONB, UUID, BOOLEAN,
 * TEXT, TIMESTAMP WITH TIME ZONE literals) are likely to trigger on an already-realized native
 * spelling. But "likely" is not "guaranteed" -- pinning {@code active()} to the target's real
 * dialect removes the one case where it would matter (e.g. a live Postgres {@code TEXT} column
 * silently rewritten to MySQL's {@code LONGTEXT} because the ambient dialect happened to be MySQL
 * for an unrelated reason) rather than relying on that coincidence.
 */
public final class DataMobilityStructureCheck {

    private DataMobilityStructureCheck() {
    }

    /**
     * @param source          the schema the data comes FROM, read live (e.g. via {@code
     *                        CurrentSchemaReader.read(dataSource, sourceEngineSystemSchemas)})
     * @param target          the schema the data would be written INTO, read live the same way
     * @param targetEngineKey a key {@link SqlDialects#forName} accepts ({@code "postgres"},
     *                        {@code "mysql"}, {@code "sqlserver"}, {@code "h2"}, ...) -- the engine
     *                        {@code target} actually is, used to resolve type-rewrite rules for the
     *                        duration of this call only (see class javadoc)
     * @param includeDdl      when true, a table/column present in {@code source} but missing from
     *                        {@code target} is treated as COMPATIBLE (DDL will create it, using
     *                        SOURCE's declared type, before any row is written) instead of
     *                        INCOMPATIBLE. Generating that DDL is a separate concern -- this method
     *                        only decides whether the gap is a blocker; {@link
     *                        SchemaDiffItem#before()} on a {@code DESTRUCTIVE_DROP_COLUMN} item
     *                        already carries SOURCE's normalized type for that purpose, and a
     *                        {@code DESTRUCTIVE_DROP_TABLE} item's own table can be looked up in
     *                        {@code source} for its full column list.
     */
    public static StructureCheckResult check(
            CurrentSchema source, CurrentSchema target, String targetEngineKey, boolean includeDdl) {
        SqlDialect targetDialect = SqlDialects.forName(targetEngineKey);
        SqlDialect previouslyActive = SqlDialects.active();
        SqlDialects.setActive(targetDialect);
        try {
            DesiredSchema desiredFromTarget = adaptToDesired(target);
            SchemaDiff diff = new SchemaDiffEngine().diff(desiredFromTarget, source);
            return bucket(diff, source, includeDdl);
        } finally {
            SqlDialects.setActive(previouslyActive);
        }
    }

    /**
     * The {@code SafetyClass} -&gt; verdict reduction, worked out per-case for the
     * {@code desired=TARGET, current=SOURCE} direction established in the class javadoc.
     *
     * <p><b>Keyed on {@code SafetyClass}, never on {@link SchemaDiffItem#before()}/{@link
     * SchemaDiffItem#after()} text.</b> Those two fields are populated by {@code compareColumn} for
     * DISPLAY, and for the nullability items specifically their (before, after) argument order does
     * not match {@code SchemaDiffItem}'s own "before = current side, after = desired side" contract
     * -- e.g. the {@code SAFE_RELAX} branch (which fires only when {@code desired.nullable()} is
     * true) passes {@code ("NULL", "NOT NULL")}, current-then-desired would be the reverse. That
     * looks like a pre-existing cosmetic inconsistency in {@code SchemaDiffEngine} (harmless for its
     * only real consumer, the Impact Report's free-text rendering) rather than a classification bug
     * -- the {@code SafetyClass} value itself is assigned correctly and unambiguously by the branch
     * structure, so this method never needs to parse those strings to know which side is which:
     *
     * <ul>
     *   <li>{@code SAFE_RELAX} is produced by exactly one branch: {@code desired.nullable()==true}.
     *       Under our direction that means TARGET permits NULL while SOURCE (the differing side)
     *       does not -- SOURCE's guaranteed-non-null values fit a nullable TARGET column with no
     *       risk. Always COMPATIBLE.</li>
     *   <li>{@code NEEDS_BACKFILL}/{@code NEEDS_HOOK} on a nullability mismatch is produced only by
     *       the complementary branch: {@code desired.nullable()==false} -- TARGET requires NOT NULL
     *       while SOURCE (the differing side) allows NULL. SOURCE may hand this column a NULL that
     *       TARGET's constraint rejects at insert time. Always INCOMPATIBLE -- this is a real risk,
     *       not a display quirk.</li>
     *   <li>The SAME two {@code SafetyClass} values are also produced by {@code addColumnItem} for a
     *       TARGET-only column with no SOURCE counterpart at all (an extra required column with no
     *       default). The reduction is identical either way: TARGET has a NOT-NULL obligation SOURCE
     *       does not clearly satisfy, so this is INCOMPATIBLE regardless of which of the two
     *       call sites produced it -- which is exactly why keying on {@code SafetyClass} rather than
     *       on the item-key prefix ("is this a shared column or a target-only one?") gives the more
     *       correct answer here, not a coarser one. A caller's first instinct might be "the target
     *       only ever has HARMLESS extras" -- false in this one case, because {@link
     *       #adaptToDesired} sets every adapted column's {@code literalDefault} to {@code null}
     *       (see its own javadoc), so a real database DEFAULT on a live TARGET column is invisible
     *       here and such a column is conservatively treated as unsatisfiable. That is a deliberate
     *       false-negative (reports INCOMPATIBLE when the live default would actually make the
     *       write succeed), not a wrong-direction bug -- safe to ship, and worth revisiting if this
     *       proves noisy in practice.</li>
     *   <li>{@code MANUAL_REVIEW} is never actually emitted by {@code SchemaDiffEngine.compareColumn}
     *       today (its type-change branch collapses {@code TypeChangeMatrix.Classification
     *       .INCOMPARABLE} into {@code DESTRUCTIVE_NARROW_TYPE}, not a separate case) -- handled
     *       defensively here as INCOMPATIBLE (an engine that could not decide should not be read as
     *       "fine") in case a future engine version starts emitting it.</li>
     *   <li>{@code SAFE_RENAME} can never fire from an adapted schema: {@link #adaptToDesired} always
     *       sets {@code renamedFromTable}/{@code renamedFromColumn} to {@code null}, and that field
     *       being non-null is the only way {@code SchemaDiffEngine.diff} produces a rename item.
     *       Included in the COMPATIBLE bucket below only for completeness/future-proofing.</li>
     * </ul>
     */
    private static StructureCheckResult bucket(SchemaDiff diff, CurrentSchema source, boolean includeDdl) {
        if (diff.isEmpty()) {
            return StructureCheckResult.equal();
        }
        List<String> incompatible = new ArrayList<>();
        List<String> compatible = new ArrayList<>();
        for (SchemaDiffItem item : diff.items()) {
            // A missing FK/index never blocks a row insert either direction -- SchemaDiffEngine
            // classifies both SAFE_ADDITIVE, indistinguishable from a genuinely harmless extra
            // column by SafetyClass alone, so they are filtered by item-key prefix instead.
            if (item.itemKey().startsWith("ADD_FOREIGN_KEY:") || item.itemKey().startsWith("ADD_INDEX:")) {
                continue;
            }
            switch (item.safetyClass()) {
                case SAFE_TABLE_CREATE, SAFE_ADDITIVE, SAFE_RELAX, SAFE_WIDEN, SAFE_RENAME ->
                        compatible.add(describeHarmless(item));
                case DESTRUCTIVE_DROP_TABLE, DESTRUCTIVE_DROP_COLUMN -> {
                    if (includeDdl) {
                        compatible.add(describeWillBeCreated(item, source));
                    } else {
                        incompatible.add(describeMissingOnTarget(item));
                    }
                }
                case DESTRUCTIVE_NARROW_TYPE -> incompatible.add(describeTypeTooNarrow(item));
                case NEEDS_BACKFILL, NEEDS_HOOK -> incompatible.add(describeUnsatisfiedNotNull(item));
                case MANUAL_REVIEW -> incompatible.add(describeManualReview(item));
            }
        }
        if (!incompatible.isEmpty()) {
            return StructureCheckResult.incompatible(incompatible);
        }
        if (compatible.isEmpty()) {
            // Everything present was an ADD_FOREIGN_KEY/ADD_INDEX item, filtered above -- for our
            // purposes (can source's rows be written into target?) that is indistinguishable from
            // no difference at all.
            return StructureCheckResult.equal();
        }
        return StructureCheckResult.compatible(compatible);
    }

    private static String describeHarmless(SchemaDiffItem item) {
        return location(item) + ": " + item.safetyClass() + " -- harmless (target has this, source does not)";
    }

    private static String describeWillBeCreated(SchemaDiffItem item, CurrentSchema source) {
        if (item.safetyClass() == SafetyClass.DESTRUCTIVE_DROP_TABLE) {
            CurrentTable sourceTable = source.tables().get(item.table());
            String columns = sourceTable == null ? "" : summarizeColumns(sourceTable);
            return item.table() + ": missing on target, will be created before import (source columns: "
                    + columns + ")";
        }
        return location(item) + ": missing on target, will be added before import (source type: "
                + item.before() + ")";
    }

    private static String describeMissingOnTarget(SchemaDiffItem item) {
        if (item.safetyClass() == SafetyClass.DESTRUCTIVE_DROP_TABLE) {
            return item.table() + ": table exists in source but not on target "
                    + "(rerun with includeDdl to create it before import, or add it manually)";
        }
        return location(item) + ": column exists in source (type " + item.before() + ") but not on target "
                + "(rerun with includeDdl to add it before import, or add it manually)";
    }

    private static String describeTypeTooNarrow(SchemaDiffItem item) {
        return location(item) + ": target type " + item.after() + " cannot hold every value of source type "
                + item.before();
    }

    private static String describeUnsatisfiedNotNull(SchemaDiffItem item) {
        return location(item) + ": target requires a value (NOT NULL, no known default) that source "
                + "cannot guarantee -- source is nullable, or this column does not exist in source at all";
    }

    private static String describeManualReview(SchemaDiffItem item) {
        return location(item) + ": the diff engine could not classify this change automatically";
    }

    private static String location(SchemaDiffItem item) {
        return item.column() == null ? item.table() : item.table() + "." + item.column();
    }

    private static String summarizeColumns(CurrentTable table) {
        List<String> parts = new ArrayList<>();
        for (CurrentColumn column : table.columns().values()) {
            parts.add(column.name() + " " + column.normalizedSqlType());
        }
        return String.join(", ", parts);
    }

    // ------------------------------------------------------------------ CurrentSchema -> DesiredSchema

    /**
     * Wraps a live {@link CurrentSchema} (read straight off a real connection, no compiled model
     * involved) as a {@link DesiredSchema} so it can play the "desired" role in {@code
     * SchemaDiffEngine.diff} -- see the class javadoc for why the TARGET, specifically, needs to be
     * the desired side.
     *
     * <p>Field defaults for the desired-only information a bare {@link CurrentColumn} does not
     * carry: {@code literalDefault=null} (a live database DEFAULT clause is deliberately not
     * threaded through here -- see {@link #bucket}'s {@code NEEDS_HOOK} case for the one place this
     * makes the check more conservative than it has to be), {@code platformManaged=false} (this is
     * a live table, not a manifest-declared platform column), {@code requiredByModel = !nullable}
     * (the live NOT NULL constraint IS the requirement), {@code bond=false}, {@code
     * additiveEligible=true} (irrelevant here -- {@link #bucket} does not distinguish {@code
     * NEEDS_HOOK} produced via {@code additiveEligible=false} from any other {@code NEEDS_HOOK}),
     * {@code renamedFromColumn=null} (a live schema carries no rename history). Table-level:
     * {@code renamedFromTable=null}, {@code uniques=List.of()}, {@code foreignKeys=List.of()},
     * {@code indexes=List.of()} (FK/index diff items are filtered out by {@link #bucket} anyway).
     */
    static DesiredSchema adaptToDesired(CurrentSchema schema) {
        Map<String, DesiredTable> tables = new LinkedHashMap<>();
        for (Map.Entry<String, CurrentTable> entry : schema.tables().entrySet()) {
            tables.put(entry.getKey(), adaptTable(entry.getValue()));
        }
        return new DesiredSchema(Map.copyOf(tables));
    }

    private static DesiredTable adaptTable(CurrentTable table) {
        Map<String, DesiredColumn> columns = new LinkedHashMap<>();
        for (Map.Entry<String, CurrentColumn> entry : table.columns().entrySet()) {
            columns.put(entry.getKey(), adaptColumn(entry.getValue()));
        }
        return new DesiredTable(table.name(), Map.copyOf(columns), List.of(), null);
    }

    private static DesiredColumn adaptColumn(CurrentColumn column) {
        boolean nullable = column.nullable();
        return new DesiredColumn(
                column.name(),
                column.normalizedSqlType(),
                nullable,
                null,
                false,
                !nullable,
                false,
                true,
                null);
    }
}
