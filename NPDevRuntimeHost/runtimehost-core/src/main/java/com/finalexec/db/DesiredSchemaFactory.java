package com.finalexec.db;

import com.finalexec.db.schemastate.DesiredColumn;
import com.finalexec.db.schemastate.DesiredSchema;
import com.finalexec.db.schemastate.DesiredTable;
import com.finalexec.db.schemastate.DesiredUniqueConstraint;
import com.npdev.dsl.v1.schemaevolution.SqlTypeNormalization;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Builds a {@link DesiredSchema} (the model's intended shape) from a {@code SchemaManifest} + the
 * per-column {@code ColumnFacts} projection (schema-engine rebuild, Phase 2). Lives in
 * {@code com.finalexec.db} rather than {@code ...db.schemastate} because {@code ColumnFacts} and
 * {@code SchemaLifecycleExecutor.columnFactsFor} are package-private (and Java sub-packages get no
 * package access) — this is the sanctioned bridge that consumes {@code ColumnFacts} exactly as the
 * executor's class-header directive requires (never re-deriving column semantics).
 *
 * <p>Pure function, no DB, wired nowhere in Phase 2. Names are lower-cased to match {@code CurrentSchema}.
 *
 * <p><b>P0.2 asymmetry — CLOSED, SER-G8.</b> This premise held through Phase 2 but no longer does: a
 * realized {@code schema-realization-manifest.json} DOES carry explicit FK/index lists
 * ({@code businessTableForeignKeys}/{@code businessTableIndexes}, read below), and {@link DesiredSchema}
 * models them via {@link DesiredForeignKey}/{@link DesiredIndex}. {@code SchemaDiffEngine} diffs them in
 * the missing-only direction today ({@code diffForeignKeysAndIndexes}); the SURPLUS direction (a live
 * constraint the model doesn't declare) is deliberately still not auto-reported, but for a DIFFERENT
 * reason than expressibility — see {@code docs/ACCEPTED_BOUNDARIES.md} B3: reporting every extra live
 * constraint would propose dropping primary-key/unique-backing indexes, which is why surplus detection
 * needs classification (S8 Wave 2, {@code ConstraintSurplusClassifier}), not just a second diff pass.
 */
public final class DesiredSchemaFactory {

    private DesiredSchemaFactory() {
    }

    public static DesiredSchema fromManifest(SchemaLifecycleExecutor.SchemaManifest manifest) {
        Map<String, DesiredTable> tables = new LinkedHashMap<>();
        Map<String, String> tableRenames = manifest.businessTableRenames();
        Map<String, List<SchemaLifecycleExecutor.UniqueConstraintDecl>> uniquesByTable =
                manifest.businessTableUniqueConstraints();

        // Iterate businessTableColumns().keySet(), NOT businessTables(): classify() enumerates desired
        // tables from businessTableColumns().entrySet(), so this is the same desired-table set it sees.
        // (In real generated manifests they are equal; some hand-built test manifests differ, which
        // otherwise made the diff phantom-DROP a table classify actually treats as desired.)
        for (String table : manifest.businessTableColumns().keySet()) {
            Map<String, SchemaLifecycleExecutor.ColumnFacts> facts =
                    SchemaLifecycleExecutor.columnFactsFor(manifest, table);
            List<String> columnNames = manifest.businessTableColumns().getOrDefault(table, List.of());

            Map<String, DesiredColumn> columns = new LinkedHashMap<>();
            for (String rawColumn : columnNames) {
                columns.put(lower(rawColumn), toDesiredColumn(rawColumn, facts.get(rawColumn)));
            }

            List<DesiredUniqueConstraint> uniques = new ArrayList<>();
            for (SchemaLifecycleExecutor.UniqueConstraintDecl decl : uniquesByTable.getOrDefault(table, List.of())) {
                uniques.add(new DesiredUniqueConstraint(lowerAll(decl.columns())));
            }

            // SER-G8: the model's declared FKs/indexes, lower-cased to match CurrentSchema. Empty for a
            // pre-G8 manifest (the keys simply are not there), so the diff behaves exactly as before.
            List<com.finalexec.db.schemastate.DesiredForeignKey> foreignKeys = new ArrayList<>();
            for (SchemaLifecycleExecutor.ForeignKeyDecl decl
                    : manifest.businessTableForeignKeys().getOrDefault(table, List.of())) {
                foreignKeys.add(new com.finalexec.db.schemastate.DesiredForeignKey(
                        lowerAll(decl.columns()), lower(decl.referencedTable()), lowerAll(decl.referencedColumns())));
            }
            List<com.finalexec.db.schemastate.DesiredIndex> indexes = new ArrayList<>();
            for (SchemaLifecycleExecutor.IndexDecl decl
                    : manifest.businessTableIndexes().getOrDefault(table, List.of())) {
                indexes.add(new com.finalexec.db.schemastate.DesiredIndex(lowerAll(decl.columns()), decl.unique()));
            }

            String renamedFrom = tableRenames.get(table);
            tables.put(lower(table), new DesiredTable(
                    lower(table), Map.copyOf(columns), List.copyOf(uniques), lower(renamedFrom),
                    List.copyOf(foreignKeys), List.copyOf(indexes)));
        }
        return new DesiredSchema(Map.copyOf(tables));
    }

    private static DesiredColumn toDesiredColumn(String rawColumn, SchemaLifecycleExecutor.ColumnFacts facts) {
        if (facts == null) {
            // A column with no facts (shouldn't happen for a well-formed manifest) is treated as a
            // plain nullable, additive-eligible column so the diff still sees it rather than dropping it.
            return new DesiredColumn(lower(rawColumn), null, true, null, false, false, false, true, null);
        }
        // ColumnFacts javadoc: a required column that is NOT additive-eligible is a required bond/FK
        // (the only reason a required column fails additive eligibility). Nullable bonds are additive-
        // eligible and are not flagged here — a known best-effort limit (Phase 2 does not diff FKs).
        boolean bond = facts.requiredByModel() && !facts.additiveEligible();
        String declaredType = facts.declaredType();
        // Platform columns (id/version/row_version/tenant_id) are ALWAYS NOT NULL (§6), independent of
        // whether they appear in the model's required set — so nullable only when a non-platform,
        // non-required model field.
        boolean nullable = !facts.requiredByModel() && !facts.platformManaged();
        return new DesiredColumn(
                lower(rawColumn),
                declaredType == null ? null : SqlTypeNormalization.normalize(declaredType),
                nullable,
                facts.literalDefaultJson(),
                facts.platformManaged(),
                facts.requiredByModel(),
                bond,
                facts.additiveEligible(),
                lower(facts.renamedFrom()));
    }

    private static List<String> lowerAll(List<String> values) {
        List<String> out = new ArrayList<>(values.size());
        for (String value : values) {
            out.add(lower(value));
        }
        return List.copyOf(out);
    }

    private static String lower(String value) {
        return value == null ? null : value.toLowerCase(Locale.ROOT);
    }
}
