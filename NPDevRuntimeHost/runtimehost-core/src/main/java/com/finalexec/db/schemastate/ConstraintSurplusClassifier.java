package com.finalexec.db.schemastate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * S8 Wave 2 (B3 FK/index surplus detection, roadmap deferred item #2): classifies ONE live index or
 * foreign key the desired schema does not (explicitly) declare, into one of four buckets. Only
 * {@link Classification#FOREIGN} is genuine drift worth reporting — tested against the 15 vectors in
 * {@code __OutsideRepo/wave2-helpers/b3-classification-vectors.json} (mirrored verbatim as JUnit cases
 * in {@code ConstraintSurplusClassifierTest}).
 *
 * <p><b>The boundary this exists to preserve</b> (docs/ACCEPTED_BOUNDARIES.md B3): reporting every live
 * constraint the model does not enumerate would propose dropping primary-key indexes — the database
 * creates these to back a declared PK/UNIQUE, and the desired side never lists them. B3 is not lifted by
 * detecting more surplus; it is lifted by classifying correctly and reporting only genuine drift.
 *
 * <p><b>Classify by STRUCTURE, never by name.</b> H2's {@code PRIMARY_KEY_5} and Postgres's
 * {@code orders_pkey} are both {@link Classification#IMPLICIT} — and so is a foreign-looking index like
 * {@code orders_pkey_backup} that does NOT actually back a real PK/UNIQUE. The deciding question is
 * always "does this live constraint's column list match a declared index/FK, or a live PK/UNIQUE on the
 * same columns" — never its name, which is engine-generated and unstable across engines/versions.
 *
 * <p><b>Column order is significant.</b> A composite index over {@code (tenant_id, status)} is not the
 * same index as one over {@code (status, tenant_id)} for query-planning purposes, so matching here is an
 * ORDER-SENSITIVE, case-insensitive list comparison — deliberately different from
 * {@code SchemaDiffEngine}'s own order-insensitive missing-only check ({@code sameColumnSet}), which
 * asks a different question ("is this DECLARED constraint satisfied by some live one") than this class
 * asks ("is this SPECIFIC live constraint accounted for").
 */
public final class ConstraintSurplusClassifier {

    public enum Classification {
        /** Present in the desired schema's own declared FK/index list — not surplus. */
        PLATFORM_DECLARED,
        /** The database created it to back a declared PK or UNIQUE constraint — not surplus. */
        IMPLICIT,
        /** Genuine drift: a DBA index, a legacy constraint — anything the model neither declares nor
         *  the database needed to create. The only class ever reported. */
        FOREIGN,
        /** The desired schema declares no FK/index anywhere (a pre-SER-G8 manifest, or one whose
         *  declared lists are present but empty) — classification is impossible, not merely empty.
         *  X0 rule: abstain, never default to {@link #FOREIGN}. */
        UNCLASSIFIABLE
    }

    private ConstraintSurplusClassifier() {
    }

    /**
     * @param desiredSchemaExpressesConstraints false when the WHOLE desired schema (every table, not
     *         just {@code desiredTable}) declares zero FK/index entries — the manifest-level ambiguity
     *         {@code b3-preflight.py} calls PRE-G8/EMPTY-BUT-PRESENT. One table legitimately declaring
     *         no indexes while other tables in the same app declare plenty is normal, not this case —
     *         callers compute this flag once per schema, not per table.
     */
    public static Classification classifyIndex(
            CurrentIndex live,
            DesiredTable desiredTable,
            CurrentTable currentTable,
            boolean desiredSchemaExpressesConstraints
    ) {
        if (!desiredSchemaExpressesConstraints) {
            return Classification.UNCLASSIFIABLE;
        }
        for (DesiredIndex wanted : desiredTable.indexes()) {
            if (sameColumnOrder(wanted.columns(), live.columns()) && wanted.unique() == live.unique()) {
                return Classification.PLATFORM_DECLARED;
            }
        }
        if (sameColumnOrder(currentTable.primaryKeyColumns(), live.columns())) {
            return Classification.IMPLICIT;
        }
        for (CurrentUniqueConstraint unique : currentTable.uniques()) {
            if (sameColumnOrder(unique.columns(), live.columns())) {
                return Classification.IMPLICIT;
            }
        }
        return Classification.FOREIGN;
    }

    /** @param desiredSchemaExpressesConstraints see {@link #classifyIndex} — the same whole-schema flag. */
    public static Classification classifyForeignKey(
            CurrentForeignKey live,
            DesiredTable desiredTable,
            boolean desiredSchemaExpressesConstraints
    ) {
        if (!desiredSchemaExpressesConstraints) {
            return Classification.UNCLASSIFIABLE;
        }
        for (DesiredForeignKey wanted : desiredTable.foreignKeys()) {
            if (sameColumnOrder(wanted.columns(), live.columns())
                    && wanted.referencedTable() != null
                    && wanted.referencedTable().equalsIgnoreCase(live.referencedTable())) {
                return Classification.PLATFORM_DECLARED;
            }
        }
        return Classification.FOREIGN;
    }

    /** Order-SENSITIVE, case-insensitive column-list equality — see the class javadoc for why this is
     *  deliberately not {@code SchemaDiffEngine}'s own order-insensitive {@code sameColumnSet}. */
    private static boolean sameColumnOrder(List<String> a, List<String> b) {
        if (a == null || b == null || a.size() != b.size()) {
            return false;
        }
        for (int i = 0; i < a.size(); i++) {
            String left = a.get(i) == null ? "" : a.get(i).toLowerCase(Locale.ROOT);
            String right = b.get(i) == null ? "" : b.get(i).toLowerCase(Locale.ROOT);
            if (!left.equals(right)) {
                return false;
            }
        }
        return true;
    }

    /**
     * STOR-31 (boundary B3): classify EVERY live constraint of every table the desired schema also
     * declares, keyed by constraint NAME (lower-cased) -- the universe a drop request's names are
     * resolved against, so a requested name can be refused BY ITS CLASSIFICATION (an `IMPLICIT`
     * primary-key backing index must never be droppable) rather than merely "not in the surplus
     * list". Same iteration and whole-schema-abstention rule as {@code SchemaDiffEngine}
     * {@code #findSurplusConstraints}: a schema that declares zero constraints anywhere yields an
     * empty map (nothing is droppable by construction then), and a table live-but-not-desired is
     * skipped -- its disposal is the ordinary diff's business, not surplus.
     *
     * <p>A name shared by constraints with DIFFERENT classifications across tables (rare; constraint
     * names are usually per-schema-unique) is resolved conservatively: the map keeps the LAST
     * classification, and {@code ConstraintSurplusDropPlan} treats any non-FOREIGN entry as a
     * refusal -- a name the operator can prove is a DBA index on one specific table can still be
     * dropped there by naming it while it is in the surplus list, which is table-scoped.
     */
    public static Map<String, Classification> classifyLiveByName(DesiredSchema desired, CurrentSchema current) {
        Map<String, Classification> byName = new LinkedHashMap<>();
        if (!desiredExpressesConstraints(desired)) {
            return byName;
        }
        for (CurrentTable ct : current.tables().values()) {
            DesiredTable dt = desired.tables().get(ct.name());
            if (dt == null) {
                continue;
            }
            for (CurrentIndex live : ct.indexes()) {
                byName.put(nameKey(live.name()), classifyIndex(live, dt, ct, true));
            }
            for (CurrentForeignKey live : ct.foreignKeys()) {
                byName.put(nameKey(live.name()), classifyForeignKey(live, dt, true));
            }
        }
        return byName;
    }

    private static boolean desiredExpressesConstraints(DesiredSchema desired) {
        return desired.tables().values().stream()
                .anyMatch(dt -> !dt.foreignKeys().isEmpty() || !dt.indexes().isEmpty());
    }

    private static String nameKey(String name) {
        return name == null ? "" : name.toLowerCase(Locale.ROOT);
    }
}
